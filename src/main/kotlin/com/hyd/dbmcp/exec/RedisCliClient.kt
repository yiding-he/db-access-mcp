package com.hyd.dbmcp.exec

import com.hyd.dbmcp.config.AppState
import com.hyd.dbmcp.config.DbConnection
import com.hyd.dbmcp.config.DbMcpProperties
import org.springframework.stereotype.Component

/**
 * 通过本机 `redis-cli` 命令执行查询。
 *
 * 实例形态：
 * - 整条语句经 RedisStatementArgv 校验后按空白拆成 argv，直接进命令行：
 *   `redis-cli -h <host> -p <port> [-n <db>] [--user <username>] GET user:1`；
 *   redis-cli 把 argv 首位当命令、其后当参数，多余语句没有注入口。
 * - 密码走 `REDISCLI_AUTH` 环境变量（官方支持），不出现在 argv、不落临时文件；
 *   留空即无认证（很多内网实例不设密码）。
 * - `--raw` 让每个返回元素独占一行（去掉默认输出的编号前缀），行数计数才有意义。
 * - database 字段存放 Redis DB 编号，对应 `-n`；留空即默认 DB 0。
 */
@Component
class RedisCliClient(
    private val state: AppState,
    private val props: DbMcpProperties,
    private val runner: CliRunner,
) {

    fun execute(connection: DbConnection, statement: String): CliResult = runner.run(
        CliRequest(
            label = "redis-cli:${connection.name}",
            command = command(connection, RedisStatementArgv.split(statement)),
            environment = environment(connection),
            rowCounter = RowCounters.NON_BLANK_LINES,
            maxRows = props.maxRows,
            maxOutputBytes = props.maxOutputBytes,
            timeoutSeconds = connection.queryTimeoutSeconds?.toLong() ?: props.queryTimeoutSeconds,
        ),
    )

    private fun command(connection: DbConnection, args: List<String>): List<String> {
        val binary = state.requireUnlocked().global.redisBinary ?: "redis-cli"
        val argv = mutableListOf(binary)
        argv += listOf("-h", connection.host)
        argv += listOf("-p", connection.port.toString())
        if (connection.database.isNotBlank()) {
            argv += listOf("-n", connection.database)
        }
        if (connection.username.isNotBlank()) {
            argv += listOf("--user", connection.username)
        }
        argv += listOf("--raw")
        argv += args
        return argv
    }

    private fun environment(connection: DbConnection): Map<String, String> =
        if (connection.password.isBlank()) emptyMap() else mapOf(ENV_AUTH to connection.password)

    companion object {

        const val ENV_AUTH = "REDISCLI_AUTH"
    }
}

/**
 * Redis 语句的 argv 分词：按空白拆分，单引号/双引号内的空白是字面量，
 * 与闸门共用同一套拆分结果（先校验后拆分，两次拆分结果必然一致）。
 */
object RedisStatementArgv {

    fun split(statement: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var index = 0
        while (index < statement.length) {
            val ch = statement[index]
            when {
                ch.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        tokens += current.toString()
                        current.clear()
                    }
                    index++
                }

                ch == '\'' || ch == '"' -> {
                    index++
                    while (index < statement.length && statement[index] != ch) {
                        current.append(statement[index])
                        index++
                    }
                    index++ // 右引号
                }

                else -> {
                    current.append(ch)
                    index++
                }
            }
        }
        if (current.isNotEmpty()) {
            tokens += current.toString()
        }
        return tokens
    }
}
