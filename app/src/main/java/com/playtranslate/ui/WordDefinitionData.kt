package com.playtranslate.ui

import com.playtranslate.language.InflectedForm
import com.playtranslate.model.FrequencyTag
import com.playtranslate.model.ImportedSenseGroup
import com.playtranslate.model.ReadingRow

/**
 * A word's dictionary payload as a definitions document draws it: the meta
 * row (Common / frequency / part-of-speech / Anki deck) and the numbered
 * senses. Shared by the magnifying lens ([MagnifierLens]) and the word
 * lists, whose rows ([DefRowHtml]) render it through one renderer.
 *
 * Promoted out of `MagnifierLens.LensDefinitionData` (where it lived as a
 * nested type) so neither surface depends on the other.
 */
data class WordDefinitionData(
    val word: String,
    val reading: String?,
    val senses: List<SenseDisplay>,
    val freqScore: Int,
    val isCommon: Boolean,
    /** Names of Anki decks already containing this word; renders a passive
     *  deck pill in the meta row when non-empty. */
    val ankiDecks: List<String> = emptyList(),
    /** Pitch-accent downstep variants for [reading], empty when unknown.
     *  The word-list row draws the contour over its reading when non-empty. */
    val pitch: List<Int> = emptyList(),
    /** Per-dictionary frequency chips from imported Yomitan frequency
     *  dictionaries, in the user's section order. */
    val frequencies: List<FrequencyTag> = emptyList(),
    /** Every reading in common-use order (shared ordering with the word detail
     *  page), occurrence flagged. The word-list row lists these below the title
     *  when there's more than one or the inline reading won't fit. */
    val readingRows: List<ReadingRow> = emptyList(),
    /** The imported groups BEHIND the flattened imported rows in [senses] —
     *  same data, structured form, which is what a definitions document
     *  draws ([DefRowHtml]). In-process
     *  only: [WordDefinitionData] never rides an intent. */
    val importedGroups: List<ImportedSenseGroup> = emptyList(),
    /** The dictionary store's language key [importedGroups] were looked up
     *  under ([com.playtranslate.language.SourceLangId.yomitanConsumingLang]).
     *  A definitions document fetches the groups' structured content with
     *  it when it draws them, so it is the lookup's own language and never
     *  a later read of the settings. Blank when there are no groups. */
    val definitionLang: String = "",
    /** The conjugation lines for this word, one per distinct inflected form
     *  ([InflectionChain.format]): every form the lemma took in the passage
     *  on the results page's cell, the tapped or dragged form in the lens.
     *  Empty for an uninflected word, for the lens's phrase and member
     *  sections (lemmas), and for engines without inflection analysis. */
    val inflectedForms: List<InflectedForm> = emptyList(),
)

/** A single rendered sense: its part(s) of speech (whole English tokens) and
 *  the gloss text. Renderers localize [pos] via `Context.localizePos`, except
 *  when [imported] is set.
 *  [imported] marks rows from imported Yomitan term dictionaries (whose [pos]
 *  is a single display header carrying the dictionary name, not POS tags — so
 *  it is rendered verbatim, never localized). Monolingual definitions run
 *  paragraph-length, which is why a word row lets each dictionary be shut
 *  ([DefRow.Word.shutDictionaries]). */
data class SenseDisplay(
    val pos: List<String>,
    val definition: String,
    /** Curated misc register tags, raw as stored in the pack. The render layer
     *  ([com.playtranslate.ui.renderMisc]) is the cleanliness authority — it
     *  localizes / passes through / drops. REQUIRED (no default) so every
     *  construction site decides explicitly; imported rows pass emptyList(). */
    val misc: List<String>,
    val imported: Boolean = false,
    /** Per-dictionary accent override (ARGB) for an imported row's title;
     *  null = the default muted header. */
    val accentColor: Int? = null,
    /** `term_sc` rowid when this imported row's source entry retained a
     *  structured glossary — rides the enrichment transport so the Anki
     *  SENTENCE pipeline (which only ever sees flattened rows) can fetch
     *  structured card HTML at send time. Null everywhere else; flat
     *  renderers ignore it. */
    val scRowid: Long? = null,
    /** Owning dictionary id for [scRowid] rows (the card markup's
     *  data-dictionary + media key). */
    val dictId: String? = null,
    // Serializable so senses can ride [WordEnrichment] through the sentence
    // Anki review's intent/args snapshots (same transport as pitch/frequencies).
) : java.io.Serializable
