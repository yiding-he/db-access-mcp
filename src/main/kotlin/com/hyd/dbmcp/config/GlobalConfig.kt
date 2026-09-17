package com.hyd.dbmcp.config

import com.hyd.dbmcp.util.Json
import tools.jackson.databind.JsonNode

/** 全局设置中与具体连接无关的部分：CLI 命令路径。路径为空表示用 PATH 里的命令名 */
data class GlobalConfig(
    val mysqlBinary: String? = null,
    val mongoshBinary: String? = null,
    val redisBinary: String? = null,
) {

    fun toJson(): JsonNode {
        val node = Json.obj()
        mysqlBinary?.let { node.put("mysqlBinary", it) }
        mongoshBinary?.let { node.put("mongoshBinary", it) }
        redisBinary?.let { node.put("redisBinary", it) }
        return node
    }

    companion object {

        val EMPTY = GlobalConfig()

        fun fromJson(node: JsonNode): GlobalConfig = GlobalConfig(
            mysqlBinary = node.path("mysqlBinary").asString(null)?.trim()?.ifBlank { null },
            mongoshBinary = node.path("mongoshBinary").asString(null)?.trim()?.ifBlank { null },
            redisBinary = node.path("redisBinary").asString(null)?.trim()?.ifBlank { null },
        )
    }
}
