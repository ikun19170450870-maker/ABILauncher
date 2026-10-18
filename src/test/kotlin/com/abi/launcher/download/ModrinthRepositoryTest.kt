package com.abi.launcher.download

import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Modrinth 接口层：URL/筛选条件构造、响应解析、落盘位置 */
class ModrinthRepositoryTest {

    private lateinit var tmp: Path
    private lateinit var gameRoot: Path

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("abi-modrinth")
        gameRoot = tmp.resolve("minecraft")
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    /** 取出并解码某个查询参数（URL 里是 percent-encoding，直接断言字符串会踩到 %3A 这种编码） */
    private fun param(url: String, name: String): String? =
        url.substringAfter('?', "")
            .split('&')
            .firstOrNull { it.startsWith("$name=") }
            ?.substringAfter('=')
            ?.let { URLDecoder.decode(it, "UTF-8") }

    @Test
    fun `搜索地址带加载器与游戏版本筛选`() {
        val url = ModrinthRepository.searchUrl("sodium", ModLoader.Fabric, "1.21.8", limit = 20, offset = 0)

        assertTrue(url.startsWith("https://api.modrinth.com/v2/search?"), url)
        assertEquals("sodium", param(url, "query"))
        val facets = param(url, "facets").orEmpty()
        assertTrue(facets.contains("project_type:mod"), facets)
        assertTrue(facets.contains("categories:fabric"), facets)
        assertTrue(facets.contains("versions:1.21.8"), facets)
        assertTrue(url.contains("limit=20"), url)
        assertTrue(!url.contains("+"), "空格必须编码成 %20 而不是 +：$url")
    }

    @Test
    fun `搜索地址在不过滤时只有 project_type`() {
        val url = ModrinthRepository.searchUrl("", null, null, limit = 10, offset = 5)

        val facets = param(url, "facets").orEmpty()
        assertTrue(facets.contains("project_type:mod"), facets)
        assertTrue(!facets.contains("categories:"), facets)
        assertTrue(!facets.contains("versions:"), facets)
        assertTrue(url.contains("offset=5"), url)
    }

    @Test
    fun `版本地址按加载器与游戏版本过滤`() {
        val url = ModrinthRepository.versionsUrl("sodium", ModLoader.Fabric, "1.21.8")

        assertTrue(url.startsWith("https://api.modrinth.com/v2/project/sodium/version?"), url)
        assertEquals("""["fabric"]""", param(url, "loaders"))
        assertEquals("""["1.21.8"]""", param(url, "game_versions"))
    }

    @Test
    fun `版本地址在不过滤时没有查询参数`() {
        val url = ModrinthRepository.versionsUrl("sodium", null, null)

        assertEquals("https://api.modrinth.com/v2/project/sodium/version", url)
    }

    @Test
    fun `解析搜索结果`() {
        val json = """
            {
              "hits": [
                {
                  "project_id": "AANobbMI",
                  "project_type": "mod",
                  "slug": "sodium",
                  "title": "Sodium",
                  "description": "现代渲染优化",
                  "categories": ["optimization"],
                  "display_categories": ["optimization"],
                  "versions": ["1.20.1", "1.21.8"],
                  "downloads": 43210000,
                  "icon_url": "https://cdn.modrinth.com/data/AANobbMI/icon.png"
                }
              ],
              "offset": 0,
              "limit": 20,
              "total_hits": 1
            }
        """.trimIndent()

        val projects = ModrinthRepository.parseSearch(json)

        assertEquals(1, projects.size)
        val project = projects.single()
        assertEquals("AANobbMI", project.id)
        assertEquals("sodium", project.slug)
        assertEquals("Sodium", project.title)
        assertEquals(43210000L, project.downloads)
        assertEquals(listOf("optimization"), project.categories)
        assertEquals(listOf("1.20.1", "1.21.8"), project.gameVersions)
        assertEquals("https://cdn.modrinth.com/data/AANobbMI/icon.png", project.iconUrl)
        assertTrue(project.summary.contains("43210k 下载"), project.summary)
    }

    @Test
    fun `解析版本列表并挑出主文件`() {
        val json = """
            [
              {
                "id": "ver1",
                "name": "Sodium 0.6.0",
                "version_number": "mc1.21.8-0.6.0",
                "version_type": "release",
                "loaders": ["fabric"],
                "game_versions": ["1.21.8"],
                "date_published": "2025-07-17T10:00:00Z",
                "downloads": 123456,
                "files": [
                  {"filename":"sodium-extra.jar","url":"https://cdn.modrinth.com/data/x/extra.jar",
                   "hashes":{"sha1":"1111111111111111111111111111111111111111","sha512":"x"},"size":10,"primary":false},
                  {"filename":"sodium.jar","url":"https://cdn.modrinth.com/data/x/sodium.jar",
                   "hashes":{"sha1":"2222222222222222222222222222222222222222"},"size":999,"primary":true}
                ]
              }
            ]
        """.trimIndent()

        val versions = ModrinthRepository.parseVersions(json)

        assertEquals(1, versions.size)
        val version = versions.single()
        assertEquals("mc1.21.8-0.6.0", version.versionNumber)
        assertEquals("release", version.versionType)
        assertEquals(listOf("fabric"), version.loaders)
        assertEquals(2, version.files.size)
        assertEquals("sodium.jar", version.primaryFile?.filename, "应挑出 primary=true 的文件")
        assertEquals("2222222222222222222222222222222222222222", version.primaryFile?.sha1)
        assertTrue(version.displayName.contains("fabric"))
    }

    @Test
    fun `没有 primary 标记时取第一个文件`() {
        val json = """
            [{"id":"v","version_number":"1.0","version_type":"beta","files":[
              {"filename":"a.jar","url":"https://cdn.modrinth.com/a.jar","size":1},
              {"filename":"b.jar","url":"https://cdn.modrinth.com/b.jar","size":2}]}]
        """.trimIndent()

        val version = ModrinthRepository.parseVersions(json).single()

        assertEquals("a.jar", version.primaryFile?.filename)
        assertNull(version.primaryFile?.sha1, "没有 hashes 时 sha1 为空，下载器会跳过校验")
    }

    @Test
    fun `下载任务落到 mods 目录并带上 SHA-1`() {
        val file = ModrinthFile(
            filename = "sodium.jar",
            url = "https://cdn.modrinth.com/data/x/sodium.jar",
            sha1 = "abc123",
            size = 1024,
            primary = true,
        )

        val target = ModrinthRepository.downloadTarget(file, gameRoot)

        assertEquals(gameRoot.resolve("mods/sodium.jar"), target.destination)
        assertEquals("https://cdn.modrinth.com/data/x/sodium.jar", target.url)
        assertEquals("abc123", target.sha1)
        assertEquals(1024L, target.size)
        assertEquals(gameRoot.resolve("mods"), ModrinthRepository.modsDirectory(gameRoot))
    }

    @Test
    fun `搜索结果的 total_hits 用来算总页数`() {
        val json = """{"hits":[],"total_hits":1234}"""

        assertEquals(1234, ModrinthRepository.parseTotalHits(json))
        assertEquals(1234, ModrinthRepository.parseSearchResult(json).totalHits)
        assertEquals(0, ModrinthRepository.parseTotalHits("{}"))
    }

    @Test
    fun `分页参数会写进搜索地址`() {
        val url = ModrinthRepository.searchUrl("", null, null, limit = 40, offset = 80)

        assertTrue(url.contains("limit=40"), url)
        assertTrue(url.contains("offset=80"), url)
    }

    @Test
    fun `没有 icon_url 时图标为空`() {
        val json = """{"hits":[{"project_id":"x","title":"X","description":"","downloads":1}]}"""

        val project = ModrinthRepository.parseSearch(json).single()

        assertNull(project.iconUrl)
    }

    @Test
    fun `图标缓存文件名稳定且唯一`() {
        val a = IconCache.fileNameOf("https://cdn.modrinth.com/data/a/icon.png")
        val b = IconCache.fileNameOf("https://cdn.modrinth.com/data/a/icon.png")
        val c = IconCache.fileNameOf("https://cdn.modrinth.com/data/b/icon.png")

        assertEquals(a, b)
        assertTrue(a != c)
        assertTrue(a.endsWith(".png"))
    }

    @Test
    fun `光影和资源包不按加载器筛选`() {
        val shader = param(
            ModrinthRepository.searchUrl("", ModLoader.Fabric, "1.21.8", 20, 0, ModrinthProjectType.Shader),
            "facets",
        ).orEmpty()
        assertTrue(shader.contains("project_type:shader"), shader)
        assertTrue(!shader.contains("categories:"), "光影跟加载器无关：$shader")
        assertTrue(shader.contains("versions:1.21.8"), shader)

        val resourcePack = param(
            ModrinthRepository.searchUrl("", ModLoader.Fabric, "1.21.8", 20, 0, ModrinthProjectType.ResourcePack),
            "facets",
        ).orEmpty()
        assertTrue(resourcePack.contains("project_type:resourcepack"), resourcePack)
        assertTrue(!resourcePack.contains("categories:"), resourcePack)

        val modPack = param(
            ModrinthRepository.searchUrl("", ModLoader.Fabric, "1.21.8", 20, 0, ModrinthProjectType.ModPack),
            "facets",
        ).orEmpty()
        assertTrue(modPack.contains("project_type:modpack"), modPack)
        assertTrue(modPack.contains("categories:fabric"), modPack)
    }

    @Test
    fun `光影的版本地址不带加载器过滤`() {
        val url = ModrinthRepository.versionsUrl("bliss", ModLoader.Fabric, "1.21.8", ModrinthProjectType.Shader)

        assertTrue(!url.contains("loaders="), url)
        assertTrue(url.contains("game_versions="), url)
    }

    @Test
    fun `各类型下载到不同目录`() {
        fun file(name: String) = ModrinthFile(
            filename = name,
            url = "https://cdn.modrinth.com/data/x/$name",
            sha1 = "abc",
            size = 10,
            primary = true,
        )

        assertEquals(
            gameRoot.resolve("mods/a.jar"),
            ModrinthRepository.downloadTarget(file("a.jar"), gameRoot, ModrinthProjectType.Mod).destination,
        )
        assertEquals(
            gameRoot.resolve("shaderpacks/a.zip"),
            ModrinthRepository.downloadTarget(file("a.zip"), gameRoot, ModrinthProjectType.Shader).destination,
        )
        assertEquals(
            gameRoot.resolve("resourcepacks/a.zip"),
            ModrinthRepository.downloadTarget(file("a.zip"), gameRoot, ModrinthProjectType.ResourcePack).destination,
        )
        assertNull(
            ModrinthRepository.directoryFor(gameRoot, ModrinthProjectType.ModPack),
            "整合包没有单一目录（要走整套安装）",
        )
    }

    @Test
    fun `加载器标签与版本号推断`() {
        assertEquals("fabric", ModrinthRepository.loaderTag(ModLoader.Fabric))
        assertEquals("quilt", ModrinthRepository.loaderTag(ModLoader.Quilt))
        assertEquals("forge", ModrinthRepository.loaderTag(ModLoader.Forge))
        assertEquals("neoforge", ModrinthRepository.loaderTag(ModLoader.NeoForge))
        assertNull(ModrinthRepository.loaderTag(ModLoader.Vanilla))
        assertNull(ModrinthRepository.loaderTag(null))

        assertEquals(ModLoader.Fabric, ModrinthRepository.guessLoader("fabric-loader-0.19.3-1.21.8"))
        assertEquals(ModLoader.Quilt, ModrinthRepository.guessLoader("quilt-loader-0.27.1-1.21.8"))
        assertEquals(ModLoader.NeoForge, ModrinthRepository.guessLoader("1.21.1-NeoForge_21.1.0"))
        assertEquals(ModLoader.Forge, ModrinthRepository.guessLoader("1.20.1-forge-47.2.0"))
        assertNull(ModrinthRepository.guessLoader("1.21.8"))
    }
}
