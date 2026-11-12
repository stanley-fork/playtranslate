package com.playtranslate.ui

import android.content.Context
import android.graphics.Rect
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import com.playtranslate.R
import com.playtranslate.model.ImportedSenseGroup
import com.playtranslate.themeColor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A row, or a tappable element of a row, of a laid-out document, as the
 * page last reported it: [act] is the [DefAction] it fires, empty for a row
 * that is no tap target itself. The rect is in CSS px from the document's
 * top-left corner.
 */
internal data class DocTarget(
    val rowId: String,
    val act: String,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
)

/** One tappable document target as a controller action ([NavAction.target]). */
internal data class DocNavTarget(
    val document: DefinitionDocument,
    val rowId: String,
    val act: String,
) : NavTarget {
    override fun rectOnScreen(out: Rect): Boolean = document.rectOnScreen(rowId, act, out)
    override fun activate() = document.activate(rowId, act)
    override fun successor(): NavTarget? = DefAction.fallback(act)?.let { copy(act = it) }
}

/**
 * A surface's definitions as ONE page: the controller a host hands its rows
 * to ([setRows]) and hears taps from ([onAction]). It owns the view (added
 * to [slot]), the rows, what has been sent to the page, the controller
 * cursor, and what happens when the page cannot draw.
 *
 * The rules:
 *  - **The row list here is the truth.** The page is a rendering of it. A
 *    change to a row changes the list first and the page only if that row
 *    has been sent, so nothing is lost when rows are sent later or again.
 *  - **Rows are fed, not dumped.** The first [CHUNK] rows go at once; more
 *    follow while the document's end is within two screens of what is
 *    visible ([endIsNear]), checked after every layout and on every scroll.
 *    The page cannot tell what is on screen (its viewport is the whole
 *    document under a host scroll), so the host side decides. Each chunk's
 *    structured content is fetched and its markup built off the main
 *    thread, under the language the rows were looked up in.
 *  - **A rebind with the same rows patches.** [setRows] with the same ids
 *    in the same order replaces only the rows whose value changed (a deck
 *    badge, a hide, a spinner): no new row set, no jump. The exception is
 *    a row that gains a dictionary's structured glossary under its id:
 *    only feeding fetches that, so the rebind is a new row set.
 *  - **The view is 1 px only before its first layout.** A later row set
 *    keeps the height on screen until its own first layout, so a rebind
 *    never collapses the surface. Never GONE: the page reports nothing
 *    while its viewport has no width.
 *  - **Renderer death is recovered once per content.** A new shell takes
 *    the dead one's place and the rows are sent again. If that one dies
 *    too before the host sets new rows, the notice shows instead: the
 *    content itself may be what kills the renderer, and a loop of rebuilds
 *    would never end. There is no latch beyond that, because there is no
 *    other renderer to fall back to.
 *  - **No usable WebView shows the notice** where the definitions would be.
 *
 * [scope] must be confined to the main thread, like everything else here.
 * [theme] is read each time a shell is taken. A document holds its shell
 * from the first [prepare] or [setRows] until [release].
 */
internal class DefinitionDocument(
    private val ctx: Context,
    private val slot: ViewGroup,
    private val scope: CoroutineScope,
    private val theme: () -> DefinitionsDocument.DocTheme,
    private val onAction: (row: DefRow, action: String) -> Unit,
    /** What stands in the slot has changed: the page laid its rows out, or
     *  no layout will follow for these rows, because the notice took the
     *  page's place after its renderer died a second time ([showsNotice]) or
     *  their first chunk could not be built ([buildFailed]). A notice put up
     *  inside [setRows] (no usable WebView) is not announced: the caller
     *  reads [showsNotice] when the call returns. */
    private val onLayout: () -> Unit = {},
    /** Where a chunk's content is fetched and its markup built. */
    private val buildOn: CoroutineDispatcher = Dispatchers.Default,
) {
    private val app = ctx.applicationContext
    private val density = ctx.resources.displayMetrics.density
    private val rtl = ctx.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL

    private var rows: List<DefRow> = emptyList()
    private var language: String = ""

    /** The page's row-set generation: every `ptSet` starts one. */
    private var gen = 0

    /** Bumps when the host sets rows that are not a patch of the last ones;
     *  recovery is allowed once per value. */
    private var content = 0
    private var recoveredContent = -1

    /** How many of [rows], from the top, the page holds. */
    private var fed = 0
    private var feedJob: Job? = null

    /** Row id to the id of its element in the page, and back. Ids are made
     *  here and never derived from the row's own, which can hold anything. */
    private val domIds = HashMap<String, String>()
    private val rowIds = HashMap<String, String>()
    private var nextDom = 0

    /** The structured glossaries of the fed rows, kept for patches. A new
     *  map each time, so a build off the main thread reads a stable one. */
    private var structured: Map<Long, String> = emptyMap()
    private val appliedCss = HashSet<String>()

    /** Per row id: the latest patch asked for, so an older build that lands
     *  later is dropped. */
    private val patchSeq = HashMap<String, Int>()

    private var view: DefinitionDocumentView? = null
    private var shell: DefinitionShell? = null
    private var notice: TextView? = null
    private var holding = false
    private var height = 1

    /** Every row and tappable element of the last layout, in page order. */
    var targets: List<DocTarget> = emptyList()
        private set

    private var cursor: DocTarget? = null

    @get:VisibleForTesting
    val shownRows: List<DefRow> get() = rows

    /** True when the notice stands where the definitions would be. */
    @get:VisibleForTesting
    val showsNotice: Boolean get() = notice?.parent != null

    /** True when the first chunk of the rows could not be built: the page
     *  draws none of this row set and reports no layout for it. Until the
     *  next row set, or a later send of the chunk that goes through. */
    var buildFailed = false
        private set

    // ── Rows ─────────────────────────────────────────────────────────────

    /** The user has asked for definitions this document will draw: start
     *  loading a shell now, while the lookup runs. Idempotent. */
    fun prepare() {
        if (holding) return
        holding = true
        DefinitionShells.retain(ctx)
    }

    /**
     * The rows to show, looked up in [language] (the dictionary store's
     * language key, which also routes the page's media). Same ids in the
     * same order as the last call, no row gaining a structured glossary
     * ([gainsStructured]): a patch of the rows that changed. Anything else:
     * a new row set, fed from the top.
     */
    fun setRows(newRows: List<DefRow>, language: String) {
        if (newRows.isEmpty() && shell == null) {
            // Nothing to draw and nothing drawn: no shell is taken for it.
            rows = newRows
            this.language = language
            return
        }
        prepare()
        val sameIds = shell != null && language == this.language && newRows.size == rows.size &&
            newRows.indices.all { newRows[it].id == rows[it].id }
        // Over every row, sent or not: a chunk in flight was fetched for
        // the rows as they were.
        val patch = sameIds && newRows.indices.none { gainsStructured(rows[it], newRows[it]) }
        if (patch) {
            val old = rows
            rows = newRows
            syncFocusable()
            // A row not yet sent needs nothing: it is built from the list
            // when its chunk goes, and a chunk in flight checks on landing.
            for (i in 0 until min(fed, newRows.size)) if (newRows[i] != old[i]) patch(i)
            return
        }
        rows = newRows
        this.language = language
        content++
        restart()
    }

    /** The imported dictionary content [row] draws, whose structured form
     *  is fetched when the row is fed ([build]). */
    private fun importedGroups(row: DefRow): List<ImportedSenseGroup> = when (row) {
        is DefRow.Word -> row.data.importedGroups
        is DefRow.Imported -> row.groups
        is DefRow.SheetWord -> row.importedGroups
        is DefRow.More, is DefRow.Sense -> emptyList()
    }

    /**
     * Whether [new] draws a structured glossary that [old], the row it
     * replaces under the same id, did not. Such a row cannot be patched: a
     * patch draws with what feeding fetched, so the new glossary would stay
     * flat text until the next row set. Compared row against row and never
     * against what has been fetched, which stays empty when styling is off
     * or a fetch failed and would make every patch a new row set.
     */
    private fun gainsStructured(old: DefRow, new: DefRow): Boolean {
        val now = importedGroups(new)
        val before = importedGroups(old)
        if (now == before) return false
        val had = before.flatMapTo(HashSet()) { g -> g.senses.mapNotNull { it.scRowid } }
        return now.any { g -> g.senses.any { s -> s.scRowid?.let { it !in had } == true } }
    }

    /** The view is a focus stop only while a row offers something to act
     *  on: a block of plain definitions (a word page's senses) is passed
     *  over, as the native rows it replaced were. */
    private fun syncFocusable() {
        view?.isFocusable = rows.any {
            when (it) {
                // A dictionary's title is a target too. (A hidden word's
                // stub draws none, and is a stop for its eye.)
                is DefRow.Word -> it.opens || it.speak != null || it.trailing != null ||
                    it.data.importedGroups.isNotEmpty()
                is DefRow.More -> true
                is DefRow.Imported -> it.curation?.removable == true
                is DefRow.Sense -> it.removable || it.examples.any { e -> e.removeIndex != null }
                is DefRow.SheetWord -> true
            }
        }
    }

    /** Drops the shell and the view; the rows are forgotten too. A later
     *  [setRows] starts over. For a surface that closes, or that hides its
     *  definitions for long (a live view pins the render process's memory). */
    fun release() {
        feedJob?.cancel()
        feedJob = null
        gen++
        rows = emptyList()
        fed = 0
        buildFailed = false
        targets = emptyList()
        cursor = null
        shell?.destroy()
        shell = null
        view?.let {
            it.removeOnAttachStateChangeListener(attach)
            if (it.isAttachedToWindow) it.viewTreeObserver.removeOnScrollChangedListener(onScroll)
            slot.removeView(it)
        }
        view = null
        // No view, no height: the next one starts at 1 px.
        height = 1
        notice?.let { slot.removeView(it) }
        notice = null
        if (holding) {
            holding = false
            DefinitionShells.release()
        }
    }

    private fun restart() {
        feedJob?.cancel()
        feedJob = null
        gen++
        fed = 0
        buildFailed = false
        domIds.clear()
        rowIds.clear()
        patchSeq.clear()
        structured = emptyMap()
        targets = emptyList()
        if (!ensureShell()) return
        syncFocusable()
        if (rows.isEmpty()) {
            // Nothing to show: an empty page at 1 px, the shell kept for
            // the rows that follow a lookup.
            shell?.exec("ptSet('',$gen)")
            applyHeight(1)
            return
        }
        feed()
    }

    private fun feed() {
        if (feedJob?.isActive == true || fed >= rows.size) return
        val g = gen
        val from = fed
        val chunk = rows.subList(from, min(from + CHUNK, rows.size)).toList()
        val doms = chunk.map { row ->
            domIds.getOrPut(row.id) { "k${nextDom++}".also { rowIds[it] = row.id } }
        }
        val lang = language
        val known = structured
        feedJob = scope.launch {
            val built = try {
                withContext(buildOn) { build(chunk, doms, lang, known) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Class only: no message (it may carry a path), no content.
                Log.w(TAG, "chunk build failed: ${e.javaClass.simpleName}")
                // Nothing sends a first chunk again, so nothing of these
                // rows will be drawn: a host holding a loading state up for
                // their layout is told that none is coming.
                if (g == gen && from == 0) {
                    buildFailed = true
                    onLayout()
                }
                return@launch
            }
            if (g != gen) return@launch
            deliver(from, chunk, doms, built, g)
        }
    }

    private class Built(
        val htmls: List<String>,
        val structured: Map<Long, String>,
        val css: Map<String, String>,
    )

    private suspend fun build(
        chunk: List<DefRow>,
        doms: List<String>,
        lang: String,
        known: Map<Long, String>,
    ): Built {
        val groups = chunk.flatMap(::importedGroups)
        // Null when there is nothing structured to draw, and when the store
        // fails: these rows then draw their dictionaries' flat text, which
        // every imported sense carries. A store failure costs the styling,
        // never the rows.
        val styled = fetchYomitanStyledData(app, lang, groups)
        val all = if (styled == null) known else known + styled.structured
        val htmls = chunk.indices.map { DefRowHtml.html(ctx, chunk[it], doms[it], all, lang) }
        return Built(htmls, all, styled?.dictStyles.orEmpty())
    }

    private fun deliver(from: Int, chunk: List<DefRow>, doms: List<String>, built: Built, g: Int) {
        val s = shell ?: return
        structured = built.structured
        for ((dictId, css) in built.css) {
            if (appliedCss.add(dictId)) s.exec("ptApplyDictCss(${quote(dictId)},${quote(css)})")
        }
        val sb = StringBuilder()
        for (i in chunk.indices) {
            // The list moved on while this chunk was being built: the page
            // gets the row as it is now.
            val now = rows.getOrNull(from + i)
            sb.append(
                if (now != null && now != chunk[i] && now.id == chunk[i].id) {
                    DefRowHtml.html(ctx, now, doms[i], built.structured, language)
                } else {
                    built.htmls[i]
                },
            )
        }
        s.exec(if (from == 0) "ptSet(${quote(sb.toString())},$g)" else "ptAppend(${quote(sb.toString())},$g)")
        fed = from + chunk.size
        buildFailed = false
    }

    private fun patch(index: Int) {
        val row = rows[index]
        val dom = domIds[row.id] ?: return
        val seq = (patchSeq[row.id] ?: 0) + 1
        patchSeq[row.id] = seq
        val g = gen
        val lang = language
        val known = structured
        scope.launch {
            val html = withContext(buildOn) { DefRowHtml.html(ctx, row, dom, known, lang) }
            if (g != gen || patchSeq[row.id] != seq) return@launch
            shell?.exec("ptReplace(${quote(dom)},${quote(html)},$g)")
        }
    }

    // ── The view and its shell ───────────────────────────────────────────

    /** A live shell showing in the view, themed; false with the notice up
     *  when the device has no usable WebView. */
    private fun ensureShell(): Boolean {
        if (shell != null) return true
        val s = DefinitionShells.obtain(ctx) ?: run {
            showNotice()
            return false
        }
        hideNotice()
        val v = view ?: DefinitionDocumentView(ctx).also {
            view = it
            it.keys = keys
            it.addOnAttachStateChangeListener(attach)
            slot.addView(it, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
            if (it.isAttachedToWindow) it.viewTreeObserver.addOnScrollChangedListener(onScroll)
        }
        shell = s
        s.listener = listener
        s.mediaLanguage = language
        appliedCss.clear()
        v.show(s)
        s.exec(DefinitionsDocument.themeJs(theme(), rtl))
        return true
    }

    private fun applyHeight(px: Int) {
        height = max(1, px)
        val v = view ?: return
        val lp = v.layoutParams ?: return
        if (lp.height != height) {
            lp.height = height
            v.layoutParams = lp
        }
    }

    private fun showNotice() {
        view?.let { slot.removeView(it) }
        view = null
        height = 1
        if (notice?.parent != null) return
        val t = theme().tokens
        val pad = (16 * density).roundToInt()
        notice = TextView(ctx).apply {
            text = ctx.getString(R.string.definitions_need_webview)
            setTextColor(t.textMuted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(pad, (12 * density).roundToInt(), pad, (12 * density).roundToInt())
        }.also {
            slot.addView(it, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun hideNotice() {
        notice?.let { slot.removeView(it) }
        notice = null
    }

    private val listener = object : DefinitionShell.Listener {
        override fun onLayout(gen: Int, heightCssPx: Int, targetsJson: String) {
            if (gen != this@DefinitionDocument.gen) return
            applyHeight((heightCssPx * density).roundToInt())
            laidOut(parseTargets(targetsJson))
            this@DefinitionDocument.onLayout()
            feedIfNear()
        }

        override fun onAction(domId: String, act: String) {
            val rowId = rowIds[domId] ?: return
            val row = rows.firstOrNull { it.id == rowId } ?: return
            this@DefinitionDocument.onAction(row, act)
        }

        override fun onRendererGone() {
            shell = null
            if (recoveredContent == content) {
                Log.w(TAG, "renderer died twice on one content; showing the notice")
                showNotice()
                // The host may be holding something up until these rows are
                // drawn, and no layout will come to say so.
                this@DefinitionDocument.onLayout()
                return
            }
            recoveredContent = content
            restart()
        }
    }

    private fun parseTargets(json: String): List<DocTarget> = try {
        val a = JSONArray(json)
        val out = ArrayList<DocTarget>(a.length() / 6)
        var i = 0
        while (i + 5 < a.length()) {
            rowIds[a.getString(i)]?.let { rowId ->
                out += DocTarget(rowId, a.getString(i + 1), a.getInt(i + 2), a.getInt(i + 3), a.getInt(i + 4), a.getInt(i + 5))
            }
            i += 6
        }
        out
    } catch (e: Exception) {
        Log.w(TAG, "targets unreadable: ${e.javaClass.simpleName}")
        emptyList()
    }

    // ── Feeding on scroll ────────────────────────────────────────────────

    private val onScroll = ViewTreeObserver.OnScrollChangedListener { feedIfNear() }

    private val attach = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            v.viewTreeObserver.addOnScrollChangedListener(onScroll)
        }

        override fun onViewDetachedFromWindow(v: View) {
            v.viewTreeObserver.removeOnScrollChangedListener(onScroll)
        }
    }

    private fun feedIfNear() {
        if (fed < rows.size && fed > 0 && endIsNear()) feed()
    }

    /** Whether what the page holds ends within two screens of the visible
     *  part of the view (a view not on screen at all counts from its top). */
    private fun endIsNear(): Boolean {
        val v = view ?: return false
        val visible = Rect()
        val visibleBottom = if (v.isShown && v.getLocalVisibleRect(visible)) visible.bottom else 0
        return height - visibleBottom < 2 * ctx.resources.displayMetrics.heightPixels
    }

    // ── Targets for a controller ─────────────────────────────────────────

    /** The tappable targets as actions for a virtual cursor. */
    fun navActions(): List<NavAction> {
        val v = view ?: return emptyList()
        return targets.filter { it.act.isNotEmpty() }
            .map { NavAction(v, target = DocNavTarget(this, it.rowId, it.act)) }
    }

    /** The top of row [rowId] in the view's own px, null when the page does
     *  not hold it. For a host that anchors its scroll on a row. */
    fun rowTopPx(rowId: String): Int? =
        targets.firstOrNull { it.rowId == rowId }?.let { (it.top * density).roundToInt() }

    /** The id of the row at [y] (the view's own px), or of the first row
     *  below it; null for none. Rows that stand side by side share a top,
     *  and the first of them stands for their line: a host that anchors its
     *  scroll on this row is still on the line's start when the page is one
     *  column again (a rotation). */
    fun rowAt(y: Int): String? {
        val rowTops = targets.distinctBy { it.rowId }
        val at = rowTops.lastOrNull { (it.top * density).roundToInt() <= y } ?: rowTops.firstOrNull() ?: return null
        return rowTops.first { it.top == at.top }.rowId
    }

    /** The document's view, for a host that measures against it. */
    val hostView: View? get() = view

    /** The view's height as the page last reported it, in px; 1 before
     *  the first layout, for an empty document and while there is no view
     *  (released, or the notice standing in). The view keeps its height
     *  across a new row set and across a new shell, until the next layout:
     *  more than 1 says a layout was once reported to this view, not that
     *  rows are on screen now. After a new row set the last one's still
     *  are; after a renderer death the view is blank at that height until
     *  the new shell has drawn them. */
    val heightPx: Int get() = height

    internal fun rectOnScreen(rowId: String, act: String, out: Rect): Boolean {
        val v = view ?: return false
        val t = targets.firstOrNull { it.rowId == rowId && it.act == act } ?: return false
        if (!v.isShown) return false
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        localRect(t, out)
        out.offset(loc[0], loc[1])
        return true
    }

    private fun localRect(t: DocTarget, out: Rect) {
        out.set(
            (t.left * density).roundToInt(),
            (t.top * density).roundToInt(),
            ((t.left + t.width) * density).roundToInt(),
            ((t.top + t.height) * density).roundToInt(),
        )
    }

    internal fun activate(rowId: String, act: String) {
        val row = rows.firstOrNull { it.id == rowId } ?: return
        onAction(row, act)
    }

    /**
     * Plays the page for a JVM test, where no engine lays anything out: a
     * layout report in which every row the page has been sent is
     * [rowHeightCssPx] tall, stacked from the top, a tap target for its own
     * action when it has one, a word row's buttons after it as the page
     * reports them. Goes through what a real report does: the height, the
     * targets, the host's callback, the next chunk.
     */
    @VisibleForTesting
    fun layoutForTest(rowHeightCssPx: Int) {
        val out = ArrayList<DocTarget>()
        var top = 0
        for (row in rows) {
            if (row.id !in domIds) break
            val act = when (row) {
                is DefRow.Word -> if (row.opens) DefAction.OPEN else ""
                is DefRow.More -> DefAction.MORE
                is DefRow.Imported, is DefRow.Sense -> ""
                is DefRow.SheetWord -> if (row.hidden) "" else DefAction.TARGET
            }
            out += DocTarget(row.id, act, 0, top, 300, rowHeightCssPx)
            if (row is DefRow.Word) {
                val stub = row.trailing == DefRow.Trailing.SHOW
                // In page order: the head's buttons (explain first), then
                // what an explain answer adds under the head.
                if (row.explain != null && !stub) out += DocTarget(row.id, DefAction.EXPLAIN, 128, top, 36, 40)
                if (row.speak != null && !stub) out += DocTarget(row.id, DefAction.SPEAK, 180, top, 36, 40)
                if (row.trailing != null) out += DocTarget(row.id, DefAction.TRAILING, 232, top, 36, 40)
                row.explain?.answer?.takeIf { !stub }?.let { answer ->
                    out += DocTarget(row.id, DefAction.EXPLAIN_CLOSE, 252, top + 40, 36, 32)
                    if (answer.retry) out += DocTarget(row.id, DefAction.EXPLAIN_RETRY, 16, top + 72, 80, 32)
                }
            }
            top += rowHeightCssPx
        }
        applyHeight((top * density).roundToInt())
        laidOut(out)
        onLayout()
        feedIfNear()
    }

    /** The page laid its rows out as [laid]: they are the targets now, and
     *  the cursor stands on its own target among them or on what took its
     *  place ([DocNav.settle]). A move is told to the page, which draws the
     *  highlight from its own copy of the cursor; a cursor that merely
     *  lost its target is dropped here, as it always was. */
    private fun laidOut(laid: List<DocTarget>) {
        targets = laid
        val was = cursor
        val now = was?.let { DocNav.settle(laid, it.rowId, it.act) }
        if (was != null && now != null && now.act != was.act) setCursor(now) else cursor = now
    }

    /** Draws the controller's highlight on a target, or clears it (null). */
    fun setCursor(target: DocTarget?) {
        cursor = target
        val dom = target?.let { domIds[it.rowId] }
        shell?.exec(if (dom == null) "ptCursor(null,'')" else "ptCursor(${quote(dom)},${quote(target.act)})")
    }

    // ── Focus and D-pad on an Activity page ──────────────────────────────

    private val keys = object : DefinitionDocumentView.Keys {
        override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
            val dir = when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> DocNav.Dir.UP
                KeyEvent.KEYCODE_DPAD_DOWN -> DocNav.Dir.DOWN
                KeyEvent.KEYCODE_DPAD_LEFT -> DocNav.Dir.LEFT
                KeyEvent.KEYCODE_DPAD_RIGHT -> DocNav.Dir.RIGHT
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A,
                -> {
                    val c = cursor ?: return false
                    if (event.repeatCount == 0) activate(c.rowId, c.act)
                    return true
                }
                else -> return false
            }
            val next = DocNav.next(targets, cursor, dir, rtl)
            if (next == null) {
                // The last row the page holds is not the last row there is:
                // send the next ones and keep focus here for them.
                if (dir == DocNav.Dir.DOWN && fed < rows.size) {
                    feed()
                    return true
                }
                return false
            }
            moveCursorTo(next)
            return true
        }

        override fun onFocus(gained: Boolean, direction: Int) {
            if (!gained) {
                setCursor(null)
                return
            }
            val from = if (direction == View.FOCUS_UP || direction == View.FOCUS_BACKWARD) DocNav.Dir.UP else DocNav.Dir.DOWN
            DocNav.next(targets, null, from, rtl)?.let { moveCursorTo(it) }
        }

        override fun cursorRect(out: Rect): Boolean {
            val c = cursor ?: return false
            localRect(c, out)
            return true
        }

        override fun navActions(): List<NavAction> = this@DefinitionDocument.navActions()
    }

    private fun moveCursorTo(target: DocTarget) {
        setCursor(target)
        val v = view ?: return
        val r = Rect()
        localRect(target, r)
        v.requestRectangleOnScreen(r, false)
    }

    /** JSON string literal, additionally escaping the two codepoints legal
     *  in JSON but not in script source (U+2028/U+2029: they occur in
     *  CJK-adjacent dictionary text). */
    private fun quote(s: String): String =
        JSONObject.quote(s).replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")

    private companion object {
        const val TAG = "DefinitionDocument"

        /** Rows per send: about two screens of list rows, and the size the
         *  measurements put at one short wait on a slow phone. */
        const val CHUNK = 8
    }
}

/**
 * Moving a cursor over a document's targets with a D-pad, by where the page
 * laid them out. Pure.
 *
 * The page reports targets in document order, a row before its buttons,
 * which is also their order along the line: the row, then each button toward
 * the line's end. A row is where its first reported target is: its own
 * element, reported whether or not it takes a tap itself. A line is a run of
 * consecutive rows that share a top and stand side by side: in one column
 * every row is a line of its own, in two (the results' Words card on a wide
 * page) a line is the rows across. Up and down move to the previous or next
 * line that holds a tap target, onto its row nearest the cursor's row across
 * (the cursor keeps its column; a last line holding one row takes it from
 * either), and in that row onto the kind of target the cursor is on when the
 * row has one (a column of eyes walks as a column), else the row's first.
 * Left and right step along the line's targets, a row's own and then its
 * buttons, row after row, toward the line's end being right on a
 * left-to-right page and left on a right-to-left one. Null means there is
 * nowhere further that way.
 */
internal object DocNav {
    enum class Dir { UP, DOWN, LEFT, RIGHT }

    /** A row: where it is (its first reported target) and its targets that
     *  take a tap, in page order. */
    private class Row(val box: DocTarget) {
        val taps = ArrayList<DocTarget>()
    }

    fun next(targets: List<DocTarget>, cur: DocTarget?, dir: Dir, rtl: Boolean): DocTarget? {
        val rows = LinkedHashMap<String, Row>()
        for (t in targets) {
            val row = rows.getOrPut(t.rowId) { Row(t) }
            if (t.act.isNotEmpty()) row.taps += t
        }
        val lines = ArrayList<MutableList<Row>>()
        for (row in rows.values) {
            val line = lines.lastOrNull()
            if (line != null && onLine(line.last().box, row.box)) line += row else lines += mutableListOf(row)
        }
        // A target with no act is never a cursor position: a row without a
        // tap target is passed over, and so is a line of such rows.
        val reachable = lines.map { line -> line.filter { it.taps.isNotEmpty() } }.filter { it.isNotEmpty() }
        if (reachable.isEmpty()) return null
        val at = cur?.let { c -> rows[c.rowId]?.taps?.firstOrNull { it.act == c.act } }
        if (at == null) {
            // A fresh cursor: the first row coming down, the last going up.
            val row = if (dir == Dir.UP) reachable.last().last() else reachable.first().first()
            return row.taps.first()
        }
        val i = reachable.indexOfFirst { line -> line.any { it.box.rowId == at.rowId } }
        return when (dir) {
            Dir.UP, Dir.DOWN -> {
                val line = reachable.getOrNull(i + if (dir == Dir.DOWN) 1 else -1) ?: return null
                val left = rows.getValue(at.rowId).box.left
                val row = line.minBy { abs(it.box.left - left) }
                row.taps.firstOrNull { it.act == at.act } ?: row.taps.first()
            }
            Dir.LEFT, Dir.RIGHT -> {
                val line = reachable[i].flatMap { it.taps }
                val towardEnd = (dir == Dir.RIGHT) != rtl
                line.getOrNull(line.indexOf(at) + if (towardEnd) 1 else -1)
            }
        }
    }

    /**
     * Where a cursor on [act] of row [rowId] stands in [targets], a layout
     * that may have come after a press of that very target: on it, while
     * the page still lays it out; else on the action that takes its place
     * in the same row, for the few that name one ([DefAction.fallback]: an
     * explain answer's ✕ and its Try again go with the press, and leave the
     * row's explain button); else nowhere (null), as a cursor whose target
     * is gone has always been. The one rule for every cursor that walks a
     * document's targets.
     */
    fun settle(targets: List<DocTarget>, rowId: String, act: String): DocTarget? =
        targets.firstOrNull { it.rowId == rowId && it.act == act }
            ?: DefAction.fallback(act)?.let { next -> targets.firstOrNull { it.rowId == rowId && it.act == next } }

    /** Whether the row at [b], the one after the row at [a], is on [a]'s
     *  line: the same top, and beside it, not over it. Rows reported at one
     *  spot are therefore a column, in document order. */
    private fun onLine(a: DocTarget, b: DocTarget): Boolean =
        b.top == a.top && (b.left >= a.left + a.width || b.left + b.width <= a.left)
}

/** The theme of a document that sits in one of the app's cards, its text
 *  sized from [baseFontSizePx], its rows in two columns on a wide page when
 *  [twoColumnsWhenWide] (the results' Words card only). */
internal fun cardDocTheme(
    ctx: Context,
    baseFontSizePx: Float,
    twoColumnsWhenWide: Boolean = false,
) = DefinitionsDocument.DocTheme(
    DefinitionsDocument.Tokens(
        text = ctx.themeColor(R.attr.ptText),
        textMuted = ctx.themeColor(R.attr.ptTextMuted),
        textHint = ctx.themeColor(R.attr.ptTextHint),
        accent = ctx.themeColor(R.attr.ptAccent),
        panel = ctx.themeColor(R.attr.ptCard),
        baseFontSizePx = baseFontSizePx,
    ),
    divider = ctx.themeColor(R.attr.ptDivider),
    band = hiddenWordBandColor(ctx),
    outline = ctx.themeColor(R.attr.ptOutline),
    wash = ctx.themeColor(R.attr.ptAccentTint),
    twoColumnsWhenWide = twoColumnsWhenWide,
)
