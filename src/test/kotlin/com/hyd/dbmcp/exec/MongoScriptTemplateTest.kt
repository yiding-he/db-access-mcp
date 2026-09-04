package com.hyd.dbmcp.exec

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * mongosh 脚本模板的形态约束。
 * 这几条对应实测出来的坑：语句必须内联进源码（不能走运行时 eval），连接串必须走环境变量。
 */
class MongoScriptTemplateTest {

    private val template: String by lazy {
        javaClass.getResourceAsStream("/scripts/mongo_run.js")!!.reader(Charsets.UTF_8).readText()
    }

    @Test
    fun `语句以占位符形式内联，不使用运行时 eval`() {
        assertTrue(template.contains("__DBMCP_STATEMENT__"), "占位符被改动了")
        assertFalse(template.contains("eval("), "语句退回 eval() 会让链式游标方法失效")
    }

    @Test
    fun `连接串走环境变量，行数上限不在脚本里重复判定`() {
        assertEquals(1, Regex("""process\.env\.DBMCP_MONGO_URI""").findAll(template).count())
        assertFalse(template.contains("mongodb://"), "模板里不该出现连接串样例")
        // 脚本自行截断的话，CliRunner 看不到超限行，#META.truncated 会跟实际输出不一致
        assertFalse(template.contains("MAX_ROWS"), "行数上限只能由 CliRunner 一处判定")
    }
}
