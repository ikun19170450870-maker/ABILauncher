package com.abi.launcher.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path

// =====================================================================================
//  微软登录界面（配合 MsaLoginCore.kt 使用）
//
//  用法（在你的界面里放一行就行）：
//      val msa = rememberMsaLoginState(LauncherPaths.dataRoot)
//      …
//      MsaLoginSection(msa) { line -> appendLog(line) }
//      …
//      // 启动游戏前拿参数（会自动刷新过期的 token）：
//      scope.launch { msa.launchArgs()?.let { args -> plan.command.addAll(args) } }
//
//  依赖：Compose Material3（项目已有）。打包成 exe 时运行时必须有 java.net.http 模块。
// =====================================================================================

/** 登录界面状态：放在外面，启动游戏时随手就能取当前账号 */
class MsaLoginUiState(val dataRoot: Path) {

    var accounts by mutableStateOf<List<MsaAccount>>(emptyList())
    var current by mutableStateOf<MsaAccount?>(null)
    var busy by mutableStateOf(false)
    var status by mutableStateOf<String?>(null)
    var device by mutableStateOf<DeviceCode?>(null)
    var error by mutableStateOf<String?>(null)
    var cancelRequested by mutableStateOf(false)

    /** 登录用的 ClientID：存在 <dataRoot>/msa-client-id.txt，界面里可改，不用动代码 */
    var clientId by mutableStateOf(MsaLogin.PUBLIC_CLIENT_ID)

    private val clientIdFile: Path get() = dataRoot.resolve("msa-client-id.txt")

    fun loadClientId() {
        clientId = runCatching {
            if (java.nio.file.Files.isRegularFile(clientIdFile)) {
                java.nio.file.Files.readString(clientIdFile).trim().ifBlank { MsaLogin.PUBLIC_CLIENT_ID }
            } else {
                MsaLogin.PUBLIC_CLIENT_ID
            }
        }.getOrDefault(MsaLogin.PUBLIC_CLIENT_ID)
    }

    fun saveClientId() {
        runCatching {
            java.nio.file.Files.createDirectories(dataRoot)
            java.nio.file.Files.writeString(clientIdFile, clientId.trim().ifBlank { MsaLogin.PUBLIC_CLIENT_ID })
        }
    }

    fun effectiveClientId(): String = clientId.trim().ifBlank { MsaLogin.PUBLIC_CLIENT_ID }

    private val file: Path get() = MsaLogin.Store.file(dataRoot)

    fun reload() {
        loadClientId()
        accounts = MsaLogin.Store.load(file)
        if (current == null) current = accounts.firstOrNull()
        if (current != null && accounts.none { it.uuid == current?.uuid }) current = accounts.firstOrNull()
    }

    fun select(account: MsaAccount) {
        current = account
    }

    fun remove(account: MsaAccount) {
        accounts = accounts.filterNot { it.uuid == account.uuid }
        MsaLogin.Store.save(accounts, file)
        if (current?.uuid == account.uuid) current = accounts.firstOrNull()
    }

    /**
     * 启动游戏前调用：保证 token 可用（过期自动刷新），返回 `--accessToken --uuid --username`。
     * 返回 null 表示没有可用账号（error 里会说原因）。
     */
    suspend fun launchArgs(): List<String>? = withContext(Dispatchers.IO) {
        val account = current ?: return@withContext null
        val fresh = runCatching { MsaLogin.ensureFresh(account) }
            .onFailure { failure -> error = failure.message ?: "登录已失效，请重新登录" }
            .getOrNull() ?: return@withContext null
        MsaLogin.Store.save(MsaLogin.Store.upsert(MsaLogin.Store.load(file), fresh), file)
        withContext(Dispatchers.Main) {
            current = fresh
            reload()
        }
        fresh.launchArgs()
    }

    /** 联机更稳的一套参数（带 --userType msa / --xuid / --clientId） */
    suspend fun fullLaunchArgs(): List<String>? =
        launchArgs()?.let { current?.fullLaunchArgs(MsaLogin.PUBLIC_CLIENT_ID) }
}

@Composable
fun rememberMsaLoginState(dataRoot: Path): MsaLoginUiState {
    val state = remember(dataRoot) { MsaLoginUiState(dataRoot) }
    LaunchedEffect(state) { state.reload() }
    return state
}

@Composable
fun MsaLoginSection(state: MsaLoginUiState, onLog: (String) -> Unit = {}) {
    val scope = rememberCoroutineScope()

    fun startLogin() {
        if (state.busy) return
        state.busy = true
        state.error = null
        state.device = null
        state.cancelRequested = false
        state.status = "正在申请设备码…"
        onLog("[账号] 开始微软设备码登录")

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    MsaLogin.login(
                        cancelled = { state.cancelRequested },
                        onDeviceCode = { device ->
                            state.device = device
                            onLog("[账号] ${device.describe()}")
                            runCatching {
                                java.awt.Desktop.getDesktop().browse(java.net.URI.create(device.verificationUri))
                            }
                        },
                        onStatus = { message ->
                            state.status = message
                            onLog("[账号] $message")
                        },
                    )
                }
            }
            result
                .onSuccess { account ->
                    val file = MsaLogin.Store.file(state.dataRoot)
                    MsaLogin.Store.save(MsaLogin.Store.upsert(MsaLogin.Store.load(file), account), file)
                    state.current = account
                    state.reload()
                    state.device = null
                    state.status = "登录成功：${account.name}"
                    state.error = null
                    onLog("[账号] 微软登录成功：${account.name}（${account.uuid}）")
                }
                .onFailure { failure ->
                    state.device = null
                    state.status = null
                    state.error = failure.message ?: failure::class.simpleName ?: "登录失败"
                    onLog("[账号] 登录失败：${state.error}")
                }
            state.busy = false
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("微软正版登录", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            Text(
                "已内置本启动器的应用 ID，无需填写；设备码登录不需要重定向 URI。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { startLogin() },
                    enabled = !state.busy,
                    modifier = Modifier.height(46.dp).width(200.dp),
                ) { Text(if (state.busy) "登录中…" else "用微软账号登录", fontWeight = FontWeight.Bold) }
                if (state.busy) {
                    OutlinedButton(
                        onClick = { state.cancelRequested = true },
                        modifier = Modifier.height(46.dp),
                    ) { Text("取消") }
                }
                state.status?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // ---- 设备码：让用户去浏览器输入 ----
            state.device?.let { device ->
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(8.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("请打开下面的网址，输入这个代码：", style = MaterialTheme.typography.bodyMedium)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                device.userCode,
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                            )
                            OutlinedButton(
                                onClick = {
                                    runCatching {
                                        java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(
                                            java.awt.datatransfer.StringSelection(device.userCode),
                                            null,
                                        )
                                    }
                                    onLog("[账号] 设备码已复制")
                                },
                                modifier = Modifier.height(40.dp),
                            ) { Text("复制代码") }
                            OutlinedButton(
                                onClick = {
                                    runCatching {
                                        java.awt.Desktop.getDesktop().browse(java.net.URI.create(device.verificationUri))
                                    }
                                },
                                modifier = Modifier.height(40.dp),
                            ) { Text("打开浏览器") }
                        }
                        Text(
                            device.verificationUri,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ---- 错误（MsaError 的 message 已经是人话，可直接展示）----
            state.error?.let { message ->
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(8.dp)) {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                    )
                }
            }

            // ---- 已保存的正版账号 ----
            if (state.accounts.isEmpty()) {
                Text(
                    "还没有正版账号。点上面的按钮登录；离线账号在别处管理。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 160.dp)) {
                    items(state.accounts, key = { it.uuid }) { account ->
                        val active = account.uuid == state.current?.uuid
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(
                                    if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                    RoundedCornerShape(6.dp),
                                )
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    account.name + if (active) "（当前）" else "",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                )
                                Text(
                                    account.uuid,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            OutlinedButton(
                                onClick = {
                                    state.select(account)
                                    onLog("[账号] 已切换到 ${account.name}")
                                },
                                enabled = !active,
                                modifier = Modifier.height(34.dp),
                            ) { Text("使用") }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = {
                                    state.remove(account)
                                    onLog("[账号] 已删除 ${account.name}")
                                },
                                modifier = Modifier.height(34.dp),
                            ) { Text("删除") }
                        }
                    }
                }
            }

            Text(
                "登录信息保存在 " + MsaLogin.Store.file(state.dataRoot).toString() +
                    "（含 refresh_token，属于账号凭据，别外传）。启动游戏时会自动续期。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
