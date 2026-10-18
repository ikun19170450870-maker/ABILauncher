package com.abi.launcher.download

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 整合包（.mrpack）解析与安装 */
class ModpackInstallerTest {

    private lateinit var tmp: Path

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("abi-modpack")
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    private val fabriPack = """
        {
          "formatVersion": 1,
          "game": "minecraft",
          "versionId": "1.2.3",
          "name": "Fabulously Optimized",
          "files": [
            {"path":"mods/sodium.jar","hashes":{"sha1":"aaaa"},"env":{"client":"required","server":"unsupported"},
             "downloads":["https://cdn.modrinth.com/data/x/sodium.jar"],"fileSize":123},
            {"path":"mods/server-only.jar","hashes":{"sha1":"bbbb"},"env":{"client":"unsupported","server":"required"},
             "downloads":["https://cdn.modrinth.com/data/y/only.jar"],"fileSize":10},
            {"path":"config/sodium-options.json","hashes":{"sha1":"cccc"},"env":{"client":"optional"},
             "downloads":["https://cdn.modrinth.com/data/z/opt.json"],"fileSize":5}
          ],
          "dependencies": {"minecraft":"1.20.1","fabric-loader":"0.15.0"}
        }
    """.trimIndent()

    private fun mrpack(indexJson: String, extraEntries: Map<String, String> = emptyMap()): Path {
        val file = tmp.resolve("pack-${System.nanoTime()}.mrpack")
        ZipOutputStream(Files.newOutputStream(file)).use { out ->
            out.putNextEntry(ZipEntry("modrinth.index.json"))
            out.write(indexJson.toByteArray())
            out.closeEntry()
            extraEntries.forEach { (name, content) ->
                out.putNextEntry(ZipEntry(name))
                out.write(content.toByteArray())
                out.closeEntry()
            }
        }
        return file
    }

    @Test
    fun `解析 Fabric 整合包的索引`() {
        val index = ModpackIndexReader.read(fabriPack)!!

        assertEquals("Fabulously Optimized", index.name)
        assertEquals("1.2.3", index.versionId)
        assertEquals("1.20.1", index.minecraft)
        assertEquals(ModLoader.Fabric, index.loader)
        assertEquals("0.15.0", index.loaderVersion)
        assertEquals(3, index.files.size)
        assertEquals("mods/sodium.jar", index.files[0].path)
        assertEquals("aaaa", index.files[0].sha1)
        assertEquals(123L, index.files[0].size)
    }

    @Test
    fun `纯服务端文件会被标记为不需要`() {
        val index = ModpackIndexReader.read(fabriPack)!!

        assertTrue(index.files[0].clientRequired, "client=required 要装")
        assertFalse(index.files[1].clientRequired, "client=unsupported 要跳过")
        assertTrue(index.files[2].clientRequired, "client=optional 也要装")
    }

    @Test
    fun `各种加载器的依赖都能识别`() {
        fun pack(deps: String) = """
            {"name":"X","files":[],"dependencies":$deps}
        """.trimIndent()

        assertEquals(ModLoader.Forge, ModpackIndexReader.read(pack("""{"minecraft":"1.20.1","forge":"47.2.0"}"""))!!.loader)
        assertEquals(ModLoader.NeoForge, ModpackIndexReader.read(pack("""{"minecraft":"1.21.1","neoforge":"21.1.0"}"""))!!.loader)
        assertEquals(ModLoader.Quilt, ModpackIndexReader.read(pack("""{"minecraft":"1.21.1","quilt-loader":"0.27.0"}"""))!!.loader)
        assertNull(ModpackIndexReader.read(pack("""{"minecraft":"1.20.1"}"""))!!.loader, "原版整合包没有加载器")
    }

    @Test
    fun `缺少必要字段时返回 null`() {
        assertNull(ModpackIndexReader.read("""{"name":"X","files":[]}"""), "没有 dependencies.minecraft")
        assertNull(ModpackIndexReader.read("not json"))
    }

    @Test
    fun `整合包名字会变成安全的目录名`() {
        assertEquals("Fabulously-Optimized", ModpackIndexReader.instanceDirectoryName("Fabulously Optimized"))
        assertEquals("我的整合包-v1", ModpackIndexReader.instanceDirectoryName("我的整合包/v1"))
        assertEquals("a-b-c", ModpackIndexReader.instanceDirectoryName("a:b*c?"))
        assertEquals("modpack", ModpackIndexReader.instanceDirectoryName("   "))
        assertTrue(ModpackIndexReader.instanceDirectoryName("x".repeat(200)).length <= 60)
    }

    @Test
    fun `解压 overrides 并挡掉越界文件`() {
        val pack = mrpack(
            "{}",
            mapOf(
                "overrides/config/foo.txt" to "hello",
                "overrides/../evil.txt" to "bad",
                "mods/not-an-override.jar" to "x",
            ),
        )
        val instance = tmp.resolve("instance").also { Files.createDirectories(it) }

        val count = ModpackInstaller().extractOverrides(pack, instance)

        assertEquals(1, count)
        assertEquals("hello", Files.readString(instance.resolve("config/foo.txt")))
        assertFalse(Files.exists(tmp.resolve("evil.txt")), "越界的 overrides 文件必须被挡住")
        assertFalse(Files.exists(instance.resolve("mods/not-an-override.jar")))
    }

    @Test
    fun `不是整合包时给出清楚的错误`() {
        val summary = ModpackInstaller().install(mrpackNoIndex())

        assertFalse(summary.ok)
        assertTrue(summary.message.contains("modrinth.index.json"), summary.message)
        assertNull(summary.instanceRoot)
    }

    private fun mrpackNoIndex(): Path {
        val file = tmp.resolve("noindex-${System.nanoTime()}.mrpack")
        ZipOutputStream(Files.newOutputStream(file)).use { out ->
            out.putNextEntry(ZipEntry("readme.txt"))
            out.write("hi".toByteArray())
            out.closeEntry()
        }
        return file
    }
}
