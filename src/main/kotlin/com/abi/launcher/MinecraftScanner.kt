package com.abi.launcher

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * 一个可启动的 Minecraft 版本，对应 `versions/<目录名>/<目录名>.json`。
 */
data class MinecraftVersion(
    /** 版本号，例如 1.21.4、1.20.1-forge-47.2.0 */
    val id: String,
    /** 原版 JSON 中的 type：release / snapshot / old_beta / old_alpha */
    val type: String?,
    val releaseTime: String?,
    /** 启动该版本所需的 Java 大版本，来自 javaVersion.majorVersion */
    val javaMajorVersion: Int?,
    /** javaVersion.component，例如 java-runtime-epsilon */
    val javaComponent: String?,
    /** Forge / Fabric / OptiFine 等模组端会继承某个原版版本 */
    val inheritsFrom: String?,
    val mainClass: String?,
    val assetIndex: String?,
    val libraryCount: Int,
    /** 版本目录内是否已经有客户端 jar */
    val clientJarPresent: Boolean,
    /** 版本定义的来源文件 */
    val jsonPath: Path,
) {
    val isModded: Boolean get() = !inheritsFrom.isNullOrBlank()

    /** 界面展示用的中文分类 */
    val category: String
        get() = when {
            isModded -> "模组"
            type == "release" -> "正式版"
            type == "snapshot" -> "快照"
            type == "old_beta" -> "远古 Beta"
            type == "old_alpha" -> "远古 Alpha"
            type.isNullOrBlank() -> "未知"
            else -> type
        }
}

/** 一个游戏目录（通常是 .minecraft），内含若干版本。 */
data class MinecraftInstallation(
    val root: Path,
    val name: String,
    /** 由目录特征识别出的启动器，例如 PCL2、HMCL；识别不出为 null */
    val launcher: String?,
    val versions: List<MinecraftVersion>,
) {
    val releaseCount: Int get() = versions.count { !it.isModded && it.type == "release" }
    val snapshotCount: Int get() = versions.count { !it.isModded && it.type == "snapshot" }
    val moddedCount: Int get() = versions.count { it.isModded }
}

/** 一次扫描的完整结果。 */
data class ScanResult(
    val root: Path,
    val installations: List<MinecraftInstallation>,
    /** 实际遍历过的子目录数量 */
    val scannedDirectories: Int,
    /** 目标目录不存在或不是目录 */
    val skipped: Boolean = false,
    /** 目录数量超过上限，扫描被提前结束 */
    val truncated: Boolean = false,
) {
    val totalVersions: Int get() = installations.sumOf { it.versions.size }
    val allVersions: List<MinecraftVersion> get() = installations.flatMap { it.versions }
    val releaseCount: Int get() = allVersions.count { !it.isModded && it.type == "release" }
    val snapshotCount: Int get() = allVersions.count { !it.isModded && it.type == "snapshot" }
    val moddedCount: Int get() = allVersions.count { it.isModded }

    /** 这些版本分别需要哪些 Java 大版本 */
    val javaRequirements: List<Int> get() = allVersions.mapNotNull { it.javaMajorVersion }.distinct().sorted()
}

/**
 * 在任意目录中识别 Minecraft Java 版。
 *
 * 支持的目录形态：
 *  - 标准 `.minecraft`（含 versions 子目录），例如 `%APPDATA%\.minecraft`
 *  - 启动器实例目录（Prism / MultiMC / CurseForge / PCL2 等）：实例里再套 `.minecraft`
 *  - 用户直接选中 `versions` 目录
 *  - 用户直接选中单个版本目录 `versions/<id>`
 *  - 以上任意形态的上级目录：递归查找，默认最深 5 层
 */
object MinecraftScanner {

    const val DEFAULT_MAX_DEPTH = 5
    const val DEFAULT_MAX_DIRECTORIES = 20_000

    /** 游戏数据目录，递归时不需要进入 */
    private val IGNORED_DIRECTORIES = setOf(
        "libraries", "assets", "saves", "logs", "screenshots", "resourcepacks",
        "shaderpacks", "crash-reports", "crash_reports", "runtime", "cache",
        "webcache", "webcache2", "downloads", "natives", "mods", "config",
        "defaultconfigs", "backups", "backup", "temp", "tmp", "staging",
        "node_modules", ".git", ".idea", ".vscode", "quickplay", "jre", "java",
        "pcl", ".hmcl", "versions",
    )

    /** versions 目录里可能出现、但不是版本定义的 JSON 文件 */
    private val NON_VERSION_JSON = setOf(
        "jre_manifest.json", "version_manifest.json", "version_manifest_v2.json",
        "launcher_profiles.json", "launcher_accounts.json", "minecraftinstance.json",
    )

    /** 判定“这确实是一份 Minecraft 版本 JSON”的字段，避免把普通 JSON 误判成版本 */
    private val VERSION_MARKER_FIELDS = listOf(
        "mainClass", "libraries", "downloads", "assetIndex", "assets",
        "inheritsFrom", "releaseTime", "type", "minimumLauncherVersion",
    )

    private val LAUNCHER_MARKERS = listOf(
        "PCL.ini" to "PCL2",
        "hmcl.json" to "HMCL",
        ".hmcl.json" to "HMCL",
        "instance.cfg" to "MultiMC / Prism",
        "mmc-pack.json" to "MultiMC / Prism",
        "minecraftinstance.json" to "CurseForge",
        "launcher_profiles.json" to "官方启动器",
    )

    fun scan(
        root: Path,
        maxDepth: Int = DEFAULT_MAX_DEPTH,
        maxDirectories: Int = DEFAULT_MAX_DIRECTORIES,
    ): ScanResult {
        val normalizedRoot = root.toAbsolutePath().normalize()
        if (!root.isDirectory()) {
            return ScanResult(normalizedRoot, emptyList(), 0, skipped = true)
        }

        val found = LinkedHashMap<Path, MinecraftInstallation>()
        val queue = ArrayDeque<Pair<Path, Int>>()
        queue.addLast(root to 0)
        var visited = 0
        var truncated = false

        while (queue.isNotEmpty()) {
            if (visited >= maxDirectories) {
                truncated = true
                break
            }
            val (dir, depth) = queue.removeFirst()
            visited++

            // 形态一：目录本身是游戏目录（含 versions 子目录）
            val versionsDir = dir.resolve("versions")
            if (versionsDir.isDirectory()) {
                val versions = readVersionsIn(versionsDir)
                if (versions.isNotEmpty()) {
                    val installation = buildInstallation(dir, versions)
                    found[installation.root] = installation
                    continue // 游戏目录内部只有游戏数据，不再向下遍历
                }
            }

            if (depth == 0) {
                // 形态二：用户直接选中了 versions 目录
                val directVersions = readVersionsIn(dir)
                if (directVersions.isNotEmpty()) {
                    val installation = buildInstallation(dir.parent ?: dir, directVersions)
                    found[installation.root] = installation
                    continue
                }
                // 形态三：用户直接选中了单个版本目录
                val single = parseVersionDirectory(dir)
                if (single != null) {
                    val parent = dir.parent
                    val installRoot = if (parent?.name == "versions") parent.parent ?: parent else parent ?: dir
                    val installation = buildInstallation(installRoot, listOf(single))
                    found[installation.root] = installation
                    continue
                }
            }

            if (depth >= maxDepth) continue
            for (child in subDirectories(dir)) {
                if (child.name.lowercase() in IGNORED_DIRECTORIES) continue
                queue.addLast(child to depth + 1)
            }
        }

        return ScanResult(
            root = normalizedRoot,
            installations = found.values.sortedBy { it.root.toString().lowercase() },
            scannedDirectories = visited,
            truncated = truncated,
        )
    }

    /**
     * 读取「该目录下」的版本：只看 `<目录>/versions/<版本>/`，**不递归**到子文件夹里去找游戏。
     *
     * 这是启动器需要的语义：用户选中的就是游戏根目录（通常是 .minecraft）。
     * 另外兼容用户直接选中 versions 目录的情况。
     */
    fun openGameRoot(root: Path): ScanResult {
        val normalized = root.toAbsolutePath().normalize()
        if (!root.isDirectory()) return ScanResult(normalized, emptyList(), 0, skipped = true)

        // 1. 标准情况：<目录>/versions/<版本>
        val versions = readVersionsIn(root.resolve("versions"))
        if (versions.isNotEmpty()) {
            return ScanResult(normalized, listOf(buildInstallation(root, versions)), 1)
        }

        // 2. 用户直接选中的就是 versions 目录
        val direct = readVersionsIn(root)
        if (direct.isNotEmpty()) {
            return ScanResult(normalized, listOf(buildInstallation(root.parent ?: root, direct)), 1)
        }

        // 3. 该目录下没有版本：如实返回空结果，不再向子目录搜索
        return ScanResult(normalized, emptyList(), 1)
    }

    private fun subDirectories(dir: Path): List<Path> = runCatching {
        Files.newDirectoryStream(dir).use { stream -> stream.filter { it.isDirectory() } }
    }.getOrDefault(emptyList())

    private fun readVersionsIn(versionsDir: Path): List<MinecraftVersion> =
        subDirectories(versionsDir).mapNotNull { parseVersionDirectory(it) }

    /** 解析一个版本目录，得到版本信息；不像版本目录则返回 null。 */
    private fun parseVersionDirectory(versionDir: Path): MinecraftVersion? {
        for ((json, exactNameMatch) in candidateVersionJsons(versionDir)) {
            val obj = readJsonObject(json) ?: continue
            toVersion(obj, versionDir, json, exactNameMatch)?.let { return it }
        }
        return null
    }

    /**
     * 版本定义通常就叫 `<目录名>.json`；若被改名，则退回“目录里 id 与目录名一致的那个 JSON”。
     */
    private fun candidateVersionJsons(versionDir: Path): List<Pair<Path, Boolean>> {
        val named = versionDir.resolve("${versionDir.name}.json")
        if (named.isRegularFile()) return listOf(named to true)

        val others = runCatching {
            Files.newDirectoryStream(versionDir).use { stream ->
                stream.filter { it.isRegularFile() && it.name.lowercase().endsWith(".json") }
                    .filter { it.name.lowercase() !in NON_VERSION_JSON }
            }
        }.getOrDefault(emptyList())

        return others.map { it to (it.name.removeSuffix(".json") == versionDir.name) }
    }

    private fun readJsonObject(json: Path): JsonObject? = runCatching {
        JsonParser.parseString(json.readText()).asJsonObject
    }.getOrNull()

    private fun toVersion(
        obj: JsonObject,
        versionDir: Path,
        json: Path,
        exactNameMatch: Boolean,
    ): MinecraftVersion? {
        // 至少要有一个版本定义特征字段，否则视为普通 JSON
        if (VERSION_MARKER_FIELDS.none { obj.has(it) }) return null
        val declaredId = obj.scalar("id")
        if (declaredId == null && !exactNameMatch) return null

        val id = declaredId ?: versionDir.name
        val javaVersion = obj.obj("javaVersion")
        return MinecraftVersion(
            id = id,
            type = obj.scalar("type"),
            releaseTime = obj.scalar("releaseTime"),
            javaMajorVersion = javaVersion?.int("majorVersion"),
            javaComponent = javaVersion?.scalar("component"),
            inheritsFrom = obj.scalar("inheritsFrom"),
            mainClass = obj.scalar("mainClass"),
            assetIndex = obj.obj("assetIndex")?.scalar("id") ?: obj.scalar("assets"),
            libraryCount = obj.arraySize("libraries"),
            clientJarPresent = hasClientJar(versionDir, id, json),
            jsonPath = json.toAbsolutePath().normalize(),
        )
    }

    private fun hasClientJar(versionDir: Path, id: String, json: Path): Boolean {
        val stem = json.name.removeSuffix(".json").removeSuffix(".JSON")
        return listOf("$stem.jar", "$id.jar", "${versionDir.name}.jar")
            .distinct()
            .any { versionDir.resolve(it).isRegularFile() }
    }

    private fun buildInstallation(root: Path, versions: List<MinecraftVersion>): MinecraftInstallation {
        val normalized = root.toAbsolutePath().normalize()
        val resolved = inheritJavaRequirement(versions)
        val sorted = resolved.sortedWith(
            compareBy<MinecraftVersion> { categoryRank(it) }
                .thenByDescending { it.releaseTime ?: "" }
                .thenByDescending { it.id.lowercase() },
        )
        return MinecraftInstallation(
            root = normalized,
            name = normalized.fileName?.toString() ?: normalized.toString(),
            launcher = detectLauncher(normalized),
            versions = sorted,
        )
    }

    private fun categoryRank(version: MinecraftVersion): Int = when {
        version.isModded -> 2
        version.type == "release" -> 0
        else -> 1
    }

    /** 模组版本自己常常没有 javaVersion，需要沿 inheritsFrom 链去找父版本 */
    private fun inheritJavaRequirement(versions: List<MinecraftVersion>): List<MinecraftVersion> {
        val byId = versions.associateBy { it.id }
        return versions.map { version ->
            if (version.javaMajorVersion != null || version.inheritsFrom == null) return@map version

            var current: MinecraftVersion? = byId[version.inheritsFrom]
            var guard = 0
            while (current != null && current.javaMajorVersion == null && current.inheritsFrom != null && guard++ < 8) {
                current = byId[current.inheritsFrom]
            }
            val parent = current
            if (parent?.javaMajorVersion != null) {
                version.copy(
                    javaMajorVersion = parent.javaMajorVersion,
                    javaComponent = parent.javaComponent,
                )
            } else {
                version
            }
        }
    }

    private fun detectLauncher(root: Path): String? =
        LAUNCHER_MARKERS.firstOrNull { root.resolve(it.first).isRegularFile() }?.second
}

// ---------- Gson 小工具：让取值写法短一些，并容忍字段缺失或类型不同 ----------

private fun JsonObject.obj(name: String): JsonObject? =
    get(name)?.takeIf { it.isJsonObject }?.asJsonObject

private fun JsonObject.arraySize(name: String): Int =
    get(name)?.takeIf { it.isJsonArray }?.asJsonArray?.size() ?: 0

private fun JsonObject.int(name: String): Int? =
    get(name)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.takeIf { it.isNumber }?.asInt

/** 字符串或数字都当作字符串读出来（assetIndex.id 有时是数字） */
private fun JsonObject.scalar(name: String): String? {
    val primitive = get(name)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
    return when {
        primitive.isString -> primitive.asString
        primitive.isNumber -> primitive.asNumber.toString()
        else -> null
    }
}
