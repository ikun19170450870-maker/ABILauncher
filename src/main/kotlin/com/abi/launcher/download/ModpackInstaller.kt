package com.abi.launcher.download

import com.abi.launcher.LauncherPaths
import com.abi.launcher.launch.arr
import com.abi.launcher.launch.long
import com.abi.launcher.launch.obj
import com.abi.launcher.launch.str
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** .mrpack 里 files[] 的一项 */
data class ModpackFile(
    /** 相对实例目录的路径，比如 mods/sodium.jar */
    val path: String,
    val url: String,
    val sha1: String?,
    val size: Long,
    /** env.client == "unsupported" 的（纯服务端）文件要跳过 */
    val clientRequired: Boolean,
)

/** modrinth.index.json */
data class ModpackIndex(
    val name: String,
    val versionId: String,
    val minecraft: String,
    val loader: ModLoader?,
    val loaderVersion: String?,
    val files: List<ModpackFile>,
)

data class ModpackInstallSummary(
    val ok: Boolean,
    /** 整合包的独立实例目录（可以直接当成游戏根目录打开） */
    val instanceRoot: Path?,
    val versionId: String?,
    val message: String,
    val filesDownloaded: Int = 0,
    val overridesExtracted: Int = 0,
)

/** 解析 modrinth.index.json（纯函数，便于测试） */
object ModpackIndexReader {

    fun read(json: String): ModpackIndex? {
        val root = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull() ?: return null
        val dependencies = root.obj("dependencies") ?: return null
        val minecraft = dependencies.str("minecraft") ?: return null

        val (loader, loaderVersion) = when {
            dependencies.str("fabric-loader") != null -> ModLoader.Fabric to dependencies.str("fabric-loader")
            dependencies.str("quilt-loader") != null -> ModLoader.Quilt to dependencies.str("quilt-loader")
            dependencies.str("neoforge") != null -> ModLoader.NeoForge to dependencies.str("neoforge")
            dependencies.str("forge") != null -> ModLoader.Forge to dependencies.str("forge")
            else -> null to null
        }

        val files = root.arr("files")?.mapNotNull { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val path = obj.str("path") ?: return@mapNotNull null
            val url = obj.arr("downloads")
                ?.firstOrNull { it.isJsonPrimitive }?.asString
                ?: return@mapNotNull null
            ModpackFile(
                path = path,
                url = url,
                sha1 = obj.obj("hashes")?.str("sha1"),
                size = obj.long("fileSize") ?: 0L,
                clientRequired = obj.obj("env")?.str("client") != "unsupported",
            )
        }.orEmpty()

        return ModpackIndex(
            name = root.str("name")?.takeIf { it.isNotBlank() } ?: "modpack",
            versionId = root.str("versionId").orEmpty(),
            minecraft = minecraft,
            loader = loader,
            loaderVersion = loaderVersion,
            files = files,
        )
    }

    /** 整合包名字 → 安全的目录名 */
    fun instanceDirectoryName(name: String): String =
        name.trim()
            .replace(Regex("[\\\\/:*?\"<>|\\s]+"), "-")
            .trim('-', '.')
            .ifBlank { "modpack" }
            .take(60)
}

/**
 * Modrinth 整合包（.mrpack）安装器。
 *
 * 每个整合包装成**自己的实例目录** `<数据目录>/instances/<整合包名>`：
 * 里面是完整的一套（`versions/`、`mods/`、`config/` …），互相不干扰，
 * 直接把那个目录当成游戏根目录打开就能启动。
 */
class ModpackInstaller(private val downloader: HttpDownloader = HttpDownloader()) {

    fun install(
        mrpack: Path,
        source: DownloadSource = DownloadSource.Official,
        instanceRootOverride: Path? = null,
        onLog: (String) -> Unit = {},
        onProgress: (completed: Int, total: Int, current: String) -> Unit = { _, _, _ -> },
        cancelled: () -> Boolean = { false },
    ): ModpackInstallSummary {
        val index = ZipFile(mrpack.toFile()).use { zip ->
            val entry = zip.getEntry("modrinth.index.json")
                ?: return ModpackInstallSummary(false, null, null, "这个 .mrpack 里没有 modrinth.index.json，可能不是 Modrinth 整合包")
            val text = zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
            ModpackIndexReader.read(text)
                ?: return ModpackInstallSummary(false, null, null, "modrinth.index.json 解析失败（缺少 dependencies.minecraft？）")
        }

        val instanceRoot = instanceRootOverride
            ?: LauncherPaths.dataRoot.resolve("instances")
                .resolve(ModpackIndexReader.instanceDirectoryName(index.name))
        instanceRoot.createDirectories()

        onLog("[整合包] ${index.name} ${index.versionId} → 实例目录 $instanceRoot")
        onLog(
            "[整合包] 需要 Minecraft ${index.minecraft}" +
                (index.loader?.let { " + ${it.displayName} ${index.loaderVersion.orEmpty()}" } ?: "（纯原版）"),
        )

        // 1) 先在实例目录里装好原版 + 对应加载器
        val officialUrl = runCatching {
            MojangRepository.fetchManifest(source).manifest?.versions?.firstOrNull { it.id == index.minecraft }?.url
        }.getOrNull()

        val loaderResult = ModLoaderInstaller(downloader).install(
            gameVersion = index.minecraft,
            officialGameJsonUrl = officialUrl,
            loader = index.loader ?: ModLoader.Vanilla,
            loaderVersion = index.loaderVersion,
            gameRoot = instanceRoot,
            source = source,
            onLog = onLog,
            onProgress = onProgress,
            cancelled = cancelled,
        )
        if (!loaderResult.ok) {
            return ModpackInstallSummary(
                ok = false,
                instanceRoot = instanceRoot,
                versionId = loaderResult.versionId,
                message = "整合包的原版/加载器还没装好：${loaderResult.message}",
            )
        }

        // 2) 按 index 里的 files[] 拉模组/配置（带 SHA-1 校验，已下好的会跳过）
        val targets = index.files.filter { it.clientRequired }.mapNotNull { file ->
            val destination = instanceRoot.resolve(file.path).normalize()
            if (!destination.startsWith(instanceRoot)) {
                onLog("[整合包] 跳过越界路径：${file.path}")
                null
            } else {
                DownloadTarget(
                    url = file.url,
                    destination = destination,
                    sha1 = file.sha1,
                    size = file.size,
                    label = file.path,
                    alternates = listOf(DownloadSource.mirrorOf(file.url)).filter { it != file.url },
                )
            }
        }
        onLog("[整合包] 要下载 ${targets.size} 个文件（已下好的会跳过）")
        val results = downloader.downloadAll(targets, cancelled = cancelled, onProgress = onProgress)
        val failed = results.count { it.outcome == DownloadOutcome.Failed }
        if (failed > 0) {
            onLog("[整合包] 有 $failed 个文件下载失败，重新点一次会接着下（已完成的会跳过）")
        }

        // 3) 解压 overrides/ 覆盖到实例目录
        val extracted = extractOverrides(mrpack, instanceRoot, onLog)
        onLog("[整合包] 解压 overrides 共 $extracted 个文件")

        runCatching {
            instanceRoot.resolve("abi-modpack.txt").writeText(
                buildString {
                    appendLine("整合包：${index.name}")
                    appendLine("版本：${index.versionId}")
                    appendLine("Minecraft：${index.minecraft}")
                    appendLine("加载器：${index.loader?.displayName ?: "原版"} ${index.loaderVersion.orEmpty()}")
                    appendLine("来源：Modrinth（$source）")
                },
            )
        }

        val versionId = loaderResult.versionId
        val message = if (failed == 0) {
            "整合包安装完成：${index.name}（版本 $versionId）"
        } else {
            "整合包基本装好（$failed 个文件失败），可以再点一次补齐"
        }
        return ModpackInstallSummary(
            ok = failed == 0,
            instanceRoot = instanceRoot,
            versionId = versionId,
            message = message,
            filesDownloaded = targets.size - failed,
            overridesExtracted = extracted,
        )
    }

    /** 把压缩包里的 overrides 目录解压到实例目录（去掉 overrides 前缀，带越界保护） */
    fun extractOverrides(
        mrpack: Path,
        instanceRoot: Path,
        onLog: (String) -> Unit = {},
    ): Int {
        var count = 0
        ZipFile(mrpack.toFile()).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory || !entry.name.startsWith("overrides/")) continue
                val relative = entry.name.removePrefix("overrides/")
                if (relative.isBlank()) continue
                val destination = instanceRoot.resolve(relative).normalize()
                if (!destination.startsWith(instanceRoot)) {
                    onLog("[整合包] 跳过越界文件：${entry.name}")
                    continue
                }
                destination.parent?.createDirectories()
                zip.getInputStream(entry).use { input ->
                    Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING)
                }
                count++
            }
        }
        return count
    }
}
