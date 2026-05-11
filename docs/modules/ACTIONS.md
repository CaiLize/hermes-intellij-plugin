# Actions 模块 - 用户交互操作

## 模块职责

处理用户触发的各种操作，包括快捷键、菜单项、工具栏按钮等。

---

## 文件清单

| 文件 | 职责 | 关键方法 |
|------|------|----------|
| `HermesPasteAction.kt` | 包装原始粘贴操作，支持 InputPanel 内图片/文件粘贴 | `actionPerformed()`, `update()` |
| `InsertCodeAction.kt` | 在编辑器光标位置插入代码 | `actionPerformed()` |
| `NewChatAction.kt` | 开始新对话 | `actionPerformed()` |
| `ReplaceSelectionAction.kt` | 替换编辑器选中内容 | `actionPerformed()` |
| `SendFileAction.kt` | 发送当前文件到聊天面板 | `actionPerformed()` |
| `SendSelectionAction.kt` | 发送选中代码到聊天面板 | `actionPerformed()`, 快捷键 `Alt+Shift+H` |

---

## 核心实现

### HermesPasteAction

包装原始粘贴操作，判断焦点位置决定由谁处理粘贴：

```kotlin
class HermesPasteAction(private val originalPaste: AnAction?) : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
        val inputPanel = SwingUtilities.getAncestorOfClass(InputPanel::class.java, focused)
        
        if (inputPanel != null) {
            // 焦点在 InputPanel，由 KeyEventDispatcher 处理
            return
        }
        // 焦点在其他地方，调用原始 paste action
        originalPaste?.actionPerformed(e)
    }
}
```

**设计要点**：
- 不直接处理粘贴逻辑，只做焦点判断
- 避免与 KeyEventDispatcher 冲突

---

### SendSelectionAction

发送编辑器选中的代码到聊天面板：

```kotlin
class SendSelectionAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        
        val selectionModel = editor.selectionModel
        val selectedText = selectionModel.selectedText ?: return
        
        val startOffset = selectionModel.selectionStart
        val startLine = editor.document.getLineNumber(startOffset)
        val endLine = editor.document.getLineNumber(selectionModel.selectionEnd)
        
        val context = CodeContext(
            filePath = editor.virtualFile.path,
            content = selectedText,
            lineRange = "${startLine + 1}-${endLine + 1}",
            language = editor.virtualFile.fileType.name
        )
        
        // 附加到 ChatPanel
        HermesToolWindowFactory.getChatPanel(project)?.attachContext(context)
    }
}
```

**快捷键**：`Alt+Shift+H`

---

### InsertCodeAction

在编辑器光标位置插入代码：

```kotlin
class InsertCodeAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        
        val code = getCodeFromClipboard() ?: return
        
        val command = object : WriteCommandAction.Simple<Unit>(project) {
            override fun run(command: Indenter) {
                val offset = editor.caretModel.offset
                editor.document.insertString(offset, code)
            }
        }
        command.execute()
    }
}
```

---

### ReplaceSelectionAction

替换编辑器选中内容：

```kotlin
class ReplaceSelectionAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        
        val selectionModel = editor.selectionModel
        val selectedText = selectionModel.selectedText ?: return
        
        val code = getCodeFromClipboard() ?: return
        
        val command = object : WriteCommandAction.Simple<Unit>(project) {
            override fun run(command: Indenter) {
                val start = selectionModel.selectionStart
                val end = selectionModel.selectionEnd
                editor.document.replaceString(start, end, code)
            }
        }
        command.execute()
    }
}
```

---

### NewChatAction

开始新对话：

```kotlin
class NewChatAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        val chatService = HermesChatService.getInstance(project)
        
        // 保存当前对话
        chatService.saveCurrentConversation()
        
        // 创建新对话
        chatService.startNewConversation()
        
        // 刷新 UI
        HermesToolWindowFactory.getChatPanel(project)?.refresh()
    }
}
```

---

### SendFileAction

发送当前文件到聊天面板：

```kotlin
class SendFileAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        
        val virtualFile = editor.virtualFile
        val content = editor.document.text
        
        val attachment = FileAttachment(
            filePath = virtualFile.path,
            content = content,
            language = virtualFile.fileType.name
        )
        
        HermesToolWindowFactory.getChatPanel(project)?.attachFile(attachment)
    }
}
```

---

## 注册配置 (plugin.xml)

```xml
<actions>
    <!-- 发送选区 -->
    <action id="HermesAI.SendSelection"
            class="com.hermes.intellij.actions.SendSelectionAction"
            text="Send Selection to Hermes"
            description="Send selected code to Hermes AI">
        <keyboard-shortcut keymap="$default" first-keystroke="alt shift H"/>
    </action>
    
    <!-- 新对话 -->
    <action id="HermesAI.NewChat"
            class="com.hermes.intellij.actions.NewChatAction"
            text="New Chat"/>
    
    <!-- 插入代码 -->
    <action id="HermesAI.InsertCode"
            class="com.hermes.intellij.actions.InsertCodeAction"
            text="Insert Code at Caret"/>
    
    <!-- 替换选区 -->
    <action id="HermesAI.ReplaceSelection"
            class="com.hermes.intellij.actions.ReplaceSelectionAction"
            text="Replace Selection with Code"/>
</actions>
```

---

## 相关文档

- [../architecture/LAYERS.md](../architecture/LAYERS.md) - 分层职责
- [SERVICES.md](SERVICES.md) - 服务层实现
- [TOOLWINDOW.md](TOOLWINDOW.md) - UI 组件
