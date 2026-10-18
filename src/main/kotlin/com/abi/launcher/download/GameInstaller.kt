package com.abi.launcher.download

import com.abi.launcher.LauncherPaths
import com.abi.launcher.launch.DownloadSpec
import com.abi.launcher.launch.LaunchCommandBuilder
import com.abi.launcher.launch.RuleContext
import com.abi.launcher.launch.RuleEvaluator
import com.abi.launcher.launch.VersionSpecReader
import com.abi.launcher.launch.long
import com.abi.launcher.launch.obj
import com.abi.launcher.launch.readJsonObject
import com.abi.launcher.launch.scalar
import com.abi.launcher.launch.str
import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.Path

/**
 * 版本 JSON 里与「下载」有关的信息。
 *
 * 版本 JSON 会被两个小读取器各取所需：
 *  - [VersionSpecReader]（启动用）：mainClass / 参数 / 依赖库与规则
 *  - [VersionDownloads]（下载用）：客户端 jar、资源索引、日志配置的 url 与 sha1
 */
data class VersionDownloads(
    val clientJar: DownloadSpec?,
    val assetIndexId: String?,
    val assetIndex: DownloadSpec?,
    val logConfigId: String?,
    val logConfig: DownloadSpec?,
) {
    companion object {
        fun read(json: Path): VersionDownloads {
            val obj = readJsonObject(json) ?: return VersionDownloads(null, null, null, null, null)
            val downloads = obj.obj("downloads")
            val assetIndex = obj.obj("assetIndex")
            val logFile = obj.obj("logging")?.obj("client")?.obj("file")
            return VersionDownloads(
                clientJar = downloads?.obj("client")?.let(::toSpec),
                assetIndexId = assetIndex?.scalar("id"),
                assetIndex = assetIndex?.let(::toSpec),
                logConfigId = logFile?.str("id"),
                logConfig = logFile?.let(::toSpec),
            )
        }

        private fun toSpec(obj: JsonObject) = DownloadSpec(
            path = obj.str("path"),
            url = obj.str("url"),
            sha1 = obj.str("sha1"),
            size = obj.long("size") ?: 0L,
        )
    }
}

/** 一次安装要下载的全部文件 */
data class InstallPlan(
    val versionId: String,
    val gameRoot: Path,
    val targets: List<DownloadTarget>,
    val warnings: List<String>,
) {
    val totalBytes: Long get() = targets.sumOf { it.size }
}

data class InstallSummary(
    val versionId: String,
    val gameRoot: Path,
    val downloaded: Int,
    val skipped: Int,
    val failed: List<String>,
    val bytes: Long,
    val cancelled: Boolean,
) {
    val ok: Boolean get() = failed.isEmpty() && !cancelled
}

/**
 * 只要知道「版本 JSON + 目标游戏目录 + 下载源」，就能算出要下哪些文件、放到哪。
 * 位置严格遵循官方布局，这样启动模块不用改任何东西就能直接用。
 */
object InstallPlanner {

    fun plan(
        versionJson: Path,
        versionId: String,
        gameRoot: Path,
        source: DownloadSource,
        context: RuleContext = RuleContext.current(),
    ): InstallPlan {
        val warnings = mutableListOf<String>()
        val targets = mutableListOf<DownloadTarget>()
        val alternate = DownloadSource.alternateOf(source)

        val downloads = VersionDownloads.read(versionJson)
        // 依赖库与规则交给启动模块的解析器，避免两套规则实现
        val libraries = VersionSpecReader.read(versionJson).libraries
            .filter { RuleEvaluator.isAllowed(it.rules, context) }

        val versionDir = gameRoot.resolve("versions").resolve(versionId)

        // 1. 客户端 jar
        val clientJar = downloads.clientJar
        if (clientJar?.url != null) {
            targets += DownloadTarget(
                url = source.clientJarUrl(versionId, clientJar.url),
                destination = versionDir.resolve("$versionId.jar"),
                sha1 = clientJar.sha1,
                size = clientJar.size,
                label = "客户端 $versionId.jar",
                alternates = listOf(alternate.clientJarUrl(versionId, clientJar.url)),
            )
        } else {
            warnings.add("版本 JSON 缺少客户端 jar 的下载地址（可能是模组版本，需要先装父版本）")
        }

        // 2. 依赖库（已按当前系统规则过滤）
        libraries.forEach { library ->
            val artifact = library.artifact
            // 旧版纯 natives 库（声明了 natives 但没有主 artifact）不该被猜出一条主 jar 路径
            val path = artifact?.path
                ?: if (library.natives.isNotEmpty()) null else LaunchCommandBuilder.mavenPath(library.name)
            if (path != null) {
                targets += DownloadTarget(
                    url = source.libraryUrl(path, artifact?.url),
                    destination = gameRoot.resolve("libraries").resolve(path),
                    sha1 = artifact?.sha1,
                    size = artifact?.size ?: 0L,
                    label = "库 ${library.name}",
                    alternates = listOf(alternate.libraryUrl(path, artifact?.url)),
                )
            } else if (library.natives.isEmpty()) {
                warnings.add("无法推断库路径：${library.name}")
            }

            // 旧版格式：natives 是单独的分类库
            library.natives[context.osName]?.let { classifier ->
                val resolved = classifier.replace("\${arch}", if (context.osArch == "x86") "32" else "64")
                val spec = library.classifiers[resolved]
                val nativePath = spec?.path ?: LaunchCommandBuilder.mavenPath(library.name, resolved)
                if (nativePath != null) {
                    targets += DownloadTarget(
                        url = source.libraryUrl(nativePath, spec?.url),
                        destination = gameRoot.resolve("libraries").resolve(nativePath),
                        sha1 = spec?.sha1,
                        size = spec?.size ?: 0L,
                        label = "natives ${library.name}",
                        alternates = listOf(alternate.libraryUrl(nativePath, spec?.url)),
                    )
                }
            }
        }

        // 3. 资源索引（拿到它才知道要下哪些资源对象）
        val assetIndexId = downloads.assetIndexId
        if (assetIndexId != null) {
            targets += DownloadTarget(
                url = source.assetIndexUrl(versionId, downloads.assetIndex?.url),
                destination = gameRoot.resolve("assets").resolve("indexes").resolve("$assetIndexId.json"),
                sha1 = downloads.assetIndex?.sha1,
                size = downloads.assetIndex?.size ?: 0L,
                label = "资源索引 $assetIndexId.json",
                alternates = listOf(alternate.assetIndexUrl(versionId, downloads.assetIndex?.url)),
            )
        } else {
            warnings.add("版本 JSON 缺少资源索引信息")
        }

        // 4. 日志配置（很小，镜像站通常不镜像，主用官方地址）
        val logFile = downloads.logConfig
        if (logFile?.url != null && downloads.logConfigId != null) {
            targets += DownloadTarget(
                url = logFile.url,
                destination = gameRoot.resolve("assets").resolve("log_configs").resolve(downloads.logConfigId),
                sha1 = logFile.sha1,
                size = logFile.size,
                label = "日志配置 ${downloads.logConfigId}",
            )
        }

        return InstallPlan(versionId, gameRoot, targets, warnings)
    }

    /** 第二阶段：根据已经落地的资源索引算出全部资源对象 */
    fun assetObjectTargets(
        assetIndexJson: Path,
        gameRoot: Path,
        source: DownloadSource,
    ): List<DownloadTarget> {
        if (!Files.isRegularFile(assetIndexJson)) return emptyList()
        val text = runCatching { Files.readString(assetIndexJson) }.getOrNull() ?: return emptyList()
        val alternate = DownloadSource.alternateOf(source)
        return MojangRepository.parseAssetObjects(text).map { asset ->
            DownloadTarget(
                url = source.assetObjectUrl(asset.hash),
                destination = gameRoot.resolve("assets").resolve("objects")
                    .resolve(asset.hash.take(2)).resolve(asset.hash),
                sha1 = asset.hash, // 内容寻址：文件名就是 SHA-1
                size = asset.size,
                label = "资源 ${asset.name}",
                alternates = listOf(alternate.assetObjectUrl(asset.hash)),
            )
        }
    }
}

/**
 * 安装器：拉版本 JSON → 计划 → 下载 → 资源对象。
 * 已存在且校验通过的文件会自动跳过，所以可以随时中断再续。
 */
class GameInstaller(private val downloader: HttpDownloader = HttpDownloader()) {

    fun install(
        versionId: String,
        officialVersionJsonUrl: String?,
        gameRoot: Path,
        source: DownloadSource,
        onLog: (String) -> Unit = {},
        onProgress: (done: Int, total: Int, current: String) -> Unit = { _, _, _ -> },
        cancelled: () -> Boolean = { false },
        context: RuleContext = RuleContext.current(),
    ): InstallSummary {
        LauncherPaths.ensureGameRoot(gameRoot)

        // 1. 版本 JSON
        onLog("[下载] 获取版本 JSON：$versionId")
        val jsonText = MojangRepository.fetchVersionJsonText(versionId, officialVersionJsonUrl, source)
        val versionJson = MojangRepository.saveVersionJson(gameRoot, versionId, jsonText)

        // 2. 计划
        val plan = InstallPlanner.plan(versionJson, versionId, gameRoot, source, context)
        plan.warnings.forEach { onLog("[下载] 警告: $it") }
        onLog("[下载] 需要下载 ${plan.targets.size} 个文件（约 ${plan.totalBytes / 1024 / 1024} MB）+ 资源对象")

        var done = 0
        var total = plan.targets.size
        val results = mutableListOf<DownloadResult>()

        // 3. 客户端 jar / 库 / 资源索引 / 日志配置
        results += downloader.downloadAll(
            targets = plan.targets,
            cancelled = cancelled,
            onProgress = { completed, count, current ->
                done = completed
                total = count
                onProgress(done, total, current)
            },
            onResult = { result ->
                if (result.outcome == DownloadOutcome.Failed) {
                    onLog("[下载] 失败: ${result.target.label} — ${result.message}")
                }
            },
        )

        // 4. 资源对象（数量多，单独一批）
        val assetIndexFile = plan.targets
            .firstOrNull { it.label.startsWith("资源索引") }
            ?.destination
        if (assetIndexFile != null && !cancelled()) {
            val assetTargets = InstallPlanner.assetObjectTargets(assetIndexFile, gameRoot, source)
            if (assetTargets.isNotEmpty()) {
                onLog("[下载] 资源文件 ${assetTargets.size} 个，开始下载…")
                val base = plan.targets.size
                results += downloader.downloadAll(
                    targets = assetTargets,
                    cancelled = cancelled,
                    onProgress = { completed, count, current ->
                        onProgress(base + completed, base + count, current)
                    },
                    onResult = { result ->
                        if (result.outcome == DownloadOutcome.Failed) {
                            onLog("[下载] 失败: ${result.target.label} — ${result.message}")
                        }
                    },
                )
            }
        }

        val failed = results.filter { it.outcome == DownloadOutcome.Failed }.map { it.target.label }
        val summary = InstallSummary(
            versionId = versionId,
            gameRoot = gameRoot,
            downloaded = results.count { it.outcome == DownloadOutcome.Downloaded },
            skipped = results.count { it.outcome == DownloadOutcome.Skipped },
            failed = failed,
            bytes = results.sumOf { it.bytes },
            cancelled = cancelled(),
        )
        onLog(
            "[下载] 完成：新下载 ${summary.downloaded} 个，跳过已存在 ${summary.skipped} 个" +
                if (failed.isEmpty()) "" else "，失败 ${failed.size} 个",
        )
        return summary
    }

    /** 测试区用：只下载客户端 jar，验证整条链路（网络 + 校验 + 落盘） */
    fun downloadClientJarOnly(
        versionId: String,
        officialVersionJsonUrl: String?,
        gameRoot: Path,
        source: DownloadSource,
        onLog: (String) -> Unit = {},
        onProgress: (done: Int, total: Int, current: String) -> Unit = { _, _, _ -> },
        cancelled: () -> Boolean = { false },
    ): DownloadResult {
        LauncherPaths.ensureGameRoot(gameRoot)
        onLog("[测试] 获取版本 JSON：$versionId")
        val jsonText = MojangRepository.fetchVersionJsonText(versionId, officialVersionJsonUrl, source)
        val versionJson = MojangRepository.saveVersionJson(gameRoot, versionId, jsonText)

        val plan = InstallPlanner.plan(versionJson, versionId, gameRoot, source)
        val jar = plan.targets.firstOrNull { it.label.startsWith("客户端 ") }
            ?: return DownloadResult(
                DownloadTarget("", gameRoot, null, 0, "客户端 jar"),
                DownloadOutcome.Failed,
                message = "版本 JSON 里没有客户端 jar 下载地址",
            )

        onLog("[测试] 开始下载 ${jar.label}（${jar.size / 1024 / 1024} MB）")
        val result = downloader.download(
            jar,
            cancelled = cancelled,
            onBytes = { bytes -> onProgress(bytes.toInt(), jar.size.toInt().coerceAtLeast(1), jar.label) },
        )
        onLog("[测试] 结果：${result.outcome}${result.message?.let { " — $it" } ?: ""}")
        return result
    }
}
