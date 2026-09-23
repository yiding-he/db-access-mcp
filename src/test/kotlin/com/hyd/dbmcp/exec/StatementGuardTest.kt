package com.hyd.dbmcp.exec

import com.hyd.dbmcp.config.DbKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 只读闸门的用例表。
 *
 * MongoDB 侧没有服务端只读兜底，这个闸门是唯一防线，所以除了「该拒的拒掉」，
 * 也固定住「该放的放过」——免得以后收紧规则时把正常查询误杀。
 */
class StatementGuardTest {

    @Test
    fun `MySQL 放行的只读语句`() {
        listOf(
            "SELECT 1",
            "select * from orders where id = 1;",
            "  SELECT 1  ;\n",
            "WITH t AS (SELECT 1 AS a) SELECT * FROM t",
            "SHOW TABLES",
            "SHOW FULL PROCESSLIST",
            "DESC orders",
            "DESCRIBE orders",
            "EXPLAIN SELECT * FROM orders",
            "EXPLAIN DELETE FROM orders",
            "SELECT /*+ MAX_EXECUTION_TIME(1000) */ 1",
            "SELECT 'set' AS word, group_config FROM t",
            // 分号可以合法出现在字符串常量里，因此不按分号判多语句（用户决定：不防同批次自我撤销只读）
            "SELECT ';' AS semicolon",
            "SELECT 1; SELECT 2",
            // 被括号包裹的组合查询
            "(SELECT 1) UNION (SELECT 2)",
            "((SELECT 1 AS a) UNION (SELECT 2 AS a)) ORDER BY a",
            "(WITH t AS (SELECT 1 AS a) SELECT * FROM t) UNION (SELECT 2)",
        ).forEach { assertTrue(allowed(DbKind.MYSQL, it), "应当放行：$it") }
    }

    @Test
    fun `MySQL 拒绝的语句`() {
        listOf(
            "",
            "   ",
            ";",
            "DELETE FROM orders",
            "UPDATE orders SET a = 1",
            "INSERT INTO orders VALUES (1)",
            "DROP TABLE orders",
            "TRUNCATE orders",
            "ALTER TABLE orders ADD COLUMN a INT",
            "CREATE TABLE t (a INT)",
            "SET autocommit = 0",
            "USE test",
            "CALL some_procedure()",
            "SELECT 1 INTO OUTFILE '/tmp/a.txt'",
            "SELECT 1 INTO/**/OUTFILE '/tmp/a.txt'",
            "SELECT 1 INTO\n\tDUMPFILE '/tmp/a.txt'",
            "SELECT load_file('/etc/passwd')",
            "LOAD DATA INFILE 'a.txt' INTO TABLE t",
            "SELECT * FROM orders FOR UPDATE",
            "SELECT * FROM orders LOCK IN SHARE MODE",
            "SELECT /*!32302 1/0, */ 1",
            "/*x*/ SELECT 1",
            "LOCK TABLES orders READ",
            // 括号不能用来绕过首关键字白名单
            "(DELETE FROM orders)",
            "(UPDATE orders SET a = 1)",
            "((DROP TABLE orders))",
            "(SELECT 1) INTO OUTFILE '/tmp/a.txt'",
        ).forEach { rejected(DbKind.MYSQL, it) }
    }

    @Test
    fun `MongoDB 放行的只读表达式`() {
        listOf(
            "db.orders.find({})",
            "db.orders.findOne({_id: 1})",
            "db.orders.find({status: 1}).limit(20)",
            "db.orders.find({}).sort({_id: -1}).limit(1).skip(2)",
            "db.orders.find({a: ObjectId('64a1b2c3d4e5f6a7b8c9d0e1')})",
            "db.getCollection('weird.name').find({})",
            "db.orders.countDocuments({a: 1})",
            "db.orders.estimatedDocumentCount()",
            "db.orders.distinct('user_id')",
            "db.orders.aggregate([{\$match: {a: 1}}, {\$group: {_id: '\$a', n: {\$sum: 1}}}])",
            "db.orders.stats()",
            "db.getCollectionNames()",
            "db.listCollections()",
            "db.orders.find({a: 1}).toArray()",
            // 索引查询
            "db.orders.getIndexes()",
            "db.getCollection('weird.name').getIndexes()",
            "db.orders.getIndexSpecs()",
            "db.orders.getIndexKeys()",
            // count（countDocuments 的旧别名）与单项统计别名
            "db.orders.count({a: 1})",
            "db.orders.dataSize()",
            "db.orders.storageSize()",
            "db.orders.totalSize()",
            "db.orders.totalIndexSize()",
            // 元数据
            "db.orders.isCapped()",
            "db.orders.options()",
            // 库级统计
            "db.stats()",
            // 查询计划：游标链末尾与集合上的前缀写法
            "db.orders.find({}).explain()",
            "db.orders.find({}).sort({_id: 1}).explain()",
            "db.orders.explain().find({})",
            "db.orders.explain('executionStats').find({})",
            "db.getCollection('x').explain().count({})",
            // 字符串常量里的分号不算多语句
            "db.orders.find({code: 'A;B'})",
            "db.orders.find({code: \";\"}).limit(1)",
        ).forEach { assertTrue(allowed(DbKind.MONGODB, it), "应当放行：$it") }
    }

    @Test
    fun `MongoDB 拒绝的语句`() {
        listOf(
            "",
            "db.orders.insertOne({a: 1})",
            "db.orders.insertMany([{a: 1}])",
            "db.orders.updateOne({}, {\$set: {a: 1}})",
            "db.orders.deleteMany({})",
            "db.orders.drop()",
            "db.orders.dropIndexes()",
            "db.orders.createIndex({a: 1})",
            "db.orders.renameCollection('y')",
            "db.createCollection('x')",
            "db.orders.aggregate([{\$out: 'copy'}])",
            "db.orders.aggregate([{\$merge: 'copy'}])",
            "db.orders.aggregate([{\$accumulator: 1}])",
            "db.runCommand({ping: 1})",
            "db.adminCommand({listDatabases: 1})",
            "db.serverStatus()",
            // 名字含 find 但会写入
            "db.orders.findAndModify({query: {}, update: {\$set: {a: 1}}})",
            "db.orders.find({}).forEach(printjson)",
            "db.orders.find({}); db.orders.drop()",
            // explain 不是绕过白名单的口子：前缀后仍须是只读方法，db 级没有 explain
            "db.orders.getIndexes().drop()",
            "db.explain().find({})",
            "db.orders.explain().insertOne({})",
            "db.orders.explain().explain().find({})",
            "const docs = db.orders.find({})",
            "show collections",
            "1 + 1",
            "db['orders'].find({})",
            "db.orders.find({a: 1})[0]",
        ).forEach { rejected(DbKind.MONGODB, it) }
    }

    @Test
    fun `语句规范化只去掉首尾空白与末尾分号`() {
        val outcome = StatementGuard.check(DbKind.MYSQL, "\n  SELECT   1 ;  \n")

        assertTrue(outcome is GuardOutcome.Allowed, "应当放行：$outcome")
        assertEquals("SELECT   1", (outcome as GuardOutcome.Allowed).statement)
    }

    @Test
    fun `拒绝原因可直接回给 Agent`() {
        val outcome = StatementGuard.check(DbKind.MYSQL, "DELETE FROM orders")

        assertTrue(outcome is GuardOutcome.Rejected)
        assertNotNull((outcome as GuardOutcome.Rejected).reason.ifBlank { null })
    }

    @Test
    fun `Redis 读命令白名单与参数校验`() {
        assertTrue(allowed(DbKind.REDIS, "GET msg"))
        assertTrue(allowed(DbKind.REDIS, "SCAN 0 COUNT 5"))
        assertTrue(allowed(DbKind.REDIS, "KEYS cache:*"))
        assertTrue(allowed(DbKind.REDIS, "GET \"key with space\""))

        rejected(DbKind.REDIS, "SET msg hello")
        rejected(DbKind.REDIS, "DEL msg")
        rejected(DbKind.REDIS, "CONFIG SET maxmemory 100mb")
        rejected(DbKind.REDIS, "GET -h1.2.3.4")
        rejected(DbKind.REDIS, "")
        rejected(DbKind.REDIS, "FLUSHALL")
    }

    private fun allowed(kind: DbKind, statement: String): Boolean =
        StatementGuard.check(kind, statement) is GuardOutcome.Allowed

    private fun rejected(kind: DbKind, statement: String) {
        val outcome = StatementGuard.check(kind, statement)
        assertTrue(outcome is GuardOutcome.Rejected, "应当拒绝：$kind <- $statement，实际为 $outcome")
    }
}
