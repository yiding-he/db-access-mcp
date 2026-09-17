package com.hyd.dbmcp.mcp

import com.hyd.dbmcp.config.AppState
import com.hyd.dbmcp.config.ConfigStore
import com.hyd.dbmcp.config.DbConnection
import com.hyd.dbmcp.config.DbKind
import com.hyd.dbmcp.config.DbMcpProperties
import com.hyd.dbmcp.exec.CliRunner
import com.hyd.dbmcp.exec.MongoShellClient
import com.hyd.dbmcp.exec.MySqlClient
import com.hyd.dbmcp.exec.RedisCliClient
import com.hyd.dbmcp.exec.QueryService
import com.hyd.dbmcp.util.Json
import tools.jackson.databind.JsonNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * MCP 契约的单元测试：直接调 handler，不起 HTTP 服务也不连数据库。
 *
 * 被测连接一律指向 127.0.0.1 的 1 号端口（必然连不上），
 * 用来走「执行失败」分支；真连库的端到端验证是手工步骤（见 README 验收清单）。
 */
class McpHandlerTest {

    private lateinit var state: AppState

    private lateinit var handler: McpHandler

    private var sessionId: String? = null

    @TempDir
    lateinit var dir: Path

    @BeforeEach
    fun setUp() {
        val props = DbMcpProperties().apply { configPath = dir.resolve("config.data").toString() }
        val runner = CliRunner()
        state = AppState(ConfigStore(props))
        handler = McpHandler(state, QueryService(state, MySqlClient(state, props, runner), MongoShellClient(state, props, runner), RedisCliClient(state, props, runner)))
    }

    @Test
    fun `未解锁时所有方法返回 423 与 -32002`() {
        val response = handler.handle(request("initialize", 1), null, null)

        assertEquals(423, response.status)
        assertTrue(response.body!!.contains("-32002"), response.body)
        assertTrue(response.body!!.contains("mcp server not ready"), response.body)
        assertEquals(423, handler.handle(request("tools/list", 2), null, null).status)
        assertNull(response.sessionId)
    }

    @Test
    fun `initialize 返回协商版本并下发会话 id`() {
        unlock()

        val response = handler.handle(request("initialize", 7, """{"protocolVersion":"2025-06-18"}"""), null, null)
        val body = Json.parse(response.body!!)

        assertEquals(200, response.status)
        assertNotNull(response.sessionId)
        assertEquals("2025-06-18", body.path("result").path("protocolVersion").asString())
        assertEquals(7, body.path("id").asInt())
        assertTrue(body.path("result").path("serverInfo").path("name").asString().isNotBlank())
        assertTrue(body.path("result").path("capabilities").has("tools"))
        assertTrue(body.path("result").path("capabilities").has("resources"))
        assertTrue(body.path("result").path("instructions").asString().contains("db_query"))
    }

    @Test
    fun `客户端要求更低协议版本时按客户端给的回落`() {
        unlock()

        val body = Json.parse(call("initialize", 1, """{"protocolVersion":"2025-03-26"}""").body!!)

        assertEquals("2025-03-26", body.path("result").path("protocolVersion").asString())
    }

    @Test
    fun `缺少会话 id 或会话不存在时返回 404`() {
        unlock()
        val established = initialize()

        assertEquals(404, handler.handle(request("tools/list", 2), null, null).status)
        assertEquals(404, handler.handle(request("tools/list", 3), null, "not-a-session").status)
        assertEquals(200, handler.handle(request("tools/list", 4), null, established).status)
    }

    @Test
    fun `不支持的协议版本头被拒绝`() {
        unlock()
        val established = initialize()

        val response = handler.handle(request("tools/list", 2), "1999-01-01", established)

        assertEquals(400, response.status)
        assertTrue(response.body!!.contains("不支持的协议版本"), response.body)
    }

    @Test
    fun `DELETE 作废会话`() {
        unlock()
        val established = initialize()

        handler.closeSession(established)

        assertEquals(404, handler.handle(request("tools/list", 2), null, established).status)
    }

    @Test
    fun `tools list 把连接名写进 enum`() {
        unlock()

        val tool = Json.parse(call("tools/list", 3).body!!).path("result").path("tools")[0]

        assertEquals("db_query", tool.path("name").asString())
        assertFalse(tool.path("description").asString().isBlank())
        assertEquals(
            listOf("app_mysql", "app_mongo"),
            tool.path("inputSchema").path("properties").path("connection").path("enum").stringValues(),
        )
        assertEquals(listOf("connection", "statement"), tool.path("inputSchema").path("required").stringValues())
    }

    @Test
    fun `resources 列表与读取只暴露名字描述与类型`() {
        unlock()

        val listed = Json.parse(call("resources/list", 4).body!!).path("result").path("resources")
        assertEquals(listOf("dbmcp://connections"), (0 until listed.size()).map { listed.get(it).path("uri").asString() })

        val text = Json.parse(call("resources/read", 5, """{"uri":"dbmcp://connections"}""").body!!)
            .path("result").path("contents")[0].path("text").asString()

        assertTrue(text.contains("app_mysql"), text)
        assertTrue(text.contains("主库只读账号"), text)
        assertFalse(text.contains("127.0.0.1"), text)
        assertFalse(text.contains("db-secret"), text)
    }

    @Test
    fun `未知 resource 与未知工具都报参数错误`() {
        unlock()

        assertTrue(call("resources/read", 6, """{"uri":"dbmcp://nope"}""").body!!.contains("-32602"))
        assertTrue(call("tools/call", 7, """{"name":"other","arguments":{}}""").body!!.contains("-32602"))
    }

    @Test
    fun `连接名不存在时回 isError 且只有一行说明`() {
        unlock()

        val result = Json.parse(call("tools/call", 8, query("nope", "SELECT 1")).body!!).path("result")
        val text = result.path("content")[0].path("text").asString()

        assertTrue(result.path("isError").asBoolean())
        assertTrue(text.startsWith("#META "), text)
        assertTrue(text.contains("找不到连接"), text)
    }

    @Test
    fun `被闸门拒绝的语句以 isError 回给 Agent`() {
        unlock()

        val text = queryText("app_mysql", "DELETE FROM orders")

        assertTrue(text.startsWith("#META "), text)
        assertTrue(text.contains("首关键字"), text)
    }

    @Test
    fun `执行失败时只有 META 一行且带 error 字段`() {
        unlock()

        val meta = Json.parse(queryText("app_mysql", "SELECT 1").removePrefix("#META "))

        assertFalse(meta.path("ok").asBoolean())
        assertTrue(meta.path("error").asString().isNotBlank(), meta.toString())
        assertEquals("mysql", meta.path("db").asString())
        assertEquals("app_mysql", meta.path("connection").asString())
        assertEquals("SELECT 1", meta.path("statement_digest").asString())
        assertEquals(0, meta.path("rows").asInt())
    }

    @Test
    fun `通知只回 202 空体`() {
        unlock()
        val established = initialize()

        val response = handler.handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}""", null, established)

        assertEquals(202, response.status)
        assertNull(response.body)
    }

    @Test
    fun `未知方法返回 method not found`() {
        unlock()

        assertEquals(-32601, Json.parse(call("prompts/list", 9).body!!).path("error").path("code").asInt())
    }

    @Test
    fun `非法 JSON 与批量请求都属无效请求`() {
        unlock()

        assertEquals(-32700, Json.parse(handler.handle("{ not json", null, null).body!!).path("error").path("code").asInt())
        val batch = handler.handle("""[{"jsonrpc":"2.0","id":1,"method":"ping"}]""", null, initialize())
        assertEquals(-32600, Json.parse(batch.body!!).path("error").path("code").asInt())
    }

    @Test
    fun `改密后旧会话全部失效`() {
        unlock()
        val established = initialize()

        state.changePassword(PASSWORD, NEW_PASSWORD)

        assertEquals(404, handler.handle(request("tools/list", 2), null, established).status)
    }

    private fun initialize(): String {
        val response = handler.handle(request("initialize", 1), null, null)
        val established = response.sessionId ?: error("initialize 未下发会话 id：${response.body}")
        sessionId = established
        handler.handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}""", null, established)
        return established
    }

    private fun call(method: String, id: Int, params: String? = null): McpResponse =
        handler.handle(request(method, id, params), null, sessionId ?: initialize())

    private fun query(connection: String, statement: String): String {
        val params = Json.obj()
        params.put("name", "db_query")
        val arguments = params.putObject("arguments")
        arguments.put("connection", connection)
        arguments.put("statement", statement)
        return Json.write(params)
    }

    private fun queryText(connection: String, statement: String): String =
        Json.parse(call("tools/call", 98, query(connection, statement)).body!!)
            .path("result").path("content")[0].path("text").asString()

    private fun request(method: String, id: Int, params: String? = null): String {
        val node = Json.obj()
        node.put("jsonrpc", "2.0")
        node.put("id", id)
        node.put("method", method)
        if (params != null) {
            node.set("params", Json.parse(params))
        }
        return Json.write(node)
    }

    /**
     * Jackson 3 的 JsonNode 自带 map 成员方法（语义是「生成新数组」），会盖掉 Kotlin 的 Iterable.map，
     * 所以数组取值守卫用下标，不在 JsonNode 上调用 map/forEach。
     */
    private fun JsonNode.stringValues(): List<String> =
        (0 until size()).map { get(it).asString() }

    private fun unlock() {
        state.setup(PASSWORD)
        state.saveConnection(
            DbConnection("app_mysql", DbKind.MYSQL, "主库只读账号", "127.0.0.1", 1, "reader", "db-secret", "app", ""),
            null,
        )
        state.saveConnection(
            DbConnection("app_mongo", DbKind.MONGODB, "订单库", "127.0.0.1", 1, "", "", "orders", ""),
            null,
        )
    }

    companion object {

        private const val PASSWORD = "unit-test-passphrase"

        private const val NEW_PASSWORD = "unit-test-passphrase-2"
    }
}
