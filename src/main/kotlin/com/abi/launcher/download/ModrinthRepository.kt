package com.abi.launcher.download

import com.abi.launcher.launch.arr
import com.abi.launcher.launch.int
import com.abi.launcher.launch.long
import com.abi.launcher.launch.obj
import com.abi.launcher.launch.scalar
import com.abi.launcher.launch.str
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import java.net.URLEncoder
import java.nio.file.Path

/**
 * Modrinth 的内容类型。
 *
 * @param targetDirectory 下载到游戏目录下的哪个子目录；整合包没有单一目录（要整套安装），所以是 null
 * @param usesLoader 是否按模组加载器筛选（光影/资源包跟加载器无关）
 */
enum class ModrinthProjectType(
    val label: String,
    val facet: String,
    val targetDirectory: String?,
    val usesLoader: Boolean,
) {
    Mod("模组", "mod", "mods", true),
    Shader("光影", "shader", "shaderpacks", false),
    ResourcePack("资源包", "resourcepack", "resourcepacks", false),
    ModPack("整合包", "modpack", null, true),
}

data class ModrinthSearchResult(
    val projects: List<ModrinthProject>,
    /** 符合条件的结果总数（不只当前这一页） */
    val totalHits: Int,
)

data class ModrinthProject(
    val id: String,
    val slug: String,
    val title: String,
    val description: String,
    val downloads: Long,
    val categories: List<String>,
    val gameVersions: List<String>,
    val iconUrl: String? = null,
) {
    val summary: String
        get() = buildString {
            append(downloads / 1000).append("k 下载")
            if (categories.isNotEmpty()) append("　·　").append(categories.take(3).joinToString("/"))
            if (gameVersions.isNotEmpty()) append("　·　").append(gameVersions.takeLast(3).joinToString(" "))
        }
}

data class ModrinthFile(
    val filename: String,
    val url: String,
    val sha1: String?,
    val size: Long,
    val primary: Boolean,
)

data class ModrinthVersion(
    val id: String,
    val name: String,
    val versionNumber: String,
    /** release / beta / alpha */
    val versionType: String,
    val loaders: List<String>,
    val gameVersions: List<String>,
    val datePublished: String,
    val downloads: Long,
    val files: List<ModrinthFile>,
) {
    val displayName: String
        get() = buildString {
            append(versionNumber.ifBlank { name })
            append("（").append(versionType).append("）")
            if (loaders.isNotEmpty()) append("　").append(loaders.joinToString("/"))
            if (gameVersions.isNotEmpty()) append("　").append(gameVersions.joinToString(" "))
        }

    /** 主文件（没有 primary 标记时取第一个） */
    val primaryFile: ModrinthFile? get() = files.firstOrNull { it.primary } ?: files.firstOrNull()
}

/**
 * Modrinth 公开 API（v2）。
 *
 * 只用到三个端点：
 *  - 搜索：   /v2/search?query=&facets=[["project_type:mod"],["categories:fabric"],["versions:1.21.8"]]
 *  - 版本列表：/v2/project/<id>/version?loaders=["fabric"]&game_versions=["1.21.8"]
 *  - 文件下载：版本里的 files[].url（cdn.modrinth.com），自带 sha1/sha512
 *
 * 注意：Modrinth 要求带一个能识别调用方的 User-Agent（我们的下载器统一发 ABI-Launcher/1.0）。
 */
object ModrinthRepository {

    const val API_ROOT = "https://api.modrinth.com/v2"

    /** 加载器在 Modrinth 里是分类标签 */
    fun loaderTag(loader: ModLoader?): String? = when (loader) {
        ModLoader.Fabric -> "fabric"
        ModLoader.Quilt -> "quilt"
        ModLoader.Forge -> "forge"
        ModLoader.NeoForge -> "neoforge"
        else -> null
    }

    /** 从版本号里猜加载器（fabric-loader-… / 1.20.1-forge-… 这种） */
    fun guessLoader(versionId: String): ModLoader? {
        val lower = versionId.lowercase()
        return when {
            lower.contains("fabric") -> ModLoader.Fabric
            lower.contains("quilt") -> ModLoader.Quilt
            lower.contains("neoforge") -> ModLoader.NeoForge
            lower.contains("forge") -> ModLoader.Forge
            else -> null
        }
    }

    fun modsDirectory(gameRoot: Path): Path = gameRoot.resolve("mods")

    /** 这类内容下载到哪个目录；整合包返回 null（它要整套安装成实例） */
    fun directoryFor(gameRoot: Path, type: ModrinthProjectType): Path? =
        type.targetDirectory?.let { gameRoot.resolve(it) }

    // ---------------------------------------------------------------- URL 构造

    fun searchUrl(
        query: String,
        loader: ModLoader?,
        gameVersion: String?,
        limit: Int,
        offset: Int,
        type: ModrinthProjectType = ModrinthProjectType.Mod,
    ): String {
        val facets = JsonArray()
        facets.add(JsonArray().apply { add("project_type:${type.facet}") })
        if (type.usesLoader) {
            loaderTag(loader)?.let { tag -> facets.add(JsonArray().apply { add("categories:$tag") }) }
        }
        gameVersion?.takeIf { it.isNotBlank() }?.let { version ->
            facets.add(JsonArray().apply { add("versions:$version") })
        }
        return buildString {
            append(API_ROOT).append("/search")
            append("?query=").append(encode(query))
            append("&facets=").append(encode(facets.toString()))
            append("&limit=").append(limit.coerceIn(1, 100))
            append("&offset=").append(offset.coerceAtLeast(0))
            append("&index=relevance")
        }
    }

    fun versionsUrl(
        projectIdOrSlug: String,
        loader: ModLoader?,
        gameVersion: String?,
        type: ModrinthProjectType = ModrinthProjectType.Mod,
    ): String {
        val loaders = JsonArray()
        if (type.usesLoader) {
            loaderTag(loader)?.let { loaders.add(it) }
        }
        val versions = JsonArray()
        gameVersion?.takeIf { it.isNotBlank() }?.let { versions.add(it) }
        return buildString {
            append(API_ROOT).append("/project/").append(encode(projectIdOrSlug)).append("/version")
            if (loaders.size() > 0) append("?loaders=").append(encode(loaders.toString()))
            if (versions.size() > 0) append(if (loaders.size() > 0) "&" else "?").append("game_versions=").append(encode(versions.toString()))
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    // ---------------------------------------------------------------- 网络

    fun search(
        query: String,
        loader: ModLoader?,
        gameVersion: String?,
        limit: Int = 20,
        offset: Int = 0,
        type: ModrinthProjectType = ModrinthProjectType.Mod,
        downloader: HttpDownloader = HttpDownloader(),
    ): ModrinthSearchResult {
        val json = downloader.fetchText(searchUrl(query, loader, gameVersion, limit, offset, type))
        return parseSearchResult(json)
    }

    fun versions(
        projectIdOrSlug: String,
        loader: ModLoader?,
        gameVersion: String?,
        type: ModrinthProjectType = ModrinthProjectType.Mod,
        downloader: HttpDownloader = HttpDownloader(),
    ): List<ModrinthVersion> {
        val json = downloader.fetchText(versionsUrl(projectIdOrSlug, loader, gameVersion, type))
        return parseVersions(json)
    }

    /** 把 Modrinth 的文件变成一个下载任务，落到对应类型的目录（模组→mods，光影→shaderpacks…） */
    fun downloadTarget(file: ModrinthFile, gameRoot: Path, type: ModrinthProjectType = ModrinthProjectType.Mod): DownloadTarget {
        val directory = directoryFor(gameRoot, type) ?: modsDirectory(gameRoot)
        return DownloadTarget(
            url = file.url,
            destination = directory.resolve(file.filename),
            sha1 = file.sha1,
            size = file.size,
            label = "${type.label} ${file.filename}",
        )
    }

    // ---------------------------------------------------------------- 解析

    fun parseSearchResult(json: String): ModrinthSearchResult =
        ModrinthSearchResult(projects = parseSearch(json), totalHits = parseTotalHits(json))

    /** 搜索结果里的 total_hits，界面用它算总页数 */
    fun parseTotalHits(json: String): Int =
        runCatching { JsonParser.parseString(json).asJsonObject.int("total_hits") ?: 0 }.getOrDefault(0)

    fun parseSearch(json: String): List<ModrinthProject> {
        val root = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull() ?: return emptyList()
        val hits = root.arr("hits") ?: return emptyList()
        return hits.mapNotNull { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val id = obj.scalar("project_id") ?: return@mapNotNull null
            ModrinthProject(
                id = id,
                slug = obj.scalar("slug").orEmpty(),
                title = obj.scalar("title").orEmpty(),
                description = obj.scalar("description").orEmpty(),
                downloads = obj.long("downloads") ?: 0L,
                categories = obj.arr("display_categories")?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }
                    ?: obj.arr("categories")?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }.orEmpty(),
                gameVersions = obj.arr("versions")?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }.orEmpty(),
                iconUrl = obj.scalar("icon_url")?.takeIf { it.isNotBlank() },
            )
        }
    }

    fun parseVersions(json: String): List<ModrinthVersion> {
        val array = runCatching { JsonParser.parseString(json).asJsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val id = obj.scalar("id") ?: return@mapNotNull null
            ModrinthVersion(
                id = id,
                name = obj.scalar("name").orEmpty(),
                versionNumber = obj.scalar("version_number").orEmpty(),
                versionType = obj.scalar("version_type").orEmpty(),
                loaders = obj.arr("loaders")?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }.orEmpty(),
                gameVersions = obj.arr("game_versions")?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }.orEmpty(),
                datePublished = obj.scalar("date_published").orEmpty(),
                downloads = obj.long("downloads") ?: 0L,
                files = obj.arr("files")?.mapNotNull { file ->
                    val fileObj = file.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                    val url = fileObj.scalar("url") ?: return@mapNotNull null
                    ModrinthFile(
                        filename = fileObj.scalar("filename").orEmpty(),
                        url = url,
                        sha1 = fileObj.obj("hashes")?.scalar("sha1"),
                        size = fileObj.long("size") ?: 0L,
                        primary = fileObj.get("primary")?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.asBoolean ?: false,
                    )
                }.orEmpty(),
            )
        }
    }
}
