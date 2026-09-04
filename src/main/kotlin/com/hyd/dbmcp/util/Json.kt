package com.hyd.dbmcp.util

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode

/**
 * 全项目共用的 JSON 能力，只用 Jackson 树 API。
 *
 * 刻意不引入 jackson-module-kotlin：领域模型与 JSON 的映射由各自的 toJson/fromJson 手写，
 * 保持「除 Boot 的 web + thymeleaf 外零新增依赖」。
 */
object Json {

    private val mapper: JsonMapper = JsonMapper.builder().build()

    fun obj(): ObjectNode = JsonNodeFactory.instance.objectNode()

    fun arr(): ArrayNode = JsonNodeFactory.instance.arrayNode()

    fun parse(text: String): JsonNode = mapper.readTree(text)

    fun write(node: JsonNode): String = mapper.writeValueAsString(node)
}
