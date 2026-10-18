package com.abi.launcher.download

import com.abi.launcher.launch.arr
import com.abi.launcher.launch.obj
import com.abi.launcher.launch.scalar
import com.abi.launcher.launch.str
import com.google.gson.JsonParser

/** 可选的模组加载器 */
enum class ModLoader(val displayName: String) {
    Vanilla("原版"),
    Fabric("Fabric"),
    Quilt("Quilt"),
    Forge("Forge"),
    NeoForge("NeoForge"),
    ;

    /** 需要运行官方安装器（而不是只拼一个版本 JSON） */
    val usesInstaller: Boolean get() = this == Forge || this == NeoForge
}

data class LoaderOption(
    val version: String,
    val stable: Boolean,
    val note: String? = null,
) {
    val displayName: String get() = buildString {
        append(version)
        if (stable) append("（推荐）")
        note?.let { append(" · $it") }
    }
}

/**
 * 模组加载器的元数据。
 *
 * - Fabric / Quilt：有「元数据 API」，能直接拿到一份**版本 JSON**（带 inheritsFrom），
 *   所以安装 = 保存这份 JSON + 下载它声明的库，不需要跑安装器。
 * - Forge / NeoForge：只能下官方安装器 jar 然后运行它，由安装器生成版本 JSON 并铺库。
 */
object ModLoaderRepository {

    // ---------- Fabric ----------

    fun fabricLoaderListUrl(gameVersion: String, source: DownloadSource): String = when (source) {
        DownloadSource.Official -> "https://meta.fabricmc.net/v2/versions/loader/$gameVersion"
        DownloadSource.Bmclapi -> "https://bmclapi2.bangbang93.com/fabric-meta/v2/versions/loader/$gameVersion"
    }

    fun fabricProfileUrl(gameVersion: String, loaderVersion: String, source: DownloadSource): String = when (source) {
        DownloadSource.Official ->
            "https://meta.fabricmc.net/v2/versions/loader/$gameVersion/$loaderVersion/profile/json"
        DownloadSource.Bmclapi ->
            "https://bmclapi2.bangbang93.com/fabric-meta/v2/versions/loader/$gameVersion/$loaderVersion/profile/json"
    }

    // ---------- Quilt ----------

    fun quiltLoaderListUrl(gameVersion: String, source: DownloadSource): String = when (source) {
        DownloadSource.Official -> "https://meta.quiltmc.org/v3/versions/loader/$gameVersion"
        DownloadSource.Bmclapi -> "https://bmclapi2.bangbang93.com/quilt-meta/v3/versions/loader/$gameVersion"
    }

    fun quiltProfileUrl(gameVersion: String, loaderVersion: String, source: DownloadSource): String = when (source) {
        DownloadSource.Official ->
            "https://meta.quiltmc.org/v3/versions/loader/$gameVersion/$loaderVersion/profile/json"
        DownloadSource.Bmclapi ->
            "https://bmclapi2.bangbang93.com/quilt-meta/v3/versions/loader/$gameVersion/$loaderVersion/profile/json"
    }

    // ---------- Forge ----------

    const val FORGE_PROMOS_URL = "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json"

    fun forgeInstallerUrl(gameVersion: String, loaderVersion: String): String =
        "https://maven.minecraftforge.net/net/minecraftforge/forge/$gameVersion-$loaderVersion/" +
            "forge-$gameVersion-$loaderVersion-installer.jar"

    // ---------- NeoForge ----------

    fun neoforgeListUrl(gameVersion: String, source: DownloadSource): String = when (source) {
        DownloadSource.Official -> "https://bmclapi2.bangbang93.com/neoforge/list/$gameVersion"
        DownloadSource.Bmclapi -> "https://bmclapi2.bangbang93.com/neoforge/list/$gameVersion"
    }

    fun neoforgeInstallerUrl(loaderVersion: String): String =
        "https://maven.neoforged.net/releases/net/neoforged/neoforge/$loaderVersion/" +
            "neoforge-$loaderVersion-installer.jar"

    // ---------- 通用：列出可选加载器版本 ----------

    fun listLoaderVersions(
        loader: ModLoader,
        gameVersion: String,
        source: DownloadSource,
        downloader: HttpDownloader = HttpDownloader(),
    ): List<LoaderOption> = when (loader) {
        ModLoader.Vanilla -> emptyList()
        ModLoader.Fabric -> parseFabricLike(
            downloader.fetchText(
                fabricLoaderListUrl(gameVersion, source),
                alternates = listOf(fabricLoaderListUrl(gameVersion, DownloadSource.alternateOf(source))),
            ),
        )
        ModLoader.Quilt -> parseFabricLike(
            downloader.fetchText(
                quiltLoaderListUrl(gameVersion, source),
                alternates = listOf(quiltLoaderListUrl(gameVersion, DownloadSource.alternateOf(source))),
            ),
        )
        ModLoader.Forge -> parseForgePromos(downloader.fetchText(FORGE_PROMOS_URL), gameVersion)
        ModLoader.NeoForge -> parseNeoForgeList(
            downloader.fetchText(
                neoforgeListUrl(gameVersion, source),
                alternates = listOf(neoforgeListUrl(gameVersion, DownloadSource.alternateOf(source))),
            ),
            gameVersion,
        )
    }

    /** Fabric / Quilt 的列表结构一致：[{ "loader": { "version": "...", "stable": true } }, ...] */
    fun parseFabricLike(json: String): List<LoaderOption> {
        val array = runCatching { JsonParser.parseString(json).asJsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val loaderNode = obj.obj("loader")
            val version = loaderNode?.str("version") ?: obj.str("version") ?: return@mapNotNull null
            val stable = loaderNode?.get("stable")?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.asBoolean ?: true
            LoaderOption(version, stable)
        }
    }

    /** Forge 用 promotions：{"promos": {"1.20.1-recommended": "47.2.0", "1.20.1-latest": "47.3.0"}} */
    fun parseForgePromos(json: String, gameVersion: String): List<LoaderOption> {
        val promos = runCatching { JsonParser.parseString(json).asJsonObject.obj("promos") }.getOrNull() ?: return emptyList()
        val recommended = promos.scalar("$gameVersion-recommended")
        val latest = promos.scalar("$gameVersion-latest")
        return buildList {
            recommended?.let { add(LoaderOption(it, stable = true, note = "推荐")) }
            latest?.takeIf { it != recommended }?.let { add(LoaderOption(it, stable = false, note = "最新")) }
        }
    }

    /** NeoForge 列表：[{"version": "20.4.237", "mcversion": "1.20.4"}, ...] */
    fun parseNeoForgeList(json: String, gameVersion: String): List<LoaderOption> {
        val array = runCatching { JsonParser.parseString(json).asJsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val version = obj.scalar("version") ?: obj.scalar("rawVersion") ?: return@mapNotNull null
            val mcVersion = obj.scalar("mcversion") ?: gameVersion
            if (mcVersion != gameVersion) return@mapNotNull null
            LoaderOption(version, stable = true)
        }
    }

    /** 从 Fabric/Quilt 的版本 JSON 里取出版本号（形如 fabric-loader-0.19.3-1.21.8） */
    fun profileVersionId(profileJson: String, fallback: String): String =
        runCatching { JsonParser.parseString(profileJson).asJsonObject.scalar("id") }.getOrNull() ?: fallback
}
