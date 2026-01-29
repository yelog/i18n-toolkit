# I18n Toolkit

![Build](https://github.com/yelog/i18n-toolkit/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)

English | [Chinese README](README.zh-CN.md)

A productivity plugin for JavaScript/TypeScript i18n in JetBrains IDEs: completion, preview, navigation, search, and diagnostics.

**Latest Updates (v0.0.2)**
- Fixed navigation behavior: Cmd+Click on function names preserves default behavior
- Translation search popup remembers last query for quick re-filtering
- [Full Changelog](CHANGELOG.md)

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

### Code Intelligence
- **Smart Completion**: Auto-complete i18n keys in `t()` / `$t()` and custom functions with translation previews
- **Translation Preview**: View translations inline via inlay hints or translation-only folding mode
- **Missing Key Detection**: Highlights undefined i18n keys with one-click quick fix to create translations (JSON/JS/TS/Properties)

### Navigation
- **Go to Definition**: Cmd+Click on i18n key strings (not function names) to navigate to translation files
- **Go to Implementation**: Jump to translation implementations across all locales
- **Cross-Locale Navigation**: Navigate between different language versions from translation files
- **Find Usages**: Find all code locations using a specific translation key

### Search & Discovery
- **Search Everywhere Integration**: Dedicated "I18n" tab for fuzzy search across keys and translations
  - Press Enter to copy key to clipboard
  - Press Ctrl+Enter to navigate to translation file
  - Remembers last search query for quick re-filtering
- **Status Bar Widget**: Quick locale switcher in IDE status bar
- **Quick Documentation**: Hover over i18n keys to see all translations

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
- **Go to Declaration / Go to Implementation**: Navigate from key usage in code to translation files
- **Cmd+Click Navigation**: Click on the i18n key string (not the function name) to jump to translation definition
  - Clicking on `t` in `t('key')` preserves default behavior (jump to method declaration)
  - Clicking on `'key'` opens translation chooser if multiple locales exist
- **Find Usages**: From translation file, find all code locations using a specific key
- **Search Everywhere**: Use the dedicated `I18n` tab for fuzzy search across keys and translations
  - Enter: copy key to clipboard
  - Ctrl+Enter: open translation file
  - Last search query is remembered for quick re-filtering

## FAQ

**Q: Completion or translations do not show up**
- Ensure locale files are under supported directories (`locales`, `i18n`, `lang`, etc.) and in supported formats (JSON, YAML, JS, TS, etc.)
- Check `Custom i18n functions` in settings if using custom function names
- Make sure indexing is complete and a display language is selected in settings
- Verify that your i18n framework is detected (check status bar or settings)

**Q: Locale file updates are not reflected**
- The plugin listens for file changes and refreshes the cache automatically
- If changes aren't detected, try reopening the file or project
- For large projects, initial scanning may take a few seconds

**Q: Navigation shows "Choose Declaration" for both function and key**
- This has been fixed in version 0.0.2
- Update to the latest version where navigation only triggers on the key string itself
- Clicking on the function name (e.g., `t`) preserves default IDE behavior

**Q: How do I add support for custom i18n functions?**
- Go to `Settings` -> `I18n Toolkit` -> `Custom i18n functions`
- Add your function names separated by commas (e.g., `t, $t, translate, __`)
- The plugin will recognize these functions for completion and navigation

**Q: Can I use this with non-JavaScript projects?**
- Currently, the plugin focuses on JavaScript/TypeScript frameworks
- Spring Message support is included for Java projects
- Other languages may be added in future versions

**Q: How do I report issues or request features?**
- Visit the [GitHub Issues](https://github.com/yelog/i18n-toolkit/issues) page
- Provide details about your setup, framework, and the issue you're experiencing

## Development

### Prerequisites
- JDK: 21
- Gradle: 9.2.1 (use `./gradlew`)
- IntelliJ Platform: 2025.2.5

### Build Commands
```bash
./gradlew buildPlugin          # Build distributable ZIP
./gradlew test                 # Run tests
./gradlew check                # Run tests with coverage
./gradlew verifyPlugin         # Verify plugin compatibility
./gradlew runIde               # Launch IDE sandbox with plugin
```

### Project Structure
- `src/main/kotlin/com/github/yelog/i18ntoolkit/` - Source code
  - `service/` - Cache service and core logic
  - `hint/` - Inlay hints provider
  - `reference/` - Reference contributor for navigation
  - `completion/` - Code completion
  - `navigation/` - Go to Declaration/Implementation
  - `searcheverywhere/` - Search Everywhere integration
  - `settings/` - Plugin settings
- See [CLAUDE.md](CLAUDE.md) for detailed architecture and development guidelines

### Contributing
Contributions are welcome! Please:
1. Fork the repository
2. Create a feature branch
3. Run `./gradlew check` before committing
4. Submit a pull request with clear description

For bug reports or feature requests, please use [GitHub Issues](https://github.com/yelog/i18n-toolkit/issues).

## License
Apache License 2.0. See `LICENSE`.

---
Plugin based on the [IntelliJ Platform Plugin Template][template].

[template]: https://github.com/JetBrains/intellij-platform-plugin-template
[docs:plugin-description]: https://plugins.jetbrains.com/docs/intellij/plugin-user-experience.html#plugin-description-and-presentation
