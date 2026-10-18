package com.abi.launcher.account

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

// =====================================================================================
// Minecraft 微软设备码登录（纯后端，无 GUI）
// ①设备码 → ②轮询换token → ③Xbox Live → ④XSTS → ⑤Minecraft token → ⑥档案 → ⑦启动参数
// 依赖：只用 JDK(java.net.http) + Gson；ClientID 用第三方启动器通用公开值，无需 Azure 注册
// ⚠️ 公开 ClientID 属于官方启动器，随时可能失效；正式发布建议自己注册 Azure 公共客户端
//    千万别填 Azure 门户那个应用 ID（c44b4083-…），会报「租户 Microsoft Services 中不存在」
// =====================================================================================

data class HttpResult(val status: Int, val body: String) { val ok get() = status in 200..299 }

/** 登录异常：上层按类型分别提示 */
sealed class MsaError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Net(message: String, cause: Throwable? = null) : MsaError(message, cause)   // 网络错误
    class Timeout(message: String = "登录超时，请重试") : MsaError(message)           // 超时/过期/取消
    class Denied(message: String = "你在浏览器里拒绝了授权") : MsaError(message)      // 用户拒绝
    class NotOwned(message: String = "这个微软账号没有购买 Minecraft") : MsaError(message)
    class NoXboxAccount(message: String = "该账号还没有 Xbox 账号：先去 xbox.com 登录一次") : MsaError(message)
    class RegionBlocked(message: String = "所在地区不支持 Xbox Live") : MsaError(message)
    class ChildAccount(message: String = "未成年账号：需要家长在 Microsoft 家庭里同意") : MsaError(message)
    class BadClientId(message: String) : MsaError(message)                            // ClientID 不对
    class Protocol(message: String, val status: Int = 0, val raw: String = "") : MsaError(message)
}

data class DeviceCode(
    val userCode: String, val deviceCode: String, val verificationUri: String,
    val expiresInSeconds: Int, val intervalSeconds: Int, val message: String?,
) { fun describe() = "打开 $verificationUri 输入代码：$userCode" }

data class MsaToken(val accessToken: String, val refreshToken: String?, val expiresInSeconds: Long)
data class XblToken(val token: String, val uhs: String)
data class XstsToken(val token: String, val uhs: String, val xuid: String)
data class McToken(val accessToken: String, val expiresInSeconds: Long)
data class McProfile(val uuid: String, val name: String)

/** 本地账号（JSON）；refreshToken 是凭据，别外传/别提交 git */
data class MsaAccount(
    val name: String = "", val uuid: String = "", val accessToken: String = "",
    val refreshToken: String? = null, val expiresAtEpochSeconds: Long = 0L,
    val xuid: String? = null, val type: String = "msa",
) {
    fun expiring(skew: Long = 300, now: Long = System.currentTimeMillis() / 1000) = expiresAtEpochSeconds <= now + skew
    val canRefresh get() = !refreshToken.isNullOrBlank()

    /** ★ --accessToken / --uuid / --username */
    fun launchArgs() = listOf("--accessToken", accessToken, "--uuid", uuid, "--username", name)

    /** 联机更稳的一套 */
    fun fullLaunchArgs(clientId: String) = buildList {
        addAll(launchArgs())
        add("--userType"); add("msa")
        xuid?.takeIf { it.isNotBlank() }?.let { add("--xuid"); add(it) }
        add("--clientId"); add(clientId)
        add("--userProperties"); add("{}")
    }
}

object MsaLogin {

    /** 第三方启动器通用公开 ClientID（也可传入自己的 Azure 应用 ID） */
    const val PUBLIC_CLIENT_ID = "7aa24396-481a-4567-af2b-3998de7bc7fd"

    private const val SCOPE = "XboxLive.signin offline_access"
    private const val DEVICE_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode"
    private const val TOKEN_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token"
    private const val XBL_URL = "https://user.auth.xboxlive.com/user/authenticate"
    private const val XSTS_URL = "https://xsts.auth.xboxlive.com/xsts/authorize"
    private const val MC_LOGIN = "https://api.minecraftservices.com/authentication/login_with_xbox"
    private const val MC_PROFILE = "https://api.minecraftservices.com/minecraft/profile"
    private const val MC_OWNED = "https://api.minecraftservices.com/entitlements/mcstore"

    val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()

    // ---------------- HTTP 封装（网络异常统一变 MsaError.Net）

    private fun send(r: HttpRequest): HttpResult = try {
        val resp = http.send(r, HttpResponse.BodyHandlers.ofString())
        HttpResult(resp.statusCode(), resp.body() ?: "")
    } catch (e: Exception) {
        throw MsaError.Net("网络错误：${e.message}", e)
    }

    private fun postJson(url: String, body: String) = send(
        HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json").header("User-Agent", "ABILauncher/1.0")
            .POST(HttpRequest.BodyPublishers.ofString(body, Charsets.UTF_8)).build(),
    )

    private fun postForm(url: String, form: Map<String, String>) = send(
        HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("User-Agent", "ABILauncher/1.0")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    form.entries.joinToString("&") { (k, v) ->
                        java.net.URLEncoder.encode(k, "UTF-8") + "=" + java.net.URLEncoder.encode(v, "UTF-8")
                    },
                    Charsets.UTF_8,
                ),
            ).build(),
    )

    private fun get(url: String, bearer: String) = send(
        HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
            .header("Authorization", "Bearer $bearer").header("User-Agent", "ABILauncher/1.0")
            .GET().build(),
    )

    private fun obj(body: String): JsonObject? = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
    private fun JsonObject.s(k: String) = get(k)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
    private fun JsonObject.n(k: String) = get(k)?.takeIf { it.isJsonPrimitive }?.asLong

    // ---------------- ① 申请设备码

    fun requestDeviceCode(clientId: String = PUBLIC_CLIENT_ID): DeviceCode {
        val r = postForm(DEVICE_URL, mapOf("client_id" to clientId, "scope" to SCOPE))
        if (!r.ok) throw translate(r, "申请设备码")
        val o = obj(r.body) ?: throw MsaError.Protocol("设备码响应无法解析", r.status, r.body)
        val user = o.s("user_code") ?: throw MsaError.Protocol("响应缺少 user_code", r.status, r.body)
        val device = o.s("device_code") ?: throw MsaError.Protocol("响应缺少 device_code", r.status, r.body)
        return DeviceCode(
            userCode = user, deviceCode = device,
            verificationUri = o.s("verification_uri") ?: "https://microsoft.com/link",
            expiresInSeconds = o.n("expires_in")?.toInt() ?: 900,
            intervalSeconds = (o.n("interval")?.toInt() ?: 5).coerceAtLeast(1),
            message = o.s("message"),
        )
    }

    // ---------------- ② 轮询换 Microsoft token（阻塞；cancelled 返回 true 立刻中断）

    fun pollForToken(
        device: DeviceCode, clientId: String = PUBLIC_CLIENT_ID,
        cancelled: () -> Boolean = { false }, onTick: (String) -> Unit = {},
    ): MsaToken {
        val deadline = System.currentTimeMillis() + device.expiresInSeconds * 1000L
        var interval = device.intervalSeconds
        while (System.currentTimeMillis() < deadline) {
            if (cancelled()) throw MsaError.Timeout("已取消登录")
            Thread.sleep(interval * 1000L)
            val r = postForm(
                TOKEN_URL,
                mapOf(
                    "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                    "client_id" to clientId, "device_code" to device.deviceCode,
                ),
            )
            val token = parseToken(r.body)
            when {
                r.ok && token != null -> return token
                r.body.contains("authorization_pending") -> onTick("等待你在浏览器里完成登录…")
                r.body.contains("slow_down") -> { interval += 5; onTick("轮询过快，放慢到 ${interval}s") }
                r.body.contains("expired_token") -> throw MsaError.Timeout("设备码已过期，请重试")
                r.body.contains("authorization_declined") -> throw MsaError.Denied()
                else -> throw translate(r, "轮询登录")
            }
        }
        throw MsaError.Timeout()
    }

    fun parseToken(body: String): MsaToken? {
        val o = obj(body) ?: return null
        val access = o.s("access_token") ?: return null
        return MsaToken(access, o.s("refresh_token"), o.n("expires_in") ?: 3600L)
    }

    /** refresh_token 静默续期（不需要用户操作） */
    fun refresh(refreshToken: String, clientId: String = PUBLIC_CLIENT_ID): MsaToken {
        val r = postForm(
            TOKEN_URL,
            mapOf(
                "grant_type" to "refresh_token", "client_id" to clientId,
                "refresh_token" to refreshToken, "scope" to SCOPE,
            ),
        )
        val t = parseToken(r.body) ?: throw translate(r, "刷新登录")
        if (!r.ok) throw translate(r, "刷新登录")
        return t.copy(refreshToken = t.refreshToken ?: refreshToken)
    }

    // ---------------- ③ Xbox Live

    fun authenticateXbl(msaAccessToken: String): XblToken {
        val body = """{"Properties":{"AuthMethod":"RPS","SiteName":"user.auth.xboxlive.com","RpsTicket":"d=$msaAccessToken"},""" +
            """"RelyingParty":"http://auth.xboxlive.com","TokenType":"JWT"}"""
        val r = postJson(XBL_URL, body)
        val o = obj(r.body)
        val token = o?.s("Token") ?: throw translate(r, "Xbox Live 认证")
        val uhs = o.get("DisplayClaims")?.asJsonObject?.get("xui")?.asJsonArray
            ?.firstOrNull()?.asJsonObject?.s("uhs").orEmpty()
        return XblToken(token, uhs)
    }

    // ---------------- ④ XSTS（失败时按 XErr 翻译）

    fun authorizeXsts(xblToken: String): XstsToken {
        val body = """{"Properties":{"SandboxId":"RETAIL","UserTokens":["$xblToken"]},""" +
            """"RelyingParty":"rp://api.minecraftservices.com/","TokenType":"JWT"}"""
        val r = postJson(XSTS_URL, body)
        val o = obj(r.body)
        val token = o?.s("Token")
        if (!r.ok || token == null) {
            when (o?.get("XErr")?.takeIf { it.isJsonPrimitive }?.asString) {
                "2148916233" -> throw MsaError.NoXboxAccount()
                "2148916235" -> throw MsaError.RegionBlocked()
                "2148916238" -> throw MsaError.ChildAccount()
                else -> throw translate(r, "XSTS 授权")
            }
        }
        val xui = o.get("DisplayClaims")?.asJsonObject?.get("xui")?.asJsonArray?.firstOrNull()?.asJsonObject
        return XstsToken(token, xui?.s("uhs").orEmpty(), xui?.s("xid").orEmpty())
    }

    // ---------------- ⑤ Minecraft token

    fun loginWithXbox(xsts: XstsToken): McToken {
        val r = postJson(MC_LOGIN, """{"identityToken":"XBL3.0 x=${xsts.uhs};${xsts.token}"}""")
        val o = obj(r.body)
        val token = o?.s("access_token")
        if (!r.ok || token == null) {
            if (o?.s("error") == "UnauthorizedOperation") throw MsaError.NotOwned()
            throw translate(r, "换 Minecraft 令牌")
        }
        return McToken(token, o.n("expires_in") ?: 86400L)
    }

    /** 是否拥有 Java 版（items 非空才算买了） */
    fun ownsGame(mcAccessToken: String): Boolean {
        val r = get(MC_OWNED, mcAccessToken)
        val items = obj(r.body)?.get("items")?.takeIf { it.isJsonArray }?.asJsonArray
        return r.ok && items != null && items.size() > 0
    }

    /** 玩家档案；404 = 没买游戏或没创建角色名 */
    fun fetchProfile(mcAccessToken: String): McProfile {
        val r = get(MC_PROFILE, mcAccessToken)
        if (r.status == 404) throw MsaError.NotOwned()
        if (!r.ok) throw translate(r, "读取玩家档案")
        val o = obj(r.body) ?: throw MsaError.Protocol("档案解析失败", r.status, r.body)
        val id = o.s("id") ?: throw MsaError.Protocol("档案缺少 id", r.status, r.body)
        val name = o.s("name") ?: throw MsaError.Protocol("档案缺少 name", r.status, r.body)
        return McProfile(id.replace("-", ""), name)
    }

    // ---------------- 一站式

    /** 完整登录（阻塞）：onDeviceCode 把设备码显示给用户 */
    fun login(
        clientId: String = PUBLIC_CLIENT_ID, checkOwnership: Boolean = true,
        cancelled: () -> Boolean = { false },
        onDeviceCode: (DeviceCode) -> Unit = {}, onStatus: (String) -> Unit = {},
    ): MsaAccount {
        onStatus("正在申请设备码…")
        val device = requestDeviceCode(clientId)
        onDeviceCode(device)
        onStatus("等待浏览器里完成登录…")
        return finish(pollForToken(device, clientId, cancelled, onStatus), clientId, checkOwnership, onStatus)
    }

    /** 用 refresh_token 静默登录 */
    fun loginWithRefresh(
        account: MsaAccount, clientId: String = PUBLIC_CLIENT_ID,
        checkOwnership: Boolean = true, onStatus: (String) -> Unit = {},
    ): MsaAccount {
        val rt = account.refreshToken ?: throw MsaError.Protocol("没有 refresh_token，需要重新登录")
        onStatus("正在刷新登录…")
        return finish(refresh(rt, clientId), clientId, checkOwnership, onStatus)
    }

    /** 启动游戏前调一次：没到期直接用，快到期自动刷新 */
    fun ensureFresh(
        account: MsaAccount, clientId: String = PUBLIC_CLIENT_ID,
        skewSeconds: Long = 300, onStatus: (String) -> Unit = {},
    ): MsaAccount = when {
        !account.expiring(skewSeconds) -> account
        !account.canRefresh -> throw MsaError.Protocol("登录已过期，请重新登录")
        else -> loginWithRefresh(account, clientId, checkOwnership = false, onStatus = onStatus)
    }

    private fun finish(msa: MsaToken, clientId: String, checkOwnership: Boolean, onStatus: (String) -> Unit): MsaAccount {
        onStatus("认证 Xbox Live…")
        val xbl = authenticateXbl(msa.accessToken)
        onStatus("获取 XSTS 授权…")
        val xsts = authorizeXsts(xbl.token)
        onStatus("登录 Minecraft 服务…")
        val mc = loginWithXbox(xsts)
        if (checkOwnership && !ownsGame(mc.accessToken)) throw MsaError.NotOwned()
        onStatus("读取玩家档案…")
        val profile = fetchProfile(mc.accessToken)
        onStatus("登录成功：${profile.name}")
        return MsaAccount(
            name = profile.name,
            uuid = profile.uuid,
            accessToken = mc.accessToken,
            refreshToken = msa.refreshToken,
            expiresAtEpochSeconds = System.currentTimeMillis() / 1000 + mc.expiresInSeconds,
            xuid = xsts.xuid.takeIf { it.isNotBlank() },
        )
    }

    // ---------------- 错误翻译

    fun translate(r: HttpResult, step: String = ""): MsaError {
        if (r.status == 0) return MsaError.Net("网络错误：连不上微软服务器")
        val o = obj(r.body)
        val code = o?.s("error").orEmpty()
        val desc = o?.s("error_description").orEmpty()
        val lower = "$code $desc".lowercase()
        return when {
            lower.contains("aadsts50020") || desc.contains("租户") ->
                MsaError.BadClientId(
                    "ClientID 与账号租户不匹配：不要用 Azure 门户自己那个应用 ID，" +
                        "请填你自己注册的应用（帐户类型要含个人账号）",
                )
            lower.contains("aadsts700016") ->
                MsaError.BadClientId(
                    "这个应用在「个人 Microsoft 帐户」里不存在：去 Azure 应用 → 「受支持的帐户类型」" +
                        "改成「任何组织目录中的帐户和个人 Microsoft 帐户」；或者这个 ID 本身就是别人家专用、你借不到",
                )
            code == "unauthorized_client" ->
                MsaError.BadClientId(
                    "这个应用不允许设备码登录 / 缺权限：检查 ①「允许公共客户端流 = 是」" +
                        "② API 权限 → Microsoft Graph → 委托的权限 → 加 XboxLive.signin 和 offline_access" +
                        "（借来的公开 ID 报这个，通常说明它只对自己家客户端开放）",
                )
            lower.contains("aadsts7000218") || code == "invalid_client" ->
                MsaError.BadClientId("这个应用没打开「允许公共客户端流」：Azure → 身份验证 → 高级设置 → 改成「是」并保存")
            code == "authorization_declined" -> MsaError.Denied()
            code == "expired_token" -> MsaError.Timeout("设备码已过期，请重试")
            code == "invalid_grant" -> MsaError.Timeout("授权已失效，请重新登录")
            else -> MsaError.Protocol(buildString { if (step.isNotBlank()) append("「$step」"); append("失败（HTTP ${r.status}）"); val detail = if (desc.isNotBlank()) desc else r.body; if (detail.isNotBlank()) append("：${detail.take(240)}"); if (r.status == 403) append("　→ 403 常见原因：① 这个 Azure 应用没勾 XboxLive.signin 权限 ② 账号被 Xbox 限制/没建 Xbox 账号 ③ 借来的公开 ID 只对自己家客户端开放") }, r.status, r.body)
        }
    }

    // ---------------- 本地 JSON 账号文件（默认 <dataRoot>/msa-accounts.json，与离线账号分开）

    object Store {
        private val gson = GsonBuilder().setPrettyPrinting().create()
        fun file(dataRoot: Path): Path = dataRoot.resolve("msa-accounts.json")

        fun load(f: Path): List<MsaAccount> {
            if (!Files.isRegularFile(f)) return emptyList()
            val root = runCatching { JsonParser.parseString(Files.readString(f)).asJsonObject }.getOrNull() ?: return emptyList()
            val arr = root.get("accounts")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
            return arr.mapNotNull { runCatching { gson.fromJson(it, MsaAccount::class.java) }.getOrNull() }
        }

        fun save(accounts: List<MsaAccount>, f: Path) {
            val root = JsonObject().apply { add("accounts", gson.toJsonTree(accounts)) }
            Files.createDirectories(f.parent)
            Files.writeString(f, gson.toJson(root))
        }

        fun upsert(accounts: List<MsaAccount>, account: MsaAccount) =
            accounts.filterNot { it.uuid == account.uuid } + account

        /** 读第一个账号并保证可用（必要时自动刷新） */
        fun pickAndRefresh(
            f: Path, clientId: String = PUBLIC_CLIENT_ID, onStatus: (String) -> Unit = {},
        ): MsaAccount? {
            val all = load(f)
            val first = all.firstOrNull() ?: return null
            val fresh = runCatching { ensureFresh(first, clientId, onStatus = onStatus) }.getOrNull() ?: return null
            save(upsert(all, fresh), f)
            return fresh
        }
    }
}

// ============================== 用法 ==============================
// val f = MsaLogin.Store.file(LauncherPaths.dataRoot)
// // 首次登录：把设备码显示给用户 → 登录 → 落盘
// val acc = MsaLogin.login(onDeviceCode = { show(it.describe()) }, onStatus = { show(it) })
// MsaLogin.Store.save(MsaLogin.Store.upsert(MsaLogin.Store.load(f), acc), f)
// // 以后每次启动：静默刷新 + 拿启动参数
// val fresh = MsaLogin.ensureFresh(acc)
// fresh.launchArgs()                              // --accessToken --uuid --username
// fresh.fullLaunchArgs(MsaLogin.PUBLIC_CLIENT_ID)  // 联机更稳的一套
// // 异常类型：Timeout / Denied / NotOwned / NoXboxAccount / RegionBlocked / ChildAccount / BadClientId / Net / Protocol
// =================================================================
