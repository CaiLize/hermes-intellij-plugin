package com.hermes.intellij.toolwindow

import com.hermes.intellij.actions.HermesPasteAction
import com.hermes.intellij.actions.NewChatAction
import com.hermes.intellij.services.SelectionContextService
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class HermesToolWindowFactory : ToolWindowFactory, DumbAware {

    private val logger = Logger.getInstance(HermesToolWindowFactory::class.java)

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val chatPanel = ChatPanel(project)

        toolWindow.component.putClientProperty(CHAT_PANEL_KEY, chatPanel)

        val content = ContentFactory.getInstance().createContent(chatPanel, "Chat", false)
        toolWindow.contentManager.addContent(content)

        toolWindow.setTitleActions(listOf(NewChatAction()))

        // Initialize editor selection listener
        SelectionContextService.getInstance(project).initialize()

        // 替换全局 $Paste action，支持图片/文件粘贴
        // 注意：这会在 IDE 日志中产生警告，但不影响功能
        replacePasteAction()

        // 工具窗口关闭时恢复原始 paste action
        Disposer.register(content, Disposable {
            restorePasteAction()
        })
    }

    private fun replacePasteAction() {
        try {
            val actionManager = ActionManager.getInstance()
            if (originalPaste == null) {
                originalPaste = actionManager.getAction("\$Paste")
            }
            if (originalPaste != null) {
                actionManager.replaceAction("\$Paste", HermesPasteAction(originalPaste))
                logger.info("Replaced \$Paste action with HermesPasteAction")
            }
        } catch (e: Exception) {
            logger.warn("Failed to replace \$Paste action: ${e.message}")
        }
    }

    private fun restorePasteAction() {
        try {
            val actionManager = ActionManager.getInstance()
            if (originalPaste != null) {
                actionManager.replaceAction("\$Paste", originalPaste!!)
                originalPaste = null
                logger.info("Restored original \$Paste action")
            }
        } catch (e: Exception) {
            // 忽略恢复时的异常（例如 IDE 关闭时）
            logger.warn("Failed to restore \$Paste action (expected during shutdown): ${e.message}")
        }
    }

    companion object {
        const val TOOL_WINDOW_ID = "Hermes AI"
        const val CHAT_PANEL_KEY = "HermesChatPanel"

        @Volatile
        private var originalPaste: AnAction? = null

        fun getChatPanel(toolWindow: ToolWindow): ChatPanel? {
            return toolWindow.component.getClientProperty(CHAT_PANEL_KEY) as? ChatPanel
        }
    }
}
