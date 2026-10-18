package com.abi.launcher.launch

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

data class DownloadSpec(
    val path: String?,
    val url: String?,
    val sha1: String?,
    val size: Long,
)

data class LibrarySpec(
    /** Maven 坐标，例如 org.lwjgl:lwjgl:3.3.3 或 org.lwjgl:lwjgl:3.3.3:natives-windows */
    val name: String,
    val rules: List<RuleSpec>,
    /** downloads.artifact */
    val artifact: DownloadSpec?,
    /** downloads.classifiers（旧版 natives 用） */
    val classifiers: Map<String, DownloadSpec>,
    /** 旧版 natives 映射：windows -> natives-windows */
    val natives: Map<String, String>,
    /** extract.exclude */
    val extractExclude: List<String>,
)

data class ArgumentSpec(
    val value: List<String>,
    val rules: List<RuleSpec>,
)

/** 一个版本的定义（已合并 inheritsFrom 链） */
data class VersionSpec(
    val id: String,
    val type: String?,
    val mainClass: String?,
    val assets: String?,
    val assetIndexId: String?,
    val assetIndexUrl: String?,
    val javaMajorVersion: Int?,
    val javaComponent: String?,
    val libraries: List<LibrarySpec>,
    val jvmArguments: List<ArgumentSpec>,
    val gameArguments: List<ArgumentSpec>,
    /** 1.13 以前的版本用这一整串参数代替 arguments.game */
    val minecraftArguments: String?,
    val loggingFileId: String?,
    val inheritsFrom: String?,
    val sourcePath: Path,
    /** 客户端 jar 的候选位置，按优先级排列（本版本目录 → 父版本目录） */
    val clientJarCandidates: List<Path> = emptyList(),
    /** inheritsFrom 指向的父版本没找到时记录下来 */
    val missingParent: String? = null,
) {
    val isModded: Boolean get() = !inheritsFrom.isNullOrBlank()
}

object VersionSpecReader {

    /** 只读自身，不处理继承 */
    fun read(json: Path): VersionSpec {
        val obj = readJsonObject(json) ?: error("无法解析版本 JSON: $json")
        val arguments = obj.obj("arguments")
        return VersionSpec(
            id = obj.scalar("id") ?: json.name.removeSuffix(".json"),
            type = obj.str("type"),
            mainClass = obj.str("mainClass"),
            assets = obj.scalar("assets"),
            assetIndexId = obj.obj("assetIndex")?.scalar("id"),
            assetIndexUrl = obj.obj("assetIndex")?.str("url"),
            javaMajorVersion = obj.obj("javaVersion")?.int("majorVersion"),
            javaComponent = obj.obj("javaVersion")?.str("component"),
            libraries = obj.arr("libraries")?.mapNotNull { element ->
                element.takeIf { it.isJsonObject }?.asJsonObject?.let { parseLibrary(it) }
            }.orEmpty(),
            jvmArguments = parseArguments(arguments?.arr("jvm")),
            gameArguments = parseArguments(arguments?.arr("game")),
            minecraftArguments = obj.str("minecraftArguments"),
            loggingFileId = obj.obj("logging")?.obj("client")?.obj("file")?.str("id"),
            inheritsFrom = obj.str("inheritsFrom"),
            sourcePath = json.toAbsolutePath().normalize(),
        )
    }

    /** 读取并按 inheritsFrom 链合并出最终版本定义 */
    fun resolve(versionJson: Path, installRoot: Path, depth: Int = 0): VersionSpec {
        val own = read(versionJson)
        val parentId = own.inheritsFrom?.trim()?.takeIf { it.isNotEmpty() }
        if (parentId == null || depth >= 8) return own.withJarCandidates(versionJson)

        val parentJson = findVersionJson(installRoot, parentId)
        if (parentJson == null) {
            return own.copy(missingParent = parentId).withJarCandidates(versionJson)
        }
        val parent = resolve(parentJson, installRoot, depth + 1)
        return merge(parent, own).withJarCandidates(versionJson, parent.clientJarCandidates)
    }

    /** 在 versions/ 下按版本号找 JSON；目录名与 id 不一致时会逐个比对 */
    fun findVersionJson(installRoot: Path, versionId: String): Path? {
        val versionsDir = installRoot.resolve("versions")
        val direct = versionsDir.resolve(versionId).resolve("$versionId.json")
        if (direct.isRegularFile()) return direct

        if (!Files.isDirectory(versionsDir)) return null
        return runCatching {
            Files.newDirectoryStream(versionsDir).use { stream ->
                stream.filter { it.isRegularFile() && it.name.lowercase().endsWith(".json") }
                    .toList()
                    .firstOrNull { json ->
                        val obj = readJsonObject(json) ?: return@firstOrNull false
                        (obj.scalar("id") ?: json.name.removeSuffix(".json")) == versionId
                    }
            }
        }.getOrNull()
    }

    private fun parseLibrary(obj: com.google.gson.JsonObject): LibrarySpec = LibrarySpec(
        name = obj.str("name").orEmpty(),
        rules = parseRules(obj.arr("rules")),
        artifact = obj.obj("downloads")?.obj("artifact")?.let { parseDownload(it) },
        classifiers = obj.obj("downloads")?.obj("classifiers")?.entrySet()
            ?.mapNotNull { (key, value) ->
                value.takeIf { it.isJsonObject }?.asJsonObject?.let { key to parseDownload(it) }
            }?.toMap().orEmpty(),
        natives = obj.obj("natives")?.entrySet()
            ?.mapNotNull { (key, value) ->
                value.takeIf { it.isJsonPrimitive }?.asString?.let { key to it }
            }?.toMap().orEmpty(),
        extractExclude = obj.obj("extract")?.arr("exclude")
            ?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }.orEmpty(),
    )

    private fun parseDownload(obj: com.google.gson.JsonObject) = DownloadSpec(
        path = obj.str("path"),
        url = obj.str("url"),
        sha1 = obj.str("sha1"),
        size = obj.long("size") ?: 0L,
    )

    private fun parseArguments(array: com.google.gson.JsonArray?): List<ArgumentSpec> {
        if (array == null) return emptyList()
        return array.mapNotNull { element ->
            when {
                element.isJsonPrimitive -> ArgumentSpec(listOf(element.asString), emptyList())
                element.isJsonObject -> {
                    val obj = element.asJsonObject
                    val values = obj.arr("value")?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }
                        ?: listOfNotNull(obj.str("value"))
                    ArgumentSpec(values, parseRules(obj.arr("rules")))
                }
                else -> null
            }
        }
    }

    private fun parseRules(array: com.google.gson.JsonArray?): List<RuleSpec> {
        if (array == null) return emptyList()
        return array.mapNotNull { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            RuleSpec(
                action = obj.str("action").orEmpty(),
                os = obj.obj("os")?.let { OsRule(it.str("name"), it.str("version"), it.str("arch")) },
                features = obj.obj("features")?.entrySet()
                    ?.mapNotNull { (key, value) ->
                        value.takeIf { it.isJsonPrimitive }?.asBoolean?.let { key to it }
                    }?.toMap().orEmpty(),
            )
        }
    }

    /** 子版本覆盖父版本的标量字段，库与参数则拼接 */
    private fun merge(parent: VersionSpec, child: VersionSpec): VersionSpec = child.copy(
        type = child.type ?: parent.type,
        mainClass = child.mainClass ?: parent.mainClass,
        assets = child.assets ?: parent.assets,
        assetIndexId = child.assetIndexId ?: parent.assetIndexId,
        assetIndexUrl = child.assetIndexUrl ?: parent.assetIndexUrl,
        javaMajorVersion = child.javaMajorVersion ?: parent.javaMajorVersion,
        javaComponent = child.javaComponent ?: parent.javaComponent,
        minecraftArguments = child.minecraftArguments ?: parent.minecraftArguments,
        loggingFileId = child.loggingFileId ?: parent.loggingFileId,
        libraries = dedupeByName(parent.libraries + child.libraries),
        jvmArguments = parent.jvmArguments + child.jvmArguments,
        gameArguments = parent.gameArguments + child.gameArguments,
        missingParent = parent.missingParent,
    )

    /**
     * 同名库以子版本（靠后）为准，顺序保持父版本在前。
     *
     * 去重键是 `group:artifact[:classifier]`，**不含版本号**：
     * 子版本用高版本覆盖父版本的同名库时必须只保留子版本的那份，否则 classpath 上会同时出现新旧两个 jar。
     * 而 `lwjgl` 与 `lwjgl:natives-windows` 属于不同分类库，必须各自保留。
     */
    private fun dedupeByName(libraries: List<LibrarySpec>): List<LibrarySpec> {
        val byKey = LinkedHashMap<String, LibrarySpec>()
        libraries.forEach { byKey[dedupeKey(it.name)] = it }
        return byKey.values.toList()
    }

    private fun dedupeKey(name: String): String {
        val parts = name.split(':')
        return when {
            parts.size >= 4 -> "${parts[0]}:${parts[1]}:${parts[3]}"
            parts.size >= 3 -> "${parts[0]}:${parts[1]}"
            else -> name
        }
    }

    private fun VersionSpec.withJarCandidates(json: Path, parentCandidates: List<Path> = emptyList()): VersionSpec {
        val dir = json.toAbsolutePath().normalize().parent ?: return this
        val stem = json.name.removeSuffix(".json")
        val own = listOf(dir.resolve("$id.jar"), dir.resolve("$stem.jar"))
        return copy(clientJarCandidates = (own + parentCandidates).distinct())
    }
}
