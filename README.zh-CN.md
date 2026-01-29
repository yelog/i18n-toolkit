# I18n Toolkit

![Build](https://github.com/yelog/i18n-toolkit/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)

[English README](README.md) | 中文说明

面向 JetBrains IDE 的 JavaScript/TypeScript i18n 生产力插件：补全、预览、导航、搜索与诊断一体化。

**最新更新 (v0.0.2)**
- 修复导航行为：Cmd+点击函数名保持默认行为（跳转方法声明）
- 翻译搜索弹窗记住上次查询，快速重复过滤
- [完整更新日志](CHANGELOG.md)

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

### 代码智能
- **智能补全**：在 `t()` / `$t()` 等函数中自动补全 i18n key，支持按显示语言翻译匹配
- **翻译预览**：通过内联提示（Inlay）或仅翻译折叠模式查看翻译内容
- **缺失 Key 检测**：高亮显示未定义的 i18n key，一键快速创建翻译（支持 JSON/JS/TS/Properties）

### 导航功能
- **跳转到定义**：Cmd+点击 i18n key 字符串（非函数名）导航到翻译文件
- **跳转到实现**：查看所有语言版本的翻译实现
- **跨语言导航**：在翻译文件中快速切换到其他语言版本
- **查找使用**：查找特定翻译 key 在代码中的所有使用位置

### 搜索与发现
- **Search Everywhere 集成**：专用"I18n"标签页，支持 key 和翻译的模糊搜索
  - 按 Enter 复制 key 到剪贴板
  - 按 Ctrl+Enter 导航到翻译文件
  - 记住上次搜索查询，快速重复过滤
- **状态栏小部件**：IDE 状态栏快速语言切换器
- **快速文档**：悬停在 i18n key 上查看所有翻译

## 快速开始
1. 将语言文件放在标准目录中（如 `locales` / `i18n` / `messages` 等），插件会自动扫描与缓存
2. 在代码中使用 i18n 函数：

```ts
const { t } = useTranslation('common')

const title = t('app.title')
```

3. 打开设置：`Settings/Preferences` -> 搜索 `I18n Toolkit`
   - 选择显示语言
   - 选择显示模式（内联 / 仅翻译）
   - 配置自定义 i18n 函数名（如 `t, $t, i18n.t`）

## 配置说明
> 设置入口：`Settings/Preferences` -> 搜索 `I18n Toolkit`

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
> 快捷键可在 `Settings/Preferences -> Keymap` 中搜索 `I18n Toolkit` 自行配置

| 动作 | 说明 | 默认快捷键 |
| --- | --- | --- |
| Show I18n Translations | 打开翻译搜索弹窗 | macOS: `Cmd+Shift+I`; Windows/Linux: `Ctrl+Shift+I` |
| Navigate to I18n File | 跳转到当前 key 的翻译文件 | `Ctrl+J` |
| Switch I18n Display Language | 循环切换显示语言 | 未绑定 |
| Copy I18n Key | 复制光标处 key | 未绑定 |

## 搜索与导航
- **跳转到声明/实现**：从代码中的 key 使用处导航到翻译文件
- **Cmd+Click 导航**：点击 i18n key 字符串（非函数名）跳转到翻译定义
  - 点击 `t('key')` 中的 `t` 保持默认行为（跳转方法声明）
  - 点击 `'key'` 打开翻译选择器（如果存在多个语言版本）
- **查找使用**：从翻译文件中查找所有使用特定 key 的代码位置
- **Search Everywhere**：使用专用 `I18n` 标签页模糊搜索 key 和翻译
  - Enter：复制 key 到剪贴板
  - Ctrl+Enter：打开翻译文件
  - 记住上次搜索查询，快速重复过滤

## FAQ

**问：补全没有出现或翻译未显示？**
- 确认语言文件位于标准目录（`locales`、`i18n`、`lang` 等）且为支持的格式（JSON、YAML、JS、TS 等）
- 在设置中检查 `Custom I18n Functions`，如使用自定义函数名需添加
- 确认索引已完成且在设置中选择了显示语言
- 验证 i18n 框架是否被检测到（检查状态栏或设置）

**问：翻译文件更新后未生效？**
- 插件会监听文件变化并自动刷新缓存
- 如果变化未被检测到，尝试重新打开文件或项目
- 对于大型项目，初始扫描可能需要几秒钟

**问：导航时函数名和 key 都显示"Choose Declaration"？**
- 此问题已在 0.0.2 版本中修复
- 更新到最新版本，导航仅在点击 key 字符串本身时触发
- 点击函数名（如 `t`）将保持默认 IDE 行为

**问：如何添加对自定义 i18n 函数的支持？**
- 进入 `设置` -> `I18n Toolkit` -> `Custom i18n functions`
- 添加您的函数名，用逗号分隔（如 `t, $t, translate, __`）
- 插件将识别这些函数进行补全和导航

**问：可以在非 JavaScript 项目中使用吗？**
- 目前插件主要针对 JavaScript/TypeScript 框架
- 包含对 Java 项目的 Spring Message 支持
- 其他语言可能在未来版本中添加

**问：如何报告问题或请求功能？**
- 访问 [GitHub Issues](https://github.com/yelog/i18n-toolkit/issues) 页面
- 提供您的设置、框架和遇到的问题的详细信息

## 开发与构建

### 环境要求
- JDK: 21
- Gradle: 9.2.1（请使用 `./gradlew`）
- IntelliJ Platform: 2025.2.5

### 构建命令
```bash
./gradlew buildPlugin          # 构建可分发的 ZIP 文件
./gradlew test                 # 运行测试
./gradlew check                # 运行测试并生成覆盖率报告
./gradlew verifyPlugin         # 验证插件兼容性
./gradlew runIde               # 启动带插件的 IDE 沙箱
```

### 项目结构
- `src/main/kotlin/com/github/yelog/i18ntoolkit/` - 源代码
  - `service/` - 缓存服务和核心逻辑
  - `hint/` - 内联提示提供者
  - `reference/` - 引用贡献者（导航）
  - `completion/` - 代码补全
  - `navigation/` - 跳转到声明/实现
  - `searcheverywhere/` - Search Everywhere 集成
  - `settings/` - 插件设置
- 详见 [CLAUDE.md](CLAUDE.md) 了解详细架构和开发指南

### 贡献指南
欢迎贡献！请：
1. Fork 仓库
2. 创建功能分支
3. 提交前运行 `./gradlew check`
4. 提交带有清晰描述的 Pull Request

如需报告 Bug 或请求功能，请使用 [GitHub Issues](https://github.com/yelog/i18n-toolkit/issues)。

## 许可证
Apache License 2.0，详见 `LICENSE`。
