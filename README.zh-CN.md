# I18n Helper

![Build](https://github.com/yelog/i18n-helper/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)

[English README](README.md) | 中文说明

面向 JetBrains IDE 的 JavaScript/TypeScript i18n 生产力插件：补全、预览、导航、搜索与诊断一体化。

## 目录
- [功能概览](#功能概览)
- [快速开始](#快速开始)
- [配置说明](#配置说明)
- [目录扫描与 Key 规则](#目录扫描与-key-规则)
- [支持范围](#支持范围)
- [常用动作与快捷键](#常用动作与快捷键)
- [搜索与导航](#搜索与导航)
- [FAQ](#faq)
- [开发与构建](#开发与构建)
- [许可证](#许可证)

## 功能概览
- 智能补全：在 `t()` / `$t()` 等函数中提供 i18n key 列表，支持按显示语言翻译匹配
- 翻译预览：内联提示（Inlay）或“仅翻译模式”（折叠原 key 显示翻译）
- 语义导航：Goto Declaration / Goto Implementation，Cmd+Click 跳转
- 跨语言跳转：翻译文件行标记快速跳转到其他语言
- 质量与修复：缺失 key 标红并支持一键创建（JSON/JS/TS/Properties）
- 搜索与效率：Search Everywhere 标签页、翻译弹窗、复制 key、状态栏切换语言

## 快速开始
1. 将语言文件放在标准目录中（如 `locales` / `i18n` / `messages` 等），插件会自动扫描与缓存
2. 在代码中使用 i18n 函数：

```ts
const { t } = useTranslation('common')

const title = t('app.title')
```

3. 打开设置：`Settings/Preferences` -> 搜索 `I18n Helper`
   - 选择显示语言
   - 选择显示模式（内联 / 仅翻译）
   - 配置自定义 i18n 函数名（如 `t, $t, i18n.t`）

## 配置说明
> 设置入口：`Settings/Preferences` -> 搜索 `I18n Helper`

| 配置项 | 说明 | 影响范围 |
| --- | --- | --- |
| 显示语言 | 选择显示语言（来自已扫描 locale 列表） | 补全右侧翻译、内联提示、翻译折叠、跳转到翻译文件 |
| 显示模式 | `Show translation after key` / `Show translation only` | 内联翻译提示或折叠显示翻译 |
| Framework | `auto` 或手动指定框架 | 框架识别行为 |
| Custom I18n Functions | 逗号分隔函数名 | 识别 i18n 调用、补全与导航 |
| Shortcuts | 语言切换与翻译弹窗快捷键 | 操作效率 |

显示语言未设置时的回退顺序：
- `zh_CN` -> `zh` -> `en` -> 首个可用语言

## 目录扫描与 Key 规则
### 扫描目录
插件会递归扫描以下目录（自动忽略 `.git` / `node_modules` / `dist` / `build` 等）：
- `locales`, `locale`, `i18n`, `lang`, `langs`, `messages`, `translations`

### 支持的文件格式
- JSON、YAML/YML、TOML、Properties、JavaScript、TypeScript
- JS/TS 文件要求导出对象字面量（如 `export default { ... }`）

### Key 前缀规则（基于路径）
插件通过路径推导 `locale` 与 `keyPrefix`：

| 文件路径 | 识别 locale | keyPrefix 示例 |
| --- | --- | --- |
| `src/locales/en/common.json` | `en` | `common.` |
| `src/locales/en.json` | `en` | (无) |
| `src/views/mes/locales/lang/zh_CN/order.ts` | `zh_CN` | `mes.order.` |
| `src/messages/zh_CN.properties` | `zh_CN` | (无，messages 不作为前缀) |

### 命名空间（Namespace）
支持从 `useTranslation` / `useI18n` / `useTranslations` 自动解析命名空间：

```ts
const { t } = useTranslation('user')

t('profile.name') // 实际匹配 user.profile.name
```

## 支持范围
- 语言：JavaScript、TypeScript、Vue、TSX
- 框架自动识别：vue-i18n、react-i18next、i18next、next-intl、@nuxtjs/i18n、react-intl、spring message
- IDE：支持所有集成 JavaScript 插件的 JetBrains IDE（IntelliJ IDEA / WebStorm / Rider 等）

限制说明：
- 缺失 key 的一键创建仅支持 JSON / JS / TS / Properties
- YAML/TOML 偏移为估算值，格式化后可能略有偏差

## 常用动作与快捷键
> 快捷键可在 `Settings/Preferences -> Keymap` 中搜索 `I18n Helper` 自行配置

| 动作 | 说明 | 默认快捷键 |
| --- | --- | --- |
| Show I18n Translations | 打开翻译搜索弹窗 | macOS: `Cmd+Shift+I`; Windows/Linux: `Ctrl+Shift+I` |
| Navigate to I18n File | 跳转到当前 key 的翻译文件 | `Ctrl+J` |
| Switch I18n Display Language | 循环切换显示语言 | 未绑定 |
| Copy I18n Key | 复制光标处 key | 未绑定 |

## 搜索与导航
- Goto Declaration / Goto Implementation 从 key 使用处跳转到翻译定义
- Cmd+Click 直接导航
- Find Usages 从翻译文件反查使用位置
- Search Everywhere: `I18n` 标签页支持 key 与翻译模糊搜索
  - Enter: 复制 key
  - Ctrl+Enter: 打开翻译文件

## FAQ
**补全没有出现 / 翻译未显示？**
- 确认语言文件位于标准目录且格式受支持
- 在设置中检查 `Custom I18n Functions`
- 确认已选择显示语言或索引完成

**翻译文件更新后未生效？**
- 插件会监听文件变化自动刷新
- 如需强制刷新，可重启 IDE 或重新打开项目

## 开发与构建
- JDK: 21
- Gradle: 9.2.1（请使用 `./gradlew`）
- IntelliJ Platform: 2025.2.5

常用命令：
```bash
./gradlew buildPlugin
./gradlew test
./gradlew check
./gradlew runIde
```

## 许可证
Apache License 2.0，详见 `LICENSE`。
