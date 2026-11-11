package com.playtranslate.ui

import com.playtranslate.ask.AskPrompts
import com.playtranslate.ask.AskQuestion
import com.playtranslate.language.ChineseScriptVariant
import com.playtranslate.language.SourceLangId
import com.playtranslate.model.TranslationLangContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [askSentence], the sentence an Ask question is about, made from a result's
 * text and language context: a Chinese target carries its script, the
 * variant's code, where the context's target is "zh" for all four variants;
 * any other target is its own code, whatever variant is stored beside it.
 * And end to end, through [AskPrompts.build]: each Chinese reader's prompts
 * ask for their own variant, by script and, for Taiwan and Hong Kong,
 * region. Pure JVM.
 */
class AskHostingTest {

    private fun context(target: String, variant: ChineseScriptVariant = ChineseScriptVariant.SIMPLIFIED) =
        TranslationLangContext(SourceLangId.JA, target, variant)

    @Test fun `a Chinese target's sentence carries its variant's code`() {
        for (variant in ChineseScriptVariant.all) {
            assertEquals(variant.code, askSentence("猫", context("zh", variant)).targetLang)
        }
    }

    @Test fun `any other target's sentence carries its code, whatever variant is stored beside it`() {
        assertEquals("es", askSentence("猫", context("es", ChineseScriptVariant.TRADITIONAL)).targetLang)
        assertEquals("en", askSentence("猫", context("en")).targetLang)
    }

    @Test fun `each Chinese reader is asked for their own variant`() {
        val cases = mapOf(
            ChineseScriptVariant.SIMPLIFIED to "Simplified Chinese (简体中文, zh-Hans)",
            ChineseScriptVariant.TRADITIONAL to "Traditional Chinese (繁體中文, zh-Hant)",
            ChineseScriptVariant.TRADITIONAL_TW to "Traditional Chinese (Taiwan) (繁體中文（台灣）, zh-Hant-TW)",
            ChineseScriptVariant.TRADITIONAL_HK to "Traditional Chinese (Hong Kong) (繁體中文（香港）, zh-Hant-HK)",
        )
        for ((variant, name) in cases) {
            val sentence = askSentence("猫が好きです", context("zh", variant))

            val messages = AskPrompts.build(AskQuestion.Custom("Why?"), sentence)

            assertTrue("$variant: ${messages.system}", messages.system.contains("Answer in $name."))
            assertTrue("$variant: ${messages.user}", messages.user.endsWith("\n\nAnswer in $name."))
        }
    }
}
