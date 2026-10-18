package com.abi.launcher

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 游戏根目录读取：只看所选目录下的 versions，不向子文件夹递归。
 */
class GameRootTest {

    private lateinit var tmp: Path

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("abi-root")
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    private fun versionJson(id: String, releaseTime: String = "2024-01-01T00:00:00+00:00") = """
        {"id":"$id","type":"release","releaseTime":"$releaseTime",
         "mainClass":"net.minecraft.client.main.Main","assetIndex":{"id":"1"},
         "javaVersion":{"majorVersion":21},"libraries":[{"name":"g:a:1"}]}
    """.trimIndent()

    private fun writeVersion(versionsDir: Path, id: String, releaseTime: String = "2024-01-01T00:00:00+00:00") {
        val dir = versionsDir.resolve(id).createDirectories()
        dir.resolve("$id.json").writeText(versionJson(id, releaseTime))
        dir.resolve("$id.jar").createFile()
    }

    @Test
    fun `只读取所选目录下 versions 里的版本`() {
        val root = tmp.resolve(".minecraft").createDirectories()
        val versions = root.resolve("versions").createDirectories()
        writeVersion(versions, "1.21.8", "2025-07-17T10:00:00+00:00")
        writeVersion(versions, "26.2", "2026-06-16T10:00:00+00:00")

        val result = MinecraftScanner.openGameRoot(root)

        assertFalse(result.skipped)
        assertEquals(1, result.installations.size)
        val found = result.installations.single()
        assertEquals(root.toAbsolutePath().normalize(), found.root)
        assertEquals(setOf("1.21.8", "26.2"), found.versions.map { it.id }.toSet())
        assertEquals(2, result.totalVersions)
    }

    @Test
    fun `不识别子文件夹里的游戏目录`() {
        val root = tmp.resolve(".minecraft").createDirectories()
        writeVersion(root.resolve("versions").createDirectories(), "1.21.8")

        // 子文件夹里的另一套游戏（整合包实例），不应被识别
        writeVersion(
            root.resolve("instances").resolve("PvP").resolve(".minecraft").resolve("versions").createDirectories(),
            "1.7.10",
        )
        writeVersion(
            root.resolve("整合包").resolve("versions").createDirectories(),
            "1.12.2",
        )

        val result = MinecraftScanner.openGameRoot(root)

        assertEquals(1, result.installations.size)
        assertEquals(listOf("1.21.8"), result.installations.single().versions.map { it.id })
        assertEquals(1, result.totalVersions, "只应有根目录 versions 下的那 1 个版本")
    }

    @Test
    fun `选中上级目录时不下钻 返回空结果`() {
        val gameDir = tmp.resolve("zawu2").resolve(".minecraft").createDirectories()
        writeVersion(gameDir.resolve("versions").createDirectories(), "1.21.8")

        // 选中 .minecraft 的上一级：按新语义不再自动进入 .minecraft
        val result = MinecraftScanner.openGameRoot(tmp.resolve("zawu2"))

        assertFalse(result.skipped)
        assertEquals(0, result.installations.size)
        assertEquals(0, result.totalVersions)
    }

    @Test
    fun `直接选中 versions 目录时把父目录当根目录`() {
        val root = tmp.resolve(".minecraft").createDirectories()
        val versions = root.resolve("versions").createDirectories()
        writeVersion(versions, "1.21.8")

        val found = MinecraftScanner.openGameRoot(versions).installations.single()

        assertEquals(root.toAbsolutePath().normalize(), found.root)
        assertEquals(listOf("1.21.8"), found.versions.map { it.id })
    }

    @Test
    fun `目录下没有 versions 时返回空结果`() {
        val empty = tmp.resolve("photos").createDirectories()
        empty.resolve("a.json").writeText("""{"hello":1}""")
        empty.resolve("sub").createDirectories()

        val result = MinecraftScanner.openGameRoot(empty)

        assertFalse(result.skipped)
        assertEquals(0, result.installations.size)
        assertTrue(result.totalVersions == 0)
    }

    @Test
    fun `目录不存在时标记 skipped`() {
        val result = MinecraftScanner.openGameRoot(tmp.resolve("nope"))

        assertTrue(result.skipped)
        assertEquals(0, result.installations.size)
    }
}
