package com.hyd.dbmcp.config

import com.hyd.dbmcp.util.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 配置文件明文层的序列化与校验规则。
 */
class AppConfigTest {

    @Test
    fun `连接配置序列化往返一致`() {
        val config = AppConfig(listOf(mysql(), mongo()))

        val restored = AppConfig.fromJson(Json.parse(Json.write(config.toJson())))

        assertEquals(config, restored)
    }

    @Test
    fun `配置明文的 JSON 形状`() {
        val json = Json.write(AppConfig(listOf(mysql())).toJson())

        assertEquals(
            """{"version":1,"globalConfig":{},"connections":[{"name":"app_mysql","kind":"mysql","description":"主库只读账号",""" +
                """"host":"10.0.0.1","port":3306,"username":"reader","password":"secret",""" +
                """"database":"app","authSource":""}]}""",
            json,
        )
    }

    @Test
    fun `未知数据库类型的条目被忽略而不是整体失败`() {
        val json = """
            {"version":1,"connections":[
              {"name":"a","kind":"oracle","description":"d","host":"h","port":1521},
              {"name":"b","kind":"mysql","description":"d","host":"h","port":3306}
            ]}
        """.trimIndent()

        val config = AppConfig.fromJson(Json.parse(json))

        assertEquals(listOf("b"), config.connections.map { it.name })
    }

    @Test
    fun `缺字段按空串与类型默认端口处理`() {
        val json = """{"version":1,"connections":[{"name":"a","kind":"mysql","host":"h"}]}"""

        val connection = AppConfig.fromJson(Json.parse(json)).connections.single()

        assertEquals("", connection.description)
        assertEquals("", connection.password)
        assertEquals("", connection.database)
        assertEquals(3306, connection.port)
    }

    @Test
    fun `对外的连接信息不含地址与凭据`() {
        val publicJson = Json.write(mongo().toPublicJson())

        assertTrue(publicJson.contains("\"name\""))
        assertTrue(publicJson.contains("\"description\""))
        assertTrue(publicJson.contains("\"kind\""))
        assertTrue(!publicJson.contains("secret"), publicJson)
        assertTrue(!publicJson.contains("10.0.0.1"), publicJson)
    }

    @Test
    fun `校验规则覆盖必填与取值范围`() {
        val broken = mysql().copy(name = "坏的 名字", description = "", host = "", port = 70000)

        val errors = ConnectionValidation.errors(broken, emptyList())

        assertEquals(4, errors.size, errors.toString())
    }

    @Test
    fun `MongoDB 必须给出数据库名`() {
        val errors = ConnectionValidation.errors(mongo().copy(database = ""), emptyList())

        assertEquals(listOf("MongoDB 必须指定数据库名"), errors)
    }

    @Test
    fun `同名连接被拒绝`() {
        val existing = listOf(mysql())

        val errors = ConnectionValidation.errors(mysql().copy(description = "另一个"), existing)

        assertEquals(1, errors.size, errors.toString())
        assertTrue(errors.single().contains("已存在"), errors.toString())
    }

    @Test
    fun `合法配置没有校验错误`() {
        // 编辑场景的重名排除由调用方负责（others 里不包含自身）
        assertEquals(emptyList<String>(), ConnectionValidation.errors(mysql(), emptyList()))
    }

    @Test
    fun `MongoDB 连接串对凭据做百分号编码`() {
        val uri = mongo().copy(username = "a b", password = "p@ss:w/rd+1", authSource = "admin").mongoUri()

        assertTrue(
            uri.startsWith("mongodb://a%20b:p%40ss%3Aw%2Frd%2B1@"),
            uri,
        )
        assertTrue(uri.endsWith("/orders?authSource=admin"), uri)
    }

    @Test
    fun `没有用户名时连接串不带凭据段`() {
        val uri = mongo().copy(username = "", password = "", authSource = "").mongoUri()

        assertEquals("mongodb://10.0.0.1:27017/orders", uri)
    }

    private fun mysql() = DbConnection(
        name = "app_mysql",
        kind = DbKind.MYSQL,
        description = "主库只读账号",
        host = "10.0.0.1",
        port = 3306,
        username = "reader",
        password = "secret",
        database = "app",
        authSource = "",
    )

    private fun mongo() = DbConnection(
        name = "app_mongo",
        kind = DbKind.MONGODB,
        description = "订单库",
        host = "10.0.0.1",
        port = 27017,
        username = "reader",
        password = "secret",
        database = "orders",
        authSource = "",
    )
}
