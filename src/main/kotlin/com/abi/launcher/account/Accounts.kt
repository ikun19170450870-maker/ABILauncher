package com.abi.launcher.account

import com.abi.launcher.LauncherPaths
import com.abi.launcher.launch.LaunchCommandBuilder
import com.abi.launcher.launch.arr
import com.abi.launcher.launch.obj
import com.abi.launcher.launch.str
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile
import kotlin.io.path.writeText

/**
 * 一个游戏账号。
 *
 * - `offline`：离线账号，UUID 由 `OfflinePlayer:<名字>` 的 MD5 推出（和官方一致），只能单机；
 * - `msa`：微软正版账号（登录流程见项目说明），可以联机。
 */
data class GameAccount(
    val name: String,
    /** 不带短横线的 UUID */
    val uuid: String,
    val type: String = "offline",
    /** 正版账号的 Minecraft accessToken（离线账号固定为 0），只是为了让启动直接可用 */
    val accessToken: String = "0",
) {
    val isOffline: Boolean get() = type == "offline"
    val displayName: String get() = if (isOffline) "$name（离线）" else name
}

/** 账号列表存在 %APPDATA%\ABI\accounts.json */
object AccountStore {

    val file: Path get() = LauncherPaths.dataRoot.resolve("accounts.json")

    fun load(from: Path = file): List<GameAccount> {
        if (!from.isRegularFile()) return emptyList()
        val text = runCatching { Files.readString(from) }.getOrNull() ?: return emptyList()
        val root = runCatching { com.google.gson.JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return emptyList()
        val array = root.arr("accounts") ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val name = obj.str("name") ?: return@mapNotNull null
            val type = obj.str("type") ?: "offline"
            GameAccount(
                name = name,
                uuid = obj.str("uuid") ?: offlineUuid(name),
                type = type,
                accessToken = obj.str("access_token") ?: "0",
            )
        }
    }

    fun save(accounts: List<GameAccount>, to: Path = file) {
        val array = JsonArray()
        accounts.forEach { account ->
            array.add(
                JsonObject().apply {
                    addProperty("name", account.name)
                    addProperty("uuid", account.uuid)
                    addProperty("type", account.type)
                    if (!account.isOffline) addProperty("access_token", account.accessToken)
                },
            )
        }
        val root = JsonObject().apply { add("accounts", array) }
        to.parent.createDirectories()
        to.writeText(GsonBuilder().setPrettyPrinting().create().toJson(root))
    }

    /** 按名字生成离线账号（UUID 与官方离线算法一致，同名永远同 UUID） */
    fun offline(name: String): GameAccount =
        GameAccount(name = name, uuid = offlineUuid(name), type = "offline")

    fun offlineUuid(name: String): String = LaunchCommandBuilder.offlineUuid(name)

    /** 加一个账号；同名的直接返回已有的 */
    fun add(accounts: List<GameAccount>, account: GameAccount): List<GameAccount> =
        if (accounts.any { it.uuid == account.uuid || it.name == account.name }) accounts else accounts + account

    fun remove(accounts: List<GameAccount>, account: GameAccount): List<GameAccount> =
        accounts.filterNot { it.uuid == account.uuid && it.name == account.name }
}
