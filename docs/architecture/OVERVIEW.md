# 系统架构概览

## 整体架构图

```
┌─────────────────────────────────────────────────────────────────────────┐
│                          IntelliJ IDEA Host                             │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │                    Presentation Layer (UI)                      │   │
│  │  ┌─────────────┐  ┌──────────────┐  ┌─────────────────────┐    │   │
│  │  │ ChatPanel   │  │ InputPanel   │  │ MessageBubble       │    │   │
│  │  │ - titleBar  │  │ - textArea   │  │ - avatar           │    │   │
│  │  │ - msgList   │  │ - chips      │  │ - content          │    │   │
│  │  │ - input     │  │ - sendBtn    │  │ - actions          │    │   │
│  │  └─────────────┘  └──────────────┘  └─────────────────────┘    │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                    ↓                                    │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │                    Business Layer (Services)                    │   │
│  │  ┌─────────────────┐  ┌─────────────────┐  ┌────────────────┐  │   │
│  │  │ HermesChatSvc   │  │ ConversationMgr │  │ SelectionSvc   │  │   │
│  │  │ - sendMessage() │  │ - buildRequest()│  │ - listen()     │  │   │
│  │  │ - cancel()      │  │ - save/load()   │  │ - debounce()   │  │   │
│  │  └─────────────────┘  └─────────────────┘  └────────────────┘  │   │
│  │  ┌─────────────────┐  ┌─────────────────┐                      │   │
│  │  │ ConversationStore│  │ ImageStore      │                      │   │
│  │  │ - XML persist   │  │ - disk storage  │                      │   │
│  │  └─────────────────┘  └─────────────────┘                      │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                    ↓                                    │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │                      Data Layer (API)                           │   │
│  │  ┌─────────────────┐  ┌─────────────────┐  ┌────────────────┐  │   │
│  │  │ HermesApiClient │  │ SseStreamParser │  │ Data Models    │  │   │
│  │  │ - HTTP client   │  │ - SSE parse     │  │ - Request      │  │   │
│  │  │ - streamChat()  │  │ - Flow<Delta>   │  │ - Response     │  │   │
│  │  └─────────────────┘  └─────────────────┘  └────────────────┘  │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
                                    ↓
                    ┌───────────────────────────────┐
                    │    Hermes Gateway (Local)     │
                    │    http://127.0.0.1:8642      │
                    └───────────────────────────────┘
```

## 分层职责

| 层级 | 职责 | 关键组件 |
|------|------|----------|
| **Presentation** | UI 渲染、用户交互、事件处理 | ChatPanel, InputPanel, MessageBubble |
| **Business** | 业务逻辑、状态管理、数据协调 | HermesChatService, ConversationManager |
| **Data** | 网络通信、数据序列化、持久化 | HermesApiClient, ConversationStore |

## 服务作用域

| 服务 | 作用域 | 单例模式 |
|------|--------|----------|
| `HermesApiClient` | Application | `getInstance()` |
| `HermesSettingsState` | Application | `getInstance()` |
| `HermesChatService` | Project | `getInstance(project)` |
| `SelectionContextService` | Project | `getInstance(project)` |

## 相关文档

- [LAYERS.md](LAYERS.md) - 各层详细职责
- [DATA_FLOW.md](DATA_FLOW.md) - 核心数据流
- [../modules/SERVICES.md](../modules/SERVICES.md) - 服务层实现
