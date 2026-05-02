package com.hermes.intellij.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.DumbAware

/**
 * Replaces the current selection in the editor with the given code string.
 * Falls back to insert at cursor if no selection exists.
 */
class ReplaceSelectionAction : AnAction("Replace Selection", "Replace selected code with AI-generated code", null), DumbAware {

    var codeToReplace: String = ""

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return

        WriteCommandAction.runWriteCommandAction(project, "Hermes AI: Replace Selection", null, {
            // 获取所有选区，支持多选区（Ctrl+多选）
            val allCarets = editor.caretModel.allCarets
            val selections = allCarets.filter { it.hasSelection() }

            if (selections.isNotEmpty()) {
                // 从后往前替换，避免偏移量变化导致位置错误
                val sortedSelections = selections.sortedByDescending { it.selectionStart }
                for (caret in sortedSelections) {
                    val start = caret.selectionStart
                    val end = caret.selectionEnd
                    editor.document.replaceString(start, end, codeToReplace)
                }
                // 选中第一个选区的位置（最小的 start）
                val firstCaret = sortedSelections.last()
                editor.selectionModel.setSelection(firstCaret.selectionStart, firstCaret.selectionStart + codeToReplace.length)
            } else {
                // No selection, insert at cursor
                val offset = editor.caretModel.offset
                editor.document.insertString(offset, codeToReplace)
                editor.caretModel.moveToOffset(offset + codeToReplace.length)
            }
        })
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.getData(CommonDataKeys.EDITOR) != null
    }
}
