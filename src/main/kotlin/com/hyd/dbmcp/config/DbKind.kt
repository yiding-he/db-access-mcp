package com.hyd.dbmcp.config

/** 支持的数据库类型，id 同时用于配置文件与 MCP 返回内容 */
enum class DbKind(val id: String, val label: String, val defaultPort: Int) {

    MYSQL("mysql", "MySQL", 3306),

    MONGODB("mongodb", "MongoDB", 27017),

    REDIS("redis", "Redis", 6379);

    companion object {
        fun of(id: String): DbKind? = entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
}
