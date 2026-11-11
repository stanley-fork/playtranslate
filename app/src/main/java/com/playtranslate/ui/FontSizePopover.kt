package com.playtranslate.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.themeColor
import kotlin.math.abs

/**
 * The one-handle text-size picker: the results Text size picker
 * ([FontSizeRangePopover]) with a single handle, for a text drawn at one
 * size rather than fitted between two. The same card ([SizePickerCard]), the
 * same track, handle and axis ([SliderTrackPainter], [Prefs.FONT_SP_FLOOR] to
 * [Prefs.FONT_SP_CEIL]) and the same size printed over the handle; with one
 * handle the accent runs from the track's start to it. Whole sp only.
 *
 * Edits its size live through [write] and reads it through [read], so the
 * value is never held here. Shown by the surface's [PopoverHost]; the Ask
 * section's header opens it for the answer's text (AskSectionBinder).
 * Built in code for the range picker's reason: the capture overlay's plain
 * inflater drops `app:` attributes.
 */
class FontSizePopover(
    private val read: () -> Int,
    private val write: (Int) -> Unit,
) : PopoverContent {
    /** Invoked after every committed (whole sp) change; the size is already
     *  written. */
    var onSizeChanged: (() -> Unit)? = null

    override fun createView(ctx: Context, host: PopoverHost): View =
        SizePickerCard.view(ctx, TrackView(ctx))

    override fun cardWidth(ctx: Context, natural: Int): Int = SizePickerCard.cardWidth(ctx)

    /**
     * The slider: a grabbed handle moves from where it is, and a touch on
     * bare track jumps it there. Its grab area is a
     * [SliderTrackPainter.TOUCH_BOX_DP] square centred on the painted circle,
     * which the row is sized and the track inset to keep inside this view.
     */
    @SuppressLint("ClickableViewAccessibility")
    private inner class TrackView(c: Context) : View(c) {
        private val painter = SliderTrackPainter(c)
        private val accent = c.themeColor(R.attr.ptAccent)
        private val labelPaint = SizePickerCard.labelPaint(c)
        private val trackCenterY = SizePickerCard.trackCenterY(c, painter)

        /** Handle x − finger x at grab time, so a dragged handle moves from
         *  where it is instead of jumping under the finger. */
        private var grabOffset = 0f

        private fun xFor(value: Int): Float =
            painter.xFor(value, Prefs.FONT_SP_FLOOR, Prefs.FONT_SP_CEIL, width)

        private fun valueFor(x: Float): Int =
            painter.valueFor(x, Prefs.FONT_SP_FLOOR, Prefs.FONT_SP_CEIL, width)

        override fun onDraw(canvas: Canvas) {
            val value = read()
            val x = xFor(value)
            val start = painter.touchHalf
            painter.drawTrack(canvas, start, width - start, trackCenterY)
            painter.drawStretch(canvas, start, x, trackCenterY, accent)
            painter.drawHandle(canvas, x, trackCenterY, accent)
            SizePickerCard.drawLabel(canvas, labelPaint, value, x, width)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // A scrolling page and the sheet's gesture layers would
                    // otherwise claim a drag that wanders vertically.
                    parent?.requestDisallowInterceptTouchEvent(true)
                    val handleX = xFor(read())
                    if (abs(event.x - handleX) <= painter.touchHalf) {
                        grabOffset = handleX - event.x
                    } else {
                        grabOffset = 0f
                        set(valueFor(event.x))
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    set(valueFor(event.x + grabOffset))
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        private fun set(value: Int) {
            if (value == read()) return
            write(value)
            invalidate()
            onSizeChanged?.invoke()
        }
    }
}
