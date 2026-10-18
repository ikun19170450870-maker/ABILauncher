package com.abi.launcher.launch

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile

data class JavaRuntime(
    val javaExecutable: Path,
    val major: Int,
    /** 来源说明，便于排查 */
    val source: String,
) {
    val label: String get() = "Java $major  ·  $javaExecutable"
}

/**
 * 找可用的 Java。优先精确匹配版本 JSON 要求的 javaVersion.majorVersion，
 * 找不到就退而求其次（更高的最小版本 → 最高的可用版本），并让调用方给出警告。
 */
object JavaRuntimeLocator {

    @Volatile
    private var cached: List<JavaRuntime>? = null

    fun findFor(requiredMajor: Int?, extraRoots: List<Path> = emptyList()): JavaRuntime? {
        val all = all(extraRoots)
        if (all.isEmpty()) return null
        if (requiredMajor == null) return all.last()

        return all.firstOrNull { it.major == requiredMajor }
            ?: all.filter { it.major > requiredMajor }.minByOrNull { it.major }
            ?: all.last()
    }

    fun all(extraRoots: List<Path> = emptyList()): List<JavaRuntime> {
        if (extraRoots.isEmpty()) cached?.let { return it }

        val found = LinkedHashMap<Path, JavaRuntime>()
        candidateExecutables(extraRoots).forEach { executable ->
            describe(executable)?.let { found[it.javaExecutable] = it }
        }
        val list = found.values.sortedBy { it.major }
        if (extraRoots.isEmpty()) cached = list
        return list
    }

    private fun candidateExecutables(extraRoots: List<Path>): List<Path> {
        val candidates = LinkedHashSet<Path>()

        // 1. 当前运行启动器的 JVM
        runCatching { Path.of(System.getProperty("java.home")) }
            .getOrNull()?.let { home -> candidates.add(executableIn(home)) }

        // 2. 常见 JDK 安装位置
        val programFiles = listOfNotNull(
            System.getenv("ProgramFiles"),
            System.getenv("ProgramFiles(x86)"),
        )
        val vendorDirectories = listOf("Java", "Eclipse Adoptium", "Microsoft", "Amazon Corretto", "Zulu", "BellSoft")
        val roots = mutableListOf<Path>()
        programFiles.forEach { base ->
            vendorDirectories.forEach { vendor -> roots.add(Path.of(base, vendor)) }
        }
        System.getenv("LOCALAPPDATA")?.let { roots.add(Path.of(it, "Programs", "Eclipse Adoptium")) }
        System.getProperty("user.home")?.let { roots.add(Path.of(it, ".gradle", "jdks")) }
        roots.addAll(extraRoots)

        roots.filter { Files.isDirectory(it) }.forEach { root ->
            runCatching {
                Files.newDirectoryStream(root).use { stream ->
                    stream.filter { Files.isDirectory(it) }.forEach { child -> candidates.add(executableIn(child)) }
                }
            }
        }

        // 3. 官方启动器风格的 runtime/<组件>/<平台>/<组件>/bin/java.exe
        extraRoots.filter { Files.isDirectory(it) }.forEach { root ->
            runCatching {
                Files.walk(root, 5).use { stream ->
                    stream.filter { it.fileName?.toString() == "java.exe" || it.fileName?.toString() == "java" }
                        .forEach { found -> candidates.add(found) }
                }
            }
        }

        return candidates.filter { it.isRegularFile() }
    }

    private fun executableIn(home: Path): Path =
        home.resolve("bin").resolve(if (isWindows()) "java.exe" else "java")

    private fun describe(executable: Path): JavaRuntime? {
        if (!executable.isRegularFile()) return null
        val home = executable.parent?.parent ?: return null
        val major = readMajorFromReleaseFile(home) ?: readMajorByRunning(executable) ?: return null
        return JavaRuntime(executable, major, home.toString())
    }

    /** JDK 目录里的 release 文件比启动一次 java -version 快得多 */
    private fun readMajorFromReleaseFile(home: Path): Int? = runCatching {
        val release = home.resolve("release")
        if (!release.isRegularFile()) return null
        val line = Files.readAllLines(release).firstOrNull { it.startsWith("JAVA_VERSION=") } ?: return null
        parseMajor(line.substringAfter('=').trim().trim('"'))
    }.getOrNull()

    private fun readMajorByRunning(executable: Path): Int? = runCatching {
        val process = ProcessBuilder(executable.toString(), "-version")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        val version = Regex("\"([0-9][0-9._]*)\"").find(output)?.groupValues?.get(1) ?: return null
        parseMajor(version)
    }.getOrNull()

    /** "25.0.3" -> 25；"1.8.0_402" -> 8 */
    fun parseMajor(version: String): Int? {
        val cleaned = version.trim().trim('"')
        if (cleaned.isEmpty()) return null
        val parts = cleaned.split('.', '_', '-')
        val first = parts.firstOrNull()?.toIntOrNull() ?: return null
        if (first != 1) return first
        return parts.getOrNull(1)?.toIntOrNull()
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("win")
}
