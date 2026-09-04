package com.hyd.dbmcp

import java.util.Properties

/** 读取 Boot 的 bootBuildInfo 产物（META-INF/build-info.properties）；拿不到就回 unknown，不影响运行 */
object BuildInfo {

    private const val RESOURCE = "META-INF/build-info.properties"

    val version: String by lazy {
        val properties = Properties()
        runCatching {
            javaClass.classLoader.getResourceAsStream(RESOURCE)?.use { stream -> properties.load(stream) }
        }
        properties.getProperty("build.version")?.takeIf { it.isNotBlank() } ?: "unknown"
    }
}
