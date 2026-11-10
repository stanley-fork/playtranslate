package com.playtranslate.ask

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AskPrompts.build], the two messages a question about a sentence becomes,
 * and [AskQuestion.title]:
 *  - a ready-made question sends its own prompt, with `{text}` the sentence
 *    and the language tokens the two languages' English names (the
 *    translation prompts' substitution, [com.playtranslate.translation.llm.LlmPromptTemplates.fill]);
 *  - the system message is [AskPrompts.SYSTEM] filled the same way, naming
 *    the language the answer is written in;
 *  - a typed question and the sentence are the user's own words: neither is
 *    read as a template, whatever braces they contain.
 *
 * The wording of SYSTEM and of the typed question's frame is a first draft,
 * so it is pinned only through its tokens, never as literal text. Pure JVM.
 */
class AskPromptsTest {

    private val sentence = AskSentence("猫が好きです", sourceLang = "ja", targetLang = "fr")

    /** A preset of the test's own, so its prompt can be pinned literally. */
    private val preset = AskPreset(
        id = "test",
        title = "Explain it",
        prompt = "Explain {text}, a {source} sentence, in {target} ({source_code} to {target_code}).",
    )

    private fun count(haystack: String, needle: String): Int = haystack.windowed(needle.length).count { it == needle }

    @Test fun `a preset's user message is its prompt with the sentence and the two languages' names`() {
        val messages = AskPrompts.build(AskQuestion.Preset(preset), sentence)

        assertEquals("Explain 猫が好きです, a Japanese sentence, in French (ja to fr).", messages.user)
    }

    @Test fun `the system message is SYSTEM filled the same way and names the answer's language`() {
        val messages = AskPrompts.build(AskQuestion.Preset(preset), sentence)

        val expected = AskPrompts.SYSTEM
            .replace("{source_code}", "ja").replace("{target_code}", "fr")
            .replace("{source}", "Japanese").replace("{target}", "French")
        assertEquals(expected, messages.system)
        assertTrue(messages.system, messages.system.contains("French"))
        assertFalse("no token left unfilled: ${messages.system}", Regex("\\{[a-z_]+\\}").containsMatchIn(messages.system))
    }

    @Test fun `the system message is the same whatever is asked`() {
        assertEquals(
            AskPrompts.build(AskQuestion.Preset(preset), sentence).system,
            AskPrompts.build(AskQuestion.Custom("Why?"), sentence).system,
        )
    }

    @Test fun `the built-in grammar breakdown prompt carries the sentence`() {
        val grammar = AskPreset(AskPresets.GRAMMAR_BREAKDOWN, "Grammar breakdown", AskPrompts.GRAMMAR_BREAKDOWN)

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
