package com.github.yelog.i18ntoolkit

import com.github.yelog.i18ntoolkit.reference.I18nKeyReference
import com.github.yelog.i18ntoolkit.service.I18nCacheService
import com.github.yelog.i18ntoolkit.util.I18nKeyExtractor
import com.github.yelog.i18ntoolkit.util.I18nNamespaceResolver
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.TestDataPath
import com.intellij.testFramework.fixtures.BasePlatformTestCase

@TestDataPath("\$CONTENT_ROOT/src/test/testData")
class I18nNamespaceTest : BasePlatformTestCase() {

    // ----- pure logic (no PSI) -----

    fun testExtractInlineNamespaceParsesColon() {
        assertEquals(
            "account_bill" to "bill_record",
            I18nNamespaceResolver.extractInlineNamespace("account_bill:bill_record")
        )
    }

    fun testExtractInlineNamespaceKeepsDotsInRealKey() {
        assertEquals(
            "account_bill" to "section.item",
            I18nNamespaceResolver.extractInlineNamespace("account_bill:section.item")
        )
    }

    fun testExtractInlineNamespaceRejectsMissingColon() {
        assertNull(I18nNamespaceResolver.extractInlineNamespace("account_bill.bill_record"))
    }

    fun testExtractInlineNamespaceRejectsLeadingOrTrailingColon() {
        assertNull(I18nNamespaceResolver.extractInlineNamespace(":bill_record"))
        assertNull(I18nNamespaceResolver.extractInlineNamespace("account_bill:"))
    }

    fun testDetectInlineNamespaceInputFromPartialTyping() {
        assertEquals("account_bill", I18nNamespaceResolver.detectInlineNamespaceInput("account_bill:"))
        assertEquals("account_bill", I18nNamespaceResolver.detectInlineNamespaceInput("account_bill:bill"))
        assertNull(I18nNamespaceResolver.detectInlineNamespaceInput("account_bill"))
    }

    // ----- integration: forward resolution (the path the annotator uses) -----

    fun testColonNamespaceResolvesToDottedKeyWithoutHook() {
        myFixture.tempDirFixture.createFile(
            "src/locales/en/account_bill.json",
            """{"bill_record": "Bill Record"}""".trimIndent()
        )
        myFixture.tempDirFixture.createFile(
            "src/locales/zh/account_bill.json",
            """{"bill_record": "账单记录"}""".trimIndent()
        )
        myFixture.tempDirFixture.createFile(
            "src/main.ts",
            "const msg = t('account_bill:bill_record')\n".trimIndent()
        )

        val cacheService = I18nCacheService.getInstance(project)
        ApplicationManager.getApplication().executeOnPooledThread { cacheService.refresh() }.get()

        // The translation file maps to a dotted cache key 'account_bill.bill_record'.
        assertContainsElements(cacheService.getAllKeys(), "account_bill.bill_record")

        // The t() key 'account_bill:bill_record' normalizes to the dotted key.
        val psiFile = PsiManager.getInstance(project)
            .findFile(myFixture.findFileInTempDir("src/main.ts")!!)!!
        val keyOffset = psiFile.text.indexOf("account_bill:bill_record")
        assertTrue(keyOffset >= 0)

        val candidate = I18nKeyExtractor.findKeyAtOffset(psiFile, keyOffset, cacheService)
        assertNotNull(candidate)
        assertEquals("account_bill.bill_record", candidate!!.fullKey)

        // The normalized key resolves against the cache (this is the check the
        // I18nKeyAnnotator performs — non-empty means no "Unresolved" error).
        val translations = cacheService.getAllTranslations(candidate.fullKey)
        assertContainsElements(translations.keys, "en", "zh")
    }

    fun testColonKeyReferenceResolvesForNavigation() {
        myFixture.tempDirFixture.createFile(
            "src/locales/en/account_bill.json",
            """{"bill_record": "Bill Record"}""".trimIndent()
        )
        myFixture.tempDirFixture.createFile(
            "src/main.ts",
            "const msg = t('account_bill:bill_record')\n".trimIndent()
        )

        val cacheService = I18nCacheService.getInstance(project)
        ApplicationManager.getApplication().executeOnPooledThread { cacheService.refresh() }.get()

        val psiFile = PsiManager.getInstance(project)
            .findFile(myFixture.findFileInTempDir("src/main.ts")!!)!!
        val literal = PsiTreeUtil.findChildOfType(psiFile, JSLiteralExpression::class.java)
        assertNotNull(literal)

        val refs = literal!!.references
        val i18nRef = refs.filterIsInstance<I18nKeyReference>().firstOrNull()
        assertNotNull("No I18nKeyReference created for t('ns:key') call", i18nRef)

        val resolved = i18nRef!!.resolve()
        assertNotNull("Cmd+Click navigation should resolve 'ns:key' to the translation", resolved)
    }

    // ----- i18next semantics: inline colon overrides useTranslation hook -----

    fun testInlineColonOverridesUseTranslationHook() {
        myFixture.tempDirFixture.createFile(
            "src/locales/en/common.json",
            """{"greet": "Hello"}""".trimIndent()
        )
        myFixture.tempDirFixture.createFile(
            "src/locales/en/account_bill.json",
            """{"bill_record": "Bill Record"}""".trimIndent()
        )
        myFixture.tempDirFixture.createFile(
            "src/main.ts",
            """
            function Component() {
              const { t } = useTranslation('common')
              const msg = t('account_bill:bill_record')
              return msg
            }
            """.trimIndent()
        )

        val cacheService = I18nCacheService.getInstance(project)
        ApplicationManager.getApplication().executeOnPooledThread { cacheService.refresh() }.get()

        val psiFile = PsiManager.getInstance(project)
            .findFile(myFixture.findFileInTempDir("src/main.ts")!!)!!
        val keyOffset = psiFile.text.indexOf("account_bill:bill_record")
        val candidate = I18nKeyExtractor.findKeyAtOffset(psiFile, keyOffset, cacheService)
        assertNotNull(candidate)
        // Inline 'account_bill:' wins over hook 'common'.
        assertEquals("account_bill.bill_record", candidate!!.fullKey)
    }

    // ----- regression: plain dot key (no colon) with hook namespace -----

    fun testDotKeyWithoutColonUsesHookNamespace() {
        myFixture.tempDirFixture.createFile(
            "src/locales/en/common.json",
            """{"greet": "Hello"}""".trimIndent()
        )
        myFixture.tempDirFixture.createFile(
            "src/main.ts",
            """
            function Component() {
              const { t } = useTranslation('common')
              const msg = t('greet')
              return msg
            }
            """.trimIndent()
        )

        val cacheService = I18nCacheService.getInstance(project)
        ApplicationManager.getApplication().executeOnPooledThread { cacheService.refresh() }.get()

        val psiFile = PsiManager.getInstance(project)
            .findFile(myFixture.findFileInTempDir("src/main.ts")!!)!!
        val keyOffset = psiFile.text.indexOf("'greet'")
        val candidate = I18nKeyExtractor.findKeyAtOffset(psiFile, keyOffset + 1, cacheService)
        assertNotNull(candidate)
        // No colon → hook namespace 'common' applies → 'common.greet'.
        assertEquals("common.greet", candidate!!.fullKey)
    }

    fun testNextIntlNamespaceResolvesFromOuterComponentForNestedHandler() {
        myFixture.tempDirFixture.createFile(
            "i18n/en.json",
            """
            {
              "Subscriptions": {
                "Fresh": {
                  "Cancellation": {
                    "SecondarySaveTactics": {
                      "Discount": {
                        "Feedback": {
                          "Success": {
                            "title": "Nice one"
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
            """.trimIndent()
        )
        myFixture.tempDirFixture.createFile(
            "src/page.tsx",
            """
            import { useTranslations } from 'next-intl'

            export default function Page() {
              const t = useTranslations('Subscriptions.Fresh.Cancellation.SecondarySaveTactics.Discount')

              const handleAcceptDiscountOffer = async () => {
                const title = t('Feedback.Success.title')
                return title
              }

              return null
            }
            """.trimIndent()
        )

        val cacheService = I18nCacheService.getInstance(project)
        ApplicationManager.getApplication().executeOnPooledThread { cacheService.refresh() }.get()

        val psiFile = PsiManager.getInstance(project)
            .findFile(myFixture.findFileInTempDir("src/page.tsx")!!)!!
        val keyOffset = psiFile.text.indexOf("Feedback.Success.title")
        val candidate = I18nKeyExtractor.findKeyAtOffset(psiFile, keyOffset, cacheService)

        assertNotNull(candidate)
        assertEquals(
            "Subscriptions.Fresh.Cancellation.SecondarySaveTactics.Discount.Feedback.Success.title",
            candidate!!.fullKey
        )
        assertFalse(cacheService.getAllTranslations(candidate.fullKey).isEmpty())
    }

    fun testNestedTranslationBindingDoesNotInheritOuterNamespace() {
        myFixture.tempDirFixture.createFile(
            "src/page.tsx",
            """
            import { useTranslations } from 'next-intl'

            export default function Page() {
              const t = useTranslations('outer')

              function Child() {
                const t = useTranslations()
                return t('title')
              }

              return <Child />
            }
            """.trimIndent()
        )

        val cacheService = I18nCacheService.getInstance(project)
        val psiFile = PsiManager.getInstance(project)
            .findFile(myFixture.findFileInTempDir("src/page.tsx")!!)!!
        val keyOffset = psiFile.text.indexOf("'title'") + 1
        val candidate = I18nKeyExtractor.findKeyAtOffset(psiFile, keyOffset, cacheService)

        assertNotNull(candidate)
        assertEquals("title", candidate!!.fullKey)
    }

    fun testDynamicNestedTranslationBindingDoesNotInheritOuterNamespace() {
        myFixture.tempDirFixture.createFile(
            "src/page.tsx",
            """
            import { useTranslations } from 'next-intl'

            export default function Page({ namespace }) {
              const t = useTranslations('outer')

              function Child() {
                const t = useTranslations(namespace)
                return t('title')
              }

              return <Child />
            }
            """.trimIndent()
        )

        val cacheService = I18nCacheService.getInstance(project)
        val psiFile = PsiManager.getInstance(project)
            .findFile(myFixture.findFileInTempDir("src/page.tsx")!!)!!
        val keyOffset = psiFile.text.indexOf("'title'") + 1
        val candidate = I18nKeyExtractor.findKeyAtOffset(psiFile, keyOffset, cacheService)

        assertNotNull(candidate)
        assertEquals("title", candidate!!.fullKey)
    }

    fun testSiblingNestedFunctionCannotProvideNamespace() {
        myFixture.tempDirFixture.createFile(
            "src/page.tsx",
            """
            import { useTranslations } from 'next-intl'

            export default function Page() {
              function Child() {
                const t = useTranslations('child')
                return t('childTitle')
              }

              const handleClick = () => t('pageTitle')
              return <Child />
            }
            """.trimIndent()
        )

        val cacheService = I18nCacheService.getInstance(project)
        val psiFile = PsiManager.getInstance(project)
            .findFile(myFixture.findFileInTempDir("src/page.tsx")!!)!!
        val keyOffset = psiFile.text.indexOf("'pageTitle'") + 1
        val candidate = I18nKeyExtractor.findKeyAtOffset(psiFile, keyOffset, cacheService)

        assertNotNull(candidate)
        assertEquals("pageTitle", candidate!!.fullKey)
    }

    fun testHookAssignedToDifferentVariableCannotProvideNamespace() {
        myFixture.tempDirFixture.createFile(
            "src/page.tsx",
            """
            import { useTranslations } from 'next-intl'

            export default function Page() {
              const translate = useTranslations('unrelated')
              return t('title')
            }
            """.trimIndent()
        )

        val cacheService = I18nCacheService.getInstance(project)
        val psiFile = PsiManager.getInstance(project)
            .findFile(myFixture.findFileInTempDir("src/page.tsx")!!)!!
        val keyOffset = psiFile.text.indexOf("'title'") + 1
        val candidate = I18nKeyExtractor.findKeyAtOffset(psiFile, keyOffset, cacheService)

        assertNotNull(candidate)
        assertEquals("title", candidate!!.fullKey)
    }

    fun testDefaultNamespaceResolvesBareKeyFromI18nextConfig() {
        myFixture.tempDirFixture.createFile(
            "src/i18n/config.js",
            """
            i18n.init({
              defaultNS: 'translations'
            })
            """.trimIndent()
        )
        myFixture.tempDirFixture.createFile(
            "src/i18n/locales/english/translations.json",
            """{"misc":{"translation-pending":"Help us translate"}}"""
        )
        myFixture.tempDirFixture.createFile(
            "src/main.ts",
            "const message = t('misc.translation-pending')"
        )

        val cacheService = I18nCacheService.getInstance(project)
        ApplicationManager.getApplication().executeOnPooledThread { cacheService.refresh() }.get()

        val psiFile = PsiManager.getInstance(project)
            .findFile(myFixture.findFileInTempDir("src/main.ts")!!)!!
        val keyOffset = psiFile.text.indexOf("misc.translation-pending")
        val candidate = I18nKeyExtractor.findKeyAtOffset(psiFile, keyOffset, cacheService)

        assertNotNull(candidate)
        assertEquals("translations.misc.translation-pending", candidate!!.fullKey)
        assertFalse(cacheService.getAllTranslations(candidate.fullKey).isEmpty())

        val literal = PsiTreeUtil.findChildOfType(psiFile, JSLiteralExpression::class.java)
        assertNotNull(literal)
        val reference = literal!!.references.filterIsInstance<I18nKeyReference>().firstOrNull()
        assertNotNull("No i18n reference created for a default namespace key", reference)
        assertNotNull("Default namespace key should resolve for navigation", reference!!.resolve())
    }

    fun testNamedLocaleDirectoryIsKeptDistinct() {
        myFixture.tempDirFixture.createFile(
            "src/i18n/locales/english/translations.json",
            """{"greeting":"Hello"}"""
        )
        myFixture.tempDirFixture.createFile(
            "src/i18n/locales/espanol/translations.json",
            """{"greeting":"Hola"}"""
        )

        val cacheService = I18nCacheService.getInstance(project)
        ApplicationManager.getApplication().executeOnPooledThread { cacheService.refresh() }.get()

        assertContainsElements(cacheService.getAvailableLocales(), "english", "espanol")
        assertContainsElements(
            cacheService.getAllTranslations("translations.greeting").keys,
            "english",
            "espanol"
        )
    }

    // Note: Find Usages reverse search (translation JSON → code) also routes
    // through I18nNamespaceResolver.getFullKey via
    // I18nFindUsagesHandlerFactory.checkCallExpression, so the colon key is
    // normalized to the dotted form the same way as the forward path verified
    // above. An E2E test for that reverse path needs the JS i18n function
    // resolver initialized in the sandbox, which is out of scope for this fix.

    override fun getTestDataPath() = "src/test/testData"
}
