package com.hyd.dbmcp.web

import com.hyd.dbmcp.config.AppState
import com.hyd.dbmcp.config.AppStatus
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpSession
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/** 浏览器会话与「解锁代次」的绑定：代次变化即要求重新输入配置密码 */
object WebSession {

    private const val ATTRIBUTE = "dbmcp.unlockGeneration"

    fun stamp(session: HttpSession, generation: Long) {
        session.setAttribute(ATTRIBUTE, generation)
    }

    fun matches(session: HttpSession, generation: Long): Boolean = session.getAttribute(ATTRIBUTE) == generation

    fun invalidate(session: HttpSession) {
        session.removeAttribute(ATTRIBUTE)
    }
}

/**
 * 管理界面的准入：只有解锁页与首次设密页可以匿名访问，其余必须先解锁。
 * 注意这只管浏览器会话；MCP 端点的准入在 McpHandler 里按全局解锁状态判定。
 */
class UnlockInterceptor(private val state: AppState) : HandlerInterceptor {

    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        val path = request.requestURI.removeSuffix("/")
        if (path == "/admin/unlock" || path == "/admin/setup") {
            return true
        }
        val session = request.getSession(false)
        if (state.isUnlocked() && session != null && WebSession.matches(session, state.generation)) {
            return true
        }
        val target = if (state.status() == AppStatus.SETUP_REQUIRED) "/admin/setup" else "/admin/unlock"
        session?.let { WebSession.invalidate(it) }
        response.sendRedirect(request.contextPath + target)
        return false
    }
}

/**
 * 管理界面的准入：只有解锁页与首次设密页可以匿名访问，其余必须先解锁。
 * 注意这只管浏览器会话；MCP 端点的准入在 McpHandler 里按全局解锁状态判定。
 *
 * 这个类没有 @Bean 方法，`proxyBeanMethods = false` 让 Spring 不再用 CGLIB 代理它，
 * 于是 Kotlin 的 final 类也能直接当配置类用。
 */
@Configuration(proxyBeanMethods = false)
class WebConfig(private val state: AppState) : WebMvcConfigurer {

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(UnlockInterceptor(state)).addPathPatterns("/admin/**")
    }

    override fun addViewControllers(registry: ViewControllerRegistry) {
        registry.addRedirectViewController("/", "/admin/connections")
        registry.addRedirectViewController("/admin", "/admin/connections")
    }
}
