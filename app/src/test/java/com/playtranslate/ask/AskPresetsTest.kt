package com.playtranslate.ask

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.Prefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [AskPresets], the one source of the ready-made questions and their order,
 * and the two prefs behind the Ask section:
 *  - [AskPresets.order]: the presets named in the recency first, in its
 *    order, then the rest in declared order; an id naming no preset is
 *    skipped and a repeated id counts once;
 *  - [AskPresets.markAsked]: the asked preset leads, the others keep their
 *    order, nothing is duplicated;
 *  - [Prefs.askPresetRecency] and [Prefs.hideAskSection]: their defaults
 *    and round trips;
 *  - [AskPresets.ordered]: the built-in Grammar breakdown is offered.
 *
 * Robolectric for Prefs and the preset's title resource.
 */
@RunWith(RobolectricTestRunner::class)
class AskPresetsTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private val a = AskPreset("a", "A", "a {text}")
    private val b = AskPreset("b", "B", "b {text}")
    private val c = AskPreset("c", "C", "c {text}")
    private val d = AskPreset("d", "D", "d {text}")
    private val declared = listOf(a, b, c, d)

    private fun sp() = ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)

    @Before fun clearPrefs() { sp().edit().clear().commit() }
    @After fun tearDown() { sp().edit().clear().commit() }

    // ── order ────────────────────────────────────────────────────────────

    @Test fun `order puts the recently asked first in recency order, then the rest in declared order`() {
        assertEquals(listOf(c, a, b, d), AskPresets.order(declared, listOf("c", "a")))
        assertEquals(listOf(d, c, b, a), AskPresets.order(declared, listOf("d", "c", "b", "a")))
    }

    @Test fun `order skips an id that names no preset`() {
        assertEquals(listOf(b, a, c, d), AskPresets.order(declared, listOf("gone", "b", "also-gone")))
    }

    @Test fun `order counts a repeated id once`() {
        assertEquals(listOf(b, a, c, d), AskPresets.order(declared, listOf("b", "b", "a", "b")))
    }

    @Test fun `an empty recency is the declared order`() {
        assertEquals(declared, AskPresets.order(declared, emptyList()))
    }

    // ── markAsked ────────────────────────────────────────────────────────

    @Test fun `markAsked puts the preset first, keeps the others' order and never duplicates`() {
        val prefs = Prefs(ctx)
        prefs.askPresetRecency = listOf("b", "c", "d")

        AskPresets.markAsked(prefs, a)
        assertEquals("a new preset leads", listOf("a", "b", "c", "d"), prefs.askPresetRecency)

        AskPresets.markAsked(prefs, c)
        assertEquals("an asked one moves up, once", listOf("c", "a", "b", "d"), prefs.askPresetRecency)

        AskPresets.markAsked(prefs, c)
        assertEquals("asking the leader again changes nothing", listOf("c", "a", "b", "d"), prefs.askPresetRecency)
    }

    @Test fun `asking A, B, A gives A then B`() {
        val prefs = Prefs(ctx)

        AskPresets.markAsked(prefs, a)
        AskPresets.markAsked(prefs, b)
        AskPresets.markAsked(prefs, a)

        assertEquals(listOf("a", "b"), prefs.askPresetRecency)
        // And a fresh Prefs reads the same order back from storage.
        assertEquals(listOf("a", "b"), Prefs(ctx).askPresetRecency)
    }

    // ── the prefs ────────────────────────────────────────────────────────

    @Test fun `askPresetRecency is empty by default and round-trips a list`() {
        assertEquals(emptyList<String>(), Prefs(ctx).askPresetRecency)

        Prefs(ctx).askPresetRecency = listOf(AskPresets.GRAMMAR_BREAKDOWN, "b", "a")

        assertEquals(listOf(AskPresets.GRAMMAR_BREAKDOWN, "b", "a"), Prefs(ctx).askPresetRecency)
    }

    @Test fun `hideAskSection is false by default and round-trips`() {
        assertFalse("the Ask section starts shown", Prefs(ctx).hideAskSection)

        Prefs(ctx).hideAskSection = true
        assertTrue(Prefs(ctx).hideAskSection)

        Prefs(ctx).hideAskSection = false
        assertFalse(Prefs(ctx).hideAskSection)
    }

    // ── ordered ──────────────────────────────────────────────────────────

    @Test fun `ordered offers the built-in Grammar breakdown`() {
        val grammar = AskPresets.ordered(ctx, Prefs(ctx)).singleOrNull { it.id == AskPresets.GRAMMAR_BREAKDOWN }

        requireNotNull(grammar) { "Grammar breakdown missing from AskPresets.ordered" }
        assertTrue("a title to show", grammar.title.isNotBlank())
        assertTrue("a prompt that carries the sentence: ${grammar.prompt}", grammar.prompt.contains("{text}"))
    }
}
