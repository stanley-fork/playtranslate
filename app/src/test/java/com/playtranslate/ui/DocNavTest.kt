package com.playtranslate.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [DocNav]: a D-pad cursor over a document's targets, by where the page laid
 * them out. A line is the consecutive rows that share a top and stand side
 * by side: one row in one column, the rows across in two. Up and down walk
 * the lines, onto the row nearest across (the cursor keeps its column) and
 * the cursor's kind of target; left and right walk a line, from one row's
 * buttons onto the next row's, toward the line's end or back, mirrored on a
 * right-to-left page; an edge is null, so the host can hand focus on. A row
 * that takes no tap itself still places its line and its column.
 *
 * The first tests give every target one spot, which is a column, so they
 * pin one column as it always moved; a column laid out with real rects
 * moves the same.
 */
class DocNavTest {

    private fun t(row: String, act: String) = DocTarget(row, act, 0, 0, 10, 10)

    private val targets = listOf(
        t("a", DefAction.OPEN), t("a", DefAction.SPEAK), t("a", DefAction.TRAILING),
        // A stub: no speak button.
        t("b", DefAction.OPEN), t("b", DefAction.TRAILING),
        t("c", DefAction.OPEN), t("c", DefAction.SPEAK), t("c", DefAction.TRAILING),
        // A row that is no target itself contributes none.
        t("d", ""),
    )

    private fun next(cur: DocTarget?, dir: DocNav.Dir, rtl: Boolean = false) =
        DocNav.next(targets, cur, dir, rtl)

    @Test fun `a fresh cursor lands on the first row coming down and the last going up`() {
        assertEquals(t("a", DefAction.OPEN), next(null, DocNav.Dir.DOWN))
        assertEquals(t("c", DefAction.OPEN), next(null, DocNav.Dir.UP))
    }

    @Test fun `down and up keep the column, or take the row when the next has no such button`() {
        assertEquals(t("b", DefAction.TRAILING), next(t("a", DefAction.TRAILING), DocNav.Dir.DOWN))
        assertEquals(t("b", DefAction.OPEN), next(t("a", DefAction.SPEAK), DocNav.Dir.DOWN))
        assertEquals(t("a", DefAction.OPEN), next(t("b", DefAction.OPEN), DocNav.Dir.UP))
    }

    @Test fun `the first and last rows are edges`() {
        assertNull(next(t("a", DefAction.OPEN), DocNav.Dir.UP))
        assertNull(next(t("c", DefAction.TRAILING), DocNav.Dir.DOWN))
    }

    @Test fun `right walks from the row to its buttons and stops at the last`() {
        assertEquals(t("a", DefAction.SPEAK), next(t("a", DefAction.OPEN), DocNav.Dir.RIGHT))
        assertEquals(t("a", DefAction.TRAILING), next(t("a", DefAction.SPEAK), DocNav.Dir.RIGHT))
        assertNull(next(t("a", DefAction.TRAILING), DocNav.Dir.RIGHT))
        assertNull(next(t("a", DefAction.OPEN), DocNav.Dir.LEFT))
    }

    @Test fun `on a right-to-left page the buttons lie to the left`() {
        assertEquals(t("a", DefAction.SPEAK), next(t("a", DefAction.OPEN), DocNav.Dir.LEFT, rtl = true))
        assertEquals(t("a", DefAction.OPEN), next(t("a", DefAction.SPEAK), DocNav.Dir.RIGHT, rtl = true))
        assertNull(next(t("a", DefAction.OPEN), DocNav.Dir.RIGHT, rtl = true))
    }

    @Test fun `a cursor whose target is gone starts again`() {
        assertEquals(t("a", DefAction.OPEN), next(t("z", DefAction.OPEN), DocNav.Dir.DOWN))
    }

    @Test fun `no targets, no cursor`() {
        assertNull(DocNav.next(listOf(t("d", "")), null, DocNav.Dir.DOWN, false))
    }

    // ── A laid-out page: targets at the rects the page reports ──────────

    /**
     * Row [id] as the page reports it: its own element at [left], [top],
     * 300 wide and [height] tall, a tap target for [own] ('' when it takes
     * none), then [buttons] along its head line toward its end, which is
     * leftward on a right-to-left page.
     */
    private fun laid(
        id: String,
        left: Int,
        top: Int,
        height: Int = 100,
        own: String = DefAction.OPEN,
        buttons: List<String> = listOf(DefAction.SPEAK, DefAction.TRAILING),
        rtl: Boolean = false,
    ): List<DocTarget> = listOf(DocTarget(id, own, left, top, 300, height)) +
        buttons.mapIndexed { i, act ->
            val offset = 180 + 52 * i
            DocTarget(id, act, if (rtl) left + 300 - offset - 36 else left + offset, top + 14, 36, 40)
        }

    /** A over C in the first column, B over D in the second, as a 608px
     *  page lays them out (300px columns, an 8px gap). The first column is
     *  on the right of a right-to-left page. */
    private fun grid(rtl: Boolean = false): List<DocTarget> {
        val first = if (rtl) 308 else 0
        val second = if (rtl) 0 else 308
        return laid("a", first, 0, rtl = rtl) + laid("b", second, 0, rtl = rtl) +
            laid("c", first, 100, rtl = rtl) + laid("d", second, 100, rtl = rtl)
    }

    /** The target of [row] that fires [act]. */
    private fun List<DocTarget>.of(row: String, act: String) = single { it.rowId == row && it.act == act }

    private fun move(page: List<DocTarget>, from: DocTarget?, dir: DocNav.Dir, rtl: Boolean = false) =
        DocNav.next(page, from, dir, rtl)

    @Test fun `in two columns down and up move between lines, keeping the cursor's column and its kind of target`() {
        val g = grid()
        assertEquals("the row below, not the row beside", g.of("c", DefAction.OPEN), move(g, g.of("a", DefAction.OPEN), DocNav.Dir.DOWN))
        assertEquals(g.of("d", DefAction.TRAILING), move(g, g.of("b", DefAction.TRAILING), DocNav.Dir.DOWN))
        assertEquals(g.of("a", DefAction.OPEN), move(g, g.of("c", DefAction.OPEN), DocNav.Dir.UP))
        assertEquals(g.of("b", DefAction.TRAILING), move(g, g.of("d", DefAction.TRAILING), DocNav.Dir.UP))
        // The first line and the last are edges in either column, though a
        // row comes before B and after C in the page.
        assertNull(move(g, g.of("b", DefAction.OPEN), DocNav.Dir.UP))
        assertNull(move(g, g.of("c", DefAction.OPEN), DocNav.Dir.DOWN))
    }

    @Test fun `in two columns a fresh cursor lands on the first row coming down and the last row going up`() {
        val g = grid()
        assertEquals(g.of("a", DefAction.OPEN), move(g, null, DocNav.Dir.DOWN))
        assertEquals("the last row, not the last line's first", g.of("d", DefAction.OPEN), move(g, null, DocNav.Dir.UP))
    }

    @Test fun `in two columns left and right walk a line, from one row's buttons onto the next row of the line`() {
        val g = grid()
        assertEquals(g.of("b", DefAction.OPEN), move(g, g.of("a", DefAction.TRAILING), DocNav.Dir.RIGHT))
        assertEquals(g.of("a", DefAction.TRAILING), move(g, g.of("b", DefAction.OPEN), DocNav.Dir.LEFT))
        assertNull("the line's end, not the next line", move(g, g.of("b", DefAction.TRAILING), DocNav.Dir.RIGHT))
        assertNull(move(g, g.of("a", DefAction.OPEN), DocNav.Dir.LEFT))
    }

    @Test fun `on a right-to-left page the first column is on the right and a line runs leftward`() {
        val g = grid(rtl = true)
        assertEquals(g.of("b", DefAction.OPEN), move(g, g.of("a", DefAction.TRAILING), DocNav.Dir.LEFT, rtl = true))
        assertEquals(g.of("a", DefAction.TRAILING), move(g, g.of("b", DefAction.OPEN), DocNav.Dir.RIGHT, rtl = true))
        assertNull(move(g, g.of("a", DefAction.OPEN), DocNav.Dir.RIGHT, rtl = true))
        assertNull(move(g, g.of("b", DefAction.TRAILING), DocNav.Dir.LEFT, rtl = true))
        // Down keeps the column, mirrored as it is.
        assertEquals(g.of("c", DefAction.SPEAK), move(g, g.of("a", DefAction.SPEAK), DocNav.Dir.DOWN, rtl = true))
        assertEquals(g.of("d", DefAction.OPEN), move(g, g.of("b", DefAction.OPEN), DocNav.Dir.DOWN, rtl = true))
    }

    @Test fun `a last line holding one row takes the cursor from either column, and up returns it to its own`() {
        val g = laid("a", 0, 0) + laid("b", 308, 0) + laid("c", 0, 100)
        assertEquals(g.of("c", DefAction.OPEN), move(g, g.of("b", DefAction.OPEN), DocNav.Dir.DOWN))
        assertEquals(g.of("c", DefAction.TRAILING), move(g, g.of("b", DefAction.TRAILING), DocNav.Dir.DOWN))
        assertEquals(g.of("c", DefAction.OPEN), move(g, g.of("a", DefAction.OPEN), DocNav.Dir.DOWN))
        assertEquals("its own column, not the row before it", g.of("a", DefAction.OPEN), move(g, g.of("c", DefAction.OPEN), DocNav.Dir.UP))
    }

    @Test fun `down onto a row without the cursor's kind of target lands on that row's first`() {
        // C has no eye.
        val g = laid("a", 0, 0) + laid("b", 308, 0) +
            laid("c", 0, 100, buttons = listOf(DefAction.SPEAK)) + laid("d", 308, 100)
        assertEquals(g.of("c", DefAction.OPEN), move(g, g.of("a", DefAction.TRAILING), DocNav.Dir.DOWN))
        assertEquals("a kind it has is kept", g.of("c", DefAction.SPEAK), move(g, g.of("a", DefAction.SPEAK), DocNav.Dir.DOWN))
    }

    @Test fun `rows of one line may differ in height, and the next line starts below the taller`() {
        val g = laid("a", 0, 0, height = 100) + laid("b", 308, 0, height = 160) +
            laid("c", 0, 160) + laid("d", 308, 160)
        assertEquals("one line", g.of("b", DefAction.OPEN), move(g, g.of("a", DefAction.TRAILING), DocNav.Dir.RIGHT))
        assertEquals(g.of("c", DefAction.OPEN), move(g, g.of("a", DefAction.OPEN), DocNav.Dir.DOWN))
        assertEquals(g.of("d", DefAction.OPEN), move(g, g.of("b", DefAction.OPEN), DocNav.Dir.DOWN))
        assertEquals(g.of("a", DefAction.SPEAK), move(g, g.of("c", DefAction.SPEAK), DocNav.Dir.UP))
        assertEquals(g.of("b", DefAction.SPEAK), move(g, g.of("d", DefAction.SPEAK), DocNav.Dir.UP))
    }

    @Test fun `a row that takes no tap itself still places its line and its column`() {
        // A and F take no tap themselves: each one's own element is reported,
        // with no act, before its buttons.
        val g = laid("a", 0, 0, own = "") + laid("b", 308, 0) +
            laid("c", 0, 100) + laid("d", 308, 100) +
            laid("e", 0, 200) + laid("f", 308, 200, own = "")
        // Its buttons are reachable along its line; its own element is no stop.
        assertEquals(g.of("a", DefAction.SPEAK), move(g, null, DocNav.Dir.DOWN))
        assertEquals(g.of("b", DefAction.OPEN), move(g, g.of("a", DefAction.TRAILING), DocNav.Dir.RIGHT))
        assertEquals(g.of("a", DefAction.TRAILING), move(g, g.of("b", DefAction.OPEN), DocNav.Dir.LEFT))
        assertNull(move(g, g.of("a", DefAction.SPEAK), DocNav.Dir.LEFT))
        // Its own element places its column, not its first button, which
        // sits nearer the other column.
        assertEquals(g.of("c", DefAction.SPEAK), move(g, g.of("a", DefAction.SPEAK), DocNav.Dir.DOWN))
        assertEquals(g.of("f", DefAction.SPEAK), move(g, g.of("d", DefAction.OPEN), DocNav.Dir.DOWN))
        assertEquals(g.of("d", DefAction.TRAILING), move(g, g.of("f", DefAction.TRAILING), DocNav.Dir.UP))
    }

    @Test fun `one column laid out with real rects moves exactly as the same rows at one spot`() {
        // The rows of the first tests, stacked the way a page lays out one
        // column: the same left, each row below the last.
        val column = laid("a", 0, 0) +
            laid("b", 0, 100, buttons = listOf(DefAction.TRAILING)) +
            laid("c", 0, 200) +
            DocTarget("d", "", 0, 300, 300, 100)
        assertEquals(
            "precondition: the same targets in the same order",
            targets.map { it.rowId to it.act }, column.map { it.rowId to it.act },
        )
        val starts = listOf(null, t("z", DefAction.OPEN)) + targets.filter { it.act.isNotEmpty() }
        for (rtl in listOf(false, true)) for (dir in DocNav.Dir.entries) for (from in starts) {
            val laidFrom = from?.let { f -> column.firstOrNull { it.rowId == f.rowId && it.act == f.act } ?: f }
            assertEquals(
                "$dir from $from, rtl=$rtl",
                DocNav.next(targets, from, dir, rtl)?.let { it.rowId to it.act },
                DocNav.next(column, laidFrom, dir, rtl)?.let { it.rowId to it.act },
            )
        }
    }
}
