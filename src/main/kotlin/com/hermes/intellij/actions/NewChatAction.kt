package com.hermes.intellij.actions

import com.hermes.intellij.toolwindow.HermesToolWindowFactory
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Starts a new chat conversation by clearing the current history.
 */
class NewChatAction : AnAction(
    "New Chat",
    "Start a new Hermes AI conversation",
    IconLoader.getIcon("/icons/newchat_16.svg", NewChatAction::class.java)
), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val toolWindow = ToolWindowManager.getInstance(project)
            .getToolWindow(HermesToolWindowFactory.TOOL_WINDOW_ID) ?: return

        val chatPanel = HermesToolWindowFactory.getChatPanel(toolWindow)
        chatPanel?.newChat()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}
