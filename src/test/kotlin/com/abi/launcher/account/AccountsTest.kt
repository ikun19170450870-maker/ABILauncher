package com.abi.launcher.account

import com.abi.launcher.launch.LaunchCommandBuilder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 离线账号：UUID 算法、存取、增删 */
class AccountsTest {

    private lateinit var tmp: Path
    private lateinit var file: Path

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("abi-accounts")
        file = tmp.resolve("accounts.json")
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    @Test
    fun `离线账号 UUID 与官方算法一致且稳定`() {
        val steve = AccountStore.offline("Steve")
        val again = AccountStore.offline("Steve")
        val alex = AccountStore.offline("Alex")

        assertEquals(steve.uuid, again.uuid, "同名必须得到同一个 UUID")
        assertTrue(steve.uuid != alex.uuid)
        assertEquals(32, steve.uuid.length, "UUID 是不带短横线的 32 位")
        assertTrue(steve.uuid.matches(Regex("[0-9a-f]{32}")))
        assertEquals(LaunchCommandBuilder.offlineUuid("Steve"), steve.uuid)
        assertTrue(steve.isOffline)
        assertEquals("Steve（离线）", steve.displayName)
    }

    @Test
    fun `账号列表可以存取往返`() {
        val accounts = listOf(
            AccountStore.offline("Steve"),
            AccountStore.offline("Alex"),
            GameAccount(name = "正版玩家", uuid = "0123456789abcdef0123456789abcdef", type = "msa", accessToken = "tok"),
        )

        AccountStore.save(accounts, file)
        val loaded = AccountStore.load(file)

        assertEquals(accounts, loaded)
        assertEquals("msa", loaded.last().type)
        assertEquals("tok", loaded.last().accessToken)
        assertEquals("正版玩家", loaded.last().displayName, "正版账号不显示「离线」后缀")
    }

    @Test
    fun `文件不存在时返回空列表`() {
        assertEquals(emptyList(), AccountStore.load(tmp.resolve("nope.json")))
    }

    @Test
    fun `重复添加同名账号会被忽略`() {
        var accounts = emptyList<GameAccount>()
        accounts = AccountStore.add(accounts, AccountStore.offline("Steve"))
        accounts = AccountStore.add(accounts, AccountStore.offline("Steve"))
        accounts = AccountStore.add(accounts, AccountStore.offline("Alex"))

        assertEquals(2, accounts.size)
        assertEquals(listOf("Steve", "Alex"), accounts.map { it.name })
    }

    @Test
    fun `可以删掉指定账号`() {
        val steve = AccountStore.offline("Steve")
        val alex = AccountStore.offline("Alex")
        val remaining = AccountStore.remove(listOf(steve, alex), steve)

        assertEquals(listOf(alex), remaining)
    }
}
