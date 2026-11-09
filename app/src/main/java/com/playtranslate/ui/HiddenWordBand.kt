package com.playtranslate.ui

import android.content.Context
import androidx.core.graphics.ColorUtils
import com.playtranslate.R
import com.playtranslate.themeColor

/** Alpha of a hidden word's band: the page background at half strength. */
internal const val HIDDEN_WORD_BAND_ALPHA = 128

/**
 * The band behind a hidden word's stub, on every surface that lists words
 * (the results Words card, the Anki sentence sheet): the page background at
 * 50% over the card, so the stub reads as sunk into it.
 */
internal fun hiddenWordBandColor(context: Context): Int =
    ColorUtils.setAlphaComponent(context.themeColor(R.attr.ptBg), HIDDEN_WORD_BAND_ALPHA)
