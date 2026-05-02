# Hermes AI Assistant - IntelliJ IDEA Plugin

[![Version](https://img.shields.io/badge/version-1.0.0-blue.svg)](https://github.com/nousresearch/hermes-agent)
[![Platform](https://img.shields.io/badge/Platform-IntelliJ%20IDEA%202024.1+-green.svg)](https://www.jetbrains.com/idea/)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.25-purple.svg)](https://kotlinlang.org/)

连接本地部署的 **Hermes Agent**，在 IntelliJ IDEA 中提供 AI 驱动的智能编码辅助。

---

## ✨ 核心特性

- 🗨️ **智能对话** - 与 Hermes AI 进行自然语言交流，获取编码建议
- 📝 **代码上下文** - 一键发送选中代码或当前文件作为上下文
- 🖼️ **多模态支持** - 支持截图、图片粘贴，视觉化问题描述
- ⚡ **流式响应** - 实时显示 AI 思考过程和回答
- 🔌 **代码操作** - 直接插入或替换编辑器中的代码
- 💾 **对话持久化** - 自动保存历史对话，支持多会话管理
- 🎨 **主题适配** - 完美支持 IntelliJ 亮色/暗色主题

---

## 📦 安装

### 方式一：从本地安装（推荐）

1. 构建插件：
   ```bash
   cd hermes-intellij-plugin
   ./gradlew buildPlugin
   ```

2. 插件位于 `release/hermes-ai-assistant-1.0.0.zip`

3. 在 IntelliJ IDEA 中：
   - `Settings → Plugins → ⚙️ → Install Plugin from Disk...`
   - 选择生成的 ZIP 文件
   - 重启 IDE

### 方式二：开发模式运行

```bash
./gradlew runIde
```

这会启动一个新的 IntelliJ IDEA 沙盒实例，插件已自动加载。

---

## 🔧 配置

安装插件后，在 `Settings → Hermes AI` 中配置：

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| **API Endpoint** | `http://127.0.0.1:8642/v1` | Hermes Gateway 地址 |
| **Model** | `hermes-agent` | 使用的 AI 模型 |
| **API Key** | (空) | 可选的认证密钥 |

> 💡 **提示**: 确保 Hermes Agent 的 Gateway 服务正在运行。

---

## 🚀 使用指南

### 打开聊天面板

- 点击右侧工具栏的 **Hermes AI** 图标
- 或使用快捷键 `Alt + Shift + H`（发送选中代码时）

### 发送消息

| 操作 | 方法 |
|------|------|
| 发送文本 | 输入后按 `Enter` |
| 换行 | `Shift + Enter` |
| 取消响应 | `Esc` 或点击停止按钮 |
| 粘贴图片 | `Ctrl + V`（截图直接粘贴） |
| 粘贴文件 | 拖拽文件到输入框 |

### 发送代码上下文

**方法 1：右键菜单**
1. 在编辑器中选中代码
2. 右键 → `Hermes AI` → `Send Selection to Hermes`
3. 或使用快捷键 `Alt + Shift + H`

**方法 2：发送当前文件**
1. 右键 → `Hermes AI` → `Send File to Hermes`

### 代码操作

AI 生成的代码块提供以下操作：
- 📋 **Copy** - 复制到剪贴板
- 📥 **Insert** - 插入到当前光标位置
- 🔄 **Replace** - 替换选中的代码

### 对话管理

- **新对话**: 点击标题栏的下拉箭头 → 选择新对话
- **切换对话**: 下拉菜单中选择历史对话
- **删除对话**: 下拉菜单中点击 🗑️ 图标

---

## 🏗️ 项目结构

```
hermes-intellij-plugin/
├── build.gradle.kts          # 构建配置
├── gradle.properties         # 版本和兼容性
├── AGENTS.md                 # 架构文档
├── src/main/
│   ├── kotlin/
│   │   └── com/hermes/intellij/
│   │       ├── actions/      # 用户操作
│   │       ├── api/          # API 客户端
│   │       ├── model/        # 数据模型
│   │       ├── services/     # 业务服务
│   │       ├── settings/     # 设置配置
│   │       └── toolwindow/   # UI 组件
│   └── resources/
│       ├── META-INF/
│       │   └── plugin.xml    # 插件描述
│       ├── icons/            # SVG 图标
│       └── messages/         # 国际化
```

详细架构文档请参阅 [AGENTS.md](./AGENTS.md)

---

## 🛠️ 技术栈

| 技术 | 版本 |
|------|------|
| Kotlin | 1.9.25 |
| IntelliJ Platform | 2.2.1 |
| kotlinx.serialization | 1.6.3 |
| Kotlin Coroutines | 1.8.1 |
| Gradle | 8.14.4 |
| JDK | 17 |

**兼容性**: IntelliJ IDEA 2024.1 - 2027.3

---

## 📸 功能预览

### 聊天界面
- 流式响应显示
- Markdown 代码高亮
- 图片预览
- 代码上下文标签

### 代码上下文
- 选区高亮显示
- 文件路径标识
- 快速删除

### 多模态支持
- 截图直接粘贴
- 图片压缩
- 文件拖拽上传

---

## 🔑 快捷键

| 快捷键 | 功能 |
|--------|------|
| `Enter` | 发送消息 |
| `Shift + Enter` | 换行 |
| `Esc` | 取消/清空 |
| `Ctrl + V` | 粘贴（文本/图片/文件） |
| `Alt + Shift + H` | 发送选中代码 |

---

## 🐛 已知问题

详见 [Issues](https://github.com/nousresearch/hermes-agent/issues)

---

## 📝 更新日志

### v1.0.0 (2026-05-02)

**核心功能**
- ✅ 基础聊天功能
- ✅ 流式响应支持
- ✅ 代码上下文集成
- ✅ 多模态（图片/文件）支持
- ✅ 对话持久化
- ✅ 多会话管理

---

## 🤝 贡献

欢迎提交 Issue 和 Pull Request！

1. Fork 本项目
2. 创建特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 开启 Pull Request

---

## 📄 许可证

本项目采用 MIT 许可证 - 详见 [LICENSE](../LICENSE) 文件

---

## 🔗 相关链接

- [Hermes Agent 主项目](https://github.com/nousresearch/hermes-agent)
- [Hermes 文档](https://hermes-agent.nousresearch.com/docs)
- [IntelliJ 插件开发文档](https://plugins.jetbrains.com/docs/intellij/welcome.html)

---

**Made with ❤️ by Hermes Team**
