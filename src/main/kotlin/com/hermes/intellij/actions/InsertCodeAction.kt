package com.hermes.intellij.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.DumbAware

/**
 * Inserts a given code string at the current cursor position in the editor.
 * This action is triggered from CodeBlockPanel buttons.
 */
class InsertCodeAction : AnAction("Insert at Cursor", "Insert code at the current cursor position", null), DumbAware {

    var codeToInsert: String = ""

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return

        WriteCommandAction.runWriteCommandAction(project, "Hermes AI: Insert Code", null, {
            val offset = editor.caretModel.offset
            editor.document.insertString(offset, codeToInsert)
            editor.caretModel.moveToOffset(offset + codeToInsert.length)
        })
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.getData(CommonDataKeys.EDITOR) != null
    }
}
