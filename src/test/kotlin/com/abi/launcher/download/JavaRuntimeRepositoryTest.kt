package com.abi.launcher.download

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Java 运行时清单的解析与落盘布局 */
class JavaRuntimeRepositoryTest {

    private lateinit var tmp: Path
    private lateinit var gameRoot: Path

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("abi-java")
        gameRoot = tmp.resolve("minecraft").createDirectories()
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    private val allJson = """
        {
          "files": {"一些无关字段": {}},
          "windows-x64": {
            "java-runtime-gamma": [
              {
                "manifest": {
                  "sha1": "abc",
                  "size": 100,
                  "url": "https://piston-meta.mojang.com/v1/products/java-runtime/abc/windows-x64/java-runtime-gamma.json"
                },
                "version": {"name": "17.0.8", "released": "2023-08-01T00:00:00+00:00"}
              }
            ]
          },
          "linux": {
            "java-runtime-epsilon": [
              {
                "manifest": {
                  "sha1": "def",
                  "size": 200,
                  "url": "https://piston-meta.mojang.com/v1/products/java-runtime/def/linux/java-runtime-epsilon.json"
                },
                "version": {"name": "25.0.1"}
              }
            ]
          }
        }
    """.trimIndent()

    private val componentJson = """
        {
          "files": {
            "java-runtime-gamma": {"type": "directory"},
            "java-runtime-gamma/bin": {"type": "directory"},
            "java-runtime-gamma/bin/java.exe": {
              "type": "file",
              "executable": true,
              "downloads": {
                "raw": {
                  "sha1": "aaa",
                  "size": 123,
                  "url": "https://piston-data.mojang.com/v1/objects/aaa/java.exe"
                }
              }
            },
            "java-runtime-gamma/bin/java": {"type": "link", "target": "../java.exe"},
            "java-runtime-gamma/release": {
              "type": "file",
              "downloads": {
                "raw": {
                  "sha1": "bbb",
                  "size": 45,
                  "url": "https://piston-data.mojang.com/v1/objects/bbb/release"
                }
              }
            }
          }
        }
    """.trimIndent()

    @Test
    fun `平台键按系统和架构映射`() {
        assertEquals("windows-x64", JavaRuntimeRepository.platformKey("Windows 11", "amd64"))
        assertEquals("windows-x86", JavaRuntimeRepository.platformKey("Windows 10", "x86"))
        assertEquals("mac-os-arm64", JavaRuntimeRepository.platformKey("Mac OS X", "aarch64"))
        assertEquals("mac-os", JavaRuntimeRepository.platformKey("Mac OS X", "x86_64"))
        assertEquals("linux", JavaRuntimeRepository.platformKey("Linux", "amd64"))
        assertEquals("linux-i386", JavaRuntimeRepository.platformKey("Linux", "i386"))
    }

    @Test
    fun `从 all json 里查出组件清单地址`() {
        val found = JavaRuntimeRepository.parseComponent(allJson, "java-runtime-gamma", "windows-x64")

        assertEquals("java-runtime-gamma", found?.component)
        assertEquals("windows-x64", found?.platform)
        assertEquals("17.0.8", found?.versionName)
        assertTrue(found!!.manifestUrl.contains("java-runtime-gamma.json"))

        // 其它平台上的组件
        assertEquals(
            "java-runtime-epsilon",
            JavaRuntimeRepository.parseComponent(allJson, "java-runtime-epsilon", "linux")?.component,
        )
        // 不存在的组件要返回 null，而不是误把顶层 files 当平台
        assertNull(JavaRuntimeRepository.parseComponent(allJson, "java-runtime-alpha", "windows-x64"))
        assertNull(JavaRuntimeRepository.parseComponent(allJson, "java-runtime-gamma", "files"))
    }

    @Test
    fun `解析组件清单里的文件表`() {
        val files = JavaRuntimeRepository.parseRuntimeFiles(componentJson)

        assertEquals(5, files.size)
        val exe = files.single { it.relativePath.endsWith("java.exe") }
        assertEquals("file", exe.type)
        assertTrue(exe.executable)
        assertEquals("aaa", exe.sha1)
        assertEquals(123L, exe.size)
        assertEquals("https://piston-data.mojang.com/v1/objects/aaa/java.exe", exe.url)

        assertEquals("directory", files.single { it.relativePath == "java-runtime-gamma/bin" }.type)
        assertEquals("link", files.single { it.relativePath.endsWith("/bin/java") }.type)
    }

    @Test
    fun `落盘位置遵循官方布局且只下载 file 类型`() {
        val component = JavaRuntimeRepository.parseComponent(allJson, "java-runtime-gamma", "windows-x64")!!
        val files = JavaRuntimeRepository.parseRuntimeFiles(componentJson)

        val targets = JavaRuntimeRepository.runtimeTargets(files, component, gameRoot, DownloadSource.Official)

        assertEquals(2, targets.size, targets.joinToString { it.label })
        assertEquals(
            gameRoot.resolve("runtime/java-runtime-gamma/windows-x64/java-runtime-gamma/bin/java.exe"),
            targets.single { it.label.endsWith("java.exe") }.destination,
        )
        assertEquals(
            gameRoot.resolve("runtime/java-runtime-gamma/windows-x64/java-runtime-gamma/release"),
            targets.single { it.label.endsWith("release") }.destination,
        )
        // 官方源时主地址就是官方地址，备用地址是镜像
        val exe = targets.single { it.label.endsWith("java.exe") }
        assertEquals("https://piston-data.mojang.com/v1/objects/aaa/java.exe", exe.url)
        assertEquals("https://bmclapi2.bangbang93.com/v1/objects/aaa/java.exe", exe.alternates.single())
    }

    @Test
    fun `镜像源会改写运行时下载地址`() {
        val component = JavaRuntimeRepository.parseComponent(allJson, "java-runtime-gamma", "windows-x64")!!
        val files = JavaRuntimeRepository.parseRuntimeFiles(componentJson)

        val targets = JavaRuntimeRepository.runtimeTargets(files, component, gameRoot, DownloadSource.Bmclapi)

        val exe = targets.single { it.label.endsWith("java.exe") }
        assertEquals("https://bmclapi2.bangbang93.com/v1/objects/aaa/java.exe", exe.url)
        assertEquals("https://piston-data.mojang.com/v1/objects/aaa/java.exe", exe.alternates.single())
    }

    @Test
    fun `官方地址到镜像地址的映射规则`() {
        assertEquals(
            "https://bmclapi2.bangbang93.com/maven/com/example/a.jar",
            DownloadSource.mirrorOf("https://libraries.minecraft.net/com/example/a.jar"),
        )
        assertEquals(
            "https://bmclapi2.bangbang93.com/assets/aa/aabb",
            DownloadSource.mirrorOf("https://resources.download.minecraft.net/aa/aabb"),
        )
        assertEquals(
            "https://bmclapi2.bangbang93.com/v1/objects/x/file",
            DownloadSource.mirrorOf("https://piston-data.mojang.com/v1/objects/x/file"),
        )
        assertEquals(
            "https://bmclapi2.bangbang93.com/mc/game/version_manifest_v2.json",
            DownloadSource.mirrorOf("https://launchermeta.mojang.com/mc/game/version_manifest_v2.json"),
        )
    }
}
