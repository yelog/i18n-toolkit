<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# i18n-toolkit Changelog

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

## [Unreleased]
### Added
- Initial scaffold created from [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template)
