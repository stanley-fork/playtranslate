package com.playtranslate.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.annotation.VisibleForTesting
import com.playtranslate.AnkiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A list of looked-up words drawn as ONE definitions document: what the
 * results page's Words card, the dictionary search, the word page's "Words"
 * and "Other matches" sections and the lens all share. Each word of type
 * [T] is the one word row ([DefRow.Word]), with a speak button, the host's
 * second button, and a tap that opens the word; [describe] says what a row
 * shows of its word, and an optional footer is a one-line action under the
 * rows (the lens's "Load more").
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
internal class WordRowList<T>(
    private val ctx: Context,
    slot: ViewGroup,
    private val scope: CoroutineScope,
    private val host: Host<T>,
    /** What a row shows of its word. Read once per [setRows]. */
    private val describe: (T) -> Entry,
    /** The page's theme, read each time a page is taken. */
    theme: () -> DefinitionsDocument.DocTheme,
    /** Whether rows show their definitions in full ([DefRow.Word.full]):
     *  true where the row is where the word is read, false for a list whose
     *  rows tap through to the word's own page. */
    private val full: Boolean = false,
    /** The page laid the rows out. */
    onLayout: () -> Unit = {},
) {
    /** What a row shows of its word. [data]'s Anki decks are the list's to
     *  fill ([Host.deckBadges]); the rest is drawn as given. */
    class Entry(
        val data: WordDefinitionData,
        /** Draws the chevron and makes the row a tap target ([Host.onOpen]). */
        val opens: Boolean = true,
        /** A warning line over the definitions ("Machine translated"). */
        val label: String? = null,
        /** What stands in for the definitions when the word has none. */
        val emptyText: String? = null,
    )

    interface Host<T> {
        /** The host view tree is still live; guards every async re-entry. */
        val isAlive: Boolean

        /** Whether rows carry the "already in Anki" deck pill. */
        val deckBadges: Boolean

        /** [row]'s second button as it should draw now; null for none. */
        fun trailing(row: T): DefRow.Trailing?

        /** Whether a press held on [row]'s second button is an action of
         *  its own ([onTrailingHold]) and not a tap. */
        fun trailingHolds(row: T): Boolean = false

        /** The row itself was tapped: open the word. */
        fun onOpen(row: T)

        /** The second button was tapped while it showed [shown], which is
         *  what the user acted on. */
        fun onTrailing(row: T, shown: DefRow.Trailing)

        /** The second button was held ([trailingHolds]). */
        fun onTrailingHold(row: T) {}

        /** Speak [row]'s word and return when it is done; the row draws its
         *  spinner for as long as this runs. */
        suspend fun speak(row: T)

        /** The footer was tapped. */
        fun onFooter() {}

        /** Any tap in the list (live-mode hosts pause on it). */
        fun onInteraction() {}
    }

    private val document = DefinitionDocument(
        ctx, slot, scope,
        theme = theme,
        onAction = ::onRowAction,
        onLayout = onLayout,
    )

    private var rows: List<T> = emptyList()
    private var entries: List<Entry> = emptyList()
    private var ids: List<String> = emptyList()
    private var footer: String? = null
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
    val wordRows: List<T> get() = rows

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
     * structured content with it, so it must be the lookup's own), and the
     * [footer] under them, null for none. An empty list with no footer lets
     * the page go. Decks not yet known are asked for.
     */
    fun setRows(newRows: List<T>, definitionLang: String, footer: String? = null) {
        cancelSpeaking()
        rows = newRows
        entries = newRows.map(describe)
        ids = rowIdsOf(entries)
        this.footer = footer
        lang = definitionLang
        publish()
        loadDeckBadges()
    }

    /** The footer's text alone (its loading state), the rows as they are. */
    fun setFooter(text: String?) {
        if (footer == text) return
        footer = text
        publish()
    }

    /** No rows, the page kept for the ones a running lookup will bring. */
    fun clear() {
        cancelSpeaking()
        rows = emptyList()
        entries = emptyList()
        ids = emptyList()
        footer = null
        publish()
    }

    /**
     * The rows as the document should show them now. The host calls it when
     * something its [Host.trailing] reads has changed; everything in here
     * that changes a row calls it too.
     */
    fun publish() {
        if (released || !shown) return
        val words: List<DefRow> = rows.mapIndexed { i, row ->
            val entry = entries[i]
            DefRow.Word(
                id = ids[i],
                data = entry.data.copy(ankiDecks = decksByWord[entry.data.word].orEmpty()),
                opens = entry.opens,
                speak = if (ids[i] in speaking) DefRow.Speak.LOADING else DefRow.Speak.IDLE,
                trailing = host.trailing(row),
                trailingHolds = host.trailingHolds(row),
                full = full,
                label = entry.label,
                emptyText = entry.emptyText,
            )
        }
        document.setRows(words + listOfNotNull(footer?.let { DefRow.More(FOOTER_ID, it) }), lang)
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
        if (row is DefRow.More) {
            host.onInteraction()
            host.onFooter()
            return
        }
        val state = rows.getOrNull(ids.indexOf(row.id)) ?: return
        host.onInteraction()
        when (action) {
            DefAction.OPEN -> host.onOpen(state)
            DefAction.SPEAK -> speak(row.id, state)
            DefAction.TRAILING -> (row as? DefRow.Word)?.trailing?.let { host.onTrailing(state, it) }
            DefAction.hold(DefAction.TRAILING) -> host.onTrailingHold(state)
        }
    }

    /** Each row has its own job, so taps on different rows don't clobber
     *  one another and a second tap on a speaking row does nothing. */
    private fun speak(id: String, row: T) {
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
        val words = entries.map { it.data.word }.distinct()
        scope.launch {
            val result = try {
                withContext(Dispatchers.IO) { anki.decksByWord(words) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                return@launch
            }
            if (!host.isAlive) return@launch
            for (word in words) decksByWord[word] = result[word].orEmpty()
            publish()
        }
    }

    /** Batched "already in Anki" lookup for the words not asked yet, after
     *  the rows are up so the query never delays them. Gated and silent. */
    private fun loadDeckBadges() {
        if (!host.deckBadges) return
        val anki = AnkiManager(ctx)
        if (!anki.isAnkiDroidInstalled() || !anki.hasPermission()) return
        val uncached = entries.map { it.data.word }.distinct().filter { it !in decksByWord }
        if (uncached.isEmpty()) return
        scope.launch {
            val result = try {
                withContext(Dispatchers.IO) { anki.decksByWord(uncached) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // An Anki failure costs the badge, never the rows.
                return@launch
            }
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

    /** The id of the word at [index] in the list as last set. */
    fun idAt(index: Int): String? = ids.getOrNull(index)

    /** Every row and tappable element the page has laid out, in page
     *  order, for a host that walks them with its own cursor (the lens). */
    val targets: List<DocTarget> get() = document.targets

    /** Draws the controller's highlight on [target], or clears it (null). */
    fun setCursor(target: DocTarget?) = document.setCursor(target)

    /** What a tap on [act] of row [rowId] does (a controller's press). */
    fun activate(rowId: String, act: String) = document.activate(rowId, act)

    /** True when the notice stands where the rows would be (no usable
     *  WebView): no layout report will come. */
    val showsNotice: Boolean get() = document.showsNotice

    /** The word rows as last published, in drawn order. */
    @get:VisibleForTesting
    val shownRows: List<DefRow.Word> get() = document.shownRows.filterIsInstance<DefRow.Word>()

    /** Every row as last published, the footer included. */
    @get:VisibleForTesting
    val shownDocRows: List<DefRow> get() = document.shownRows

    /** A tap the page would report on the row at [index]. */
    @VisibleForTesting
    fun tapForTest(index: Int, action: String) {
        document.shownRows.getOrNull(index)?.let { onRowAction(it, action) }
    }

    /** Stands in for the page under a JVM test ([DefinitionDocument.layoutForTest]). */
    @VisibleForTesting
    fun layoutForTest(rowHeightCssPx: Int) = document.layoutForTest(rowHeightCssPx)

    companion object {
        /** A row's gloss size in sp before a list's scale. */
        const val GLOSS_SP = 16.5f

        /** The scale of a list that is the page's main content (the
         *  dictionary search, a word page's sections). */
        const val LARGE_SCALE = 1.12f

        /** The scale of the results page's Words card: a notch below, so
         *  its rows read denser beside the translation. */
        const val COMPACT_SCALE = 1.0f

        /** The footer's row id. */
        const val FOOTER_ID = "footer"

        /** A document id per row: the word and its reading, which a rebind
         *  of the same lookup repeats (so it patches), with a counter for a
         *  list that holds the same pair twice. */
        fun rowIdsOf(entries: List<Entry>): List<String> {
            val seen = HashMap<String, Int>()
            return entries.map { e ->
                val key = e.data.word + "\u0001" + e.data.reading.orEmpty()
                val n = seen.merge(key, 1, Int::plus)!!
                if (n == 1) key else "$key\u0001$n"
            }
        }
    }
}

/** A looked-up word of the app's word lists as its row shows it. */
internal fun RowState.toWordEntry() = WordRowList.Entry(
    WordDefinitionData(
        word = displayWord,
        reading = reading.ifEmpty { null },
        senses = senses,
        freqScore = freqScore,
        isCommon = isCommon,
        pitch = pitch,
        frequencies = frequencies,
        readingRows = readingRows,
        importedGroups = importedGroups,
        inflectedForms = inflectedForms,
    ),
)

/** A word list of the app's own looked-up rows in one of its cards, its
 *  text sized by [scale] ([WordRowList.LARGE_SCALE] and its like). */
internal fun wordRowList(
    ctx: Context,
    slot: ViewGroup,
    scope: CoroutineScope,
    host: WordRowList.Host<RowState>,
    scale: Float,
) = WordRowList(
    ctx, slot, scope, host,
    describe = RowState::toWordEntry,
    theme = { cardDocTheme(ctx, WordRowList.GLOSS_SP * scale) },
)
