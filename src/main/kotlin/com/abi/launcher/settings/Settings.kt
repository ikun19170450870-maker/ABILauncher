package com.abi.launcher.settings

import com.abi.launcher.LauncherPaths
import com.abi.launcher.launch.obj
import com.abi.launcher.launch.scalar
import com.abi.launcher.launch.str
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile
import kotlin.io.path.writeText

/** 全局设置（存 %APPDATA%\ABI\settings.json） */
data class GlobalSettings(
    /**
     * 版本隔离：每个版本用自己的目录当游戏目录。
     * 开了以后各版本的存档 / mods / config 互不干扰（PCL、HMCL 都有这个开关）。
     */
    val versionIsolation: Boolean = false,
    /** 缺 Java 时自动下载官方运行时 */
    val autoDownloadJava: Boolean = true,
    /** 游戏语言：zh_cn / en_us / auto（跟随游戏文件） */
    val gameLanguage: String = "zh_cn",
    /** 默认最大内存（MB） */
    val memoryMb: String = "2048",
    /** 界面整体缩放：auto（按窗口自动缩）或 0.6–1.5 的数字字符串 */
    val uiScale: String = "auto",
    /** 锁定窗口大小：关掉可调整大小 → 最大化按钮变灰，无法全屏 */
    val windowLocked: Boolean = true,
    /** 黑夜模式 */
    val themeDark: Boolean = false,
    /** 自定义背景（PNG/JPG/BMP/GIF 动图；空 = 不用背景） */
    val backgroundPath: String = "",
)

/** 单个版本的设置（存 <游戏目录>\abi-versions.json） */
data class VersionSettings(
    /** 显示用的名字（别名）；留空就用版本 id。安装时起的名字就存这里 */
    val displayName: String = "",
    /** auto = 跟随全局；isolated = 强制隔离；shared = 强制不隔离 */
    val isolation: String = IsolationAuto,
    /** 这个版本单独的内存（MB）；留空用全局 */
    val memoryMb: String = "",
    /** 指定 java.exe / java 路径；留空自动找 */
    val javaPath: String = "",
) {
    val isEmpty: Boolean
        get() = displayName.isBlank() && isolation == IsolationAuto &&
            memoryMb.isBlank() && javaPath.isBlank()

    companion object {
        const val IsolationAuto = "auto"
        const val IsolationIsolated = "isolated"
        const val IsolationShared = "shared"
    }
}

/** 隔离到底开不开：单版本设置优先，其次全局开关 */
fun resolveIsolation(globallyIsolated: Boolean, perVersion: VersionSettings?): Boolean =
    when (perVersion?.isolation) {
        VersionSettings.IsolationIsolated -> true
        VersionSettings.IsolationShared -> false
        else -> globallyIsolated
    }

object SettingsStore {

    val file: Path get() = LauncherPaths.dataRoot.resolve("settings.json")

    fun load(from: Path = file): GlobalSettings {
        if (!from.isRegularFile()) return GlobalSettings()
        val root = runCatching { JsonParser.parseString(Files.readString(from)).asJsonObject }.getOrNull()
            ?: return GlobalSettings()
        return GlobalSettings(
            versionIsolation = root.get("version_isolation")?.takeIf { it.isJsonPrimitive }?.asBoolean
                ?: root.scalar("version_isolation")?.toBooleanStrictOrNull() ?: false,
            autoDownloadJava = root.get("auto_download_java")?.takeIf { it.isJsonPrimitive }?.asBoolean
                ?: root.scalar("auto_download_java")?.toBooleanStrictOrNull() ?: true,
            gameLanguage = root.str("game_language") ?: "zh_cn",
            memoryMb = root.str("memory_mb") ?: "2048",
            uiScale = root.str("ui_scale") ?: "auto",
            windowLocked = root.get("window_locked")?.takeIf { it.isJsonPrimitive }?.asBoolean
                ?: root.scalar("window_locked")?.toBooleanStrictOrNull() ?: true,
            themeDark = root.get("theme_dark")?.takeIf { it.isJsonPrimitive }?.asBoolean
                ?: root.scalar("theme_dark")?.toBooleanStrictOrNull() ?: false,
            backgroundPath = root.str("background_path").orEmpty(),
        )
    }

    fun save(settings: GlobalSettings, to: Path = file) {
        val root = JsonObject().apply {
            addProperty("version_isolation", settings.versionIsolation)
            addProperty("auto_download_java", settings.autoDownloadJava)
            addProperty("game_language", settings.gameLanguage)
            addProperty("memory_mb", settings.memoryMb)
            addProperty("ui_scale", settings.uiScale)
            addProperty("window_locked", settings.windowLocked)
            addProperty("theme_dark", settings.themeDark)
            addProperty("background_path", settings.backgroundPath)
        }
        to.parent.createDirectories()
        to.writeText(GsonBuilder().setPrettyPrinting().create().toJson(root))
    }

    // ---------------------------------------------------------------- 单版本设置

    /** 每个游戏目录一份，跟着游戏目录走 */
    fun versionsFile(gameRoot: Path): Path = gameRoot.resolve("abi-versions.json")

    fun loadVersions(gameRoot: Path): Map<String, VersionSettings> {
        val source = versionsFile(gameRoot)
        if (!source.isRegularFile()) return emptyMap()
        val root = runCatching { JsonParser.parseString(Files.readString(source)).asJsonObject }.getOrNull()
            ?: return emptyMap()
        val entries = root.obj("versions") ?: return emptyMap()
        return entries.entrySet().mapNotNull { (id, element) ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            id to VersionSettings(
                displayName = obj.str("display_name").orEmpty(),
                isolation = obj.str("isolation") ?: VersionSettings.IsolationAuto,
                memoryMb = obj.str("memory_mb").orEmpty(),
                javaPath = obj.str("java_path").orEmpty(),
            )
        }.toMap()
    }

    fun saveVersions(gameRoot: Path, settings: Map<String, VersionSettings>) {
        val versions = JsonObject()
        settings.forEach { (id, value) ->
            if (value.isEmpty) return@forEach
            versions.add(
                id,
                JsonObject().apply {
                    addProperty("display_name", value.displayName)
                    addProperty("isolation", value.isolation)
                    addProperty("memory_mb", value.memoryMb)
                    addProperty("java_path", value.javaPath)
                },
            )
        }
        val root = JsonObject().apply { add("versions", versions) }
        val target = versionsFile(gameRoot)
        runCatching {
            target.parent.createDirectories()
            target.writeText(GsonBuilder().setPrettyPrinting().create().toJson(root))
        }
    }
}
