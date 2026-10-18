package com.abi.launcher.launch

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LaunchCommandBuilderTest {

    private lateinit var tmp: Path
    private lateinit var root: Path

    /** 测试里不真的跑 java，只用来占位 */
    private val java = JavaRuntime(Path.of("java"), 21, "test")

    private val windows = RuleContext(osName = "windows", osVersion = "10.0", osArch = "x86_64")

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("abi-launch")
        root = tmp.resolve(".minecraft").createDirectories()
        root.resolve("assets").resolve("indexes").createDirectories()
        root.resolve("assets").resolve("indexes").resolve("test.json").writeText("""{"objects":{}}""")
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    // ---------- 工具 ----------

    private fun writeVersion(id: String, json: String, withJar: Boolean = true): Path {
        val dir = root.resolve("versions").resolve(id).createDirectories()
        val file = dir.resolve("$id.json")
        file.writeText(json)
        if (withJar) dir.resolve("$id.jar").createFile()
        return file
    }

    private fun writeLibrary(relativePath: String) {
        val file = root.resolve("libraries").resolve(relativePath)
        Files.createDirectories(file.parent)
        file.createFile()
    }

    private fun zipWithEntry(target: Path, entryName: String) {
        Files.createDirectories(target.parent)
        ZipOutputStream(Files.newOutputStream(target)).use { zip ->
            zip.putNextEntry(ZipEntry(entryName))
            zip.write("fake-native".toByteArray())
            zip.closeEntry()
        }
    }

    private fun plan(versionJson: Path, options: LaunchOptions = LaunchOptions()) = LaunchCommandBuilder.plan(
        version = VersionSpecReader.resolve(versionJson, root),
        versionDir = versionJson.parent,
        installRoot = root,
        java = java,
        options = options,
        ruleContext = windows,
    )

    private fun simpleJson(
        id: String,
        libraries: String = "[]",
        game: String = """["--username","${'$'}{auth_player_name}"]""",
        jvm: String = """["-cp","${'$'}{classpath}"]""",
        mainClass: String = "net.minecraft.client.main.Main",
    ) = """
        {
          "id": "$id",
          "type": "release",
          "mainClass": "$mainClass",
          "assetIndex": {"id": "test"},
          "javaVersion": {"majorVersion": 21},
          "libraries": $libraries,
          "arguments": {"jvm": $jvm, "game": $game}
        }
    """.trimIndent()

    // ---------- 用例 ----------

    @Test
    fun `按规则过滤掉其他系统的库`() {
        writeLibrary("org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3.jar")
        writeLibrary("org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-windows.jar")

        val json = writeVersion(
            "1.21",
            simpleJson(
                "1.21",
                libraries = """
                    [
                      {"name":"org.lwjgl:lwjgl:3.3.3"},
                      {"name":"org.lwjgl:lwjgl:3.3.3:natives-windows",
                       "rules":[{"action":"allow","os":{"name":"windows"}}]},
                      {"name":"org.lwjgl:lwjgl:3.3.3:natives-linux",
                       "rules":[{"action":"allow","os":{"name":"linux"}}]},
                      {"name":"com.example:mac-only:1.0",
                       "rules":[{"action":"allow","os":{"name":"osx"}}]}
                    ]
                """.trimIndent(),
            ),
        )

        val plan = plan(json)

        val names = plan.classpath.map { it.fileName.toString() }
        assertTrue(names.contains("lwjgl-3.3.3.jar"), "基础库应进 classpath: $names")
        assertTrue(names.contains("lwjgl-3.3.3-natives-windows.jar"), "Windows natives 应进 classpath: $names")
        assertFalse(names.any { it.contains("natives-linux") }, "Linux natives 不应出现: $names")
        assertFalse(names.any { it.contains("mac-only") }, "macOS 专用库不应出现: $names")
        assertTrue(plan.preflight.isReady, plan.preflight.summary())
    }

    @Test
    fun `旧版 natives 分类库会被解压且不进入 classpath`() {
        writeLibrary("net/java/jinput/jinput/2.0.10/jinput-2.0.10.jar")
        val nativeJar = root.resolve("libraries/net/java/jinput/jinput-platform/2.0.10/jinput-platform-2.0.10-natives-windows.jar")
        zipWithEntry(nativeJar, "jinput-dx8.dll")

        val json = writeVersion(
            "1.6.4",
            simpleJson(
                "1.6.4",
                libraries = """
                    [
                      {"name":"net.java.jinput:jinput:2.0.10"},
                      {"name":"net.java.jinput:jinput-platform:2.0.10",
                       "natives":{"windows":"natives-windows"},
                       "downloads":{"classifiers":{
                         "natives-windows":{"path":"net/java/jinput/jinput-platform/2.0.10/jinput-platform-2.0.10-natives-windows.jar"}
                       }}}
                    ]
                """.trimIndent(),
                jvm = """["-Djava.library.path=${'$'}{natives_directory}","-cp","${'$'}{classpath}"]""",
            ),
        )

        val plan = plan(json)

        assertEquals(1, plan.nativesToExtract.size)
        assertEquals(nativeJar, plan.nativesToExtract.single().jar)
        assertFalse(
            plan.classpath.any { it.fileName.toString().contains("natives-windows") },
            "分类 natives 库不应进 classpath: ${plan.classpath}",
        )
        assertFalse(
            plan.classpath.any { it.fileName.toString() == "jinput-platform-2.0.10.jar" },
            "纯 natives 库不应被猜出一条主 jar 路径: ${plan.classpath}",
        )
        assertTrue(plan.preflight.isReady, "纯 natives 库不应导致预检失败: ${plan.preflight.summary()}")
        assertTrue(plan.command.contains("-Djava.library.path=${plan.nativesDirectory}"))

        // 真正解压
        NativesExtractor.extract(plan.nativesToExtract.single(), plan.nativesDirectory)
        assertTrue(plan.nativesDirectory.resolve("jinput-dx8.dll").toFile().exists(), "dll 应被解压出来")
    }

    @Test
    fun `没有 artifact path 时按 Maven 坐标推断路径`() {
        writeLibrary("com/example/demo/1.2.3/demo-1.2.3.jar")
        val json = writeVersion(
            "1.21",
            simpleJson("1.21", libraries = """[{"name":"com.example:demo:1.2.3"}]"""),
        )

        val plan = plan(json)

        assertTrue(
            plan.classpath.any { it.toString().replace('\\', '/').endsWith("com/example/demo/1.2.3/demo-1.2.3.jar") },
            "应推断出 Maven 路径: ${plan.classpath}",
        )
        assertTrue(plan.preflight.isReady)
    }

    @Test
    fun `占位符会被替换且离线 UUID 稳定`() {
        val json = writeVersion(
            "1.21",
            simpleJson(
                "1.21",
                game = """
                    ["--username","${'$'}{auth_player_name}","--uuid","${'$'}{auth_uuid}",
                     "--gameDir","${'$'}{game_directory}","--assetIndex","${'$'}{assets_index_name}",
                     "--userType","${'$'}{user_type}","--versionType","${'$'}{version_type}"]
                """.trimIndent(),
            ),
        )

        val plan = plan(json, LaunchOptions(playerName = "Steve"))

        assertTrue(plan.command.contains("Steve"))
        assertTrue(plan.command.contains("test"), "assets_index_name 应被替换")
        assertTrue(plan.command.contains("msa"))
        assertTrue(plan.command.contains("release"))
        assertFalse(plan.command.any { it.contains("\${") }, "不应残留未替换的占位符: ${plan.command}")
        assertTrue(plan.command.contains(LaunchCommandBuilder.offlineUuid("Steve")))
        // 同名玩家 UUID 必须一致
        assertEquals(LaunchCommandBuilder.offlineUuid("Steve"), LaunchCommandBuilder.offlineUuid("Steve"))
        assertTrue(plan.warnings.none { it.contains("占位符") }, "不应有占位符警告: ${plan.warnings}")
    }

    @Test
    fun `classpath 用系统分隔符连接`() {
        writeLibrary("com/example/demo/1.2.3/demo-1.2.3.jar")
        val json = writeVersion(
            "1.21",
            simpleJson(
                "1.21",
                libraries = """[{"name":"com.example:demo:1.2.3"}]""",
                jvm = """["-cp","${'$'}{classpath}","-Dsep=${'$'}{classpath_separator}"]""",
            ),
        )

        val plan = plan(json)

        val classpathValue = plan.command[plan.command.indexOf("-cp") + 1]
        assertEquals(File.pathSeparator, plan.command.first { it.startsWith("-Dsep=") }.removePrefix("-Dsep="))
        assertEquals(plan.classpath.joinToString(File.pathSeparator), classpathValue)
    }

    @Test
    fun `缺少客户端 jar 时预检不通过`() {
        val json = writeVersion("1.21", simpleJson("1.21"), withJar = false)

        val plan = plan(json)

        assertFalse(plan.isReady)
        assertTrue(plan.preflight.missingClientJar.isNotEmpty())
        assertTrue(plan.preflight.summary().contains("缺少客户端 jar"), plan.preflight.summary())
    }

    @Test
    fun `缺少依赖库时预检会列出`() {
        val json = writeVersion(
            "1.21",
            simpleJson("1.21", libraries = """[{"name":"com.example:missing:9.9"}]"""),
        )

        val plan = plan(json)

        assertFalse(plan.isReady)
        assertEquals(1, plan.preflight.missingLibraries.size)
        assertTrue(plan.preflight.summary().contains("缺少 1 个依赖库"), plan.preflight.summary())
    }

    @Test
    fun `子版本继承父版本并合并库与参数`() {
        // 父版本：原版 1.20.1
        val parent = writeVersion(
            "1.20.1",
            """
            {
              "id": "1.20.1",
              "type": "release",
              "mainClass": "net.minecraft.client.main.Main",
              "assetIndex": {"id": "test"},
              "javaVersion": {"majorVersion": 17},
              "libraries": [{"name":"com.example:base:1.0"},{"name":"com.example:override:1.0"}],
              "arguments": {
                "jvm": ["-cp", "${'$'}{classpath}"],
                "game": ["--username","${'$'}{auth_player_name}"]
              }
            }
            """.trimIndent(),
        )
        writeLibrary("com/example/base/1.0/base-1.0.jar")
        writeLibrary("com/example/override/1.0/override-1.0.jar")
        writeLibrary("com/example/override/2.0/override-2.0.jar")
        writeLibrary("com/example/modloader/1.0/modloader-1.0.jar")

        // 子版本：模组端，没有自己的 jar，也不声明 javaVersion
        val childDir = root.resolve("versions").resolve("1.20.1-forge").createDirectories()
        val child = childDir.resolve("1.20.1-forge.json")
        child.writeText(
            """
            {
              "id": "1.20.1-forge",
              "inheritsFrom": "1.20.1",
              "mainClass": "cpw.mods.bootstraplauncher.BootstrapLauncher",
              "libraries": [
                {"name":"com.example:override:2.0"},
                {"name":"com.example:modloader:1.0"}
              ],
              "arguments": {"game": ["--forgeArg"]}
            }
            """.trimIndent(),
        )

        val spec = VersionSpecReader.resolve(child, root)

        assertEquals("cpw.mods.bootstraplauncher.BootstrapLauncher", spec.mainClass, "mainClass 应被子版本覆盖")
        assertEquals(17, spec.javaMajorVersion, "javaVersion 应继承父版本")
        assertEquals("test", spec.assetIndexId, "assetIndex 应继承父版本")
        assertEquals(
            listOf("com.example:base:1.0", "com.example:override:2.0", "com.example:modloader:1.0"),
            spec.libraries.map { it.name },
            "同名库应被子版本覆盖且保持父版本顺序",
        )
        assertTrue(spec.gameArguments.flatMap { it.value }.contains("--forgeArg"), "子版本参数应保留")
        assertTrue(spec.gameArguments.flatMap { it.value }.contains("--username"), "父版本参数应保留")

        // 父版本的 jar 应作为候选，从而预检通过
        val plan = LaunchCommandBuilder.plan(spec, childDir, root, java, LaunchOptions(), windows)
        assertTrue(
            plan.classpath.any { it.fileName.toString() == "1.20.1.jar" },
            "应回退到父版本的客户端 jar: ${plan.classpath}",
        )
        assertTrue(plan.preflight.isReady, plan.preflight.summary())
        assertTrue(plan.command.contains("cpw.mods.bootstraplauncher.BootstrapLauncher"))
        assertTrue(plan.command.contains("--forgeArg"))
    }

    @Test
    fun `同名库按 group-artifact 去重但保留不同分类库`() {
        writeVersion(
            "1.20.1",
            """
            {
              "id": "1.20.1",
              "mainClass": "net.minecraft.client.main.Main",
              "libraries": [
                {"name":"com.example:override:1.0"},
                {"name":"org.lwjgl:lwjgl:3.3.2"},
                {"name":"org.lwjgl:lwjgl:3.3.2:natives-windows"}
              ]
            }
            """.trimIndent(),
        )

        val childDir = root.resolve("versions").resolve("1.20.1-forge").createDirectories()
        val child = childDir.resolve("1.20.1-forge.json")
        child.writeText(
            """
            {
              "id": "1.20.1-forge",
              "inheritsFrom": "1.20.1",
              "mainClass": "cpw.mods.bootstraplauncher.BootstrapLauncher",
              "libraries": [
                {"name":"com.example:override:2.0"},
                {"name":"org.lwjgl:lwjgl:3.3.3"}
              ]
            }
            """.trimIndent(),
        )

        val merged = VersionSpecReader.resolve(child, root).libraries.map { it.name }

        assertEquals(
            listOf(
                "com.example:override:2.0",
                "org.lwjgl:lwjgl:3.3.3",
                "org.lwjgl:lwjgl:3.3.2:natives-windows",
            ),
            merged,
            "同 group:artifact 只保留子版本，但不同 classifier 的库必须各自保留",
        )
    }

    @Test
    fun `父版本缺失时会给出警告并仍可解析`() {
        val childDir = root.resolve("versions").resolve("orphan").createDirectories()
        val child = childDir.resolve("orphan.json")
        child.writeText(
            """
            {"id":"orphan","inheritsFrom":"not-installed","mainClass":"X",
             "arguments":{"game":["--username","${'$'}{auth_player_name}"]}}
            """.trimIndent(),
        )

        val spec = VersionSpecReader.resolve(child, root)

        assertEquals("not-installed", spec.missingParent)
        assertEquals("X", spec.mainClass)
    }

    @Test
    fun `旧版 minecraftArguments 也能生成参数`() {
        val json = writeVersion(
            "1.6.4",
            """
            {
              "id": "1.6.4",
              "mainClass": "net.minecraft.client.Minecraft",
              "minecraftArguments": "--username ${'$'}{auth_player_name} --session ${'$'}{auth_session}",
              "libraries": []
            }
            """.trimIndent(),
        )

        val plan = plan(json, LaunchOptions(playerName = "Alex"))

        assertTrue(plan.command.contains("--username"))
        assertTrue(plan.command.contains("Alex"))
        assertTrue(plan.command.any { it.startsWith("--session") || it.startsWith("token:") })
        // 老版本没有 arguments.jvm，应自动补上 java.library.path 与 -cp
        assertTrue(plan.command.any { it.startsWith("-Djava.library.path=") })
        assertTrue(plan.command.contains("-cp"))
    }

    @Test
    fun `Java 大版本解析兼容 1_8 写法`() {
        assertEquals(8, JavaRuntimeLocator.parseMajor("1.8.0_402"))
        assertEquals(17, JavaRuntimeLocator.parseMajor("17.0.9"))
        assertEquals(25, JavaRuntimeLocator.parseMajor("25.0.3"))
        assertEquals(21, JavaRuntimeLocator.parseMajor("21"))
    }
}
