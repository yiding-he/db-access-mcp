package com.hyd.dbmcp.config

import com.hyd.dbmcp.util.Json
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.net.URLEncoder

/**
 * 一个数据库连接配置。单一扁平模型，不适用的字段留空串，避免为两种数据库拆出类型层次。
 *
 * @param database MySQL 可选（空则不指定默认库）；MongoDB 必填
 * @param authSource 仅 MongoDB 使用
 */
data class DbConnection(
    val name: String,
    val kind: DbKind,
    val description: String,
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val database: String,
    val authSource: String,
    val queryTimeoutSeconds: Int? = null,
) {

    /** 管理页与日志里展示用的非敏感摘要 */
    fun endpoint(): String = "$host:$port"

    fun withPassword(newPassword: String): DbConnection = copy(password = newPassword)

    /**
     * 拼 MongoDB 连接串。用户名与密码按 RFC 3986 userinfo 规则转义，
     * 否则密码里出现 `@` `:` 就会把连接串解析错。
     */
    fun mongoUri(): String {
        val authority = if (username.isBlank()) {
            ""
        } else if (password.isBlank()) {
            "${userinfo(username)}@"
        } else {
            "${userinfo(username)}:${userinfo(password)}@"
        }
        val query = if (authSource.isBlank()) "" else "?authSource=${userinfo(authSource)}"
        return "mongodb://$authority$host:$port/$database$query"
    }

    fun toJson(): ObjectNode {
        val node = Json.obj()
        node.put("name", name)
        node.put("kind", kind.id)
        node.put("description", description)
        node.put("host", host)
        node.put("port", port)
        node.put("username", username)
        node.put("password", password)
        node.put("database", database)
        node.put("authSource", authSource)
        queryTimeoutSeconds?.let { node.put("queryTimeoutSeconds", it) }
        return node
    }

    /** 给 MCP resources/read 的内容：只暴露名字、描述、类型，绝不暴露地址与凭据 */
    fun toPublicJson(): ObjectNode {
        val node = Json.obj()
        node.put("name", name)
        node.put("description", description)
        node.put("kind", kind.id)
        return node
    }

    companion object {

        fun fromJson(node: JsonNode): DbConnection? {
            val kind = DbKind.of(node.path("kind").asString("")) ?: return null
            val timeoutNode = node.path("queryTimeoutSeconds")
            val queryTimeoutSeconds = if (timeoutNode.isMissingNode || timeoutNode.isNull) null
                else timeoutNode.asInt().takeIf { it > 0 }
            return DbConnection(
                name = node.path("name").asString(""),
                kind = kind,
                description = node.path("description").asString(""),
                host = node.path("host").asString(""),
                port = node.path("port").asInt(kind.defaultPort),
                username = node.path("username").asString(""),
                password = node.path("password").asString(""),
                database = node.path("database").asString(""),
                authSource = node.path("authSource").asString(""),
                queryTimeoutSeconds = queryTimeoutSeconds,
            )
        }

        /** userinfo 段允许的字符集很窄，一律按 UTF-8 百分号编码最安全 */
        private fun userinfo(value: String): String =
            URLEncoder.encode(value, Charsets.UTF_8)
                .replace("+", "%20")
    }
}
