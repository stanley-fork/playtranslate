package com.playtranslate.ask

import com.playtranslate.language.ChineseScriptVariant
import com.playtranslate.translation.SentenceQuestion
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

/**
 * One word of a sentence, as a question about it names it: the [sentence]
 * it stands in, the [word] as its dictionary row heads it, that word's
 * [reading] where it has one of its own, and the forms it takes in the
 * sentence where they differ from the word ([surfaces]: 食べさせられた for
 * 食べる; a row that covers several occurrences has several, a word that
 * stands as itself none). What a word row's host says of the row, and what
 * an answer is kept against: an answer about one of these is no answer
 * about another.
 */
data class WordInSentence(
    val sentence: AskSentence,
    val word: String,
    val reading: String? = null,
    val surfaces: List<String> = emptyList(),
)

/** What the user asked: a ready-made question, their own words, or how one
 *  word of the sentence is used in it. */
sealed class AskQuestion {
    /** The line over the answer. */
    abstract val title: String

    data class Preset(val preset: AskPreset) : AskQuestion() {
        override val title: String get() = preset.title
    }

    data class Custom(val text: String) : AskQuestion() {
        override val title: String get() = text
    }

    /** A word row's question: how [word] is used in the sentence it was
     *  read in ([WordInSentence]'s word, reading and forms). [title] is the
     *  asking surface's to give, being a string of the app's. */
    data class Word(
        val word: String,
        val reading: String?,
        val surfaces: List<String>,
        override val title: String,
    ) : AskQuestion()
}

/**
 * The Ask section's prompts, as data: one system prompt, the wording of each
 * ready-made question, and the frames a question is put in with its
 * sentence. Templates share the translation prompts' tokens and
 * substitution ([LlmPromptTemplates.fill]) except `{target}`, which here is
 * the answer's language named in full ([answerLanguage]) where the
 * translation prompts have its English name. `{source}` is the sentence's
 * language's English name, `{text}` the sentence, `{question}` the
 * question's wording, `{word}` the word a word question is about
 * ([wordValue]). [build] ends every user message with [ANSWER_IN], so
 * both messages say the answer's language.
 *
 * The prompts are for the models that are put one. A service that writes
 * its own (PlayTranslate Cloud) is sent the question's parts, which [build]
 * returns beside them ([SentenceQuestion]).
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

    /** The grammar breakdown's wording ([AskPreset.question]). */
    const val GRAMMAR_BREAKDOWN =
        "Break this {source} sentence down into its grammar points, in the order they appear. " +
            "For each one, quote the part of the sentence it covers, then explain in one or two " +
            "lines what it does there."

    /** A word row's question ([AskQuestion.Word]): `{word}` is the word as
     *  [wordValue] names it. A first draft, like the wording above it. */
    const val WORD_IN_SENTENCE =
        "Explain how {word} is used in this {source} text: which of its meanings it has here, " +
            "and what its form and the words around it add. Answer in two to four sentences, " +
            "and do not translate the whole text."

    /** The frame for a ready-made question: its wording, then the sentence. */
    const val PRESET = "{question}\n\n{text}"

    /** The frame for the user's own question. */
    const val CUSTOM = "{question}\n\nThe sentence ({source}):\n{text}"

    /**
     * [question] about [sentence], in the two forms a service takes it
     * ([SentenceQuestion]): the parts (the pair's codes as [sentence] holds
     * them, the sentence, and the question's wording without the sentence),
     * and the two messages made of them.
     *
     * The wording is a ready-made question's template filled, or the
     * user's own words, which are never read as a template. It goes into
     * the user message as a value, like the sentence, so nothing either
     * contains is expanded as a token.
     */
    fun build(question: AskQuestion, sentence: AskSentence): SentenceQuestion {
        // A value given for {target} takes the place of the English name
        // fill would give it.
        val target = LlmPromptTemplates.TOKEN_TARGET to answerLanguage(sentence.targetLang)
        fun fill(template: String, vararg values: Pair<String, String>) =
            LlmPromptTemplates.fill(template, sentence.sourceLang, sentence.targetLang, mapOf(target, *values))
        val (wording, frame) = when (question) {
            is AskQuestion.Preset -> fill(question.preset.question) to PRESET
            is AskQuestion.Custom -> question.text to CUSTOM
            // The word is a value of the wording, as the wording is of the
            // frame: a word that reads like a token stays the word.
            is AskQuestion.Word ->
                fill(WORD_IN_SENTENCE, LlmPromptTemplates.TOKEN_WORD to wordValue(question)) to PRESET
        }
        return SentenceQuestion(
            source = sentence.sourceLang,
            target = sentence.targetLang,
            sentence = sentence.text,
            question = wording,
            system = fill(SYSTEM),
            user = fill(
                "$frame\n\n$ANSWER_IN",
                LlmPromptTemplates.TOKEN_QUESTION to wording,
                LlmPromptTemplates.TOKEN_TEXT to sentence.text,
            ),
        )
    }

    /**
     * The word of a word question as its wording names it: the word in
     * quotes, then in brackets its reading and the forms it takes in the
     * sentence, whichever it has: `"食べる" (read たべる; it appears here as
     * "食べさせられた")`. The forms are what the model can find in the text,
     * which a dictionary form alone often is not.
     */
    internal fun wordValue(question: AskQuestion.Word): String {
        val notes = listOfNotNull(
            question.reading?.takeIf { it.isNotBlank() && it != question.word }?.let { "read $it" },
            question.surfaces.filter { it.isNotBlank() && it != question.word }.distinct()
                .takeIf { it.isNotEmpty() }
                ?.let { forms -> "it appears here as " + forms.joinToString(", ") { "\"$it\"" } },
        )
        return "\"${question.word}\"" + if (notes.isEmpty()) "" else notes.joinToString("; ", " (", ")")
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
