package com.hyd.dbmcp.exec

import com.hyd.dbmcp.config.AppState
import com.hyd.dbmcp.config.DbConnection
import com.hyd.dbmcp.config.DbMcpProperties
import org.springframework.stereotype.Component

/**
 * 通过本机 `mysql` 命令执行查询。
 *
 * 实测要点（mysql 8.4.8 客户端）：
 * - 没有 `--json` 输出选项，用 `-B -t` 拿 ASCII 表格，且表格模式下每个数据行严格一行；
 * - 0 行结果时 stdout 完全为空（连表头都没有），所以「空结果」= 退出码 0 + 无输出；
 * - 密码走 `MYSQL_PWD` 环境变量，语句走 stdin，两者都不出现在进程命令行里；
 * - 必须显式 `--default-character-set=utf8mb4`：否则客户端默认字符集跟随系统代码页（中文 Windows 上实测
 *   `@@character_set_results = gbk`），结果集以 GBK 字节回传，而我们按 UTF-8 解码，中文就变成一串 U+FFFD；
 * - `--init-command="SET SESSION TRANSACTION READ ONLY"` 让服务端直接拒绝写操作（ERROR 1792）。
 */
@Component
class MySqlClient(
    private val state: AppState,
    private val props: DbMcpProperties,
    private val runner: CliRunner,
) {

    fun execute(connection: DbConnection, statement: String): CliResult = runner.run(
        CliRequest(
            label = "mysql:${connection.name}",
            command = command(connection),
            environment = mapOf("MYSQL_PWD" to connection.password),
            stdin = "$statement\n",
            rowCounter = RowCounters.MYSQL_TABLE,
            maxRows = props.maxRows,
            maxOutputBytes = props.maxOutputBytes,
            timeoutSeconds = connection.queryTimeoutSeconds?.toLong() ?: props.queryTimeoutSeconds,
        ),
    )

    private fun command(connection: DbConnection): List<String> = buildList {
        add(state.requireUnlocked().global.mysqlBinary ?: "mysql")
        add("-h"); add(connection.host)
        add("-P"); add(connection.port.toString())
        add("-u"); add(connection.username)
        if (connection.database.isNotBlank()) {
            add("-D"); add(connection.database)
        }
        add("--connect-timeout=${props.connectTimeoutSeconds}")
        add("--default-character-set=utf8mb4")
        add("--local-infile=0")
        add("--init-command=SET SESSION TRANSACTION READ ONLY")
        add("-B")
        add("-t")
    }
}
