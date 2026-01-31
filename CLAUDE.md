# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

IntelliJ Platform plugin providing i18n (internationalization) support for JavaScript/TypeScript projects. Detects frameworks like vue-i18n, react-i18next, next-intl, and provides inline translation previews, navigation, and find usages.

## Build Commands

```bash
./gradlew buildPlugin          # Build distributable ZIP
./gradlew runIde               # Launch IDE sandbox with plugin
./gradlew check                # Run tests with coverage
./gradlew test --tests "*MyTest*"  # Run specific tests
./gradlew verifyPlugin         # Verify plugin compatibility
```

Always use the Gradle wrapper. JDK 21 required.

## Architecture

### Core Data Flow

1. **I18nProjectActivity** (startup) → initializes I18nCacheService
2. **I18nCacheService** (project service) → central translation cache
   - Uses I18nDirectoryScanner to find translation files in standard dirs (`locales`, `i18n`, `lang`, etc.)
   - Uses TranslationFileParser to parse JSON/YAML/JS/TS/TOML/Properties
   - Uses I18nKeyGenerator to compute key prefixes from file paths
3. **I18nFileListener** → invalidates cache on file changes

### Extension Points (plugin.xml)

| Extension | Purpose |
|-----------|---------|
| `I18nInlayHintsProvider` | Shows translations inline at `t()` / `$t()` calls |
| `I18nReferenceContributor` | Enables Cmd+Click navigation from key to definition |
| `I18nLineMarkerProvider` | Cross-locale navigation in translation files |
| `I18nFoldingBuilder` | Code folding for i18n calls |
| `I18nSettingsConfigurable` | Plugin settings UI |
| `I18nKeyAnnotator` | Highlights missing i18n keys as warnings |
| `I18nKeyCompletionContributor` | Auto-completion for i18n keys in code |
| `I18nDocumentationProvider` | Quick Definition/Documentation (Cmd+hover) for i18n keys |
| `I18nGotoDeclarationHandler` | Handles navigation from folded regions |
| `I18nSearchEverywhereContributor` | Adds "I18n" tab to Search Everywhere (Enter=copy, Ctrl+Enter=navigate) |

### Actions

| Action | Shortcut | Purpose |
|--------|----------|---------|
| `I18nSwitchLocaleAction` | - | Cycle display language for inline previews |
| `I18nTranslationsPopupAction` | Cmd+Shift+I (Mac), Ctrl+Shift+I (Win/Linux) | Show all translations for current key (remembers last query) |
| `I18nCopyKeyAction` | - | Copy current i18n key to clipboard |
| `I18nNavigateToFileAction` | Ctrl+J (Mac) | Navigate to translation file for current key |

### Status Bar

- `I18nStatusBarWidget` - Displays current display language in status bar (right side, before memory indicator)
  - Click to show dropdown menu for switching language
  - Automatically updates when language is changed via settings or actions

### Key Models (I18nModels.kt)

- `I18nFramework` - Enum of supported frameworks (VUE_I18N, REACT_I18NEXT, etc.)
- `TranslationEntry` - Single translation with key, value, locale, file position
- `TranslationFile` - Parsed file with locale, module, key prefix
- `TranslationData` - Aggregated translations indexed by key → locale → entry

### Key Prefix Generation

Path-based key prefixes enable modular translation organization:
- `src/locales/lang/zh_CN/system.ts` → prefix `system.`
- `src/views/mes/locales/lang/zh_CN/order.ts` → prefix `mes.order.`

## Package Structure

| Package | Purpose |
|---------|---------|
| `service/` | Core services - I18nCacheService (central translation cache) |
| `scanner/` | I18nDirectoryScanner - finds translation files in standard directories |
| `parser/` | TranslationFileParser - parses JSON/YAML/JS/TS/TOML/Properties files |
| `detector/` | I18nFrameworkDetector - auto-detects i18n frameworks from package.json |
| `model/` | Data models (I18nFramework, TranslationEntry, TranslationFile, TranslationData) |
| `hint/` | I18nInlayHintsProvider - inline translation previews |
| `folding/` | I18nFoldingBuilder - code folding for translation-only display mode |
| `reference/` | I18nReferenceContributor - Cmd+Click navigation, I18nUsageSearcher - Find Usages |
| `navigation/` | Go to Declaration/Implementation handlers |
| `completion/` | I18nKeyCompletionContributor - key auto-completion |
| `annotator/` | I18nKeyAnnotator - highlights missing i18n keys |
| `documentation/` | I18nDocumentationProvider - Quick Definition/Documentation (Cmd+hover) |
| `marker/` | I18nLineMarkerProvider - cross-locale navigation in translation files |
| `searcheverywhere/` | I18nSearchEverywhereContributor - "I18n" tab in Search Everywhere |
| `action/` | User actions (switch locale, show translations popup, copy key, navigate) |
| `popup/` | I18nTranslationsPopup - translation search dialog |
| `quickfix/` | Quick fixes for missing keys (JSON/JS/TS/Properties only) |
| `statusbar/` | I18nStatusBarWidget - display language selector in status bar |
| `settings/` | Plugin settings UI and state persistence |
| `listener/` | I18nFileListener - cache invalidation on file changes, I18nDocumentListenerRegistrar |
| `startup/` | I18nProjectActivity - initialization, I18nDynamicPluginListener - dynamic loading |
| `util/` | Utility classes (I18nKeyGenerator - path-based key prefixes) |
| `usages/` | I18nFindUsagesHandlerFactory - Find Usages integration |

## Code Patterns

**Services**: Use `@Service(Service.Level.PROJECT)` with companion `getInstance(project)`.

**Logging**: Use `thisLogger()` extension from `com.intellij.openapi.diagnostic`.

**File scanning**: Skip `node_modules`, `dist`, `build`, `.git` directories.

**Settings**: Project settings stored via `I18nSettingsState` - supports custom i18n functions, display locale, framework override.

**Framework Detection**: `I18nFrameworkDetector` auto-detects from `package.json` (JS/TS) or build files (Spring Boot/Java).

## Dependencies

External libraries (see build.gradle.kts):
- `snakeyaml:2.2` - YAML parsing
- `toml4j:0.7.2` - TOML parsing

## Testing

- Run all tests: `./gradlew test`
- Run tests with coverage: `./gradlew check` (uses Kover plugin, outputs XML report)
- Run specific test: `./gradlew test --tests "*MyTest*"`
- UI testing: `./gradlew runIdeForUiTests` (robot-server on port 8082)

## Important Notes

- Preserve `<!-- Plugin description -->` markers in README.md - parsed by build
- Plugin supports dynamic loading (no IDE restart required) via `I18nDynamicPluginListener`
- All language extensions (JS/TS/Vue/TSX) are registered separately for each feature
- Navigation behavior (v0.0.2+): Cmd+Click on i18n function names (e.g., `t`) preserves default behavior; only triggers on the key string itself
- Translation search popup (v0.0.2+): Remembers last query and auto-selects text for quick replacement
- Run `./gradlew check` before committing
- Run `./gradlew verifyPlugin` when changing plugin.xml or dependencies
- Quick fix for missing keys: Supports JSON/JS/TS/Properties only (YAML/TOML offsets are estimated)
