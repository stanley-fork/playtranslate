package com.playtranslate.ask

import com.playtranslate.language.ChineseScriptVariant
import com.playtranslate.translation.llm.LlmPromptTemplates
import com.playtranslate.translation.llm.languageDisplayName
import java.util.Locale

/** The sentence a question is about, and the pair it is being read in:
 *  [sourceLang] is the sentence's language, the code the translation
 *  prompts take, and [targetLang] the reader's, which the answer is
 *  written in, as a language tag that carries the script where the
 *  language has two: for Chinese the target's [ChineseScriptVariant] code
 *  ("zh-Hant"), never "zh", the backend code all four variants share.
 *  What an [AskSession] is keyed by. */
data class AskSentence(val text: String, val sourceLang: String, val targetLang: String)

/** What the user asked: a ready-made question, or their own words. */
sealed class AskQuestion {
    /** The line over the answer. */
    abstract val title: String

    data class Preset(val preset: AskPreset) : AskQuestion() {
        override val title: String get() = preset.title
    }

    data class Custom(val text: String) : AskQuestion() {
        override val title: String get() = text
    }
}

/**
 * The Ask section's prompts, as data: one system prompt, a template per
 * ready-made question, and the frame a typed question is put in. Templates
 * share the translation prompts' tokens and substitution
 * ([LlmPromptTemplates.fill]) except `{target}`, which here is the answer's
 * language named in full ([answerLanguage]) where the translation prompts
 * have its English name. `{source}` is the sentence's language's English
 * name, `{text}` the sentence, `{question}` the user's own words. [build]
 * ends every user message with [ANSWER_IN], so both messages say the
 * answer's language.
 *
 * Kotlin constants, not string resources, for the reason the translation
 * prompts are: the models are instructed in English, and a localized prompt
 * would be a different prompt. The wording here is a first draft to be
 * replaced; nothing else depends on it.
 */
object AskPrompts {

    /** The answer's language: a sentence of [SYSTEM], and the last line of
     *  every user message ([build]). */
    const val ANSWER_IN = "Answer in {target}."

    const val SYSTEM =
        "You help a reader understand a {source} sentence they came across in a game. " +
            ANSWER_IN + " Be concise and specific to this sentence. " +
            "Write plain text, without Markdown."

    const val GRAMMAR_BREAKDOWN =
        "Break this {source} sentence down into its grammar points, in the order they appear. " +
            "For each one, quote the part of the sentence it covers, then explain in one or two " +
            "lines what it does there.\n\n{text}"

    /** The frame for the user's own question. */
    const val CUSTOM = "{question}\n\nThe sentence ({source}):\n{text}"

    /** The two messages a completion takes. */
    data class Messages(val system: String, val user: String)

    /** The messages that put [question] to a model about [sentence]. */
    fun build(question: AskQuestion, sentence: AskSentence): Messages {
        // A value given for {target} takes the place of the English name
        // fill would give it.
        val target = LlmPromptTemplates.TOKEN_TARGET to answerLanguage(sentence.targetLang)
        val values = hashMapOf(target, LlmPromptTemplates.TOKEN_TEXT to sentence.text)
        val template = when (question) {
            is AskQuestion.Preset -> question.preset.prompt
            is AskQuestion.Custom -> {
                values[LlmPromptTemplates.TOKEN_QUESTION] = question.text
                CUSTOM
            }
        }
        return Messages(
            system = LlmPromptTemplates.fill(SYSTEM, sentence.sourceLang, sentence.targetLang, mapOf(target)),
            user = LlmPromptTemplates.fill(
                "$template\n\n$ANSWER_IN", sentence.sourceLang, sentence.targetLang, values,
            ),
        )
    }

    /**
     * The language an answer is written in, named so that a model cannot
     * take it for another: its English name, its own name and its code,
     * "Spanish (español, es)". [code] is an [AskSentence.targetLang].
     *
     * Chinese is named by its script, which "Chinese" alone leaves open.
     * Measured on Gemini 3.1 Flash-Lite: told "Answer in Chinese", a
     * Traditional reader was answered in Simplified 8 times of 8; with the
     * language named precisely, in [SYSTEM] and again as the user message's
     * last line, 112 of 112 answers across 14 target languages were in the
     * right language and script. "zh" alone is Simplified, as everywhere in
     * the app. The Taiwan and Hong Kong variants are named with their region
     * too: the reader's translations are converted to it (Taiwan's
     * vocabulary, Hong Kong's character forms), and an answer is not.
     *
     * The English name is the translation prompts' ([languageDisplayName])
     * and the own name [Locale]'s, except the Chinese names: those are CLDR's
     * names for each variant (the short ones, so Hong Kong without "SAR
     * China"), which Locale does not expose. A code with no name is named by
     * the code itself.
     */
    fun answerLanguage(code: String): String {
        val locale = Locale.forLanguageTag(code)
        if (ChineseScriptVariant.isChineseTarget(locale.language)) {
            return when (ChineseScriptVariant.forLocale(locale)) {
                ChineseScriptVariant.SIMPLIFIED -> "Simplified Chinese (简体中文, zh-Hans)"
                ChineseScriptVariant.TRADITIONAL -> "Traditional Chinese (繁體中文, zh-Hant)"
                ChineseScriptVariant.TRADITIONAL_TW -> "Traditional Chinese (Taiwan) (繁體中文（台灣）, zh-Hant-TW)"
                ChineseScriptVariant.TRADITIONAL_HK -> "Traditional Chinese (Hong Kong) (繁體中文（香港）, zh-Hant-HK)"
            }
        }
        val english = languageDisplayName(code)
        // languageDisplayName gives back a code it has no name for.
        if (english == code) return code
        return "$english (${locale.getDisplayLanguage(locale)}, $code)"
    }
}
