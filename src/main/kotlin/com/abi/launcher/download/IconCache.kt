package com.abi.launcher.download

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.abi.launcher.LauncherPaths
import org.jetbrains.skia.Image
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

/**
 * 图标缓存：内存 → 本地磁盘 → 网络。
 *
 * 界面拿图标是「先看内存里有没有，有就直接画」，所以不会因为下载图标卡住列表；
 * 下过一次的会存到 %APPDATA%\ABI\cache\icons，下次点开秒显示。
 */
object IconCache {

    private val memory = ConcurrentHashMap<String, ImageBitmap>()

    val diskDirectory: Path get() = LauncherPaths.cacheRoot.resolve("icons")

    fun cached(url: String): ImageBitmap? = memory[url]

    /** 阻塞式加载（调用方放到 IO 线程）：失败返回 null，界面显示占位符即可 */
    fun load(url: String, downloader: HttpDownloader = HttpDownloader()): ImageBitmap? {
        memory[url]?.let { return it }

        val file = diskDirectory.resolve(fileNameOf(url))
        val bytes = when {
            file.isRegularFile() -> runCatching { file.readBytes() }.getOrNull()
            else -> runCatching { downloader.fetchBytes(url) }
                .getOrNull()
                ?.also { data ->
                    runCatching {
                        file.parent.createDirectories()
                        file.writeBytes(data)
                    }
                }
        } ?: return null

        return runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }
            .onSuccess { memory[url] = it }
            .getOrNull()
    }

    /** 用一个稳定的哈希当文件名，避免 URL 里的特殊字符 */
    fun fileNameOf(url: String): String =
        MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) } + ".png"
}
