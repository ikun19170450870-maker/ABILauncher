package com.abi.launcher.ui

import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import javax.imageio.stream.ImageOutputStream
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 版本文件管理（模组/光影/存档/材质包）与背景图解码 */
class VersionFilesTest {

    private lateinit var tmp: Path
    private lateinit var gameDir: Path

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("abi-files")
        gameDir = tmp.resolve("minecraft")
        Files.createDirectories(gameDir)
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    private fun makeFile(name: String): Path {
        val file = tmp.resolve(name)
        Files.writeString(file, "x")
        return file
    }

    @Test
    fun `拖入模组会被复制进 mods 目录`() {
        val added = VersionFiles.import(gameDir, FileCategory.Mods, listOf(makeFile("sodium.jar")))

        assertEquals(listOf("sodium.jar"), added)
        assertTrue(Files.exists(gameDir.resolve("mods/sodium.jar")))
        assertEquals(1, VersionFiles.list(gameDir, FileCategory.Mods).size)
    }

    @Test
    fun `重复文件不会覆盖而是加序号`() {
        val source = makeFile("sodium.jar")
        VersionFiles.import(gameDir, FileCategory.Mods, listOf(source))
        val again = VersionFiles.import(gameDir, FileCategory.Mods, listOf(source))

        assertEquals(listOf("sodium (2).jar"), again)
        assertEquals(2, VersionFiles.list(gameDir, FileCategory.Mods).size)
    }

    @Test
    fun `各类别落在各自的目录`() {
        VersionFiles.import(gameDir, FileCategory.Shaders, listOf(makeFile("bliss.zip")))
        VersionFiles.import(gameDir, FileCategory.ResourcePacks, listOf(makeFile("faithful.zip")))

        assertTrue(Files.exists(gameDir.resolve("shaderpacks/bliss.zip")))
        assertTrue(Files.exists(gameDir.resolve("resourcepacks/faithful.zip")))
    }

    @Test
    fun `可以删除文件`() {
        VersionFiles.import(gameDir, FileCategory.Mods, listOf(makeFile("bad.jar")))
        val file = VersionFiles.list(gameDir, FileCategory.Mods).single()

        assertTrue(VersionFiles.delete(file.path))
        assertTrue(VersionFiles.list(gameDir, FileCategory.Mods).isEmpty())
    }

    @Test
    fun `只有装了 Iris 或 OptiFine 才算有光影加载器`() {
        assertFalse(VersionFiles.hasShaderLoader(gameDir))

        VersionFiles.import(gameDir, FileCategory.Mods, listOf(makeFile("fabric-api.jar")))
        assertFalse(VersionFiles.hasShaderLoader(gameDir), "只有 Fabric API 不算")

        VersionFiles.import(gameDir, FileCategory.Mods, listOf(makeFile("iris-1.7.5.jar")))
        assertTrue(VersionFiles.hasShaderLoader(gameDir))
    }

    @Test
    fun `存档文件夹也能拖入和删除`() {
        val save = tmp.resolve("我的世界")
        Files.createDirectories(save.resolve("region"))
        Files.writeString(save.resolve("level.dat"), "x")

        val added = VersionFiles.import(gameDir, FileCategory.Saves, listOf(save))

        assertEquals(listOf("我的世界"), added)
        assertTrue(Files.exists(gameDir.resolve("saves/我的世界/region")))
        assertTrue(VersionFiles.list(gameDir, FileCategory.Saves).single().isDirectory)

        VersionFiles.delete(gameDir.resolve("saves/我的世界"))
        assertFalse(Files.exists(gameDir.resolve("saves/我的世界")))
    }

    @Test
    fun `静态图背景只有一帧`() {
        val png = tmp.resolve("bg.png")
        val image = BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB)
        ImageIO.write(image, "png", png.toFile())

        val frames = assertNotNull(Backdrop.load(png))

        assertEquals(1, frames.images.size)
        assertFalse(frames.animated)
        assertEquals(8, frames.images[0].width)
    }

    @Test
    fun `GIF 动图会被解成多帧`() {
        val gif = tmp.resolve("bg.gif")
        writeGif(gif, 3)

        val frames = assertNotNull(Backdrop.load(gif))

        assertEquals(3, frames.images.size)
        assertTrue(frames.animated)
        assertTrue(frames.delayMillis > 0)
    }

    @Test
    fun `不是图片的文件返回 null`() {
        val fake = makeFile("bg.mp4")

        assertEquals(null, Backdrop.load(fake))
    }

    private fun writeGif(target: Path, frameCount: Int) {
        val writer = ImageIO.getImageWritersByFormatName("gif").next()
        val output: ImageOutputStream = ImageIO.createImageOutputStream(target.toFile())
        writer.output = output
        writer.prepareWriteSequence(null)
        repeat(frameCount) { index ->
            val frame = BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB)
            frame.setRGB(0, 0, 0xFF000000.toInt() or (index * 40))
            writer.writeToSequence(IIOImage(frame, null, null), null)
        }
        writer.endWriteSequence()
        output.close()
        writer.dispose()
    }
}
