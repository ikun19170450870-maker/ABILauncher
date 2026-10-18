package com.abi.launcher.download

import com.abi.launcher.launch.JavaRuntime
import com.abi.launcher.launch.JavaRuntimeLocator
import com.abi.launcher.launch.VersionSpecReader
import com.abi.launcher.launch.readJsonObject
import com.abi.launcher.launch.scalar
import com.abi.launcher.launch.str
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile

data class LoaderInstallResult(
    val ok: Boolean,
    /** 装好之后应该启动的版本号（如 fabric-loader-0.19.3-1.21.8 或 1.20.1-forge-47.2.0） */
    val versionId: String?,
    val message: String,
)

/**
 * 模组加载器安装。
 *
 * 两种路线：
 *  - Fabric / Quilt：从元数据 API 拿一份**完整的版本 JSON**（内部带 inheritsFrom 指向原版），
 *    存成 versions/<id>/<id>.json，然后按它下载依赖库即可 —— 不需要跑任何安装器。
 *  - Forge / NeoForge：下载官方安装器 jar 并运行（安装器自己会生成版本 JSON 并铺库），
 *    之后我们再按生成的 JSON 补齐缺失的库。
 */
class ModLoaderInstaller(private val downloader: HttpDownloader = HttpDownloader()) {

    fun install(
        gameVersion: String,
        officialGameJsonUrl: String?,
        loader: ModLoader,
        loaderVersion: String?,
        gameRoot: Path,
        source: DownloadSource,
        onLog: (String) -> Unit = {},
        onProgress: (done: Int, total: Int, current: String) -> Unit = { _, _, _ -> },
        cancelled: () -> Boolean = { false },
    ): LoaderInstallResult {
        // 1. 先把原版装好：客户端 jar、依赖库、资源索引与资源文件
        onLog("[加载器] 第 1 步：安装原版 $gameVersion")
        val vanilla = GameInstaller(downloader).install(
            versionId = gameVersion,
            officialVersionJsonUrl = officialGameJsonUrl,
            gameRoot = gameRoot,
            source = source,
            onLog = onLog,
            onProgress = onProgress,
            cancelled = cancelled,
        )
        if (!vanilla.ok) {
            return LoaderInstallResult(false, gameVersion, "原版安装未完成，已中止加载器安装")
        }
        if (loader == ModLoader.Vanilla) {
            return LoaderInstallResult(true, gameVersion, "原版 $gameVersion 安装完成")
        }

        return when (loader) {
            ModLoader.Fabric, ModLoader.Quilt -> installFromProfile(
                gameVersion, loader, loaderVersion, gameRoot, source, onLog, onProgress, cancelled,
            )
            ModLoader.Forge, ModLoader.NeoForge -> installWithInstaller(
                gameVersion, loader, loaderVersion, gameRoot, source, onLog, onProgress, cancelled,
            )
            ModLoader.Vanilla -> LoaderInstallResult(true, gameVersion, "原版")
        }
    }

    // ---------------------------------------------------------------- Fabric / Quilt

    private fun installFromProfile(
        gameVersion: String,
        loader: ModLoader,
        loaderVersion: String?,
        gameRoot: Path,
        source: DownloadSource,
        onLog: (String) -> Unit,
        onProgress: (Int, Int, String) -> Unit,
        cancelled: () -> Boolean,
    ): LoaderInstallResult {
        val version = loaderVersion
            ?: runCatching { ModLoaderRepository.listLoaderVersions(loader, gameVersion, source, downloader).firstOrNull()?.version }
                .getOrNull()
            ?: return LoaderInstallResult(false, null, "无法获取 ${loader.displayName} 的加载器版本列表（网络或镜像不通）")

        val url = when (loader) {
            ModLoader.Fabric -> ModLoaderRepository.fabricProfileUrl(gameVersion, version, source)
            else -> ModLoaderRepository.quiltProfileUrl(gameVersion, version, source)
        }
        val alternate = when (loader) {
            ModLoader.Fabric -> ModLoaderRepository.fabricProfileUrl(gameVersion, version, DownloadSource.alternateOf(source))
            else -> ModLoaderRepository.quiltProfileUrl(gameVersion, version, DownloadSource.alternateOf(source))
        }

        onLog("[加载器] 第 2 步：获取 ${loader.displayName} $version 的版本 JSON")
        val profileJson = runCatching { downloader.fetchText(url, alternates = listOf(alternate)) }
            .getOrElse { return LoaderInstallResult(false, null, "获取 ${loader.displayName} 版本 JSON 失败：${it.message}") }

        val fallbackId = "${loader.displayName.lowercase()}-loader-$version-$gameVersion"
        val versionId = ModLoaderRepository.profileVersionId(profileJson, fallbackId)
        MojangRepository.saveVersionJson(gameRoot, versionId, profileJson)
        onLog("[加载器] 已写入版本：$versionId")

        val versionJson = gameRoot.resolve("versions").resolve(versionId).resolve("$versionId.json")
        val plan = InstallPlanner.plan(versionJson, versionId, gameRoot, source)
        plan.warnings.forEach { onLog("[加载器] 警告: $it") }
        onLog("[加载器] 第 3 步：下载它声明的 ${plan.targets.size} 个文件")

        val results = downloader.downloadAll(
            targets = plan.targets,
            cancelled = cancelled,
            onProgress = onProgress,
            onResult = { result ->
                if (result.outcome == DownloadOutcome.Failed) onLog("[加载器] 失败: ${result.target.label} — ${result.message}")
            },
        )
        val failed = results.count { it.outcome == DownloadOutcome.Failed }
        return if (failed == 0) {
            LoaderInstallResult(true, versionId, "${loader.displayName} $version 安装完成")
        } else {
            LoaderInstallResult(false, versionId, "${loader.displayName} 有 $failed 个文件下载失败，可以重试（已下好的会跳过）")
        }
    }

    // ---------------------------------------------------------------- Forge / NeoForge

    private fun installWithInstaller(
        gameVersion: String,
        loader: ModLoader,
        loaderVersion: String?,
        gameRoot: Path,
        source: DownloadSource,
        onLog: (String) -> Unit,
        onProgress: (Int, Int, String) -> Unit,
        cancelled: () -> Boolean,
    ): LoaderInstallResult {
        val version = loaderVersion
            ?: runCatching { ModLoaderRepository.listLoaderVersions(loader, gameVersion, source, downloader).firstOrNull()?.version }
                .getOrNull()
            ?: return LoaderInstallResult(false, null, "无法获取 ${loader.displayName} 的版本列表")

        // 安装器本身要用 Java 跑：按刚装好的原版版本 JSON 的要求准备，缺了就自动下载官方运行时
        val runtimeRoot = gameRoot.resolve("runtime")
        val vanillaJson = gameRoot.resolve("versions").resolve(gameVersion).resolve("$gameVersion.json")
        val spec = runCatching { VersionSpecReader.resolve(vanillaJson, gameRoot) }.getOrNull()
        val required = spec?.javaMajorVersion
        var runtime = JavaRuntimeLocator.findFor(required, listOf(runtimeRoot))
        if (required != null && runtime?.major != required && spec?.javaComponent != null) {
            onLog("[加载器] 安装器需要 Java $required，先准备运行时…")
            JavaRuntimeRepository.ensureRuntime(
                component = spec.javaComponent,
                expectedMajor = required,
                gameRoot = gameRoot,
                source = source,
                downloader = downloader,
                onLog = onLog,
                onProgress = onProgress,
                cancelled = cancelled,
            )
            runtime = JavaRuntimeLocator.findFor(required, listOf(runtimeRoot))
        }
        if (runtime == null) {
            return LoaderInstallResult(false, null, "${loader.displayName} 需要运行官方安装器，但本机没有可用的 Java")
        }

        val installerUrl = when (loader) {
            ModLoader.Forge -> ModLoaderRepository.forgeInstallerUrl(gameVersion, version)
            else -> ModLoaderRepository.neoforgeInstallerUrl(version)
        }
        val cacheDir = gameRoot.resolve("cache").createDirectories()
        val installerFile = cacheDir.resolve(installerUrl.substringAfterLast('/'))

        onLog("[加载器] 下载安装器：${installerUrl.substringAfterLast('/')}")
        val downloaded = downloader.download(
            DownloadTarget(
                url = installerUrl,
                destination = installerFile,
                sha1 = null,
                size = 0,
                label = "${loader.displayName} 安装器 $version",
                alternates = listOf(DownloadSource.mirrorOf(installerUrl)),
            ),
            cancelled = cancelled,
        )
        if (downloaded.outcome == DownloadOutcome.Failed) {
            return LoaderInstallResult(false, null, "下载安装器失败：${downloaded.message}")
        }

        val expectedId = readInstallerVersionId(installerFile)
            ?: "$gameVersion-${loader.displayName.lowercase()}-$version"
        onLog("[加载器] 运行安装器（预期版本号 $expectedId），这一步比较慢…")

        val exitCode = runInstaller(runtime, installerFile, gameRoot, onLog)
        val generated = gameRoot.resolve("versions").resolve(expectedId).resolve("$expectedId.json")
        val versionJson = when {
            generated.isRegularFile() -> generated
            else -> findFreshChildVersion(gameRoot, gameVersion, onLog)
        }
        if (versionJson == null) {
            return LoaderInstallResult(
                false, null,
                "安装器执行结束（退出码 $exitCode）但没找到生成的版本 JSON，安装可能失败了，请看上面的安装器日志",
            )
        }

        val versionId = versionJson.fileName.toString().removeSuffix(".json")
        onLog("[加载器] 安装器生成了版本：$versionId，正在补齐缺失的依赖库")
        val plan = InstallPlanner.plan(versionJson, versionId, gameRoot, source)
        val results = downloader.downloadAll(
            targets = plan.targets,
            cancelled = cancelled,
            onProgress = onProgress,
            onResult = { result ->
                if (result.outcome == DownloadOutcome.Failed) onLog("[加载器] 失败: ${result.target.label} — ${result.message}")
            },
        )
        val failed = results.count { it.outcome == DownloadOutcome.Failed }
        return LoaderInstallResult(
            failed == 0, versionId,
            if (failed == 0) "${loader.displayName} $version 安装完成" else "${loader.displayName} 有 $failed 个库下载失败",
        )
    }

    /** 安装器 jar 里的 install_profile.json 声明了它会生成哪个版本号 */
    fun readInstallerVersionId(installerJar: Path): String? = runCatching {
        ZipFile(installerJar.toFile()).use { zip ->
            val entry = zip.getEntry("install_profile.json") ?: return@use null
            val text = zip.getInputStream(entry).bufferedReader().readText()
            val obj = JsonParser.parseString(text).asJsonObject
            obj.scalar("version") ?: obj.scalar("profile")
        }
    }.getOrNull()

    /** 兜底：在 versions 下找「继承自该原版、且刚被创建」的版本 JSON */
    private fun findFreshChildVersion(gameRoot: Path, gameVersion: String, onLog: (String) -> Unit): Path? {
        val versionsDir = gameRoot.resolve("versions")
        if (!Files.isDirectory(versionsDir)) return null
        return runCatching {
            Files.newDirectoryStream(versionsDir).use { stream ->
                stream.filter { it.isRegularFile() && it.fileName.toString().endsWith(".json") }
                    .toList()
                    .mapNotNull { json ->
                        val obj = readJsonObject(json) ?: return@mapNotNull null
                        val parent = obj.str("inheritsFrom") ?: return@mapNotNull null
                        if (parent != gameVersion) null else json to runCatching { Files.getLastModifiedTime(json).toMillis() }.getOrDefault(0L)
                    }
                    .maxByOrNull { it.second }
                    ?.first
            }
        }.onSuccess { found -> if (found != null) onLog("[加载器] 兜底找到版本 JSON：${found.fileName}") }.getOrNull()
    }

    private fun runInstaller(java: JavaRuntime, installer: Path, gameRoot: Path, onLog: (String) -> Unit): Int {
        // 不同年代的安装器参数风格不同，两种都试一次
        val attempts = listOf(
            listOf(java.javaExecutable.toString(), "-jar", installer.toString(), "--installClient", gameRoot.toString()),
            listOf(java.javaExecutable.toString(), "-jar", installer.toString(), "--installClient"),
        )
        var lastCode = -1
        attempts.forEachIndexed { index, command ->
            onLog("[加载器] 执行（第 ${index + 1} 种参数）：${command.joinToString(" ")}")
            lastCode = runCatching {
                val process = ProcessBuilder(command)
                    .directory(gameRoot.toFile())
                    .redirectErrorStream(true)
                    .start()
                process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { line -> onLog("[安装器] $line") }
                }
                if (!process.waitFor(15, TimeUnit.MINUTES)) {
                    process.destroyForcibly()
                    -1
                } else {
                    process.exitValue()
                }
            }.getOrElse {
                onLog("[加载器] 安装器执行异常：${it.message}")
                -1
            }
            if (lastCode == 0) return 0
        }
        return lastCode
    }
}
