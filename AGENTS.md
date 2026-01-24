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

**Always use the Gradle wrapper** (`./gradlew` on Unix, `gradlew.bat` on Windows).

---

## Build / Test / Lint Commands

### Build

```bash
# Build the plugin (produces distributable ZIP)
./gradlew buildPlugin

# Clean and rebuild
./gradlew clean buildPlugin
```

### Test

```bash
# Run all tests with coverage (Kover)
./gradlew check

# Run all tests only
./gradlew test

# Run a single test class
./gradlew test --tests "com.github.yelog.i18nhelper.MyPluginTest"

# Run a single test method
./gradlew test --tests "com.github.yelog.i18nhelper.MyPluginTest.testXMLFile"

# Run tests matching a pattern
./gradlew test --tests "*MyPluginTest*"
```

### Verification & Quality

```bash
# Verify plugin compatibility with IntelliJ Platform
./gradlew verifyPlugin

# Run Qodana code inspection (requires Docker or Qodana CLI)
# CI uses: JetBrains/qodana-action
```

### Run Plugin Locally

```bash
# Launch IDE sandbox with plugin installed
./gradlew runIde

# Launch IDE for UI testing (with robot-server)
./gradlew runIdeForUiTests
```

### IDE Run Configurations

Pre-configured run configurations are available in `.run/`:
- **Run Plugin** - launches `runIde`
- **Run Tests** - runs `check`
- **Run Verifications** - runs `verifyPlugin`

---

## Project Structure

```
src/
├── main/
│   ├── kotlin/com/github/yelog/i18nhelper/
│   │   ├── MyBundle.kt              # Message bundle for i18n
│   │   ├── services/                # Project-level services
│   │   ├── startup/                 # Project startup activities
│   │   └── toolWindow/              # Tool window implementations
│   └── resources/
│       ├── META-INF/plugin.xml      # Plugin descriptor
│       └── messages/MyBundle.properties
└── test/
    ├── kotlin/                      # Test classes
    └── testData/                    # Test fixtures
```

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
import com.github.yelog.i18nhelper.MyBundle

// Bad
import com.intellij.openapi.*
```

### Naming Conventions

| Element | Convention | Example |
|---------|------------|---------|
| Package | lowercase, dot-separated | `com.github.yelog.i18nhelper` |
| Class | PascalCase | `MyProjectService` |
| Function | camelCase | `getRandomNumber()` |
| Constant | UPPER_SNAKE_CASE | `private const val BUNDLE = "..."` |
| Property | camelCase | `private val service` |

### IntelliJ Platform Patterns

**Services**: Use `@Service` annotation with appropriate level.
```kotlin
@Service(Service.Level.PROJECT)
class MyProjectService(project: Project) { }
```

**Logging**: Use `thisLogger()` extension.
```kotlin
import com.intellij.openapi.diagnostic.thisLogger

thisLogger().info("Message")
thisLogger().warn("Warning message")
```

**Message Bundles**: Use `DynamicBundle` for i18n strings.
```kotlin
MyBundle.message("key", param1, param2)
```

**UI Components**: Prefer JetBrains UI components.
```kotlin
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
```

### Testing

- Extend `BasePlatformTestCase` for platform tests
- Use `@TestDataPath` for test fixtures
- Access fixtures via `myFixture`

```kotlin
@TestDataPath("\$CONTENT_ROOT/src/test/testData")
class MyPluginTest : BasePlatformTestCase() {
    fun testSomething() {
        val psiFile = myFixture.configureByText(...)
        // assertions
    }
    
    override fun getTestDataPath() = "src/test/testData/rename"
}
```

### Error Handling

- Let IntelliJ Platform handle most exceptions
- Log warnings/errors via `thisLogger()`
- Avoid empty catch blocks

---

## Important Files

| File | Purpose | Notes |
|------|---------|-------|
| `build.gradle.kts` | Build configuration | Plugin dependencies, signing, publishing |
| `gradle.properties` | Project properties | Version, platform version, plugin metadata |
| `settings.gradle.kts` | Project settings | Root project name |
| `src/main/resources/META-INF/plugin.xml` | Plugin descriptor | Extensions, services, actions |
| `README.md` | Documentation | **Contains plugin description markers - do not remove `<!-- Plugin description -->` sections** |
| `CHANGELOG.md` | Release notes | Used by Gradle changelog plugin |

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
