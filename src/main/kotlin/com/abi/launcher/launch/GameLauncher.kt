package com.abi.launcher.launch

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

class GameProcess internal constructor(
    private val process: Process,
    private val waiter: Thread,
) {
    val isAlive: Boolean get() = process.isAlive
    val pid: Long get() = process.pid()

    /** 请求结束（相当于点窗口的关闭） */
    fun stop() {
        if (process.isAlive) process.destroy()
    }

    /** 强制结束 */
    fun kill() {
        if (process.isAlive) process.destroyForcibly()
    }

    /** 阻塞等待进程结束，返回退出码 */
    fun waitFor(): Int {
        waiter.join()
        return runCatching { process.exitValue() }.getOrDefault(-1)
    }
}

object NativesExtractor {

    /** 把旧版分类库里的 dll/so 解压到 natives 目录 */
    fun extract(extraction: NativeExtraction, targetDir: Path, onLog: (String) -> Unit = {}) {
        val normalizedTarget = targetDir.toAbsolutePath().normalize()
        Files.createDirectories(normalizedTarget)
        var count = 0
        ZipInputStream(Files.newInputStream(extraction.jar)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                if (!entry.isDirectory && shouldExtract(name, extraction.excludes)) {
                    val target = normalizedTarget.resolve(name).normalize()
                    // 防目录穿越
                    if (target.startsWith(normalizedTarget)) {
                        Files.createDirectories(target.parent)
                        Files.newOutputStream(target).use { output -> zip.copyTo(output) }
                        count++
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        onLog("[启动器] 已解压 $count 个 natives 文件 -> ${extraction.jar.fileName}")
    }

    private fun shouldExtract(name: String, excludes: List<String>): Boolean {
        if (name.startsWith("META-INF/")) return false
        return excludes.none { name.startsWith(it) }
    }
}

object GameLauncher {

    /**
     * 启动游戏。onOutput 会在后台线程上被逐行回调，onExit 在进程结束时回调一次。
     */
    fun launch(
        plan: LaunchPlan,
        onOutput: (String) -> Unit,
        onExit: (Int) -> Unit,
    ): GameProcess {
        check(plan.isReady) { "版本文件不完整，无法启动：${plan.preflight.summary()}" }

        plan.nativesToExtract.forEach { extraction ->
            runCatching { NativesExtractor.extract(extraction, plan.nativesDirectory, onOutput) }
                .onFailure { onOutput("[启动器] natives 解压失败: ${it.message}") }
        }
        // 现代版本的 jvm 参数会引用 natives 目录下的子目录，先把目录建好
        runCatching { Files.createDirectories(plan.nativesDirectory) }

        plan.warnings.forEach { onOutput("[启动器] 警告: $it") }
        onOutput("[启动器] 使用 ${plan.java.label}")
        onOutput("[启动器] ${plan.compactCommandLine()}")

        val builder = ProcessBuilder(plan.command)
            .directory(plan.gameDirectory.toFile())
            .redirectErrorStream(true)

        val process = builder.start()
        onOutput("[启动器] 游戏进程已启动，PID=${process.pid()}")

        thread(isDaemon = true, name = "abi-mc-output") {
            runCatching {
                process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { line -> onOutput(line) }
                }
            }.onFailure { onOutput("[启动器] 读取输出失败: ${it.message}") }
        }

        val waiter = thread(isDaemon = true, name = "abi-mc-wait") {
            val code = runCatching { process.waitFor() }.getOrDefault(-1)
            onExit(code)
        }

        return GameProcess(process, waiter)
    }
}
