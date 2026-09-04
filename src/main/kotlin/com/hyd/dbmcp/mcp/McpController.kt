package com.hyd.dbmcp.mcp

import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.nio.charset.StandardCharsets

/**
 * MCP 的 HTTP 入口。只用手写的最小契约：
 * POST 收单条 JSON-RPC，GET 不提供服务端推送通道（405），DELETE 作废会话。
 */
@RestController
class McpController(private val handler: McpHandler) {

    @PostMapping("/mcp")
    fun post(
        @RequestHeader(value = PROTOCOL_VERSION_HEADER, required = false) protocolVersion: String?,
        @RequestHeader(value = SESSION_HEADER, required = false) sessionId: String?,
        @RequestBody requestBody: String,
        response: HttpServletResponse,
    ) {
        val result = handler.handle(requestBody, protocolVersion, sessionId)
        writeTo(result, response)
    }

    @GetMapping("/mcp")
    fun get(response: HttpServletResponse) {
        response.setHeader("Allow", "POST, DELETE")
        response.status = HttpServletResponse.SC_METHOD_NOT_ALLOWED
    }

    @DeleteMapping("/mcp")
    fun delete(
        @RequestHeader(value = SESSION_HEADER, required = false) sessionId: String?,
        response: HttpServletResponse,
    ) {
        handler.closeSession(sessionId)
        response.status = HttpServletResponse.SC_OK
    }

    private fun writeTo(result: McpResponse, response: HttpServletResponse) {
        result.sessionId?.let { response.setHeader(SESSION_HEADER, it) }
        response.status = result.status
        val body = result.body ?: return
        response.characterEncoding = StandardCharsets.UTF_8.name()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.writer.use { it.write(body) }
    }

    companion object {

        const val PROTOCOL_VERSION_HEADER = "MCP-Protocol-Version"

        const val SESSION_HEADER = "Mcp-Session-Id"
    }
}
