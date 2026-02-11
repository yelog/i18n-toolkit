<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# i18n-toolkit Changelog

## [Unreleased]
## 0.0.5
### Added
- Java/Spring i18n support for string literal keys, including inline hints, key completion, quick documentation, unresolved-key annotation, and Cmd/Ctrl+Click references
- Module-aware i18n lookup for multi-module projects, with dependency-module fallback when resolving keys
- Detection of Spring message bundles in `src/main/resources` (e.g. `messages.properties`, `messages_zh_CN.properties`)

### Fixed
- Java `LangUtil.get(...)` i18n key completion now auto-popups reliably while typing and no longer loses candidates when completion runs on copied PSI files
- Spring `.properties` translation keys now participate in plugin Find Usages / reference search for Java i18n calls, resolving false "unused key" gray states
- Custom i18n function parsing now accepts Chinese separators (`，` / `；`) in settings and resolves qualified method names case-insensitively (e.g. `LangUtil.get` vs `langUtil.get`)
- Fixed a `StringIndexOutOfBoundsException` in properties key fallback parsing during usage highlighting when caret/element offset lands on a newline boundary

### Changed
- Extended cache snapshot/index structures with per-module translation data to support module-scoped key visibility and resolution
- Added Spring filename locale extraction (`messages_xx[_YY]`) and integrated it into translation file path parsing
- Enabled optional Java plugin dependency registration via `i18n-java.xml` and included `com.intellij.java` in bundled plugin configuration
- Set Kotlin `jvmDefault` to `NO_COMPATIBILITY` to avoid synthetic deprecated bridge methods on interface default APIs

### Compatibility
- Replaced deprecated API paths reported by IntelliJ 2026.1 EAP: removed `CompletionContributor.invokeAutoPopup(...)` override, removed `DynamicPluginListener.checkUnloadPlugin(...)` override, removed explicit `StatusBarWidget.getPresentation(PlatformType)` implementation, and migrated daemon refresh to a non-deprecated restart path with runtime compatibility fallback

## 0.0.4
### Fixed
- In `Translation Only` display mode, translated folds are now re-collapsed immediately when the caret leaves the i18n key during Replace operations

### Changed
- Refactored translation cache refresh to use non-blocking read actions with refresh coalescing to reduce UI-thread work
- Improved Find Usages and reference search performance by scanning indexed source files instead of recursively traversing project directories
- Added cancellation checkpoints in cache build and usage scanning flows to keep the IDE responsive on large projects

## 0.0.3
### Fixed
- Inline translation hints not displaying when files are reopened after being closed
- Inline translation hints not showing when IDEA starts with files already open (especially after long project loading times)
- Inline translation hints disappearing after switching away from IDEA for an extended period and returning
- Added intelligent cache clearing mechanism with 30-second threshold to avoid performance issues from frequent window switching

## 0.0.2
### Fixed
- Navigation behavior: Cmd+Click on i18n function name (e.g., `t`) now preserves default behavior (jump to method declaration) instead of showing translation chooser
- Navigation now only triggers when clicking on the i18n key string or its quotes, not on the function name

### Added
- Translation search popup now remembers last search query and results when reopened
- Search query text is automatically selected for quick replacement when popup opens

## 0.0.1
### Added

- Auto-detects popular i18n frameworks and scans multi-format locale files
- Smart key completion with display-language translation matching
- Inline translation preview, Quick Documentation, and translation search popup
- Go to Declaration/Implementation, cross-locale navigation, and Find Usages
