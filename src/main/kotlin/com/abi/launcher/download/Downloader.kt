package com.abi.launcher.download

import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** 部分 CDN 会拒绝没有 User-Agent 的请求 */
private const val USER_AGENT = "ABI-Launcher/1.0"

/** 下载源：官方与国内镜像，URL 规则不同，互为备用 */
enum class DownloadSource(val displayName: String, val shortName: String) {
    Official("官方 Mojang", "官方"),
    Bmclapi("BMCLAPI 镜像", "镜像"),
    ;

    fun manifestUrl(): String = when (this) {
        Official -> "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
        Bmclapi -> "https://bmclapi2.bangbang93.com/mc/game/version_manifest_v2.json"
    }

    fun versionJsonUrl(versionId: String, officialUrl: String?): String = when (this) {
        Official -> officialUrl ?: "https://piston-meta.mojang.com/version/$versionId/json"
        Bmclapi -> "https://bmclapi2.bangbang93.com/version/$versionId/json"
    }

    fun clientJarUrl(versionId: String, officialUrl: String?): String = when (this) {
        Official -> officialUrl ?: "https://piston-data.mojang.com/version/$versionId/client"
        Bmclapi -> "https://bmclapi2.bangbang93.com/version/$versionId/client"
    }

    fun libraryUrl(mavenPath: String, officialUrl: String?): String = when (this) {
        Official -> officialUrl ?: "https://libraries.minecraft.net/$mavenPath"
        Bmclapi -> "https://bmclapi2.bangbang93.com/maven/$mavenPath"
    }

    fun assetIndexUrl(versionId: String, officialUrl: String?): String = when (this) {
        Official -> officialUrl ?: "https://piston-meta.mojang.com/version/$versionId/assetIndex"
        Bmclapi -> "https://bmclapi2.bangbang93.com/version/$versionId/assetIndex"
    }

    /** Java 运行时清单（官方 all.json） */
    fun javaRuntimeManifestUrl(): String = when (this) {
        Official ->
            "https://launchermeta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json"
        Bmclapi ->
            "https://bmclapi2.bangbang93.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json"
    }

    /** 资源是内容寻址的：objects/<hash 前两位>/<hash> */
    fun assetObjectUrl(hash: String): String {
        val prefix = hash.take(2)
        return when (this) {
            Official -> "https://resources.download.minecraft.net/$prefix/$hash"
            Bmclapi -> "https://bmclapi2.bangbang93.com/assets/$prefix/$hash"
        }
    }

    companion object {
        /** 主源失败时用另一个源重试 */
        fun alternateOf(source: DownloadSource): DownloadSource =
            if (source == Official) Bmclapi else Official

        /** 把官方地址改写成 BMCLAPI 镜像地址（注意 libraries / assets 的路径前缀不一样） */
        fun mirrorOf(url: String): String = url
            .replace("https://launchermeta.mojang.com/", "https://bmclapi2.bangbang93.com/")
            .replace("https://piston-meta.mojang.com/", "https://bmclapi2.bangbang93.com/")
            .replace("https://piston-data.mojang.com/", "https://bmclapi2.bangbang93.com/")
            .replace("https://libraries.minecraft.net/", "https://bmclapi2.bangbang93.com/maven/")
            .replace("https://resources.download.minecraft.net/", "https://bmclapi2.bangbang93.com/assets/")
    }
}

/** 一个待下载文件 */
data class DownloadTarget(
    val url: String,
    val destination: Path,
    val sha1: String?,
    val size: Long,
    val label: String,
    /** 备用地址（另一个下载源），主地址失败时依次尝试 */
    val alternates: List<String> = emptyList(),
) {
    val allUrls: List<String> get() = listOf(url) + alternates
}

enum class DownloadOutcome { Downloaded, Skipped, Failed }

data class DownloadResult(
    val target: DownloadTarget,
    val outcome: DownloadOutcome,
    val bytes: Long = 0,
    val message: String? = null,
)

/** 一次 HTTP 文本请求的结果（登录流程需要拿到非 2xx 的响应体，比如 XSTS 的 XErr） */
data class HttpTextResult(val status: Int, val body: String) {
    val ok: Boolean get() = status in 200..299
}

/** 带进度与 SHA-1 校验的下载器 */
class HttpDownloader(
    private val connectTimeout: Duration = Duration.ofSeconds(15),
    private val requestTimeout: Duration = Duration.ofMinutes(10),
) {
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(connectTimeout)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    fun fetchText(url: String, alternates: List<String> = emptyList()): String =
        String(fetchBytes(url, alternates), Charsets.UTF_8)

    /** 依次尝试 url 与备用地址，全部失败才抛错 */
    fun fetchBytes(url: String, alternates: List<String> = emptyList()): ByteArray {
        var lastError: Exception? = null
        for (candidate in listOf(url) + alternates) {
            try {
                val request = HttpRequest.newBuilder(URI.create(candidate))
                    .timeout(requestTimeout)
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
                if (response.statusCode() in 200..299) return response.body()
                lastError = IllegalStateException("HTTP ${response.statusCode()} ($candidate)")
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw IllegalStateException("请求失败：$url（已尝试 ${1 + alternates.size} 个地址）", lastError)
    }

    /**
     * 下载单个文件：先写 .tmp，校验 SHA-1 通过后再原子改名。
     * 目标已存在且校验通过时直接跳过。
     */
    fun download(
        target: DownloadTarget,
        cancelled: () -> Boolean = { false },
        onBytes: (Long) -> Unit = {},
    ): DownloadResult {
        val destination = target.destination
        if (Files.isRegularFile(destination)) {
            if (target.sha1 == null || sha1Of(destination).equals(target.sha1, ignoreCase = true)) {
                return DownloadResult(target, DownloadOutcome.Skipped, Files.size(destination))
            }
        }

        Files.createDirectories(destination.parent)
        val temp = destination.resolveSibling(destination.fileName.toString() + ".tmp")

        var lastError: Exception? = null
        for (candidate in target.allUrls) {
            if (cancelled()) return DownloadResult(target, DownloadOutcome.Failed, message = "已取消")
            try {
                val request = HttpRequest.newBuilder(URI.create(candidate))
                    .timeout(requestTimeout)
                    .header("User-Agent", "ABI-Launcher/1.0")
                    .GET()
                    .build()

                val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
                if (response.statusCode() !in 200..299) {
                    lastError = IllegalStateException("HTTP ${response.statusCode()}")
                    continue
                }

                var written = 0L
                response.body().use { input: InputStream ->
                    Files.newOutputStream(temp).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (cancelled()) {
                                runCatching { Files.deleteIfExists(temp) }
                                return DownloadResult(target, DownloadOutcome.Failed, written, "已取消")
                            }
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            written += read
                            onBytes(written)
                        }
                    }
                }

                if (target.sha1 != null) {
                    val actual = sha1Of(temp)
                    if (!actual.equals(target.sha1, ignoreCase = true)) {
                        Files.deleteIfExists(temp)
                        lastError = IllegalStateException("SHA-1 校验不通过（期望 ${target.sha1.take(8)}…，实际 ${actual.take(8)}…）")
                        continue
                    }
                }

                Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING)
                return DownloadResult(target, DownloadOutcome.Downloaded, written)
            } catch (e: Exception) {
                lastError = e
                runCatching { Files.deleteIfExists(temp) }
            }
        }
        return DownloadResult(target, DownloadOutcome.Failed, message = lastError?.message ?: "未知错误")
    }

    /** GET 并拿到响应体与状态码（登录/档案这类接口用） */
    fun getText(url: String, bearer: String? = null): HttpTextResult =
        sendText(
            HttpRequest.newBuilder(URI.create(url))
                .timeout(requestTimeout)
                .header("User-Agent", USER_AGENT)
                .apply { if (bearer != null) header("Authorization", "Bearer $bearer") }
                .GET(),
        )

    /** POST JSON */
    fun postJson(url: String, json: String, bearer: String? = null): HttpTextResult =
        sendText(
            HttpRequest.newBuilder(URI.create(url))
                .timeout(requestTimeout)
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .apply { if (bearer != null) header("Authorization", "Bearer $bearer") }
                .POST(HttpRequest.BodyPublishers.ofString(json, Charsets.UTF_8)),
        )

    /** POST 表单（OAuth 的 devicecode / token 端点用） */
    fun postForm(url: String, form: Map<String, String>): HttpTextResult {
        val body = form.entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }
        return sendText(
            HttpRequest.newBuilder(URI.create(url))
                .timeout(requestTimeout)
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body, Charsets.UTF_8)),
        )
    }

    private fun sendText(builder: HttpRequest.Builder): HttpTextResult {
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        return HttpTextResult(response.statusCode(), response.body() ?: "")
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    /**
     * 并发下载一批文件。onProgress(完成数, 总数, 当前文件) 会在任意线程调用。
     */
    fun downloadAll(
        targets: List<DownloadTarget>,
        concurrency: Int = 8,
        cancelled: () -> Boolean = { false },
        onProgress: (completed: Int, total: Int, current: String) -> Unit = { _, _, _ -> },
        onResult: (DownloadResult) -> Unit = {},
    ): List<DownloadResult> {
        if (targets.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(concurrency.coerceIn(1, 32)) { runnable ->
            Thread(runnable, "abi-download").apply { isDaemon = true }
        }
        val completed = AtomicInteger(0)
        val results = java.util.Collections.synchronizedList(mutableListOf<DownloadResult>())
        try {
            val tasks = targets.map { target ->
                Callable {
                    if (cancelled()) {
                        DownloadResult(target, DownloadOutcome.Failed, message = "已取消")
                    } else {
                        val result = download(target, cancelled)
                        results.add(result)
                        onResult(result)
                        onProgress(completed.incrementAndGet(), targets.size, target.label)
                        result
                    }
                }
            }
            pool.invokeAll(tasks)
        } finally {
            pool.shutdownNow()
        }
        return results.toList()
    }

    companion object {
        fun sha1Of(path: Path): String {
            val digest = MessageDigest.getInstance("SHA-1")
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
