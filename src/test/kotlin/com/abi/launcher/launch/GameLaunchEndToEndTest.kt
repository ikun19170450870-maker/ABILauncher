package com.abi.launcher.launch

import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 端到端验证：造一个「长得像 Minecraft」的版本（假的 mainClass），
 * 走完 解析版本 JSON → 组装命令行 → 真正启动 JVM 进程 → 拿到输出与退出码 的全过程。
 */
class GameLaunchEndToEndTest {

    private lateinit var tmp: Path
    private lateinit var root: Path

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("abi-launch-e2e")
        root = tmp.resolve(".minecraft").createDirectories()
        root.resolve("assets").resolve("indexes").createDirectories()
        root.resolve("assets").resolve("indexes").resolve("test.json").writeText("""{"objects":{}}""")
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    @Test
    fun `完整启动一个 Minecraft 结构的版本并收到游戏输出`() {
        val versionDir = root.resolve("versions").resolve("fake").createDirectories()

        // 1. 用当前 JDK 自带编译器编一个假游戏主类，并打成 jar
        val classesDir = compileFakeGame(tmp.resolve("fakesrc"))
        jarDirectory(classesDir, versionDir.resolve("fake.jar"))

        // 2. 一个普通的依赖库
        jarDirectory(Files.createDirectories(tmp.resolve("libsrc-stub")), root.resolve("libraries/com/example/demo/1.0/demo-1.0.jar"))

        // 3. 版本 JSON
        val versionJson = versionDir.resolve("fake.json")
        versionJson.writeText(
            """
            {
              "id": "fake",
              "type": "release",
              "mainClass": "FakeMain",
              "assetIndex": {"id": "test"},
              "javaVersion": {"majorVersion": 21},
              "libraries": [{"name": "com.example:demo:1.0"}],
              "arguments": {
                "jvm": ["-cp", "${'$'}{classpath}"],
                "game": ["--username", "${'$'}{auth_player_name}",
                         "--gameDir", "${'$'}{game_directory}",
                         "--nativesDir", "${'$'}{natives_directory}"]
              }
            }
            """.trimIndent(),
        )

        val runtime = currentJvm()
        val spec = VersionSpecReader.resolve(versionJson, root)
        val plan = LaunchCommandBuilder.plan(
            version = spec,
            versionDir = versionDir,
            installRoot = root,
            java = runtime,
            options = LaunchOptions(playerName = "测试玩家", maxMemoryMb = 512),
        )

        assertTrue(plan.isReady, "预检应通过: ${plan.preflight.summary()}")

        // 4. 真正启动
        val output = Collections.synchronizedList(mutableListOf<String>())
        val exitCode = AtomicInteger(Int.MIN_VALUE)
        val process = GameLauncher.launch(
            plan,
            onOutput = { line -> output += line },
            onExit = { code -> exitCode.set(code) },
        )
        val code = process.waitFor()
        val text = output.joinToString("\n")

        assertEquals(0, code, "假游戏应以 0 退出，输出:\n$text")
        assertEquals(0, exitCode.get())
        assertTrue(text.contains("FAKE_GAME_STARTED"), "应收到游戏输出:\n$text")
        assertTrue(text.contains("FAKE_GAME_EXIT"), "游戏应正常跑完:\n$text")

        // 参数与占位符替换结果
        assertTrue(text.contains("ARG0=--username"), text)
        assertTrue(text.contains("ARG1=测试玩家"), "中文玩家名应原样传递（含 UTF-8 处理）:\n$text")
        assertTrue(text.contains("ARG2=--gameDir"), text)
        assertTrue(text.contains("ARG3=${root}"), text)
        assertTrue(text.contains("ARG4=--nativesDir"), text)
        assertTrue(text.contains("ARG5=${plan.nativesDirectory}"), text)

        // classpath 真的传进去了：假游戏自身 + 一个库 = 2 项
        assertTrue(text.contains("CP_ENTRIES=2"), "classpath 应有 2 项:\n$text")
    }

    @Test
    fun `文件不完整时拒绝启动`() {
        val versionDir = root.resolve("versions").resolve("broken").createDirectories()
        val versionJson = versionDir.resolve("broken.json")
        versionJson.writeText(
            """
            {"id":"broken","mainClass":"X","assetIndex":{"id":"test"},"libraries":[],
             "arguments":{"jvm":["-cp","${'$'}{classpath}"],"game":[]}}
            """.trimIndent(),
        )

        val spec = VersionSpecReader.resolve(versionJson, root)
        val plan = LaunchCommandBuilder.plan(spec, versionDir, root, currentJvm(), LaunchOptions())

        assertTrue(!plan.isReady)
        val failure = runCatching { GameLauncher.launch(plan, onOutput = {}, onExit = {}) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException, "应因预检失败抛错，实际: $failure")
    }

    // ---------- 工具 ----------

    private fun currentJvm(): JavaRuntime {
        val home = Path.of(System.getProperty("java.home"))
        val executable = home.resolve("bin").resolve(if (isWindows()) "java.exe" else "java")
        return JavaRuntime(executable, Runtime.version().feature(), "当前 JVM")
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("win")

    private fun compileFakeGame(workDir: Path): Path {
        val source = workDir.createDirectories().resolve("FakeMain.java")
        source.writeText(
            """
            public class FakeMain {
                public static void main(String[] args) {
                    System.out.println("FAKE_GAME_STARTED");
                    String cp = System.getProperty("java.class.path");
                    System.out.println("CP_ENTRIES=" + cp.split(java.io.File.pathSeparator).length);
                    for (int i = 0; i < args.length; i++) {
                        System.out.println("ARG" + i + "=" + args[i]);
                    }
                    System.out.println("FAKE_GAME_EXIT");
                }
            }
            """.trimIndent(),
        )

        val outputDir = workDir.resolve("classes")
        Files.createDirectories(outputDir)
        val compiler = ToolProvider.getSystemJavaCompiler()
            ?: error("当前 JVM 没有 javac（测试需要 JDK 而不是 JRE）")
        val exitCode = compiler.run(null, null, null, "-d", outputDir.toString(), source.toString())
        check(exitCode == 0) { "javac 编译假游戏失败" }
        return outputDir
    }

    private fun jarDirectory(classesDir: Path, jarFile: Path) {
        Files.createDirectories(jarFile.parent)
        JarOutputStream(Files.newOutputStream(jarFile)).use { jar ->
            if (!Files.exists(classesDir)) {
                // 空 jar 也要是个合法的 zip
                jar.putNextEntry(JarEntry("placeholder.txt"))
                jar.write("placeholder".toByteArray())
                jar.closeEntry()
                return@use
            }
            Files.walk(classesDir).use { stream ->
                stream.filter { Files.isRegularFile(it) }.forEach { file ->
                    val entryName = classesDir.relativize(file).toString().replace('\\', '/')
                    jar.putNextEntry(JarEntry(entryName))
                    Files.copy(file, jar)
                    jar.closeEntry()
                }
            }
        }
    }
}
