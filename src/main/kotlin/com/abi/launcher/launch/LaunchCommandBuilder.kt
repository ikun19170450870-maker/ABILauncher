package com.abi.launcher.launch

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.isRegularFile

data class LaunchOptions(
    val playerName: String = "Steve",
    val maxMemoryMb: Int = 2048,
    val minMemoryMb: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    val extraJvmArgs: List<String> = emptyList(),
    /** 默认为游戏根目录 */
    val gameDirectory: Path? = null,
    val demoUser: Boolean = false,
    /** 离线模式的 accessToken */
    val accessToken: String = "0",
    /** 覆盖离线 UUID 计算 */
    val uuid: String? = null,
)

/** 旧版格式里需要从分类库解压出来的 natives */
data class NativeExtraction(val jar: Path, val excludes: List<String>)

data class PreflightReport(
    val missingClientJar: List<Path>,
    val missingLibraries: List<Path>,
    val missingAssetIndex: Path?,
    val missingAssets: Int,
) {
    val isReady: Boolean
        get() = missingClientJar.isEmpty() && missingLibraries.isEmpty() && missingAssetIndex == null

    fun summary(): String = buildString {
        if (missingClientJar.isNotEmpty()) {
            append("缺少客户端 jar（")
            append(missingClientJar.joinToString("、") { it.fileName.toString() })
            append("）")
        }
        if (missingLibraries.isNotEmpty()) {
            if (isNotEmpty()) append("；")
            append("缺少 ${missingLibraries.size} 个依赖库")
        }
        if (missingAssetIndex != null) {
            if (isNotEmpty()) append("；")
            append("缺少资源索引 ${missingAssetIndex.fileName}")
        }
        if (missingAssets > 0) {
            if (isNotEmpty()) append("；")
            append("有 $missingAssets 个资源文件缺失（可能影响贴图/音效）")
        }
        if (isEmpty()) append("文件完整，可以启动")
    }
}

data class LaunchPlan(
    val installRoot: Path,
    val version: VersionSpec,
    val versionDir: Path,
    val java: JavaRuntime,
    val command: List<String>,
    val classpath: List<Path>,
    val nativesDirectory: Path,
    val gameDirectory: Path,
    val nativesToExtract: List<NativeExtraction>,
    val assetIndexPath: Path?,
    val preflight: PreflightReport,
    val warnings: List<String>,
) {
    val isReady: Boolean get() = preflight.isReady

    /** 完整命令行（classpath 会非常长） */
    fun commandLine(): String = command.joinToString(" ") { quote(it) }

    /** 给日志用的紧凑命令行 */
    fun compactCommandLine(): String {
        val classpathText = classpath.joinToString(File.pathSeparator)
        return command.joinToString(" ") { argument ->
            when {
                argument == classpathText -> "<classpath: ${classpath.size} 项>"
                else -> quote(argument)
            }
        }
    }

    private fun quote(argument: String): String =
        if (argument.any { it.isWhitespace() || it == '"' }) "\"$argument\"" else argument
}

object LaunchCommandBuilder {

    fun plan(
        version: VersionSpec,
        versionDir: Path,
        installRoot: Path,
        java: JavaRuntime,
        options: LaunchOptions,
        /** 便于测试注入；为空时按当前系统推断 */
        ruleContext: RuleContext? = null,
        checkAssets: Boolean = true,
    ): LaunchPlan {
        val warnings = mutableListOf<String>()
        val context = ruleContext ?: RuleContext.current(featuresOf(options))

        val librariesDir = installRoot.resolve("libraries")
        val assetsDir = installRoot.resolve("assets")
        val nativesDir = versionDir.resolve("natives")
        val gameDir = options.gameDirectory ?: installRoot

        if (version.missingParent != null) {
            warnings += "找不到父版本 ${version.missingParent}，仅使用本版本 JSON 的内容"
        }

        // ---- 1. 按规则筛出本机适用的库，同时区分 classpath 与需要解压的 natives ----
        val applicable = version.libraries.filter { RuleEvaluator.isAllowed(it.rules, context) }
        val classpathJars = mutableListOf<Path>()
        val nativesToExtract = mutableListOf<NativeExtraction>()
        val missingLibraries = mutableListOf<Path>()

        applicable.forEach { library ->
            val artifact = artifactPath(librariesDir, library)
            if (artifact != null) {
                classpathJars.add(artifact)
                if (!artifact.isRegularFile()) missingLibraries.add(artifact)
            } else if (library.natives.isEmpty()) {
                warnings.add("无法推断库路径: ${library.name}")
            }

            // 旧版格式：natives 是单独的分类库，需要解压到 natives 目录
            library.natives[context.osName]?.let { classifier ->
                val resolved = classifier.replace("\${arch}", if (context.osArch == "x86") "32" else "64")
                nativeArtifactPath(librariesDir, library, resolved)?.let { jar ->
                    if (jar.isRegularFile()) {
                        nativesToExtract.add(NativeExtraction(jar, library.extractExclude))
                    } else {
                        missingLibraries.add(jar)
                    }
                }
            }
        }

        // ---- 2. 客户端 jar ----
        val clientJar = version.clientJarCandidates.firstOrNull { it.isRegularFile() }
        val missingClientJar = if (clientJar == null) version.clientJarCandidates else emptyList()
        if (clientJar != null) {
            classpathJars.add(clientJar)
        } else if (version.clientJarCandidates.isEmpty()) {
            warnings += "版本 JSON 未提供客户端 jar 路径"
        }

        // ---- 3. 资源索引与文件 ----
        val assetIndexId = version.assetIndexId ?: version.assets
        val assetIndexPath = assetIndexId?.takeIf { it.isNotBlank() }
            ?.let { assetsDir.resolve("indexes").resolve("$it.json") }
        val missingAssetIndex = assetIndexPath?.takeIf { !it.isRegularFile() }
        val missingAssets = if (checkAssets && assetIndexPath != null && missingAssetIndex == null) {
            countMissingAssets(assetsDir, assetIndexPath)
        } else {
            0
        }

        // ---- 4. 占位符变量表 ----
        val uuid = options.uuid ?: offlineUuid(options.playerName)
        val variables = mapOf(
            "auth_player_name" to options.playerName,
            "version_name" to version.id,
            "game_directory" to gameDir.toString(),
            "assets_root" to assetsDir.toString(),
            "assets_index_name" to assetIndexId.orEmpty(),
            "auth_uuid" to uuid,
            "auth_access_token" to options.accessToken,
            "auth_session" to "token:${options.accessToken}:$uuid",
            "user_type" to "msa",
            "version_type" to (version.type ?: "release"),
            "natives_directory" to nativesDir.toString(),
            "launcher_name" to "ABI-Launcher",
            "launcher_version" to "1.0",
            "library_directory" to librariesDir.toString(),
            "classpath" to classpathJars.joinToString(File.pathSeparator),
            "classpath_separator" to File.pathSeparator,
            "clientid" to "",
            "auth_xuid" to "",
            "resolution_width" to (options.width?.toString() ?: "854"),
            "resolution_height" to (options.height?.toString() ?: "480"),
            "quickPlayPath" to "",
            "quickPlaySingleplayer" to "",
            "quickPlayMultiplayer" to "",
            "quickPlayRealms" to "",
        )

        // ---- 5. JVM 参数 ----
        val jvmArgs = mutableListOf<String>()
        jvmArgs += "-Xmx${options.maxMemoryMb}M"
        options.minMemoryMb?.let { jvmArgs += "-Xms${it}M" }
        // 统一按 UTF-8 输出，避免中文玩家名与模组日志乱码（图标/输出都以此为准）
        jvmArgs += listOf("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
        jvmArgs += options.extraJvmArgs

        val jvmFromJson = version.jvmArguments
            .filter { RuleEvaluator.isAllowed(it.rules, context) }
            .flatMap { it.value }

        if (jvmFromJson.isEmpty()) {
            // 老版本没有 arguments.jvm，按约定补上
            jvmArgs += listOf("-Djava.library.path=" + nativesDir.toString(), "-cp", "\${classpath}")
        } else {
            jvmArgs += jvmFromJson
            val hasClasspath = jvmFromJson.any { it.contains("\${classpath}") }
            val hasClasspathFlag = jvmFromJson.any { it == "-cp" || it == "-classpath" }
            if (!hasClasspath || !hasClasspathFlag) {
                if (hasClasspath) {
                    jvmArgs += listOf("-cp", "\${classpath}")
                } else {
                    warnings += "版本 JSON 的 JVM 参数里没有 classpath，已自动补上"
                    jvmArgs += listOf("-cp", "\${classpath}")
                }
            }
        }

        version.loggingFileId?.let { id ->
            val logConfig = assetsDir.resolve("log_configs").resolve(id)
            if (logConfig.isRegularFile()) {
                jvmArgs += "-Dlog4j.configurationFile=$logConfig"
            } else {
                warnings += "缺少日志配置 assets/log_configs/$id（不影响启动）"
            }
        }

        // ---- 6. 游戏参数 ----
        val gameArgsFromJson = if (version.gameArguments.isNotEmpty()) {
            version.gameArguments
                .filter { RuleEvaluator.isAllowed(it.rules, context) }
                .flatMap { it.value }
        } else {
            version.minecraftArguments?.split(Regex("\\s+"))?.filter { it.isNotBlank() }.orEmpty()
        }

        // ---- 7. 替换占位符并拼出最终命令 ----
        val resolvedJvm = jvmArgs.map { substitute(it, variables, warnings) }
        val resolvedGame = gameArgsFromJson.map { substitute(it, variables, warnings) }

        val mainClass = version.mainClass?.takeIf { it.isNotBlank() } ?: run {
            warnings += "版本 JSON 里没有 mainClass，无法启动"
            ""
        }

        val command = buildList {
            add(java.javaExecutable.toString())
            addAll(resolvedJvm)
            add(mainClass)
            addAll(resolvedGame)
        }

        val preflight = PreflightReport(
            missingClientJar = missingClientJar,
            missingLibraries = missingLibraries.distinct(),
            missingAssetIndex = missingAssetIndex,
            missingAssets = missingAssets,
        )

        return LaunchPlan(
            installRoot = installRoot,
            version = version,
            versionDir = versionDir,
            java = java,
            command = command,
            classpath = classpathJars,
            nativesDirectory = nativesDir,
            gameDirectory = gameDir,
            nativesToExtract = nativesToExtract,
            assetIndexPath = assetIndexPath,
            preflight = preflight,
            warnings = warnings,
        )
    }

    /** 与官方一致的离线 UUID：基于 "OfflinePlayer:<名字>" 的 MD5，去掉短横线 */
    fun offlineUuid(playerName: String): String =
        UUID.nameUUIDFromBytes("OfflinePlayer:$playerName".toByteArray(Charsets.UTF_8))
            .toString()
            .replace("-", "")

    /** Maven 坐标 -> libraries 下的相对路径 */
    fun mavenPath(name: String, extraClassifier: String? = null): String? {
        val parts = name.split(':')
        if (parts.size < 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) return null
        val group = parts[0]
        val artifact = parts[1]
        val version = parts[2]
        val classifier = extraClassifier ?: parts.getOrNull(3)
        val fileName = buildString {
            append(artifact).append('-').append(version)
            if (!classifier.isNullOrBlank()) append('-').append(classifier)
            append(".jar")
        }
        return "${group.replace('.', '/')}/$artifact/$version/$fileName"
    }

    /** 旧版纯 natives 库（声明了 natives 但没有主 artifact）不该被猜出一条主 jar 路径 */
    private fun artifactPath(librariesDir: Path, library: LibrarySpec): Path? {
        val relative = library.artifact?.path
            ?: if (library.natives.isNotEmpty()) null else mavenPath(library.name)
        return relative?.let { librariesDir.resolve(it) }
    }

    private fun nativeArtifactPath(librariesDir: Path, library: LibrarySpec, classifier: String): Path? =
        (library.classifiers[classifier]?.path ?: mavenPath(library.name, classifier))
            ?.let { librariesDir.resolve(it) }

    private fun countMissingAssets(assetsDir: Path, indexFile: Path): Int {
        val objects = readJsonObject(indexFile)?.obj("objects") ?: return 0
        var missing = 0
        objects.entrySet().forEach { (_, element) ->
            val hash = element.takeIf { it.isJsonObject }?.asJsonObject?.str("hash") ?: return@forEach
            if (hash.length < 2) return@forEach
            val path = assetsDir.resolve("objects").resolve(hash.substring(0, 2)).resolve(hash)
            if (!Files.isRegularFile(path)) missing++
        }
        return missing
    }

    private fun featuresOf(options: LaunchOptions): Map<String, Boolean> = mapOf(
        "is_demo_user" to options.demoUser,
        "has_custom_resolution" to (options.width != null && options.height != null),
        "has_quick_plays_support" to false,
        "is_quick_play_singleplayer" to false,
        "is_quick_play_multiplayer" to false,
        "is_quick_play_realms" to false,
    )

    private val placeholder = Regex("\\$\\{([A-Za-z0-9_]+)}")

    private fun substitute(value: String, variables: Map<String, String>, warnings: MutableList<String>): String {
        if (!value.contains("\${")) return value
        return placeholder.replace(value) { match ->
            val key = match.groupValues[1]
            val replacement = variables[key]
            if (replacement == null) {
                val warning = "未识别的占位符: \${$key}"
                if (warning !in warnings) warnings += warning
                ""
            } else {
                replacement
            }
        }
    }
}
