package com.hermes.intellij.actions

import com.hermes.intellij.model.CodeContext
import com.hermes.intellij.toolwindow.HermesToolWindowFactory
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.wm.ToolWindowManager
import java.awt.Component
import javax.swing.JOptionPane

/**
 * Sends the entire current file to the Hermes AI chat panel as context.
 */
class SendFileAction : AnAction("Send File to Hermes", "Send current file as context to Hermes AI chat", null), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return

        var content = editor.document.text
        val lineCount = editor.document.lineCount

        // Truncate very large files
        if (lineCount > 10000) {
            val lines = content.lines()
            content = lines.take(10000).joinToString("\n") + "\n\n... (file truncated, ${lineCount - 10000} more lines)"
        }

        val language = virtualFile.fileType.name.lowercase()
        val filePath = virtualFile.path

        // FIX: VULN-016 - 敏感文件检查
        if (com.hermes.intellij.security.FileSecurityValidator.isSensitiveFile(filePath)) {
            val fileName = filePath.substringAfterLast('/')
            val message = buildString {
                append("⚠️ 检测到敏感文件：$fileName\n\n")
                append("该文件可能包含敏感信息（如密钥、凭证等）。\n")
                append("确定要发送到 Hermes AI 吗？")
            }
            val result = JOptionPane.showConfirmDialog(
                null as Component?,
                message,
                "安全警告",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
            )
            if (result != JOptionPane.YES_OPTION) {
                return
            }
        }

        val context = CodeContext(
            filePath = filePath,
            lineRange = null,
            content = content,
            language = language
        )

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
        e.presentation.isEnabledAndVisible = editor != null
    }
}
