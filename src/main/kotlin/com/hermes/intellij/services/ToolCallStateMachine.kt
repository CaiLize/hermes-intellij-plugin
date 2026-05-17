package com.hermes.intellij.services

import com.hermes.intellij.api.models.ToolCall
import com.intellij.openapi.diagnostic.Logger
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 工具调用状态机 — 集中管理单个对话回合中所有工具调用的生命周期
 *
 * 设计原则:
 * 1. 单一事实来源 (Single Source of Truth): 所有工具调用状态统一在此管理
 * 2. 线程安全: 所有状态转换都是原子操作
 * 3. 幂等性: 重复调用同一转换不会产生副作用
 * 4. 可观测性: 提供状态查询接口供 UI 层订阅
 *
 * 状态转换图:
 *
 *                    ┌──────────┐
 *                    │  INITIAL │
 *                    └────┬─────┘
 *                         │ onToolCall()
 *                         ▼
 *                    ┌──────────┐     onToolCallError()
 *               ┌───►│  RUNNING │───────────────────┐
 *               │    └────┬─────┘                   │
 *               │         │ onToolCallComplete()    │ onStreamError()/onCancel()
 *               │         ▼                         │
 *               │    ┌──────────┐                   │
 *               │    │COMPLETED │                   │
 *               │    └──────────┘                   │
 *               │                                   ▼
 *               │                              ┌──────────┐
 *               └──────────────────────────────│ CANCELLED│
 *                                              └──────────┘
 *
 * 批量操作:
 * - markAllCompleted(): 将所有 RUNNING → COMPLETED
 * - markAllCancelled(): 将所有 RUNNING → CANCELLED
 * - clear(): 清空所有记录，回到 INITIAL
 */
class ToolCallStateMachine {

    private val logger = Logger.getInstance(ToolCallStateMachine::class.java)

    /** 单个工具调用的状态记录 */
    data class ToolCallRecord(
        val id: String,              // 唯一 ID，用于精确追踪
        val name: String,            // 工具名称
        val arguments: String,       // 调用参数
        var status: ToolCallStatus,  // 当前状态
        val createdAt: Long = System.currentTimeMillis(),
        var completedAt: Long? = null
    )

    enum class ToolCallStatus {
        RUNNING,    // 工具正在执行
        COMPLETED,  // 工具已成功完成
        CANCELLED   // 工具被中断
    }

    /** 工具调用集合 — 使用 ConcurrentHashMap 保证线程安全 */
    private val toolCalls = ConcurrentHashMap<String, ToolCallRecord>()

    /** 流式请求是否活跃 */
    private val isStreamActive = AtomicBoolean(false)

    /** 监听器列表 — UI 层可注册回调 */
    private val listeners = mutableListOf<StateChangeListener>()
    private val listenerLock = Any()

    /** 接口：状态变更监听器 */
    interface StateChangeListener {
        fun onToolCallAdded(record: ToolCallRecord)
        fun onToolCallUpdated(record: ToolCallRecord)
        fun onToolCallRemoved(id: String)
        fun onStreamStarted()
        fun onStreamEnded(hasError: Boolean)
    }

    // ============================================================
    // 公共 API — 状态转换
    // ============================================================

    /** 开始新的流式请求 */
    fun startStream() {
        if (isStreamActive.compareAndSet(false, true)) {
            synchronized(listenerLock) {
                listeners.forEach { it.onStreamStarted() }
            }
            clear() // 每次新请求前清空旧状态
            logger.debug("[ToolCallFSM] Stream started, state reset")
        }
    }

    /** 结束流式请求 */
    fun endStream(hasError: Boolean = false) {
        if (isStreamActive.compareAndSet(true, false)) {
            synchronized(listenerLock) {
                listeners.forEach { it.onStreamEnded(hasError) }
            }
            logger.debug("[ToolCallFSM] Stream ended, hasError=$hasError")
        }
    }

    /** 注册状态监听器 */
    fun addListener(listener: StateChangeListener) {
        synchronized(listenerLock) {
            listeners.add(listener)
        }
    }

    /** 移除状态监听器 */
    fun removeListener(listener: StateChangeListener) {
        synchronized(listenerLock) {
            listeners.remove(listener)
        }
    }

    /**
     * 记录一个新的工具调用
     * @return 生成的工具调用记录
     */
    fun addToolCall(toolCall: ToolCall, argumentsFull: String = ""): ToolCallRecord {
        val id = toolCall.id.ifBlank { UUID.randomUUID().toString() }
        val name = toolCall.function?.name ?: "unknown"
        val args = toolCall.function?.arguments ?: argumentsFull

        val record = ToolCallRecord(
            id = id,
            name = name,
            arguments = args,
            status = ToolCallStatus.RUNNING
        )

        // 幂等检查：如果已存在相同名称的 RUNNING 状态调用，更新它而不是添加新的
        val existing = toolCalls.values.find {
            it.name == name && it.status == ToolCallStatus.RUNNING && it.id != id
        }
        if (existing != null) {
            logger.debug("[ToolCallFSM] Found existing running tool call '$name', updating instead of duplicating")
            toolCalls[existing.id] = record
        } else {
            toolCalls[id] = record
        }

        synchronized(listenerLock) {
            listeners.forEach { it.onToolCallAdded(record) }
        }

        logger.info("[ToolCallFSM] Tool call added: id=$id, name=$name, pending=${getRunningCount()}")
        return record
    }

    /**
     * 更新工具调用状态为完成
     * @return 是否成功更新 (幂等：已完成的返回 false)
     */
    fun markToolCallCompleted(id: String): Boolean {
        val record = toolCalls[id] ?: return false
        if (record.status != ToolCallStatus.RUNNING) return false

        val updated = record.copy(status = ToolCallStatus.COMPLETED, completedAt = System.currentTimeMillis())
        toolCalls[id] = updated

        synchronized(listenerLock) {
            listeners.forEach { it.onToolCallUpdated(updated) }
        }

        logger.debug("[ToolCallFSM] Tool call completed: id=$id, name=${record.name}")
        return true
    }

    /**
     * 按工具名称标记为完成 (兼容旧 API)
     */
    fun markToolCallCompletedByName(name: String): Boolean {
        var updated = false
        toolCalls.values.filter { it.name == name && it.status == ToolCallStatus.RUNNING }.forEach {
            markToolCallCompleted(it.id)
            updated = true
        }
        return updated
    }

    /**
     * 将所有 RUNNING 状态的工具调用标记为完成
     * @return 被更新的记录列表
     */
    fun markAllCompleted(): List<ToolCallRecord> {
        val updated = mutableListOf<ToolCallRecord>()
        toolCalls.values.filter { it.status == ToolCallStatus.RUNNING }.forEach { record ->
            val updatedRecord = record.copy(status = ToolCallStatus.COMPLETED, completedAt = System.currentTimeMillis())
            toolCalls[record.id] = updatedRecord
            updated.add(updatedRecord)
        }

        if (updated.isNotEmpty()) {
            synchronized(listenerLock) {
                listeners.forEach { listener ->
                    updated.forEach { listener.onToolCallUpdated(it) }
                }
            }
            logger.debug("[ToolCallFSM] Marked ${updated.size} tool(s) as completed")
        }

        return updated
    }

    /**
     * 将所有 RUNNING 状态的工具调用标记为取消
     * @return 被更新的记录列表
     */
    fun markAllCancelled(): List<ToolCallRecord> {
        val updated = mutableListOf<ToolCallRecord>()
        toolCalls.values.filter { it.status == ToolCallStatus.RUNNING }.forEach { record ->
            val updatedRecord = record.copy(status = ToolCallStatus.CANCELLED, completedAt = System.currentTimeMillis())
            toolCalls[record.id] = updatedRecord
            updated.add(updatedRecord)
        }

        if (updated.isNotEmpty()) {
            synchronized(listenerLock) {
                listeners.forEach { listener ->
                    updated.forEach { listener.onToolCallUpdated(it) }
                }
            }
            logger.debug("[ToolCallFSM] Marked ${updated.size} tool(s) as cancelled")
        }

        return updated
    }

    /**
     * 标记单个工具调用为取消
     */
    fun markToolCallCancelled(id: String): Boolean {
        val record = toolCalls[id] ?: return false
        if (record.status != ToolCallStatus.RUNNING) return false

        val updated = record.copy(status = ToolCallStatus.CANCELLED, completedAt = System.currentTimeMillis())
        toolCalls[id] = updated

        synchronized(listenerLock) {
            listeners.forEach { it.onToolCallUpdated(updated) }
        }

        logger.debug("[ToolCallFSM] Tool call cancelled: id=$id, name=${record.name}")
        return true
    }

    /** 清空所有工具调用记录 */
    fun clear() {
        toolCalls.clear()
        logger.debug("[ToolCallFSM] All tool calls cleared")
    }

    // ============================================================
    // 查询 API
    // ============================================================

    /** 获取所有工具调用记录 (不可变副本) */
    fun getAllRecords(): List<ToolCallRecord> = toolCalls.values.toList()

    /** 获取正在运行的工具调用数量 */
    fun getRunningCount(): Int = toolCalls.values.count { it.status == ToolCallStatus.RUNNING }

    /** 获取已完成工具调用数量 */
    fun getCompletedCount(): Int = toolCalls.values.count { it.status == ToolCallStatus.COMPLETED }

    /** 获取已取消工具调用数量 */
    fun getCancelledCount(): Int = toolCalls.values.count { it.status == ToolCallStatus.CANCELLED }

    /** 是否还有正在运行的工具调用 */
    fun hasRunning(): Boolean = getRunningCount() > 0

    /** 是否所有工具调用都已完成或取消 */
    fun allSettled(): Boolean = toolCalls.values.none { it.status == ToolCallStatus.RUNNING }

    /** 是否有工具调用记录 */
    fun isEmpty(): Boolean = toolCalls.isEmpty()

    /** 流式请求是否活跃 */
    fun isStreamActive(): Boolean = isStreamActive.get()

    /** 获取指定 ID 的工具调用记录 */
    fun getRecord(id: String): ToolCallRecord? = toolCalls[id]

    /** 获取指定名称的工具调用记录列表 */
    fun getRecordsByName(name: String): List<ToolCallRecord> =
        toolCalls.values.filter { it.name == name }
}
