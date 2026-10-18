package com.abi.launcher.crash

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 崩溃判断与日志导出 */
class CrashReportTest {

    private lateinit var tmp: Path

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("abi-crash")
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    @Test
    fun `内存不够`() {
        val d = CrashAnalyzer.analyze(-1, listOf("java.lang.OutOfMemoryError: Java heap space"))
        assertTrue(d.title.contains("内存"), d.title)
        assertTrue(d.suggestions.any { it.contains("内存") })
    }

    @Test
    fun `模组不兼容或缺前置`() {
        val log = listOf(
            "net.fabricmc.loader.impl.FormattedException: Incompatible mod set!",
            "Mod 'Sodium' (sodium) 0.5.8 requires version 1.20.1 of minecraft",
        )
        val d = CrashAnalyzer.analyze(1, log)

        assertTrue(d.title.contains("模组"), d.title)
        assertTrue(d.highlights.any { it.contains("Incompatible") }, d.highlights.joinToString())
    }

    @Test
    fun `Mixin 注入失败`() {
        val d = CrashAnalyzer.analyze(1, listOf("org.spongepowered.asm.mixin.injection.throwables.InvalidInjectionException: Critical injection failure"))

        assertTrue(d.title.contains("Mixin"), d.title)
    }

    @Test
    fun `Java 版本不对`() {
        val d = CrashAnalyzer.analyze(1, listOf("java.lang.UnsupportedClassVersionError: class file version 65.0"))

        assertTrue(d.title.contains("Java"), d.title)
    }

    @Test
    fun `显卡 OpenGL 问题`() {
        val d = CrashAnalyzer.analyze(1, listOf("GLFW error 65542: WGL: The driver does not appear to support OpenGL"))

        assertTrue(d.title.contains("显卡"), d.title)
    }

    @Test
    fun `认不出来时明确说认不出来`() {
        val d = CrashAnalyzer.analyze(1, listOf("随便什么", "没有任何已知特征"), modCount = 7)

        assertEquals("游戏异常退出", d.title)
        assertTrue(d.summary.contains("认出具体原因"), "实际命中：${d.title} ／ ${d.summary}")
        assertTrue(d.suggestions.any { it.contains("导出日志") }, d.suggestions.joinToString())
    }

    @Test
    fun `退出码 0 也能给出正常提示`() {
        val d = CrashAnalyzer.analyze(null, emptyList())

        assertEquals("启动失败", d.title)
    }

    @Test
    fun `最后一句话逐字保留`() {
        val footer = CrashAnalyzer.analyze(1, emptyList()).footer

        assertTrue(footer.startsWith("如果你眼睛没有失明"), footer)
        assertTrue(footer.contains("是把日志导出来 喂给ai或者是把日志给别人"), footer)
        assertEquals(2, Regex("请勿截图这个界面").findAll(footer).count(), footer)
        assertTrue(footer.contains("醒悟截图这个界面！！！！"), footer)
    }

    @Test
    fun `导出日志包含判断结果、模组列表和最后一句话`() {
        val gameDir = tmp.resolve("minecraft")
        Files.createDirectories(gameDir.resolve("mods"))
        Files.createDirectories(gameDir.resolve("crash-reports"))
        Files.writeString(gameDir.resolve("mods/sodium.jar"), "x")
        Files.writeString(gameDir.resolve("mods/fabric-api.jar"), "x")
        Files.writeString(gameDir.resolve("crash-reports/crash-2026-01-01.txt"), "游戏自己的崩溃报告内容")

        val diagnosis = CrashAnalyzer.analyze(1, listOf("java.lang.OutOfMemoryError"))
        val out = CrashAnalyzer.export(
            directory = tmp.resolve("报告"),
            diagnosis = diagnosis,
            info = mapOf("版本" to "1.21.8", "Java" to "Java 21"),
            logLines = (1..600).map { "日志第 $it 行" },
            gameDirectory = gameDir,
        )

        val text = Files.readString(out)
        assertTrue(out.fileName.toString().startsWith("崩溃报告-"), out.fileName.toString())
        assertTrue(text.contains("判断结果"), "缺少判断结果")
        assertTrue(text.contains("内存不够"), "缺少结论")
        assertTrue(text.contains("版本：1.21.8"), "缺少环境信息")
        assertTrue(text.contains("sodium.jar") && text.contains("fabric-api.jar"), "缺少模组列表")
        assertTrue(text.contains("游戏自己的崩溃报告内容"), "缺少游戏 crash-report")
        assertTrue(text.contains("日志第 600 行"), "应该带上最近的日志")
        assertTrue(!text.contains("日志第 1 行"), "只带最近 500 行")
        assertTrue(text.contains("请勿截图这个界面"), "缺少最后一句话")
    }

    @Test
    fun `游戏目录不存在时导出也不炸`() {
        val out = CrashAnalyzer.export(
            directory = tmp.resolve("报告2"),
            diagnosis = CrashAnalyzer.analyze(1, emptyList()),
            info = emptyMap(),
            logLines = emptyList(),
            gameDirectory = tmp.resolve("不存在"),
        )

        assertTrue(Files.exists(out))
    }
}
