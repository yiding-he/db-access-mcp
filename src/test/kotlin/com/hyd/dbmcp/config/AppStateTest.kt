package com.hyd.dbmcp.config

import com.hyd.dbmcp.crypto.ConfigEnvelopeException
import com.hyd.dbmcp.crypto.WrongConfigPasswordException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * 状态机与「内存改一份、磁盘同步一份」的写回行为。
 */
class AppStateTest {

    private val password = "super-secret-passphrase"

    @Test
    fun `文件不存在时要求设置配置密码`(@TempDir dir: Path) {
        val state = newState(dir)

        assertEquals(AppStatus.SETUP_REQUIRED, state.status())
        assertFalse(state.isUnlocked())
        assertThrows(NotReadyException::class.java) { state.connections() }
    }

    @Test
    fun `首次设置后进入已解锁并写出文件`(@TempDir dir: Path) {
        val state = newState(dir)

        state.setup(password)

        assertTrue(state.isUnlocked())
        assertEquals(AppStatus.UNLOCKED, state.status())
        assertTrue(newStore(dir).exists())
        assertEquals(1L, state.generation)
    }

    @Test
    fun `配置文件已存在时不允许再次初始化`(@TempDir dir: Path) {
        val state = newState(dir)
        state.setup(password)

        assertThrows(ConfigEnvelopeException::class.java) { newState(dir).setup("another-passphrase") }
    }

    @Test
    fun `未解锁进程重启后必须重新输入密码`(@TempDir dir: Path) {
        val first = newState(dir)
        first.setup(password)
        first.saveConnection(connection("mysql_a"), null)

        // 模拟重启：全新 AppState 只依赖磁盘上的信封
        val restarted = newState(dir)
        assertEquals(AppStatus.LOCKED, restarted.status())
        assertThrows(WrongConfigPasswordException::class.java) { restarted.unlock("wrong-password-here") }

        restarted.unlock(password)

        assertEquals(listOf("mysql_a"), restarted.connections().map { it.name })
    }

    @Test
    fun `增删连接都会整体重写配置文件`(@TempDir dir: Path) {
        val state = newState(dir)
        state.setup(password)
        val envelopeOf = { newStore(dir).readEnvelope()!! }

        state.saveConnection(connection("mysql_a"), null)
        state.saveConnection(connection("mongo_b"), null)
        assertEquals(listOf("mongo_b", "mysql_a"), state.connections().map { it.name }.sorted())
        val afterAdd = envelopeOf()
        state.saveConnection(connection("mysql_a").copy(description = "改过的描述"), "mysql_a")
        val afterUpdate = envelopeOf()
        state.deleteConnection("mongo_b")
        val afterDelete = envelopeOf()

        assertEquals(listOf("mysql_a"), state.connections().map { it.name })
        assertTrue(afterAdd != afterUpdate && afterUpdate != afterDelete, "三次写入的信封应当各不相同")
        assertFalse(afterAdd.contains("mysql_a"), "连接名必须以密文形式落盘：$afterAdd")

        val reopened = newState(dir).apply { unlock(password) }
        assertEquals("改过的描述", reopened.connections().single().description)
    }

    @Test
    fun `改密后旧密码失效新密码生效且会话代次递增`(@TempDir dir: Path) {
        val state = newState(dir)
        state.setup(password)
        state.saveConnection(connection("mysql_a"), null)
        val generationBefore = state.generation

        state.changePassword(password, "brand-new-passphrase")
        val newPassword = "brand-new-passphrase"

        assertEquals(generationBefore + 1, state.generation)
        assertNotNull(state.find("mysql_a"))
        assertThrows(WrongConfigPasswordException::class.java) { newState(dir).unlock(password) }

        val reopened = newState(dir).apply { unlock(newPassword) }

        assertEquals(listOf("mysql_a"), reopened.connections().map { it.name })
    }

    @Test
    fun `当前密码不正确时拒绝改密，原密码继续可用`(@TempDir dir: Path) {
        val state = newState(dir)
        state.setup(password)

        assertThrows(WrongConfigPasswordException::class.java) {
            state.changePassword("not-the-password", "brand-new-passphrase")
        }

        val reopened = newState(dir)
        reopened.unlock(password)
        assertEquals(0, reopened.connections().size)
    }

    @Test
    fun `删除不存在的连接报错`(@TempDir dir: Path) {
        val state = newState(dir)
        state.setup(password)

        assertThrows(IllegalArgumentException::class.java) { state.deleteConnection("nope") }
    }

    private fun newState(dir: Path) = AppState(newStore(dir))

    private fun newStore(dir: Path) = ConfigStore(properties(dir))

    private fun properties(dir: Path) = DbMcpProperties().apply { configPath = dir.resolve("config.data").toString() }

    private fun connection(name: String) = DbConnection(
        name = name,
        kind = if (name.startsWith("mongo")) DbKind.MONGODB else DbKind.MYSQL,
        description = "用于单测的连接",
        host = "127.0.0.1",
        port = if (name.startsWith("mongo")) 27017 else 3306,
        username = "reader",
        password = "db-password-should-not-leak",
        database = "app",
        authSource = "",
    )
}
