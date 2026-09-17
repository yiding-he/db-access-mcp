package com.hyd.dbmcp.config

import com.hyd.dbmcp.util.Json
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

/** 解密后的配置明文结构 */
data class AppConfig(
    val connections: List<DbConnection>,
    val global: GlobalConfig = GlobalConfig.EMPTY,
) {

    fun toJson(): ObjectNode {
        val node = Json.obj()
        node.put("version", CURRENT_VERSION)
        node.set("globalConfig", global.toJson())
        val list = node.putArray("connections")
        connections.forEach { list.add(it.toJson()) }
        return node
    }

    fun withConnections(updated: List<DbConnection>): AppConfig = copy(connections = updated)

    companion object {

        const val CURRENT_VERSION = 1

        val EMPTY = AppConfig(emptyList())

        fun fromJson(node: JsonNode): AppConfig {
            val version = node.path("version").asInt(CURRENT_VERSION)
            require(version == CURRENT_VERSION) { "不支持的配置版本：$version" }
            val connections = node.path("connections").mapNotNull { DbConnection.fromJson(it) }
            return AppConfig(
                connections = connections,
                global = node.path("globalConfig").let { if (it.isMissingNode) GlobalConfig.EMPTY else GlobalConfig.fromJson(it) },
            )
        }
    }
}
