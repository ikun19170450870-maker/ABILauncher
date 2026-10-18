package com.abi.launcher

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

/**
 * 启动器自己的目录约定。
 *
 * 启动器不再依赖别人的 .minecraft，而是自己维护一份游戏目录：
 *
 * ```
 * %APPDATA%\ABI\minecraft\        <- 默认游戏根目录（版本、库、资源都下载到这里）
 *   ├─ versions\<版本号>\<版本号>.json / .jar
 *   ├─ libraries\...
 *   └─ assets\indexes\...  assets\objects\...
 * %APPDATA%\ABI\cache\            <- 清单等缓存
 * %APPDATA%\ABI\logs\
 * ```
 */
object LauncherPaths {

    /** 打包成 exe 时 jpackage 会设置这个属性（指向 exe 路径） */
    private val appDir: Path? = runCatching {
        System.getProperty("jpackage.app-path")?.takeIf { it.isNotBlank() }?.let { Path.of(it).parent }
    }.getOrNull()

    /**
     * 便携模式：从打包好的 exe 运行时，数据就放在 exe 旁边 ——
     * 双击启动后，exe 同目录会自动出现 `.minecraft\`（游戏目录）、`instances\`、`cache\`、`崩溃报告\` 等。
     *
     * 不需要任何标记文件；如果那个目录不可写（比如解压到了 Program Files），自动退回 %APPDATA%\ABI。
     */
    val portable: Boolean = appDir?.let { dir ->
        runCatching {
            Files.createDirectories(dir)
            val probe = dir.resolve(".abi-write-test")
            Files.writeString(probe, "ok")
            Files.deleteIfExists(probe)
            true
        }.getOrDefault(false)
    } ?: false

    /** 启动器数据根目录：便携模式 = exe 旁边；否则 %APPDATA%\ABI（拿不到 APPDATA 时退回用户目录下的 .abi） */
    val dataRoot: Path by lazy {
        if (portable) return@lazy appDir!!
        val base = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
            ?: Path.of(System.getProperty("user.home"))
        base.resolve("ABI")
    }

    /** 启动器自己所在的目录：打包后 = exe 所在目录；开发/命令行运行 = 当前工作目录 */
    private val launcherDir: Path = appDir
        ?: Path.of(System.getProperty("user.dir")?.takeIf { it.isNotBlank() } ?: ".")

    private fun isWritable(dir: Path): Boolean = runCatching {
        Files.createDirectories(dir)
        val probe = dir.resolve(".abi-write-test")
        Files.writeString(probe, "ok")
        Files.deleteIfExists(probe)
        true
    }.getOrDefault(false)

    /**
     * 默认游戏根目录 = **启动器所在目录的上一层** 里的 `minecraft`：
     *
     * ```
     * 启动器在 E:\ABI\ABI\          -> 游戏目录 E:\ABI\minecraft\
     * 打包后在 D:\Games\ABILauncher\ -> 游戏目录 D:\Games\minecraft\
     * ```
     *
     * 也就是"挨着启动器的文件夹"，不在它里面 —— 换个地方放启动器，规则一样。
     * 上一层写不进去（比如放在 Program Files 下）就退回启动器目录里的 minecraft。
     */
    val defaultGameRoot: Path by lazy {
        val parent = launcherDir.parent
        if (parent != null && isWritable(parent)) parent.resolve("minecraft")
        else launcherDir.resolve("minecraft")
    }

    val cacheRoot: Path get() = dataRoot.resolve("cache")

    /** 建立标准目录结构（幂等，重复调用无副作用） */
    fun ensureGameRoot(gameRoot: Path = defaultGameRoot): Path {
        gameRoot.createDirectories()
        gameRoot.resolve("versions").createDirectories()
        gameRoot.resolve("libraries").createDirectories()
        gameRoot.resolve("assets").resolve("indexes").createDirectories()
        gameRoot.resolve("assets").resolve("objects").createDirectories()
        gameRoot.resolve("assets").resolve("log_configs").createDirectories()
        return gameRoot
    }

    /** 这个目录像不像一个游戏根目录（有 versions 文件夹） */
    fun looksLikeGameRoot(dir: Path): Boolean = dir.resolve("versions").isDirectory()

    /** 该版本是否已经装在这个游戏目录里（有 JSON 就算装了，jar 可能还缺） */
    fun isVersionInstalled(gameRoot: Path, versionId: String): Boolean {
        val dir = gameRoot.resolve("versions").resolve(versionId)
        return dir.resolve("$versionId.json").exists() || dir.resolve("$versionId.jar").exists()
    }

    /** 客户端 jar 是否存在（真正能启动的条件之一） */
    fun hasClientJar(gameRoot: Path, versionId: String): Boolean =
        Files.isRegularFile(gameRoot.resolve("versions").resolve(versionId).resolve("$versionId.jar"))
}
