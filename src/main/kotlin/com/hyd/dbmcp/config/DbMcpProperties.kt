package com.hyd.dbmcp.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/** `dbmcp.*` 配置项，全部有默认值，正常部署不需要改 */
@Component
@ConfigurationProperties(prefix = "dbmcp")
class DbMcpProperties {

    /** 加密配置文件位置，支持 `~` 前缀 */
    var configPath: String = "~/.config/db-access-mcp/config.data"

    /** 单条查询最长执行时间，到点强杀子进程 */
    var queryTimeoutSeconds: Long = 30

    /** 最多返回多少数据行，超出即截断并 kill */
    var maxRows: Int = 100

    /** stdout 累计字节上限，超出即截断并 kill */
    var maxOutputBytes: Int = 61_440

    /** CLI 自身的连接超时（秒） */
    var connectTimeoutSeconds: Long = 10
}
