package com.playtranslate.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [DocNav]: a D-pad cursor over a document's targets. Up and down walk the
 * rows and keep the cursor's column; left and right walk a row toward the
 * line's end or back, mirrored on a right-to-left page; an edge is null, so
 * the host can hand focus on.
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
}
