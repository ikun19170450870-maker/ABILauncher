package com.abi.launcher

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MinecraftScannerTest {

    private lateinit var tmp: Path

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("abi-mc-scan")
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    // ---------- 测试数据构造 ----------

    private fun versionJson(
        id: String,
        type: String = "release",
        releaseTime: String = "2024-06-13T08:00:00+00:00",
        java: Int? = 17,
        component: String = "java-runtime-gamma",
        inheritsFrom: String? = null,
        libraries: Int = 3,
    ): String = buildString {
        append("{")
        append("\"id\":\"$id\",")
        append("\"type\":\"$type\",")
        append("\"releaseTime\":\"$releaseTime\",")
        append("\"mainClass\":\"net.minecraft.client.main.Main\",")
        append("\"assetIndex\":{\"id\":\"17\"},")
        if (inheritsFrom != null) append("\"inheritsFrom\":\"$inheritsFrom\",")
        if (java != null) append("\"javaVersion\":{\"component\":\"$component\",\"majorVersion\":$java},")
        append("\"libraries\":[")
        repeat(libraries) { index ->
            if (index > 0) append(",")
            append("{\"name\":\"group:artifact:1.0\"}")
        }
        append("]}")
    }

    private fun writeVersion(
        versionsDir: Path,
        id: String,
        json: String = versionJson(id),
        withJar: Boolean = true,
        jsonName: String = "$id.json",
    ) {
        val dir = versionsDir.resolve(id).createDirectories()
        dir.resolve(jsonName).writeText(json)
        if (withJar) dir.resolve(jsonName.removeSuffix(".json") + ".jar").createFile()
    }

    // ---------- 用例 ----------

    @Test
    fun `识别标准 minecraft 目录并忽略 versions 下的散落 json`() {
        val install = tmp.resolve(".minecraft").createDirectories()
        val versions = install.resolve("versions").createDirectories()
        writeVersion(versions, "1.21.4", versionJson("1.21.4", releaseTime = "2024-12-03T10:00:00+00:00", java = 21))
        writeVersion(versions, "25w02a", versionJson("25w02a", type = "snapshot", releaseTime = "2025-01-08T10:00:00+00:00", java = 21))
        // 真实环境（官方启动器 / PCL2）会在 versions 目录里放这些文件，必须忽略
        versions.resolve("jre_manifest.json").writeText("""{"files":{}}""")
        versions.resolve("version_manifest_v2.json").writeText("""{"latest":{"release":"1.21.4"}}""")
        install.resolve("launcher_profiles.json").writeText("""{"profiles":{}}""")

        val result = MinecraftScanner.scan(tmp)

        assertFalse(result.skipped)
        assertEquals(1, result.installations.size)
        val found = result.installations.single()
        assertEquals(install.toAbsolutePath().normalize(), found.root)
        assertEquals(setOf("1.21.4", "25w02a"), found.versions.map { it.id }.toSet())
        assertEquals(2, result.totalVersions)
        assertEquals(1, result.releaseCount)
        assertEquals(1, result.snapshotCount)
        assertEquals(0, result.moddedCount)
        assertEquals(listOf(21), result.javaRequirements)
        assertTrue(found.versions.all { it.clientJarPresent })
        assertEquals("官方启动器", found.launcher)
    }

    @Test
    fun `识别 PCL2 特征并让模组版本继承父版本的 Java 需求`() {
        val install = tmp.resolve("mc").createDirectories()
        val versions = install.resolve("versions").createDirectories()
        writeVersion(versions, "1.20.1", versionJson("1.20.1", java = 17))
        writeVersion(
            versions,
            "1.20.1-forge-47.2.0",
            versionJson("1.20.1-forge-47.2.0", inheritsFrom = "1.20.1", java = null),
            withJar = false,
        )
        install.resolve("PCL.ini").writeText("LaunchAdvance=0")
        install.resolve("launcher_profiles.json").writeText("""{"profiles":{}}""")

        val found = MinecraftScanner.scan(tmp).installations.single()

        assertEquals("PCL2", found.launcher, "PCL.ini 的存在优先于官方启动器特征")
        assertEquals(1, found.releaseCount)
        assertEquals(1, found.moddedCount)
        assertEquals(listOf("1.20.1", "1.20.1-forge-47.2.0"), found.versions.map { it.id }, "正式版应排在模组之前")

        val forge = found.versions.first { it.isModded }
        assertEquals("1.20.1", forge.inheritsFrom)
        assertEquals(17, forge.javaMajorVersion, "模组版本应继承父版本的 Java 需求")
        assertEquals("java-runtime-gamma", forge.javaComponent)
        assertFalse(forge.clientJarPresent)
    }

    @Test
    fun `递归识别嵌套的多实例目录`() {
        val pvp = tmp.resolve("instances").resolve("PvP").resolve(".minecraft").resolve("versions").createDirectories()
        writeVersion(pvp, "1.8.9", versionJson("1.8.9", java = 8, releaseTime = "2015-12-09T09:00:00+00:00"))
        val survival = tmp.resolve("instances").resolve("生存").resolve(".minecraft").resolve("versions").createDirectories()
        writeVersion(survival, "1.21", versionJson("1.21", java = 21, releaseTime = "2024-06-13T08:00:00+00:00"))

        val result = MinecraftScanner.scan(tmp)

        assertEquals(2, result.installations.size)
        assertEquals(2, result.totalVersions)
        assertEquals(listOf(8, 21), result.javaRequirements)
    }

    @Test
    fun `直接选中 versions 目录时把父目录视为游戏目录`() {
        val install = tmp.resolve(".minecraft").createDirectories()
        val versions = install.resolve("versions").createDirectories()
        writeVersion(versions, "1.19.2", versionJson("1.19.2", java = 17))

        val found = MinecraftScanner.scan(versions).installations.single()

        assertEquals(install.toAbsolutePath().normalize(), found.root)
        assertEquals(listOf("1.19.2"), found.versions.map { it.id })
    }

    @Test
    fun `直接选中单个版本目录`() {
        val install = tmp.resolve(".minecraft").createDirectories()
        val versionDir = install.resolve("versions").resolve("1.18.2").createDirectories()
        versionDir.resolve("1.18.2.json").writeText(versionJson("1.18.2", java = 17))
        versionDir.resolve("1.18.2.jar").createFile()

        val found = MinecraftScanner.scan(versionDir).installations.single()

        assertEquals(install.toAbsolutePath().normalize(), found.root)
        assertEquals(listOf("1.18.2"), found.versions.map { it.id })
        assertTrue(found.versions.single().clientJarPresent)
    }

    @Test
    fun `普通 json 不会被误判为游戏版本`() {
        val docs = tmp.resolve("docs").createDirectories()
        docs.resolve("note.json").writeText("""{"title":"hello","count":3}""")
        tmp.resolve("readme.json").writeText("""{"a":1}""")

        val result = MinecraftScanner.scan(tmp)

        assertEquals(0, result.installations.size)
        assertFalse(result.skipped)
    }

    @Test
    fun `目录不存在时返回空结果并标记 skipped`() {
        val result = MinecraftScanner.scan(tmp.resolve("not-here"))

        assertTrue(result.skipped)
        assertEquals(0, result.installations.size)
        assertEquals(0, result.totalVersions)
    }

    @Test
    fun `既没有 javaVersion 也没有父版本时 Java 需求为未知`() {
        val versions = tmp.resolve(".minecraft").resolve("versions").createDirectories()
        writeVersion(versions, "custom-1.0", versionJson("custom-1.0", java = null))

        val version = MinecraftScanner.scan(tmp).installations.single().versions.single()

        assertNull(version.javaMajorVersion)
        assertFalse(version.isModded)
    }

    @Test
    fun `正式版按发布日期倒序排列`() {
        val versions = tmp.resolve(".minecraft").resolve("versions").createDirectories()
        writeVersion(versions, "1.16.5", versionJson("1.16.5", releaseTime = "2021-01-15T10:00:00+00:00", java = 8))
        writeVersion(versions, "1.21.4", versionJson("1.21.4", releaseTime = "2024-12-03T10:00:00+00:00", java = 21))
        writeVersion(versions, "1.19.2", versionJson("1.19.2", releaseTime = "2022-08-05T10:00:00+00:00", java = 17))

        val found = MinecraftScanner.scan(tmp).installations.single()

        assertEquals(listOf("1.21.4", "1.19.2", "1.16.5"), found.versions.map { it.id })
    }

    @Test
    fun `超过最大深度就不再继续查找`() {
        val deep = tmp.resolve("l1").resolve("l2").resolve("l3").resolve("l4").resolve("l5").resolve(".minecraft")
        val versions = deep.resolve("versions").createDirectories()
        writeVersion(versions, "1.21", versionJson("1.21", java = 21))

        // .minecraft 位于第 6 层，默认 maxDepth = 5 时找不到
        assertEquals(0, MinecraftScanner.scan(tmp).installations.size)
        // 放宽深度限制后可以找到
        assertEquals(1, MinecraftScanner.scan(tmp, maxDepth = 8).installations.size)
    }

    @Test
    fun `版本 JSON 被改名时按目录名 fallback`() {
        val versions = tmp.resolve(".minecraft").resolve("versions").createDirectories()
        // 目录名与 JSON 文件名不一致，但 JSON 里的 id 与目录名相同
        writeVersion(versions, "1.12.2", versionJson("1.12.2", java = 8), jsonName = "version.json")

        val found = MinecraftScanner.scan(tmp).installations.single()

        assertEquals(listOf("1.12.2"), found.versions.map { it.id })
    }
}
