package com.hyd.dbmcp.web

import com.hyd.dbmcp.config.DbConnection
import com.hyd.dbmcp.config.DbKind

/**
 * 新增/编辑连接的表单对象。
 *
 * 用可变属性 + request param 绑定（字段少且平铺），端口保持字符串以便把非法输入当成校验错误
 * 而不是 HTTP 400；密码永远不回显，编辑时留空表示沿用已保存的密码。
 */
class ConnectionForm {

    /** 编辑时携带的原连接名，空表示新增（连接名本身不允许修改，故表单里它是只读的） */
    var originalName: String = ""
    var name: String = ""
    var kind: String = DbKind.MYSQL.id
    var description: String = ""
    var host: String = ""
    var port: String = ""
    var username: String = ""
    var password: String = ""
    var database: String = ""
    var authSource: String = ""
    var queryTimeoutSeconds: String = ""

    /**
     * @param passwordIfBlank 密码留空时要沿用的已保存密码（新增时传空串）
     * @return 数据库类型不合法时返回 null
     */
    fun toConnectionOrNull(passwordIfBlank: String): DbConnection? {
        val dbKind = DbKind.of(kind.trim().lowercase()) ?: return null
        val timeout = queryTimeoutSeconds.trim().ifBlank { null }
            ?.toIntOrNull()?.takeIf { it > 0 }
        return DbConnection(
            name = name.trim(),
            kind = dbKind,
            description = description.trim(),
            host = host.trim(),
            port = portOf(dbKind),
            username = username.trim(),
            password = password.ifBlank { passwordIfBlank },
            database = database.trim(),
            authSource = authSource.trim(),
            queryTimeoutSeconds = timeout,
        )
    }

    private fun portOf(dbKind: DbKind): Int =
        port.trim().ifBlank { dbKind.defaultPort.toString() }.toIntOrNull() ?: 0

    companion object {

        fun of(connection: DbConnection): ConnectionForm = ConnectionForm().apply {
            originalName = connection.name
            name = connection.name
            kind = connection.kind.id
            description = connection.description
            host = connection.host
            port = connection.port.toString()
            username = connection.username
            database = connection.database
            authSource = connection.authSource
            queryTimeoutSeconds = connection.queryTimeoutSeconds?.toString() ?: ""
        }
    }
}
