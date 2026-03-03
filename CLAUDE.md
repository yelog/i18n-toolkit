# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

IntelliJ Platform plugin providing i18n (internationalization) support for JavaScript/TypeScript and Java/Spring projects. Detects frameworks like vue-i18n, react-i18next, next-intl, Spring MessageSource and provides inline translation previews, navigation, and find usages.

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
   - Uses I18nModuleResolver for module-aware lookups in multi-module projects
3. **I18nFileListener** → invalidates cache on file changes (VFS async listener)
4. **I18nDocumentListener** → handles content changes with file-level cache invalidation

### Extension Points (plugin.xml)

| Extension | Purpose |
|-----------|---------|
| `I18nInlayHintsProvider` | Shows translations inline at `t()` / `$t()` calls |
| `I18nReferenceContributor` | Enables Cmd+Click navigation from key to definition |
| `I18nLineMarkerProvider` | Cross-locale navigation in translation files |
| `I18nFoldingBuilder` | Code folding for "translation-only" display mode |
| `I18nSettingsConfigurable` | Plugin settings UI |
| `I18nKeyAnnotator` | Highlights missing i18n keys as warnings |
| `I18nKeyCompletionContributor` | Auto-completion for i18n keys in code |
| `I18nDocumentationProvider` | Quick Definition/Documentation (Cmd+hover) for i18n keys |
| `I18nGotoDeclarationHandler` | Handles navigation from folded regions |
| `I18nSearchEverywhereContributor` | Adds "I18n" tab to Search Everywhere (Enter=copy, Ctrl+Enter=navigate) |
| `I18nFindUsagesHandlerFactory` | Find Usages integration for translation keys |
| `I18nStatusBarWidgetFactory` | Display language selector in status bar |
| `I18nDynamicPluginListener` | Dynamic plugin loading support (no restart required) |

### Java/Spring Support (i18n-java.xml)

Optional Java plugin dependency providing Spring i18n features:

| Extension | Purpose |
|-----------|---------|
| `JavaI18nInlayHintsProvider` | Inline hints for `messageSource.getMessage()` and validation annotations |
| `JavaI18nReferenceContributor` | Cmd+Click navigation in Java files |
| `JavaI18nKeyAnnotator` | Missing key detection for Java i18n calls |
| `JavaI18nKeyCompletionContributor` | Auto-completion in Java string literals |
| `JavaI18nDocumentationProvider` | Quick documentation for Java i18n keys |
| `JavaI18nTypedHandler` | Auto-popup completion while typing in Java |

**SpringMessagePatternMatcher** recognizes:
- `messageSource.getMessage("key", args, locale)`
- Custom utilities: `MessageUtils.get()`, `I18nUtil.msg()`, etc.
- Validation annotations: `@NotBlank(message = "{key}")`
- User-configured methods via settings

### Actions

| Action | Shortcut | Purpose |
|--------|----------|---------|
| `I18nSwitchLocaleAction` | - | Cycle display language for inline previews |
| `I18nTranslationsPopupAction` | Cmd+Shift+I (Mac), Ctrl+Shift+I (Win/Linux) | Show all translations for current key (remembers last query) |
| `I18nCopyKeyAction` | - | Copy current i18n key to clipboard |
| `I18nNavigateToFileAction` | Ctrl+J (Mac) | Navigate to translation file for current key |
| `I18nGenerateReportAction` | - | Generate HTML/CSV translation coverage reports |

### Multi-Module Support

**I18nModuleResolver** provides module-aware translation lookup:
- Resolves module names from VirtualFiles using IntelliJ's ModuleUtilCore
- Supports dependency-module fallback for shared translations in Spring Cloud projects
- Cache snapshot maintains per-module TranslationData for scoped key resolution

### Key Models (I18nModels.kt)

- `I18nFramework` - Enum of supported frameworks (VUE_I18N, REACT_I18NEXT, SPRING_MESSAGE, etc.)
- `TranslationEntry` - Single translation with key, value, locale, file position
- `TranslationFile` - Parsed file with locale, module, key prefix
- `TranslationData` - Aggregated translations indexed by key → locale → entry
- `CacheSnapshot` - Thread-safe cache with module-aware translation data

### Key Prefix Generation

Path-based key prefixes enable modular translation organization:
- `src/locales/lang/zh_CN/system.ts` → prefix `system.`
- `src/views/mes/locales/lang/zh_CN/order.ts` → prefix `mes.order.`
- Spring: `messages_zh_CN.properties` → extracted locale from filename

### Cache Invalidation Strategy

The plugin uses a multi-layered cache invalidation approach:

1. **File-level** (`I18nFileListener`): VFS changes trigger cache refresh
2. **Document-level** (`I18nDocumentListener`): Content changes invalidate specific file cache
3. **UI-level** (`I18nUiRefresher`): Daemon restart for inlay hints refresh
4. **Application-level** (`I18nApplicationActivationListener`): Refresh when returning to IDE after extended periods (30s throttle)

## Package Structure

| Package | Purpose |
|---------|---------|
| `service/` | Core services - I18nCacheService (central translation cache), I18nTranslationReporter |
| `scanner/` | I18nDirectoryScanner - finds translation files in standard directories |
| `parser/` | TranslationFileParser - parses JSON/YAML/JS/TS/TOML/Properties files |
| `detector/` | I18nFrameworkDetector - auto-detects i18n frameworks from package.json/build files |
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
| `action/` | User actions (switch locale, show translations popup, copy key, navigate, generate reports) |
| `popup/` | I18nTranslationsPopup - translation search dialog, I18nTranslationEditPopup |
| `quickfix/` | CreateI18nKeyQuickFix - quick fixes for missing keys (JSON/JS/TS/Properties only) |
| `rename/` | I18nKeyRenameProcessor - rename refactoring for translation keys |
| `statusbar/` | I18nStatusBarWidget - display language selector in status bar |
| `settings/` | Plugin settings UI and state persistence |
| `listener/` | I18nFileListener, I18nDocumentListener, I18nDocumentListenerRegistrar, I18nApplicationActivationListener |
| `startup/` | I18nProjectActivity - initialization, I18nDynamicPluginListener - dynamic loading |
| `util/` | Utility classes (I18nKeyGenerator, I18nFunctionResolver, I18nNamespaceResolver, I18nModuleResolver, I18nLocaleUtils, I18nUiRefresher) |
| `usages/` | I18nFindUsagesHandlerFactory - Find Usages integration |
| `spring/` | Java/Spring-specific implementations (JavaI18nInlayHintsProvider, JavaI18nReferenceContributor, etc.) |

## Code Patterns

**Services**: Use `@Service(Service.Level.PROJECT)` with companion `getInstance(project)`.

**Logging**: Use `thisLogger()` extension from `com.intellij.openapi.diagnostic`.

**File scanning**: Skip `node_modules`, `dist`, `build`, `.git` directories.

**Settings**: Project settings stored via `I18nSettingsState` - supports custom i18n functions, display locale, framework override.

**Framework Detection**: `I18nFrameworkDetector` auto-detects from `package.json` (JS/TS) or build files (Spring Boot/Java).

**Namespace Resolution**: `I18nNamespaceResolver` extracts namespace from `useTranslation('ns')`, `useI18n({ namespace: 'ns' })` calls.

**Function Resolution**: `I18nFunctionResolver` combines default functions (`t`, `$t`, `i18n.t`) with user-configured custom functions.

## Dependencies

External libraries (see build.gradle.kts):
- `snakeyaml:2.2` - YAML parsing
- `toml4j:0.7.2` - TOML parsing

Bundled plugins (gradle.properties):
- `JavaScript` - Required for JS/TS/Vue support
- `com.intellij.java` - Required for Java/Spring support

## Testing

- Run all tests: `./gradlew test`
- Run tests with coverage: `./gradlew check` (uses Kover plugin, outputs XML report)
- Run specific test: `./gradlew test --tests "*MyTest*"`
- UI testing: `./gradlew runIdeForUiTests` (robot-server on port 8082)

Test classes extend `BasePlatformTestCase` with `@TestDataPath` annotation.

## Important Notes

- Preserve `<!-- Plugin description -->` markers in README.md - parsed by build
- Plugin supports dynamic loading (no IDE restart required) via `I18nDynamicPluginListener`
- All language extensions (JS/TS/Vue/TSX/Java) are registered separately for each feature
- Navigation behavior (v0.0.2+): Cmd+Click on i18n function names (e.g., `t`) preserves default behavior; only triggers on the key string itself
- Translation search popup (v0.0.2+): Remembers last query and auto-selects text for quick replacement
- Run `./gradlew check` before committing
- Run `./gradlew verifyPlugin` when changing plugin.xml or dependencies
- Quick fix for missing keys: Supports JSON/JS/TS/Properties only (YAML/TOML offsets are estimated)
- Java/Spring support is optional - registered via `i18n-java.xml` with `optional="true"` dependency
- Multi-module projects: Module-aware lookup with dependency fallback for shared translations
