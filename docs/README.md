# Hermes IntelliJ Plugin - 文档索引

本文档集涵盖了 Hermes AI Assistant IntelliJ 插件的完整设计、架构和实现细节。

---

## 📁 文档目录

### 🏗️ 架构设计 (architecture/)

| 文档 | 说明 |
|------|------|
| [OVERVIEW.md](architecture/OVERVIEW.md) | 系统架构概览、分层设计 |
| [LAYERS.md](architecture/LAYERS.md) | 各层职责与交互 |
| [DATA_FLOW.md](architecture/DATA_FLOW.md) | 核心数据流（消息、图片、代码上下文） |

### 📦 模块设计 (modules/)

| 文档 | 说明 |
|------|------|
| [ACTIONS.md](modules/ACTIONS.md) | Actions 模块 - 用户交互操作 |
| [API.md](modules/API.md) | API 通信层 - HTTP 客户端与 SSE 解析 |
| [SERVICES.md](modules/SERVICES.md) | 服务层 - 业务逻辑与状态管理 |
| [TOOLWINDOW.md](modules/TOOLWINDOW.md) | UI 组件 - 工具窗口实现 |

### 🗃️ 数据模型 (models/)

| 文档 | 说明 |
|------|------|
| [CHAT_MESSAGE.md](models/CHAT_MESSAGE.md) | ChatMessage 模型与序列化 |
| [CONTENT_PART.md](models/CONTENT_PART.md) | 多模态内容（图片、文件） |
| [MESSAGE_SEGMENT.md](models/MESSAGE_SEGMENT.md) | 消息分段存储设计 |

### 🔌 API 接口 (api/)

| 文档 | 说明 |
|------|------|
| [REQUEST_FORMAT.md](api/REQUEST_FORMAT.md) | 请求格式规范 |
| [RESPONSE_FORMAT.md](api/RESPONSE_FORMAT.md) | 响应格式规范 |
| [SSE_STREAM.md](api/SSE_STREAM.md) | 流式响应协议 |

### 💾 持久化 (persistence/)

| 文档 | 说明 |
|------|------|
| [CONVERSATION_STORE.md](persistence/CONVERSATION_STORE.md) | 对话存储（XML 格式） |
| [IMAGE_STORE.md](persistence/IMAGE_STORE.md) | 图片存储管理 |
| [MIGRATION.md](persistence/MIGRATION.md) | 数据迁移策略 |

### 🎨 UI 设计 (ui/)

| 文档 | 说明 |
|------|------|
| [COLORS.md](ui/COLORS.md) | 颜色系统（亮/暗主题） |
| [MESSAGE_BUBBLE.md](ui/MESSAGE_BUBBLE.md) | 消息气泡渲染逻辑 |
| [MARKDOWN.md](ui/MARKDOWN.md) | Markdown 渲染 |

### 📋 架构决策记录 (decisions/)

| 文档 | 说明 |
|------|------|
| [ADR-001-SVG_ICON_PATH.md](decisions/ADR-001-SVG_ICON_PATH.md) | SVG 图标使用 path 而非 text |
| [ADR-002-MESSAGE_SEGMENT.md](decisions/ADR-002-MESSAGE_SEGMENT.md) | 消息分段存储方案选择 |
| [ADR-003-TOOL_CALL_ORDER.md](decisions/ADR-003-TOOL_CALL_ORDER.md) | 工具调用顺序恢复方案 |

---

## 🔗 相关文档

- [项目架构 (AGENTS.md)](/mnt/d/IdeaProjects/hermes-intellij-plugin/AGENTS.md) - 项目结构与技术栈
- [项目说明 (README.md)](/mnt/d/IdeaProjects/hermes-intellij-plugin/README.md) - 快速开始指南

---

## 📝 文档维护

- 新增功能时同步更新相关文档
- 架构变更需创建新的 ADR 文档
- 保持文档与代码的一致性

最后更新：2026-05-12
