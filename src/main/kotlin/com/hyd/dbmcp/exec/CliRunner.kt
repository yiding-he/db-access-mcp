package com.hyd.dbmcp.exec

import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.springframework.stereotype.Component

/** 把 CLI 的 stdout 行换算成「数据行数」，两种数据库的输出形状不同 */
fun interface RowCounter {

    fun count(lines: List<String>): Int
}

/** MySQL `-B -t` 的 ASCII 表格：以 `|` 开头的行里第一行是表头，其余是数据行 */
object RowCounters {

    val MYSQL_TABLE = RowCounter { lines ->
        (lines.count { it.startsWith("|") } - 1).coerceAtLeast(0)
    }

    /** 每行一条记录（MongoDB 的 EJSON 输出），`#` 开头的是我们自己的注释行，不计入 */
    val NON_BLANK_LINES = RowCounter { lines ->
        lines.count { it.isNotBlank() && !it.startsWith("#") }
    }
}

class CliRequest(
    val label: String,
    val command: List<String>,
    /** 追加进子进程私有环境变量的键值（密码只走这里，不进 command line） */
    val environment: Map<String, String> = emptyMap(),
    val stdin: String? = null,
    val rowCounter: RowCounter,
    val maxRows: Int,
    val maxOutputBytes: Int,
    val timeoutSeconds: Long,
)

class CliResult(
    val exitCode: Int,
    val lines: List<String>,
    val stderr: String,
    val rows: Int,
    val truncated: Boolean,
    val timedOut: Boolean,
    val outputBytes: Int,
    val elapsedMs: Long,
) {
    val succeeded: Boolean get() = !timedOut && (exitCode == 0 || (truncated && stderr.isBlank()))
}

/**
 * 子进程执行器：密码等敏感值只通过子进程私有环境变量传递（不同连接天然互不覆盖，
 * 因为 ProcessBuilder 的 environment 是子进程各自的副本），并施加超时、行数、字节三重上限。
 *
 * 任何一条上限被触碰就立即 destroyForcibly，避免大结果集把时间耗在传输上。
 */
@Component
class CliRunner {

    fun run(request: CliRequest): CliResult {
        val startedAt = System.nanoTime()
        val builder = ProcessBuilder(request.command)
        request.environment.forEach { (key, value) -> builder.environment()[key] = value }
        val process = builder.start()
        val stdout = Accumulator(request.rowCounter, request.maxRows, request.maxOutputBytes)
        val stderr = StringBuilder()

        val stdinThread = thread(isDaemon = true, name = "${request.label}-stdin") {
            runCatching {
                process.outputStream.use { it.write((request.stdin ?: "").toByteArray(Charsets.UTF_8)) }
            }
        }
        val stderrThread = thread(isDaemon = true, name = "${request.label}-stderr") {
            runCatching {
                process.errorStream.bufferedReader(Charsets.UTF_8).useLines { sequences ->
                    sequences.forEach { line ->
                        synchronized(stderr) {
                            if (stderr.length < MAX_ERROR_CHARS) {
                                stderr.append(line).append('\n')
                            }
                        }
                    }
                }
            }
        }
        val stdoutThread = thread(isDaemon = true, name = "${request.label}-stdout") {
            runCatching {
                process.inputStream.bufferedReader(Charsets.UTF_8).useLines reader@{ lines ->
                    for (line in lines) {
                        if (!stdout.accept(line)) {
                            process.destroyForcibly()
                            return@reader
                        }
                    }
                }
            }
        }

        val finished = process.waitFor(request.timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
        }
        val exitCode = if (finished) process.waitFor() else -1
        stdinThread.join(TERMINAL_JOIN_MS)
        stdoutThread.join(TERMINAL_JOIN_MS)
        stderrThread.join(TERMINAL_JOIN_MS)

        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        val snapshot = stdout.snapshot()
        return CliResult(
            exitCode = exitCode,
            lines = snapshot.lines,
            stderr = synchronized(stderr) { stderr.toString().trimEnd() },
            rows = snapshot.rows,
            truncated = snapshot.truncated,
            timedOut = !finished,
            outputBytes = snapshot.bytes,
            elapsedMs = elapsedMs,
        )
    }

    private class Accumulator(
        private val rowCounter: RowCounter,
        private val maxRows: Int,
        private val maxOutputBytes: Int,
    ) {

        private val lines = mutableListOf<String>()

        private var bytes = 0

        private var truncated = false

        @Synchronized
        fun accept(line: String): Boolean {
            val lineBytes = line.toByteArray(Charsets.UTF_8).size + 1
            if (bytes + lineBytes > maxOutputBytes) {
                truncated = true
                lines += "# 输出已达上限 ${maxOutputBytes} 字节，后续内容被丢弃"
                return false
            }
            bytes += lineBytes
            lines += line
            if (rowCounter.count(lines) > maxRows) {
                lines.removeAt(lines.size - 1)
                bytes -= lineBytes
                truncated = true
                lines += "# 结果行数已达上限 $maxRows，后续内容被丢弃"
                return false
            }
            return true
        }

        @Synchronized
        fun snapshot(): Snapshot = Snapshot(lines.toList(), rowCounter.count(lines), truncated, bytes)

        class Snapshot(val lines: List<String>, val rows: Int, val truncated: Boolean, val bytes: Int)
    }

    companion object {

        private const val MAX_ERROR_CHARS = 4_000

        private const val TERMINAL_JOIN_MS = 3_000L
    }
}
