package com.playtranslate.ask

import com.playtranslate.translation.llm.LlmPromptTemplates

/** The sentence a question is about, and the pair it is being read in:
 *  [sourceLang] is the sentence's language and [targetLang] the reader's,
 *  which the answer is written in. Language codes, as the translation
 *  prompts take them. What an [AskSession] is keyed by. */
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
 * ([LlmPromptTemplates.fill]): `{source}` and `{target}` are the two
 * languages' English names, `{text}` the sentence, `{question}` the user's
 * own words.
 *
 * Kotlin constants, not string resources, for the reason the translation
 * prompts are: the models are instructed in English, and a localized prompt
 * would be a different prompt. The wording here is a first draft to be
 * replaced; nothing else depends on it.
 */
object AskPrompts {

    const val SYSTEM =
        "You help a reader understand a {source} sentence they came across in a game. " +
            "Answer in {target}. Be concise and specific to this sentence. " +
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
        val values = HashMap<String, String>()
        values[LlmPromptTemplates.TOKEN_TEXT] = sentence.text
        val template = when (question) {
            is AskQuestion.Preset -> question.preset.prompt
            is AskQuestion.Custom -> {
                values[LlmPromptTemplates.TOKEN_QUESTION] = question.text
                CUSTOM
            }
        }
        return Messages(
            system = LlmPromptTemplates.fill(SYSTEM, sentence.sourceLang, sentence.targetLang),
            user = LlmPromptTemplates.fill(template, sentence.sourceLang, sentence.targetLang, values),
        )
    }
}
