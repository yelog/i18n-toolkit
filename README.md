# I18n Toolkit

![Build](https://github.com/yelog/i18n-toolkit/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)

English | [Chinese README](README.zh-CN.md)

A productivity plugin for JavaScript/TypeScript i18n in JetBrains IDEs: completion, preview, navigation, search, and diagnostics.

<!-- Plugin description -->
Intelligent i18n (internationalization) helper for JavaScript/TypeScript projects in JetBrains IDEs.
<br><br>
Key features:
<ul>
  <li>Auto-detects popular i18n frameworks and scans multi-format locale files</li>
  <li>Smart key completion with display-language translation matching</li>
  <li>Inline translation preview, Quick Documentation, and translation search popup</li>
  <li>Go to Declaration/Implementation, cross-locale navigation, and Find Usages</li>
</ul>
<!-- Plugin description end -->

## Table of contents
- [Features](#features)
- [Quick start](#quick-start)
- [Settings](#settings)
- [Scanning and key rules](#scanning-and-key-rules)
- [Supported scope](#supported-scope)
- [Actions and shortcuts](#actions-and-shortcuts)
- [Navigation and search](#navigation-and-search)
- [FAQ](#faq)
- [Development](#development)
- [License](#license)

## Features
- Smart completion for i18n keys in `t()` / `$t()` and custom functions
- Translation preview via inlay hints or translation-only folding
- Go to Declaration / Go to Implementation, Cmd+Click navigation
- Cross-locale navigation from translation files
- Missing-key diagnostics with one-click create (JSON/JS/TS/Properties)
- Search Everywhere tab for i18n keys and translations
- Status bar locale switcher and utility actions

## Quick start
1. Place locale files under standard i18n directories (see scanning rules below). The plugin scans and caches automatically.
2. Use i18n keys in code:

```ts
const { t } = useTranslation('common')

const title = t('app.title')
```

3. Open settings: `Settings/Preferences` -> search for `I18n Toolkit`.
   - Select Display language
   - Choose display mode (inline or translation-only)
   - Configure custom i18n functions if needed

## Settings
> Open: `Settings/Preferences` -> search for `I18n Toolkit`

| Setting | Description | Affects |
| --- | --- | --- |
| Display language | Choose the display locale from scanned locales | Completion translation, inlay hints, folding, navigation target |
| Display mode | `Show translation after key` / `Show translation only` | Inline hints or translation-only folding |
| Framework | `auto` or a specific framework | Framework detection behavior |
| Custom i18n functions | Comma-separated function names | Completion and navigation detection |
| Shortcuts | Locale switch and translation popup | Productivity actions |

Display language fallback when not set:
- `zh_CN` -> `zh` -> `en` -> first available locale

## Scanning and key rules
### Scanned directories
The plugin recursively scans these folders (ignores `.git`, `node_modules`, `dist`, `build`, etc.):
- `locales`, `locale`, `i18n`, `lang`, `langs`, `messages`, `translations`

### Supported file formats
- JSON, YAML/YML, TOML, Properties, JavaScript, TypeScript
- JS/TS files should export an object literal (for example `export default { ... }`)

### Key prefix rules (path based)
The plugin derives `locale` and `keyPrefix` from file paths.

| File path | Locale | keyPrefix example |
| --- | --- | --- |
| `src/locales/en/common.json` | `en` | `common.` |
| `src/locales/en.json` | `en` | (none) |
| `src/views/mes/locales/lang/zh_CN/order.ts` | `zh_CN` | `mes.order.` |
| `src/messages/zh_CN.properties` | `zh_CN` | (none, messages is not a prefix) |

### Namespace support
Namespace is resolved from `useTranslation`, `useI18n`, and `useTranslations`:

```ts
const { t } = useTranslation('user')

t('profile.name') // resolves to user.profile.name
```

## Supported scope
- Languages: JavaScript, TypeScript, Vue, TSX
- Frameworks (auto-detect): vue-i18n, react-i18next, i18next, next-intl, @nuxtjs/i18n, react-intl, spring message
- IDEs: JetBrains IDEs with JavaScript support (IntelliJ IDEA, WebStorm, Rider, etc.)

Limitations:
- Missing-key quick fix supports JSON / JS / TS / Properties only
- YAML/TOML offsets are estimated and may differ after formatting

## Actions and shortcuts
> Configure shortcuts in `Settings/Preferences -> Keymap` and search for `I18n Toolkit`.

| Action | Description | Default shortcut |
| --- | --- | --- |
| Show I18n Translations | Open translation search popup | macOS: `Cmd+Shift+I`; Windows/Linux: `Ctrl+Shift+I` |
| Navigate to I18n File | Jump to translation file for current key | `Ctrl+J` |
| Switch I18n Display Language | Cycle display locale | Unassigned |
| Copy I18n Key | Copy key at caret | Unassigned |

## Navigation and search
- Go to Declaration / Go to Implementation from key usage
- Cmd+Click on key to navigate to translation definition
- Find Usages from translation file to usages
- Search Everywhere: `I18n` tab supports key and translation fuzzy search
  - Enter: copy key
  - Ctrl+Enter: open translation file

## FAQ
**Completion or translations do not show up**
- Ensure locale files are under supported directories and formats
- Check `Custom i18n functions` in settings
- Make sure indexing is complete and a display language is selected

**Locale file updates are not reflected**
- The plugin listens for file changes and refreshes automatically
- If needed, reopen the project or restart the IDE

## Development
- JDK: 21
- Gradle: 9.2.1 (use `./gradlew`)
- IntelliJ Platform: 2025.2.5

Commands:
```bash
./gradlew buildPlugin
./gradlew test
./gradlew check
./gradlew runIde
```

## License
Apache License 2.0. See `LICENSE`.

---
Plugin based on the [IntelliJ Platform Plugin Template][template].

[template]: https://github.com/JetBrains/intellij-platform-plugin-template
[docs:plugin-description]: https://plugins.jetbrains.com/docs/intellij/plugin-user-experience.html#plugin-description-and-presentation
