package com.abi.launcher.download

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.io.path.createDirectories
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 下载器测试：起一个本地 HTTP 服务器当「Mojang」，验证下载、SHA-1 校验、
 * .tmp 原子落盘、跳过已存在、备用地址回退、并发下载。
 */
class DownloaderTest {

    private lateinit var tmp: Path
    private lateinit var server: HttpServer
    private var port = 0
    private val files = ConcurrentHashMap<String, ByteArray>()

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("abi-download")
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = files[exchange.requestURI.path]
            if (body == null) {
                exchange.sendResponseHeaders(404, -1)
            } else {
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
        }
        server.executor = Executors.newFixedThreadPool(4)
        server.start()
        port = server.address.port
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
        tmp.toFile().deleteRecursively()
    }

    private fun downloader() = HttpDownloader(Duration.ofSeconds(5), Duration.ofSeconds(20))

    private fun url(path: String) = "http://127.0.0.1:$port$path"

    private fun sha1(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `下载成功并校验 SHA-1 落盘`() {
        val payload = "hello minecraft".toByteArray()
        files["/a.txt"] = payload
        val target = tmp.resolve("a.txt")

        val result = downloader().download(
            DownloadTarget(url("/a.txt"), target, sha1(payload), payload.size.toLong(), "a.txt"),
        )

        assertEquals(DownloadOutcome.Downloaded, result.outcome, result.message)
        assertEquals("hello minecraft", Files.readString(target))
        assertFalse(Files.exists(tmp.resolve("a.txt.tmp")), "临时文件应已改名")
    }

    @Test
    fun `SHA-1 不匹配时判失败且不落盘`() {
        files["/bad.txt"] = "tampered".toByteArray()
        val target = tmp.resolve("bad.txt")

        val result = downloader().download(
            DownloadTarget(url("/bad.txt"), target, "0".repeat(40), 8, "bad.txt"),
        )

        assertEquals(DownloadOutcome.Failed, result.outcome)
        assertFalse(Files.exists(target), "校验失败的文件不应留下")
        assertTrue(result.message!!.contains("SHA-1"), result.message!!)
    }

    @Test
    fun `已存在且校验通过的文件会跳过`() {
        val payload = "cached".toByteArray()
        files["/c.txt"] = payload
        val target = tmp.resolve("c.txt")
        Files.write(target, payload)

        val result = downloader().download(
            DownloadTarget(url("/c.txt"), target, sha1(payload), payload.size.toLong(), "c.txt"),
        )

        assertEquals(DownloadOutcome.Skipped, result.outcome)
    }

    @Test
    fun `主地址 404 时回退备用地址`() {
        val payload = "fallback".toByteArray()
        files["/ok.txt"] = payload
        val target = tmp.resolve("ok.txt")

        val result = downloader().download(
            DownloadTarget(
                url = url("/missing.txt"),
                destination = target,
                sha1 = sha1(payload),
                size = payload.size.toLong(),
                label = "ok.txt",
                alternates = listOf(url("/ok.txt")),
            ),
        )

        assertEquals(DownloadOutcome.Downloaded, result.outcome, result.message)
        assertEquals("fallback", Files.readString(target))
    }

    @Test
    fun `并发下载一批文件`() {
        val targets = (1..12).map { index ->
            val payload = "file-$index".toByteArray()
            files["/f$index"] = payload
            DownloadTarget(url("/f$index"), tmp.resolve("f$index"), sha1(payload), payload.size.toLong(), "f$index")
        }

        val results = downloader().downloadAll(targets, concurrency = 4)

        assertEquals(12, results.size)
        assertTrue(results.all { it.outcome == DownloadOutcome.Downloaded }, results.joinToString { "${it.target.label}:${it.outcome}" })
        (1..12).forEach { assertEquals("file-$it", Files.readString(tmp.resolve("f$it"))) }
    }

    @Test
    fun `取消后不再继续下载`() {
        files["/big.txt"] = ByteArray(1024) { 1 }
        val target = tmp.resolve("big.txt")

        val result = downloader().download(
            DownloadTarget(url("/big.txt"), target, null, 1024, "big.txt"),
            cancelled = { true },
        )

        assertEquals(DownloadOutcome.Failed, result.outcome)
        assertEquals("已取消", result.message)
    }
}
