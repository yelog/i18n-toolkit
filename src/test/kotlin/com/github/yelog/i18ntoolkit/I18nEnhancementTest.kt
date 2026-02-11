package com.github.yelog.i18ntoolkit

import com.github.yelog.i18ntoolkit.rename.I18nKeyRenameProcessor
import com.github.yelog.i18ntoolkit.service.I18nCacheService
import com.github.yelog.i18ntoolkit.util.I18nKeyCreationSupport
import com.github.yelog.i18ntoolkit.util.I18nTranslationConsistencySupport
import com.github.yelog.i18ntoolkit.util.I18nTranslationWriter
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.TestDataPath
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.usageView.UsageInfo
import com.intellij.json.psi.JsonProperty

@TestDataPath("\$CONTENT_ROOT/src/test/testData")
class I18nEnhancementTest : BasePlatformTestCase() {

    fun testYamlAndTomlCreationAndUpdate() {
        myFixture.tempDirFixture.createFile(
            "src/locales/en/common.yaml",
            "hello: 'Hello'\n"
        )
        myFixture.tempDirFixture.createFile(
            "src/locales/zh/common.toml",
            "hello = \"你好\"\n"
        )

        val cacheService = I18nCacheService.getInstance(project)
        ApplicationManager.getApplication().executeOnPooledThread { cacheService.refresh() }.get()

        val targetFiles = I18nKeyCreationSupport.findTargetFiles(cacheService, "common.welcome")
        assertTrue(targetFiles.any { it.locale == "en" })
        assertTrue(targetFiles.any { it.locale == "zh" })

        targetFiles.forEach { translationFile ->
            I18nKeyCreationSupport.createKeyInTranslationFile(
                project = project,
                translationFile = translationFile,
                fullKey = "common.welcome",
                initialValue = ""
            )
        }

        ApplicationManager.getApplication().executeOnPooledThread { cacheService.refresh() }.get()

        val translations = cacheService.getAllTranslations("common.welcome")
        assertContainsElements(translations.keys, "en", "zh")

        val enEntry = translations["en"]
        val zhEntry = translations["zh"]
        assertNotNull(enEntry)
        assertNotNull(zhEntry)

        I18nTranslationWriter.updateTranslationValue(project, enEntry!!, "Welcome")
        I18nTranslationWriter.updateTranslationValue(project, zhEntry!!, "欢迎")

        ApplicationManager.getApplication().executeOnPooledThread { cacheService.refresh() }.get()

        assertEquals("Welcome", cacheService.getTranslationStrict("common.welcome", "en")?.value)
        assertEquals("欢迎", cacheService.getTranslationStrict("common.welcome", "zh")?.value)
    }

    fun testFillMissingTranslationsCreatesMissingLocaleEntries() {
        myFixture.tempDirFixture.createFile(
            "src/locales/en/common.yaml",
            "hello: 'Hello'\n"
        )
        myFixture.tempDirFixture.createFile(
            "src/locales/zh/common.yaml",
            "other: '其他'\n"
        )

        val cacheService = I18nCacheService.getInstance(project)
        ApplicationManager.getApplication().executeOnPooledThread { cacheService.refresh() }.get()

        val created = I18nTranslationConsistencySupport.fillMissingTranslations(
            project = project,
            key = "common.hello",
            allLocales = cacheService.getAvailableLocales()
        )
        assertEquals(1, created)

        ApplicationManager.getApplication().executeOnPooledThread { cacheService.refresh() }.get()

        val translations = cacheService.getAllTranslations("common.hello")
        assertContainsElements(translations.keys, "en", "zh")
        assertEquals("", translations["zh"]?.value)
    }

    fun testRenameI18nKeyUpdatesLocalesAndUsages() {
        myFixture.tempDirFixture.createFile(
            "src/locales/en/common.json",
            """
            {
              "greet": "Hello"
            }
            """.trimIndent()
        )
        myFixture.tempDirFixture.createFile(
            "src/locales/zh/common.json",
            """
            {
              "greet": "你好"
            }
            """.trimIndent()
        )
        myFixture.tempDirFixture.createFile(
            "src/main.ts",
            """
            const { t } = useTranslation('common')
            const message = t('greet')
            """.trimIndent()
        )

        val cacheService = I18nCacheService.getInstance(project)
        ApplicationManager.getApplication().executeOnPooledThread { cacheService.refresh() }.get()

        val enFile = myFixture.findFileInTempDir("src/locales/en/common.json")
        assertNotNull(enFile)

        myFixture.openFileInEditor(enFile!!)
        val greetOffset = myFixture.editor.document.text.indexOf("greet")
        assertTrue(greetOffset >= 0)
        myFixture.editor.caretModel.moveToOffset(greetOffset + 1)

        val elementAtCaret = myFixture.file.findElementAt(myFixture.editor.caretModel.offset)
        assertNotNull(elementAtCaret)

        val property = PsiTreeUtil.getParentOfType(elementAtCaret, JsonProperty::class.java, false)
        assertNotNull(property)

        val processor = I18nKeyRenameProcessor()
        assertTrue(processor.canProcessElement(property!!))

        val usages = ReferencesSearch.search(property).findAll()
            .map { UsageInfo(it) }
            .toTypedArray()

        processor.renameElement(property, "welcome", usages, null)

        ApplicationManager.getApplication().executeOnPooledThread { cacheService.refresh() }.get()

        val enText = VfsUtil.loadText(myFixture.findFileInTempDir("src/locales/en/common.json")!!)
        val zhText = VfsUtil.loadText(myFixture.findFileInTempDir("src/locales/zh/common.json")!!)
        val sourceText = VfsUtil.loadText(myFixture.findFileInTempDir("src/main.ts")!!)

        assertTrue(enText.contains("\"welcome\""))
        assertFalse(enText.contains("\"greet\""))

        assertTrue(zhText.contains("\"welcome\""))
        assertFalse(zhText.contains("\"greet\""))

        assertTrue("sourceText=$sourceText", sourceText.contains("t('welcome')"))
        assertFalse(sourceText.contains("t('greet')"))

        val translations = cacheService.getAllTranslations("common.welcome")
        assertContainsElements(translations.keys, "en", "zh")
        assertTrue(cacheService.getAllTranslations("common.greet").isEmpty())
    }

    fun testRenameI18nKeyBlockedWhenTargetExists() {
        myFixture.tempDirFixture.createFile(
            "src/locales/en/common.json",
            """
            {
              "greet": "Hello",
              "welcome": "Welcome"
            }
            """.trimIndent()
        )
        myFixture.tempDirFixture.createFile(
            "src/locales/zh/common.json",
            """
            {
              "greet": "你好",
              "welcome": "欢迎"
            }
            """.trimIndent()
        )
        myFixture.tempDirFixture.createFile(
            "src/main.ts",
            """
            const { t } = useTranslation('common')
            const message = t('greet')
            """.trimIndent()
        )

        val cacheService = I18nCacheService.getInstance(project)
        ApplicationManager.getApplication().executeOnPooledThread { cacheService.refresh() }.get()

        val enFile = myFixture.findFileInTempDir("src/locales/en/common.json")
        assertNotNull(enFile)

        myFixture.openFileInEditor(enFile!!)
        val greetOffset = myFixture.editor.document.text.indexOf("greet")
        assertTrue(greetOffset >= 0)
        myFixture.editor.caretModel.moveToOffset(greetOffset + 1)

        val elementAtCaret = myFixture.file.findElementAt(myFixture.editor.caretModel.offset)
        assertNotNull(elementAtCaret)

        val property = PsiTreeUtil.getParentOfType(elementAtCaret, JsonProperty::class.java, false)
        assertNotNull(property)

        val processor = I18nKeyRenameProcessor()
        assertTrue(processor.canProcessElement(property!!))

        val usages = ReferencesSearch.search(property).findAll()
            .map { UsageInfo(it) }
            .toTypedArray()

        processor.renameElement(property, "welcome", usages, null)

        ApplicationManager.getApplication().executeOnPooledThread { cacheService.refresh() }.get()

        val enText = VfsUtil.loadText(myFixture.findFileInTempDir("src/locales/en/common.json")!!)
        val zhText = VfsUtil.loadText(myFixture.findFileInTempDir("src/locales/zh/common.json")!!)
        val sourceText = VfsUtil.loadText(myFixture.findFileInTempDir("src/main.ts")!!)

        assertTrue(enText.contains("\"greet\""))
        assertTrue(enText.contains("\"welcome\""))

        assertTrue(zhText.contains("\"greet\""))
        assertTrue(zhText.contains("\"welcome\""))

        assertTrue(sourceText.contains("t('greet')"))
        assertFalse(sourceText.contains("t('welcome')"))
    }

    override fun getTestDataPath() = "src/test/testData"
}
