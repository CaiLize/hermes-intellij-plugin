package com.hermes.intellij.services

import com.hermes.intellij.model.CodeContext
import com.hermes.intellij.toolwindow.HermesToolWindowFactory
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.util.Alarm

/**
 * Listens for code selection changes ONLY when the Hermes tool window is visible.
 * When hidden, the listener is completely unregistered — zero overhead.
 */
@Service(Service.Level.PROJECT)
class SelectionContextService(private val project: Project) : Disposable {

    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val DEBOUNCE_MS = 500
    private var listenerDisposable: Disposable? = null

    fun initialize() {
        // Watch for tool window visibility changes
        project.messageBus.connect(this).subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun stateChanged(toolWindowManager: ToolWindowManager) {
                    val tw = toolWindowManager.getToolWindow(HermesToolWindowFactory.TOOL_WINDOW_ID)
                    if (tw?.isVisible == true) {
                        registerSelectionListener()
                    } else {
                        unregisterSelectionListener()
                    }
                }
            }
        )
    }

    private fun registerSelectionListener() {
        if (listenerDisposable != null) return

        val disposable = Disposer.newDisposable(this, "HermesSelectionListener")
        EditorFactory.getInstance().eventMulticaster.addSelectionListener(
            object : SelectionListener {
                override fun selectionChanged(e: SelectionEvent) {
                    onSelectionChanged(e)
                }
            },
            disposable
        )
        listenerDisposable = disposable
    }

    private fun unregisterSelectionListener() {
        listenerDisposable?.let { Disposer.dispose(it) }
        listenerDisposable = null
        alarm.cancelAllRequests()
    }

    private fun onSelectionChanged(e: SelectionEvent) {
        val editor = e.editor
        if (!editor.selectionModel.hasSelection()) return

        alarm.cancelAllRequests()
        alarm.addRequest({ processSelection(editor) }, DEBOUNCE_MS)
    }

    private fun processSelection(editor: Editor) {
        if (!editor.selectionModel.hasSelection()) return

        val vf = editor.virtualFile ?: return
        val fileType = vf.fileType.name.lowercase()

        // 获取所有选区，支持多选区（Ctrl+多选）
        val allCarets = editor.caretModel.allCarets
        if (allCarets.isEmpty()) return

        val toolWindow = ToolWindowManager.getInstance(project)
            .getToolWindow(HermesToolWindowFactory.TOOL_WINDOW_ID) ?: return
        val chatPanel = HermesToolWindowFactory.getChatPanel(toolWindow) ?: return

        // 为每个选区创建 CodeContext
        for (caret in allCarets) {
            if (!caret.hasSelection()) continue

            val startOffset = caret.selectionStart
            val endOffset = caret.selectionEnd
            val selectedText = editor.document.getText(com.intellij.openapi.util.TextRange.create(startOffset, endOffset))
            if (selectedText.isBlank()) continue

            val startLine = editor.document.getLineNumber(startOffset) + 1
            val endLine = editor.document.getLineNumber(endOffset) + 1

            val context = CodeContext(
                filePath = vf.path,
                lineRange = "$startLine-$endLine",
                content = selectedText,
                language = fileType
            )

            chatPanel.replaceOrAddContext(context) { existing ->
                existing.filePath == vf.path && rangesOverlap(existing.lineRange, "$startLine-$endLine")
            }
        }
    }

    private fun rangesOverlap(range1: String?, range2: String): Boolean {
        if (range1 == null) return false
        val parts1 = range1.split("-")
        val parts2 = range2.split("-")
        if (parts1.size != 2 || parts2.size != 2) return false
        val s1 = parts1[0].toIntOrNull() ?: return false
        val e1 = parts1[1].toIntOrNull() ?: return false
        val s2 = parts2[0].toIntOrNull() ?: return false
        val e2 = parts2[1].toIntOrNull() ?: return false
        return s1 <= e2 && s2 <= e1
    }

    override fun dispose() {
        unregisterSelectionListener()
    }

    companion object {
        fun getInstance(project: Project): SelectionContextService =
            project.getService(SelectionContextService::class.java)
    }
}
