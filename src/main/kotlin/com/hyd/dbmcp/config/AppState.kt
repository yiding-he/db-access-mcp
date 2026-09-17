package com.hyd.dbmcp.config

import com.hyd.dbmcp.crypto.CipherHandle
import com.hyd.dbmcp.crypto.ConfigCipher
import com.hyd.dbmcp.crypto.ConfigEnvelopeException
import com.hyd.dbmcp.util.Json
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets

enum class AppStatus {

    /** 配置文件不存在，管理员需要先设置配置密码 */
    SETUP_REQUIRED,

    /** 配置文件存在但尚未解密 */
    LOCKED,

    /** 已解密，MCP 可用 */
    UNLOCKED;
}

/** 解锁后驻留内存的状态：派生出的密钥信息 + 配置明文。配置密码本身不留存 */
private class UnlockedState(val handle: CipherHandle, val config: AppConfig)

/**
 * 全局唯一的状态与配置来源：解锁状态、连接配置的读写都经过这里，
 * 任何变更都是「先更新内存、再整体重加密写盘」。
 */
@Component
class AppState(private val store: ConfigStore) {

    private val monitor = Any()

    private val log = LoggerFactory.getLogger(AppState::class.java)

    @Volatile
    private var state: UnlockedState? = null

    /** 每次成功解锁/改密自增，用于让旧的浏览器会话与 MCP 会话失效 */
    @Volatile
    var generation: Long = 0L
        private set

    fun status(): AppStatus = when {
        state != null -> AppStatus.UNLOCKED
        store.exists() -> AppStatus.LOCKED
        else -> AppStatus.SETUP_REQUIRED
    }

    fun isUnlocked(): Boolean = state != null

    /** 未解锁时抛 NotReadyException，MCP 层据此返回 -32002 */
    fun requireUnlocked(): AppConfig = state?.config ?: throw NotReadyException()

    fun connections(): List<DbConnection> = requireUnlocked().connections

    fun find(name: String): DbConnection? = requireUnlocked().connections.firstOrNull { it.name == name }

    /** 首次启动：设置配置密码并写出空配置 */
    fun setup(password: String) {
        if (store.exists()) {
            throw ConfigEnvelopeException("配置文件已存在，无需重复设置")
        }
        synchronized(monitor) {
            val sealed = ConfigCipher.seal(encode(AppConfig.EMPTY), password)
            store.writeEnvelope(sealed.envelope)
            state = UnlockedState(sealed.handle, AppConfig.EMPTY)
            generation++
            log.info("配置文件已创建：{}", store.path)
        }
    }

    /** 解锁：用配置密码解密磁盘上的信封 */
    fun unlock(password: String) {
        synchronized(monitor) {
            val envelope = store.readEnvelope() ?: throw NotReadyException()
            val opened = ConfigCipher.open(envelope, password)
            val config = decode(opened.plaintext)
            state = UnlockedState(opened.handle, config)
            generation++
            log.info("配置已解锁，连接数={}", config.connections.size)
        }
    }

    /** 修改配置密码：先用旧密码验证，再用新密码整体重写文件 */
    fun changePassword(currentPassword: String, newPassword: String) {
        val snapshot = requireUnlocked()
        synchronized(monitor) {
            val envelope = store.readEnvelope() ?: throw NotReadyException()
            ConfigCipher.open(envelope, currentPassword)
            val sealed = ConfigCipher.seal(encode(snapshot), newPassword)
            store.writeEnvelope(sealed.envelope)
            state = UnlockedState(sealed.handle, snapshot)
            generation++
            log.info("配置密码已修改，配置文件整体重写")
        }
    }

    /** 新增或替换连接；originalName 非空表示编辑，用于定位被替换的条目 */
    fun saveConnection(candidate: DbConnection, originalName: String?) {
        val snapshot = requireUnlocked()
        val updated = snapshot.connections.toMutableList()
        val index = if (originalName != null) updated.indexOfFirst { it.name == originalName } else -1
        if (originalName != null && index < 0) {
            throw IllegalArgumentException("找不到连接：$originalName")
        }
        if (index >= 0) {
            updated[index] = candidate
        } else {
            updated += candidate
        }
        persist(updated)
    }

    fun deleteConnection(name: String) {
        val snapshot = requireUnlocked()
        if (snapshot.connections.none { it.name == name }) {
            throw IllegalArgumentException("找不到连接：$name")
        }
        persist(snapshot.connections.filterNot { it.name == name })
    }

    /** 更新全局设置（命令路径等），空串/空白视为清除，回退到 PATH 查找 */
    fun saveSettings(mysqlBinary: String?, mongoshBinary: String?, redisBinary: String?) {
        val snapshot = requireUnlocked()
        val updated = snapshot.copy(
            global = GlobalConfig(
                mysqlBinary = mysqlBinary?.trim()?.ifBlank { null },
                mongoshBinary = mongoshBinary?.trim()?.ifBlank { null },
                redisBinary = redisBinary?.trim()?.ifBlank { null },
            ),
        )
        synchronized(monitor) {
            val current = state ?: throw NotReadyException()
            store.writeEnvelope(ConfigCipher.reseal(encode(updated), current.handle))
            state = UnlockedState(current.handle, updated)
            log.info("全局设置已写回：{}", store.path)
        }
    }

    private fun persist(connections: List<DbConnection>) {
        val current = state ?: throw NotReadyException()
        synchronized(monitor) {
            val config = current.config.withConnections(connections)
            store.writeEnvelope(ConfigCipher.reseal(encode(config), current.handle))
            state = UnlockedState(current.handle, config)
            log.info("配置已写回：{}（连接数={}）", store.path, config.connections.size)
        }
    }

    private fun encode(config: AppConfig): ByteArray =
        Json.write(config.toJson()).toByteArray(StandardCharsets.UTF_8)

    private fun decode(plaintext: ByteArray): AppConfig =
        AppConfig.fromJson(Json.parse(String(plaintext, StandardCharsets.UTF_8)))
}

class NotReadyException : RuntimeException("mcp server not ready")
