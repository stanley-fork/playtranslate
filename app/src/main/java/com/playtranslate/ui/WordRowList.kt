package com.playtranslate.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.annotation.VisibleForTesting
import com.playtranslate.AnkiManager
import com.playtranslate.language.SourceLangId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A list of looked-up words drawn as ONE definitions document: what the
 * results page's Words card, the dictionary search and the word page's
 * "Words" and "Other matches" sections share. Each [RowState] is a
 * [DefRow.Word] of the list row look, with a speak button, the host's
 * second button, and a tap that opens the word.
 *
 * It keeps the state a row is drawn from beyond the row itself (the deck
 * cache, which rows are speaking) and [publish] maps it all to rows. Every
 * change ends in [publish], and the document patches only the rows whose
 * value changed, so a deck badge, a spinner or the host's own change (a
 * word hidden) never rebuilds the list.
 *
 * The list holds a page only while it has rows and is [shown]: a hidden or
 * empty list neither loads the WebView nor keeps its render process.
 */
internal class WordRowList(
    private val ctx: Context,
    slot: ViewGroup,
    private val scope: CoroutineScope,
    private val host: Host,
    /** The rows' text scale: every size in a row is a fraction of the gloss
     *  size this gives. */
    scale: Float,
) {
    interface Host {
        /** The host view tree is still live; guards every async re-entry. */
        val isAlive: Boolean

        /** The language the rows' words are in (speech, the hidden set). */
        val sourceLang: SourceLangId

        /** Whether rows carry the "already in Anki" deck pill. */
        val deckBadges: Boolean

        /** [row]'s second button as it should draw now; null for none. */
        fun trailing(row: RowState): DefRow.Trailing?

        /** The row itself was tapped: open the word. */
        fun onOpen(row: RowState)

        /** The second button was tapped while it showed [shown], which is
         *  what the user acted on. */
        fun onTrailing(row: RowState, shown: DefRow.Trailing)

        /** Speak [row]'s word and return when it is done; the row draws its
         *  spinner for as long as this runs. */
        suspend fun speak(row: RowState)

        /** Any tap in the list (live-mode hosts pause on it). */
        fun onInteraction() {}
    }

    private val document = DefinitionDocument(
        ctx, slot, scope,
        theme = { cardDocTheme(ctx, GLOSS_SP * scale) },
        onAction = ::onRowAction,
    )

    private var rows: List<RowState> = emptyList()
    private var ids: List<String> = emptyList()
    private var lang: String = ""

    /** word to its Anki decks, as far as they have been asked for. */
    private val decksByWord = HashMap<String, List<String>>()

    /** Row ids with a speak in flight (the row draws its spinner), and the
     *  jobs, so a second tap on a speaking row does nothing. */
    private val speaking = HashSet<String>()
    private val speakJobs = HashMap<String, Job>()

    private var released = false

    /** Whether the list is on screen. A list that is not holds no page; it
     *  draws the rows it has when it is shown again. */
    var shown: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            if (value) publish() else document.release()
        }

    /** The rows as given, in their drawn order. */
    val wordRows: List<RowState> get() = rows

    val isEmpty: Boolean get() = rows.isEmpty()

    /** The user is about to see rows here (their lookup is running): start
     *  loading the page now. A no-op for a list that is not shown. Never
     *  call it inside a page's entrance animation: the first page of a
     *  process blocks the main thread while the WebView loads. */
    fun prepare() {
        if (!released && shown) document.prepare()
    }

    /**
     * The words to list, in drawn order, looked up under [definitionLang]
     * (the dictionary store's language key; the document fetches each row's
     * structured content with it, so it must be the lookup's own). An empty
     * list lets the page go. Decks not yet known are asked for.
     */
    fun setRows(newRows: List<RowState>, definitionLang: String) {
        cancelSpeaking()
        rows = newRows
        ids = rowIdsOf(newRows)
        lang = definitionLang
        if (newRows.isEmpty()) {
            // Nothing was found: the page prepared for the rows is let go,
            // not kept waiting.
            document.release()
            return
        }
        publish()
        loadDeckBadges()
    }

    /** No rows, the page kept for the ones a running lookup will bring. */
    fun clear() {
        cancelSpeaking()
        rows = emptyList()
        ids = emptyList()
        publish()
    }

    /**
     * The rows as the document should show them now. The host calls it when
     * something its [Host.trailing] reads has changed; everything in here
     * that changes a row calls it too.
     */
    fun publish() {
        if (released || !shown) return
        document.setRows(
            rows.mapIndexed { i, row ->
                DefRow.Word(
                    id = ids[i],
                    variant = DefRow.Variant.CELL,
                    data = WordDefinitionData(
                        word = row.displayWord,
                        reading = row.reading.ifEmpty { null },
                        senses = row.senses,
                        freqScore = row.freqScore,
                        isCommon = row.isCommon,
                        ankiDecks = decksByWord[row.displayWord].orEmpty(),
                        pitch = row.pitch,
                        frequencies = row.frequencies,
                        readingRows = row.readingRows,
                        importedGroups = row.importedGroups,
                        inflectedForms = row.inflectedForms,
                    ),
                    opens = true,
                    speak = if (ids[i] in speaking) DefRow.Speak.LOADING else DefRow.Speak.IDLE,
                    trailing = host.trailing(row),
                )
            },
            lang,
        )
    }

    /** Terminal: the page and its shell go, nothing draws afterwards. */
    fun release() {
        released = true
        cancelSpeaking()
        document.release()
    }

    private fun cancelSpeaking() {
        speakJobs.values.forEach { it.cancel() }
        speakJobs.clear()
        speaking.clear()
    }

    private fun onRowAction(row: DefRow, action: String) {
        val state = rows.getOrNull(ids.indexOf(row.id)) ?: return
        host.onInteraction()
        when (action) {
            DefAction.OPEN -> host.onOpen(state)
            DefAction.SPEAK -> speak(row.id, state)
            DefAction.TRAILING -> (row as? DefRow.Word)?.trailing?.let { host.onTrailing(state, it) }
        }
    }

    /** Each row has its own job, so taps on different rows don't clobber
     *  one another and a second tap on a speaking row does nothing. */
    private fun speak(id: String, row: RowState) {
        if (speakJobs[id]?.isActive == true) return
        speakJobs[id] = scope.launch {
            speaking += id
            publish()
            try {
                host.speak(row)
            } finally {
                // Only while this is still the row's job: a new list cancels
                // the old jobs and starts with nothing speaking.
                if (speakJobs[id] === coroutineContext[Job]) {
                    speakJobs.remove(id)
                    speaking -= id
                    publish()
                }
            }
        }
    }

    // ── Anki deck badges ─────────────────────────────────────────────────

    /** The decks [word] is known to be in; null when not asked yet. */
    fun cachedDecks(word: String): List<String>? = decksByWord[word]

    /** One word's decks, asked for now and kept for the list; null when
     *  AnkiDroid is absent or not permitted. */
    suspend fun loadDecks(word: String): List<String>? {
        val anki = AnkiManager(ctx)
        if (!anki.isAnkiDroidInstalled() || !anki.hasPermission()) return null
        val decks = withContext(Dispatchers.IO) { anki.decksByWord(listOf(word))[word].orEmpty() }
        decksByWord[word] = decks
        return decks
    }

    /** Asks again for every listed word's decks (a card was added, the
     *  page came back) and redraws the rows whose pill changed. */
    fun refreshDeckBadges() {
        if (!host.deckBadges || rows.isEmpty()) return
        decksByWord.clear()
        val anki = AnkiManager(ctx)
        if (!anki.isAnkiDroidInstalled() || !anki.hasPermission()) {
            publish()
            return
        }
        val words = rows.map { it.displayWord }.distinct()
        scope.launch {
            val result = withContext(Dispatchers.IO) { anki.decksByWord(words) }
            if (!host.isAlive) return@launch
            for (word in words) decksByWord[word] = result[word].orEmpty()
            publish()
        }
    }

    /** Batched "already in Anki" lookup for the words not asked yet.
     *  Gated and silent. */
    private fun loadDeckBadges() {
        if (!host.deckBadges) return
        val anki = AnkiManager(ctx)
        if (!anki.isAnkiDroidInstalled() || !anki.hasPermission()) return
        val uncached = rows.map { it.displayWord }.distinct().filter { it !in decksByWord }
        if (uncached.isEmpty()) return
        scope.launch {
            val result = withContext(Dispatchers.IO) { anki.decksByWord(uncached) }
            if (!host.isAlive) return@launch
            for (w in uncached) decksByWord[w] = result[w].orEmpty()
            publish()
        }
    }

    // ── What the page shows, for its host ────────────────────────────────

    /** The document's view once the list has drawn, for a host that anchors
     *  its scroll on a row ([rowAt], [rowTop]). */
    val view: View? get() = document.hostView

    /** The id of the row at [y] ([view]'s own px), else of the first row
     *  below it; null when no row is laid out. */
    fun rowAt(y: Int): String? = document.rowAt(y)

    /** The top of row [id] in [view]'s own px; null when it is gone. */
    fun rowTop(id: String): Int? = document.rowTopPx(id)

    /** The rows as last published, in drawn order. */
    @get:VisibleForTesting
    val shownRows: List<DefRow.Word> get() = document.shownRows.filterIsInstance<DefRow.Word>()

    /** A tap the page would report on the row at [index]. */
    @VisibleForTesting
    fun tapForTest(index: Int, action: String) {
        document.shownRows.getOrNull(index)?.let { onRowAction(it, action) }
    }

    companion object {
        /** A row's gloss size in sp before the list's scale. */
        private const val GLOSS_SP = 16.5f

        /** The scale of a list that is the page's main content (the
         *  dictionary search, a word page's sections). */
        const val LARGE_SCALE = 1.12f

        /** The scale of the results page's Words card: a notch below, so
         *  its rows read denser beside the translation. */
        const val COMPACT_SCALE = 1.0f

        /** A document id per row: the word and its reading, which a rebind
         *  of the same lookup repeats (so it patches), with a counter for a
         *  list that holds the same pair twice. */
        fun rowIdsOf(rows: List<RowState>): List<String> {
            val seen = HashMap<String, Int>()
            return rows.map { row ->
                val key = row.displayWord + "\u0001" + row.reading
                val n = seen.merge(key, 1, Int::plus)!!
                if (n == 1) key else "$key\u0001$n"
            }
        }
    }
}
