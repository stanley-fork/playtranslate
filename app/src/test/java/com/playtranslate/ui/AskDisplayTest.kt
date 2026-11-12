package com.playtranslate.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.R
import com.playtranslate.ask.AskQuestion
import com.playtranslate.ask.AskState
import com.playtranslate.translation.BackendFailure
import com.playtranslate.translation.BackendFailureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [askDisplay]: a question's state in words, the one place a state becomes
 * text for every surface that draws one (the Ask card, a word row). One
 * cell per state:
 *
 * | state                          | body                         | muted | footer                | Try again |
 * |--------------------------------|------------------------------|-------|-----------------------|-----------|
 * | Idle                           | nothing to show (null)       |       |                       |           |
 * | Asking, service named          | "Asking <service>…"          | no    | none                  | no        |
 * | Asking, not yet named          | "Asking…"                    | no    | none                  | no        |
 * | Answered                       | the answer                   | no    | who answered          | no        |
 * | Answered, cut short            | the answer                   | no    | the cut line, then who| no        |
 * | Out of scope                   | the service's scope, by name | yes   | none                  | no        |
 * | Failed, nothing to say why     | the generic line             | yes   | none                  | yes       |
 * | Failed, a service and a reason | the generic line, the reason | yes   | none                  | yes       |
 *
 * The title is the question's own in every state. The Ask card's drawing of
 * these is [AskSectionBinderTest]'s, a row's [DefRowHtmlTest]'s.
 */
@RunWith(RobolectricTestRunner::class)
class AskDisplayTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val question = AskQuestion.Word("食べる", "たべる", listOf("食べた"), title = "In this sentence")

    private fun shown(state: AskState): AskDisplay = askDisplay(ctx, state)!!

    @Test fun `nothing asked is nothing to show`() {
        assertNull(askDisplay(ctx, AskState.Idle))
    }

    @Test fun `a question that is out reads Asking, with its service once it is named`() {
        val named = shown(AskState.Asking(question, "Gemini"))
        assertEquals("In this sentence", named.title)
        assertEquals(ctx.getString(R.string.ask_status_asking, "Gemini"), named.body)
        assertFalse(named.muted)
        assertNull(named.footer)
        assertFalse(named.retry)

        val unnamed = shown(AskState.Asking(question))
        assertEquals(ctx.getString(R.string.ask_status_asking_unnamed), unnamed.body)
        assertFalse(unnamed.muted)
        assertNull(unnamed.footer)
        assertFalse(unnamed.retry)
    }

    @Test fun `an answer is its text, with who gave it under it`() {
        val answered = shown(AskState.Answered(question, "It is the past of to eat.", "Gemini"))
        assertEquals("In this sentence", answered.title)
        assertEquals("It is the past of to eat.", answered.body)
        assertFalse(answered.muted)
        assertEquals(ctx.getString(R.string.ask_answered_by, "Gemini"), answered.footer)
        assertFalse(answered.retry)
    }

    @Test fun `an answer cut at its length limit says so before who gave it`() {
        val cut = shown(AskState.Answered(question, "It is the", "Gemini", cut = true))
        assertEquals("It is the", cut.body)
        assertEquals(
            ctx.getString(R.string.ask_answer_cut) + "\n" + ctx.getString(R.string.ask_answered_by, "Gemini"),
            cut.footer,
        )
    }

    @Test fun `a question the service does not take is the app's word on it, with no Try again`() {
        val out = shown(AskState.OutOfScope(question, "PlayTranslate Cloud"))
        assertEquals("In this sentence", out.title)
        assertEquals(ctx.getString(R.string.ask_out_of_scope, "PlayTranslate Cloud"), out.body)
        assertTrue(out.muted)
        assertNull(out.footer)
        assertFalse("asking again would get the same", out.retry)
    }

    @Test fun `a failure with nothing to say why is the generic line, with Try again`() {
        for (failed in listOf(
            AskState.Failed(question),
            // Half a reason is none: a name with no failure, a failure with no name.
            AskState.Failed(question, service = "OpenAI"),
            AskState.Failed(question, failure = BackendFailure(BackendFailureKind.RATE_LIMITED, 429)),
        )) {
            val shown = shown(failed)
            assertEquals("In this sentence", shown.title)
            assertEquals("$failed", ctx.getString(R.string.ask_failed), shown.body)
            assertTrue(shown.muted)
            assertNull(shown.footer)
            assertTrue(shown.retry)
        }
    }

    @Test fun `a failure naming a service and its reason shows the reason under the generic line`() {
        val failed = shown(
            AskState.Failed(question, service = "OpenAI", failure = BackendFailure(BackendFailureKind.RATE_LIMITED, 429)),
        )
        assertEquals(
            ctx.getString(R.string.ask_failed) + "\n" + ctx.getString(R.string.translation_error_rate_limited, "OpenAI"),
            failed.body,
        )
        assertTrue(failed.muted)
        assertTrue(failed.retry)
    }
}
