# I18n Helper

![Build](https://github.com/yelog/i18n-helper/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)

<!-- Plugin description -->
Intelligent i18n (internationalization) helper for JavaScript/TypeScript projects in JetBrains IDEs.

## Features

- **Auto-detect i18n frameworks**: vue-i18n, react-i18next, i18next, next-intl, @nuxtjs/i18n, react-intl
- **Inline translation preview**: Shows translations at `t()` / `$t()` call sites
- **Cmd+Click navigation**: Jump from i18n key usage to translation file definition
- **Find Usages**: Find all usages of a translation key from the translation file
- **Cross-language navigation**: Navigate between locale files for the same key
- **Multiple file formats**: JSON, YAML, JS, TS, Properties, TOML

## Supported Directory Structures

The plugin automatically scans these directories:
- `locales`, `locale`, `i18n`, `lang`, `langs`, `messages`, `translations`

## Key Prefix Rules

| Path Pattern | Key Prefix |
|--------------|------------|
| `src/locales/lang/zh_CN/system.ts` | `system.` |
| `src/locales/lang/en.ts` | (none) |
| `src/views/mes/locales/lang/zh_CN/order.ts` | `mes.order.` |

<!-- Plugin description end -->

## Installation

- Using the IDE built-in plugin system:

  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > <kbd>Search for "i18n-helper"</kbd> >
  <kbd>Install</kbd>

- Using JetBrains Marketplace:

  Go to [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID) and install it by clicking the <kbd>Install to ...</kbd> button in case your IDE is running.

  You can also download the [latest release](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID/versions) from JetBrains Marketplace and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

- Manually:

  Download the [latest release](https://github.com/yelog/i18n-helper/releases/latest) and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>


---
Plugin based on the [IntelliJ Platform Plugin Template][template].

[template]: https://github.com/JetBrains/intellij-platform-plugin-template
[docs:plugin-description]: https://plugins.jetbrains.com/docs/intellij/plugin-user-experience.html#plugin-description-and-presentation
