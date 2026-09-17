package com.hyd.dbmcp.exec

import com.hyd.dbmcp.config.AppState
import com.hyd.dbmcp.config.DbConnection
import com.hyd.dbmcp.config.DbMcpProperties
import java.nio.file.Files
import java.nio.file.Path
import org.springframework.stereotype.Component

/**
 * 通过本机 `mongosh` 命令执行查询。
 *
 * 实测要点（mongosh 2.6.0）：
 * - `--nodb` 下 `connect(uri)` 返回的是 Database 对象，因此脚本里直接 `var db = connect(...)`；
 * - 语句必须内联进脚本源码：mongosh 只对源码做 async-rewriter 变换，链式游标写法（`find({}).limit(1)`）
 *   在源码里可用，放进运行时 `eval()` 就报 `... .limit is not a function`；
 * - 语句走 stdin 会进 REPL，输出里混进 `> ` 与表达式回显，不可用；`--file <脚本>` 输出才干净；
 * - `--json` 只能配 `--eval`，与 `--file` 互斥，所以文档内容由脚本自己 `EJSON.stringify` 逐行打印；
 * - 连接串放子进程环境变量：密钥既不进 argv（同机其它用户可读进程命令行），也不落临时文件；
 *   临时脚本由 Java 创建（POSIX 下仅属主可读），执行完立即删除。
 */
@Component
class MongoShellClient(
    private val state: AppState,
    private val props: DbMcpProperties,
    private val runner: CliRunner,
) {

    private val template: String by lazy { loadTemplate() }

    fun execute(connection: DbConnection, statement: String): CliResult {
        val script = writeScript(statement)
        try {
            return runner.run(
                CliRequest(
                    label = "mongosh:${connection.name}",
                    command = listOf(
                        state.requireUnlocked().global.mongoshBinary ?: "mongosh",
                        "--nodb", "--quiet", "--norc", "--file", script.toString(),
                    ),
                    environment = mapOf(ENV_URI to connection.mongoUri()),
                    rowCounter = RowCounters.NON_BLANK_LINES,
                    maxRows = props.maxRows,
                    maxOutputBytes = props.maxOutputBytes,
                    timeoutSeconds = connection.queryTimeoutSeconds?.toLong() ?: props.queryTimeoutSeconds,
                ),
            )
        } finally {
            Files.deleteIfExists(script)
        }
    }

    /**
     * 把 jar 内模板的占位符替换成待执行语句，写成一次性脚本。
     * 语句已经过 StatementGuard 校验（必须是单个从 db 开始的只读表达式），所以内联进源码不会引入额外出口。
     */
    private fun writeScript(statement: String): Path =
        Files.createTempFile("db-access-mcp-mongo-", ".js").also {
            Files.writeString(it, template.replace(STATEMENT_MARKER, "($statement)"), Charsets.UTF_8)
        }

    private fun loadTemplate(): String {
        javaClass.getResourceAsStream(SCRIPT_RESOURCE).use { input ->
            requireNotNull(input) { "找不到脚本资源 $SCRIPT_RESOURCE" }
            return String(input.readBytes(), Charsets.UTF_8)
        }
    }

    companion object {

        private const val SCRIPT_RESOURCE = "/scripts/mongo_run.js"

        private const val STATEMENT_MARKER = "__DBMCP_STATEMENT__"

        const val ENV_URI = "DBMCP_MONGO_URI"
    }
}
