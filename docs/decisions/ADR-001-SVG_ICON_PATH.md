# ADR-001: SVG 图标使用 Path 而非 Text

## 状态

✅ 已采纳（2026-05-12）

## 背景

Hermes IntelliJ 插件的 SVG 图标最初使用 `<text>` 元素绘制字母 "H"：

```xml
<svg viewBox="0 0 13 13" xmlns="http://www.w3.org/2000/svg">
  <circle cx="6.5" cy="6.5" r="6.5" fill="#8B5CF6"/>
  <text x="6.5" y="9.5" font-family="Arial" font-size="8" fill="white" text-anchor="middle">H</text>
</svg>
```

## 问题

在小尺寸（13x13）图标中，IntelliJ 平台的 SVG 渲染器对 `<text>` 元素支持不佳：

1. **字体依赖**：Arial 字体可能无法加载，导致文字不显示
2. **渲染模糊**：小尺寸文字渲染后边缘模糊
3. **位置偏移**：文字基线在不同系统上可能偏移

## 决策

改用 `<path>` 元素绘制字母 "H"，完全避免字体依赖：

```xml
<svg viewBox="0 0 13 13" xmlns="http://www.w3.org/2000/svg">
  <circle cx="6.5" cy="6.5" r="6.5" fill="#8B5CF6"/>
  <path d="M4 3v7h1.5V6.8h2V10H9V3H7.5v2.3h-2V3H4z" fill="white"/>
</svg>
```

## 实施

### 修改文件

1. `src/main/resources/icons/hermes_13.svg` - 工具窗口图标（13x13）
2. `src/main/resources/META-INF/pluginIcon.svg` - 插件列表图标（40x40）

### Path 数据

字母 "H" 的路径数据（适用于 13x13  viewBox）：

```
M4 3v7h1.5V6.8h2V10H9V3H7.5v2.3h-2V3H4z
```

## 影响

### 正面影响

- ✅ 图标在所有系统上渲染一致
- ✅ 无字体依赖
- ✅ 边缘清晰，无模糊
- ✅ 文件体积略小

### 负面影响

- ⚠️ 修改图标需要图形工具或手动计算 path 数据
- ⚠️ 无法通过修改 font-size 调整大小

## 后续

如需修改图标设计，建议使用矢量图形工具（如 Figma、Inkscape）导出 path 数据。

## 相关文档

- [../modules/TOOLWINDOW.md](../modules/TOOLWINDOW.md) - UI 组件
- [../ui/COLORS.md](../ui/COLORS.md) - 颜色系统
