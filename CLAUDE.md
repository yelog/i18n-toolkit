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
| `I18nTranslationsPopupAction` | Cmd+Shift+I (Mac) | Show all translations for current key |
| `I18nCopyKeyAction` | - | Copy current i18n key to clipboard |
| `I18nNavigateToFileAction` | Ctrl+J (Mac) | Navigate to translation file for current key |

### Key Models (I18nModels.kt)

- `I18nFramework` - Enum of supported frameworks (VUE_I18N, REACT_I18NEXT, etc.)
- `TranslationEntry` - Single translation with key, value, locale, file position
- `TranslationFile` - Parsed file with locale, module, key prefix
- `TranslationData` - Aggregated translations indexed by key → locale → entry

### Key Prefix Generation

Path-based key prefixes enable modular translation organization:
- `src/locales/lang/zh_CN/system.ts` → prefix `system.`
- `src/views/mes/locales/lang/zh_CN/order.ts` → prefix `mes.order.`

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

## Important Notes

- Preserve `<!-- Plugin description -->` markers in README.md - parsed by build
- Plugin supports dynamic loading (no IDE restart required) via `I18nDynamicPluginListener`
- All language extensions (JS/TS/Vue/TSX) are registered separately for each feature
- Run `./gradlew check` before committing
- Run `./gradlew verifyPlugin` when changing plugin.xml or dependencies
- UI testing sandbox: `./gradlew runIdeForUiTests` (robot-server on port 8082)
