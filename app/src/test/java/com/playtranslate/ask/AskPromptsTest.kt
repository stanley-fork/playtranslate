package com.playtranslate.ask

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AskPrompts.build], the two messages a question about a sentence becomes,
 * [AskPrompts.answerLanguage], the name they give the answer's language, and
 * [AskQuestion.title]:
 *  - the answer's language is named by its English name, its own name and
 *    its code, Chinese by its script and, for Taiwan and Hong Kong, its
 *    region; a code with no name is named by the code itself;
 *  - a ready-made question sends its own prompt, with `{text}` the sentence,
 *    `{source}` the sentence's language's English name (the translation
 *    prompts' substitution, [com.playtranslate.translation.llm.LlmPromptTemplates.fill])
 *    and `{target}` the answer's language named in full;
 *  - the system message is [AskPrompts.SYSTEM] filled the same way;
 *  - every user message, a preset's or a typed question's, ends with the
 *    line "Answer in <the answer's language>.";
 *  - a typed question and the sentence are the user's own words: neither is
 *    read as a template, whatever braces they contain.
 *
 * The wording of SYSTEM and of the typed question's frame is a first draft,
 * so it is pinned only through its tokens, never as literal text. The
 * language's name and the last line are the form that was measured, and are
 * pinned as written. Pure JVM.
 */
class AskPromptsTest {

    private val sentence = AskSentence("猫が好きです", sourceLang = "ja", targetLang = "fr")

    /** A preset of the test's own, so its prompt can be pinned literally. */
    private val preset = AskPreset(
        id = "test",
        title = "Explain it",
        prompt = "Explain {text}, a {source} sentence, in {target} ({source_code} to {target_code}).",
    )

    private val grammar = AskPreset(AskPresets.GRAMMAR_BREAKDOWN, "Grammar breakdown", AskPrompts.GRAMMAR_BREAKDOWN)

    private fun count(haystack: String, needle: String): Int = haystack.windowed(needle.length).count { it == needle }

    // ── The answer's language ───────────────────────────────────────────

    @Test fun `a language is named by its English name, its own name and its code`() {
        assertEquals("Spanish (español, es)", AskPrompts.answerLanguage("es"))
    }

    @Test fun `Chinese is named by its script, and zh alone is Simplified`() {
        assertEquals("Traditional Chinese (繁體中文, zh-Hant)", AskPrompts.answerLanguage("zh-Hant"))
        assertEquals("Simplified Chinese (简体中文, zh-Hans)", AskPrompts.answerLanguage("zh"))
        assertEquals("Simplified Chinese (简体中文, zh-Hans)", AskPrompts.answerLanguage("zh-Hans"))
    }

    @Test fun `the Taiwan and Hong Kong variants are named with their region`() {
        assertEquals("Traditional Chinese (Taiwan) (繁體中文（台灣）, zh-Hant-TW)", AskPrompts.answerLanguage("zh-Hant-TW"))
        assertEquals("Traditional Chinese (Hong Kong) (繁體中文（香港）, zh-Hant-HK)", AskPrompts.answerLanguage("zh-Hant-HK"))
    }

    @Test fun `a code with no name is named by the code itself`() {
        // A well-formed subtag that is no language, which Locale names by
        // the subtag, and the undetermined tag, which it names with nothing.
        assertEquals("xx", AskPrompts.answerLanguage("xx"))
        assertEquals("und", AskPrompts.answerLanguage("und"))
    }

    // ── The messages ────────────────────────────────────────────────────

    @Test fun `a preset's user message is its prompt filled, then the answer's language`() {
        val messages = AskPrompts.build(AskQuestion.Preset(preset), sentence)

        assertEquals(
            "Explain 猫が好きです, a Japanese sentence, in French (français, fr) (ja to fr).\n\n" +
                "Answer in French (français, fr).",
            messages.user,
        )
    }

    @Test fun `the system message is SYSTEM filled the same way and names the answer's language in full`() {
        val messages = AskPrompts.build(AskQuestion.Preset(preset), sentence)

        val expected = AskPrompts.SYSTEM
            .replace("{source_code}", "ja").replace("{target_code}", "fr")
            .replace("{source}", "Japanese").replace("{target}", "French (français, fr)")
        assertEquals(expected, messages.system)
        assertTrue(messages.system, messages.system.contains("Answer in French (français, fr)."))
        assertFalse("no token left unfilled: ${messages.system}", Regex("\\{[a-z_]+\\}").containsMatchIn(messages.system))
    }

    @Test fun `a Traditional reader's script is named in both messages, last in every user message`() {
        val traditional = sentence.copy(targetLang = "zh-Hant")
        val name = "Traditional Chinese (繁體中文, zh-Hant)"
        val line = "Answer in $name."

        for (question in listOf(AskQuestion.Preset(grammar), AskQuestion.Custom("Why is が used here?"))) {
            val messages = AskPrompts.build(question, traditional)

            // {source} is still the English name.
            assertEquals(
                AskPrompts.SYSTEM.replace("{source}", "Japanese").replace("{target}", name),
                messages.system,
            )
            assertTrue(messages.system, messages.system.contains(line))
            assertTrue("the last line, after a blank one: ${messages.user}", messages.user.endsWith("\n\n$line"))
        }
    }

    @Test fun `the system message is the same whatever is asked`() {
        assertEquals(
            AskPrompts.build(AskQuestion.Preset(preset), sentence).system,
            AskPrompts.build(AskQuestion.Custom("Why?"), sentence).system,
        )
    }

    @Test fun `the built-in grammar breakdown prompt carries the sentence`() {
        val user = AskPrompts.build(AskQuestion.Preset(grammar), sentence).user

        assertTrue(user, user.contains("猫が好きです"))
        assertFalse("its {text} was filled: $user", user.contains("{text}"))
    }

    @Test fun `a typed question's user message carries the question verbatim and the sentence`() {
        val question = "Why is が used here, and not は?"

        val user = AskPrompts.build(AskQuestion.Custom(question), sentence).user

        assertTrue(user, user.contains(question))
        assertTrue(user, user.contains("猫が好きです"))
    }

    @Test fun `a typed question naming a token is not expanded`() {
        val question = "What does {text} mean, in {source} or {target}?"

        val user = AskPrompts.build(AskQuestion.Custom(question), sentence).user

        assertTrue("the question as typed: $user", user.contains(question))
        // The sentence appears once: where the frame puts it, not again
        // inside the question.
        assertEquals(user, 1, count(user, "猫が好きです"))
    }

    @Test fun `a sentence naming a token is not expanded either`() {
        val tricky = AskSentence("a {question} b {source}", sourceLang = "ja", targetLang = "en")

        val user = AskPrompts.build(AskQuestion.Custom("Q?"), tricky).user

        assertTrue("the sentence as captured: $user", user.contains("a {question} b {source}"))
        assertEquals("the question appears once: $user", 1, count(user, "Q?"))
    }

    @Test fun `a question's title is a preset's title or the typed words`() {
        assertEquals("Explain it", AskQuestion.Preset(preset).title)
        assertEquals("Why is が used here?", AskQuestion.Custom("Why is が used here?").title)
    }
}
