package com.hermes.intellij.toolwindow

import com.hermes.intellij.services.ToolCallStateMachine
import com.hermes.intellij.services.ToolCallStateMachine.StateChangeListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger

/**
 * ChatPanel 专用的状态机监听器 — 将 ToolCallStateMachine 事件转发到 UI
 *
 * 设计原则:
 * 1. 所有 UI 更新都通过 invokeLater 切换到 EDT 线程
 * 2. 幂等性：重复调用不会产生副作用
 * 3. 状态机是单一事实来源，此监听器只负责通知
 */
class ChatPanelStateListener(private val chatPanel: ChatPanel) : StateChangeListener {

    private val logger = Logger.getInstance(ChatPanelStateListener::class.java)

    override fun onToolCallAdded(record: ToolCallStateMachine.ToolCallRecord) {
        ApplicationManager.getApplication().invokeLater {
            logger.debug("[ChatPanelStateListener] onToolCallAdded: ${record.name}")
            chatPanel.onToolCall(record.name, "Running...")
        }
    }

    override fun onToolCallUpdated(record: ToolCallStateMachine.ToolCallRecord) {
        ApplicationManager.getApplication().invokeLater {
            logger.debug("[ChatPanelStateListener] onToolCallUpdated: ${record.name} -> ${record.status}")
            when (record.status) {
                ToolCallStateMachine.ToolCallStatus.COMPLETED -> {
                    chatPanel.onToolCallComplete(record.name)
                }
                ToolCallStateMachine.ToolCallStatus.CANCELLED -> {
                    // 取消状态：通知 UI 显示中断标记
                    chatPanel.onToolCallCancelled(record.name)
                }
                else -> {}
            }
        }
    }

    override fun onToolCallRemoved(id: String) {
        // 当前不需要
    }

    override fun onStreamStarted() {
        // 流开始时由 sendMessage 统一处理 UI 状态
    }

    override fun onStreamEnded(hasError: Boolean) {
        // 流结束时由 sendMessage 统一处理 UI 状态
    }
}
