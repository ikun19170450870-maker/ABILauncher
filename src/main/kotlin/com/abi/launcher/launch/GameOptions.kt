package com.abi.launcher.launch

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile
import kotlin.io.path.readLines
import kotlin.io.path.writeText

/**
 * 游戏目录里的 options.txt。
 *
 * Minecraft 的界面语言就存在这个文件的 `lang:` 行里，而**新目录里没有这个文件**，
 * 游戏首次启动会生成一份默认英文的 —— 这就是「刚下载的版本启动后是英文」的原因。
 * 国内启动器（PCL2/HMCL）都会顺手写一下，所以这里也补上。
 */
object GameOptions {

    const val ChineseSimplified = "zh_cn"
    const val English = "en_us"

    /** 只在新目录里预置一次，之后跟随游戏内设置 */
    const val Auto = "auto"

    fun optionsFile(gameRoot: Path): Path = gameRoot.resolve("options.txt")

    /** 读出当前语言；没有文件或没有该行时返回 null */
    fun readLanguage(gameRoot: Path): String? {
        val file = optionsFile(gameRoot)
        if (!file.isRegularFile()) return null
        return runCatching {
            file.readLines().firstOrNull { it.startsWith("lang:", ignoreCase = true) }
                ?.substringAfter(':')
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /**
     * 启动前应用语言设置，返回 true 表示确实动了文件。
     *
     * - [Auto]：只在 options.txt 不存在时写入 [firstRunLanguage]，之后不再干预（尊重游戏内的修改）；
     * - 具体语言（zh_cn / en_us …）：每次启动都把 `lang:` 行改成它，其它行原样保留。
     */
    fun applyLanguage(
        gameRoot: Path,
        language: String,
        firstRunLanguage: String = ChineseSimplified,
    ): Boolean = when {
        language == Auto -> {
            if (optionsFile(gameRoot).isRegularFile()) false else setLanguage(gameRoot, firstRunLanguage)
        }
        else -> setLanguage(gameRoot, language)
    }

    /** 写入/替换 `lang:` 行，其它设置全部保留 */
    fun setLanguage(gameRoot: Path, language: String): Boolean {
        val file = optionsFile(gameRoot)
        val lines = when {
            file.isRegularFile() -> runCatching { file.readLines().toMutableList() }.getOrElse { mutableListOf() }
            else -> mutableListOf()
        }

        val index = lines.indexOfFirst { it.startsWith("lang:", ignoreCase = true) }
        if (index >= 0) {
            if (lines[index].equals("lang:$language", ignoreCase = true)) {
                return false // 本来就是它，不用改
            }
            lines[index] = "lang:$language"
        } else {
            lines.add(0, "lang:$language")
        }

        file.parent.createDirectories()
        file.writeText(lines.joinToString(System.lineSeparator()))
        return true
    }
}
