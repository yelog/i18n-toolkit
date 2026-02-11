package com.github.yelog.i18ntoolkit

import com.intellij.ide.highlighter.XmlFileType
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiManager
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.testFramework.TestDataPath
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.PsiErrorElementUtil
import com.github.yelog.i18ntoolkit.service.I18nCacheService
import com.github.yelog.i18ntoolkit.settings.I18nSettingsState
import com.github.yelog.i18ntoolkit.spring.JavaI18nTypedHandler
import com.github.yelog.i18ntoolkit.spring.SpringMessagePatternMatcher
import com.github.yelog.i18ntoolkit.util.I18nFunctionResolver
import com.github.yelog.i18ntoolkit.util.I18nKeyExtractor

@TestDataPath("\$CONTENT_ROOT/src/test/testData")
class MyPluginTest : BasePlatformTestCase() {

    fun testXMLFile() {
        val psiFile = myFixture.configureByText(XmlFileType.INSTANCE, "<foo>bar</foo>")
        val xmlFile = assertInstanceOf(psiFile, XmlFile::class.java)

        assertFalse(PsiErrorElementUtil.hasErrors(project, xmlFile.virtualFile))

        assertNotNull(xmlFile.rootTag)

        xmlFile.rootTag?.let {
            assertEquals("foo", it.name)
            assertEquals("bar", it.value.text)
        }
    }

    fun testSpringLocaleMessageKeysDoNotIncludeFilenamePrefix() {
        myFixture.tempDirFixture.createFile(
            "build.gradle.kts",
            """
            plugins {
                java
            }
            dependencies {
                implementation("org.springframework.boot:spring-boot-starter")
            }
            """.trimIndent()
        )
        myFixture.tempDirFixture.createFile(
            "src/main/resources/i18n/messages.properties",
            "tray.uwipBarcode.required=Base Required"
        )
        myFixture.tempDirFixture.createFile(
            "src/main/resources/i18n/messages_en_US.properties",
            "tray.uwipBarcode.required=UwipBarcode Required"
        )
        myFixture.tempDirFixture.createFile(
            "src/main/resources/i18n/messages_zh_CN.properties",
            "tray.uwipBarcode.required=条码为必填项"
        )

        val cacheService = I18nCacheService.getInstance(project)
        ApplicationManager.getApplication().executeOnPooledThread { cacheService.refresh() }.get()

        val keys = cacheService.getAllKeys()
        assertContainsElements(keys, "tray.uwipBarcode.required")
        assertFalse(keys.any { it.startsWith("messages_en_US.") || it.startsWith("messages_zh_CN.") })

        val translations = cacheService.getAllTranslations("tray.uwipBarcode.required")
        assertContainsElements(translations.keys, "en_US", "zh_CN")
        assertFalse(translations.containsKey("default"))

        val locales = cacheService.getAvailableLocales()
        assertContainsElements(locales, "en_US", "zh_CN")
    }

    fun testTargetDirectoryIsIgnoredAndUnicodeEscapesAreDecoded() {
        myFixture.tempDirFixture.createFile(
            "build.gradle.kts",
            """
            plugins {
                java
            }
            dependencies {
                implementation("org.springframework.boot:spring-boot-starter")
            }
            """.trimIndent()
        )
        myFixture.tempDirFixture.createFile(
            "src/main/resources/i18n/messages_zh_CN.properties",
            "tray.uwipBarcode.required=\\u4E2D\\u6587"
        )
        myFixture.tempDirFixture.createFile(
            "target/classes/static/i18n/messages_zh_CN.properties",
            "messages_zh_CN.tray.uwipBarcode.required=SHOULD_NOT_BE_INDEXED"
        )

        val cacheService = I18nCacheService.getInstance(project)
        ApplicationManager.getApplication().executeOnPooledThread { cacheService.refresh() }.get()

        val keys = cacheService.getAllKeys()
        assertContainsElements(keys, "tray.uwipBarcode.required")
        assertFalse(keys.any { it.startsWith("messages_zh_CN.") })

        val zhEntry = cacheService.getTranslationStrict("tray.uwipBarcode.required", "zh_CN")
        assertNotNull(zhEntry)
        assertEquals("中文", zhEntry!!.value)
        assertFalse(zhEntry.file.path.contains("/target/"))
    }

    fun testJavaCustomFunctionLangUtilGetIsRecognized() {
        val settings = I18nSettingsState.getInstance(project)
        settings.state.customI18nFunctions = "t, \$t, LangUtil.get"

        val psiFile = myFixture.configureByText(
            "Demo.java",
            """
            class Demo {
                String value() {
                    return LangUtil.get("uwip.uwipbarcode.notexist");
                }
            }
            """.trimIndent()
        )

        val literal = PsiTreeUtil.findChildOfType(psiFile, PsiLiteralExpression::class.java)
        assertNotNull(literal)

        val match = SpringMessagePatternMatcher.extractKey(literal!!)
        assertNotNull(match)
        assertEquals("uwip.uwipbarcode.notexist", match!!.key)
    }

    fun testKeyExtractorSupportsJavaCustomFunctionCall() {
        val settings = I18nSettingsState.getInstance(project)
        settings.state.customI18nFunctions = "t, \$t, LangUtil.get"

        val psiFile = myFixture.configureByText(
            "Demo2.java",
            """
            class Demo2 {
                String value() {
                    return LangUtil.get("uwip.uwipbarcode.notexist");
                }
            }
            """.trimIndent()
        )

        val offset = psiFile.text.indexOf("uwip.uwipbarcode.notexist")
        assertTrue(offset >= 0)

        val cacheService = I18nCacheService.getInstance(project)
        val keyCandidate = I18nKeyExtractor.findKeyAtOffset(psiFile, offset, cacheService)
        assertNotNull(keyCandidate)
        assertEquals("uwip.uwipbarcode.notexist", keyCandidate!!.fullKey)
    }

    fun testJavaCompletionSupportsCustomFunctionLangUtilGet() {
        val settings = I18nSettingsState.getInstance(project)
        settings.state.customI18nFunctions = "t, \$t, LangUtil.get"

        myFixture.tempDirFixture.createFile(
            "build.gradle.kts",
            """
            plugins {
                java
            }
            dependencies {
                implementation("org.springframework.boot:spring-boot-starter")
            }
            """.trimIndent()
        )
        myFixture.tempDirFixture.createFile(
            "src/main/resources/i18n/messages_en_US.properties",
            """
            tray.uwipBarcode.required=UwipBarcode Required
            uwip.uwipbarcode.notexist=Not Exist
            """.trimIndent()
        )

        val cacheService = I18nCacheService.getInstance(project)
        ApplicationManager.getApplication().executeOnPooledThread { cacheService.refresh() }.get()

        myFixture.configureByText(
            "Demo3.java",
            """
            class Demo3 {
                String value() {
                    return LangUtil.get("uwi<caret>");
                }
            }
            """.trimIndent()
        )

        val lookupElements = myFixture.completeBasic()
        assertNotNull(lookupElements)
        val lookupStrings = lookupElements!!.mapNotNull { it.lookupString }
        assertContainsElements(lookupStrings, "uwip.uwipbarcode.notexist")
    }

    fun testJavaCompletionAutoPopupSupportsCustomFunctionLangUtilGet() {
        val settings = I18nSettingsState.getInstance(project)
        settings.state.customI18nFunctions = "t, \$t, LangUtil.get"

        val psiFile = myFixture.configureByText(
            "Demo4.java",
            """
            class Demo4 {
                String value() {
                    return LangUtil.get("uwi<caret>");
                }
            }
            """.trimIndent()
        )

        val handler = JavaI18nTypedHandler()
        val result = handler.checkAutoPopup(
            'i',
            project,
            myFixture.editor,
            psiFile
        )
        assertEquals(TypedHandlerDelegate.Result.STOP, result)
    }

    fun testJavaCompletionWorksWhenLiteralIsEscapedContent() {
        val settings = I18nSettingsState.getInstance(project)
        settings.state.customI18nFunctions = "t, \$t, LangUtil.get"

        myFixture.tempDirFixture.createFile(
            "build.gradle.kts",
            """
            plugins {
                java
            }
            dependencies {
                implementation("org.springframework.boot:spring-boot-starter")
            }
            """.trimIndent()
        )
        myFixture.tempDirFixture.createFile(
            "src/main/resources/i18n/messages_en_US.properties",
            "tray.uwipBarcode.required=UwipBarcode Required"
        )

        val cacheService = I18nCacheService.getInstance(project)
        ApplicationManager.getApplication().executeOnPooledThread { cacheService.refresh() }.get()

        myFixture.configureByText(
            "DemoEscaped.java",
            """
            class DemoEscaped {
                String value() {
                    return LangUtil.get("tray.uwipBarcode.req<caret>uired");
                }
            }
            """.trimIndent()
        )

        val lookupElements = myFixture.completeBasic()
        assertNotNull(lookupElements)
        val lookupStrings = lookupElements!!.mapNotNull { it.lookupString }
        assertContainsElements(lookupStrings, "tray.uwipBarcode.required")
    }

    fun testFindUsagesFromSpringPropertiesFindsJavaLangUtilGetReferences() {
        val settings = I18nSettingsState.getInstance(project)
        settings.state.customI18nFunctions = "t, \$t, LangUtil.get"

        myFixture.tempDirFixture.createFile(
            "build.gradle.kts",
            """
            plugins {
                java
            }
            dependencies {
                implementation("org.springframework.boot:spring-boot-starter")
            }
            """.trimIndent()
        )
        myFixture.tempDirFixture.createFile(
            "src/main/resources/i18n/messages_en_US.properties",
            "tray.uwipBarcode.required=UwipBarcode Required"
        )
        myFixture.tempDirFixture.createFile(
            "src/main/java/Demo5.java",
            """
            class Demo5 {
                String value() {
                    return LangUtil.get("tray.uwipBarcode.required");
                }
            }
            """.trimIndent()
        )

        val cacheService = I18nCacheService.getInstance(project)
        ApplicationManager.getApplication().executeOnPooledThread { cacheService.refresh() }.get()

        val propertiesFile = myFixture.findFileInTempDir("src/main/resources/i18n/messages_en_US.properties")
        assertNotNull(propertiesFile)
        val propertiesPsi = PsiManager.getInstance(project).findFile(propertiesFile!!)
        assertNotNull(propertiesPsi)

        val keyOffset = propertiesPsi!!.text.indexOf("tray.uwipBarcode.required")
        assertTrue(keyOffset >= 0)
        val keyLeaf = propertiesPsi.findElementAt(keyOffset)
        assertNotNull(keyLeaf)

        val references = ReferencesSearch.search(keyLeaf!!).findAll()
        assertTrue(references.isNotEmpty())
        assertTrue(references.any { it.element.containingFile.name == "Demo5.java" })
    }

    fun testCustomFunctionSeparatorsSupportChinesePunctuation() {
        val settings = I18nSettingsState.getInstance(project)
        settings.state.customI18nFunctions = "t，\$t；LangUtil.get"

        val functions = I18nFunctionResolver.getFunctions(project)
        assertContainsElements(functions, "t", "\$t", "LangUtil.get")
    }

    fun testSpringMatcherSupportsCaseInsensitiveQualifiedFunction() {
        val settings = I18nSettingsState.getInstance(project)
        settings.state.customI18nFunctions = "LangUtil.get"

        val psiFile = myFixture.configureByText(
            "DemoCase.java",
            """
            class DemoCase {
                String value() {
                    return langUtil.get("tray.uwipBarcode.required");
                }
            }
            """.trimIndent()
        )

        val literal = PsiTreeUtil.findChildOfType(psiFile, PsiLiteralExpression::class.java)
        assertNotNull(literal)

        val match = SpringMessagePatternMatcher.extractKey(literal!!)
        assertNotNull(match)
        assertEquals("tray.uwipBarcode.required", match!!.key)
    }

    override fun getTestDataPath() = "src/test/testData"
}
