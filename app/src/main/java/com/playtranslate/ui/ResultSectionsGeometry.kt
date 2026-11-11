package com.playtranslate.ui

/**
 * Pure, Android-free geometry for [ResultSectionsLayout]: where the Original
 * and Translation sections' header cells and cards sit across the layout's
 * width when the two are paired. Isolated so the whole hide table can be
 * unit-tested without a view.
 *
 * The rule ("the eye removes the card, never the header"): a hidden
 * section keeps its header cell and gives up its card. With one of the pair
 * hidden its header shrinks to a fixed cell and the other section's card
 * takes the full width beneath both headers; with both hidden the two
 * headers share the row and there is no card row.
 *
 * All positions are START-relative (0 is the row's start edge); the layout
 * mirrors them for a right-to-left direction.
 */
object ResultSectionsGeometry {

    /** A horizontal extent: [start] from the row's start edge, [width] wide. */
    data class Span(val start: Int, val width: Int) {
        val end: Int get() = start + width
    }

    /**
     * The paired top row. Original leads (start side), Translation trails.
     * A null card is a hidden section's. [headerDividerStart] is the start
     * edge of the hairline between the two header cells, always present;
     * [cardDividerStart] the one between the two cards, present only while
     * both cards are.
     */
    data class PairedRow(
        val sourceHeader: Span,
        val targetHeader: Span,
        val headerDividerStart: Int,
        val sourceCard: Span?,
        val targetCard: Span?,
        val cardDividerStart: Int?,
    )

    /**
     * Lay the pair out across [widthPx]. [dividerPx] is the hairline's own
     * width and [dividerMarginPx] the clear space on each side of it, so the
     * two cells stand `dividerPx + 2 * dividerMarginPx` apart.
     * [hiddenCellPx] is the fixed width of a hidden section's header cell
     * while the other section is shown; it never takes more than half of
     * what the two cells share, so a narrow row degrades to halves.
     */
    fun pairedRow(
        widthPx: Int,
        dividerPx: Int,
        dividerMarginPx: Int,
        hiddenCellPx: Int,
        sourceHidden: Boolean,
        targetHidden: Boolean,
    ): PairedRow {
        val gap = dividerPx + 2 * dividerMarginPx
        val shared = (widthPx - gap).coerceAtLeast(0)
        val half = shared / 2
        // The leading cell's width decides everything else: the divider
        // follows it, and the trailing cell takes what is left (so an odd
        // pixel goes to the trailing cell, never to a gap).
        val leading = when {
            sourceHidden == targetHidden -> half
            sourceHidden -> minOf(hiddenCellPx, half)
            else -> shared - minOf(hiddenCellPx, half)
        }
        val sourceHeader = Span(0, leading)
        val targetHeader = Span(leading + gap, shared - leading)
        val dividerStart = leading + dividerMarginPx
        val full = Span(0, widthPx)
        return when {
            sourceHidden && targetHidden ->
                PairedRow(sourceHeader, targetHeader, dividerStart, null, null, null)
            sourceHidden ->
                PairedRow(sourceHeader, targetHeader, dividerStart, null, full, null)
            targetHidden ->
                PairedRow(sourceHeader, targetHeader, dividerStart, full, null, null)
            else ->
                PairedRow(sourceHeader, targetHeader, dividerStart, sourceHeader, targetHeader, dividerStart)
        }
    }

    /** A hidden section's header cell while its pair is shown, in dp. Wide
     *  enough for a language name and the crossed eye. */
    const val HIDDEN_HEADER_CELL_DP = 140

    /** The hairline between the paired cells, and the clear space on each
     *  side of it, in dp. */
    const val DIVIDER_DP = 1
    const val DIVIDER_MARGIN_DP = 8
}
