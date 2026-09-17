/**
 * 只读语句闸门（关键字白名单 + MongoDB 侧的表达式结构解析）。
 *
 * MySQL 侧这只是第一道，执行时还带 `SET SESSION TRANSACTION READ ONLY` 做服务端兜底；
 * 不按分号切语句 —— 分号可以合法出现在字符串常量里，纯字符扫描无法可靠判定。
 * 已实测的边界（用户判定本 MCP 不需要防，记录以免被误当作已防住）：
 * `SELECT 1; SET SESSION TRANSACTION READ WRITE; DELETE ...` 会让会话只读被同批次撤掉，
 * 而 mysql 命令行本身没有只读参数（8.4.8 实测无 --read-only / --transaction-access-mode）。
 *
 * MongoDB 侧这是**唯一**防线（没有服务端只读兜底），所以走严格的表达式结构解析：
 * 整条语句必须是一个从 `db` 开始的单一只读表达式，分号天然出现在结构之外会被拒。
 */package com.hyd.dbmcp.exec

import com.hyd.dbmcp.config.DbKind

/** 闸门结果：Allowed 带去掉末尾分号的语句，Rejected 带可直接回给 Agent 的原因 */
sealed class GuardOutcome {

    data class Allowed(val statement: String) : GuardOutcome()

    data class Rejected(val reason: String) : GuardOutcome()
}

/**
 * 只读语句闸门（关键字白名单 + 引号感知的单语句判定）。
 *
 * MySQL 侧这只是第一道，执行时还带 `SET SESSION TRANSACTION READ ONLY` 做服务端兜底。
 * 但这个兜底不是不可逆开关：它只是一次会话变量设定，同批次里再来一条
 * `SET SESSION TRANSACTION READ WRITE` 就能撤掉（实测 4 条绕法全部有效）；
 * 而 mysql 命令行本身没有只读参数（实测 8.4.8 无 `--read-only` / `--transaction-access-mode`），
 * 所以「一批只走一条语句」仍是不可缺的一道。
 * 分号按引号/注释状态机判定：只有落在字符串常量与注释之外的分号才算多语句，`SELECT ';'` 不误杀。
 *
 * MongoDB 侧这是**唯一**防线（没有服务端只读兜底），所以走严格的表达式结构解析：
 * 整条语句必须是一个从 `db` 开始的单一只读表达式，分号天然出现在结构之外会被拒。
 */
object StatementGuard {

    private val MYSQL_ALLOWED_FIRST = setOf("SELECT", "WITH", "SHOW", "DESC", "DESCRIBE", "EXPLAIN")

    /**
     * Redis 的读命令白名单。redis-cli 的 argv 模式下首 token 就是命令，
     * 白名单已是完整防线（无多语句注入口）；按需在此补命令。
     */
    private val REDIS_ALLOWED_FIRST = setOf(
        "GET", "MGET", "STRLEN", "GETRANGE", "TYPE", "EXISTS", "TTL", "PTTL", "KEYS", "SCAN", "RANDOMKEY",
        "LRANGE", "LLEN", "LINDEX",
        "SMEMBERS", "SISMEMBER", "SCARD",
        "HGET", "HMGET", "HGETALL", "HKEYS", "HVALS", "HLEN",
        "ZRANGE", "ZREVRANGE", "ZRANGEBYSCORE", "ZSCORE", "ZCOUNT", "ZCARD", "ZRANK",
        "DBSIZE", "INFO", "CONFIG", "PING",
    )

    /** 首关键字允许被括号前缀，否则 `(SELECT ...) UNION (SELECT ...)` 这类合法只读写法会被误杀 */
    private val MYSQL_FIRST_WORD = Regex("""^\s*[ (]*([A-Za-z]+)""")

    /**
     * 首关键字白名单已经挡住写入语句本体，这里只补几个真实风险口子：读写文件、加锁。
     * 不做全局写入关键字扫描，避免 `WHERE name = 'set'` 这类误杀。
     */
    private val MYSQL_FORBIDDEN = listOf(
        Regex("(?i)\\binto\\s+outfile\\b") to "禁止 SELECT ... INTO OUTFILE（会写服务器文件）",
        Regex("(?i)\\binto\\s+dumpfile\\b") to "禁止 SELECT ... INTO DUMPFILE（会写服务器文件）",
        Regex("(?i)\\bload_file\\s*\\(") to "禁止 load_file()（会读服务器文件）",
        Regex("(?i)\\bload\\s+data\\b") to "禁止 LOAD DATA",
        Regex("(?i)\\bfor\\s+update\\b") to "禁止 SELECT ... FOR UPDATE（会加行锁）",
        Regex("(?i)\\block\\s+in\\s+share\\s+mode\\b") to "禁止 LOCK IN SHARE MODE（会加共享锁）",
    )

    /** 可执行注释（以斜杠星感叹号开头）会绕过首关键字判断，整体禁止 */
    private val EXECUTABLE_COMMENT = Regex("/\\*!")

    private val BLOCK_COMMENT = Regex("""/\*[^*]*\*+(?:[^/*][^*]*\*+)*/""")

    private val MONGO_COLLECTION_METHODS = setOf(
        "find", "findOne", "countDocuments", "estimatedDocumentCount", "distinct", "aggregate", "stats",
    )

    private val MONGO_DB_METHODS = setOf("getCollectionNames", "listCollections", "getCollectionInfos")

    /** 只读游标上允许的后链方法 */
    private val MONGO_CURSOR_METHODS = setOf("limit", "sort", "skip", "batchSize", "hint", "maxTimeMS", "toArray", "count")

    private val AGGREGATE_WRITE_STAGES = listOf(
        // 用字符类 [$] 写_literal_ 美元符号：原始字符串里反斜杠不能转义 $，会误触 Kotlin 插值
        Regex("""[$]out\b""") to "\$out（会写入集合）",
        Regex("""[$]merge\b""") to "\$merge（会写入集合）",
        Regex("""[$]accumulator\b""") to "\$accumulator（可执行任意代码）",
    )

    fun check(kind: DbKind, raw: String): GuardOutcome {
        val statement = raw.trim().removeSuffix(";").trim()
        if (statement.isEmpty()) {
            return GuardOutcome.Rejected("语句为空")
        }
        return when (kind) {
            DbKind.MYSQL -> checkMySql(statement)
            DbKind.MONGODB -> checkMongo(statement)
            DbKind.REDIS -> checkRedis(statement)
        }
    }

    private fun checkRedis(statement: String): GuardOutcome {
        val argv = RedisStatementArgv.split(statement)
        if (argv.isEmpty()) {
            return GuardOutcome.Rejected("Redis 语句为空")
        }
        val command = argv.first().uppercase()
        if (command !in REDIS_ALLOWED_FIRST) {
            return GuardOutcome.Rejected(
                "Redis 只允许只读命令，必须在 ${REDIS_ALLOWED_FIRST.sorted().joinToString("/")} 中，当前是「${argv.first()}」",
            )
        }
        // 选项开头的 token 会被 redis-cli 当自己的参数解析（如 -h 改变目标地址），一律拒绝，逼写引号
        argv.drop(1).firstOrNull { it.startsWith("-") }?.let {
            return GuardOutcome.Rejected("Redis 参数不允许以破折号开头：「$it」，值请用引号包住或去掉破折号")
        }
        // CONFIG 命令只允许 GET 子命令
        if (command == "CONFIG" && (argv.size < 2 || argv[1].uppercase() != "GET")) {
            return GuardOutcome.Rejected("CONFIG 只允许 CONFIG GET 子命令")
        }
        return GuardOutcome.Allowed(statement)
    }

    private fun checkMySql(statement: String): GuardOutcome {
        val firstWord = MYSQL_FIRST_WORD.find(statement)?.groupValues?.get(1)?.uppercaseChars() ?: ""
        if (firstWord !in MYSQL_ALLOWED_FIRST) {
            return GuardOutcome.Rejected(
                "MySQL 只允许只读语句，首关键字必须是 ${MYSQL_ALLOWED_FIRST.joinToString("/")}，当前是「$firstWord」",
            )
        }
        // 可执行注释必须在折叠普通注释之前先查，否则会被当成普通注释蒹掉
        if (EXECUTABLE_COMMENT.containsMatchIn(statement)) {
            return GuardOutcome.Rejected("语句被拒绝：禁止 MySQL 可执行注释（斜杠星感叹号开头的写法，会绕过首关键字判断）")
        }
        // 再把普通块注释折叠成空格后扫描，挡住 INTO/**/OUTFILE 这种用注释拆词的绕法
        val scanTarget = BLOCK_COMMENT.replace(statement, " ")
        MYSQL_FORBIDDEN.firstOrNull { (pattern, _) -> pattern.containsMatchIn(scanTarget) }?.let { (_, reason) ->
            return GuardOutcome.Rejected("语句被拒绝：$reason")
        }
        return GuardOutcome.Allowed(statement)
    }

    private fun checkMongo(statement: String): GuardOutcome {
        val chain = MongoChain.parse(statement)
            ?: return GuardOutcome.Rejected(
                "MongoDB 语句必须是从 db 开始的单个只读表达式，例如 db.orders.find({status: 1}).limit(20)；" +
                    "不支持赋值、多条表达式或其它写法",
            )
        val calls = chain.calls
        if (calls.isEmpty()) {
            return GuardOutcome.Rejected("MongoDB 语句没有方法调用，例如 db.orders.find()")
        }
        val first = calls.first().name
        when {
            first in MONGO_DB_METHODS -> Unit
            first == "getCollection" -> {
                val second = calls.getOrNull(1)?.name
                    ?: return GuardOutcome.Rejected("db.getCollection(\"x\") 之后还需要只读方法，例如 .find()")
                if (second !in MONGO_COLLECTION_METHODS) {
                    return GuardOutcome.Rejected(rejectedCollectionMethod(second))
                }
            }

            first in MONGO_COLLECTION_METHODS -> Unit

            else -> return GuardOutcome.Rejected(rejectedCollectionMethod(first))
        }
        calls.drop(if (first == "getCollection") 2 else 1).forEach { call ->
            if (call.name !in MONGO_CURSOR_METHODS) {
                return GuardOutcome.Rejected("MongoDB 语句中的「${call.name}()」不是只读游标方法，已被拒绝")
            }
        }
        if (calls.any { it.name == "aggregate" }) {
            AGGREGATE_WRITE_STAGES.firstOrNull { (pattern, _) -> pattern.containsMatchIn(statement) }?.let { (_, reason) ->
                return GuardOutcome.Rejected("aggregate 管道被拒绝：出现 $reason")
            }
        }
        return GuardOutcome.Allowed(statement)
    }

    private fun rejectedCollectionMethod(name: String) =
        "MongoDB 只允许只读方法，「$name()」不在允许列表：${(MONGO_COLLECTION_METHODS + MONGO_DB_METHODS).sorted().joinToString("/")}"

    private fun String.uppercaseChars(): String = map { it.uppercaseChar() }.joinToString("")
}

/**
 * db 起始表达式的手写扫描器：把 `db.users.find({...}).limit(3)` 拆成有序的段，
 * 括号按配平与字符串状态识别（支持 ObjectId("...") 这类嵌套调用）。
 *
 * 出现任何结构之外的东西（运算符、下标、赋值、第二条表达式）即返回 null，由调用方拒绝。
 */
private class MongoChain(val segments: List<Segment>) {

    class Segment(val name: String, val args: String?)

    val calls: List<Segment> get() = segments.filter { it.args != null }

    companion object {

        fun parse(expression: String): MongoChain? {
            val scanner = Scanner(expression)
            if (scanner.readIdentifier() != "db") {
                return null
            }
            val segments = mutableListOf<Segment>()
            while (true) {
                if (scanner.atEnd()) {
                    return MongoChain(segments)
                }
                if (!scanner.eat('.')) {
                    return null
                }
                val name = scanner.readQuotedOrIdentifier() ?: return null
                if (!scanner.peek('(')) {
                    segments += Segment(name, null)
                } else {
                    val args = scanner.readBalancedParens() ?: return null
                    segments += Segment(name, args)
                }
            }
        }
    }

    /** 极简字符游标，只服务这一种语法形状 */
    private class Scanner(private val text: String) {

        private var index = 0

        fun atEnd(): Boolean {
            skipSpaces()
            return index >= text.length
        }

        fun peek(expected: Char): Boolean {
            skipSpaces()
            return index < text.length && text[index] == expected
        }

        fun eat(expected: Char): Boolean {
            skipSpaces()
            if (index < text.length && text[index] == expected) {
                index++
                return true
            }
            return false
        }

        fun readIdentifier(): String? {
            skipSpaces()
            val start = index
            if (index < text.length && (text[index].isLetter() || text[index] == '_' || text[index] == '$')) {
                index++
                while (index < text.length && (text[index].isLetterOrDigit() || text[index] == '_' || text[index] == '$')) {
                    index++
                }
                return text.substring(start, index)
            }
            return null
        }

        fun readQuotedOrIdentifier(): String? {
            skipSpaces()
            val quote = text.getOrNull(index)
            return if (quote == '\'' || quote == '"' || quote == '`') {
                readQuotedString(quote)
            } else {
                readIdentifier()
            }
        }

        private fun readQuotedString(quote: Char): String? {
            val start = index
            index++
            while (index < text.length) {
                val current = text[index]
                if (current == '\\') {
                    index += 2
                } else if (current == quote) {
                    index++
                    return text.substring(start + 1, index - 1)
                } else {
                    index++
                }
            }
            return null
        }

        /** 读一对配平括号内的内容，字符串字面量里的括号与引号不参与配平 */
        fun readBalancedParens(): String? {
            skipSpaces()
            if (text.getOrNull(index) != '(') {
                return null
            }
            val start = index
            var depth = 0
            while (index < text.length) {
                when (text[index]) {
                    '(', '[', '{' -> depth++
                    ')', ']', '}' -> {
                        depth--
                        if (depth == 0) {
                            index++
                            return text.substring(start + 1, index - 1)
                        }
                        if (depth < 0) {
                            return null
                        }
                    }

                    '\'', '"', '`' -> {
                        // readQuotedString 已经把 index 推到右引号之后，不能再 +1
                        if (readQuotedString(text[index]) == null) {
                            return null
                        }
                        continue
                    }

                    else -> Unit
                }
                index++
            }
            return null
        }

        private fun skipSpaces() {
            while (index < text.length && text[index].isWhitespace()) {
                index++
            }
        }
    }
}
