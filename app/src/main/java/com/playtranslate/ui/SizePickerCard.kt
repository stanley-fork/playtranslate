package com.playtranslate.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.widget.FrameLayout
import com.playtranslate.R
import com.playtranslate.themeColor

/**
 * The text-size pickers' shared card: the results Text size range picker
 * ([FontSizeRangePopover]) and the Ask section's one-handle picker
 * ([FontSizePopover]) are the same popover but for their handles, and take
 * the card's width, its padding, the slider row and the size printed over a
 * handle from here, so the two cannot drift apart. The track and handles
 * themselves are [SliderTrackPainter]'s.
 */
internal object SizePickerCard {

    private const val CARD_W_DP = 220f
    private const val CARD_V_PAD_DP = 6f

    /** The band above the track that the handles' sizes are printed in. With
     *  the handles' own touch-box band below it, the slider row's height, so
     *  a 48 dp box fits vertically. */
    private const val LABEL_BAND_DP = 18f
    private const val TRACK_ROW_H_DP = LABEL_BAND_DP + SliderTrackPainter.TOUCH_BOX_DP
    private const val LABEL_SP = 13f

    fun cardWidth(ctx: Context): Int = dp(ctx, CARD_W_DP).toInt()

    /** The card's inside: [track], the slider row, across its full width
     *  (no horizontal padding, so an end handle keeps its whole 48 dp box
     *  inside a view that receives touches) under the card's vertical
     *  padding. */
    fun view(ctx: Context, track: View): View = FrameLayout(ctx).apply {
        val padV = dp(ctx, CARD_V_PAD_DP).toInt()
        setPadding(0, padV, 0, padV)
        track.id = R.id.sizePickerTrack
        addView(
            track,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(ctx, TRACK_ROW_H_DP).toInt()),
        )
    }

    /** Where the track's centre line sits in the slider row, below the
     *  label band. */
    fun trackCenterY(ctx: Context, painter: SliderTrackPainter): Float =
        dp(ctx, LABEL_BAND_DP) + painter.touchHalf

    /** The paint a handle's size is printed in. */
    fun labelPaint(ctx: Context): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ctx.themeColor(R.attr.ptText)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, LABEL_SP, ctx.resources.displayMetrics)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    /** Half the widest label (two digits): how far in from either end of
     *  the row a label's centre is kept. */
    fun halfLabel(paint: Paint): Float = paint.measureText("88") / 2f

    /** Print [value] centred on [x] in the label band, pulled inward at the
     *  row's ends so a two-digit size can't run off a row [width] wide. */
    fun drawLabel(canvas: Canvas, paint: Paint, value: Int, x: Float, width: Int) {
        val half = halfLabel(paint)
        canvas.drawText(value.toString(), x.coerceIn(half, width - half), -paint.fontMetrics.top, paint)
    }

    private fun dp(ctx: Context, v: Float) = v * ctx.resources.displayMetrics.density
}
