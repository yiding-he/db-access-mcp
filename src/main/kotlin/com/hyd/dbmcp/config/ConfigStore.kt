package com.hyd.dbmcp.config

import org.springframework.stereotype.Component
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * 加密配置文件的读写。写盘一律「临时文件 + 原子 rename」，避免中途失败把原配置写坏。
 */
@Component
class ConfigStore(private val props: DbMcpProperties) {

    val path: Path get() = resolveHome(props.configPath)

    fun exists(): Boolean = Files.isRegularFile(path)

    /** 读取信封 JSON 文本，文件不存在返回 null */
    fun readEnvelope(): String? = if (exists()) Files.readString(path, Charsets.UTF_8) else null

    fun writeEnvelope(envelope: String) {
        val target = path
        Files.createDirectories(target.parent)
        val temp = target.resolveSibling("${target.fileName}.tmp-${System.nanoTime()}")
        try {
            Files.writeString(temp, envelope, Charsets.UTF_8)
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun resolveHome(configured: String): Path {
        val home = System.getProperty("user.home")
        val trimmed = configured.trim()
        return if (trimmed == "~" || trimmed.startsWith("~/") || trimmed.startsWith("~\\")) {
            Paths.get(home, trimmed.removePrefix("~").trimStart('/', '\\'))
        } else {
            Paths.get(trimmed)
        }
    }
}
