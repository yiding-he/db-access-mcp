package com.hyd.dbmcp.config

/** 连接配置的字段校验，返回中文错误列表（空列表即合法） */
object ConnectionValidation {

    private val NAME_PATTERN = Regex("^[A-Za-z0-9_-]{1,32}$")

    /**
     * @param candidate 待校验配置
     * @param others 同批已存在的其它连接（编辑时排除自身）
     */
    fun errors(candidate: DbConnection, others: List<DbConnection>): List<String> {
        val errors = mutableListOf<String>()
        if (!NAME_PATTERN.matches(candidate.name)) {
            errors += "连接名只能使用字母、数字、下划线和连字符，长度 1-32"
        }
        if (others.any { it.name == candidate.name }) {
            errors += "连接名「${candidate.name}」已存在，连接名不可重复也不可修改"
        }
        if (candidate.description.isBlank()) {
            errors += "描述不能为空：Agent 靠描述判断这个连接属于哪个项目"
        }
        if (candidate.host.isBlank()) {
            errors += "主机地址不能为空"
        }
        if (candidate.port !in 1..65535) {
            errors += "端口必须在 1-65535 之间"
        }
        if (candidate.kind == DbKind.MONGODB && candidate.database.isBlank()) {
            errors += "MongoDB 必须指定数据库名"
        }
        return errors
    }
}
