package com.abi.launcher.launch

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 游戏语言（options.txt 的 lang 行）处理 */
class GameOptionsTest {

    private lateinit var tmp: Path
    private lateinit var gameRoot: Path

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("abi-options")
        gameRoot = tmp.resolve("minecraft").createDirectories()
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    @Test
    fun `新目录里没有任何 options 时读不到语言`() {
        assertNull(GameOptions.readLanguage(gameRoot))
    }

    @Test
    fun `跟随游戏文件模式只在首次预置中文`() {
        // 第一次：目录里没有 options.txt，应当写入中文
        assertTrue(GameOptions.applyLanguage(gameRoot, GameOptions.Auto))
        assertEquals(GameOptions.ChineseSimplified, GameOptions.readLanguage(gameRoot))

        // 玩家在游戏里改成了英文
        GameOptions.setLanguage(gameRoot, GameOptions.English)

        // 第二次启动：跟随模式不应该再改回去
        assertFalse(GameOptions.applyLanguage(gameRoot, GameOptions.Auto))
        assertEquals(GameOptions.English, GameOptions.readLanguage(gameRoot))
    }

    @Test
    fun `指定语言会覆盖并保留其它设置`() {
        val options = GameOptions.optionsFile(gameRoot)
        options.writeText(
            listOf(
                "fov:0.5",
                "lang:en_us",
                "music:0.3",
            ).joinToString(System.lineSeparator()),
        )

        assertTrue(GameOptions.setLanguage(gameRoot, GameOptions.ChineseSimplified))

        val lines = options.readText().lines()
        assertTrue(lines.contains("lang:zh_cn"), lines.joinToString())
        assertTrue(lines.contains("fov:0.5"), "其它设置必须原样保留：${lines.joinToString()}")
        assertTrue(lines.contains("music:0.3"), lines.joinToString())
        assertEquals(3, lines.size, "替换而不是新增：${lines.joinToString()}")
    }

    @Test
    fun `没有 lang 行时会补在最前面`() {
        val options = GameOptions.optionsFile(gameRoot)
        options.writeText("fov:0.5")

        assertTrue(GameOptions.setLanguage(gameRoot, GameOptions.ChineseSimplified))

        val lines = options.readText().lines()
        assertEquals("lang:zh_cn", lines.first())
        assertTrue(lines.contains("fov:0.5"))
    }

    @Test
    fun `已经是目标语言时不重复写文件`() {
        GameOptions.setLanguage(gameRoot, GameOptions.ChineseSimplified)

        assertFalse(GameOptions.setLanguage(gameRoot, GameOptions.ChineseSimplified), "内容没变就返回 false")
    }

    @Test
    fun `大小写不同的 lang 行也能识别`() {
        val options = GameOptions.optionsFile(gameRoot)
        options.writeText("LANG:zh_CN")

        assertEquals("zh_CN", GameOptions.readLanguage(gameRoot))
        assertTrue(GameOptions.setLanguage(gameRoot, GameOptions.English))
        assertEquals("lang:en_us", options.readText().lines().first())
    }

    @Test
    fun `目录不存在时会创建出来`() {
        val fresh = tmp.resolve("new-home/minecraft")

        assertTrue(GameOptions.setLanguage(fresh, GameOptions.ChineseSimplified))

        assertTrue(GameOptions.optionsFile(fresh).toFile().exists())
        assertEquals(GameOptions.ChineseSimplified, GameOptions.readLanguage(fresh))
    }
}
