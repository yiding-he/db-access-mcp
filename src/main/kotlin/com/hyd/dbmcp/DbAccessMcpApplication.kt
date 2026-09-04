package com.hyd.dbmcp

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class DbAccessMcpApplication

fun main(args: Array<String>) {
    runApplication<DbAccessMcpApplication>(*args)
}
