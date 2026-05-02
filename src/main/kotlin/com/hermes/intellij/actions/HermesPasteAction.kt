package com.hermes.intellij.actions

import com.hermes.intellij.toolwindow.InputPanel
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAware
import java.awt.KeyboardFocusManager
import javax.swing.SwingUtilities

/**
 * 包装原始 $Paste action。
 * 
 * 重要：当焦点在 InputPanel 内时，此 action 必须保持禁用！
 * 因为 InputPanel 使用 KeyEventDispatcher 拦截 Ctrl+V，
 * 如果此 action 启用，IntelliJ ActionSystem 会抢先消费事件，
 * KeyEventDispatcher 就收不到了。
 * 
 * 所以：焦点在 InputPanel → 不启用（委托给 originalPaste.update()，它会对非 Editor 组件禁用）
 *       焦点不在 InputPanel → 委托给 originalPaste
 */
class HermesPasteAction(
    private val originalPaste: AnAction?
) : AnAction(), DumbAware {

    companion object {
        private val LOG = Logger.getInstance(HermesPasteAction::class.java)
    }

    init {
        // 复制原始 action 的文本和描述，避免 IntelliJ 报 "Empty menu item text" 错误
        // 这对于右键菜单（EditorPopupMenu 等）中显示 $Paste 项至关重要
        templatePresentation.text = originalPaste?.templatePresentation?.text ?: "Paste"
        templatePresentation.description = originalPaste?.templatePresentation?.description ?: "Paste from clipboard"
    }

    override fun actionPerformed(e: AnActionEvent) {
        // 正常情况下不会走到这里（InputPanel 时 action 是禁用的）
        // 但以防万一，做安全处理
        val focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
        val inputPanel = SwingUtilities.getAncestorOfClass(InputPanel::class.java, focused) as? InputPanel
        if (inputPanel != null) {
            // 不应该到这里，KeyEventDispatcher 应该已经处理了
            LOG.warn("[Paste] HermesPasteAction: unexpected call while InputPanel focused")
            return
        }

        originalPaste?.actionPerformed(e)
    }

    override fun update(e: AnActionEvent) {
        // 完全委托给原始 action 的 update()
        // 当焦点在 InputPanel（非 Editor）时，originalPaste.update() 会禁用此 action
        // 这样 IntelliJ ActionSystem 不会消费 Ctrl+V，事件穿透到 KeyEventDispatcher
        originalPaste?.update(e)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}
