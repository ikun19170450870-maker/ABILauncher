package com.abi.launcher

import com.abi.launcher.launch.GameLauncher
import com.abi.launcher.launch.JavaRuntimeLocator
import com.abi.launcher.launch.LaunchCommandBuilder
import com.abi.launcher.launch.LaunchOptions
import com.abi.launcher.launch.VersionSpecReader
import java.nio.file.Path

/**
 * 命令行入口，便于在没有图形界面的环境里验证识别与启动：
 *
 * ```
 * gradlew scan   -PscanPath="C:\Users\你\AppData\Roaming\.minecraft"
 * gradlew launch -Pinstall="D:\zawu2\.minecraft" -Pversion="1.21.8" -PdryRun=true
 * ```
 */
fun main(args: Array<String>) {
    when (args.firstOrNull()?.lowercase()) {
        "scan" -> runScan(args.drop(1))
        "launch" -> runLaunch(args.drop(1))
        else -> {
            println("ABI 启动器命令行")
            println("  scan   <目录>                                在目录中识别 Minecraft")
            println("  launch <游戏目录> <版本号> [选项]             启动指定版本")
            println("         选项: --dry-run 只打印命令不启动")
            println("               --player <玩家名>   --memory <MB>")
        }
    }
}

// ---------------------------------------------------------------- scan

private fun runScan(args: List<String>) {
    val target = args.firstOrNull()?.takeIf { it.isNotBlank() }
    if (target == null) {
        println("用法: scan <目录>")
        println("例如: gradlew scan -PscanPath=\"%APPDATA%\\.minecraft\"")
        return
    }

    val result = MinecraftScanner.scan(Path.of(target))
    if (result.skipped) {
        println("目录不存在或不是目录: ${result.root}")
        return
    }

    println("扫描目录    : ${result.root}")
    println("遍历子目录  : ${result.scannedDirectories} 个${if (result.truncated) "（已达上限，结果可能不完整）" else ""}")
    println("游戏目录    : ${result.installations.size} 个")
    println("版本总数    : ${result.totalVersions} 个（正式版 ${result.releaseCount} / 快照 ${result.snapshotCount} / 模组 ${result.moddedCount}）")
    if (result.javaRequirements.isNotEmpty()) {
        println("需要的 Java : ${result.javaRequirements.joinToString(", ") { "Java $it" }}")
    }

    result.installations.forEach { installation ->
        println()
        val launcher = installation.launcher?.let { " [$it]" } ?: ""
        println("== ${installation.name}$launcher")
        println("   ${installation.root}")
        installation.versions.forEach { version ->
            val java = version.javaMajorVersion?.let { "Java $it" } ?: "Java 未知"
            val jar = if (version.clientJarPresent) "已有客户端 jar" else "缺少客户端 jar"
            val inherit = version.inheritsFrom?.let { "  继承自 $it" } ?: ""
            println("   - ${version.id.padEnd(26)} ${version.category.padEnd(7)} $java  $jar  库 ${version.libraryCount}$inherit")
        }
    }
}

// ---------------------------------------------------------------- launch

private fun runLaunch(args: List<String>) {
    var installRoot: String? = null
    var versionId: String? = null
    var dryRun = false
    var playerName = "Steve"
    var memoryMb = 2048

    var index = 0
    while (index < args.size) {
        when (val argument = args[index]) {
            "--dry-run" -> dryRun = true
            "--player" -> playerName = args.getOrNull(++index) ?: playerName
            "--memory" -> memoryMb = args.getOrNull(++index)?.toIntOrNull() ?: memoryMb
            else -> when {
                installRoot == null -> installRoot = argument
                versionId == null -> versionId = argument
            }
        }
        index++
    }

    if (installRoot.isNullOrBlank() || versionId.isNullOrBlank()) {
        println("用法: launch <游戏目录> <版本号> [--dry-run] [--player 名字] [--memory 2048]")
        return
    }

    val root = Path.of(installRoot)
    val versionJson = VersionSpecReader.findVersionJson(root, versionId)
    if (versionJson == null) {
        println("在 $root\\versions 下找不到版本「$versionId」")
        return
    }

    val spec = VersionSpecReader.resolve(versionJson, root)
    println("版本        : ${spec.id}  (${spec.type ?: "未知类型"})")
    println("版本 JSON   : ${spec.sourcePath}")
    println("mainClass   : ${spec.mainClass}")
    println("库数量      : ${spec.libraries.size}")
    if (!spec.inheritsFrom.isNullOrBlank()) println("继承自      : ${spec.inheritsFrom}${if (spec.missingParent != null) "（缺失！）" else ""}")

    val java = JavaRuntimeLocator.findFor(spec.javaMajorVersion, listOf(root.resolve("runtime")))
    if (java == null) {
        println("找不到可用的 Java（版本要求 Java ${spec.javaMajorVersion}）")
        return
    }
    println("需要的 Java : ${spec.javaMajorVersion ?: "未标注"}")
    println("选用        : ${java.label}")

    val options = LaunchOptions(
        playerName = playerName,
        maxMemoryMb = memoryMb,
    )
    val plan = LaunchCommandBuilder.plan(spec, versionJson.parent, root, java, options)

    println()
    println("预检        : ${plan.preflight.summary()}")
    println("classpath   : ${plan.classpath.size} 项")
    println("natives 解压: ${plan.nativesToExtract.size} 个")
    plan.warnings.forEach { println("警告        : $it") }

    if (dryRun) {
        println()
        println("命令行（共 ${plan.command.size} 个参数）:")
        plan.command.forEachIndexed { i, argument ->
            val shown = if (argument.length > 150) {
                argument.take(150) + " …(共 ${argument.length} 字符)"
            } else {
                argument
            }
            println("  [$i] $shown")
        }
        println()
        println("命令行（紧凑）:")
        println("  ${plan.compactCommandLine()}")
        return
    }

    if (!plan.isReady) {
        println()
        println("文件不完整，已取消启动。")
        return
    }

    println()
    println("开始启动…")
    val process = GameLauncher.launch(
        plan,
        onOutput = { line -> println(line) },
        onExit = { code -> println("[启动器] 退出码 $code") },
    )
    val exitCode = process.waitFor()
    if (exitCode != 0) {
        println("游戏以非 0 退出码结束: $exitCode")
    }
}
