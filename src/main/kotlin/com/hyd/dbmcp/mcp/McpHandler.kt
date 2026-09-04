package com.hyd.dbmcp.mcp

import com.hyd.dbmcp.BuildInfo
import com.hyd.dbmcp.config.AppState
import com.hyd.dbmcp.config.NotReadyException
import com.hyd.dbmcp.exec.QueryService
import com.hyd.dbmcp.util.Json
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** MCP 的 HTTP 响应：状态码 + 可选 JSON-RPC 正文 + 需要回给客户端的会话 id */
class McpResponse(
    val status: Int,
    val body: String?,
    val sessionId: String? = null,
)

/**
 * MCP over Streamable HTTP 的最小实现（不引官方 Java SDK：本项目只需一个只读 tool，
 * 而 SDK 会多带 6~10 个 jar）。
 *
 * 覆盖 initialize / ping / tools / resources；一次 POST 处理一条 JSON-RPC 请求；
 * 服务端除会话 id 之外不保存任何状态。
 */
@Component
class McpHandler(
    private val state: AppState,
    private val queryService: QueryService,
) {

    private val log = LoggerFactory.getLogger(McpHandler::class.java)

    /** 会话 id → 当时的解锁代次；代次变了（重新解锁或改密）旧会话即失效 */
    private val sessions = ConcurrentHashMap<String, Long>()

    fun handle(body: String, protocolVersionHeader: String?, sessionIdHeader: String?): McpResponse {
        val request = try {
            Json.parse(body)
        } catch (e: RuntimeException) {
            return response(400, error(null, CODE_PARSE_ERROR, "请求体不是合法 JSON：${e.message}"))
        }
        if (!request.isObject) {
            return response(400, error(null, CODE_INVALID_REQUEST, "请求必须是单条 JSON-RPC 对象，不支持批量数组"))
        }
        val method = request.path("method").asString("")
        val id = request.get("id")?.takeIf { !it.isNull }
        val isNotification = id == null

        if (!state.isUnlocked()) {
            return notReady(id, isNotification)
        }
        if (method == METHOD_INITIALIZE) {
            return initialize(id, request.path("params").path("protocolVersion").asString(""))
        }
        checkSession(id, protocolVersionHeader, sessionIdHeader)?.let { return it }

        return when {
            method == METHOD_PING -> response(200, result(id, Json.obj()))
            method == METHOD_TOOLS_LIST -> response(200, result(id, toolsList()))
            method == METHOD_TOOLS_CALL -> toolCall(id, request.path("params"))
            method == METHOD_RESOURCES_LIST -> response(200, result(id, resourcesList()))
            method == METHOD_RESOURCES_READ -> resourceRead(id, request.path("params"))
            method.startsWith("notifications/") -> McpResponse(202, null)
            else -> response(
                if (isNotification) 202 else 404,
                if (isNotification) null else error(id, CODE_METHOD_NOT_FOUND, "未知方法：$method"),
            )
        }
    }

    /** 管理界面之外没有可配置的版本号，直接用 Boot 的 build version */
    private fun serverVersion(): String = BuildInfo.version

    private fun initialize(id: JsonNode?, requestedVersion: String): McpResponse {
        // 客户端要求的版本在本服务器支持集合内就回显它，否则回最新支持版本
        val agreed = requestedVersion.takeIf { it in SUPPORTED_PROTOCOL_VERSIONS } ?: LATEST_PROTOCOL_VERSION
        val result = Json.obj()
        result.put("protocolVersion", agreed)
        val capabilities = result.putObject("capabilities")
        capabilities.putObject("tools").put("listChanged", false)
        capabilities.putObject("resources").put("listChanged", false).put("subscribe", false)
        val info = result.putObject("serverInfo")
        info.put("name", "db-access-mcp")
        info.put("version", serverVersion())
        result.put("instructions", INSTRUCTIONS)
        val sessionId = UUID.randomUUID().toString()
        sessions[sessionId] = state.generation
        log.info("MCP initialize：客户端请求协议版本={}，协商为={}", requestedVersion.ifBlank { "(未提供)" }, agreed)
        return McpResponse(200, Json.write(result(id, result)), sessionId)
    }

    /** DELETE /mcp：作废会话，服务端没有其它需要清理的状态 */
    fun closeSession(sessionId: String?) {
        if (sessionId != null) {
            sessions.remove(sessionId)
        }
    }

    private fun checkSession(
        id: JsonNode?,
        protocolVersionHeader: String?,
        sessionIdHeader: String?,
    ): McpResponse? {
        if (protocolVersionHeader != null && protocolVersionHeader !in SUPPORTED_PROTOCOL_VERSIONS) {
            return response(
                400,
                error(id, CODE_INVALID_REQUEST, "不支持的协议版本：$protocolVersionHeader"),
            )
        }
        val sessionId = sessionIdHeader ?: ""
        val generation = sessions[sessionId]
        if (sessionId.isEmpty() || generation == null) {
            return response(404, error(id, CODE_UNKNOWN_SESSION, "缺少或已失效的 Mcp-Session-Id，请重新 initialize"))
        }
        if (generation != state.generation) {
            sessions.remove(sessionId)
            return response(404, error(id, CODE_UNKNOWN_SESSION, "配置已重新解锁或改密，会话失效，请重新 initialize"))
        }
        return null
    }

    private fun toolCall(id: JsonNode?, params: JsonNode): McpResponse {
        val toolName = params.path("name").asString("")
        if (toolName != TOOL_NAME) {
            return response(200, error(id, CODE_INVALID_PARAMS, "未知工具：$toolName，本服务器只有 $TOOL_NAME"))
        }
        val arguments = params.path("arguments")
        val connection = arguments.path("connection").asString("")
        val statement = arguments.path("statement").asString("")
        if (connection.isBlank() || statement.isBlank()) {
            return response(200, error(id, CODE_INVALID_PARAMS, "参数 connection 与 statement 都不能为空"))
        }
        val report = try {
            queryService.query(connection, statement)
        } catch (e: NotReadyException) {
            return notReady(id, id == null)
        } catch (e: RuntimeException) {
            log.error("查询执行异常", e)
            return response(200, result(id, toolText("查询执行出现内部错误：${e.message}", true)))
        }
        return response(200, result(id, toolText(report.toResponseText(), !report.ok)))
    }

    private fun toolsList(): ObjectNode {
        val result = Json.obj()
        val tool = Json.obj()
        tool.put("name", TOOL_NAME)
        tool.put("description", "在指定的数据库连接上执行一条只读查询。返回文本的第一行是 #META 开头的 JSON 元信息，之后是查询结果原文。")
        val schema = tool.putObject("inputSchema")
        schema.put("type", "object")
        val properties = schema.putObject("properties")
        val connection = properties.putObject("connection")
        connection.put("type", "string")
        connection.put("description", "数据库连接配置名，取值来自 resource $RESOURCE_CONNECTIONS")
        val enumValues = connection.putArray("enum")
        state.connections().forEach { enumValues.add(it.name) }
        val statement = properties.putObject("statement")
        statement.put("type", "string")
        statement.put("description", "MySQL 传只读 SQL（可含 UNION/子查询）；MongoDB 传单个 db.<集合>.<只读方法>(...) 表达式")
        schema.putArray("required").add("connection").add("statement")
        result.putArray("tools").add(tool)
        return result
    }

    private fun resourcesList(): ObjectNode {
        val result = Json.obj()
        val resource = Json.obj()
        resource.put("uri", RESOURCE_CONNECTIONS)
        resource.put("name", "connections")
        resource.put("title", "数据库连接列表")
        resource.put("description", "所有可用连接配置的名字、描述与数据库类型（不含地址与任何凭据）")
        resource.put("mimeType", "application/json")
        result.putArray("resources").add(resource)
        return result
    }

    private fun resourceRead(id: JsonNode?, params: JsonNode): McpResponse {
        val uri = params.path("uri").asString("")
        if (uri != RESOURCE_CONNECTIONS) {
            return response(200, error(id, CODE_INVALID_PARAMS, "未知 resource：$uri，可用值为 $RESOURCE_CONNECTIONS"))
        }
        val contents = Json.obj()
        contents.put("uri", uri)
        contents.put("mimeType", "application/json")
        val list = Json.arr()
        state.connections().forEach { list.add(it.toPublicJson()) }
        contents.put("text", Json.write(list))
        val result = Json.obj()
        result.putArray("contents").add(contents)
        return response(200, result(id, result))
    }

    private fun notReady(id: JsonNode?, isNotification: Boolean): McpResponse =
        if (isNotification) {
            McpResponse(423, null)
        } else {
            response(423, error(id, CODE_NOT_READY, NOT_READY_MESSAGE))
        }

    private fun toolText(text: String, isError: Boolean): ObjectNode {
        val result = Json.obj()
        val content = Json.obj()
        content.put("type", "text")
        content.put("text", text)
        result.putArray("content").add(content)
        result.put("isError", isError)
        return result
    }

    private fun response(status: Int, payload: ObjectNode?): McpResponse =
        McpResponse(status, payload?.let { Json.write(it) })

    private fun result(id: JsonNode?, payload: ObjectNode): ObjectNode {
        val envelope = Json.obj()
        envelope.put("jsonrpc", JSONRPC_VERSION)
        id?.let { envelope.set("id", it) }
        envelope.set("result", payload)
        return envelope
    }

    private fun error(id: JsonNode?, code: Int, message: String): ObjectNode {
        val envelope = Json.obj()
        envelope.put("jsonrpc", JSONRPC_VERSION)
        if (id == null) envelope.putNull("id") else envelope.set("id", id)
        val errorNode = envelope.putObject("error")
        errorNode.put("code", code)
        errorNode.put("message", message)
        return envelope
    }

    companion object {

        const val LATEST_PROTOCOL_VERSION = "2025-06-18"

        const val FALLBACK_PROTOCOL_VERSION = "2025-03-26"

        val SUPPORTED_PROTOCOL_VERSIONS = setOf(LATEST_PROTOCOL_VERSION, FALLBACK_PROTOCOL_VERSION)

        const val TOOL_NAME = "db_query"

        const val RESOURCE_CONNECTIONS = "dbmcp://connections"

        const val NOT_READY_MESSAGE = "mcp server not ready"

        const val METHOD_INITIALIZE = "initialize"

        const val METHOD_PING = "ping"

        const val METHOD_TOOLS_LIST = "tools/list"

        const val METHOD_TOOLS_CALL = "tools/call"

        const val METHOD_RESOURCES_LIST = "resources/list"

        const val METHOD_RESOURCES_READ = "resources/read"

        const val CODE_PARSE_ERROR = -32700

        const val CODE_INVALID_REQUEST = -32600

        const val CODE_METHOD_NOT_FOUND = -32601

        const val CODE_INVALID_PARAMS = -32602

        const val CODE_UNKNOWN_SESSION = -32001

        /** 未解锁（配置密码尚未输入）时所有方法都返回这个错误码 */
        const val CODE_NOT_READY = -32002

        const val INSTRUCTIONS =
            "本服务器提供只读数据库查询。先读 resource dbmcp://connections 了解可用连接，再调用 db_query(connection, statement)。" +
                "MySQL 的 statement 是只读 SQL，首关键字必须是 SELECT/SHOW/DESC/DESCRIBE/EXPLAIN/WITH（允许 (SELECT ...) UNION (SELECT ...) 这类组合写法）；" +
                "MongoDB 的 statement 是单个只读表达式，如 db.orders.find({status: 1}).limit(20)。" +
                "写入类与文件读写类语句会被拒绝。返回文本第一行是 #META 的 JSON 元信息（含 ok/rows/truncated/error）。"

        private const val JSONRPC_VERSION = "2.0"
    }
}
