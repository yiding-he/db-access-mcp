package com.hyd.dbmcp.exec

import com.hyd.dbmcp.config.AppState
import com.hyd.dbmcp.config.DbConnection
import com.hyd.dbmcp.config.DbKind
import com.hyd.dbmcp.util.Json
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 一次查询的完整结果，`toResponseText()` 产出用户约定的形态：
 * 第一行是 `#META` 的 JSON 元信息，之后是 CLI 原样输出。
 */
class QueryReport(
    val ok: Boolean,
    val connection: String,
    val kind: DbKind?,
    val rows: Int,
    val truncated: Boolean,
    val elapsedMs: Long,
    val outputBytes: Int,
    val body: String,
    val error: String?,
    val statementDigest: String,
) {

    fun toResponseText(): String {
        val meta = Json.obj()
        meta.put("ok", ok)
        meta.put("connection", connection)
        meta.put("db", kind?.id ?: "")
        meta.put("rows", rows)
        meta.put("truncated", truncated)
        meta.put("elapsedMs", elapsedMs)
        meta.put("outputBytes", outputBytes)
        meta.put("statement_digest", statementDigest)
        error?.let { meta.put("error", it) }
        val prefix = "#META ${Json.write(meta)}"
        return if (ok && body.isNotBlank()) "$prefix\n$body" else prefix
    }
}

/**
 * 查询的统一入口：连接查找 → 只读闸门 → 按类型分派给 CLI → 组装结果。
 *
 * 所有失败（连接不存在、闸门拒绝、CLI 报错、超时）都以 QueryReport 返回，不抛异常，
 * 由调用方（MCP 层）决定怎么回给 Agent。
 */
@Service
class QueryService(
    private val state: AppState,
    private val mySqlClient: MySqlClient,
    private val mongoClient: MongoShellClient,
) {

    private val log = LoggerFactory.getLogger(QueryService::class.java)

    fun query(connectionName: String, rawStatement: String): QueryReport {
        val connection = state.connections().firstOrNull { it.name == connectionName }
            ?: return failure(connectionName, null, rawStatement, 0, "找不到连接「$connectionName」，可用连接见 resources：dbmcp://connections")
        return when (val outcome = StatementGuard.check(connection.kind, rawStatement)) {
            is GuardOutcome.Rejected -> failure(connectionName, connection.kind, rawStatement, 0, outcome.reason)
            is GuardOutcome.Allowed -> execute(connection, outcome.statement)
        }
    }

    /** 管理页测试连接：执行固定只读探测语句，跳过闸门（语句由代码写死） */
    fun ping(connection: DbConnection): QueryReport = execute(connection, probeStatement(connection.kind))

    private fun execute(connection: DbConnection, statement: String): QueryReport {
        val result = runCatching {
            when (connection.kind) {
                DbKind.MYSQL -> mySqlClient.execute(connection, statement)
                DbKind.MONGODB -> mongoClient.execute(connection, statement)
            }
        }.getOrElse { e ->
            log.error("启动 {} 命令失败，连接={}", connection.kind.id, connection.name, e)
            return failure(connection.name, connection.kind, statement, 0, "无法启动 ${connection.kind.label} 命令行工具：${e.message}")
        }
        val error = when {
            result.timedOut -> "执行超时，进程已被终止"
            !result.succeeded -> result.stderr.ifBlank { "${connection.kind.label} 命令返回退出码 ${result.exitCode}" }
            else -> null
        }
        val report = QueryReport(
            ok = error == null,
            connection = connection.name,
            kind = connection.kind,
            rows = result.rows,
            truncated = result.truncated,
            elapsedMs = result.elapsedMs,
            outputBytes = result.outputBytes,
            body = result.lines.joinToString("\n"),
            error = error,
            statementDigest = digest(statement),
        )
        log.info(
            "查询 connection={} db={} rows={} truncated={} elapsedMs={} ok={} statement={}",
            report.connection, report.kind?.id, report.rows, report.truncated, report.elapsedMs,
            report.ok, digest(statement, LOG_DIGEST_CHARS),
        )
        error?.let { log.info("  └ 失败原因：{}", it.replace("\n", " | ")) }
        return report
    }

    private fun failure(
        connection: String,
        kind: DbKind?,
        statement: String,
        elapsedMs: Long,
        reason: String,
    ) = QueryReport(
        ok = false,
        connection = connection,
        kind = kind,
        rows = 0,
        truncated = false,
        elapsedMs = elapsedMs,
        outputBytes = 0,
        body = "",
        error = reason,
        statementDigest = digest(statement),
    )

    private fun probeStatement(kind: DbKind): String = when (kind) {
        DbKind.MYSQL -> "SELECT 1"
        DbKind.MONGODB -> "db.runCommand({ping: 1})"
    }

    private fun digest(statement: String, limit: Int = DIGEST_CHARS): String =
        statement.replace(Regex("\\s+"), " ").trim().let {
            if (it.length <= limit) it else it.take(limit) + "…"
        }

    companion object {

        /** 回给 Agent 的语句摘要长度 */
        private const val DIGEST_CHARS = 200

        /** 落日志的语句摘要长度 */
        private const val LOG_DIGEST_CHARS = 500
    }
}
