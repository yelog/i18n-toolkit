# AGENTS.md

> Guidelines for AI agents working in this repository.

This is an **IntelliJ Platform Plugin** project built with Gradle Kotlin DSL and Kotlin.
The plugin provides i18n (internationalization) helper functionality for JetBrains IDEs.

---

## Build Environment

| Requirement | Version |
|-------------|---------|
| JDK | 21 (defined in `build.gradle.kts` via `jvmToolchain(21)`) |
| Gradle | 9.2.1 (use wrapper `./gradlew`) |
| IntelliJ Platform | 2025.2.5 |
| Kotlin | 2.3.0 |

**Always use the Gradle wrapper** (`./gradlew` on Unix, `gradlew.bat` on Windows).

---

## Build / Test / Lint Commands

### Build
```bash
./gradlew buildPlugin          # Build the plugin (produces distributable ZIP)
./gradlew clean buildPlugin    # Clean and rebuild
```

### Test
```bash
./gradlew check                # Run all tests with coverage (Kover)
./gradlew test                 # Run all tests only
./gradlew test --tests "com.github.yelog.i18ntoolkit.MyPluginTest"
./gradlew test --tests "com.github.yelog.i18ntoolkit.MyPluginTest.testXMLFile"
./gradlew test --tests "*MyPluginTest*"
```

### Verification & Quality
```bash
./gradlew verifyPlugin         # Verify plugin compatibility with IntelliJ Platform
./gradlew qodana               # Run Qodana code inspection (requires Docker)
```

### Run Plugin Locally
```bash
./gradlew runIde               # Launch IDE sandbox with plugin installed
./gradlew runIdeForUiTests     # Launch IDE for UI testing (robot-server on port 8082)
```

### IDE Run Configurations
Pre-configured run configurations in `.run/`:
- **Run Plugin** - launches `runIde`
- **Run Tests** - runs `check`
- **Run Verifications** - runs `verifyPlugin`

---

## Project Structure

```
src/
├── main/
│   ├── kotlin/com/github/yelog/i18ntoolkit/
│   │   ├── service/                 # I18nCacheService - central translation cache
│   │   ├── scanner/                 # I18nDirectoryScanner - finds translation files
│   │   ├── parser/                  # TranslationFileParser - parses JSON/YAML/JS/TS/Properties
│   │   ├── detector/                # I18nFrameworkDetector - auto-detects frameworks
│   │   ├── model/                   # Data models (I18nFramework, TranslationEntry, etc.)
│   │   ├── hint/                    # Inlay hints provider (inline translation previews)
│   │   ├── completion/              # Code completion for i18n keys
│   │   ├── reference/               # Reference contributor for navigation
│   │   ├── navigation/              # Go to Declaration/Implementation handlers
│   │   ├── settings/                # Plugin settings UI and state persistence
│   │   ├── action/                  # User actions (switch locale, popup, copy key)
│   │   ├── listener/                # File listeners for cache invalidation
│   │   ├── startup/                 # Project startup activities
│   │   └── util/                    # Utility classes
│   └── resources/
│       ├── META-INF/plugin.xml      # Plugin descriptor
│       └── messages/I18nBundle.properties  # Message bundle for i18n
└── test/
    ├── kotlin/                      # Test classes (extend BasePlatformTestCase)
    └── testData/                    # Test fixtures
```

**See CLAUDE.md for detailed architecture documentation.**

---

## Code Style Guidelines

### Language & Formatting
- **Language**: Kotlin (JVM target 21)
- **Indentation**: 4 spaces (no tabs)
- **Max line length**: Follow IntelliJ defaults (~120 chars)
- **Trailing commas**: Optional, follow existing file style
- **Blank lines**: One between functions, two between top-level declarations

### Imports
- Use explicit imports (no wildcard `*` imports)
- Group imports: Java/Kotlin stdlib → IntelliJ Platform → Project classes
- Remove unused imports

```kotlin
// Good
import com.intellij.openapi.project.Project
import com.github.yelog.i18ntoolkit.service.I18nCacheService

// Bad
import com.intellij.openapi.*
```

### Naming Conventions
| Element | Convention | Example |
|---------|------------|---------|
| Package | lowercase, dot-separated | `com.github.yelog.i18ntoolkit` |
| Class | PascalCase | `I18nCacheService` |
| Function | camelCase | `getTranslation()` |
| Constant | UPPER_SNAKE_CASE | `private const val REFRESH_KEY = "..."` |
| Property | camelCase | `private val cacheService` |

### IntelliJ Platform Patterns

**Services**: Use `@Service` annotation with companion `getInstance()`.
```kotlin
@Service(Service.Level.PROJECT)
class I18nCacheService(private val project: Project) : Disposable {
    companion object {
        fun getInstance(project: Project): I18nCacheService {
            return project.getService(I18nCacheService::class.java)
        }
    }
}
```

**Logging**: Use `thisLogger()` extension.
```kotlin
import com.intellij.openapi.diagnostic.thisLogger

thisLogger().info("Message")
thisLogger().warn("Warning: $details")
```

**Message Bundles**: Use `DynamicBundle` via `I18nBundle`.
```kotlin
I18nBundle.message("projectService", project.name)
```

**Settings**: Project settings use `@State` with `PersistentStateComponent`.
```kotlin
@Service(Service.Level.PROJECT)
@State(name = "I18nToolkitSettings", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class I18nSettingsState(private val project: Project) : PersistentStateComponent<I18nSettingsState.State>
```

**UI Components**: Prefer JetBrains UI components.
```kotlin
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
```

### Testing
- Extend `BasePlatformTestCase` for platform tests
- Use `@TestDataPath` annotation for test fixtures
- Access fixtures via `myFixture`
- Create temp files with `myFixture.tempDirFixture.createFile()`
- Get services via `Service.getInstance(project)` in tests

```kotlin
@TestDataPath("\$CONTENT_ROOT/src/test/testData")
class MyPluginTest : BasePlatformTestCase() {
    fun testSomething() {
        val psiFile = myFixture.configureByText(...)
        val service = I18nCacheService.getInstance(project)
        // assertions
    }
    
    override fun getTestDataPath() = "src/test/testData"
}
```

### Error Handling
- Let IntelliJ Platform handle most exceptions
- Log warnings/errors via `thisLogger()`
- Avoid empty catch blocks
- Use `ProgressManager.checkCanceled()` in long-running operations

---

## Code Quality

**Qodana Configuration** (`qodana.yml`):
- Uses `jetbrains/qodana-jvm-community:2024.3`
- Profile: `qodana.recommended`
- Excludes: `.qodana` directory

---

## Important Files

| File | Purpose | Notes |
|------|---------|-------|
| `build.gradle.kts` | Build configuration | Plugin dependencies, signing, publishing |
| `gradle.properties` | Project properties | Version, platform version, plugin metadata |
| `gradle/libs.versions.toml` | Version catalog | Centralized dependency versions |
| `settings.gradle.kts` | Project settings | Root project name |
| `src/main/resources/META-INF/plugin.xml` | Plugin descriptor | Extensions, services, actions |
| `qodana.yml` | Qodana config | Code quality inspection rules |
| `README.md` | Documentation | **Preserve `<!-- Plugin description -->` markers** |
| `CHANGELOG.md` | Release notes | Used by Gradle changelog plugin |
| `CLAUDE.md` | Architecture docs | Detailed component documentation |

---

## CI/CD

GitHub Actions workflows in `.github/workflows/`:
- **build.yml**: Main CI pipeline (build → test → inspect → verify → release draft)
- **release.yml**: Publish to JetBrains Marketplace
- **run-ui-tests.yml**: Manual UI test workflow

---

## Agent Behavior Notes

1. **Always use Gradle wrapper** - never invoke `gradle` directly
2. **Preserve README markers** - `<!-- Plugin description -->` sections are parsed by build
3. **Don't edit `build/`** - generated output directory
4. **Run `./gradlew check`** before committing to catch test failures
5. **Run `./gradlew verifyPlugin`** when changing plugin.xml or dependencies
6. **JDK 21 required** - ensure correct Java version is active
7. **No wildcard imports** - use explicit imports always
8. **See CLAUDE.md** for architecture details and component relationships
