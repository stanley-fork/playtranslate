package com.playtranslate.ask

import android.content.Context
import com.playtranslate.Prefs
import com.playtranslate.R

/**
 * One ready-made question the Ask section offers about a sentence: a chip
 * to tap, and the prompt it sends.
 */
data class AskPreset(
    /** Stable identity. The recency order ([AskPresets]) is keyed by it and
     *  nothing else is; never shown. */
    val id: String,
    /** The chip's text, and the title over the answer. */
    val title: String,
    /** The user message, as a template ([AskPrompts]' tokens). */
    val prompt: String,
)

/**
 * The ONE source of the presets and of their order. Everything that shows
 * presets (the Ask card's chips, the collapsed header's) asks [ordered], so
 * where a preset comes from is this object's business alone: the built-in
 * ones today, and a store of the user's own could join them here without a
 * reader changing.
 *
 * Order: most recently asked first ([Prefs.askPresetRecency], ids), then
 * every preset never asked, in its declared order.
 */
object AskPresets {

    const val GRAMMAR_BREAKDOWN = "grammar_breakdown"

    /** Every preset, most recently asked first. */
    fun ordered(ctx: Context, prefs: Prefs): List<AskPreset> =
        order(builtIn(ctx), prefs.askPresetRecency)

    /** [all] with the ones named in [recency] first, in its order. An id
     *  that names no preset (one that has since gone) is skipped. */
    internal fun order(all: List<AskPreset>, recency: List<String>): List<AskPreset> {
        val byId = all.associateBy { it.id }
        val recent = recency.mapNotNull { byId[it] }.distinct()
        return recent + all.filter { it !in recent }
    }

    /** [preset] was just asked: it leads the order from now on. */
    fun markAsked(prefs: Prefs, preset: AskPreset) {
        prefs.askPresetRecency = listOf(preset.id) + prefs.askPresetRecency.filter { it != preset.id }
    }

    private fun builtIn(ctx: Context): List<AskPreset> = listOf(
        AskPreset(
            GRAMMAR_BREAKDOWN,
            ctx.getString(R.string.ask_preset_grammar_breakdown),
            AskPrompts.GRAMMAR_BREAKDOWN,
        ),
    )
}
