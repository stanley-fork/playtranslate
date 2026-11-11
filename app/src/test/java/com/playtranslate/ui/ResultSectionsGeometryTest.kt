package com.playtranslate.ui

import com.playtranslate.ui.ResultSectionsGeometry.PairedRow
import com.playtranslate.ui.ResultSectionsGeometry.Span
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [ResultSectionsGeometry.pairedRow], the paired top row of
 * the results sections: Original leads, Translation trails, a hairline
 * between. No Android. Pins the design's hide table ("the eye removes the
 * card, never the header"): both shown, the headers halve the row and each
 * card sits under its own header; one hidden, its header shrinks to the
 * fixed 140dp cell at its own end and the other card takes the full width;
 * both hidden, the headers halve the row and there is no card row. An odd
 * pixel goes to the trailing cell, never to a gap; the hidden cell never
 * takes more than half; and the row's edges, the gap and the hairline obey
 * the same invariants at every width.
 *
 * The numbers are the design's at 1px per dp: a 1px hairline with 8px
 * either side (a 17px gap) and a 140px hidden cell, so a 776px row with
 * Translation hidden gives an Original header of 776 - 140 - 17 = 619.
 */
class ResultSectionsGeometryTest {

    private val divider = 1
    private val margin = 8
    private val hiddenCell = 140

    /** The space between the two header cells: the hairline and its margins. */
    private val gap = divider + 2 * margin

    private fun row(width: Int, sourceHidden: Boolean, targetHidden: Boolean): PairedRow =
        ResultSectionsGeometry.pairedRow(width, divider, margin, hiddenCell, sourceHidden, targetHidden)

    private fun bothShown(width: Int) = row(width, sourceHidden = false, targetHidden = false)
    private fun translationHidden(width: Int) = row(width, sourceHidden = false, targetHidden = true)
    private fun originalHidden(width: Int) = row(width, sourceHidden = true, targetHidden = false)
    private fun bothHidden(width: Int) = row(width, sourceHidden = true, targetHidden = true)

    /** (sourceHidden, targetHidden), every combination of the two eyes. */
    private val combinations = listOf(false to false, false to true, true to false, true to true)

    @Test
    fun `the design's numbers - a 140dp hidden cell and a 1dp hairline with 8dp either side`() {
        // The layout scales these by the density; the hide table is drawn
        // in them, so a change here is a change to the design.
        assertEquals(140, ResultSectionsGeometry.HIDDEN_HEADER_CELL_DP)
        assertEquals(1, ResultSectionsGeometry.DIVIDER_DP)
        assertEquals(8, ResultSectionsGeometry.DIVIDER_MARGIN_DP)
    }

    @Test
    fun `both shown, the headers halve the row and each card sits under its own header, one hairline between`() {
        // 776: shared 759, half 379, the trailing cell 380 from 396.
        assertEquals(
            PairedRow(Span(0, 379), Span(396, 380), 387, Span(0, 379), Span(396, 380), 387),
            bothShown(776),
        )
        // 891: shared 874, half 437.
        assertEquals(
            PairedRow(Span(0, 437), Span(454, 437), 445, Span(0, 437), Span(454, 437), 445),
            bothShown(891),
        )
        // 616: shared 599, half 299, the trailing cell 300.
        assertEquals(
            PairedRow(Span(0, 299), Span(316, 300), 307, Span(0, 299), Span(316, 300), 307),
            bothShown(616),
        )
        // 759: shared 742, half 371.
        assertEquals(
            PairedRow(Span(0, 371), Span(388, 371), 379, Span(0, 371), Span(388, 371), 379),
            bothShown(759),
        )
    }

    @Test
    fun `an odd pixel goes to the trailing cell, never to a gap`() {
        for (width in gap..1600) {
            val r = bothShown(width)
            val shared = width - gap
            assertEquals("leading cell at $width", shared / 2, r.sourceHeader.width)
            assertEquals("trailing cell at $width", shared - shared / 2, r.targetHeader.width)
            assertEquals("the gap at $width", gap, r.targetHeader.start - r.sourceHeader.end)
        }
    }

    @Test
    fun `Translation hidden, its header is the fixed cell at the trailing end and the Original card takes the full width`() {
        // The design's "sheetWidth - 140 - 17": 776 - 157 = 619.
        assertEquals(
            PairedRow(Span(0, 619), Span(636, 140), 627, Span(0, 776), null, null),
            translationHidden(776),
        )
        assertEquals(
            PairedRow(Span(0, 734), Span(751, 140), 742, Span(0, 891), null, null),
            translationHidden(891),
        )
        assertEquals(
            PairedRow(Span(0, 459), Span(476, 140), 467, Span(0, 616), null, null),
            translationHidden(616),
        )
        assertEquals(
            PairedRow(Span(0, 602), Span(619, 140), 610, Span(0, 759), null, null),
            translationHidden(759),
        )
    }

    @Test
    fun `Original hidden, its header is the fixed cell at the leading end and the Translation card takes the full width`() {
        assertEquals(
            PairedRow(Span(0, 140), Span(157, 619), 148, null, Span(0, 776), null),
            originalHidden(776),
        )
        assertEquals(
            PairedRow(Span(0, 140), Span(157, 734), 148, null, Span(0, 891), null),
            originalHidden(891),
        )
        assertEquals(
            PairedRow(Span(0, 140), Span(157, 459), 148, null, Span(0, 616), null),
            originalHidden(616),
        )
        assertEquals(
            PairedRow(Span(0, 140), Span(157, 602), 148, null, Span(0, 759), null),
            originalHidden(759),
        )
    }

    @Test
    fun `both hidden, the headers halve the row exactly as when both are shown, and there is no card row`() {
        assertEquals(PairedRow(Span(0, 379), Span(396, 380), 387, null, null, null), bothHidden(776))
        for (width in listOf(616, 759, 776, 891)) {
            val shown = bothShown(width)
            assertEquals(
                "at $width",
                PairedRow(shown.sourceHeader, shown.targetHeader, shown.headerDividerStart, null, null, null),
                bothHidden(width),
            )
        }
    }

    @Test
    fun `the hidden cell never takes more than half, so a narrow row degrades to halves`() {
        // 217: shared 200, half 100, under the 140 cell: two equal halves.
        assertEquals(
            PairedRow(Span(0, 100), Span(117, 100), 108, Span(0, 217), null, null),
            translationHidden(217),
        )
        assertEquals(
            PairedRow(Span(0, 100), Span(117, 100), 108, null, Span(0, 217), null),
            originalHidden(217),
        )
        // 200: shared 183, half 91. The hidden cell takes the half, the
        // shown one the rest, so the odd pixel goes to the shown cell.
        assertEquals(
            PairedRow(Span(0, 92), Span(109, 91), 100, Span(0, 200), null, null),
            translationHidden(200),
        )
        assertEquals(
            PairedRow(Span(0, 91), Span(108, 92), 99, null, Span(0, 200), null),
            originalHidden(200),
        )
        // Across the boundary: half 139 caps the cell, half 140 is the cell.
        assertEquals(139, translationHidden(296).targetHeader.width)
        assertEquals(140, translationHidden(297).targetHeader.width)
        assertEquals(140, translationHidden(298).targetHeader.width)
        for (width in gap..1600) {
            val half = (width - gap) / 2
            val cell = minOf(hiddenCell, half)
            assertEquals("hidden Translation's cell at $width", cell, translationHidden(width).targetHeader.width)
            assertEquals("hidden Original's cell at $width", cell, originalHidden(width).sourceHeader.width)
        }
    }

    @Test
    fun `the row's edges, the gap and the hairline hold for every combination at every width`() {
        for ((sourceHidden, targetHidden) in combinations) {
            for (width in gap..1600) {
                val r = row(width, sourceHidden, targetHidden)
                val at = "source hidden $sourceHidden, target hidden $targetHidden, width $width"
                assertEquals("the Original header starts the row: $at", 0, r.sourceHeader.start)
                assertEquals("the Translation header ends the row: $at", width, r.targetHeader.end)
                assertEquals("one gap between the cells: $at", gap, r.targetHeader.start - r.sourceHeader.end)
                assertEquals("the hairline a margin past the Original cell: $at",
                    r.sourceHeader.end + margin, r.headerDividerStart)
            }
        }
    }

    @Test
    fun `the cards follow the hide table at every width`() {
        for (width in gap..1600) {
            val shown = bothShown(width)
            assertEquals("at $width", shown.sourceHeader, shown.sourceCard)
            assertEquals("at $width", shown.targetHeader, shown.targetCard)
            assertEquals("the card hairline continues the header's at $width",
                shown.headerDividerStart, shown.cardDividerStart)

            val noTranslation = translationHidden(width)
            assertEquals("at $width", Span(0, width), noTranslation.sourceCard)
            assertNull(noTranslation.targetCard)
            assertNull(noTranslation.cardDividerStart)

            val noOriginal = originalHidden(width)
            assertNull(noOriginal.sourceCard)
            assertEquals("at $width", Span(0, width), noOriginal.targetCard)
            assertNull(noOriginal.cardDividerStart)

            val neither = bothHidden(width)
            assertNull(neither.sourceCard)
            assertNull(neither.targetCard)
            assertNull(neither.cardDividerStart)
        }
    }
}
