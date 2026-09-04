package com.hyd.dbmcp.exec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
/**
 * 子进程执行器的上限与环境隔离验证。
 *
 * 用 Windows 自带命令做被测进程（findstr 逐行回显文件内容、cmd 读环境变量），
 * 不依赖 mysql/mongosh 是否可用，也不需要网络。
 */
class CliRunnerTest {

    private val runner = CliRunner()

    @Test
    fun `stdout 原样收集，stdin 能送进子进程`() {
        val file = writeTable(3)

        val result = runner.run(request(listOf("findstr", ".", file.absolutePath)))

        assertTrue(result.succeeded, "stderr=${result.stderr}")
        assertEquals(3, result.rows)
        assertEquals(listOf("| c1 | c2 |", "| 1 | v1 |", "| 2 | v2 |", "| 3 | v3 |"), result.lines)
    }

    @Test
    fun `超过行数上限即截断并终止进程`() {
        val file = writeTable(500)

        val result = runner.run(request(listOf("findstr", ".", file.absolutePath), maxRows = 100))

        assertTrue(result.truncated)
        assertEquals(100, result.rows)
        assertEquals(102, result.lines.size, "表头 + 100 行数据 + 1 行截断说明")
        assertTrue(result.lines.last().startsWith("#"), result.lines.last())
    }

    @Test
    fun `超过字节上限即截断`() {
        val file = tempDir.toPath().resolve("wide.txt").toFile()
        file.writeText((1..200).joinToString("\n") { "| ${"%04d".format(it)} | ${"x".repeat(200)} |" } + "\n", Charsets.UTF_8)

        val result = runner.run(request(listOf("findstr", ".", file.absolutePath), maxOutputBytes = 2_000))

        assertTrue(result.truncated)
        assertTrue(result.outputBytes <= 2_000, "实际 ${result.outputBytes} 字节")
        assertTrue(result.lines.last().startsWith("#"), result.lines.last())
    }

    @Test
    fun `子进程私有环境变量不影响其它进程`() {
        val first = runner.run(
            request(cmdEcho("DBMCP_PROBE"), environment = mapOf("DBMCP_PROBE" to "value-one")),
        )
        val second = runner.run(
            request(cmdEcho("DBMCP_PROBE"), environment = mapOf("DBMCP_PROBE" to "value-two")),
        )
        val without = runner.run(request(cmdEcho("DBMCP_PROBE")))

        assertTrue(first.stdoutText().contains("value-one"), first.stdoutText())
        assertTrue(second.stdoutText().contains("value-two"), second.stdoutText())
        assertFalse(without.stdoutText().contains("value"), without.stdoutText())
    }

    @Test
    fun `非零退出码被如实带回`() {
        val result = runner.run(request(listOf("cmd", "/c", "exit", "7")))

        assertEquals(7, result.exitCode)
        assertFalse(result.succeeded)
    }

    @Test
    fun `超时后终止进程并标记`() {
        val result = runner.run(
            request(listOf("cmd", "/c", "ping", "-n", "20", "127.0.0.1"), timeoutSeconds = 1),
        )

        assertTrue(result.timedOut)
        assertFalse(result.succeeded)
        assertTrue(result.elapsedMs < 8_000, "耗时 ${result.elapsedMs}ms，说明没等到进程自己跑完")
    }

    @Test
    fun `被测命令不存在时失败原因里带命令名`() {
        val failure = runCatching { runner.run(request(listOf("db-access-mcp-no-such-binary-xyz"))) }

        val exception = failure.exceptionOrNull()
        assertTrue(exception is java.io.IOException, "$exception")
        assertTrue(exception!!.message!!.contains("db-access-mcp-no-such-binary-xyz"), exception.message)
    }

    private fun request(
        command: List<String>,
        environment: Map<String, String> = emptyMap(),
        stdin: String? = null,
        maxRows: Int = 10_000,
        maxOutputBytes: Int = 1_000_000,
        timeoutSeconds: Long = 20,
    ) = CliRequest(
        label = "test",
        command = command,
        environment = environment,
        stdin = stdin,
        rowCounter = RowCounters.MYSQL_TABLE,
        maxRows = maxRows,
        maxOutputBytes = maxOutputBytes,
        timeoutSeconds = timeoutSeconds,
    )

    private fun cmdEcho(variable: String) = listOf("cmd", "/c", "echo %$variable%")

    /** 造一份 MySQL `-B -t` 形状的 ASCII 表格：首行是表头，所以数据行数比 `|` 行数少 1 */
    private fun writeTable(dataRows: Int): File {
        val file = tempDir.toPath().resolve("rows.txt").toFile()
        val lines = ArrayList<String>(dataRows + 1)
        lines.add("| c1 | c2 |")
        for (i in 1..dataRows) {
            lines.add("| $i | v$i |")
        }
        file.writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
        return file
    }

    private fun CliResult.stdoutText(): String = lines.joinToString("\n")

    companion object {

        @TempDir
        lateinit var tempDir: File
    }
}
