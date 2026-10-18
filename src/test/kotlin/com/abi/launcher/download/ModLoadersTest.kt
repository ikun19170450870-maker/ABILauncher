package com.abi.launcher.download

import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 模组加载器元数据解析与安装器探测 */
class ModLoadersTest {

    private lateinit var tmp: Path

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("abi-loaders")
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    @Test
    fun `解析 Fabric 加载器列表`() {
        val json = """
            [
              {"loader":{"separator":".","build":1,"maven":"net.fabricmc:fabric-loader:0.19.3","version":"0.19.3","stable":true},
               "intermediary":{"version":"1.21.8"},"launcherMeta":{}},
              {"loader":{"version":"0.19.2","stable":false}}
            ]
        """.trimIndent()

        val options = ModLoaderRepository.parseFabricLike(json)

        assertEquals(listOf("0.19.3", "0.19.2"), options.map { it.version })
        assertTrue(options.first().stable)
        assertTrue(!options.last().stable)
        assertTrue(options.first().displayName.contains("推荐"))
    }

    @Test
    fun `解析 Quilt 加载器列表`() {
        val json = """[{"loader":{"version":"0.27.1","stable":true}}]"""

        val options = ModLoaderRepository.parseFabricLike(json)

        assertEquals(listOf("0.27.1"), options.map { it.version })
    }

    @Test
    fun `解析 Forge 的推荐版与最新版`() {
        val json = """
            {"homepage":"https://files.minecraftforge.net/",
             "promos":{"1.20.1-latest":"47.3.0","1.20.1-recommended":"47.2.0","1.19.2-recommended":"43.2.0"}}
        """.trimIndent()

        val options = ModLoaderRepository.parseForgePromos(json, "1.20.1")

        assertEquals(listOf("47.2.0", "47.3.0"), options.map { it.version })
        assertTrue(options.first().stable, "推荐版应标记为 stable")
        assertEquals("推荐", options.first().note)
        assertEquals("最新", options.last().note)

        // 只有推荐版时只返回一个
        assertEquals(listOf("43.2.0"), ModLoaderRepository.parseForgePromos(json, "1.19.2").map { it.version })
        // 没有这个游戏版本时返回空
        assertTrue(ModLoaderRepository.parseForgePromos(json, "1.7.10").isEmpty())
    }

    @Test
    fun `解析 NeoForge 列表并按游戏版本过滤`() {
        val json = """
            [
              {"version":"20.4.237","mcversion":"1.20.4"},
              {"version":"20.4.236","mcversion":"1.20.4"},
              {"version":"21.1.0","mcversion":"1.21.1"}
            ]
        """.trimIndent()

        val options = ModLoaderRepository.parseNeoForgeList(json, "1.20.4")

        assertEquals(listOf("20.4.237", "20.4.236"), options.map { it.version })
    }

    @Test
    fun `从版本 JSON 里取版本号`() {
        val profile = """{"id":"fabric-loader-0.19.3-1.21.8","inheritsFrom":"1.21.8"}"""

        assertEquals(
            "fabric-loader-0.19.3-1.21.8",
            ModLoaderRepository.profileVersionId(profile, "fallback"),
        )
        assertEquals("fallback", ModLoaderRepository.profileVersionId("{}", "fallback"))
    }

    @Test
    fun `各加载器的地址形状正确`() {
        assertTrue(
            ModLoaderRepository.fabricProfileUrl("1.21.8", "0.19.3", DownloadSource.Official)
                .endsWith("/v2/versions/loader/1.21.8/0.19.3/profile/json"),
        )
        assertTrue(
            ModLoaderRepository.fabricLoaderListUrl("1.21.8", DownloadSource.Bmclapi)
                .startsWith("https://bmclapi2.bangbang93.com/fabric-meta/"),
        )
        assertTrue(
            ModLoaderRepository.quiltLoaderListUrl("1.21.8", DownloadSource.Official)
                .startsWith("https://meta.quiltmc.org/v3/versions/loader/"),
        )
        assertEquals(
            "https://maven.minecraftforge.net/net/minecraftforge/forge/1.20.1-47.2.0/" +
                "forge-1.20.1-47.2.0-installer.jar",
            ModLoaderRepository.forgeInstallerUrl("1.20.1", "47.2.0"),
        )
        assertEquals(
            "https://maven.neoforged.net/releases/net/neoforged/neoforge/20.4.237/" +
                "neoforge-20.4.237-installer.jar",
            ModLoaderRepository.neoforgeInstallerUrl("20.4.237"),
        )
        assertTrue(ModLoader.Forge.usesInstaller)
        assertTrue(!ModLoader.Fabric.usesInstaller)
    }

    @Test
    fun `能从安装器 jar 里读出它会生成的版本号`() {
        val jar = installerJar("""{"spec":1,"profile":"forge","version":"1.20.1-forge-47.2.0"}""")

        assertEquals("1.20.1-forge-47.2.0", ModLoaderInstaller().readInstallerVersionId(jar))
    }

    @Test
    fun `安装器里只有 profile 字段时也能读出来`() {
        val jar = installerJar("""{"profile":"1.12.2-forge1.12.2-14.23.5.2859"}""")

        assertEquals("1.12.2-forge1.12.2-14.23.5.2859", ModLoaderInstaller().readInstallerVersionId(jar))
    }

    @Test
    fun `不是安装器 jar 时返回 null`() {
        assertNull(ModLoaderInstaller().readInstallerVersionId(installerJar(null)))
    }

    private fun installerJar(installProfile: String?): Path {
        val jar = tmp.resolve("installer-${System.nanoTime()}.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { out ->
            if (installProfile != null) {
                out.putNextEntry(JarEntry("install_profile.json"))
                out.write(installProfile.toByteArray())
                out.closeEntry()
            }
            out.putNextEntry(JarEntry("dummy.txt"))
            out.write("x".toByteArray())
            out.closeEntry()
        }
        return jar
    }
}
