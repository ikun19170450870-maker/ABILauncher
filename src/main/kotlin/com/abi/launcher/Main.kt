package com.abi.launcher

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.abi.launcher.crash.CrashAnalyzer
import com.abi.launcher.crash.CrashDiagnosis
import com.abi.launcher.download.DownloadOutcome
import com.abi.launcher.download.DownloadSource
import com.abi.launcher.download.DownloadTarget
import com.abi.launcher.account.AccountStore
import com.abi.launcher.account.GameAccount
import com.abi.launcher.download.GameInstaller
import com.abi.launcher.download.JavaRuntimeRepository
import com.abi.launcher.download.HttpDownloader
import com.abi.launcher.download.IconCache
import com.abi.launcher.download.LoaderOption
import com.abi.launcher.download.ModLoader
import com.abi.launcher.download.ModLoaderInstaller
import com.abi.launcher.download.ModLoaderRepository
import com.abi.launcher.download.ModpackIndexReader
import com.abi.launcher.download.ModpackInstaller
import com.abi.launcher.download.ModrinthProject
import com.abi.launcher.download.ModrinthProjectType
import com.abi.launcher.download.ModrinthRepository
import com.abi.launcher.download.ModrinthVersion
import com.abi.launcher.download.MojangRepository
import com.abi.launcher.download.RemoteVersion
import com.abi.launcher.download.VersionManifest
import com.abi.launcher.launch.GameLauncher
import com.abi.launcher.launch.GameOptions
import com.abi.launcher.launch.GameProcess
import com.abi.launcher.launch.JavaRuntime
import com.abi.launcher.launch.JavaRuntimeLocator
import com.abi.launcher.launch.LaunchCommandBuilder
import com.abi.launcher.launch.LaunchOptions
import com.abi.launcher.launch.VersionSpec
import com.abi.launcher.launch.VersionSpecReader
import com.abi.launcher.settings.GlobalSettings
import com.abi.launcher.settings.SettingsStore
import com.abi.launcher.settings.VersionSettings
import com.abi.launcher.settings.resolveIsolation
import com.abi.launcher.ui.Backdrop
import com.abi.launcher.ui.FileCategory
import com.abi.launcher.ui.VersionFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.io.path.isDirectory

fun main() {
    // 让「选择文件夹」用上 Windows 原生风格的对话框
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }

    application {
        // 初始窗口：按屏幕大小定，并放在桌面正中间
        val screenDensity = androidx.compose.ui.platform.LocalDensity.current.density
        val initialSize = remember(screenDensity) {
            val screen = java.awt.Toolkit.getDefaultToolkit().screenSize
            androidx.compose.ui.unit.DpSize(
                width = minOf(1200f, screen.width / screenDensity * 0.94f).dp,
                height = minOf(900f, screen.height / screenDensity * 0.9f).dp,
            )
        }
        val windowState = rememberWindowState(
            size = initialSize,
            position = androidx.compose.ui.window.WindowPosition(Alignment.Center),
        )
        // 缩放用「窗口像素尺寸」算：用 dp 会形成 密度→尺寸→密度 的自激，页面就会抖
        var windowPx by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }

        Window(
            onCloseRequest = ::exitApplication,
            title = "ABI Launcher",
            state = windowState,
        ) {
            LaunchedEffect(Unit) {
                runCatching { window.minimumSize = java.awt.Dimension(880, 620) }
                // 窗口/任务栏图标（resources/icon.png）
                runCatching {
                    javaClass.getResourceAsStream("/icon.png")?.use { stream ->
                        javax.imageio.ImageIO.read(stream)?.let { window.iconImage = it }
                    }
                }
                windowPx = androidx.compose.ui.unit.IntSize(window.width, window.height)
                val listener = object : java.awt.event.ComponentAdapter() {
                    override fun componentResized(event: java.awt.event.ComponentEvent?) {
                        if (window.width > 0 && window.height > 0) {
                            windowPx = androidx.compose.ui.unit.IntSize(window.width, window.height)
                        }
                    }
                }
                window.addComponentListener(listener)
            }
            // 「锁定窗口大小」：关掉可调整大小，Windows 会把最大化按钮变灰，也就无法全屏
            LaunchedEffect(windowLockedSetting.value) {
                runCatching {
                    val locked = windowLockedSetting.value
                    window.isResizable = !locked
                    if (locked) {
                        window.maximizedBounds = java.awt.Rectangle(window.x, window.y, window.width, window.height)
                    } else {
                        window.maximizedBounds = null
                    }
                }
            }
            // 拖入文件：把文件拖到窗口里，自动复制进「版本设置」当前选的那一类目录
            LaunchedEffect(Unit) {
                runCatching {
                    val dropTarget = java.awt.dnd.DropTarget()
                    dropTarget.addDropTargetListener(object : java.awt.dnd.DropTargetAdapter() {
                        override fun drop(event: java.awt.dnd.DropTargetDropEvent) {
                            runCatching {
                                event.acceptDrop(java.awt.dnd.DnDConstants.ACTION_COPY)
                                val data = event.transferable
                                    .getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor)
                                @Suppress("UNCHECKED_CAST")
                                val dropped = (data as List<java.io.File>).map { it.toPath() }
                                val current = activeDropTarget.value
                                if (current == null) {
                                    dropStatus.value = "先在「版本设置 → 文件管理」里选一个类别，再把文件拖进来"
                                } else if (dropped.isNotEmpty()) {
                                    val added = VersionFiles.import(current.first, current.second, dropped)
                                    filesRefresh.value++
                                    dropStatus.value =
                                        "已拖入 ${added.size} 个文件到「${current.second.label}」" +
                                            if (added.isEmpty()) "（没有可用的文件？）" else "：${added.joinToString("、")}"
                                }
                                event.dropComplete(true)
                            }.onFailure { runCatching { event.dropComplete(false) } }
                        }
                    })
                    window.dropTarget = dropTarget
                }
            }
            MaterialTheme(
                colorScheme = if (themeDarkSetting.value) {
                    androidx.compose.material3.darkColorScheme()
                } else {
                    androidx.compose.material3.lightColorScheme()
                },
            ) {
                // 整体缩放：改 LocalDensity，让所有 dp/sp 一起等比缩放（布局真的重排，而不是裁掉内容）
                val baseDensity = androidx.compose.ui.platform.LocalDensity.current
                val scale = uiScaleFactor(
                    setting = uiScaleSetting.value,
                    windowPx = windowPx,
                    baseDensity = baseDensity.density,
                )
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(
                        density = baseDensity.density * scale,
                        fontScale = baseDensity.fontScale,
                    ),
                ) {
                    Surface(
                        Modifier.fillMaxSize(),
                        color = if (backgroundPathSetting.value.isBlank()) {
                            MaterialTheme.colorScheme.background
                        } else {
                            androidx.compose.ui.graphics.Color.Transparent
                        },
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            BackgroundLayer()
                            LauncherScreen()
                        }
                    }
                }
            }
        }
    }
}

/** 界面缩放值（全局，main() 里也要读，所以放在外面） */
private val uiScaleSetting = mutableStateOf("auto")

/** 是否锁定窗口大小（锁定后无法最大化/全屏） */
private val windowLockedSetting = mutableStateOf(true)

/** 黑夜模式 */
private val themeDarkSetting = mutableStateOf(false)

/** 自定义背景路径（空 = 不用） */
private val backgroundPathSetting = mutableStateOf("")

/** 当前接受「拖入文件」的目标（版本设置面板打开时才设置） */
private val activeDropTarget = mutableStateOf<Pair<Path, FileCategory>?>(null)

/** 文件列表刷新信号（拖入/删除后 +1） */
private val filesRefresh = mutableStateOf(0)

/** 拖入/添加/删除后的提示文字 */
private val dropStatus = mutableStateOf<String?>(null)

/**
 * 算实际缩放系数。
 *
 * 两个关键点（上一版就是在这里抖的）：
 *  1. 用**像素尺寸 + 基础密度**算 —— 用被缩放后的 dp 会形成「密度 → 尺寸 → 密度」自激循环，页面不停抖；
 *  2. 结果**量化成 5% 一档**，拖动窗口时只在跨档时变一次，不会连续微调。
 */
private fun uiScaleFactor(
    setting: String,
    windowPx: androidx.compose.ui.unit.IntSize,
    baseDensity: Float,
): Float {
    if (setting == "auto") {
        if (windowPx.width <= 0 || windowPx.height <= 0 || baseDensity <= 0f) return 1f
        val widthDp = windowPx.width / baseDensity
        val heightDp = windowPx.height / baseDensity
        val raw = minOf(widthDp / 1200f, heightDp / 900f).coerceIn(0.62f, 1f)
        return (Math.floor(raw / 0.05) * 0.05).toFloat().coerceAtLeast(0.6f)
    }
    return setting.toFloatOrNull()?.coerceIn(0.6f, 1.5f) ?: 1f
}

private enum class Screen { Launch, VersionPicker, Download, Settings }

/** 下载页内部的两块 */
private enum class DownloadTab(val label: String) {
    Version("安装版本"),
    Mods("模组"),
}

/**
 * 「模组」块的状态。
 *
 * 放在这里（而不是模组块自己的 remember 里），这样在「安装版本 / 模组」之间来回切、
 * 甚至切到别的页再回来，搜索关键词、第几页、结果都还在。
 * 用 MutableState 字段暴露，界面里可以 `var page by store.page` 直接绑定。
 */
private class ModsState {
    val keyword = mutableStateOf("")
    val gameVersion = mutableStateOf("")
    /** 模组 / 光影 / 资源包 / 整合包 */
    val projectType = mutableStateOf(ModrinthProjectType.Mod)
    val loader = mutableStateOf(ModLoader.Fabric)
    val loaderInitialized = mutableStateOf(false)
    val loading = mutableStateOf(false)
    val results = mutableStateOf<List<ModrinthProject>>(emptyList())
    val totalHits = mutableStateOf(0)
    val page = mutableStateOf(0)
    val pageSize = mutableStateOf(20)
    val selectedProject = mutableStateOf<ModrinthProject?>(null)
    val versions = mutableStateOf<List<ModrinthVersion>>(emptyList())
    val loadingVersions = mutableStateOf(false)
    val busy = mutableStateOf(false)
    val progress = mutableStateOf(0f)
    val progressText = mutableStateOf("")
    val modCount = mutableStateOf(0)
    val icons = mutableStateMapOf<String, ImageBitmap>()
}

/** 所选版本的 Java 检测结果 */
private data class JavaStatus(
    val required: Int?,
    val component: String?,
    val chosen: JavaRuntime?,
) {
    val exact: Boolean get() = required == null || chosen?.major == required

    /** 缺失或版本不匹配，都可以靠下载官方运行时解决 */
    val needsDownload: Boolean get() = required != null && component != null && chosen?.major != required

    val text: String
        get() = when {
            required == null -> "该版本未标注 Java 要求，将使用 ${chosen?.label ?: "本机可用的 Java"}"
            chosen == null -> "本机没有找到 Java $required"
            chosen.major == required -> "Java $required 已就绪：${chosen.javaExecutable}"
            else -> "需要 Java $required，本机只有 Java ${chosen.major}（不精确匹配）"
        }
}

/** 各页面共用的一份状态 */
private class LauncherState {
    /** 启动器自己的游戏目录（%APPDATA%\ABI\minecraft），下载也下到这里 */
    val gameRoot: Path = LauncherPaths.ensureGameRoot()

    var rootInput by mutableStateOf(gameRoot.toString())
    var installation by mutableStateOf<MinecraftInstallation?>(null)
    var selectedVersion by mutableStateOf<MinecraftVersion?>(null)
    var reading by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    var folderExpanded by mutableStateOf(false)
    /** 账号列表与当前账号 */
    val accounts = mutableStateListOf<GameAccount>()
    var currentAccount by mutableStateOf<GameAccount?>(null)
    var memoryInput by mutableStateOf("2048")
    /** 启动前写入的游戏语言（options.txt 的 lang 行） */
    var gameLanguage by mutableStateOf(GameOptions.ChineseSimplified)
    /** 版本隔离（全局开关，单个版本可以覆盖） */
    var versionIsolation by mutableStateOf(false)
    /** 每个版本的设置（版本 id → 设置） */
    val versionSettings = mutableStateMapOf<String, VersionSettings>()
    /** 正在编辑设置的版本；null 表示面板收着 */
    var versionSettingsFor by mutableStateOf<MinecraftVersion?>(null)
    /** 安装时给版本起的名字（留空就用默认 id） */
    var installName by mutableStateOf("")
    /** 出错了要弹的窗口（游戏崩溃 / 启动失败 / 安装失败） */
    var crash by mutableStateOf<CrashDiagnosis?>(null)
    /** 最近一次导出的报告路径 */
    var crashExportPath by mutableStateOf<String?>(null)
    var status by mutableStateOf("就绪")
    var launching by mutableStateOf(false)
    var gameProcess by mutableStateOf<GameProcess?>(null)
    var javaRuntimes by mutableStateOf<List<JavaRuntime>>(emptyList())
    /** 缺少所需 Java 时自动下载运行时 */
    var autoDownloadJava by mutableStateOf(true)
    /** 下载源：下载页与 Java 运行时下载共用 */
    var downloadSource by mutableStateOf(DownloadSource.Bmclapi)
    /** 所选版本的 Java 检测结果 */
    var javaStatus by mutableStateOf<JavaStatus?>(null)
    /** 模组块的状态（跨页面保留） */
    val mods = ModsState()

    val logs = mutableStateListOf<String>()

    val versions: List<MinecraftVersion> get() = installation?.versions.orEmpty()
    val gameDirectory: String get() = installation?.root?.toString() ?: rootInput
}

@Composable
private fun LauncherScreen() {
    val scope = rememberCoroutineScope()
    val state = remember { LauncherState() }
    /** 微软正版登录状态（账号列表 / 当前账号 / 自动续期），数据在 msa-accounts.json */
    val msaLogin = com.abi.launcher.account.rememberMsaLoginState(LauncherPaths.dataRoot)
    var screen by remember { mutableStateOf(Screen.Launch) }

    fun appendLog(line: String) {
        SwingUtilities.invokeLater {
            state.logs.add(line)
            if (state.logs.size > 3000) state.logs.removeRange(0, state.logs.size - 3000)
        }
    }

    fun gameRootOf(): Path = state.installation?.root ?: state.gameRoot

    /** 检测所选版本需要的 Java 本机是否具备 */
    fun refreshJavaStatus() {
        val version = state.selectedVersion
        if (version == null) {
            state.javaStatus = null
            return
        }
        scope.launch {
            state.javaStatus = withContext(Dispatchers.IO) {
                runCatching {
                    val root = gameRootOf()
                    val spec = VersionSpecReader.resolve(version.jsonPath, root)
                    val (chosen, _) = JavaRuntimeRepository.inspect(spec.javaMajorVersion, root.resolve("runtime"))
                    JavaStatus(spec.javaMajorVersion, spec.javaComponent, chosen)
                }.getOrNull()
            }
        }
    }

    /** 保证该版本需要的 Java 可用：缺了或版本不对，就下载官方运行时 */
    suspend fun ensureJava(spec: VersionSpec, gameRoot: Path): JavaRuntime? {
        val required = spec.javaMajorVersion
        val component = spec.javaComponent
        val runtimeRoot = gameRoot.resolve("runtime")

        var chosen = JavaRuntimeLocator.findFor(required, listOf(runtimeRoot))
        if (required == null || chosen?.major == required) return chosen

        if (component == null) {
            appendLog("[Java] 该版本没有标注运行时组件，无法自动下载，将退用 ${chosen?.label ?: "本机 Java"}")
            return chosen
        }
        if (!state.autoDownloadJava) {
            appendLog("[Java] 需要 Java $required，本机只有 Java ${chosen?.major ?: "无"}（自动下载已关闭）")
            return chosen
        }

        appendLog("[Java] 本机缺少 Java $required，开始自动下载官方运行时：$component")
        state.status = "正在下载 Java $required"
        val result = JavaRuntimeRepository.ensureRuntime(
            component = component,
            expectedMajor = required,
            gameRoot = gameRoot,
            source = state.downloadSource,
            onLog = { appendLog(it) },
            onProgress = { done, total, current ->
                SwingUtilities.invokeLater {
                    state.status = "下载 Java $done/$total"
                    if (done == total || done % 20 == 0) appendLog("[Java] $done / $total　$current")
                }
            },
        )
        appendLog("[Java] ${result.message}")

        chosen = JavaRuntimeLocator.findFor(required, listOf(runtimeRoot))
        if (chosen?.major != required) {
            appendLog("[Java] 警告：自动安装后仍未找到精确匹配的 Java $required，将退用 ${chosen?.label ?: "无"}")
        }
        refreshJavaStatus()
        return chosen
    }

    /** 界面按钮：手动下载/修复所选版本需要的 Java */
    fun downloadJavaForSelection() {
        val version = state.selectedVersion ?: return
        scope.launch {
            val spec = withContext(Dispatchers.IO) {
                runCatching { VersionSpecReader.resolve(version.jsonPath, gameRootOf()) }.getOrNull()
            } ?: return@launch
            ensureJava(spec, gameRootOf())
        }
    }

    // ---------------------------------------------------------------- 账号

    /** 这个版本实际会用的游戏目录（考虑版本隔离） */
    fun effectiveGameDirectory(): Path {
        val root = state.installation?.root ?: state.gameRoot
        val version = state.selectedVersion ?: return root
        return if (resolveIsolation(state.versionIsolation, state.versionSettings[version.id])) {
            version.jsonPath.parent
        } else {
            root
        }
    }

    /** 弹出报错窗口：按实际日志判断原因 */
    fun raiseCrash(exitCode: Int?) {
        val diagnosis = CrashAnalyzer.analyze(
            exitCode = exitCode,
            logLines = state.logs.toList(),
            modCount = CrashAnalyzer.listMods(effectiveGameDirectory())?.size ?: 0,
        )
        state.crashExportPath = null
        state.crash = diagnosis
        appendLog("[崩溃] ${diagnosis.title}：${diagnosis.summary}")
    }

    /** 导出可以直接丢给 AI 的报告 */
    fun exportCrash(diagnosis: CrashDiagnosis) {
        val gameDir = effectiveGameDirectory()
        val info = mapOf(
            "版本" to (state.selectedVersion?.id ?: "未知"),
            "游戏根目录" to (state.installation?.root?.toString() ?: state.gameRoot.toString()),
            "实际游戏目录" to gameDir.toString(),
            "版本隔离" to if (resolveIsolation(state.versionIsolation, state.selectedVersion?.let { state.versionSettings[it.id] })) "开" else "关",
            "内存" to state.memoryInput + "MB",
            "Java 运行时" to state.javaRuntimes.joinToString(" | ") { it.label },
        )
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    CrashAnalyzer.export(
                        directory = LauncherPaths.dataRoot.resolve("崩溃报告"),
                        diagnosis = diagnosis,
                        info = info,
                        logLines = state.logs.toList(),
                        gameDirectory = gameDir,
                    )
                }
            }
            result
                .onSuccess {
                    state.crashExportPath = it.toString()
                    appendLog("[崩溃] 报告已导出：$it")
                    runCatching { openInExplorer(it.parent) }
                }
                .onFailure { appendLog("[崩溃] 导出失败：${it.message}") }
        }
    }

    /** 把诊断结论复制到剪贴板 */
    fun copyCrash(diagnosis: CrashDiagnosis) {
        val text = buildString {
            appendLine(diagnosis.title)
            appendLine(diagnosis.summary)
            if (diagnosis.causes.isNotEmpty()) {
                appendLine("可能原因：")
                diagnosis.causes.forEach { appendLine("  - $it") }
            }
            if (diagnosis.suggestions.isNotEmpty()) {
                appendLine("建议：")
                diagnosis.suggestions.forEach { appendLine("  - $it") }
            }
            appendLine()
            appendLine(diagnosis.footer)
        }
        runCatching {
            java.awt.Toolkit.getDefaultToolkit().systemClipboard
                .setContents(java.awt.datatransfer.StringSelection(text), null)
        }
        appendLog("[崩溃] 诊断已复制到剪贴板")
    }

    fun persistAccounts() {
        val snapshot = state.accounts.toList()
        scope.launch { withContext(Dispatchers.IO) { runCatching { AccountStore.save(snapshot) } } }
    }

    /** 全局设置落盘（%APPDATA%\ABI\settings.json） */
    fun persistSettings() {
        val snapshot = GlobalSettings(
            versionIsolation = state.versionIsolation,
            autoDownloadJava = state.autoDownloadJava,
            gameLanguage = state.gameLanguage,
            memoryMb = state.memoryInput,
            uiScale = uiScaleSetting.value,
            windowLocked = windowLockedSetting.value,
            themeDark = themeDarkSetting.value,
            backgroundPath = backgroundPathSetting.value,
        )
        scope.launch { withContext(Dispatchers.IO) { runCatching { SettingsStore.save(snapshot) } } }
    }

    /** 单版本设置落盘（<游戏目录>\abi-versions.json，跟着游戏目录走） */
    fun persistVersionSettings() {
        val root = state.installation?.root ?: state.gameRoot
        val snapshot = state.versionSettings.toMap()
        scope.launch { withContext(Dispatchers.IO) { runCatching { SettingsStore.saveVersions(root, snapshot) } } }
    }

    fun selectAccount(account: GameAccount) {
        state.currentAccount = account
        appendLog("[账号] 已切换到 ${account.displayName}（UUID ${account.uuid}）")
    }

    fun addOfflineAccount(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val account = AccountStore.offline(trimmed)
        if (state.accounts.none { it.uuid == account.uuid }) {
            state.accounts.add(account)
            persistAccounts()
            appendLog("[账号] 已添加离线账号 $trimmed（UUID ${account.uuid}）")
        } else {
            appendLog("[账号] 已存在同名账号 $trimmed")
        }
        state.currentAccount = state.accounts.first { it.uuid == account.uuid }
    }

    fun removeAccount(account: GameAccount) {
        if (state.accounts.size <= 1) {
            appendLog("[账号] 至少保留一个账号")
            return
        }
        state.accounts.remove(account)
        if (state.currentAccount?.uuid == account.uuid) state.currentAccount = state.accounts.firstOrNull()
        persistAccounts()
        appendLog("[账号] 已删除 ${account.displayName}")
    }

    /** 读取该目录下的版本（只看 <目录>/versions，不向子文件夹递归） */
    fun openRoot(target: Path) {
        state.reading = true
        state.message = null
        scope.launch {
            // 单版本设置跟着游戏目录走，读目录时一起读进来
            val savedVersionSettings = withContext(Dispatchers.IO) {
                runCatching { SettingsStore.loadVersions(target) }.getOrDefault(emptyMap())
            }
            val outcome = withContext(Dispatchers.IO) { runCatching { MinecraftScanner.openGameRoot(target) } }
            outcome
                .onSuccess { result ->
                    val found = result.installations.firstOrNull()
                    state.installation = found
                    state.selectedVersion = found?.versions?.let { pickDefaultVersion(it) }
                    state.versionSettings.clear()
                    state.versionSettings.putAll(savedVersionSettings)
                    state.message = when {
                        result.skipped -> "目录不存在或不是目录：${result.root}"
                        found != null -> null
                        LauncherPaths.looksLikeGameRoot(target) ->
                            "这个游戏目录里还没有安装任何版本，去顶部的「下载」页装一个吧。"
                        else ->
                            "这个目录下没有 versions 文件夹。请选择游戏根目录（通常是 .minecraft 这种，里面直接放着 versions 文件夹）。"
                    }
                    found?.let { appendLog("[启动器] 已读取 ${it.root}，共 ${it.versions.size} 个版本") }
                }
                .onFailure { state.message = "读取失败：${it.message ?: it::class.simpleName}" }
            state.reading = false
        }
    }

    fun launchSelected() {
        val root = state.installation ?: return
        val version = state.selectedVersion ?: return
        if (state.launching) {
            appendLog("[启动器] 已经有一个游戏在运行了")
            return
        }
        state.launching = true
        state.status = "正在准备…"
        appendLog("[启动器] 准备启动 ${version.id} …")

        scope.launch {
            val prepared = withContext(Dispatchers.IO) {
                runCatching {
                    val spec = VersionSpecReader.resolve(version.jsonPath, root.root)
                    val java = ensureJava(spec, root.root)
                        ?: error("没有找到可用的 Java（该版本需要 Java ${spec.javaMajorVersion ?: "未知"}）")

                    // 版本隔离 + 单版本设置
                    val perVersion = state.versionSettings[version.id]
                    val isolated = resolveIsolation(state.versionIsolation, perVersion)
                    val gameDirectory = if (isolated) version.jsonPath.parent else null
                    val effectiveGameDir = gameDirectory ?: root.root
                    val customJava = perVersion?.javaPath
                        ?.takeIf { it.isNotBlank() }
                        ?.let { Path.of(it.trim()) }
                        ?.takeIf { Files.isRegularFile(it) }
                    val effectiveJava = customJava?.let { JavaRuntime(it, java.major, "版本设置指定") } ?: java
                    val memory = perVersion?.memoryMb?.takeIf { it.isNotBlank() } ?: state.memoryInput
                    if (isolated) {
                        appendLog("[启动器] 版本隔离已开启，游戏目录：${version.jsonPath.parent}")
                    }
                    customJava?.let { appendLog("[启动器] 使用该版本指定的 Java：$it") }

                    // 有正版账号就用正版（过期自动续期），否则用离线账号
                    val msaAccount = msaLogin.current?.let { msa ->
                        runCatching { com.abi.launcher.account.MsaLogin.ensureFresh(msa) }
                            .onSuccess { fresh -> msaLogin.current = fresh }
                            .onFailure { appendLog("[账号] 正版续期失败：${it.message}（改用离线账号启动）") }
                            .getOrNull()
                    }
                    val account = state.currentAccount
                    val options = LaunchOptions(
                        playerName = msaAccount?.name?.takeIf { it.isNotBlank() }
                            ?: account?.name?.takeIf { it.isNotBlank() } ?: "Steve",
                        maxMemoryMb = memory.trim().toIntOrNull()?.coerceIn(512, 65536) ?: 2048,
                        uuid = msaAccount?.uuid?.takeIf { it.isNotBlank() }
                            ?: account?.uuid?.takeIf { it.isNotBlank() },
                        accessToken = msaAccount?.accessToken?.takeIf { it.isNotBlank() }
                            ?: account?.accessToken?.takeIf { it.isNotBlank() } ?: "0",
                        gameDirectory = gameDirectory,
                    )
                    // 新目录里没有 options.txt 时 Minecraft 会默认英文，这里按设置写一下
                    if (GameOptions.applyLanguage(effectiveGameDir, state.gameLanguage)) {
                        appendLog("[启动器] 已写入游戏语言：${state.gameLanguage}")
                    }

                    val plan = LaunchCommandBuilder.plan(spec, version.jsonPath.parent, root.root, effectiveJava, options)
                    Triple(spec, effectiveJava, plan)
                }
            }

            prepared.onSuccess { (spec, java, plan) ->
                val requiredJava = spec.javaMajorVersion?.let { "Java $it" } ?: "未标注 Java"
                appendLog("[启动器] ${spec.id}：需要 $requiredJava，选用 ${java.label}")
                plan.warnings.forEach { appendLog("[启动器] 警告: $it") }

                if (!plan.isReady) {
                    appendLog("[启动器] 无法启动：${plan.preflight.summary()}")
                    state.status = "文件不完整"
                    state.launching = false
                    return@onSuccess
                }

                appendLog("[启动器] 预检通过，classpath ${plan.classpath.size} 项")
                state.status = "运行中"

                val started = withContext(Dispatchers.IO) {
                    runCatching {
                        GameLauncher.launch(
                            plan,
                            onOutput = { line -> appendLog(line) },
                            onExit = { code ->
                                appendLog("[启动器] 游戏进程结束，退出码 $code")
                                SwingUtilities.invokeLater {
                                    state.status = if (code == 0) "已退出" else "异常退出（代码 $code）"
                                    state.launching = false
                                    state.gameProcess = null
                                }
                                if (code != 0) {
                                    SwingUtilities.invokeLater { raiseCrash(code) }
                                }
                            },
                        )
                    }
                }

                started
                    .onSuccess { state.gameProcess = it }
                    .onFailure {
                        appendLog("[启动器] 启动失败: ${it.message}")
                        state.status = "启动失败"
                        state.launching = false
                        raiseCrash(null)
                    }
            }.onFailure {
                appendLog("[启动器] 准备失败: ${it.message}")
                state.status = "准备失败"
                state.launching = false
                raiseCrash(null)
            }
        }
    }

    LaunchedEffect(Unit) {
        state.javaRuntimes = withContext(Dispatchers.IO) { JavaRuntimeLocator.all() }

        // 便携模式下，第一次双击 exe 就会在它旁边建出 .minecraft（游戏目录结构）
        withContext(Dispatchers.IO) { runCatching { LauncherPaths.ensureGameRoot() } }
        val loaded = withContext(Dispatchers.IO) { runCatching { AccountStore.load() }.getOrDefault(emptyList()) }
        val saved = withContext(Dispatchers.IO) { runCatching { SettingsStore.load() }.getOrElse { GlobalSettings() } }
        state.versionIsolation = saved.versionIsolation
        state.autoDownloadJava = saved.autoDownloadJava
        state.gameLanguage = saved.gameLanguage
        state.memoryInput = saved.memoryMb
        uiScaleSetting.value = saved.uiScale
        windowLockedSetting.value = saved.windowLocked
        themeDarkSetting.value = saved.themeDark
        backgroundPathSetting.value = saved.backgroundPath
        if (loaded.isEmpty()) {
            val default = AccountStore.offline("Steve")
            state.accounts.add(default)
            state.currentAccount = default
            withContext(Dispatchers.IO) { runCatching { AccountStore.save(state.accounts.toList()) } }
        } else {
            state.accounts.addAll(loaded)
            state.currentAccount = loaded.first()
        }

        openRoot(state.gameRoot)
    }

    LaunchedEffect(state.selectedVersion?.jsonPath, state.installation?.root) {
        refreshJavaStatus()
    }

    Column(Modifier.fillMaxSize()) {
        // ================= 报错弹窗 =================
        state.crash?.let { diagnosis ->
            DialogWindow(
                onCloseRequest = { state.crash = null },
                title = "出错了 · ${diagnosis.title}",
                state = rememberDialogState(width = 900.dp, height = 660.dp),
            ) {
                MaterialTheme {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Column(
                            Modifier.fillMaxSize().padding(22.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                diagnosis.title,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(diagnosis.summary, style = MaterialTheme.typography.bodyLarge)

                            if (diagnosis.causes.isNotEmpty()) {
                                Text("可能原因", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                diagnosis.causes.forEach { Text("· $it", style = MaterialTheme.typography.bodyMedium) }
                            }
                            if (diagnosis.suggestions.isNotEmpty()) {
                                Text("可以试试", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                diagnosis.suggestions.forEach { Text("· $it", style = MaterialTheme.typography.bodyMedium) }
                            }

                            Text("日志里的线索", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            if (diagnosis.highlights.isEmpty()) {
                                Text(
                                    "（没抓到明显的关键行，直接导出日志看吧）",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.weight(1f))
                            } else {
                                LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                                    items(diagnosis.highlights) { line ->
                                        Text(
                                            line,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }

                            state.crashExportPath?.let {
                                Text(
                                    "已导出：$it",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }

                            // ↓↓↓ 最后一句必须是这个（用户要求逐字保留）
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Text(
                                    diagnosis.footer,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(12.dp),
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = { exportCrash(diagnosis) },
                                    modifier = Modifier.height(46.dp).width(150.dp),
                                ) { Text("导出日志", fontWeight = FontWeight.Bold) }
                                OutlinedButton(
                                    onClick = { copyCrash(diagnosis) },
                                    modifier = Modifier.height(46.dp),
                                ) { Text("复制诊断") }
                                OutlinedButton(
                                    onClick = { state.crashExportPath?.let { path -> openInExplorer(Path.of(path).parent) } },
                                    enabled = state.crashExportPath != null,
                                    modifier = Modifier.height(46.dp),
                                ) { Text("打开报告目录") }
                                Spacer(Modifier.weight(1f))
                                OutlinedButton(
                                    onClick = { state.crash = null },
                                    modifier = Modifier.height(46.dp),
                                ) { Text("关闭") }
                            }
                        }
                    }
                }
            }
        }

        // ================= 顶部导航栏 =================
        val tabIndex = when (screen) {
            Screen.Download -> 1
            Screen.Settings -> 2
            else -> 0
        }
        TabRow(selectedTabIndex = tabIndex) {
            Tab(
                selected = tabIndex == 0,
                onClick = { screen = Screen.Launch },
                text = { Text("开始", style = MaterialTheme.typography.titleMedium) },
            )
            Tab(
                selected = tabIndex == 1,
                onClick = { screen = Screen.Download },
                text = { Text("下载", style = MaterialTheme.typography.titleMedium) },
            )
            Tab(
                selected = tabIndex == 2,
                onClick = { screen = Screen.Settings },
                text = { Text("设置", style = MaterialTheme.typography.titleMedium) },
            )
        }

        when (screen) {
            Screen.Launch -> StartPane(
                state = state,
                onLaunch = { launchSelected() },
                msaLogin = msaLogin,
                onStop = {
                    state.gameProcess?.stop()
                    appendLog("[启动器] 已请求结束游戏进程")
                    state.status = "正在结束…"
                },
                onPickVersion = { screen = Screen.VersionPicker },
                onDownloadJava = { downloadJavaForSelection() },
                onToggleAutoDownloadJava = { state.autoDownloadJava = !state.autoDownloadJava },
                onSelectAccount = { selectAccount(it) },
                onAddAccount = { addOfflineAccount(it) },
                onRemoveAccount = { removeAccount(it) },
                onVersionSettingsChanged = { persistVersionSettings() },
            )

            Screen.VersionPicker -> VersionPickerPane(
                state = state,
                onToggleFolder = { state.folderExpanded = !state.folderExpanded },
                onChooseFolder = {
                    chooseDirectory(state.rootInput)?.let {
                        state.rootInput = it.toString()
                        state.folderExpanded = true
                        openRoot(it)
                    }
                },
                onRead = { openRoot(Path.of(state.rootInput.trim())) },
                onSelect = { version ->
                    state.selectedVersion = version
                    appendLog("[启动器] 已选择版本 ${version.id}")
                    screen = Screen.Launch
                },
                onBack = { screen = Screen.Launch },
                onVersionSettingsChanged = { persistVersionSettings() },
            )

            Screen.Download -> DownloadPane(
                state = state,
                onLog = { appendLog(it) },
                onInstalled = { openRoot(state.gameRoot) },
                onSwitchRoot = { openRoot(it) },
                onVersionNamed = { id, name ->
                    state.versionSettings[id] = (state.versionSettings[id] ?: VersionSettings())
                        .copy(displayName = name)
                    persistVersionSettings()
                },
            )

            Screen.Settings -> SettingsPane(
                state = state,
                onLog = { appendLog(it) },
                onSettingsChanged = { persistSettings() },
                onVersionSettingsChanged = { persistVersionSettings() },
                onOpenRoot = { openRoot(it) },
            )
        }
    }
}

// ================================================================ 开始页

@Composable
private fun StartPane(
    state: LauncherState,
    onLaunch: () -> Unit,
    onStop: () -> Unit,
    onPickVersion: () -> Unit,
    onDownloadJava: () -> Unit,
    onToggleAutoDownloadJava: () -> Unit,
    onSelectAccount: (GameAccount) -> Unit,
    onAddAccount: (String) -> Unit,
    onRemoveAccount: (GameAccount) -> Unit,
    onVersionSettingsChanged: () -> Unit,
    msaLogin: com.abi.launcher.account.MsaLoginUiState,
) {    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ABI Launcher", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(16.dp))
            Text(
                if (state.launching) "游戏运行中" else state.status,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "已检测到 ${state.javaRuntimes.size} 个 Java 运行时",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "当前版本",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        state.selectedVersion?.id ?: "还没有选择版本",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    state.selectedVersion?.let { version ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Chip(version.category, categoryColor(version))
                            Text(
                                version.javaMajorVersion?.let { "需要 Java $it" } ?: "Java 未标注",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                if (version.clientJarPresent) "客户端 jar 已就绪" else "缺少客户端 jar",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (version.clientJarPresent) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            )
                        }
                    }
                    Text(
                        "游戏目录：${state.gameDirectory}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            "Java：${state.javaStatus?.text ?: "检测中…"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (state.javaStatus?.exact == false) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        if (state.javaStatus?.needsDownload == true) {
                            OutlinedButton(
                                onClick = onDownloadJava,
                                enabled = !state.launching,
                                modifier = Modifier.height(38.dp),
                            ) { Text("下载所需 Java") }
                        }
                    }
                }
                Button(onClick = onPickVersion, modifier = Modifier.height(50.dp).width(160.dp)) {
                    Text("选择版本 ▸")
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("启动设置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("账号", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    state.accounts.forEach { account ->
                        if (account.uuid == state.currentAccount?.uuid) {
                            Button(onClick = { onSelectAccount(account) }, modifier = Modifier.height(42.dp)) {
                                Text(account.displayName)
                            }
                        } else {
                            OutlinedButton(onClick = { onSelectAccount(account) }, modifier = Modifier.height(42.dp)) {
                                Text(account.displayName)
                            }
                        }
                    }
                }
                var newAccountName by remember { mutableStateOf("") }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = newAccountName,
                        onValueChange = { newAccountName = it },
                        label = { Text("新增离线账号名字") },
                        singleLine = true,
                        modifier = Modifier.width(240.dp),
                    )
                    Button(
                        onClick = {
                            onAddAccount(newAccountName)
                            newAccountName = ""
                        },
                        enabled = newAccountName.isNotBlank(),
                        modifier = Modifier.height(46.dp),
                    ) { Text("添加离线账号") }
                    state.currentAccount?.let { account ->
                        OutlinedButton(
                            onClick = { onRemoveAccount(account) },
                            enabled = state.accounts.size > 1,
                            modifier = Modifier.height(46.dp),
                        ) { Text("删除当前账号") }
                    }
                }
                Text(
                    "当前 UUID：${state.currentAccount?.uuid ?: "-"}　·　离线账号只能单机（正版登录见下面）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // ---------- 微软正版登录（设备码）----------
                Spacer(Modifier.height(8.dp))
                com.abi.launcher.account.MsaLoginSection(msaLogin)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "当前设置：版本隔离 ${if (state.versionIsolation) "开" else "关"}" +
                            "　·　内存 ${state.memoryInput}MB" +
                            "　·　语言 ${state.gameLanguage}" +
                            "　·　自动下 Java ${if (state.autoDownloadJava) "开" else "关"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = { state.selectedVersion?.let { state.versionSettingsFor = it } },
                        enabled = state.selectedVersion != null,
                        modifier = Modifier.height(40.dp),
                    ) { Text("这个版本的设置") }
                }
                VersionSettingsCard(
                    state = state,
                    onChanged = onVersionSettingsChanged,
                    onClose = { state.versionSettingsFor = null },
                )
                Spacer(Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Button(
                        onClick = onLaunch,
                        enabled = state.installation != null && state.selectedVersion != null && !state.launching,
                        modifier = Modifier.height(56.dp).width(200.dp),
                    ) { Text(if (state.launching) "运行中…" else "启动游戏", fontWeight = FontWeight.Bold) }
                    OutlinedButton(
                        onClick = onStop,
                        enabled = state.launching,
                        modifier = Modifier.height(56.dp).width(140.dp),
                    ) { Text("停止游戏") }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

// ================================================================ 版本选择页

@Composable
private fun VersionPickerPane(
    state: LauncherState,
    onToggleFolder: () -> Unit,
    onChooseFolder: () -> Unit,
    onRead: () -> Unit,
    onSelect: (MinecraftVersion) -> Unit,
    onBack: () -> Unit,
    onVersionSettingsChanged: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack, modifier = Modifier.height(46.dp)) { Text("◂ 返回") }
            Spacer(Modifier.width(18.dp))
            Column {
                Text("选择版本", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    if (state.installation == null) "先在下面指定游戏目录"
                    else "共 ${state.versions.size} 个版本　·　点一个就会选中并返回",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 版本设置面板就挂在这一页，点列表里的「设置」立刻能看到
        VersionSettingsCard(
            state = state,
            onChanged = onVersionSettingsChanged,
            onClose = { state.versionSettingsFor = null },
        )

        CollapsibleCard(
            title = "游戏目录",
            subtitle = state.gameDirectory,
            expanded = state.folderExpanded || state.installation == null,
            onToggle = onToggleFolder,
            contentSpacing = 14.dp,
        ) {
            OutlinedTextField(
                value = state.rootInput,
                onValueChange = { state.rootInput = it },
                label = { Text("游戏根目录（.minecraft）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onChooseFolder,
                    enabled = !state.reading && !state.launching,
                    modifier = Modifier.height(46.dp),
                ) { Text("选择文件夹…") }
                OutlinedButton(
                    onClick = onRead,
                    enabled = !state.reading && !state.launching && state.rootInput.isNotBlank(),
                    modifier = Modifier.height(46.dp),
                ) { Text(if (state.reading) "读取中…" else "重新读取") }
            }
            OutlinedButton(
                onClick = { state.rootInput = state.gameRoot.toString() },
                enabled = !state.reading && !state.launching,
                modifier = Modifier.height(46.dp),
            ) { Text("用启动器自己的目录") }
            state.message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Text(
                "只读取该目录下 versions 文件夹里的版本，不会去子文件夹里找。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (state.versions.isEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("没有可选的版本", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "展开上面的「游戏目录」选择包含 versions 的游戏根目录，或者去顶部「下载」页装一个版本。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.versions, key = { it.jsonPath.toString() }) { version ->
                    VersionCard(
                        version = version,
                        selected = version.id == state.selectedVersion?.id,
                        onSelect = { onSelect(version) },
                        label = state.versionSettings[version.id]?.displayName?.takeIf { it.isNotBlank() } ?: version.id,
                        note = versionNoteOf(state, version),
                        onSettings = { state.versionSettingsFor = version },
                    )
                }
            }
        }
    }
}

// ================================================================ 下载页

@Composable
private fun DownloadPane(
    state: LauncherState,
    onLog: (String) -> Unit,
    onInstalled: () -> Unit,
    onSwitchRoot: (Path) -> Unit,
    onVersionNamed: (String, String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val installer = remember { GameInstaller() }

    var manifest by remember { mutableStateOf<VersionManifest?>(null) }
    var manifestNote by remember { mutableStateOf("还没有获取版本清单") }
    var loading by remember { mutableStateOf(false) }
    var keyword by remember { mutableStateOf("") }
    var releasesOnly by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<RemoteVersion?>(null) }
    var busy by remember { mutableStateOf(false) }
    var downloadTab by remember { mutableStateOf(DownloadTab.Version) }
    var loader by remember { mutableStateOf(ModLoader.Vanilla) }
    var loaderOptions by remember { mutableStateOf<List<LoaderOption>>(emptyList()) }
    var loaderVersion by remember { mutableStateOf<String?>(null) }
    var loadingLoaders by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var progressText by remember { mutableStateOf("") }
    var progressCurrent by remember { mutableStateOf("") }
    var progressDone by remember { mutableStateOf(0) }
    var progressTotal by remember { mutableStateOf(0) }
    var cancelRequested by remember { mutableStateOf(false) }

    fun loadManifest() {
        if (loading) return
        // 调用时才读下载源，避免闭包捕获到切换前的旧值
        val source = state.downloadSource
        loading = true
        manifestNote = "正在从${source.displayName}获取清单…"
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { MojangRepository.fetchManifest(source) } }
            result
                .onSuccess {
                    manifest = it.manifest
                    manifestNote = "${it.message}　·　${MojangRepository.summarize(it.manifest)}"
                    onLog("[下载] ${it.message}")
                }
                .onFailure {
                    manifestNote = "获取清单失败：${it.message}"
                    onLog("[下载] 获取清单失败：${it.message}")
                }
            loading = false
        }
    }

    fun testConnection() {
        if (loading) return
        val source = state.downloadSource
        loading = true
        manifestNote = "正在测试 ${source.displayName} 的连通性…"
        scope.launch {
            val test = withContext(Dispatchers.IO) { MojangRepository.testConnection(source) }
            manifestNote = if (test.ok) {
                "✅ ${test.source.displayName} 连通正常，耗时 ${test.elapsedMs} ms，清单里 ${test.versionCount} 个版本，最新正式版 ${test.latestRelease}"
            } else {
                "❌ ${test.source.displayName} 连接失败（${test.elapsedMs} ms）：${test.message}"
            }
            onLog("[下载] ${manifestNote}")
            if (test.ok) loadManifest()
            loading = false
        }
    }

    /** 查询所选加载器在所选游戏版本下可用的版本列表 */
    fun loadLoaderOptions() {
        val version = selected
        if (version == null) {
            onLog("[加载器] 请先在上面选一个游戏版本")
            return
        }
        if (loader == ModLoader.Vanilla || loadingLoaders) return
        loadingLoaders = true
        onLog("[加载器] 查询 ${loader.displayName} 可用版本（适用于 ${version.id}）…")
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { ModLoaderRepository.listLoaderVersions(loader, version.id, state.downloadSource) }
            }
            result
                .onSuccess { options ->
                    loaderOptions = options
                    loaderVersion = options.firstOrNull()?.version
                    onLog("[加载器] ${loader.displayName} 有 ${options.size} 个可选版本，默认 ${loaderVersion ?: "最新"}")
                }
                .onFailure {
                    loaderOptions = emptyList()
                    loaderVersion = null
                    onLog("[加载器] 获取 ${loader.displayName} 版本列表失败：${it.message}")
                }
            loadingLoaders = false
        }
    }

    fun install(full: Boolean) {
        val version = selected
        if (version == null) {
            onLog("[下载] 请先在上面选一个版本")
            return
        }
        if (busy) return
        busy = true
        cancelRequested = false
        progress = 0f
        val source = state.downloadSource
        val officialUrl = version.url.takeIf { it.isNotBlank() }
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    if (full) {
                        val installedId: String?
                        if (loader == ModLoader.Vanilla) {
                            installer.install(
                                versionId = version.id,
                                officialVersionJsonUrl = officialUrl,
                                gameRoot = state.gameRoot,
                                source = source,
                                onLog = onLog,
                                onProgress = { done, total, current ->
                                    SwingUtilities.invokeLater {
                                        progress = if (total > 0) done.toFloat() / total else 0f
                                        progressDone = done
                                        progressTotal = total
                                        progressCurrent = current
                                        progressText = "$done / $total　$current"
                                    }
                                },
                                cancelled = { cancelRequested },
                            )
                            installedId = version.id
                        } else {
                            val result = ModLoaderInstaller().install(
                                gameVersion = version.id,
                                officialGameJsonUrl = officialUrl,
                                loader = loader,
                                loaderVersion = loaderVersion,
                                gameRoot = state.gameRoot,
                                source = source,
                                onLog = onLog,
                                onProgress = { done, total, current ->
                                    SwingUtilities.invokeLater {
                                        progress = if (total > 0) done.toFloat() / total else 0f
                                        progressDone = done
                                        progressTotal = total
                                        progressCurrent = current
                                        progressText = "$done / $total　$current"
                                    }
                                },
                                cancelled = { cancelRequested },
                            )
                            onLog("[加载器] ${result.message}")
                            if (result.ok) {
                                onLog("[加载器] 装好了，去开始页「选择版本」里选 ${result.versionId}")
                            } else {
                                error(result.message)
                            }
                            installedId = result.versionId
                        }
                        val name = state.installName.trim()
                        if (name.isNotEmpty() && installedId != null) {
                            onVersionNamed(installedId, name)
                            onLog("[下载] 已给它起名「$name」")
                        }
                    } else {
                        val result = installer.downloadClientJarOnly(
                            versionId = version.id,
                            officialVersionJsonUrl = officialUrl,
                            gameRoot = state.gameRoot,
                            source = source,
                            onLog = onLog,
                            onProgress = { done, total, current ->
                                SwingUtilities.invokeLater {
                                    progress = if (total > 0) done.toFloat() / total else 0f
                                    progressText = "已下载 ${done / 1024 / 1024} MB / ${total / 1024 / 1024} MB　$current"
                                }
                            },
                            cancelled = { cancelRequested },
                        )
                        if (result.outcome == DownloadOutcome.Failed) {
                            error(result.message ?: "下载失败")
                        }
                    }
                }
            }
            outcome.onSuccess {
                onLog("[下载] 任务结束")
                onInstalled()
            }.onFailure {
                onLog("[下载] 出错：${it.message}")
            }
            busy = false
            progressText = ""
        }
    }

    LaunchedEffect(Unit) {
        if (manifest == null) loadManifest()
    }
    val all = manifest?.versions.orEmpty()
    val filtered = all.filter { version ->
        (!releasesOnly || version.type == "release") &&
            (keyword.isBlank() || version.id.contains(keyword, ignoreCase = true))
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // ---------- 下载页内部的两个子页签 ----------
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DownloadTab.entries.forEach { tab ->
                if (tab == downloadTab) {
                    Button(onClick = { downloadTab = tab }, modifier = Modifier.height(44.dp)) { Text(tab.label) }
                } else {
                    OutlinedButton(onClick = { downloadTab = tab }, modifier = Modifier.height(44.dp)) { Text(tab.label) }
                }
            }
        }

        if (downloadTab == DownloadTab.Mods) {
            ModsPane(
                state = state,
                onLog = onLog,
                onSwitchRoot = onSwitchRoot,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        } else {
        // ---------- 标题 + 下载源 + 连通性测试 ----------
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("下载游戏", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "下载到启动器自己的目录：${state.gameRoot}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = { openInExplorer(state.gameRoot) }, modifier = Modifier.height(44.dp)) {
                Text("打开下载目录")
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("下载源", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    DownloadSource.entries.forEach { candidate ->
                        if (candidate == state.downloadSource) {
                            Button(
                                onClick = { state.downloadSource = candidate },
                                modifier = Modifier.height(44.dp),
                            ) { Text(candidate.displayName) }
                        } else {
                            OutlinedButton(
                                onClick = {
                                    state.downloadSource = candidate
                                    manifest = null
                                    loadManifest()
                                },
                                enabled = !busy,
                                modifier = Modifier.height(44.dp),
                            ) { Text(candidate.displayName) }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(
                        onClick = { testConnection() },
                        enabled = !loading && !busy,
                        modifier = Modifier.height(44.dp),
                    ) { Text("测试连通性") }
                    OutlinedButton(
                        onClick = { loadManifest() },
                        enabled = !loading && !busy,
                        modifier = Modifier.height(44.dp),
                    ) { Text("刷新清单") }
                }
                Text(
                    if (loading) "请稍候…" else manifestNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ---------- 版本列表 ----------
        Card(Modifier.fillMaxWidth().weight(1f)) {
            Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("可下载的版本", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = keyword,
                        onValueChange = { keyword = it },
                        label = { Text("搜索版本号") },
                        singleLine = true,
                        modifier = Modifier.width(260.dp),
                    )
                    TextButton(onClick = { releasesOnly = !releasesOnly }) {
                        Text(if (releasesOnly) "只看正式版（点击显示全部）" else "显示全部（点击只看正式版）")
                    }
                    Spacer(Modifier.weight(1f))
                    Text("${filtered.size} 个", style = MaterialTheme.typography.bodySmall)
                }
                if (filtered.isEmpty()) {
                    Text(
                        if (all.isEmpty()) "还没有清单数据，点上面的「测试连通性」或「刷新清单」。"
                        else "没有匹配的版本。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(filtered, key = { it.id }) { version ->
                            RemoteVersionRow(
                                version = version,
                                selected = version.id == selected?.id,
                                installed = LauncherPaths.isVersionInstalled(state.gameRoot, version.id),
                                hasJar = LauncherPaths.hasClientJar(state.gameRoot, version.id),
                                onSelect = { selected = version },
                            )
                        }
                    }
                }
            }
        }

        // ---------- 下载动作 + 测试区 ----------
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "已选：${selected?.id ?: "未选择"}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    selected?.let { Chip(it.category, MaterialTheme.colorScheme.primary) }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { install(full = true) },
                        enabled = selected != null && !busy,
                        modifier = Modifier.height(50.dp).width(260.dp),
                    ) {
                        Text(
                            if (loader == ModLoader.Vanilla) {
                                "下载并安装"
                            } else {
                                "下载并安装（${loader.displayName}${loaderVersion?.let { " $it" } ?: ""}）"
                            },
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    OutlinedButton(
                        onClick = { install(full = false) },
                        enabled = selected != null && !busy && loader == ModLoader.Vanilla,
                        modifier = Modifier.height(50.dp),
                    ) { Text("只下载客户端 jar（测试）") }
                    if (busy) {
                        OutlinedButton(
                            onClick = { cancelRequested = true },
                            modifier = Modifier.height(50.dp),
                        ) { Text("取消") }
                    }
                }

                // ---------- 模组加载器 ----------
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("模组加载器", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    ModLoader.entries.forEach { candidate ->
                        if (candidate == loader) {
                            Button(
                                onClick = { loader = candidate },
                                enabled = !busy,
                                modifier = Modifier.height(40.dp),
                            ) { Text(candidate.displayName) }
                        } else {
                            OutlinedButton(
                                onClick = {
                                    loader = candidate
                                    loaderOptions = emptyList()
                                    loaderVersion = null
                                    if (candidate != ModLoader.Vanilla) loadLoaderOptions()
                                },
                                enabled = !busy,
                                modifier = Modifier.height(40.dp),
                            ) { Text(candidate.displayName) }
                        }
                    }
                    if (loader != ModLoader.Vanilla) {
                        OutlinedButton(
                            onClick = { loadLoaderOptions() },
                            enabled = !busy && !loadingLoaders,
                            modifier = Modifier.height(40.dp),
                        ) { Text(if (loadingLoaders) "查询中…" else "重新获取版本列表") }
                    }
                }
                if (loader != ModLoader.Vanilla) {
                    Text(
                        when {
                            loadingLoaders -> "正在获取 ${loader.displayName} 的版本列表…"
                            loaderOptions.isEmpty() -> "还没有版本列表，点「重新获取版本列表」；获取不到时会自动用最新版。"
                            else -> "可选版本：点一行选中（不选就是第一个／最新）"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (loaderOptions.isNotEmpty()) {
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 130.dp)) {
                            items(loaderOptions, key = { it.version }) { option ->
                                Row(
                                    Modifier.fillMaxWidth()
                                        .clickable { loaderVersion = option.version }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(
                                        selected = option.version == loaderVersion,
                                        onClick = { loaderVersion = option.version },
                                    )
                                    Text(option.displayName, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                    Text(
                        if (loader.usesInstaller) {
                            "${loader.displayName} 需要运行官方安装器（会先自动装好原版、必要时自动下载对应的 Java），耗时较长。"
                        } else {
                            "${loader.displayName} 只会在原版基础上追加一个版本 JSON 和依赖库，装完在开始页「选择版本」里选它即可。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = state.installName,
                        onValueChange = { state.installName = it },
                        label = { Text("给这个版本起个名字（可选）") },
                        singleLine = true,
                        modifier = Modifier.width(340.dp),
                    )
                    Text(
                        "装完在版本列表里显示这个名字（版本 id 不变），同版本装多次也能分清",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (busy || progressText.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "安装进度：${phaseLabel(progressCurrent)}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            if (progressTotal > 0) {
                                "${(progress * 100).toInt()}%　（${progressDone} / ${progressTotal}）"
                            } else {
                                "正在准备…"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    ProgressBar(progress)
                    if (progressCurrent.isNotBlank()) {
                        Text(
                            "正在处理：$progressCurrent",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    "「下载并安装」会拉取：版本 JSON、客户端 jar、按系统过滤后的依赖库、资源索引与全部资源文件。" +
                        "已存在且 SHA-1 校验通过的文件会跳过，所以可以随时中断再继续。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        }
    }
}

@Composable
private fun RemoteVersionRow(
    version: RemoteVersion,
    selected: Boolean,
    installed: Boolean,
    hasJar: Boolean,
    onSelect: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().clickable { onSelect() },
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = onSelect)
            Spacer(Modifier.width(8.dp))
            Text(
                version.id,
                modifier = Modifier.width(220.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Chip(version.category, MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Text(
                version.releaseTime.take(10),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            if (installed) {
                Chip(if (hasJar) "已安装" else "只有 JSON", MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}

// ================================================================ 模组页（Modrinth）

/** 模组筛选默认用当前版本：模组版本用父版本号，未选就留空 */
private fun defaultGameVersionOf(state: LauncherState): String {
    val version = state.selectedVersion ?: return ""
    return version.inheritsFrom?.takeIf { it.isNotBlank() } ?: version.id
}

@Composable
private fun ModsPane(
    state: LauncherState,
    onLog: (String) -> Unit,
    onSwitchRoot: (Path) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val downloader = remember { HttpDownloader() }
    val gameDir = state.installation?.root ?: state.gameRoot

    // 状态挂在 LauncherState 上，切换子页签、切到别的页再回来都不会丢
    val store = state.mods
    var keyword by store.keyword
    var gameVersion by store.gameVersion
    var projectType by store.projectType
    var loader by store.loader
    var loaderInitialized by store.loaderInitialized
    var searching by store.loading
    var results by store.results
    var totalHits by store.totalHits
    var page by store.page
    var pageSize by store.pageSize
    var selectedProject by store.selectedProject
    var versions by store.versions
    var loadingVersions by store.loadingVersions
    var busy by store.busy
    var progress by store.progress
    var progressText by store.progressText
    var modCount by store.modCount
    val icons = store.icons

    fun refreshModCount() {
        val directory = ModrinthRepository.directoryFor(gameDir, projectType) ?: return
        scope.launch {
            modCount = withContext(Dispatchers.IO) {
                runCatching {
                    if (!java.nio.file.Files.isDirectory(directory)) {
                        0
                    } else {
                        java.nio.file.Files.newDirectoryStream(directory).use { stream ->
                            stream.count { java.nio.file.Files.isRegularFile(it) }
                        }
                    }
                }.getOrDefault(0)
            }
        }
    }

    fun doSearch(targetPage: Int = 0) {
        if (searching) return
        searching = true
        page = targetPage.coerceAtLeast(0)
        results = emptyList()
        versions = emptyList()
        selectedProject = null
        onLog(
            "[模组] 搜索第 ${page + 1} 页：\"${keyword.ifBlank { "(热门)" }}\"　加载器=${loader.displayName}" +
                "　游戏版本=${gameVersion.ifBlank { "不限" }}　每页 $pageSize",
        )
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    ModrinthRepository.search(
                        query = keyword,
                        loader = loader,
                        gameVersion = gameVersion.trim().ifBlank { null },
                        limit = pageSize,
                        offset = page * pageSize,
                        type = projectType,
                        downloader = downloader,
                    )
                }
            }
            outcome
                .onSuccess { result ->
                    results = result.projects
                    totalHits = result.totalHits
                    onLog("[模组] 第 ${page + 1} 页拿到 ${result.projects.size} 个，共 ${result.totalHits} 个结果")
                }
                .onFailure { onLog("[模组] 搜索失败：${it.message}") }
            searching = false
        }
    }

    fun loadVersions(project: ModrinthProject) {
        selectedProject = project
        loadingVersions = true
        versions = emptyList()
        onLog("[模组] 读取「${project.title}」的版本…")
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    ModrinthRepository.versions(
                        projectIdOrSlug = project.slug.ifBlank { project.id },
                        loader = loader,
                        gameVersion = gameVersion.trim().ifBlank { null },
                        type = projectType,
                        downloader = downloader,
                    )
                }
            }
            outcome
                .onSuccess {
                    versions = it
                    onLog("[模组] 「${project.title}」有 ${it.size} 个匹配版本")
                }
                .onFailure { onLog("[模组] 读取版本失败：${it.message}") }
            loadingVersions = false
        }
    }

    fun downloadMod(project: ModrinthProject, version: ModrinthVersion) {
        val file = version.primaryFile
        if (file == null) {
            onLog("[模组] 这个版本没有可下载文件")
            return
        }
        if (busy) return
        busy = true
        progress = 0f
        onLog("[模组] 下载 ${project.title} ${version.versionNumber} → mods/${file.filename}（${file.size / 1024} KB）")
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    ModrinthRepository.directoryFor(gameDir, projectType)?.let { java.nio.file.Files.createDirectories(it) }
                    downloader.download(
                        ModrinthRepository.downloadTarget(file, gameDir, projectType),
                        onBytes = { bytes ->
                            SwingUtilities.invokeLater {
                                progress = if (file.size > 0) bytes.toFloat() / file.size else 0f
                                progressText = "${bytes / 1024} KB / ${file.size / 1024} KB"
                            }
                        },
                    )
                }
            }
            outcome
                .onSuccess { onLog("[模组] 结果：${it.outcome}${it.message?.let { m -> " — $m" } ?: ""}") }
                .onFailure { onLog("[模组] 下载失败：${it.message}") }
            busy = false
            progressText = ""
            refreshModCount()
        }
    }

    /** 整合包：先下 .mrpack，再装成独立实例，最后把启动器切到那个实例 */
    fun installModpack(project: ModrinthProject, version: ModrinthVersion) {
        val file = version.primaryFile
        if (file == null) {
            onLog("[整合包] 这个版本没有可下载文件")
            return
        }
        if (busy) return
        busy = true
        progress = 0f
        progressText = ""
        onLog("[整合包] ${project.title} ${version.versionNumber}（${file.size / 1024 / 1024} MB）")

        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val packFile = LauncherPaths.cacheRoot.resolve("modpacks").resolve(file.filename)
                    java.nio.file.Files.createDirectories(packFile.parent)
                    val downloaded = downloader.download(
                        DownloadTarget(
                            url = file.url,
                            destination = packFile,
                            sha1 = file.sha1,
                            size = file.size,
                            label = "整合包 ${file.filename}",
                        ),
                        onBytes = { bytes ->
                            SwingUtilities.invokeLater {
                                progressText = "整合包 ${bytes / 1024 / 1024} MB / ${file.size / 1024 / 1024} MB"
                            }
                        },
                    )
                    if (downloaded.outcome == DownloadOutcome.Failed) {
                        error("下载 .mrpack 失败：${downloaded.message ?: "未知错误"}")
                    }
                    val customName = state.installName.trim()
                    ModpackInstaller(downloader).install(
                        mrpack = packFile,
                        source = state.downloadSource,
                        instanceRootOverride = customName.takeIf { it.isNotEmpty() }?.let { name ->
                            LauncherPaths.dataRoot.resolve("instances")
                                .resolve(ModpackIndexReader.instanceDirectoryName(name))
                        },
                        onLog = onLog,
                        onProgress = { done, total, current ->
                            SwingUtilities.invokeLater {
                                progress = if (total > 0) done.toFloat() / total else 0f
                                progressText = "$done / $total　$current"
                            }
                        },
                    )
                }
            }
            outcome
                .onSuccess { summary ->
                    onLog("[整合包] ${summary.message}")
                    summary.instanceRoot?.let { root ->
                        onLog("[整合包] 已把启动器切到这个实例，去开始页选版本启动就行")
                        onSwitchRoot(root)
                    }
                }
                .onFailure { onLog("[整合包] 安装失败：${it.message}") }
            busy = false
            progressText = ""
        }
    }

    LaunchedEffect(projectType) { refreshModCount() }

    LaunchedEffect(Unit) {
        // 第一次进来：按当前选中的版本推断加载器与游戏版本，并自动搜一次
        if (!loaderInitialized) {
            loader = ModrinthRepository.guessLoader(state.selectedVersion?.id.orEmpty()) ?: loader
            if (gameVersion.isBlank()) gameVersion = defaultGameVersionOf(state)
            loaderInitialized = true
        }
        if (results.isEmpty() && !searching) doSearch(0)
        refreshModCount()
    }
    // 搜索出结果后逐个把图标抓下来（内存→磁盘→网络），抓到就画，抓不到留占位符
    LaunchedEffect(results) {
        var failed = 0
        results.mapNotNull { it.iconUrl }.distinct().forEach { url ->
            if (!icons.containsKey(url)) {
                val bitmap = withContext(Dispatchers.IO) { runCatching { IconCache.load(url, downloader) }.getOrNull() }
                if (bitmap != null) icons[url] = bitmap else failed++
            }
        }
        if (failed > 0) onLog("[模组] 有 $failed 个图标没能加载（图标在 cdn.modrinth.com，网络不通时只会显示占位符）")
    }

    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Modrinth 模组", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    ModrinthRepository.directoryFor(gameDir, projectType)
                        ?.let { "下载到：$it（已有 $modCount 个文件）" }
                        ?: "整合包会装到 ${LauncherPaths.dataRoot.resolve("instances")} 下自己的实例目录",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(
                onClick = {
                    val target = ModrinthRepository.directoryFor(gameDir, projectType)
                        ?: LauncherPaths.dataRoot.resolve("instances")
                    openInExplorer(target)
                },
                modifier = Modifier.height(44.dp),
            ) {
                Text(
                    if (projectType == ModrinthProjectType.ModPack) {
                        "打开实例目录"
                    } else {
                        "打开 ${projectType.targetDirectory} 目录"
                    },
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("类型", style = MaterialTheme.typography.bodyMedium)
                    ModrinthProjectType.entries.forEach { type ->
                        if (type == projectType) {
                            Button(
                                onClick = { projectType = type; doSearch(0) },
                                enabled = !busy,
                                modifier = Modifier.height(40.dp),
                            ) { Text(type.label) }
                        } else {
                            OutlinedButton(
                                onClick = { projectType = type; doSearch(0) },
                                enabled = !busy,
                                modifier = Modifier.height(40.dp),
                            ) { Text(type.label) }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (projectType == ModrinthProjectType.ModPack) {
                            "整合包会装成独立实例目录"
                        } else {
                            "下载到 ${projectType.targetDirectory}/"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (projectType == ModrinthProjectType.ModPack) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = state.installName,
                            onValueChange = { state.installName = it },
                            label = { Text("实例名字（可选）") },
                            singleLine = true,
                            modifier = Modifier.width(320.dp),
                        )
                        Text(
                            "同一个整合包装两次可以起不同名字，各装各的、互不覆盖",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = keyword,
                        onValueChange = { keyword = it },
                        label = { Text("搜索模组（留空看热门）") },
                        singleLine = true,
                        modifier = Modifier.width(320.dp),
                    )
                    OutlinedTextField(
                        value = gameVersion,
                        onValueChange = { gameVersion = it },
                        label = { Text("游戏版本") },
                        singleLine = true,
                        modifier = Modifier.width(150.dp),
                    )
                    if (projectType.usesLoader) {
                        Text("加载器", style = MaterialTheme.typography.bodyMedium)
                        listOf(ModLoader.Fabric, ModLoader.Quilt, ModLoader.Forge, ModLoader.NeoForge).forEach { candidate ->
                            if (candidate == loader) {
                                Button(onClick = { loader = candidate }, modifier = Modifier.height(42.dp)) {
                                    Text(candidate.displayName)
                                }
                            } else {
                                OutlinedButton(onClick = { loader = candidate }, modifier = Modifier.height(42.dp)) {
                                    Text(candidate.displayName)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { doSearch() },
                        enabled = !searching && !busy,
                        modifier = Modifier.height(46.dp).width(110.dp),
                    ) { Text(if (searching) "搜索中…" else "搜索") }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { doSearch(page - 1) },
                        enabled = !searching && page > 0,
                        modifier = Modifier.height(40.dp),
                    ) { Text("◂ 上一页") }
                    OutlinedButton(
                        onClick = { doSearch(page + 1) },
                        enabled = !searching && (page + 1) * pageSize < totalHits,
                        modifier = Modifier.height(40.dp),
                    ) { Text("下一页 ▸") }
                    Text(
                        "第 ${page + 1} / ${if (totalHits <= 0) 1 else (totalHits + pageSize - 1) / pageSize} 页　·　" +
                            if (totalHits == 0) {
                                "没有结果"
                            } else {
                                "${page * pageSize + 1}–${minOf((page + 1) * pageSize, totalHits)} / 共 $totalHits 个"
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    Text("每页", style = MaterialTheme.typography.bodySmall)
                    listOf(20, 40, 60).forEach { size ->
                        if (size == pageSize) {
                            Button(onClick = { pageSize = size; doSearch(0) }, modifier = Modifier.height(36.dp)) {
                                Text("$size")
                            }
                        } else {
                            OutlinedButton(onClick = { pageSize = size; doSearch(0) }, modifier = Modifier.height(36.dp)) {
                                Text("$size")
                            }
                        }
                    }
                }
                Text(
                    "Modrinth 的模组按「加载器 + 游戏版本」筛选。装模组前请先在「下载」页装好对应的加载器版本" +
                        "（比如 Fabric），然后这里的游戏版本填原版号（如 1.21.8）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            // 搜索结果
            Card(Modifier.weight(1f).fillMaxHeight()) {
                Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("搜索结果", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (results.isEmpty()) {
                        Text(
                            if (searching) "搜索中…" else "还没有结果，填关键词后点「搜索」。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(results, key = { it.id }) { project ->
                                Card(
                                    Modifier.fillMaxWidth().clickable { loadVersions(project) },
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (project.id == selectedProject?.id) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        },
                                    ),
                                ) {
                                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            ModIcon(project.iconUrl, project.iconUrl?.let { icons[it] })
                                            Spacer(Modifier.width(12.dp))
                                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                                Text(
                                                    project.title,
                                                    style = MaterialTheme.typography.titleSmall,
                                                    fontWeight = FontWeight.Bold,
                                                )
                                                Text(
                                                    project.description.take(120),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                        Text(
                                            project.summary,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 版本与下载
            Card(Modifier.weight(1f).fillMaxHeight()) {
                Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        selectedProject?.let { "「${it.title}」的版本" } ?: "版本列表",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    if (selectedProject == null) {
                        Text(
                            "在左边点一个模组，这里会列出它在你所选加载器 / 游戏版本下可用的版本。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (loadingVersions) {
                        Text("读取中…", style = MaterialTheme.typography.bodySmall)
                    } else if (versions.isEmpty()) {
                        Text(
                            "没有匹配的版本。试试换加载器或游戏版本号。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(versions, key = { it.id }) { version ->
                                Card(Modifier.fillMaxWidth()) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                            Text(
                                                version.displayName,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium,
                                            )
                                            Text(
                                                version.primaryFile?.filename.orEmpty() +
                                                    "　${version.datePublished.take(10)}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        Button(
                                            onClick = {
                                                selectedProject?.let { project ->
                                                    if (projectType == ModrinthProjectType.ModPack) {
                                                        installModpack(project, version)
                                                    } else {
                                                        downloadMod(project, version)
                                                    }
                                                }
                                            },
                                            enabled = !busy,
                                            modifier = Modifier.height(40.dp),
                                        ) {
                                            Text(if (projectType == ModrinthProjectType.ModPack) "安装整合包" else "下载")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (busy || progressText.isNotEmpty()) {
            ProgressBar(progress)
            Text(progressText, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ================================================================ 设置页

/** 版本卡片上的一行小提示：隔离 / 单独内存 / 指定 Java */
private fun versionNoteOf(state: LauncherState, version: MinecraftVersion): String? {
    val perVersion = state.versionSettings[version.id] ?: return null
    val parts = mutableListOf<String>()
    when (perVersion.isolation) {
        VersionSettings.IsolationIsolated -> parts.add("隔离")
        VersionSettings.IsolationShared -> parts.add("不隔离")
    }
    if (perVersion.memoryMb.isNotBlank()) parts.add("内存 ${perVersion.memoryMb}")
    if (perVersion.javaPath.isNotBlank()) parts.add("指定 Java")
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/** 把进度里的原始标签翻译成「现在在干什么」 */
private fun phaseLabel(current: String): String = when {
    current.isBlank() -> "正在准备…"
    current.startsWith("客户端") -> "下载客户端 jar"
    current.startsWith("库 ") -> "下载依赖库"
    current.startsWith("natives") -> "下载旧版 natives"
    current.startsWith("资源索引") -> "下载资源索引"
    current.startsWith("资源 ") -> "下载资源文件"
    current.startsWith("日志配置") -> "下载日志配置"
    current.startsWith("整合包") -> "下载整合包本体"
    current.startsWith("模组") || current.startsWith("光影") || current.startsWith("资源包") -> "下载 $current"
    else -> "处理中：$current"
}

@Composable
private fun SettingsPane(
    state: LauncherState,
    onLog: (String) -> Unit,
    onSettingsChanged: () -> Unit,
    onVersionSettingsChanged: () -> Unit,
    onOpenRoot: (Path) -> Unit,
) {
    val gameDir = state.installation?.root ?: state.gameRoot

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("设置", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("启动设置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Switch(
                        checked = state.versionIsolation,
                        onCheckedChange = { checked ->
                            state.versionIsolation = checked
                            onSettingsChanged()
                            onLog("[设置] 版本隔离已${if (checked) "开启" else "关闭"}")
                        },
                    )
                    Column(Modifier.weight(1f)) {
                        Text("版本隔离", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (state.versionIsolation) {
                                "每个版本用 versions\\<版本名>\\ 当游戏目录：存档、mods、config、按键设置各版本独立，互不干扰。"
                            } else {
                                "所有版本共用游戏根目录：存档和 mods 都放一起（装新版本不用重复下资源，但不同版本的 mod 会互相影响）。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    "注意：打开隔离后，原来放在游戏根目录的存档不会自动搬过去 —— 想把旧存档带进隔离目录，" +
                        "手动把 saves 文件夹复制到 versions\\<版本名>\\ 下就行。单个版本可以在下面单独覆盖。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    OutlinedTextField(
                        value = state.memoryInput,
                        onValueChange = { input ->
                            state.memoryInput = input.filter { it.isDigit() }
                            onSettingsChanged()
                        },
                        label = { Text("默认最大内存（MB）") },
                        singleLine = true,
                        modifier = Modifier.width(240.dp),
                    )
                    Text(
                        "单个版本可以单独覆盖",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("游戏语言", style = MaterialTheme.typography.bodyMedium)
                    listOf(
                        GameOptions.ChineseSimplified to "简体中文",
                        GameOptions.English to "English",
                        GameOptions.Auto to "跟随游戏文件",
                    ).forEach { (code, label) ->
                        if (code == state.gameLanguage) {
                            Button(
                                onClick = { state.gameLanguage = code; onSettingsChanged() },
                                modifier = Modifier.height(40.dp),
                            ) { Text(label) }
                        } else {
                            OutlinedButton(
                                onClick = { state.gameLanguage = code; onSettingsChanged() },
                                modifier = Modifier.height(40.dp),
                            ) { Text(label) }
                        }
                    }
                    Text(
                        "写进 <游戏目录>\\options.txt 的 lang 行",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Switch(
                        checked = themeDarkSetting.value,
                        onCheckedChange = { checked ->
                            themeDarkSetting.value = checked
                            onSettingsChanged()
                        },
                    )
                    Text("黑夜模式", style = MaterialTheme.typography.bodyMedium)
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("背景", style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(
                        onClick = {
                            chooseFiles("选择背景图").firstOrNull()?.let { picked ->
                                backgroundPathSetting.value = picked.toString()
                                onSettingsChanged()
                            }
                        },
                        modifier = Modifier.height(40.dp),
                    ) { Text("选择图片 / 动图") }
                    OutlinedButton(
                        onClick = { backgroundPathSetting.value = ""; onSettingsChanged() },
                        modifier = Modifier.height(40.dp),
                    ) { Text("清除背景") }
                }
                Text(
                    backgroundPathSetting.value.takeIf { it.isNotBlank() }
                        ?.let { "当前背景：${Path.of(it).fileName}" }
                        ?: "支持 PNG / JPG / BMP / GIF 动图（MP4 暂不支持，需要额外视频解码依赖）",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = if (backgroundPathSetting.value.isNotBlank()) FontFamily.Monospace else FontFamily.Default,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("窗口", style = MaterialTheme.typography.bodyMedium)
                    listOf(
                        true to "锁定大小（不能全屏）",
                        false to "可调整大小",
                    ).forEach { (locked, label) ->
                        if (locked == windowLockedSetting.value) {
                            Button(
                                onClick = { windowLockedSetting.value = locked; onSettingsChanged() },
                                modifier = Modifier.height(40.dp),
                            ) { Text(label) }
                        } else {
                            OutlinedButton(
                                onClick = { windowLockedSetting.value = locked; onSettingsChanged() },
                                modifier = Modifier.height(40.dp),
                            ) { Text(label) }
                        }
                    }
                    Text(
                        "锁定后最大化按钮会变灰；「可调整大小」就恢复常规窗口",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("界面缩放", style = MaterialTheme.typography.bodyMedium)
                    listOf(
                        "auto" to "自动",
                        "0.75" to "75%",
                        "0.85" to "85%",
                        "1.0" to "100%",
                        "1.15" to "115%",
                        "1.3" to "130%",
                    ).forEach { (code, label) ->
                        if (code == uiScaleSetting.value) {
                            Button(
                                onClick = { uiScaleSetting.value = code; onSettingsChanged() },
                                modifier = Modifier.height(40.dp),
                            ) { Text(label) }
                        } else {
                            OutlinedButton(
                                onClick = { uiScaleSetting.value = code; onSettingsChanged() },
                                modifier = Modifier.height(40.dp),
                            ) { Text(label) }
                        }
                    }
                    Text(
                        "整个界面等比缩放；「自动」会按窗口大小自动缩（只缩不放）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Switch(
                        checked = state.autoDownloadJava,
                        onCheckedChange = { checked ->
                            state.autoDownloadJava = checked
                            onSettingsChanged()
                        },
                    )
                    Text(
                        "缺少该版本需要的 Java 时，自动下载官方运行时（约 40–60 MB）",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("目录", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "启动器数据：${LauncherPaths.dataRoot}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        onClick = { openInExplorer(LauncherPaths.dataRoot) },
                        modifier = Modifier.height(36.dp),
                    ) { Text("打开") }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "当前游戏目录：$gameDir",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = { openInExplorer(gameDir) }, modifier = Modifier.height(36.dp)) { Text("打开") }
                    if (state.gameRoot != gameDir) {
                        OutlinedButton(
                            onClick = { onOpenRoot(state.gameRoot) },
                            modifier = Modifier.height(36.dp),
                        ) { Text("回启动器目录") }
                    }
                }
                Text(
                    "整合包实例目录：${LauncherPaths.dataRoot.resolve("instances")}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        VersionSettingsCard(
            state = state,
            onChanged = onVersionSettingsChanged,
            onClose = { state.versionSettingsFor = null },
        )
    }
}

/** 单个版本的设置面板（开始页和设置页都放这一个） */
@Composable
private fun VersionSettingsCard(
    state: LauncherState,
    onChanged: () -> Unit,
    onClose: () -> Unit,
) {
    val version = state.versionSettingsFor ?: return
    val current = state.versionSettings[version.id] ?: VersionSettings()
    var displayName by remember(version.id) { mutableStateOf(current.displayName) }
    var isolation by remember(version.id) { mutableStateOf(current.isolation) }
    var memory by remember(version.id) { mutableStateOf(current.memoryMb) }
    var javaPath by remember(version.id) { mutableStateOf(current.javaPath) }
    val root = state.installation?.root ?: state.gameRoot

    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .padding(20.dp)
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "版本设置：${version.id}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = onClose, modifier = Modifier.height(36.dp)) { Text("收起") }
            }
            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = { Text("显示名字（多个安装看起来一样时用它区分；留空就用版本 id）") },
                singleLine = true,
                modifier = Modifier.width(480.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("游戏目录隔离", style = MaterialTheme.typography.bodyMedium)
                listOf(
                    VersionSettings.IsolationAuto to "跟随全局",
                    VersionSettings.IsolationIsolated to "隔离",
                    VersionSettings.IsolationShared to "不隔离",
                ).forEach { (code, label) ->
                    if (code == isolation) {
                        Button(onClick = { isolation = code }, modifier = Modifier.height(40.dp)) { Text(label) }
                    } else {
                        OutlinedButton(onClick = { isolation = code }, modifier = Modifier.height(40.dp)) { Text(label) }
                    }
                }
                val preview = if (resolveIsolation(state.versionIsolation, VersionSettings(isolation = isolation))) {
                    version.jsonPath.parent
                } else {
                    root
                }
                Text(
                    "→ $preview",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = memory,
                    onValueChange = { input -> memory = input.filter { it.isDigit() } },
                    label = { Text("这个版本的内存 MB（留空用全局）") },
                    singleLine = true,
                    modifier = Modifier.width(300.dp),
                )
                OutlinedTextField(
                    value = javaPath,
                    onValueChange = { javaPath = it },
                    label = { Text("指定 java 路径（留空自动找）") },
                    singleLine = true,
                    modifier = Modifier.width(400.dp),
                )
            }
            // ---------- 文件管理：模组 / 光影 / 存档 / 材质包 ----------
            val gameDir = if (resolveIsolation(state.versionIsolation, VersionSettings(isolation = isolation))) {
                version.jsonPath.parent
            } else {
                root
            }
            val hasShaderLoader = remember(gameDir, filesRefresh.value) { VersionFiles.hasShaderLoader(gameDir) }
            val categories: List<FileCategory> = if (hasShaderLoader) {
                FileCategory.entries.toList()
            } else {
                FileCategory.entries.filter { it != FileCategory.Shaders }
            }
            var category by remember(version.id) { mutableStateOf(FileCategory.Mods) }
            androidx.compose.runtime.LaunchedEffect(gameDir, category) {
                activeDropTarget.value = gameDir to category
            }
            androidx.compose.runtime.DisposableEffect(Unit) {
                onDispose { activeDropTarget.value = null }
            }
            val files = remember(gameDir, category, filesRefresh.value) { VersionFiles.list(gameDir, category) }

            Text(
                "文件管理（可以直接把文件拖进窗口，也可以点「添加文件」）",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                categories.forEach { item ->
                    val count = remember(gameDir, item, filesRefresh.value) { VersionFiles.list(gameDir, item).size }
                    if (item == category) {
                        Button(onClick = { category = item }, modifier = Modifier.height(38.dp)) {
                            Text("${item.label} $count")
                        }
                    } else {
                        OutlinedButton(onClick = { category = item }, modifier = Modifier.height(38.dp)) {
                            Text("${item.label} $count")
                        }
                    }
                }
                OutlinedButton(
                    onClick = {
                        val picked = chooseFiles("添加${category.label}")
                        if (picked.isNotEmpty()) {
                            val added = VersionFiles.import(gameDir, category, picked)
                            filesRefresh.value++
                            dropStatus.value = "已添加 ${added.size} 个到「${category.label}」"
                        }
                    },
                    modifier = Modifier.height(38.dp),
                ) { Text("添加文件") }
                Text(
                    "目录：${VersionFiles.directory(gameDir, category)}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!hasShaderLoader) {
                Text(
                    "（装了 Iris / OptiFine 之后，这里会自动多出「光影」一类）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            dropStatus.value?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            if (files.isEmpty()) {
                Text(
                    "「${category.label}」目录还是空的 —— 把文件拖进窗口试试",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 160.dp)) {
                    items(files, key = { it.path.toString() }) { file ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                file.name,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                file.readableSize,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(10.dp))
                            OutlinedButton(
                                onClick = {
                                    if (VersionFiles.delete(file.path)) {
                                        filesRefresh.value++
                                        dropStatus.value = "已删除 ${file.name}"
                                    } else {
                                        dropStatus.value = "删不掉：${file.name}（可能被游戏占用）"
                                    }
                                },
                                modifier = Modifier.height(34.dp),
                            ) { Text("删除") }
                        }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        val updated = VersionSettings(
                            displayName = displayName.trim(),
                            isolation = isolation,
                            memoryMb = memory.trim(),
                            javaPath = javaPath.trim(),
                        )
                        if (updated.isEmpty) {
                            state.versionSettings.remove(version.id)
                        } else {
                            state.versionSettings[version.id] = updated
                        }
                        onChanged()
                    },
                    modifier = Modifier.height(44.dp),
                ) { Text("保存", fontWeight = FontWeight.Bold) }
                OutlinedButton(
                    onClick = {
                        state.versionSettings.remove(version.id)
                        displayName = ""
                        isolation = VersionSettings.IsolationAuto
                        memory = ""
                        javaPath = ""
                        onChanged()
                    },
                    modifier = Modifier.height(44.dp),
                ) { Text("恢复默认") }
                Text(
                    "存在游戏目录下的 abi-versions.json，跟着游戏目录走",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ================================================================ 背景与文件选择

/** 背景层：静态图直接画，GIF 按 100ms 一帧循环 */
@Composable
private fun BackgroundLayer() {
    val path = backgroundPathSetting.value
    if (path.isBlank()) return
    val frames = remember(path) { runCatching { Backdrop.load(Path.of(path)) }.getOrNull() } ?: return
    var index by remember(path) { mutableStateOf(0) }
    if (frames.animated) {
        LaunchedEffect(path) {
            while (true) {
                kotlinx.coroutines.delay(frames.delayMillis.coerceAtLeast(40).toLong())
                index = (index + 1) % frames.images.size
            }
        }
    }
    Image(
        bitmap = frames.images[index.coerceIn(0, frames.images.lastIndex)],
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        alpha = 0.9f,
    )
}

/** 系统文件选择框（可多选） */
private fun chooseFiles(title: String = "选择文件"): List<Path> = runCatching {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, title, java.awt.FileDialog.LOAD)
    dialog.isMultipleMode = true
    dialog.isVisible = true
    dialog.files?.map { it.toPath() } ?: emptyList()
}.getOrDefault(emptyList())

// ================================================================ 通用组件

// 说明：日志目前仍然在内部收集（LauncherState.logs），只是界面上不再显示。
// 想恢复的话，把 LogCard 重新放回各页面即可。

@Composable
private fun ProgressBar(fraction: Float) {
    val value = fraction.coerceIn(0f, 1f)
    Box(
        Modifier
            .fillMaxWidth()
            .height(10.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(5.dp)),
    ) {
        if (value > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(value)
                    .height(10.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(5.dp)),
            )
        }
    }
}

@Composable
private fun CollapsibleCard(
    title: String,
    subtitle: String? = null,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    contentSpacing: Dp = 10.dp,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Card(modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { onToggle() }.padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (expanded) "▾" else "▸", fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                subtitle?.let {
                    Spacer(Modifier.width(12.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                trailing?.invoke()
            }
            if (expanded) {
                Column(
                    Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(contentSpacing),
                ) { content() }
            }
        }
    }
}

@Composable
private fun VersionCard(
    version: MinecraftVersion,
    selected: Boolean,
    onSelect: () -> Unit,
    label: String,
    note: String?,
    onSettings: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().clickable { onSelect() },
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = onSelect)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (label != version.id) {
                        Text(
                            version.id,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Chip(version.category, categoryColor(version))
                    if (!version.clientJarPresent) Chip("缺少 jar", MaterialTheme.colorScheme.error)
                    note?.let { Chip(it, MaterialTheme.colorScheme.primary) }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = onSettings, modifier = Modifier.height(36.dp)) { Text("设置") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text(
                        version.javaMajorVersion?.let { "需要 Java $it" } ?: "Java 未标注",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "${version.libraryCount} 个依赖库",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    version.inheritsFrom?.let {
                        Text(
                            "继承自 $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (selected) {
                Text(
                    "当前选中",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun categoryColor(version: MinecraftVersion): Color = when {
    version.isModded -> MaterialTheme.colorScheme.tertiary
    version.type == "release" -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.secondary
}

/** Modrinth 模组图标：内存里拿到就画，没拿到先显示占位符 */
@Composable
private fun ModIcon(url: String?, bitmap: ImageBitmap?, size: Dp = 44.dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.fillMaxSize())
        } else {
            Text(
                if (url == null) "—" else "…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Chip(text: String, color: Color) {
    Box(
        Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text, fontSize = 11.sp, color = color)
    }
}

// ---------------------------------------------------------------- 普通函数

/** 默认选中最新一个「有客户端 jar 的正式版」，否则退而求其次 */
private fun pickDefaultVersion(versions: List<MinecraftVersion>): MinecraftVersion? =
    versions.firstOrNull { !it.isModded && it.type == "release" && it.clientJarPresent }
        ?: versions.firstOrNull { it.clientJarPresent }
        ?: versions.firstOrNull()

/** 弹出系统文件夹选择框；取消则返回 null */
private fun chooseDirectory(initial: String): Path? {
    val start = runCatching { Path.of(initial.trim()) }.getOrNull()?.takeIf { it.isDirectory() }?.toFile()
    val chooser = JFileChooser(start).apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        isMultiSelectionEnabled = false
        dialogTitle = "选择 Minecraft 游戏根目录（.minecraft）"
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile?.toPath()
    } else {
        null
    }
}

private fun openInExplorer(dir: Path) {
    runCatching {
        if (!dir.isDirectory()) LauncherPaths.ensureGameRoot(dir)
        java.awt.Desktop.getDesktop().open(dir.toFile())
    }
}
