package com.playtranslate.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [computeGrownCardHeight] — the pure clamp math behind the
 * magnifier lens's post-release grow-to-fit. No Android types involved, so it
 * runs as a plain JVM test.
 *
 * Conventions (matching [MagnifierLens]'s real constants): overhang = 26,
 * base card = 120. `anchoredEdgeY` is the card edge that stays put: card
 * BOTTOM (not flipped) or card TOP (flipped).
 */
class MagnifierLensGeometryTest {

    private val overhang = 26
    private val base = 120

    private fun grow(
        flipped: Boolean,
        anchoredEdgeY: Int,
        desiredCardH: Int,
        safeTop: Int = 0,
        safeBottom: Int = 2000,
    ) = computeGrownCardHeight(
        flipped = flipped,
        anchoredEdgeY = anchoredEdgeY,
        desiredCardH = desiredCardH,
        baseCardH = base,
        safeTop = safeTop,
        safeBottom = safeBottom,
        overhang = overhang,
    )

    @Test
    fun notFlipped_contentFits_growsToDesired() {
        // maxCardH = 1000 - 26 - 100 = 874; desired 300 fits.
        assertEquals(300, grow(flipped = false, anchoredEdgeY = 1000, desiredCardH = 300, safeTop = 100))
    }

    @Test
    fun notFlipped_contentTooTall_clampsToSafeTop() {
        // maxCardH = 1000 - 26 - 100 = 874.
        assertEquals(874, grow(flipped = false, anchoredEdgeY = 1000, desiredCardH = 2000, safeTop = 100))
    }

    @Test
    fun flipped_contentFits_growsToDesired() {
        // maxCardH = 2000 - 26 - 200 = 1774; desired 400 fits.
        assertEquals(400, grow(flipped = true, anchoredEdgeY = 200, desiredCardH = 400, safeBottom = 2000))
    }

    @Test
    fun flipped_contentTooTall_clampsToSafeBottom() {
        // maxCardH = 2000 - 26 - 200 = 1774.
        assertEquals(1774, grow(flipped = true, anchoredEdgeY = 200, desiredCardH = 3000, safeBottom = 2000))
    }

    @Test
    fun shortContent_flooredToBase_neverShrinks() {
        assertEquals(base, grow(flipped = false, anchoredEdgeY = 1000, desiredCardH = 80, safeTop = 100))
    }

    @Test
    fun pinnedNearEdge_maxBelowBase_staysAtBase() {
        // Geometric maxCardH = 200 - 26 - 100 = 74 (< base): no room, keep base.
        assertEquals(base, grow(flipped = false, anchoredEdgeY = 200, desiredCardH = 500, safeTop = 100))
    }
}

/**
 * The pill's width cap and the chip lanes it exists to protect.
 *
 * The pill is a capsule centered on the card's edge: [computeMaxPillWidth] is
 * where it has to stop so a long word's capsule never hangs past the card's
 * rounded corners, and [computePillTextAllotment] is how the word and reading
 * are squeezed into it.
 *
 * Numbers below are [MagnifierLens]'s at density 1: a 380 card and a 16 inset
 * (the card's corner radius) on each side.
 */
class MagnifierLensPillWidthTest {

    private val cardW = 380
    private val sideInset = 16
    private val cap = computeMaxPillWidth(cardW, sideInset)

    /** Pill chrome with a reading shown: 18 + 14 padding, 2 divider, 2 × 12
     *  gaps. (The pill carries no chevron; the open cue is on the body.) */
    private val chrome = 58

    @Test
    fun capLeavesTheInsetOnEachSide() {
        assertEquals(380 - 2 * 16, cap)
    }

    @Test
    fun shortReading_takesWhatItNeeds_headlineGetsTheRest() {
        val (word, reading) = computePillTextAllotment(cap, chrome, readingWidth = 60)
        assertEquals(60, reading)
        assertEquals(cap - chrome - 60, word)
    }

    @Test
    fun longReading_cannotStarveTheHeadline() {
        // A reading that wants the whole budget is held to half of it.
        val budget = cap - chrome
        val (word, reading) = computePillTextAllotment(cap, chrome, readingWidth = budget * 2)
        assertEquals(budget - budget / 2, word)
        assertEquals(budget / 2, reading)
    }

    @Test
    fun noReading_headlineTakesTheWholeBudget() {
        // Kana-only and reading-less pills: no divider/gaps in the chrome either.
        val chromeNoReading = 32
        val (word, reading) = computePillTextAllotment(cap, chromeNoReading, readingWidth = 0)
        assertEquals(cap - chromeNoReading, word)
        assertEquals(0, reading)
    }

    /** The bound the whole fix rests on: the two allotments become the two
     *  views' maxWidths, so chrome + word + reading is what the capsule can
     *  measure to — and it never exceeds the cap, whatever the reading asked
     *  for. */
    @Test
    fun allotmentsPlusChrome_neverExceedTheCap() {
        for (readingWidth in 0..(cap * 2) step 7) {
            val (word, reading) = computePillTextAllotment(cap, chrome, readingWidth)
            assertTrue("negative headline allotment at reading $readingWidth", word >= 0)
            assertTrue("negative reading allotment at reading $readingWidth", reading >= 0)
            assertEquals(cap, chrome + word + reading)
        }
    }

    /** A card narrower than its two insets yields no pill at all rather than
     *  a negative one — the arithmetic stays sane on a tiny display. */
    @Test
    fun cardNarrowerThanItsInsets_capsAtZero() {
        assertEquals(0, computeMaxPillWidth(24, sideInset))
        assertEquals(0 to 0, computePillTextAllotment(0, chrome, readingWidth = 40))
    }
}
