package com.playtranslate.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Mirrors a [WordLookupsState] onto a results surface, the same way on
 * every surface that shows a looked-up sentence (the results pages through
 * [TranslationResultContent], the over-game capture sheet): the source
 * text's tap spans, and the lens that reads them, follow the lookup
 * synchronously, from the analysis on, rows or not; the Words card draws
 * the rows, and a settled list builds only after the surface's entrance has
 * settled, unless a newer lookup state arrived first.
 */
internal class WordLookupsRenderer(
    private val wordRows: WordRowsBinder,
    private val lens: SourceTextLens,
    /** The source text as DISPLAYED, which the spans project against (it
     *  may carry OCR newlines the result's text doesn't). */
    private val displayedSourceText: () -> String,
    private val scope: () -> CoroutineScope,
    /** The surface's view tree is still live; guards every async re-entry. */
    private val isAlive: () -> Boolean,
    /** Hold point before the Words card's page loads or a settled list is
     *  built: a surface with an entrance transition suspends until it has
     *  settled (the first WebView of a process blocks the main thread while
     *  it loads). */
    private val awaitEnterSettled: suspend () -> Unit,
    /** The tap spans now cover other RANGES of the text, so anything that
     *  pointed at a span by position (a controller cursor on a word) is
     *  stale. Not called when the ranges are the same and only the readings
     *  changed, which is what the rows settling after their analysis does. */
    private val onSpanRangesChanged: () -> Unit = {},
) {
    /** Bumped per render so a build held at the enter-settle gate lands
     *  only if no newer lookup state arrived. */
    private var generation = 0

    fun render(state: WordLookupsState) {
        if (!isAlive()) return
        val generation = ++this.generation
        when (state) {
            is WordLookupsState.Idle -> {
                setSpans(emptyList())
                wordRows.render(state)
            }
            is WordLookupsState.Loading -> {
                lens.dismiss()
                setSpans(emptyList())
                wordRows.render(state)
            }
            is WordLookupsState.Analyzed -> {
                // No resolved readings yet: computeSpans falls back to the
                // token's own lookup hint (TokenSpan.reading), which the lens
                // passes to its resolve as the reading hint; the rows'
                // resolved readings replace it when Settled lands.
                setSpans(
                    SourceWordLookup.computeTapSpans(
                        displayedSourceText(), state.tokenSpans, emptyMap(), state.phrases,
                    ),
                )
                // Drawn like Loading; asks for the rows when the card is shown.
                wordRows.render(state)
                // The rows are being looked up: load their page meanwhile,
                // but never inside the surface's entrance.
                scope().launch {
                    awaitEnterSettled()
                    if (isAlive() && generation == this@WordLookupsRenderer.generation) {
                        wordRows.prepareRows()
                    }
                }
            }
            is WordLookupsState.Settled -> {
                // Spans project against the displayed text (which may carry
                // OCR newlines the result's text doesn't).
                setSpans(
                    SourceWordLookup.computeTapSpans(
                        displayedSourceText(), state.tokenSpans, state.lookupToReading, state.phrases,
                    ),
                )
                // Furigana is NOT applied here: it's driven by the section
                // binder's bindSource, so it paints with the source text
                // (during the Translating placeholder), not after this
                // heavier lookup settles.
                scope().launch {
                    awaitEnterSettled()
                    if (!isAlive() || generation != this@WordLookupsRenderer.generation) return@launch
                    wordRows.render(state)
                }
            }
        }
    }

    private fun setSpans(spans: List<SourceWordLookup.TapSpan>) {
        val moved = spans.map { it.range } != lens.wordSpans.map { it.range }
        lens.wordSpans = spans
        if (moved) onSpanRangesChanged()
    }
}
