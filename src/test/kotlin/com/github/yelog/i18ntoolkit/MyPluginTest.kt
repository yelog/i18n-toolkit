package com.github.yelog.i18ntoolkit

import com.intellij.ide.highlighter.XmlFileType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.testFramework.TestDataPath
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.PsiErrorElementUtil
import com.github.yelog.i18ntoolkit.service.I18nCacheService
import com.github.yelog.i18ntoolkit.settings.I18nSettingsState
import com.github.yelog.i18ntoolkit.spring.SpringMessagePatternMatcher

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

    override fun getTestDataPath() = "src/test/testData"
}
