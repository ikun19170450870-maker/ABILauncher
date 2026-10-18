package com.abi.launcher.download

import com.abi.launcher.LauncherPaths
import com.abi.launcher.launch.arr
import com.abi.launcher.launch.long
import com.abi.launcher.launch.obj
import com.abi.launcher.launch.readJsonObject
import com.abi.launcher.launch.scalar
import com.abi.launcher.launch.str
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** 清单里的一个版本条目 */
data class RemoteVersion(
    val id: String,
    val type: String,
    val url: String,
    val releaseTime: String,
    val sha1: String?,
) {
    val category: String
        get() = when (type) {
            "release" -> "正式版"
            "snapshot" -> "快照"
            "old_beta" -> "远古 Beta"
            "old_alpha" -> "远古 Alpha"
            else -> type
        }
}

data class VersionManifest(
    val latestRelease: String?,
    val latestSnapshot: String?,
    val versions: List<RemoteVersion>,
)

data class ManifestResult(
    val manifest: VersionManifest,
    val fromCache: Boolean,
    val message: String,
)

data class ConnectionTest(
    val ok: Boolean,
    val source: DownloadSource,
    val elapsedMs: Long,
    val versionCount: Int,
    val latestRelease: String?,
    val message: String,
)

/** 资源索引里的一个对象（内容寻址：文件名就是它的 SHA-1） */
data class AssetObject(val name: String, val hash: String, val size: Long)

/**
 * 与 Mojang（或其镜像）打交道的唯一入口。
 *
 * 涉及的地址：
 *  - 版本清单   /mc/game/version_manifest_v2.json
 *  - 版本 JSON  清单里每个 version 的 url
 *  - 客户端 jar version.downloads.client.url
 *  - 依赖库     libraries[].downloads.artifact.url（或 libraries.minecraft.net/<maven 路径>）
 *  - 资源索引   version.assetIndex.url
 *  - 资源对象   resources.download.minecraft.net/<hash 前两位>/<hash>
 */
object MojangRepository {

    private val downloader = HttpDownloader()

    fun fetchManifest(source: DownloadSource): ManifestResult {
        val cacheFile = LauncherPaths.cacheRoot.resolve("version_manifest_${source.name}.json")
        return try {
            val text = downloader.fetchText(
                source.manifestUrl(),
                alternates = listOf(DownloadSource.alternateOf(source).manifestUrl()),
            )
            runCatching {
                cacheFile.parent.createDirectories()
                cacheFile.writeText(text)
            }
            ManifestResult(parseManifest(text), fromCache = false, message = "已从${source.displayName}获取清单")
        } catch (e: Exception) {
            val cached = loadCachedManifest(source)
            if (cached != null) {
                ManifestResult(cached, fromCache = true, message = "网络失败（${e.message}），使用本地缓存清单")
            } else {
                throw IllegalStateException("无法获取版本清单：${e.message}", e)
            }
        }
    }

    fun loadCachedManifest(source: DownloadSource): VersionManifest? {
        val cacheFile = LauncherPaths.cacheRoot.resolve("version_manifest_${source.name}.json")
        if (!Files.isRegularFile(cacheFile)) return null
        return runCatching { parseManifest(Files.readString(cacheFile)) }.getOrNull()
    }

    fun testConnection(source: DownloadSource): ConnectionTest {
        val started = System.currentTimeMillis()
        return try {
            val text = downloader.fetchText(
                source.manifestUrl(),
                alternates = listOf(DownloadSource.alternateOf(source).manifestUrl()),
            )
            val manifest = parseManifest(text)
            ConnectionTest(
                ok = true,
                source = source,
                elapsedMs = System.currentTimeMillis() - started,
                versionCount = manifest.versions.size,
                latestRelease = manifest.latestRelease,
                message = "连接正常",
            )
        } catch (e: Exception) {
            ConnectionTest(
                ok = false,
                source = source,
                elapsedMs = System.currentTimeMillis() - started,
                versionCount = 0,
                latestRelease = null,
                message = e.message ?: "连接失败",
            )
        }
    }

    /** 取版本 JSON 原文（官方源用清单里的 url，镜像源有自己的地址） */
    fun fetchVersionJsonText(versionId: String, officialUrl: String?, source: DownloadSource): String =
        downloader.fetchText(
            source.versionJsonUrl(versionId, officialUrl),
            alternates = listOf(DownloadSource.alternateOf(source).versionJsonUrl(versionId, officialUrl)),
        )

    /** 把版本 JSON 落到 versions/<id>/<id>.json，返回文件路径 */
    fun saveVersionJson(gameRoot: Path, versionId: String, jsonText: String): Path {
        val dir = gameRoot.resolve("versions").resolve(versionId).createDirectories()
        val file = dir.resolve("$versionId.json")
        file.writeText(jsonText)
        return file
    }

    fun parseManifest(text: String): VersionManifest {
        val obj = com.google.gson.JsonParser.parseString(text).asJsonObject
        val versions = obj.arr("versions")?.let { array -> parseVersions(array) }.orEmpty()
        return VersionManifest(
            latestRelease = obj.obj("latest")?.scalar("release"),
            latestSnapshot = obj.obj("latest")?.scalar("snapshot"),
            versions = versions,
        )
    }

    private fun parseVersions(array: JsonArray): List<RemoteVersion> = array.mapNotNull { element ->
        val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        val id = obj.scalar("id") ?: return@mapNotNull null
        RemoteVersion(
            id = id,
            type = obj.scalar("type").orEmpty(),
            url = obj.scalar("url").orEmpty(),
            releaseTime = obj.scalar("releaseTime").orEmpty(),
            sha1 = obj.scalar("sha1"),
        )
    }

    /** 解析资源索引里的 objects 表 */
    fun parseAssetObjects(indexText: String): List<AssetObject> {
        val root = com.google.gson.JsonParser.parseString(indexText).asJsonObject
        val objects: JsonObject = root.obj("objects") ?: return emptyList()
        return objects.entrySet().mapNotNull { (name, element) ->
            val value = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val hash = value.scalar("hash") ?: return@mapNotNull null
            AssetObject(name = name, hash = hash, size = value.long("size") ?: 0L)
        }
    }

    /** 供界面显示：清单里的 id 与类型 */
    fun summarize(manifest: VersionManifest): String =
        "共 ${manifest.versions.size} 个版本，最新正式版 ${manifest.latestRelease ?: "?"}，最新快照 ${manifest.latestSnapshot ?: "?"}"
}
