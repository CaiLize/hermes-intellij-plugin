package com.hermes.intellij.actions

import com.hermes.intellij.model.CodeContext
import com.hermes.intellij.toolwindow.ChatPanel
import com.hermes.intellij.toolwindow.HermesToolWindowFactory
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Sends the currently selected code in the editor to the Hermes AI chat panel as context.
 */
class SendSelectionAction : AnAction("Send Selection to Hermes", "Send selected code as context to Hermes AI chat", null), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val selectedText = editor.selectionModel.selectedText ?: return
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return

        val startLine = editor.document.getLineNumber(editor.selectionModel.selectionStart) + 1
        val endLine = editor.document.getLineNumber(editor.selectionModel.selectionEnd) + 1
        val lineRange = "$startLine-$endLine"

        val language = virtualFile.fileType.name.lowercase()
        val filePath = virtualFile.path

        val context = CodeContext(
            filePath = filePath,
            lineRange = lineRange,
            content = selectedText,
            language = language
        )

        // Open the tool window and attach context
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(HermesToolWindowFactory.TOOL_WINDOW_ID)
        if (toolWindow != null) {
            toolWindow.show {
                val chatPanel = HermesToolWindowFactory.getChatPanel(toolWindow)
                chatPanel?.attachContext(context)
            }
        }
    }

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = editor?.selectionModel?.hasSelection() == true
    }
}
