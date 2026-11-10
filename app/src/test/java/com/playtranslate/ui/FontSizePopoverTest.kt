package com.playtranslate.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.View.MeasureSpec
import android.widget.FrameLayout
import com.playtranslate.Prefs
import com.playtranslate.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import kotlin.math.abs

/**
 * [FontSizePopover], the one-handle text-size picker, on its own: a touch on
 * bare track jumps the handle there, a grabbed handle moves from where it is
 * rather than to the finger, only whole sizes are written and reported, the
 * size stays on the results picker's axis at both ends, every size draws, and
 * its card is the range picker's ([FontSizeRangePopover]): the same width,
 * height and slider row. The size lives in the test, read and written through
 * the picker's two functions.
 */
@RunWith(RobolectricTestRunner::class)
class FontSizePopoverTest {

    class Host : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_PlayTranslate)
            super.onCreate(savedInstanceState)
        }
    }

    private lateinit var activity: Host
    private var size = 16
    private var writes = 0
    private var reports = 0
    private val popover = FontSizePopover(read = { size }, write = { size = it; writes++ })
        .apply { onSizeChanged = { reports++ } }
    private lateinit var card: View
    private lateinit var track: View
    private lateinit var painter: SliderTrackPainter

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(Host::class.java).setup().get()
        card = popover.createView(activity, PopoverHost(FrameLayout(activity)))
        lay(card, popover.cardWidth(activity, 0))
        track = card.findViewById(R.id.sizePickerTrack)
        painter = SliderTrackPainter(activity)
    }

    private fun lay(v: View, width: Int) {
        v.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
    }

    private fun xFor(value: Int) = painter.xFor(value, Prefs.FONT_SP_FLOOR, Prefs.FONT_SP_CEIL, track.width)

    private fun send(action: Int, x: Float) {
        val t = SystemClock.uptimeMillis()
        val e = MotionEvent.obtain(t, t, action, x, track.height / 2f, 0)
        track.dispatchTouchEvent(e)
        e.recycle()
    }

    @Test
    fun `a touch on bare track jumps the handle there`() {
        val at = xFor(24)
        assertTrue("precondition: away from the handle", abs(at - xFor(16)) > painter.touchHalf)
        send(MotionEvent.ACTION_DOWN, at)
        assertEquals(24, size)
        assertEquals(1, writes)
        assertEquals(1, reports)
        send(MotionEvent.ACTION_UP, at)
        assertEquals("lifting changes nothing", 1, reports)
    }

    @Test
    fun `a grabbed handle moves from where it is, not to the finger`() {
        // Inside the handle's box, well off its centre.
        val grabAt = xFor(16) + painter.touchHalf * 0.8f
        send(MotionEvent.ACTION_DOWN, grabAt)
        assertEquals("grabbing moves nothing", 16, size)
        assertEquals(0, reports)
        send(MotionEvent.ACTION_MOVE, grabAt + (xFor(20) - xFor(16)))
        assertEquals("moved by the drag, from where it was", 20, size)
        assertEquals(1, reports)
    }

    @Test
    fun `only a whole size is written and reported`() {
        val handle = xFor(16)
        send(MotionEvent.ACTION_DOWN, handle)
        send(MotionEvent.ACTION_MOVE, handle + 1f)
        assertEquals(16, size)
        assertEquals(0, writes)
        assertEquals(0, reports)
    }

    @Test
    fun `the size stays on the axis at both ends`() {
        send(MotionEvent.ACTION_DOWN, track.width + 50f)
        assertEquals(Prefs.FONT_SP_CEIL, size)
        send(MotionEvent.ACTION_MOVE, -50f)
        assertEquals(Prefs.FONT_SP_FLOOR, size)
    }

    @Test
    fun `every size on the axis draws`() {
        val canvas = Canvas(Bitmap.createBitmap(track.width, track.height, Bitmap.Config.ARGB_8888))
        for (value in Prefs.FONT_SP_FLOOR..Prefs.FONT_SP_CEIL) {
            size = value
            track.draw(canvas)
        }
    }

    @Test
    fun `the card is the range picker's, with the same width, height and slider row`() {
        val range = FontSizeRangePopover(activity, Prefs(activity))
        val width = range.cardWidth(activity, 0)
        assertEquals(width, popover.cardWidth(activity, 0))
        val rangeCard = range.createView(activity, PopoverHost(FrameLayout(activity)))
        lay(rangeCard, width)
        assertEquals(rangeCard.measuredHeight, card.measuredHeight)
        val rangeTrack = rangeCard.findViewById<View>(R.id.sizePickerTrack)
        assertEquals(rangeTrack.top, track.top)
        assertEquals(rangeTrack.height, track.height)
    }
}
