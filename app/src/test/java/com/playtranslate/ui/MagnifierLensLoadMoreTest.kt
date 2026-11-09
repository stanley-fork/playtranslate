package com.playtranslate.ui

import android.app.Activity
import android.os.Bundle
import android.os.Looper
import android.view.Display
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import com.playtranslate.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * Pins the magnifier lens's body as rows of one document: every section is
 * a row with its own headword (the tapped unit's included, so the pill is
 * hidden while rows show), and the "Load more (n)" row ([LensLoadMore])
 * reads the count, reaches [MagnifierLens.onLoadMoreTap] once per gesture
 * and never while loading, is redrawn by [MagnifierLens.setLoadMoreLoading],
 * is the body's last controller target, and is removed by a rebind without
 * it (moving a controller cursor that sat on it to the target in its slot,
 * or to the revealed row). Also where a rebind leaves the body's viewport
 * ([LensScroll]): Keep keeps the offset, Reveal lands on the secondary's
 * row through the card's grow, Top is at the top even over a Keep bind.
 *
 * No engine lays a page out here, so the test stands in for it
 * ([MagnifierLens.layoutRowsForTest]); the scroll and the card's fit run on
 * real layout passes.
 */
@RunWith(RobolectricTestRunner::class)
class MagnifierLensLoadMoreTest {

    class Host : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_PlayTranslate)
            super.onCreate(savedInstanceState)
        }
    }

    private lateinit var controller: ActivityController<Host>
    private lateinit var lens: MagnifierLens
    private var loadMoreTaps = 0

    private fun section(word: String, reading: String, opens: Boolean = true) = LensSection(
        WordDefinitionData(word, reading, emptyList(), freqScore = 0, isCommon = false),
        label = null,
        opens = opens,
    )

    private val primary = section("弾ける", "はじける")
    private val sec1 = section("弾く", "ひく")
    private val sec2 = section("弾く", "はじく")

    @Before
    fun setUp() {
        DefinitionShells.reset()
        controller = Robolectric.buildActivity(Host::class.java).setup()
        val activity = controller.get()
        lens = MagnifierLens(activity, activity.windowManager, Display.DEFAULT_DISPLAY)
        lens.onLoadMoreTap = { loadMoreTaps++ }
        val dm = activity.resources.displayMetrics
        // Finger low on the screen: the lens sits above it, not flipped, so
        // the body is below the chrome and DPAD_DOWN walks into it.
        lens.show(dm.widthPixels / 2, dm.heightPixels * 3 / 4, dm.widthPixels, dm.heightPixels)
        lens.makeInteractive()
    }

    @After
    fun tearDown() {
        lens.dismiss()
        controller.pause().stop().destroy()
        DefinitionShells.reset()
    }

    /** Moves the clock past the 300 ms debounce the row shares with the open
     *  taps. Robolectric's clock starts near zero, which is inside the window
     *  of a lens that has never been tapped. */
    private fun pastDebounce() = ShadowSystemClock.advanceBy(Duration.ofMillis(400))

    /** The page's layout report for what is bound, every row [ROW] tall. */
    private fun laidOut(): List<String> {
        lens.layoutRowsForTest(ROW)
        return lens.navRowsForTest()
    }

    private fun press(keyCode: Int) {
        val window = requireNotNull(lens.rootViewForTest())
        window.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        window.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    private fun words(): List<String> = lens.shownRowsForTest().filterIsInstance<DefRow.Word>().map { it.data.word }

    @Test fun `every section is a row with its own word, the tapped unit's first, and the pill steps aside`() {
        assertTrue("the magnifier and the loading lens name the word in the pill", run {
            lens.setLoading("弾ける", "はじける")
            lens.pillShownForTest()
        })
        lens.setSplitDefinitions(primary, listOf(sec1, sec2), false)
        assertEquals(listOf("弾ける", "弾く", "弾く"), words())
        assertTrue(lens.shownRowsForTest().all { it is DefRow.Word && it.variant == DefRow.Variant.LENS })
        assertFalse("a row names the word now", lens.pillShownForTest())

        // The longer unit on top: the secondaries first, the tapped unit last.
        lens.setSplitDefinitions(primary, listOf(sec1), true)
        assertEquals(listOf("弾く", "弾ける"), words())

        // A lone word is the same row, alone.
        lens.setDefinitions(primary.data, null)
        assertEquals(listOf("弾ける"), words())
        assertFalse(lens.pillShownForTest())

        // Back to the magnifier: no rows, and the pill returns.
        lens.setDefinitions(null, null)
        assertTrue(words().isEmpty())
        assertTrue(lens.pillShownForTest())
    }

    @Test fun `row shows the count and a tap fires once`() {
        lens.setSplitDefinitions(primary, emptyList(), false, LensLoadMore(3))
        assertEquals("Load more (3)", lens.loadMoreTextForTest())
        assertEquals(DefRow.More::class, lens.shownRowsForTest().last()::class)
        val row = laidOut().last()

        pastDebounce()
        lens.tapRowForTest(row)
        lens.tapRowForTest(row)   // a second delivery of the same gesture, inside the debounce
        assertEquals(1, loadMoreTaps)
    }

    @Test fun `row ignores taps while loading`() {
        lens.setSplitDefinitions(primary, emptyList(), false, LensLoadMore(2))
        val row = laidOut().last()
        pastDebounce()
        lens.tapRowForTest(row)
        assertEquals(1, loadMoreTaps)

        lens.setLoadMoreLoading(true)
        assertEquals("Looking up…", lens.loadMoreTextForTest())
        assertEquals("the same row, still the last target", row, laidOut().last())
        pastDebounce()
        lens.tapRowForTest(row)
        assertEquals("a loading row is inert", 1, loadMoreTaps)

        lens.setLoadMoreLoading(false)
        assertEquals("Load more (2)", lens.loadMoreTextForTest())
        pastDebounce()
        lens.tapRowForTest(row)
        assertEquals(2, loadMoreTaps)
    }

    @Test fun `row is the last nav target`() {
        lens.setSplitDefinitions(primary, emptyList(), false, LensLoadMore(2))
        val nav = laidOut()
        assertEquals("the tapped unit's row, then the row", 2, nav.size)
        assertEquals(2, lens.shownRowsForTest().size)

        // A primary with nothing to open leaves the row as the only target.
        lens.setSplitDefinitions(
            section("弾ける", "はじける", opens = false), emptyList(), false, LensLoadMore(2),
        )
        assertEquals(listOf(nav.last()), laidOut())
    }

    @Test fun `rebind without the row removes it`() {
        lens.setSplitDefinitions(primary, emptyList(), false, LensLoadMore(2))
        val row = laidOut().last()

        lens.setSplitDefinitions(primary, listOf(sec1, sec2), false)
        assertNull(lens.loadMoreTextForTest())
        val nav = laidOut()
        assertEquals(3, nav.size)
        assertFalse(row in nav)

        // Without a row the loading flip is a no-op.
        lens.setLoadMoreLoading(true)
        assertNull(lens.loadMoreTextForTest())
        assertEquals(3, lens.shownRowsForTest().size)
    }

    @Test fun `a controller cursor starts on the first row and on the row lands on the target in its slot when a rebind drops it`() {
        lens.setSplitDefinitions(primary, emptyList(), false, LensLoadMore(2))
        val first = laidOut()
        lens.focusForController()
        assertEquals("the pill is hidden: the cursor starts on the word's row", first[0], lens.cursorRowForTest())
        press(KeyEvent.KEYCODE_DPAD_DOWN)   // the word's row to the "Load more" row
        assertEquals(first[1], lens.cursorRowForTest())

        // No reveal: the section that took the row's slot, the first loaded.
        lens.setSplitDefinitions(primary, listOf(sec1, sec2), false)
        val nav = laidOut()
        assertEquals(nav[1], lens.cursorRowForTest())
    }

    /** Twelve secondaries: a body taller than the grown card, so it scrolls. */
    private val tall = (1..12).map { section("語$it", "ご$it") }

    /** Runs the rebind's layout pass and the grow animation to their end. */
    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

    /** The body's scroll viewport. */
    private fun viewport(): ScrollView {
        fun find(v: View): ScrollView? = when (v) {
            is ScrollView -> v
            is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) }
            else -> null
        }
        return requireNotNull(find(requireNotNull(lens.rootViewForTest())))
    }

    /** Binds [tall] under the primary, lets it lay out, and scrolls the body
     *  to [y], checking the body really scrolled there. */
    private fun tallBodyScrolledTo(y: Int) {
        lens.setSplitDefinitions(primary, tall, false)
        laidOut()
        settle()
        lens.scrollToForTest(y)
        assertEquals("precondition: the body scrolls", y, lens.scrollYForTest())
    }

    @Test fun `a Keep rebind keeps the offset the body had`() {
        tallBodyScrolledTo(SCROLLED)
        lens.setSplitDefinitions(primary, tall, false, scroll = LensScroll.Keep)
        laidOut()
        settle()
        assertEquals(SCROLLED, lens.scrollYForTest())
    }

    @Test fun `a Reveal rebind lands on that secondary's row, through the grow`() {
        // The load's shape: the primary and the row, then the loaded
        // sections, taller than the card, which grows in the same rebind.
        lens.setSplitDefinitions(primary, emptyList(), false, LensLoadMore(tall.size))
        // Two short rows: the card fits them without growing to its limit.
        lens.layoutRowsForTest(20)
        settle()
        val heightBefore = viewport().height
        lens.setSplitDefinitions(primary, tall, false, scroll = LensScroll.Reveal(1))
        val nav = laidOut()
        settle()
        assertTrue("precondition: the card grew", viewport().height > heightBefore)
        // The primary on top: the primary's row, then the secondaries'.
        val top = requireNotNull(lens.rowTopForTest(nav[2]))
        assertTrue(top > 0)
        assertEquals(top, lens.scrollYForTest())
    }

    @Test fun `a Top rebind is at the top, even over a Keep`() {
        tallBodyScrolledTo(SCROLLED)
        lens.setSplitDefinitions(primary, tall, false, scroll = LensScroll.Keep)
        lens.setSplitDefinitions(primary, tall, false)
        assertEquals(0, lens.scrollYForTest())
        laidOut()
        settle()
        assertEquals(0, lens.scrollYForTest())
    }

    @Test fun `a controller cursor on the row lands on the revealed row`() {
        lens.setSplitDefinitions(primary, emptyList(), false, LensLoadMore(2))
        val first = laidOut()
        lens.focusForController()
        press(KeyEvent.KEYCODE_DPAD_DOWN)   // the word's row to the "Load more" row
        assertEquals(first[1], lens.cursorRowForTest())

        lens.setSplitDefinitions(primary, listOf(sec1, sec2), false, scroll = LensScroll.Reveal(1))
        val nav = laidOut()
        assertEquals("the cursor is on the revealed row", nav[2], lens.cursorRowForTest())
    }

    @Test fun `a controller cursor on a row keeps its place through a rebind`() {
        // The deck back-fill binds the same sections again.
        lens.setSplitDefinitions(primary, listOf(sec1, sec2), false)
        val before = laidOut()
        lens.focusForController()
        press(KeyEvent.KEYCODE_DPAD_DOWN)   // the word's row to the first secondary
        assertEquals(before[1], lens.cursorRowForTest())

        lens.setSplitDefinitions(primary, listOf(sec1, sec2), false, scroll = LensScroll.Keep)
        assertEquals("the same rows, so the same ids", before, laidOut())
        assertEquals(before[1], lens.cursorRowForTest())
    }

    @Test fun `a controller cursor on a row lands on the revealed row when the load row is tapped by touch`() {
        lens.setSplitDefinitions(primary, listOf(sec1), false, LensLoadMore(1))
        val first = laidOut()
        lens.focusForController()
        press(KeyEvent.KEYCODE_DPAD_DOWN)   // the word's row to the secondary
        assertEquals(first[1], lens.cursorRowForTest())

        // The row activated by touch, not by the cursor: the host rebinds
        // with the loaded section revealed.
        lens.setSplitDefinitions(primary, listOf(sec1, sec2), false, scroll = LensScroll.Reveal(1))
        val nav = laidOut()
        assertEquals("the cursor is on the revealed row", nav[2], lens.cursorRowForTest())
    }

    private companion object {
        const val SCROLLED = 37

        /** A row's height in the test's layout reports: tall enough that a
         *  handful of rows overflow the grown card. */
        const val ROW = 300
    }
}
