package com.abi.launcher.download

import com.abi.launcher.LauncherPaths
import com.abi.launcher.launch.RuleContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 安装计划：算出来的下载地址与落盘位置是否正确 */
class InstallPlannerTest {

    private lateinit var tmp: Path
    private lateinit var gameRoot: Path
    private val windows = RuleContext(osName = "windows", osVersion = "10.0", osArch = "x86_64")

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("abi-install")
        gameRoot = tmp.resolve("minecraft")
        LauncherPaths.ensureGameRoot(gameRoot)
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    private fun writeVersion(id: String, json: String): Path {
        val dir = gameRoot.resolve("versions").resolve(id).createDirectories()
        val file = dir.resolve("$id.json")
        file.writeText(json)
        return file
    }

    private fun versionJson(id: String = "1.21.8") = """
        {
          "id": "$id",
          "type": "release",
          "mainClass": "net.minecraft.client.main.Main",
          "assetIndex": {
            "id": "32",
            "sha1": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "size": 1234,
            "url": "https://piston-meta.mojang.com/asset-index/32.json"
          },
          "downloads": {
            "client": {
              "sha1": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
              "size": 39193383,
              "url": "https://piston-data.mojang.com/client.jar"
            }
          },
          "logging": {
            "client": {
              "file": {
                "id": "client-1.21.2.xml",
                "sha1": "cccccccccccccccccccccccccccccccccccccccc",
                "size": 1000,
                "url": "https://piston-data.mojang.com/log.xml"
              }
            }
          },
          "libraries": [
            {
              "name": "com.example:lib-a:1.0",
              "downloads": {
                "artifact": {
                  "path": "com/example/lib-a/1.0/lib-a-1.0.jar",
                  "sha1": "dddddddddddddddddddddddddddddddddddddddd",
                  "size": 100,
                  "url": "https://libraries.minecraft.net/com/example/lib-a/1.0/lib-a-1.0.jar"
                }
              }
            },
            {
              "name": "com.example:lib-linux:1.0",
              "rules": [{"action": "allow", "os": {"name": "linux"}}],
              "downloads": {
                "artifact": {
                  "path": "com/example/lib-linux/1.0/lib-linux-1.0.jar",
                  "sha1": "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
                  "size": 200,
                  "url": "https://libraries.minecraft.net/com/example/lib-linux/1.0/lib-linux-1.0.jar"
                }
              }
            },
            {
              "name": "net.java.jinput:jinput-platform:2.0.10",
              "natives": {"windows": "natives-windows"},
              "downloads": {
                "classifiers": {
                  "natives-windows": {
                    "path": "net/java/jinput/jinput-platform/2.0.10/jinput-platform-2.0.10-natives-windows.jar",
                    "sha1": "ffffffffffffffffffffffffffffffffffffffff",
                    "size": 300,
                    "url": "https://libraries.minecraft.net/net/java/jinput/natives.jar"
                  }
                }
              }
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `计划包含客户端 jar 依赖库 资源索引 日志配置`() {
        val json = writeVersion("1.21.8", versionJson())

        val plan = InstallPlanner.plan(json, "1.21.8", gameRoot, DownloadSource.Official, windows)
        val labels = plan.targets.map { it.label }

        assertEquals(5, plan.targets.size, labels.joinToString())
        assertTrue(labels.any { it == "客户端 1.21.8.jar" })
        assertTrue(labels.any { it == "库 com.example:lib-a:1.0" })
        assertTrue(labels.any { it.startsWith("natives ") })
        assertTrue(labels.any { it == "资源索引 32.json" })
        assertTrue(labels.any { it == "日志配置 client-1.21.2.xml" })
        assertFalse(labels.any { it.contains("lib-linux") }, "Linux 专用库不应出现在 Windows 的计划里")
        assertTrue(plan.warnings.isEmpty(), plan.warnings.joinToString())
    }

    @Test
    fun `官方源的落盘位置与地址`() {
        val json = writeVersion("1.21.8", versionJson())

        val plan = InstallPlanner.plan(json, "1.21.8", gameRoot, DownloadSource.Official, windows)

        val jar = plan.targets.single { it.label.startsWith("客户端 ") }
        assertEquals(gameRoot.resolve("versions").resolve("1.21.8").resolve("1.21.8.jar"), jar.destination)
        assertEquals("https://piston-data.mojang.com/client.jar", jar.url)
        assertEquals("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", jar.sha1)

        val lib = plan.targets.single { it.label == "库 com.example:lib-a:1.0" }
        assertEquals(gameRoot.resolve("libraries/com/example/lib-a/1.0/lib-a-1.0.jar"), lib.destination)

        val index = plan.targets.single { it.label.startsWith("资源索引") }
        assertEquals(gameRoot.resolve("assets/indexes/32.json"), index.destination)

        val natives = plan.targets.single { it.label.startsWith("natives ") }
        assertEquals(
            gameRoot.resolve("libraries/net/java/jinput/jinput-platform/2.0.10/jinput-platform-2.0.10-natives-windows.jar"),
            natives.destination,
        )
        // 每个文件都带了备用源地址
        assertTrue(plan.targets.all { it.alternates.isNotEmpty() || it.label.startsWith("日志配置") })
    }

    @Test
    fun `镜像源会改写下载地址`() {
        val json = writeVersion("1.21.8", versionJson())

        val plan = InstallPlanner.plan(json, "1.21.8", gameRoot, DownloadSource.Bmclapi, windows)

        val jar = plan.targets.single { it.label.startsWith("客户端 ") }
        assertEquals("https://bmclapi2.bangbang93.com/version/1.21.8/client", jar.url)
        assertTrue(jar.alternates.single() == "https://piston-data.mojang.com/client.jar")

        val lib = plan.targets.single { it.label == "库 com.example:lib-a:1.0" }
        assertEquals(
            "https://bmclapi2.bangbang93.com/maven/com/example/lib-a/1.0/lib-a-1.0.jar",
            lib.url,
        )

        // 落盘位置与下载源无关
        assertEquals(gameRoot.resolve("versions/1.21.8/1.21.8.jar"), jar.destination)
    }

    @Test
    fun `资源对象按 hash 前两位分目录`() {
        val hash = "aabbccddeeff00112233445566778899aabbccdd"
        val indexFile = gameRoot.resolve("assets/indexes/32.json")
        indexFile.parent.createDirectories()
        indexFile.writeText(
            """{"objects":{"minecraft/lang/en_us.json":{"hash":"$hash","size":123}}}""",
        )

        val targets = InstallPlanner.assetObjectTargets(indexFile, gameRoot, DownloadSource.Bmclapi)

        assertEquals(1, targets.size)
        val target = targets.single()
        assertEquals(gameRoot.resolve("assets/objects/aa/$hash"), target.destination)
        assertEquals("https://bmclapi2.bangbang93.com/assets/aa/$hash", target.url)
        assertEquals(hash, target.sha1, "资源对象的校验值就是它的 hash")
        assertEquals(123L, target.size)
    }

    @Test
    fun `清单解析出最新版与版本条目`() {
        val manifest = MojangRepository.parseManifest(
            """
            {
              "latest": {"release": "1.21.8", "snapshot": "25w31a"},
              "versions": [
                {"id": "1.21.8", "type": "release", "url": "https://x/1.21.8.json", "releaseTime": "2025-07-17T10:00:00+00:00", "sha1": "abc"},
                {"id": "25w31a", "type": "snapshot", "url": "https://x/25w31a.json", "releaseTime": "2025-08-01T10:00:00+00:00"}
              ]
            }
            """.trimIndent(),
        )

        assertEquals("1.21.8", manifest.latestRelease)
        assertEquals("25w31a", manifest.latestSnapshot)
        assertEquals(listOf("1.21.8", "25w31a"), manifest.versions.map { it.id })
        assertEquals("正式版", manifest.versions.first().category)
        assertEquals("快照", manifest.versions.last().category)
    }

    @Test
    fun `启动器目录结构会被创建`() {
        val root = tmp.resolve("abi-home")

        LauncherPaths.ensureGameRoot(root)

        assertTrue(root.resolve("versions").exists())
        assertTrue(root.resolve("libraries").exists())
        assertTrue(root.resolve("assets/indexes").exists())
        assertTrue(root.resolve("assets/objects").exists())
        assertFalse(LauncherPaths.isVersionInstalled(root, "1.21.8"))
        assertFalse(LauncherPaths.hasClientJar(root, "1.21.8"))

        root.resolve("versions/1.21.8").createDirectories().resolve("1.21.8.jar").writeText("x")
        assertTrue(LauncherPaths.isVersionInstalled(root, "1.21.8"))
        assertTrue(LauncherPaths.hasClientJar(root, "1.21.8"))
    }
}
