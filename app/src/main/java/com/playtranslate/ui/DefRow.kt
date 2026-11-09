package com.playtranslate.ui

/**
 * One row of a definitions document ([DefinitionDocument]): what a surface
 * maps its own data to, and the only thing the HTML renderer ([DefRowHtml])
 * reads. Immutable and compared by value, which is what lets a rebind patch
 * only the rows that changed.
 *
 * [id] names the row across rebinds of one document: the same word keeps the
 * same id, so a hide, a deck badge or a spinner is a patch of that row and
 * never a new list.
 */
internal sealed class DefRow {
    abstract val id: String

    /**
     * A word with its definitions.
     *
     * [opens] draws the chevron and makes the row itself a tap target
     * ([DefAction.OPEN]). [speak] and [trailing] are the row's two buttons,
     * null for none. A [Trailing.SHOW] row is a hidden word's stub: the word
     * alone, muted, on the sunk band, with the eye that shows it again; its
     * reading, its definitions and its speak button are not drawn.
     */
    data class Word(
        override val id: String,
        val variant: Variant,
        val data: WordDefinitionData,
        val opens: Boolean = true,
        val speak: Speak? = null,
        val trailing: Trailing? = null,
        /** A warning line over the definitions (the lens's "machine
         *  translated"); [Variant.LENS] only. */
        val label: String? = null,
        /** What stands in for the definitions when the word has none (the
         *  lens keeps a word with no entry on screen); [Variant.LENS] only. */
        val emptyText: String? = null,
    ) : DefRow()

    /**
     * A one-line action at the end of a list: the lens's "Load more (n)",
     * or "Looking up…" while its sections load. A tap fires
     * [DefAction.MORE]; what it does, and whether it does anything just
     * now, is the host's to say.
     */
    data class More(override val id: String, val text: String) : DefRow()

    /**
     * The imported dictionaries' definitions of a word's own page: one
     * labelled block per dictionary, in full, with live links. Under a
     * [curation] (the Anki word sheet) the definitions taken off the card
     * are left out and the rest carry a ✕.
     */
    data class Imported(
        override val id: String,
        val groups: List<com.playtranslate.model.ImportedSenseGroup>,
        val look: SenseLook = SenseLook.PAGE,
        val curation: Curation? = null,
    ) : DefRow()

    /**
     * Per-card removal inside an imported block. [removed] holds the
     * definitions already taken off, each named by its flat index across
     * the block's groups in order: the index it had before any removal,
     * which is what its ✕ reports ([DefAction.removeItem]), so a tap that
     * lands late still names the definition it was drawn on. [removable]
     * says whether the ones left offer a ✕ at all.
     */
    data class Curation(val removed: Set<Int>, val removable: Boolean)

    /**
     * One sense of a word's own page: its [number] in the left column (null
     * when the word has a single sense), the part-of-speech eyebrow ([pos],
     * as it should read, blank for none; [posColor] overrides its colour),
     * the [gloss], the register note ([misc]) and its [examples].
     * [removable] draws the ✕ that takes the sense off a card
     * ([DefAction.REMOVE]).
     */
    data class Sense(
        override val id: String,
        val number: Int?,
        val pos: String,
        val gloss: String,
        val misc: String? = null,
        val examples: List<Example> = emptyList(),
        val posColor: Int? = null,
        val look: SenseLook = SenseLook.PAGE,
        val removable: Boolean = false,
    ) : DefRow()

    /** An example sentence under a sense: the source line and, once it is
     *  known, its translation ([italic] where the target's script has an
     *  italic that reads as one). A [removeIndex] draws the example's ✕,
     *  which reports that index ([DefAction.removeItem]): the example's
     *  place among its sense's examples before any was removed. */
    data class Example(
        val text: String,
        val translation: String,
        val italic: Boolean,
        val removeIndex: Int? = null,
    )

    /**
     * A word of the Anki sentence sheet's list. A tap on the row makes the
     * word the card's target, or takes that back ([DefAction.TARGET]); its
     * eye hides the word everywhere, and on a [hidden] word's stub shows it
     * again ([DefAction.TRAILING]). A [target] draws the accent wash and
     * carries its [audio] row. The body is the imported dictionaries'
     * definitions ([importedGroups]) when they hold structured content, else
     * the first line of [meaning]. A stub is the word alone on the sunk
     * band, inert but for its eye.
     */
    data class SheetWord(
        override val id: String,
        val word: String,
        val reading: String,
        val pitch: List<Int>,
        /** The frequency stars as they read, blank for none. */
        val stars: String,
        val target: Boolean,
        val hidden: Boolean,
        val meaning: String,
        val importedGroups: List<com.playtranslate.model.ImportedSenseGroup>,
        val audio: Audio? = null,
    ) : DefRow()

    /**
     * A target word's audio row: whether its audio goes on the card ([on],
     * the row's switch, [DefAction.AUDIO]), where its preview stands
     * ([play], [DefAction.SPEAK]) and the audio source's name on the pill
     * that opens the picker ([source], blank until it is known;
     * [DefAction.SOURCE]).
     */
    data class Audio(val on: Boolean, val play: Play, val source: String)

    enum class Play { IDLE, LOADING, PLAYING }

    /** How a sense reads. [PAGE] is a word's own page. [SHEET] is the Anki
     *  word sheet's card, a notch denser: a smaller gloss, no minimum row
     *  height, examples in full ink, dictionary names uncoloured. */
    enum class SenseLook { PAGE, SHEET }

    /** The row's look. [CELL] is the word-list row: a large headword, the
     *  buttons in the page's action columns, imported definitions clamped to
     *  a preview. [LENS] is a section of the lens's card: a compact header
     *  (word, reading, chevron) over the full definitions, with the
     *  conjugation lines and live links, and no buttons of its own. */
    enum class Variant { CELL, LENS }

    enum class Speak { IDLE, LOADING }

    /** The second button: add to Anki, hide the word (shown on a full row),
     *  or show it again (on a stub). */
    enum class Trailing { ANKI, HIDE, SHOW }
}

/** What a tap in a document row asked for, as the page reports it. */
internal object DefAction {
    const val OPEN = "open"
    const val SPEAK = "speak"
    const val TRAILING = "trailing"
    const val MORE = "more"

    /** A sheet word's row: toggle it as the card's target. */
    const val TARGET = "target"

    /** A target's audio row: its switch, and the pill that picks its source. */
    const val AUDIO = "audio"
    const val SOURCE = "source"

    /** The ✕ of a row: take the row itself off the card. */
    const val REMOVE = "remove"

    /** The ✕ of item [n] inside a row (an imported definition, an example). */
    fun removeItem(n: Int): String = "$REMOVE:$n"

    /** The item a [removeItem] action names; null for any other action. */
    fun removedItem(action: String): Int? =
        if (action.startsWith("$REMOVE:")) action.substring(REMOVE.length + 1).toIntOrNull() else null
}
