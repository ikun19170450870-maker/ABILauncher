package com.abi.launcher.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readBytes

/**
 * 自定义背景。
 *
 * 支持 PNG / JPG / BMP（静态）和 GIF（多帧动图，按固定 100ms 播放）。
 * **MP4 不支持** —— Compose Desktop 没有内置视频解码器，要放 MP4 得额外加
 * JavaFX Media 或 JCodec 依赖，这里没加。
 */
object Backdrop {

    data class Frames(val images: List<ImageBitmap>, val delayMillis: Int, val name: String) {
        val animated: Boolean get() = images.size > 1
    }

    fun supportedExtensions(): List<String> = listOf("png", "jpg", "jpeg", "bmp", "gif")

    fun load(path: Path): Frames? {
        if (!path.isRegularFile()) return null
        return runCatching {
            val extension = path.name.substringAfterLast('.', "").lowercase()
            if (extension == "gif") loadGif(path) else loadStill(path)
        }.getOrNull()
    }

    private fun loadStill(path: Path): Frames {
        val bitmap = Image.makeFromEncoded(path.readBytes()).toComposeImageBitmap()
        return Frames(listOf(bitmap), 0, path.name)
    }

    private fun loadGif(path: Path): Frames? {
        ImageIO.createImageInputStream(path.toFile()).use { input ->
            val readers = ImageIO.getImageReaders(input)
            if (!readers.hasNext()) return null
            val reader = readers.next()
            try {
                reader.input = input
                val count = runCatching { reader.getNumImages(true) }.getOrDefault(1)
                val frames = (0 until count).mapNotNull { index ->
                    runCatching { reader.read(index).toImageBitmap() }.getOrNull()
                }
                if (frames.isEmpty()) return null
                // 帧延时不去解析 GIF 元数据了（那玩意儿很啰嗦），统一 100ms
                return Frames(frames, 100, path.name)
            } finally {
                runCatching { reader.dispose() }
            }
        }
    }

    private fun BufferedImage.toImageBitmap(): ImageBitmap {
        val buffer = ByteArrayOutputStream()
        ImageIO.write(this, "png", buffer)
        return Image.makeFromEncoded(buffer.toByteArray()).toComposeImageBitmap()
    }
}

/** 版本目录里的几类文件 */
enum class FileCategory(val label: String, val directory: String, val fileHint: String) {
    Mods("模组", "mods", ".jar"),
    Shaders("光影", "shaderpacks", ".zip"),
    Saves("存档", "saves", "文件夹"),
    ResourcePacks("材质包", "resourcepacks", ".zip"),
}

data class VersionFile(
    val path: Path,
    val name: String,
    val sizeBytes: Long,
    val modifiedMillis: Long,
) {
    val isDirectory: Boolean get() = path.isDirectory()
    val readableSize: String
        get() = when {
            isDirectory -> "文件夹"
            sizeBytes >= 1024 * 1024 -> "${sizeBytes / 1024 / 1024} MB"
            sizeBytes >= 1024 -> "${sizeBytes / 1024} KB"
            else -> "$sizeBytes B"
        }
}

/**
 * 版本目录里的文件增删（模组 / 光影 / 存档 / 材质包）。
 * 拖入和「添加文件」都走 [import]，重复文件会自动加序号，不会覆盖。
 */
object VersionFiles {

    fun directory(gameDir: Path, category: FileCategory): Path = gameDir.resolve(category.directory)

    fun list(gameDir: Path, category: FileCategory): List<VersionFile> {
        val dir = directory(gameDir, category)
        if (!dir.isDirectory()) return emptyList()
        return runCatching {
            Files.newDirectoryStream(dir).use { stream ->
                stream.map { path ->
                    VersionFile(
                        path = path,
                        name = path.name,
                        sizeBytes = runCatching { path.fileSize() }.getOrDefault(0L),
                        modifiedMillis = runCatching { path.getLastModifiedTime().toMillis() }.getOrDefault(0L),
                    )
                }.sortedByDescending { it.modifiedMillis }
            }
        }.getOrDefault(emptyList())
    }

    /** 装了 Iris / OptiFine / Oculus 才显示光影列表 */
    fun hasShaderLoader(gameDir: Path): Boolean = list(gameDir, FileCategory.Mods).any { file ->
        val lower = file.name.lowercase()
        lower.startsWith("iris") || lower.contains("iris-") ||
            lower.contains("optifine") || lower.contains("oculus")
    }

    /** 拖入 / 添加文件：复制进对应目录，返回实际加进去的名字 */
    fun import(gameDir: Path, category: FileCategory, sources: List<Path>): List<String> {
        val target = directory(gameDir, category)
        val added = mutableListOf<String>()
        for (source in sources) {
            if (!source.exists()) continue
            target.createDirectories()
            val baseName = source.name
            var destination = target.resolve(baseName)
            var index = 2
            while (destination.exists()) {
                val dot = baseName.lastIndexOf('.')
                val newName = if (dot > 0) {
                    baseName.substring(0, dot) + " ($index)" + baseName.substring(dot)
                } else {
                    "$baseName ($index)"
                }
                destination = target.resolve(newName)
                index++
            }
            runCatching {
                if (source.isDirectory()) {
                    copyDirectory(source, destination)
                } else {
                    Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES)
                }
                added.add(destination.name)
            }.onFailure { /* 单个失败不影响其它的 */ }
        }
        return added
    }

    fun delete(file: Path): Boolean = runCatching {
        if (file.isDirectory()) {
            Files.walk(file).use { walk ->
                walk.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        } else {
            Files.deleteIfExists(file)
        }
        true
    }.getOrDefault(false)

    private fun copyDirectory(source: Path, destination: Path) {
        Files.walk(source).use { walk ->
            walk.forEach { path ->
                val relative = source.relativize(path)
                val target = destination.resolve(relative.toString())
                if (path.isDirectory()) {
                    target.createDirectories()
                } else {
                    target.parent?.createDirectories()
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }
}
