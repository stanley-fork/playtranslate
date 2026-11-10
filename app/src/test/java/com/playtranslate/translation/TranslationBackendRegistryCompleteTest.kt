package com.playtranslate.translation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * [TranslationBackendRegistry.complete], the one pass that puts a free-form
 * prompt to the user's LLMs, and [TranslationBackendRegistry.hasCompleter],
 * the question the Ask section asks before it takes one. Pinned here:
 *  - which backends the pass tries (prompt completers that can complete and
 *    are not in a cooldown) and in what order (the registry's, setOrder
 *    included);
 *  - how it ends: the first non-blank answer wins and is returned with its
 *    backend, a failure or a blank falls through, cancellation stops it, and
 *    a pass nobody answered names the FIRST completer tried and what it
 *    threw (or nothing, when nothing was tried);
 *  - what it leaves behind: recordSuccess on the winner only, and never a
 *    report to the translation error pill's listener (contrasted with a
 *    translation pass over the same fake, which does report).
 *
 * Pure JVM over fakes; the registry's Log calls are no-ops through
 * `unitTests.isReturnDefaultValues`.
 */
class TranslationBackendRegistryCompleteTest {

    @After fun tearDown() {
        TranslationBackendRegistry.onlineAttemptListener = null
        TranslationBackendRegistry.close()
    }

    private fun cooledDown(id: String, priority: Int) = FakeCompleterBackend(id = id, priority = priority).apply {
        cooldownState.recordParsedFailure(System.currentTimeMillis() + 60_000, "Rate limited")
    }

    private fun failing(id: String, priority: Int, e: Exception) =
        FakeCompleterBackend(id = id, priority = priority, answer = { _, _ -> throw e })

    /** Runs the pass and returns what it threw, failing if it answered. */
    private fun completeFailure(): Throwable {
        val outcome = runCatching { runBlocking { TranslationBackendRegistry.complete("system", "user") } }
        return outcome.exceptionOrNull()
            ?: throw AssertionError("expected the pass to throw, ${outcome.getOrNull()?.backend?.id} answered")
    }

    // ── hasCompleter ─────────────────────────────────────────────────────

    @Test fun `hasCompleter is true exactly when some registered completer can complete`() {
        TranslationBackendRegistry.init(emptyList())
        assertFalse("an empty registry has no LLM", TranslationBackendRegistry.hasCompleter())

        TranslationBackendRegistry.init(listOf(
            FakeOnlineBackend(id = "deepl", priority = 10),
            FakeOfflineBackend(id = "mlkit", priority = 30),
        ))
        assertFalse("translation-only backends are no LLM", TranslationBackendRegistry.hasCompleter())

        TranslationBackendRegistry.init(listOf(
            FakeCompleterBackend(id = "off-1", priority = 10, canComplete = false),
            FakeCompleterBackend(id = "off-2", priority = 20, canComplete = false, requiresInternet = false),
            FakeOnlineBackend(id = "deepl", priority = 30),
        ))
        assertFalse("completers that all say no are no LLM", TranslationBackendRegistry.hasCompleter())

        TranslationBackendRegistry.init(listOf(
            FakeCompleterBackend(id = "off", priority = 10, canComplete = false),
            FakeOnlineBackend(id = "deepl", priority = 20),
            FakeCompleterBackend(id = "qwen", priority = 30, requiresInternet = false),
        ))
        assertTrue("one completer that can complete is enough", TranslationBackendRegistry.hasCompleter())
    }

    // ── order and the first answer ───────────────────────────────────────

    @Test fun `complete tries completers in registry order and the first answer wins`() = runBlocking<Unit> {
        val first = FakeCompleterBackend(id = "first", priority = 10)
        val second = FakeCompleterBackend(id = "second", priority = 20)
        val third = FakeCompleterBackend(id = "third", priority = 30, requiresInternet = false)
        TranslationBackendRegistry.init(listOf(third, second, first)) // registered out of order

        val result = TranslationBackendRegistry.complete("the system", "the user")

        assertEquals("answer-from-first", result.text)
        assertSame(first, result.backend)
        assertEquals("the system" to "the user", first.lastPrompt)
        assertEquals("later completers are not called", 0, second.completeCalls.get())
        assertEquals(0, third.completeCalls.get())
    }

    @Test fun `complete honours setOrder`() = runBlocking<Unit> {
        val byPriority = FakeCompleterBackend(id = "by-priority", priority = 10)
        val userFirst = FakeCompleterBackend(id = "user-first", priority = 20)
        TranslationBackendRegistry.init(listOf(byPriority, userFirst))
        // The precondition: without an override the priority order decides.
        assertSame(byPriority, TranslationBackendRegistry.complete("s", "u").backend)

        TranslationBackendRegistry.setOrder(listOf("user-first"))

        val result = TranslationBackendRegistry.complete("s", "u")
        assertSame(userFirst, result.backend)
        assertEquals(1, byPriority.completeCalls.get())
    }

    @Test fun `complete skips translation-only backends, completers that cannot complete and completers in a cooldown`() = runBlocking<Unit> {
        val translationOnly = FakeOnlineBackend(id = "deepl", priority = 10)
        val cannot = FakeCompleterBackend(id = "cannot", priority = 20, canComplete = false)
        val cooled = cooledDown("cooled", 30)
        val ready = FakeCompleterBackend(id = "ready", priority = 40)
        TranslationBackendRegistry.init(listOf(translationOnly, cannot, cooled, ready))

        val result = TranslationBackendRegistry.complete("s", "u")

        assertSame(ready, result.backend)
        assertEquals("a translation-only backend is not asked to translate the prompt", 0, translationOnly.translateCalls.get())
        assertEquals(0, cannot.completeCalls.get())
        assertEquals(0, cooled.completeCalls.get())
        assertEquals(0, cooled.translateCalls.get())
    }

    @Test fun `an expired cooldown does not skip the completer`() = runBlocking<Unit> {
        // The skip is "until in the future", not "has a cooldown at all".
        val expired = FakeCompleterBackend(id = "expired", priority = 10).apply {
            cooldownState.recordParsedFailure(System.currentTimeMillis() - 1, "Rate limited")
        }
        val next = FakeCompleterBackend(id = "next", priority = 20)
        TranslationBackendRegistry.init(listOf(expired, next))

        assertSame(expired, TranslationBackendRegistry.complete("s", "u").backend)
        assertEquals(0, next.completeCalls.get())
    }

    // ── falling through ──────────────────────────────────────────────────

    @Test fun `a failure falls through and the answer is credited to the completer that gave it`() = runBlocking<Unit> {
        val broken = failing("broken", 10, IOException("synthetic"))
        val crashing = failing("crashing", 20, IllegalStateException("engine"))
        val answering = FakeCompleterBackend(id = "answering", priority = 30, requiresInternet = false)
        TranslationBackendRegistry.init(listOf(broken, crashing, answering))

        val result = TranslationBackendRegistry.complete("s", "u")

        assertSame(answering, result.backend)
        assertEquals("answer-from-answering", result.text)
        assertEquals(1, broken.completeCalls.get())
        assertEquals(1, crashing.completeCalls.get())
    }

    @Test fun `a blank reply is a failure and falls through`() = runBlocking<Unit> {
        val blank = FakeCompleterBackend(id = "blank", priority = 10, answer = { _, _ -> " \n\t " })
        val empty = FakeCompleterBackend(id = "empty", priority = 20, answer = { _, _ -> "" })
        val answering = FakeCompleterBackend(id = "answering", priority = 30)
        TranslationBackendRegistry.init(listOf(blank, empty, answering))

        val result = TranslationBackendRegistry.complete("s", "u")

        assertSame(answering, result.backend)
        assertEquals(1, blank.completeCalls.get())
        assertEquals(1, empty.completeCalls.get())
        assertEquals("a blank winner records no success", 0, blank.recordSuccessCalls.get())
    }

    @Test fun `a blank reply from the only completer ends the pass with that completer named`() {
        val blank = FakeCompleterBackend(id = "blank", priority = 10, answer = { _, _ -> "   " })
        TranslationBackendRegistry.init(listOf(blank))

        val thrown = completeFailure()

        assertTrue("$thrown", thrown is CompletionFailedException)
        assertSame(blank, (thrown as CompletionFailedException).backend)
    }

    // ── recordSuccess ────────────────────────────────────────────────────

    @Test fun `a win records success on the winning completer and a failure records none`() = runBlocking<Unit> {
        val broken = failing("broken", 10, IOException("synthetic"))
        val winner = FakeCompleterBackend(id = "winner", priority = 20)
        val unused = FakeCompleterBackend(id = "unused", priority = 30)
        TranslationBackendRegistry.init(listOf(broken, winner, unused))

        TranslationBackendRegistry.complete("s", "u")

        assertEquals(0, broken.recordSuccessCalls.get())
        assertEquals(1, winner.recordSuccessCalls.get())
        assertEquals(0, unused.recordSuccessCalls.get())
    }

    @Test fun `a win clears the rung a past failure left, as a translated text does`() = runBlocking<Unit> {
        // The counter above says recordSuccess was called; this says it was
        // called with an attempt time that lets it act (the state refuses a
        // success older than its failure). A 5xx left the rate-limit ladder
        // one rung up, and that cooldown has run out on the state's clock.
        val clock = AtomicLong(System.currentTimeMillis())
        val state = CooldownState(context = null, backendId = "winner", nowMs = { clock.get() })
        state.recordLadderFailure(CooldownLadder.RateLimit, "Server error", CooldownCause.SERVER_ERROR)
        clock.addAndGet(61_000)
        val winner = FakeCompleterBackend(id = "winner", priority = 10, cooldownState = state)
        assertNull("precondition: the cooldown has run out", winner.unavailableUntil())
        TranslationBackendRegistry.init(listOf(winner))

        TranslationBackendRegistry.complete("s", "u")

        // Without the clear, the next failure would take the second rung
        // (10 minutes); after it, the first (1 minute).
        state.recordLadderFailure(CooldownLadder.RateLimit, "Server error", CooldownCause.SERVER_ERROR)
        assertEquals(clock.get() + 60_000L, state.unavailableUntil())
    }

    // ── cancellation ─────────────────────────────────────────────────────

    @Test fun `a cancellation from a completer is re-thrown and nothing after it is tried`() {
        val cancelled = FakeCompleterBackend(
            id = "cancelled", priority = 10, answer = { _, _ -> throw CancellationException("question put away") },
        )
        val next = FakeCompleterBackend(id = "next", priority = 20)
        TranslationBackendRegistry.init(listOf(cancelled, next))

        val thrown = completeFailure()

        assertTrue("$thrown", thrown is CancellationException)
        assertFalse("never turned into a failed pass", thrown is CompletionFailedException)
        assertEquals(0, next.completeCalls.get())
        assertEquals(0, cancelled.recordSuccessCalls.get())
    }

    // ── nobody answered ──────────────────────────────────────────────────

    @Test fun `when nobody answers the failure names the first completer tried and what it threw`() {
        val firstCause = OpenAiRateLimitException(BackendFailure(BackendFailureKind.RATE_LIMITED, 429))
        val secondCause = IOException("second")
        val thirdCause = IllegalStateException("third")
        // A cooled-down completer ahead of them: "first tried" is not
        // "first registered".
        val skipped = cooledDown("skipped", 5)
        val first = failing("first", 10, firstCause)
        val second = failing("second", 20, secondCause)
        val third = FakeCompleterBackend(
            id = "third", priority = 30, requiresInternet = false, answer = { _, _ -> throw thirdCause },
        )
        TranslationBackendRegistry.init(listOf(skipped, first, second, third))

        val thrown = completeFailure()

        assertTrue("$thrown", thrown is CompletionFailedException)
        thrown as CompletionFailedException
        assertSame("the first completer tried, not the last", first, thrown.backend)
        assertSame("what the first one threw, not the last one's", firstCause, thrown.cause)
        assertEquals(1, first.completeCalls.get())
        assertEquals(1, second.completeCalls.get())
        assertEquals(1, third.completeCalls.get())
        assertEquals(0, skipped.completeCalls.get())
    }

    @Test fun `when nothing is tried the failure carries neither backend nor cause`() {
        val cases = mapOf(
            "an empty registry" to emptyList(),
            "no completer at all" to listOf<TranslationBackend>(
                FakeOnlineBackend(id = "deepl", priority = 10),
                FakeOfflineBackend(id = "mlkit", priority = 30),
            ),
            "every completer skipped" to listOf<TranslationBackend>(
                FakeCompleterBackend(id = "cannot", priority = 10, canComplete = false),
                cooledDown("cooled", 20),
                FakeOnlineBackend(id = "deepl", priority = 30),
            ),
        )
        for ((case, backends) in cases) {
            TranslationBackendRegistry.init(backends)

            val thrown = completeFailure()

            assertTrue("$case: $thrown", thrown is CompletionFailedException)
            assertNull("$case: backend", (thrown as CompletionFailedException).backend)
            assertNull("$case: cause", thrown.cause)
            TranslationBackendRegistry.close()
        }
    }

    // ── the error pill's listener ────────────────────────────────────────

    @Test fun `complete never reports to the online attempt listener, where a translation pass does`() {
        val reports = mutableListOf<List<OnlineAttempt>>()
        TranslationBackendRegistry.onlineAttemptListener = { reports += it }
        val online = FakeCompleterBackend(id = "online", priority = 10, requiresInternet = true)
        TranslationBackendRegistry.init(listOf(online))

        // The precondition and contrast: a translation over this very
        // online fake reports its attempt.
        runBlocking { TranslationBackendRegistry.translate("hi", "ja", "en") }
        assertEquals("a translation pass reports", 1, reports.size)
        reports.clear()

        runBlocking { TranslationBackendRegistry.complete("s", "u") }
        assertTrue("a winning completion reports nothing: $reports", reports.isEmpty())

        TranslationBackendRegistry.init(listOf(
            failing("refused", 10, OpenAiRateLimitException(BackendFailure(BackendFailureKind.RATE_LIMITED, 429))),
            failing("unreachable", 20, IOException("synthetic connection failure")),
        ))
        val thrown = completeFailure()
        assertTrue("$thrown", thrown is CompletionFailedException)
        assertTrue("a failed completion reports nothing: $reports", reports.isEmpty())
    }
}
