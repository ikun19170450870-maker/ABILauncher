package com.abi.launcher.download

import com.abi.launcher.launch.JavaRuntime
import com.abi.launcher.launch.JavaRuntimeLocator
import com.abi.launcher.launch.arr
import com.abi.launcher.launch.long
import com.abi.launcher.launch.obj
import com.abi.launcher.launch.scalar
import com.abi.launcher.launch.str
import com.google.gson.JsonParser
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.isRegularFile

/** 官方运行时清单（all.json）里的一个组件 */
data class JavaRuntimeComponent(
    val component: String,
    val platform: String,
    val versionName: String?,
    val manifestUrl: String,
)

/** 组件清单里的一个条目 */
data class RuntimeFile(
    val relativePath: String,
    /** file / directory / link */
    val type: String,
    val url: String?,
    val sha1: String?,
    val size: Long,
    val executable: Boolean,
)

data class JavaRuntimeResult(
    val ok: Boolean,
    val alreadyPresent: Boolean,
    val javaExecutable: Path?,
    val message: String,
)

/**
 * Java 运行时的自动安装/更新。
 *
 * 官方启动器不依赖系统 JDK：版本 JSON 里的 `javaVersion.component`（如 java-runtime-epsilon）
 * 会去运行时清单里查到对应平台的下载清单，把整个 JRE 下到
 * `runtime/<组件>/<平台>/<组件>/`，然后直接用那份 java.exe。
 *
 * 我们复用同一套布局，所以 [JavaRuntimeLocator] 不用改就能找到它。
 */
object JavaRuntimeRepository {

    private val KNOWN_PLATFORMS = setOf("windows-x64", "windows-x86", "mac-os", "mac-os-arm64", "linux", "linux-i386")

    /** 当前系统在清单里的平台键 */
    fun platformKey(
        osName: String = System.getProperty("os.name").orEmpty(),
        osArch: String = System.getProperty("os.arch").orEmpty(),
    ): String {
        val os = osName.lowercase()
        val arch = osArch.lowercase()
        return when {
            os.contains("win") -> if (arch.contains("64")) "windows-x64" else "windows-x86"
            os.contains("mac") || os.contains("darwin") ->
                if (arch.contains("aarch64") || arch.contains("arm64")) "mac-os-arm64" else "mac-os"
            else -> if (arch.contains("i386") || arch.contains("i686") || arch == "x86") "linux-i386" else "linux"
        }
    }

    /** 从 all.json 里查出某个组件在当前平台的清单地址 */
    fun parseComponent(allJson: String, component: String, platform: String): JavaRuntimeComponent? {
        val root = runCatching { JsonParser.parseString(allJson).asJsonObject }.getOrNull() ?: return null
        val platformNode = root.get(platform)?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val entries = platformNode.arr(component) ?: return null
        val first = entries.firstOrNull { it.isJsonObject }?.asJsonObject ?: return null
        val manifest = first.obj("manifest") ?: return null
        val url = manifest.str("url") ?: return null
        return JavaRuntimeComponent(
            component = component,
            platform = platform,
            versionName = first.obj("version")?.scalar("name"),
            manifestUrl = url,
        )
    }

    /** 组件清单里的文件表 */
    fun parseRuntimeFiles(manifestJson: String): List<RuntimeFile> {
        val root = runCatching { JsonParser.parseString(manifestJson).asJsonObject }.getOrNull() ?: return emptyList()
        val files = root.obj("files") ?: return emptyList()
        return files.entrySet().mapNotNull { (path, element) ->
            val value = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val raw = value.obj("downloads")?.obj("raw")
            RuntimeFile(
                relativePath = path,
                type = value.str("type") ?: "file",
                url = raw?.str("url"),
                sha1 = raw?.str("sha1"),
                size = raw?.long("size") ?: 0L,
                executable = value.get("executable")?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.asBoolean ?: false,
            )
        }
    }

    /**
     * 把清单里的文件表换算成下载计划。
     * 落盘位置保持官方布局：runtime/<组件>/<平台>/<清单里的相对路径>
     */
    fun runtimeTargets(
        files: List<RuntimeFile>,
        component: JavaRuntimeComponent,
        gameRoot: Path,
        source: DownloadSource,
    ): List<DownloadTarget> {
        val base = gameRoot.resolve("runtime").resolve(component.component).resolve(component.platform)
        return files.mapNotNull { file ->
            if (file.type != "file") return@mapNotNull null
            val url = file.url ?: return@mapNotNull null
            val primary = if (source == DownloadSource.Bmclapi) DownloadSource.mirrorOf(url) else url
            val alternateUrl = if (source == DownloadSource.Bmclapi) url else DownloadSource.mirrorOf(url)
            DownloadTarget(
                url = primary,
                destination = base.resolve(file.relativePath),
                sha1 = file.sha1,
                size = file.size,
                label = "Java 运行时 ${file.relativePath.substringAfterLast('/')}",
                alternates = listOf(alternateUrl),
            )
        }
    }

    /** 可执行位（Windows 上无所谓，其它平台需要） */
    fun markExecutable(targets: List<DownloadTarget>) {
        targets.forEach { target ->
            if (!target.destination.isRegularFile()) return@forEach
            runCatching {
                val permissions = java.nio.file.Files.getPosixFilePermissions(target.destination)
                java.nio.file.Files.setPosixFilePermissions(
                    target.destination,
                    permissions + PosixFilePermission.OWNER_EXECUTE + PosixFilePermission.GROUP_EXECUTE,
                )
            }
        }
    }

    /**
     * 保证「某个大版本的 Java」可用：
     * 本机（含游戏目录下的 runtime）已有精确匹配就直接返回；否则下载官方运行时。
     * 下载完会重新检测一次，检测不到就如实报失败。
     */
    fun ensureRuntime(
        component: String,
        expectedMajor: Int?,
        gameRoot: Path,
        source: DownloadSource,
        downloader: HttpDownloader = HttpDownloader(),
        onLog: (String) -> Unit = {},
        onProgress: (done: Int, total: Int, current: String) -> Unit = { _, _, _ -> },
        cancelled: () -> Boolean = { false },
    ): JavaRuntimeResult {
        val runtimeRoot = gameRoot.resolve("runtime")
        JavaRuntimeLocator.findFor(expectedMajor, listOf(runtimeRoot))?.let { existing ->
            if (expectedMajor == null || existing.major == expectedMajor) {
                return JavaRuntimeResult(true, true, existing.javaExecutable, "本机已有 ${existing.label}")
            }
            onLog("[Java] 本机只有 Java ${existing.major}，需要 Java $expectedMajor，开始下载运行时 $component")
        }

        val platform = platformKey()
        return try {
            onLog("[Java] 查询运行时清单（$platform / $component）")
            val allJson = downloader.fetchText(
                source.javaRuntimeManifestUrl(),
                alternates = listOf(DownloadSource.alternateOf(source).javaRuntimeManifestUrl()),
            )
            val parsed = JavaRuntimeRepository.parseComponent(allJson, component, platform)
                ?: return JavaRuntimeResult(
                    false, false, null,
                    "运行时清单里没有 $platform / $component（可能该版本不支持自动安装 Java）",
                )
            onLog("[Java] 命中组件 ${parsed.component}${parsed.versionName?.let { "（$it）" } ?: ""}")

            val manifestJson = downloader.fetchText(
                if (source == DownloadSource.Bmclapi) DownloadSource.mirrorOf(parsed.manifestUrl) else parsed.manifestUrl,
                alternates = listOf(
                    if (source == DownloadSource.Bmclapi) parsed.manifestUrl else DownloadSource.mirrorOf(parsed.manifestUrl),
                ),
            )
            val files = parseRuntimeFiles(manifestJson)
            if (files.isEmpty()) return JavaRuntimeResult(false, false, null, "运行时清单里没有文件列表")

            val targets = runtimeTargets(files, parsed, gameRoot, source)
            onLog("[Java] 需要下载 ${targets.size} 个文件（约 ${targets.sumOf { it.size } / 1024 / 1024} MB）")

            val results = downloader.downloadAll(
                targets = targets,
                cancelled = cancelled,
                onProgress = onProgress,
                onResult = { result ->
                    if (result.outcome == DownloadOutcome.Failed) {
                        onLog("[Java] 失败: ${result.target.label} — ${result.message}")
                    }
                },
            )
            markExecutable(targets)

            val failed = results.count { it.outcome == DownloadOutcome.Failed }
            if (failed > 0) {
                return JavaRuntimeResult(false, false, null, "有 $failed 个文件下载失败，Java 运行时可能不完整")
            }

            // 重新检测：确认这份运行时真的能被找到
            val detected = JavaRuntimeLocator.findFor(expectedMajor, listOf(runtimeRoot))
            if (detected != null && (expectedMajor == null || detected.major == expectedMajor)) {
                JavaRuntimeResult(true, false, detected.javaExecutable, "Java 运行时已就绪：${detected.label}")
            } else {
                JavaRuntimeResult(
                    false, false, detected?.javaExecutable,
                    "运行时下载完成但没能识别出版本（期望 Java $expectedMajor，实际 ${detected?.major ?: "未知"}）",
                )
            }
        } catch (e: Exception) {
            JavaRuntimeResult(false, false, null, "下载 Java 运行时失败：${e.message}")
        }
    }

    /** 界面用：期望的 Java 是否具备 */
    fun inspect(requiredMajor: Int?, runtimeRoot: Path): Pair<JavaRuntime?, Boolean> {
        val found = JavaRuntimeLocator.findFor(requiredMajor, listOf(runtimeRoot))
        val exact = requiredMajor == null || found?.major == requiredMajor
        return found to exact
    }
}
