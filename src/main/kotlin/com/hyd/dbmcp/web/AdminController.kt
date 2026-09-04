package com.hyd.dbmcp.web

import com.hyd.dbmcp.config.AppState
import com.hyd.dbmcp.config.AppStatus
import com.hyd.dbmcp.config.ConnectionValidation
import com.hyd.dbmcp.config.DbKind
import com.hyd.dbmcp.config.DbMcpProperties
import com.hyd.dbmcp.config.NotReadyException
import com.hyd.dbmcp.crypto.ConfigEnvelopeException
import com.hyd.dbmcp.crypto.WrongConfigPasswordException
import com.hyd.dbmcp.exec.QueryReport
import com.hyd.dbmcp.exec.QueryService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpSession
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.servlet.mvc.support.RedirectAttributes

/**
 * 管理界面：解锁 / 首次设密 / 连接增删改查 / 测试连接 / 修改配置密码。
 *
 * 表单用普通 request param 接收（字段少、无嵌套），密码字段一律不回显、编辑时留空表示不修改。
 */
@Controller
@RequestMapping("/admin")
class AdminController(
    private val state: AppState,
    private val queryService: QueryService,
    private val props: DbMcpProperties,
) {

    private val log = LoggerFactory.getLogger(AdminController::class.java)

    /** 所有页面都要用的公共属性（解锁状态、数据库类型列表、连接数） */
    @ModelAttribute
    fun commonAttributes(model: Model) {
        val unlocked = state.isUnlocked()
        model.addAttribute("unlocked", unlocked)
        model.addAttribute("kinds", DbKind.entries.toList())
        model.addAttribute(
            "connectionCount",
            if (unlocked) runCatching { state.connections().size }.getOrDefault(0) else 0,
        )
        model.addAttribute("globalTimeout", props.queryTimeoutSeconds)
    }

    // region 解锁与配置密码

    @GetMapping("/unlock")
    fun unlockPage(session: HttpSession, model: Model): String {
        if (state.status() == AppStatus.SETUP_REQUIRED) {
            return "redirect:/admin/setup"
        }
        if (WebSession.matches(session, state.generation)) {
            return "redirect:/admin/connections"
        }
        model.addAttribute("status", state.status())
        return "unlock"
    }

    @PostMapping("/unlock")
    fun unlock(
        @RequestParam password: String,
        session: HttpSession,
        model: Model,
    ): String {
        if (state.status() == AppStatus.SETUP_REQUIRED) {
            return "redirect:/admin/setup"
        }
        return try {
            state.unlock(password)
            WebSession.stamp(session, state.generation)
            "redirect:/admin/connections"
        } catch (e: WrongConfigPasswordException) {
            renderUnlockError(model, "配置密码不正确")
        } catch (e: ConfigEnvelopeException) {
            renderUnlockError(model, "配置文件无法解析：${e.message}")
        } catch (e: RuntimeException) {
            log.error("解锁失败", e)
            renderUnlockError(model, "解锁失败：${e.message}")
        }
    }

    @GetMapping("/setup")
    fun setupPage(session: HttpSession): String =
        if (state.status() == AppStatus.SETUP_REQUIRED) "setup" else "redirect:/admin/unlock"

    @PostMapping("/setup")
    fun setup(
        @RequestParam password: String,
        @RequestParam confirm: String,
        session: HttpSession,
        model: Model,
    ): String {
        if (state.status() != AppStatus.SETUP_REQUIRED) {
            return "redirect:/admin/unlock"
        }
        val problem = when {
            password.length < MIN_PASSWORD_LENGTH -> "配置密码至少 $MIN_PASSWORD_LENGTH 位"
            password != confirm -> "两次输入的密码不一致"
            else -> null
        }
        if (problem != null) {
            return renderSetupError(model, problem, password)
        }
        return try {
            state.setup(password)
            WebSession.stamp(session, state.generation)
            log.info("已初始化配置文件并设置配置密码")
            "redirect:/admin/connections"
        } catch (e: RuntimeException) {
            log.error("初始化配置文件失败", e)
            renderSetupError(model, "初始化配置文件失败：${e.message}", password)
        }
    }

    @GetMapping("/password")
    fun passwordPage(model: Model): String {
        addPageAttributes(model)
        return "password"
    }

    @PostMapping("/password")
    fun changePassword(
        @RequestParam current: String,
        @RequestParam next: String,
        @RequestParam confirm: String,
        session: HttpSession,
        model: Model,
    ): String {
        val problem = when {
            next.length < MIN_PASSWORD_LENGTH -> "新密码至少 $MIN_PASSWORD_LENGTH 位"
            next != confirm -> "两次输入的新密码不一致"
            else -> null
        }
        if (problem != null) {
            addPageAttributes(model)
            model.addAttribute("error", problem)
            return "password"
        }
        return try {
            state.changePassword(current, next)
            WebSession.stamp(session, state.generation)
            log.info("配置密码已修改，配置文件整体重写")
            addPageAttributes(model)
            model.addAttribute("message", "配置密码已修改，配置文件已整体重写")
            "password"
        } catch (e: WrongConfigPasswordException) {
            addPageAttributes(model)
            model.addAttribute("error", "当前配置密码不正确")
            "password"
        } catch (e: ConfigEnvelopeException) {
            addPageAttributes(model)
            model.addAttribute("error", "配置文件无法解析：${e.message}")
            "password"
        } catch (e: NotReadyException) {
            "redirect:/admin/unlock"
        }
    }

    private fun renderUnlockError(model: Model, message: String): String {
        model.addAttribute("status", state.status())
        model.addAttribute("error", message)
        return "unlock"
    }

    private fun renderSetupError(model: Model, message: String, password: String): String {
        model.addAttribute("error", message)
        model.addAttribute("confirm", password)
        return "setup"
    }

    // endregion

    // region 连接配置

    @GetMapping("/connections")
    fun listConnections(model: Model, request: HttpServletRequest): String {
        addPageAttributes(model)
        model.addAttribute("mcpUrl", "${request.scheme}://${request.serverName}:${request.serverPort}/mcp")
        return "connections"
    }

    @GetMapping("/connections/new")
    fun newConnection(model: Model): String {
        addPageAttributes(model)
        model.addAttribute("form", ConnectionForm())
        model.addAttribute("mode", "new")
        return "connection-form"
    }

    @GetMapping("/connections/{name}/edit")
    fun editConnection(@PathVariable name: String, model: Model): String {
        addPageAttributes(model)
        val connection = state.find(name) ?: return "redirect:/admin/connections"
        model.addAttribute("form", ConnectionForm.of(connection))
        model.addAttribute("mode", "edit")
        model.addAttribute("editing", connection)
        return "connection-form"
    }

    @PostMapping("/connections/save")
    fun saveConnection(
        form: ConnectionForm,
        model: Model,
        redirect: RedirectAttributes,
    ): String {
        val mode = if (form.originalName.isBlank()) "new" else "edit"
        val candidate = form.toConnectionOrNull(storedPassword(form.originalName))
            ?: return renderFormWithError(model, form, mode, "数据库类型不合法")
        val errors = ConnectionValidation.errors(
            candidate,
            state.connections().filterNot { it.name == form.originalName },
        )
        if (errors.isNotEmpty()) {
            return renderFormWithError(model, form, mode, errors.joinToString("；"))
        }
        try {
            state.saveConnection(candidate, form.originalName.ifBlank { null })
        } catch (e: NotReadyException) {
            return "redirect:/admin/unlock"
        } catch (e: RuntimeException) {
            log.error("保存连接失败", e)
            return renderFormWithError(model, form, mode, "保存失败：${e.message}")
        }
        log.info("连接「{}」已保存", candidate.name)
        redirect.addFlashAttribute("message", "连接「${candidate.name}」已保存")
        return "redirect:/admin/connections"
    }

    /**
     * 用表单当前内容试连（未保存的草稿）。
     *
     * 只回 JSON，不重渲染表单：测试无论成败都不允许改动用户正在填的东西——
     * 早先版本测试后回整张表单，而口令字段按设计不回显，于是「测试通过 → 直接点保存」会把口令抹成空串。
     */
    @PostMapping("/connections/test")
    @ResponseBody
    fun testDraft(form: ConnectionForm): Map<String, Any> {
        val candidate = form.toConnectionOrNull(storedPassword(form.originalName))
            ?: return mapOf("ok" to false, "message" to "数据库类型不合法")
        val errors = ConnectionValidation.errors(
            candidate,
            state.connections().filterNot { it.name == form.originalName },
        )
        if (errors.isNotEmpty()) {
            return mapOf("ok" to false, "message" to errors.joinToString("；"))
        }
        val report = queryService.ping(candidate)
        log.info("表单内测试连接「{}」：{}", candidate.name, pingMessage(candidate.name, report))
        return mapOf("ok" to report.ok, "message" to pingMessage(candidate.name, report))
    }

    @PostMapping("/connections/{name}/test")
    fun testSavedConnection(@PathVariable name: String, redirect: RedirectAttributes): String {
        val connection = state.find(name)
            ?: return "redirect:/admin/connections"
        val report = queryService.ping(connection)
        val message = pingMessage(connection.name, report)
        log.info(message)
        redirect.addFlashAttribute(if (report.ok) "message" else "error", message)
        return "redirect:/admin/connections"
    }

    @PostMapping("/connections/{name}/delete")
    fun deleteConnection(@PathVariable name: String, redirect: RedirectAttributes): String {
        state.deleteConnection(name)
        log.info("连接「{}」已删除", name)
        redirect.addFlashAttribute("message", "连接「$name」已删除")
        return "redirect:/admin/connections"
    }

    /** 测试连接的结论只需要「能不能连上 + 多快」，CLI 原文在这里是噪音 */
    private fun pingMessage(name: String, report: QueryReport): String =
        if (report.ok) {
            "连接「$name」测试通过（${report.elapsedMs}ms）"
        } else {
            "连接「$name」测试失败：${report.error?.replace(Regex("\\s+"), " ")?.trim()}"
        }

    private fun renderFormWithError(model: Model, form: ConnectionForm, mode: String, error: String): String {
        addPageAttributes(model)
        model.addAttribute("form", form)
        model.addAttribute("mode", mode)
        model.addAttribute("error", error)
        return "connection-form"
    }

    /** 编辑时密码留空表示沿用已保存的密码 */
    private fun storedPassword(originalName: String): String =
        if (originalName.isBlank()) "" else state.find(originalName)?.password ?: ""

    // endregion

    private fun addPageAttributes(model: Model) {
        model.addAttribute("connections", runCatching { state.connections() }.getOrDefault(emptyList()))
    }

    companion object {

        private const val MIN_PASSWORD_LENGTH = 8
    }
}
