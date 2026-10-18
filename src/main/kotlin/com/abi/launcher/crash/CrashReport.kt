package com.abi.launcher.crash

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * 所有报错弹窗最后一行固定要说的话。
 * 按用户要求**逐字保留**（包括「醒悟截图」这个错别字，没替他改）。
 */
const val ASK_FOR_HELP_FOOTER: String =
    "如果你眼睛没有失明 并且能看见这句话 请在寻求帮助时 是把日志导出来 喂给ai或者是把日志给别人 " +
        "请勿截图这个界面 请勿截图这个界面 醒悟截图这个界面！！！！"

data class CrashDiagnosis(
    val title: String,
    /** 一句话结论 */
    val summary: String,
    val causes: List<String>,
    val suggestions: List<String>,
    /** 日志里最像线索的几行 */
    val highlights: List<String>,
) {
    val footer: String get() = ASK_FOR_HELP_FOOTER
}

/**
 * 根据实际日志内容判断「到底怎么回事」。
 *
 * 规则从最具体排到最笼统，命中哪条报哪条；认不出来就老老实实说认不出来。
 */
object CrashAnalyzer {

    private val highlightHints = listOf(
        "error", "exception", "caused by", "crash", "failed", "missing", "incompatible",
        "outofmemory", "unsupported", "mod file", "mixin", "exit code", "requires",
    )

    fun analyze(exitCode: Int?, logLines: List<String>, modCount: Int = 0): CrashDiagnosis {
        val tail = logLines.takeLast(500)
        val text = tail.joinToString("\n").lowercase()
        val highlights = tail
            .filter { line -> highlightHints.any { line.lowercase().contains(it) } }
            .takeLast(10)

        fun has(vararg needles: String) = needles.any { text.contains(it) }
        val exit = exitCode?.let { "（退出码 $it）" }.orEmpty()

        return when {
            has("outofmemoryerror", "java heap space", "insufficient memory") -> CrashDiagnosis(
                title = "游戏崩溃：内存不够",
                summary = "游戏把分配的内存用完了$exit。",
                causes = listOf("分配的内存（-Xmx）太小", "模组太多 / 光影太吃内存", "个别模组内存泄漏"),
                suggestions = listOf(
                    "设置页把「默认最大内存」调大（建议 4096–8192MB，别超过物理内存的 70%）",
                    "也可以在版本设置里只给这个版本单独调内存",
                    "关掉一些吃内存的模组或光影",
                ),
                highlights = highlights,
            )

            has(
                "incompatible mod set", "mod resolution encountered", "requires version",
                "missing or unsupported mandatory dependencies", "duplicate mods", "mod file:",
            ) -> CrashDiagnosis(
                title = "游戏崩溃：模组不兼容或缺前置",
                summary = "加载器在解析模组时就报错了$exit —— 通常是模组之间版本不搭，或者缺前置模组。",
                causes = listOf(
                    "模组要求的游戏版本 / 加载器版本跟你现在玩的不一致",
                    "缺前置模组（Fabric API、Architectury、Kotlin 之类）",
                    "同一个模组装了两份（duplicate mods）",
                ),
                suggestions = listOf(
                    "看下面日志里点名的模组，去 Modrinth 核对它要求的游戏版本与加载器版本",
                    "缺的前置直接在「下载 → 模组」里搜名字装上",
                    "手动丢进 mods 的旧模组先移走一半，二分法定位是哪个出问题",
                ),
                highlights = highlights,
            )

            has("mixin") && has("crash", "failed", "apply", "injection", "invalid") -> CrashDiagnosis(
                title = "游戏崩溃：Mixin 注入失败",
                summary = "某个模组往游戏里注入代码时失败了$exit，基本都是模组与游戏/其它模组不兼容。",
                causes = listOf("模组不是给这个游戏版本做的", "两个模组改了同一处代码", "加载器版本太旧"),
                suggestions = listOf(
                    "把下面日志提到的模组换成适配当前游戏版本的版本",
                    "在下载页把加载器更新到该游戏版本的推荐版本",
                ),
                highlights = highlights,
            )

            has(
                "unsupportedclassversionerror", "class file version",
                "has been compiled by a more recent version",
            ) -> CrashDiagnosis(
                title = "启动失败：Java 版本不对",
                summary = "这个版本（或某个模组）需要另一个 Java 大版本$exit。",
                causes = listOf("游戏要求的 Java 跟你现在用的不一致", "模组要求更高版本的 Java"),
                suggestions = listOf(
                    "设置页确认「缺少该版本需要的 Java 时自动下载」是开着的",
                    "在版本设置里手动指定合适的 java.exe",
                ),
                highlights = highlights,
            )

            has(
                "noclassdeffounderror", "nosuchmethoderror", "abstractmethoderror",
                "classnotfoundexception", "could not find or load main class",
            ) -> CrashDiagnosis(
                title = "启动失败：缺少类 / 库对不上",
                summary = "运行到一半找不到某个类$exit —— 常见于依赖库没下全，或者模组与游戏版本不匹配。",
                causes = listOf("依赖库下载不完整或损坏", "客户端 jar 与版本不对应", "模组为其它游戏版本编译"),
                suggestions = listOf(
                    "下载页对同一个版本再点一次「下载并安装」（已下好的会跳过，缺的会补上）",
                    "确认模组支持你正在玩的游戏版本",
                ),
                highlights = highlights,
            )

            has("glfw", "opengl", "pixel format", "failed to create window", "wgl", "gldrawer") -> CrashDiagnosis(
                title = "启动失败：显卡 / OpenGL 问题",
                summary = "创建游戏窗口时显卡驱动报错$exit。",
                causes = listOf("显卡驱动太旧或损坏", "远程桌面/虚拟机里没有可用 OpenGL", "双显卡切到了核显"),
                suggestions = listOf(
                    "更新显卡驱动（NVIDIA / AMD / Intel 官网）",
                    "笔记本双显卡的话，把 java.exe 指定为「高性能显卡」",
                    "远程桌面里玩不了，换本机试",
                ),
                highlights = highlights,
            )

            has("invalid session", "failed to verify username", "401", "not authenticated") -> CrashDiagnosis(
                title = "启动失败：登录状态失效",
                summary = "游戏没能通过账号验证$exit。",
                causes = listOf("登录信息过期", "账号信息不对"),
                suggestions = listOf("重新登录；或者先切离线账号玩单机"),
                highlights = highlights,
            )

            has("the game crashed whilst", "crash report", "exception in thread") -> CrashDiagnosis(
                title = "游戏崩溃",
                summary = "游戏自己抛异常并生成了崩溃报告$exit。",
                causes = listOf("某个模组在运行中出错", "游戏本体 bug 或资源损坏"),
                suggestions = listOf(
                    "点下面的「导出日志」，里面会自动带上 crash-reports 里最新的那份报告",
                    "想一下崩溃前装了什么模组，先移出去再试",
                ),
                highlights = highlights,
            )

            else -> CrashDiagnosis(
                title = if (exitCode != null && exitCode != 0) "游戏异常退出" else "启动失败",
                summary = "没能从日志里认出具体原因$exit。",
                causes = listOf("可能是模组、Java、内存或显卡驱动的问题"),
                suggestions = listOf(
                    "点下面的「导出日志」，把导出出来的文件发给 AI 或懂的人（**别截图**）",
                    if (modCount > 0) "你装了不少模组：先把 mods 全部移出，再一批批加回来，能快速定位" else "先确认 Java 与内存设置",
                ),
                highlights = highlights,
            )
        }
    }

    /** 导出成一份可以直接丢给 AI 的文本 */
    fun export(
        directory: Path,
        diagnosis: CrashDiagnosis,
        info: Map<String, String>,
        logLines: List<String>,
        gameDirectory: Path?,
    ): Path {
        val now = LocalDateTime.now()
        val file = directory.resolve("崩溃报告-${now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}.txt")
        file.parent.createDirectories()

        val text = buildString {
            appendLine("ABI Launcher 错误报告")
            appendLine("时间：$now")
            info.forEach { (key, value) -> appendLine("$key：$value") }
            appendLine()
            appendLine("===== 判断结果 =====")
            appendLine(diagnosis.title)
            appendLine(diagnosis.summary)
            appendLine("可能原因：")
            diagnosis.causes.forEach { appendLine("  - $it") }
            appendLine("建议：")
            diagnosis.suggestions.forEach { appendLine("  - $it") }
            appendLine()

            gameDirectory?.let { dir ->
                newestCrashReport(dir)?.let { report ->
                    appendLine("===== 游戏崩溃报告（${report.fileName}）=====")
                    appendLine(runCatching { Files.readString(report).take(30000) }.getOrElse { "（读取失败）" })
                    appendLine()
                }
                listMods(dir)?.let { mods ->
                    appendLine("===== 这个游戏目录里的模组（${mods.size} 个）=====")
                    mods.forEach { appendLine("  $it") }
                    appendLine()
                }
            }

            appendLine("===== 启动器日志（最近 500 行）=====")
            logLines.takeLast(500).forEach { appendLine(it) }
            appendLine()
            appendLine(ASK_FOR_HELP_FOOTER)
        }
        file.writeText(text)
        return file
    }

    /** 游戏自己写的最新 crash-report */
    fun newestCrashReport(gameDirectory: Path): Path? {
        val dir = gameDirectory.resolve("crash-reports")
        if (!Files.isDirectory(dir)) return null
        return runCatching {
            Files.newDirectoryStream(dir).use { stream ->
                stream.filter { Files.isRegularFile(it) }
                    .maxByOrNull { runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0L) }
            }
        }.getOrNull()
    }

    fun listMods(gameDirectory: Path): List<String>? {
        val dir = gameDirectory.resolve("mods")
        if (!Files.isDirectory(dir)) return null
        return runCatching {
            Files.newDirectoryStream(dir).use { stream ->
                stream.map { it.fileName.toString() }.sorted()
            }
        }.getOrNull()
    }
}
