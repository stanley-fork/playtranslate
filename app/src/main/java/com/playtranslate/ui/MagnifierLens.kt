package com.playtranslate.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Choreographer
import android.view.GestureDetector
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewTreeObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.DrawableCompat
import com.playtranslate.dictionary.Deinflector
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.overlay.OverlayPlacement
import com.playtranslate.overlay.WindowChurnGate
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.isEffectivelyDark
import com.playtranslate.overlayThemedContext
import com.playtranslate.themeColor
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Floating magnifier lens shown while the user drags on a JP/ZH/Latin token
 * and persists post-release as the dictionary surface.
 *
 * Visual structure (matches design handoff in
 * ~/playtranslate/design_handoff_magnifying_lens):
 *  - A rounded card (16dp radius, BWI_PANEL #15181B, 1dp white-16% border)
 *    holds the zoom / loading / definitions body.
 *  - A coral pill (accent-themed) overhangs the card's top edge by half its
 *    height. It carries the word, a hairline divider, and the reading while
 *    the lens is a magnifier or is loading. Once a definition row shows,
 *    the row names the word and the pill is hidden (kept in the layout, so
 *    the chips beside it do not move).
 *  - Two "calm" chip buttons (32dp visible disk, 48dp hit halo) flank the
 *    pill — Speak on the left, Anki on the right. They are no-ops in this
 *    commit; the wiring will land in a follow-up.
 *  - A 10dp triangular arrow drops from the card's bottom edge (or rises
 *    from the top when the lens flips below the finger).
 *
 * Render modes:
 *  - **ZOOM** (drag): zoomed pixels + crosshair fill the card body. The pill
 *    keeps showing the currently-detected token. Chips hidden.
 *  - **LOADING**: spinner + "Looking up…" in the body while OCR/lookup
 *    resolve. Pill shows the word being resolved. Chips hidden.
 *  - **DEFINITIONS** (drag-preview AND sticky): the body is ONE
 *    definitions document ([DefinitionDocument]) whose rows are the lens's
 *    sections: the looked-up word alone, or with a related unit (word +
 *    containing phrase on the space-delimited surfaces, fused expression +
 *    member words and other entries on JA, the longer unit on top), each
 *    row under its own headword, then the "Load more" row when sections
 *    are held back. Chips appear only once the lens becomes interactive
 *    via [makeInteractive] — that signals the post-release sticky state
 *    where quick actions are appropriate.
 *
 * Tap routing:
 *  - The page is the authority on what a tap in the body hit: a link
 *    navigates (handed to the browser), anything else is its row's tap,
 *    [onOpenTap] for the tapped/primary unit's row, [onSecondaryOpenTap]
 *    for a related one's, [onLoadMoreTap] for the "Load more" row. A row's
 *    chevron is purely a visual cue (a section's `opens` withholds it, and
 *    the tap, when the caller has nothing to open into).
 *  - While the lens is loading, a tap on the card or the pill fires
 *    [onOpenTap].
 *  - Chip taps are absorbed (no-op) — they do NOT fall through to the
 *    card-wide open handler.
 *  - The arrow strip and the (currently hidden) pill chrome region outside
 *    the card are non-interactive.
 */
/** Text-size factor of the lens's definitions. The renderer's base sizes
 *  are tuned for the full-width result row; the lens is a small floating
 *  card, so it renders the same body scaled down to keep its compact
 *  footprint (definitions land near 13sp). */
private const val LENS_DEFINITIONS_SCALE = 0.8f

/** The document id of the lens's "Load more" row. */
private const val MORE_ROW_ID = "more"

/** One section of the lens's split (phrase + word) definitions body — see
 *  [MagnifierLens.setSplitDefinitions]. [opens] renders the section's
 *  trailing chevron and makes the whole section a tap target for its
 *  open-detail callback; pass false on surfaces (or entries) with nothing
 *  to open into. */
data class LensSection(
    val data: WordDefinitionData,
    val label: String?,
    val opens: Boolean,
)

/** The lens's "Load more (n)" row ([MagnifierLens.setSplitDefinitions]):
 *  [count] sections a host will add on tap; [loading] while the host
 *  resolves them (the row reads "Looking up…" and ignores taps). */
data class LensLoadMore(val count: Int, val loading: Boolean = false)

/** Where the split body's viewport lands after
 *  [MagnifierLens.setSplitDefinitions] rebuilds it. */
sealed class LensScroll {
    /** The top (a first show). */
    object Top : LensScroll()

    /** Where it was before the rebind, clamped to the new content (a rebind
     *  that adds nothing the user asked for, such as the Anki deck
     *  back-fill). */
    object Keep : LensScroll()

    /** The secondary section at [secondaryIndex] (an index into the
     *  `secondaries` list as passed) at the body's resting position, where
     *  the first row sits at scroll 0, clamped to the content's end (after a
     *  "Load more"). An index outside the list behaves as [Keep]. */
    data class Reveal(val secondaryIndex: Int) : LensScroll()
}

/**
 * Clamped card-body height for the post-release grow-to-fit. The card grows
 * away from the anchored (arrow/finger) edge — UP when not flipped, DOWN when
 * flipped — but never past the safe screen edge: the outer chrome (pill/chip
 * [overhang]) must stay within [[safeTop], [safeBottom]]. Never shrinks below
 * [baseCardH]; a card already pinned against its edge can't grow and stays at
 * base. The window itself is pre-sized to full height, so this returns only
 * the card height — no window placement (the card grows inside a fixed window).
 *
 * Pure (no Android deps) so the clamp math is unit-testable.
 *
 * @param anchoredEdgeY screen Y of the anchored card edge — card BOTTOM when
 *   not flipped, card TOP when flipped.
 */
internal fun computeGrownCardHeight(
    flipped: Boolean,
    anchoredEdgeY: Int,
    desiredCardH: Int,
    baseCardH: Int,
    safeTop: Int,
    safeBottom: Int,
    overhang: Int,
): Int {
    val maxCardH = if (!flipped) {
        anchoredEdgeY - overhang - safeTop          // card top − overhang ≥ safeTop
    } else {
        safeBottom - overhang - anchoredEdgeY        // card bottom + overhang ≤ safeBottom
    }
    return desiredCardH.coerceIn(baseCardH, maxCardH.coerceAtLeast(baseCardH))
}

/**
 * Widest the pill capsule may render inside a [viewW]-wide lens host.
 *
 * The chips are seated off the pill's own edges ([computeChipLaneMargins]), so
 * every pixel the pill takes past this cap pushes them a pixel further past the
 * host's frame — where the FrameLayout clips them away, visible disk and touch
 * target together. At exactly this width the two chips land flush with the
 * host's left and right edges: the resting placement they are laid out at
 * before the reveal slides them out from under the pill.
 *
 * @param chipLane horizontal space one chip needs beside the pill — its
 *   hit-halo pad, its visible disk, and the resting gap to the pill's edge.
 */
internal fun computeMaxPillWidth(viewW: Int, chipLane: Int): Int =
    (viewW - 2 * chipLane).coerceAtLeast(0)

/**
 * Left and right margins that seat the chips one [chipLane] outside a
 * [pillWidth]-wide pill centered in a [viewW]-wide host. Both come out ≥ 0
 * exactly when [pillWidth] is within [computeMaxPillWidth].
 */
internal fun computeChipLaneMargins(viewW: Int, pillWidth: Int, chipLane: Int): Pair<Int, Int> {
    val pillLeft = (viewW - pillWidth) / 2
    val pillRight = pillLeft + pillWidth
    return (pillLeft - chipLane) to (viewW - pillRight - chipLane)
}

/**
 * Split what [maxPillWidth] leaves after [fixedChromeWidth] — paddings,
 * divider, gaps, chevron — between the pill's headline and its reading, given
 * the [readingWidth] the reading wants at its settled text size.
 *
 * The reading takes only what it needs, but never more than half the budget:
 * the headline is the word being looked up, and a long reading must not starve
 * it. Both numbers are handed to their views as maxWidths, so their sum plus
 * the chrome is what bounds the capsule's WRAP_CONTENT width — and that bound
 * is what keeps the chip lanes intact.
 */
internal fun computePillTextAllotment(
    maxPillWidth: Int,
    fixedChromeWidth: Int,
    readingWidth: Int,
): Pair<Int, Int> {
    val budget = (maxPillWidth - fixedChromeWidth).coerceAtLeast(0)
    val word = (budget - readingWidth.coerceAtLeast(0)).coerceAtLeast(budget - budget / 2)
    return word to (budget - word)
}

class MagnifierLens(
    internal val rawCtx: Context,
    internal val wm: WindowManager,
    private val displayId: Int,
    private val overlayHost: OverlayHost? = null,
    /** When false, the post-release Anki chip stays hidden — used by the
     *  over-game capture panel, whose lens is display + speak only. */
    private val showAnkiChip: Boolean = true,
    /** When false, the sticky lens never takes window focus, a controller
     *  attached or not: the owner keeps the controller (tap-to-lookup, whose
     *  boxes' cursor A and B drive while its card is up), and the card's own
     *  controls are touch's. */
    private val takesControllerFocus: Boolean = true,
) {
    private val density = rawCtx.resources.displayMetrics.density
    private fun dp(v: Float) = (v * density).toInt()

    private val lensH = dp(120f)
    /** Card has rounded corners; the body, pill, and chips overlay it. */
    private val cardCornerR = dp(16f).toFloat()

    /** Distance in px between finger center and the near edge of the lens body. */
    private val verticalMarginPx = dp(25f)
    /** Triangular pointer drawn between the lens body and the finger when
     *  the lens is in sticky-definitions mode. Matches WordLookupPopup's
     *  arrow proportions so the two surfaces feel like the same family. */
    private val arrowSizePx = dp(10f)
    /** Pill is 40dp tall, centered on the card's top edge so half overhangs
     *  above the card. */
    private val pillHeightPx = dp(40f)
    private val chipVisDiameterPx = dp(36f)
    private val chipHitSizePx = dp(52f)
    /** Distance from the chip hit-button edge to the visible disk edge. */
    private val chipHaloPadPx = (chipHitSizePx - chipVisDiameterPx) / 2
    /** Visible chip disk insets 4dp from the card horizontal edge. The host
     *  view is wider than the card by exactly this amount on each side so
     *  the chip's hit halo can extend the full 48dp without clipping. */
    private val chipHaloXPx = dp(4f)
    /** Pixels reserved above (or below, when flipped) the card so the chip's
     *  48dp hit halo can render fully. The chip is vertically centered on
     *  the card's edge — half its height (24dp) overhangs the card. */
    private val pillChipOverhangPx = chipHitSizePx / 2

    private val zoom = 2f
    /** Tolerance for the lens overrunning the top of the screen before we
     *  flip it below the finger; matches the original feel. */
    private val topOverhangTolerancePx = lensH / 5
    /** Extra slack on the flip threshold: the lens flips below the finger
     *  32dp earlier — while the finger is still 32dp lower on the screen
     *  than the geometric fit alone would require. */
    private val flipBiasPx = dp(32f)
    /** Gap kept between the lens's outer chrome edge and the nearest safe
     *  screen edge when the card grows to fit its content. */
    private val growBufferPx = dp(8f)

    private var lensView: LensView? = null
    /** A lookup through this lens is under way ([prepareDefinitions]):
     *  every window it builds starts loading its definitions page at once.
     *  Sticky for the lens's lifetime, because the request can race window
     *  construction: [ensureWindow] honors it at build and
     *  [prepareDefinitions] covers the already-built case. */
    private var definitionsWanted = false
    /** Full-screen host that owns the window; [lensView] is its single child,
     *  slid horizontally inside it. The window is the whole display so a tap
     *  anywhere off the card is caught and consumed (dismiss without leaking
     *  the tap to the app/game beneath). */
    private var lensRoot: LensRoot? = null
    private var params: WindowManager.LayoutParams? = null
    /** Snapshot of (isDark, accentColorRes) at the time the cached
     *  [lensView] was built. Compared against the live prefs on every
     *  [show]: when the user changes the theme the cached window is no
     *  longer correct, so we tear it down silently and let
     *  [ensureWindow] rebuild against fresh attrs. */
    private var cachedThemeKey: Pair<Boolean, Int>? = null

    private fun currentThemeKey(): Pair<Boolean, Int> =
        isEffectivelyDark(rawCtx) to Prefs(rawCtx).accent.color

    /** Most recent finger x from [show]. Used by [makeInteractive] to align
     *  the sticky-mode arrow horizontally with the release position. */
    private var lastFingerX = 0

    // --- Post-release grow-to-fit state (captured at [show], consumed by
    //     [fitHeightToContent]). ----------------------------------------
    /** Current logical card-body height, mirrored from the view. Reset to
     *  [lensH] on every [show]; grown by [fitHeightToContent] after release. */
    private var lensCardHeight = lensH
    /** Flip state of the last [show] — fixes the grow direction. */
    private var lastFlipped = false
    /** Screen height from the last [show], for the bottom safe-edge clamp. */
    private var lastScreenH = 0
    /** Screen Y of the anchored card edge (the arrow/finger edge that stays
     *  put as the card grows): card BOTTOM when not flipped, card TOP when
     *  flipped. */
    private var anchoredEdgeScreenY = 0
    /** Horizontal offset of the lens column inside the full-screen root, and
     *  the card width — captured at [show] for [makeInteractive]'s arrow. */
    private var lastLensX = 0
    private var lastCardW = 0
    /** Drives the animated card grow on release; cancelled on re-fit / teardown. */
    private var heightAnimator: ValueAnimator? = null

    /** Fires once per actual window teardown (tap-outside in sticky mode,
     *  new drag start, [dismiss] caller). */
    var onDismiss: (() -> Unit)? = null
    /** For an owner that holds what lies under the sticky lens (a capture's
     *  Definitions boxes, [WordLookupSession]): a sticky-mode DOWN off the
     *  card, with its screen position, goes here in place of the lens
     *  dismissing itself, so the owner decides what the tap means (another
     *  word under it, or the end of everything). Null for every other
     *  lens: the tap dismisses it. A touch outside the window altogether
     *  (ACTION_OUTSIDE) dismisses either way. Read at each tap. */
    var onOffCardDown: ((rawX: Float, rawY: Float) -> Unit)? = null
    /** Fires when the card or pill is tapped in sticky mode. In split mode
     *  ([setSplitDefinitions]) this is the PRIMARY section's open action —
     *  the pill and the primary section both fire it. */
    var onOpenTap: (() -> Unit)? = null
    /** Fires when one of the split body's SECONDARY sections is tapped in
     *  sticky mode, with its index into the [setSplitDefinitions]
     *  secondaries list — the containing phrase on the space-delimited
     *  surfaces, a member word on JA. Never fires outside split mode. */
    var onSecondaryOpenTap: ((Int) -> Unit)? = null
    /** Fires when the split body's "Load more" row ([setSplitDefinitions]
     *  with a [LensLoadMore]) is tapped or activated by the controller,
     *  never while the row is loading. The host resolves the held-back
     *  sections and rebinds the split with them. */
    var onLoadMoreTap: (() -> Unit)? = null
    /** Fires when the right chip (Anki) is tapped in sticky mode. */
    var onAnkiTap: (() -> Unit)? = null
    /** Fires when the right chip (Anki) is long-pressed in sticky mode.
     *  Used by the one-tap feature to open the editable review sheet
     *  while a short tap performs the headless send. */
    var onAnkiLongPress: (() -> Unit)? = null
    /** Fires when the left chip (Speak) is tapped in sticky mode. */
    var onSpeakTap: (() -> Unit)? = null

    /** True when there's no [overlayHost] — the lens attaches directly to the
     *  activity window (TYPE_APPLICATION_PANEL) instead of going through a
     *  backend overlay host. Used by the in-app tap-a-word surface. */
    private val useActivityWindow: Boolean get() = overlayHost == null

    val isInteractive: Boolean get() = lensView?.isInteractive == true

    /** A lookup is under way (a drag has started): load the definitions
     *  page while the user moves toward a word, so the first definition does
     *  not wait for it. This is the first moment a lens touches the WebView;
     *  nothing loads for a lens that only magnifies. */
    fun prepareDefinitions() {
        definitionsWanted = true
        lensView?.prepareDefinitions()
    }

    /** True while the sticky lens HOLDS WINDOW FOCUS for controller input —
     *  [makeInteractive] found a controller, so A/B/dpad/stick are driving the
     *  lens, not the game. The a11y key filter's "game input clears the
     *  lookup" rule must stand down for those keys while this is set. */
    val isConsumingController: Boolean get() = isInteractive && tookControllerFocus
    private var tookControllerFocus = false

    /** Zoom source, held here as well as on the view: setBitmap can arrive
     *  while the lens is hidden (the camera scene flow attaches the frame
     *  BEFORE its deferred reveal creates the view), and a view-only write
     *  would silently drop it — the zoom then renders as a black card. The
     *  drag flow clears this via setBitmap(null) before every recycle, so
     *  it can never dangle a recycled bitmap into the next show(). */
    private var sourceBitmapForShow: Bitmap? = null

    fun setBitmap(bitmap: Bitmap?) {
        sourceBitmapForShow = bitmap
        lensView?.setSourceBitmap(bitmap)
    }

    /** The bars the app beneath keeps hidden, for [makeInteractive]'s focus
     *  flip: the host's read of the display's own insets state (overlay
     *  hosting) or the hosting activity's decor (activity-window hosting;
     *  [rawCtx] may be a themed wrapper around the Activity — the capture
     *  sheet's is — which the host read unwraps). Neither reads our own
     *  root, so the answer never depends on whether it has traversed yet.
     *  Null pre-R, by [OverlayHost]'s policy. */
    private fun gameHiddenBarsForFlip(): Int? =
        if (overlayHost != null) overlayHost.hiddenSystemBarsOnDisplay(displayId)
        else OverlayHost.hiddenSystemBarsOfActivity(rawCtx)

    /** Update the word + reading on the pill. Pass null for either to hide. */
    fun setLabel(word: String?, reading: String?) {
        lensView?.setLabel(word, reading)
    }

    /** Single-unit definitions body. [opens] draws the body's floating
     *  chevron — the same open cue every split section carries — and is
     *  false only when the caller has nothing to open into (no entry, so
     *  no [onOpenTap]); it is a cue, not a gate, so tap routing is
     *  unchanged either way. Pass null [data] to return to the zoom body. */
    fun setDefinitions(data: WordDefinitionData?, label: String?, opens: Boolean = true) {
        lensView?.setDefinitions(data, label, opens)
        // Re-fit when an already-interactive lens gets new content (the Anki
        // deck-badge back-fill re-binds after makeInteractive). Pre-release
        // and dwell-preview binds aren't interactive, so they stay at base
        // height until makeInteractive runs the first fit.
        if (isInteractive) fitHeightToContent()
    }

    /** Split definitions body: the [primary] section plus one or more
     *  [secondaries], dividers between, each headed by its own headword and
     *  (when it opens) a trailing chevron. [primary] is the TAPPED unit:
     *  the pill keeps its identity and [onOpenTap] is its open action;
     *  each secondary routes to [onSecondaryOpenTap] with its list index.
     *  [secondariesOnTop] places the LONGER unit above: true on the
     *  space-delimited surfaces (tapped word + containing phrase above it),
     *  false on JA (tapped unit on top + its member words below, in
     *  expression order, then its alternative entries). Sections bind flat
     *  instantly; any section whose data carries a structured Yomitan
     *  payload upgrades to its own styled (WebView) renderer once painted,
     *  exactly like the single-unit body. [loadMore], when set, appends a
     *  "Load more (n)" row after the last section, below a divider, whose
     *  tap fires [onLoadMoreTap]; [secondaries] may then be empty, and the
     *  primary renders alone above the row.
     *
     *  [scroll] is where the body's viewport lands ([LensScroll]):
     *  [LensScroll.Top] scrolls at once; [LensScroll.Keep] and
     *  [LensScroll.Reveal] scroll in the layout pass that lays the rebuilt
     *  sections out, before that pass draws. The grow-to-fit below requests
     *  its layout in the same call, so that one pass also lays the card out
     *  at the grow's end state ([animateCardGrowInside]) and the scroll is
     *  clamped against the grown viewport; the grow's frames are draw-only
     *  (translation and clip, see [LensView.setGrowFrame]) and never touch
     *  the scroll offset, so the grow does not undo it. A later relayout
     *  keeps the offset in pixels: a section above the viewport whose
     *  height changes afterwards (an image taking its box) shifts what
     *  the viewport shows. */
    fun setSplitDefinitions(
        primary: LensSection,
        secondaries: List<LensSection>,
        secondariesOnTop: Boolean = true,
        loadMore: LensLoadMore? = null,
        scroll: LensScroll = LensScroll.Top,
    ) {
        lensView?.setSplitDefinitions(primary, secondaries, secondariesOnTop, loadMore, scroll)
        if (isInteractive) fitHeightToContent()
    }

    /** Flips the "Load more" row between its label and its loading text in
     *  place: no rebind, no height change (both are one line). No-op
     *  without a row. */
    fun setLoadMoreLoading(loading: Boolean) {
        lensView?.setLoadMoreLoading(loading)
    }

    /** The "Load more" row's text as bound; null when the lens has none. */
    @VisibleForTesting
    internal fun loadMoreTextForTest(): String? = lensView?.loadMoreTextOrNull()

    /** The ids of the rows a controller can reach, top to bottom. */
    @VisibleForTesting
    internal fun navRowsForTest(): List<String> = lensView?.navRows().orEmpty()

    /** Stands in for the page under a JVM test: a layout report with every
     *  row [rowHeightCssPx] tall ([DefinitionDocument.layoutForTest]). */
    @VisibleForTesting
    internal fun layoutRowsForTest(rowHeightCssPx: Int) {
        lensView?.layoutRowsForTest(rowHeightCssPx)
    }

    /** The rows the lens has bound, in visual order. */
    @VisibleForTesting
    internal fun shownRowsForTest(): List<DefRow> = lensView?.shownRowsForTest().orEmpty()

    /** The id of the row the controller cursor is on; null when it is not on a row. */
    @VisibleForTesting
    internal fun cursorRowForTest(): String? = lensView?.cursorRowForTest()

    /** The top of row [id] in the scrolling body, in px. */
    @VisibleForTesting
    internal fun rowTopForTest(id: String): Int? = lensView?.rowTopForTest(id)

    /** The tap the page would report on row [id]. */
    @VisibleForTesting
    internal fun tapRowForTest(id: String) {
        lensView?.tapRowForTest(id)
    }

    /** Whether the pill is drawn (it is hidden while definition rows show). */
    @VisibleForTesting
    internal fun pillShownForTest(): Boolean = lensView?.pillShownForTest() == true

    /** The lens window's root, where key events enter. */
    @VisibleForTesting
    internal fun rootViewForTest(): View? = lensView?.rootView

    @VisibleForTesting
    internal fun scrollYForTest(): Int = lensView?.definitionsScroll?.scrollY ?: 0

    @VisibleForTesting
    internal fun scrollToForTest(y: Int) {
        lensView?.definitionsScroll?.scrollTo(0, y)
    }

    fun setLoading(word: String?, reading: String?) {
        lensView?.setLoading(word, reading)
    }

    /** Show the spinner alone in the Placeholder pill (no label) while
     *  the drag-start OCR job runs. Pass false once OCR completes
     *  (success, "no text", exception, or cancellation) to animate back
     *  to the magnifying-glass icon + "Find a word" prompt. No-op when
     *  the pill is showing a word. */
    fun setPillLoading(loading: Boolean) {
        lensView?.setPillLoading(loading)
    }

    /** Show or hide the loading spinner on the Speak chip. */
    fun setSpeakChipLoading(loading: Boolean) {
        lensView?.setSpeakChipLoading(loading)
    }

    /** Promote the shown lens to sticky. Returns false when there is no lens
     *  to promote (its window never attached, or a rebuild lost it): the
     *  owner's session has no lens to end from later and ends itself
     *  ([DragLookupController.endSession]). */
    fun makeInteractive(): Boolean {
        val view = lensView ?: return false
        val root = lensRoot ?: return false
        val p = params ?: return false
        // The interactive card needs window focus only for key/stick
        // navigation (dpad + A over the pill/chips, stick scroll, B/Escape
        // dismiss). With no gamepad or hardware keyboard attached there is
        // nothing to navigate WITH, so stay non-focusable: focus taking is
        // never free (it steals key input from the app beneath). Touch and
        // outside-touch dismissal work either way — neither needs focus.
        var flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        tookControllerFocus = takesControllerFocus && hasNavInputDevice(rawCtx)
        if (!tookControllerFocus) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        } else {
            // Focus makes this window the system-bar control target, whose
            // default request would show the nav pill over an immersive game.
            // Mirror the game's state, armed before the flag flip so the
            // request rides the focus grant. The mask is read from windows
            // attached BEFORE this one, never from this root: show() and this
            // flip can run in the same frame (the capture sheet's word tap),
            // and a window's own rootWindowInsets are null until its first
            // traversal — a self-read there silently left the default request
            // in place and the pill came up with the lens.
            OverlayHost.mirrorSystemBars(root, gameHiddenBarsForFlip())
        }
        // The window is already full-screen (set in show()); this flag change
        // is flags-only — no size or position change, so it can't trigger the
        // resize flash. The card grows purely inside the fixed window below.
        p.flags = flags
        try { wm.updateViewLayout(root, p) } catch (_: Exception) {}
        // Sticky now: the root catches off-card taps to dismiss (and consumes
        // them so they don't reach the app/game behind), and — while focusable
        // for the controller — the B button dismisses the same way.
        root.interactive = true
        root.onOffCardDown = { x, y -> offCardDown(x, y) }
        root.onOutside = { dismiss() }
        root.onDismissKey = { dismiss() }
        view.attachInteractiveListeners(onDismissRequest = { x, y -> offCardDown(x, y) })
        view.setArrowVisible(true, arrowRelX())
        // Release commit: grow the card to fit the dictionary, animated inside
        // the fixed window (definitions were bound just above).
        fitHeightToContent()
        return true
    }

    /** A sticky-mode DOWN off the card at ([rawX], [rawY]): the owner's
     *  [onOffCardDown] when it set one, else the dismissal. */
    private fun offCardDown(rawX: Float, rawY: Float) {
        val hook = onOffCardDown
        if (hook != null) hook(rawX, rawY) else dismiss()
    }

    /** Controller-opened lens: the cursor starts on its first target (the
     *  word's row, once it shows), so the next A drills straight into the
     *  detail screen. A touch-opened lens selects on the first controller
     *  input instead (the sheet's first-press rule). */
    fun focusForController() {
        lensView?.focusStart()
    }

    /** Grow the card to fit newly-bound definitions on an ALREADY-interactive
     *  lens — the Anki deck-badge re-bind after [makeInteractive]. Grows the
     *  card inside the fixed full-height window; no-op for dwell-preview binds
     *  (not yet interactive), so the card stays at base height until release. */
    fun fitHeightToContent() {
        val view = lensView ?: return
        if (!view.isInteractive) return
        val target = grownCardHeight(view)
        if (target <= lensCardHeight) return
        animateCardGrowInside(view, target)
    }

    /** Clamped card height for the currently bound definitions. */
    private fun grownCardHeight(view: LensView): Int {
        val (safeTop, safeBottom) = safeVerticalBounds()
        return computeGrownCardHeight(
            flipped = lastFlipped,
            anchoredEdgeY = anchoredEdgeScreenY,
            desiredCardH = view.desiredCardHeightForContent(),
            baseCardH = lensH,
            safeTop = safeTop,
            safeBottom = safeBottom,
            overhang = pillChipOverhangPx,
        )
    }

    /** Visible vertical bounds for the grow clamp: system-bar + display-cutout
     *  insets shrink the raw screen, plus [growBufferPx]. From the window's
     *  own dispatch ([LensRoot.windowInsets]); the raw screen edges before
     *  the first one. */
    private fun safeVerticalBounds(): Pair<Int, Int> {
        val insets = lensRoot?.windowInsets?.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        val top = (insets?.top ?: 0) + growBufferPx
        val bottom = lastScreenH - (insets?.bottom ?: 0) - growBufferPx
        return top to bottom
    }

    /** The lens column's horizontal range inside the root for a column
     *  [viewW] wide on a screen [screenW] wide: inboard of a visible side
     *  navigation bar this window lies below and of a side display cutout
     *  ([LensRoot.windowInsets]); the screen edges before the first
     *  dispatch. A column wider than the span pins to its left. */
    private fun columnRange(root: LensRoot, screenW: Int, viewW: Int): IntRange {
        val side = root.windowInsets?.getInsets(
            WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.displayCutout()
        )
        val lo = side?.left ?: 0
        val hi = maxOf(lo, screenW - (side?.right ?: 0) - viewW)
        return lo..hi
    }

    /** Where the sticky arrow's tip sits inside the lens column: over the
     *  finger, kept inside the card region (lens-local coords), not the
     *  chip-halo padding. */
    private fun arrowRelX(): Int =
        (lastFingerX - lastLensX).coerceIn(chipHaloXPx + arrowSizePx, chipHaloXPx + lastCardW - arrowSizePx)

    /** Slide the lens column to the finger's x, inboard of what the window
     *  hears ([columnRange]); a sticky card takes its arrow along. The one
     *  placement of the column: [show] runs it for a finger move, and the
     *  root runs it when its insets change ([LensRoot.onInsetsChanged]),
     *  which covers the first dispatch, which lands after the reveal has
     *  placed the card, and a side bar the game shows or hides under a
     *  sticky card (Codex reviews, 2026-10-11: the first cut clamped only in
     *  show, and the test covering it needed a second show to see the
     *  effect, the trigger a real bar change never supplies). The window is
     *  fixed full-screen, so this is a pure view translation: no
     *  updateViewLayout, no flash. */
    private fun placeColumn() {
        val root = lensRoot ?: return
        val view = lensView ?: return
        val p = params ?: return
        val viewW = lastCardW + 2 * chipHaloXPx
        lastLensX = (lastFingerX - viewW / 2).coerceIn(columnRange(root, p.width, viewW))
        root.setLensX(lastLensX)
        if (view.isInteractive) view.setArrowVisible(true, arrowRelX())
    }

    /** The bounds moved under a sticky card (a bar the window hears came
     *  or went): re-fit the card to them, growing into room that appeared
     *  or shrinking out from under a bar that now covers its far edge. The
     *  content-driven [fitHeightToContent] only grows, since content
     *  shrinking under a reader is jitter; a bound shrinking is a cover.
     *  The anchored edge stays on the word, as it does for every grow. */
    private fun refitToBounds() {
        val view = lensView ?: return
        if (!view.isInteractive) return
        val target = grownCardHeight(view)
        if (target == lensCardHeight) return
        animateCardGrowInside(view, target)
    }

    /** Animate the card body from [lensCardHeight] to [target] INSIDE the fixed
     *  full-height window — never a window resize (which would flash the overlay
     *  at its gravity anchor). Not flipped: the card is bottom-anchored (its top
     *  rides up as it grows) so the arrow stays on the word; flipped: the card
     *  top is fixed and it grows downward.
     *
     *  The children are laid out ONCE at the end-state ([LensView.beginCardGrow])
     *  and each animated frame is draw-only ([LensView.setGrowFrame]) — a
     *  per-frame relayout re-measures the whole dictionary subtree, which on
     *  long entries stalls the main thread mid-grow. */
    private fun animateCardGrowInside(view: LensView, target: Int) {
        heightAnimator?.cancel()
        applyCardFrame(view, lensCardHeight)
        val finalTop = if (!lastFlipped) anchoredEdgeScreenY - target else anchoredEdgeScreenY
        view.beginCardGrow(target, finalTop)
        heightAnimator = ValueAnimator.ofInt(lensCardHeight, target).apply {
            duration = 180L
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { anim ->
                val h = anim.animatedValue as Int
                lensCardHeight = h
                val top = if (!lastFlipped) anchoredEdgeScreenY - h else anchoredEdgeScreenY
                view.setGrowFrame(h, top)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    view.endCardGrow()
                }
            })
            start()
        }
    }

    /** Place the card of height [h] inside the fixed window via its screen-space
     *  top: bottom-anchored when not flipped (top = anchoredEdge − h, so the
     *  arrow stays on the word), top-anchored (fixed top, grows down) flipped. */
    private fun applyCardFrame(view: LensView, h: Int) {
        val cardTop = if (!lastFlipped) anchoredEdgeScreenY - h else anchoredEdgeScreenY
        view.setCardGeometry(h, cardTop)
    }

    /** Window flags for the lens in its non-interactive (zoom) state. On the
     *  MediaProjection backend the window must stay touchable: a non-touchable
     *  TYPE_APPLICATION_OVERLAY is opacity-capped by the anti-tapjacking rule
     *  and renders washed out. The drag gesture is owned by the floating
     *  icon's window, so a touchable lens window does not steal it. */
    private fun zoomWindowFlags(): Int {
        // HARDWARE_ACCELERATED: only read at addView time (OverlayHost also
        // stamps it centrally), carried here so the wholesale flag rewrites
        // in resetToZoom/makeInteractive keep params honest — and so the
        // non-host (in-app activity window) path gets it too. The styled
        // definitions panel hosts a WebView, which needs the HW pipeline.
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        if (overlayHost?.windowType != WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        return flags
    }

    fun resetToZoom() {
        val view = lensView ?: return
        val root = lensRoot ?: return
        val p = params ?: return
        // Back to the base-height zoom card for the next drag.
        heightAnimator?.cancel()
        lensCardHeight = lensH
        view.setCardHeight(lensH)
        // No longer sticky: stop catching off-card taps. Window stays full-
        // screen (never resized); just revert the flags. The following show()
        // repositions the base card for the new drag.
        root.interactive = false
        root.onOffCardDown = null
        root.onOutside = null
        p.flags = zoomWindowFlags()
        try { wm.updateViewLayout(root, p) } catch (_: Exception) {}
        view.detachInteractiveListeners()
        view.setDefinitions(null, null, opens = false)
        view.setLabel(null, null)
        view.setSourceBitmap(null)
        view.setArrowVisible(false, 0)
    }

    /** Card width — the visible rounded panel — under the existing
     *  responsive rule: `min(screenW × 0.85, 380dp)`. The host view is
     *  wider by 2 × [chipHaloXPx] so the chip hit halos can render. */
    private fun cardWidth(screenW: Int): Int =
        (screenW * 0.85f).toInt().coerceAtMost(dp(380f))

    /**
     * Position and show the lens.
     *
     * [fingerY] is the anchor's TOP edge in screen coords. [anchorHeight] is
     * the anchor's height — for the drag flow the anchor is a finger point so
     * 0 is right, but for tap-on-a-word callers the anchor is a line of text
     * with a real height, and the lens needs that to land cleanly below the
     * line in the flipped case (otherwise "below" means below the line's top,
     * which lands on the line itself).
     *
     * Flip decision: prefer above when the lens fits with [flipBiasPx] of
     * headroom to spare, fall back to below otherwise. In drag mode we
     * tolerate a small overhang past the screen top because the user's
     * finger is on-screen and they can see what happens; in activity-window
     * mode there's a status bar to worry about, so we require the lens
     * window — including the pill/chip overhang — to fully fit before
     * staying above.
     */
    fun show(
        fingerX: Int,
        fingerY: Int,
        screenW: Int,
        screenH: Int,
        anchorHeight: Int = 0,
    ): Boolean {
        // If the user changed the theme (mode or accent) between drags,
        // the cached LensView still carries the old colors. Tear it down
        // silently so ensureWindow rebuilds with the new attrs. Silent =
        // does NOT fire onDismiss (the user didn't dismiss; we're just
        // reconfiguring under the hood).
        val themeKey = currentThemeKey()
        // The window is sized to the screen it was built for ([ensureWindow]).
        // A show for another screen gets a window of that size: one of the
        // old size would catch off-card taps on only part of the new screen
        // and place the card past its edge. The owner ends a session whose
        // display changed ([DragLookupController.onDisplayChanged], and its
        // capture checks the display when the frame returns), so this is
        // reached by a show that lands between the change and that event.
        // Same silent rebuild as the theme change: no onDismiss, the user
        // dismissed nothing.
        val builtSize = params?.let { it.width to it.height }
        val rebuilt = lensView != null && (cachedThemeKey != themeKey || builtSize != (screenW to screenH))
        if (rebuilt) removeOverlayInternal()
        cachedThemeKey = themeKey

        val cardW = cardWidth(screenW)
        val viewW = cardW + 2 * chipHaloXPx
        ensureWindow(cardW, viewW, screenW, screenH)
        val view = lensView
        val root = lensRoot
        if (view == null || root == null) {
            // No window: the add was refused (a revoked overlay permission, a
            // display that went away). A rebuild that lost a working window
            // reports it as a dismissal the user did not make, so the
            // owner's lens state clears (its drag, if one is under way,
            // carries on: [DragLookupController.lensDismissed]); a first
            // show that fails has nothing to report. Either way the caller
            // reads the return, and a session that reaches its release with
            // no lens settles itself ([DragLookupController.endSession]).
            android.util.Log.w(
                "MagnifierLens",
                "lens window did not attach at ${screenW}x$screenH (rebuilt=$rebuilt)",
            )
            if (rebuilt) onDismiss?.invoke()
            return false
        }

        val aboveY = fingerY - verticalMarginPx - lensH
        // Activity-window callers (tap-on-word in the result screen) need the
        // pill/chip overhang to fully clear the screen top — there's a status
        // bar in the way. Drag callers can tolerate a small overhang past the
        // screen top (the user is actively pointing at the screen and the
        // chip's exact position isn't load-bearing).
        val flipThreshold =
            if (useActivityWindow) pillChipOverhangPx else -topOverhangTolerancePx
        val flipped = aboveY < flipThreshold + flipBiasPx
        view.setSourcePoint(fingerX.toFloat(), fingerY.toFloat(), screenW, screenH)
        view.setLensFlipped(flipped)
        root.isVisible = true

        lastFingerX = fingerX
        lastFlipped = flipped
        lastScreenH = screenH
        // A fresh show is always the base-height zoom card; undo any grow
        // left over from a prior sticky lens being reused for a new drag.
        heightAnimator?.cancel()
        lensCardHeight = lensH

        // The card's TOP in screen coords. The window spans the full screen at
        // y = 0, so within it the card top sits at this same Y (card-top override).
        val lensBodyY = if (!flipped) {
            aboveY
        } else {
            (fingerY + anchorHeight + verticalMarginPx)
                .coerceAtMost((screenH - lensH).coerceAtLeast(0))
        }
        // Anchored card edge (the arrow/finger edge that stays put when the
        // card grows on release): card bottom above the finger, card top when
        // flipped below it.
        anchoredEdgeScreenY = if (!flipped) lensBodyY + lensH else lensBodyY
        view.setCardGeometry(lensH, lensBodyY)

        // Slide the lens column to follow the finger horizontally; the window
        // itself is fixed full-screen (never moves or resizes), so this is a
        // pure view translation inside the root — no updateViewLayout, no flash.
        lastCardW = cardW
        placeColumn()
        view.invalidate()
        return true
    }

    fun hide() {
        lensRoot?.visibility = View.INVISIBLE
    }

    /** Dismiss the lens and fire [onDismiss]. Returns false when there was
     *  no window to dismiss and nothing fired: an owner ending its session
     *  settles itself then ([DragLookupController.endSession]). */
    fun dismiss(): Boolean {
        if (lensView == null) return false
        removeOverlayInternal()
        onDismiss?.invoke()
        return true
    }

    /** Remove the overlay + reset state WITHOUT firing onDismiss. Used by
     *  the dismiss() user path (which then fires the callback) and by
     *  the theme-change rebuild in [show] (which must not look like a
     *  user-initiated dismissal). */
    private fun removeOverlayInternal() {
        val root = lensRoot ?: return
        // Full interactive teardown BEFORE the window goes — this is the one
        // removal path every dismissal funnels through, and the stick-scroll
        // Choreographer callback re-posts itself: without this, dismissing
        // mid-deflection leaves it running per-frame against the detached
        // view (holding it) forever. resetToZoom() detaches separately; a
        // second detach here is idempotent.
        lensView?.detachInteractiveListeners()
        // The definitions page's WebView must be destroy()ed, not just
        // dropped with the window: a removed-but-undestroyed WebView holds
        // renderer resources and its context graph until GC, so repeated
        // lens open/dismiss churn would accumulate the most expensive
        // object in the app.
        lensView?.releaseDefinitions()
        heightAnimator?.cancel()
        heightAnimator = null
        lensCardHeight = lensH
        lensView = null
        lensRoot = null
        val p = params
        params = null
        val remove: () -> Unit = {
            if (overlayHost != null) {
                overlayHost.removeOverlayWindow(root)
            } else {
                // Activity-window lens: deliberately NOT gated through
                // WindowChurnGate — a destroy deferred past the hosting
                // activity's finish would trip the framework's window-leak
                // detection. Its add still ticks the gate's quiet clock.
                try { wm.removeView(root) } catch (_: Exception) {}
            }
        }
        // Orphaned-UP guard: a key that went down on this focused window is
        // still held (the Anki one-tap long-press fires at the timeout and
        // dismisses mid-press) — the window must outlive its UI as an
        // invisible, untouchable key sink until the release lands here, or
        // the UP would be delivered unmatched to whatever window is focused
        // next (the game, in the drag flow). See [WindowKeyPairGuard].
        if (root.keyGuard.hasPendingDown && p != null) {
            root.visibility = View.INVISIBLE
            p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            try { wm.updateViewLayout(root, p) } catch (_: Exception) {}
            root.keyGuard.beginLinger(remove)
        } else {
            remove()
        }
    }

    private fun ensureWindow(cardW: Int, viewW: Int, screenW: Int, windowH: Int) {
        if (lensView != null) return
        // Build the themed context fresh on each window construction so
        // it reflects the user's current mode + accent. Caching this at
        // MagnifierLens construction is what caused the lens to ignore
        // theme changes — the floating-icon menu sidesteps the same
        // issue by being reconstructed every time it's shown.
        val themedCtx = overlayThemedContext(rawCtx)
        val view = LensView(
            ctx = themedCtx,
            cardW = cardW,
            viewW = viewW,
            lensH = lensH,
            chipHaloXPx = chipHaloXPx,
            pillChipOverhangPx = pillChipOverhangPx,
            cardCornerR = cardCornerR,
            zoom = zoom,
            arrowSizePx = arrowSizePx,
            pillHeightPx = pillHeightPx,
            chipVisDiameterPx = chipVisDiameterPx,
            chipHitSizePx = chipHitSizePx,
            chipHaloPadPx = chipHaloPadPx,
            density = density,
            onOpenTap = { onOpenTap?.invoke() },
            onSecondaryOpenTap = { i -> onSecondaryOpenTap?.invoke(i) },
            onLoadMoreTap = { onLoadMoreTap?.invoke() },
            onAnkiTap = { onAnkiTap?.invoke() },
            onAnkiLongPress = { onAnkiLongPress?.invoke() },
            onSpeakTap = { onSpeakTap?.invoke() },
            showAnkiChip = showAnkiChip,
            onBodyHeightChanged = { fitHeightToContent() },
        )
        val root = LensRoot(themedCtx, view, viewW)
        root.onInsetsChanged = {
            placeColumn()
            refitToBounds()
        }
        val windowType = if (useActivityWindow)
            WindowManager.LayoutParams.TYPE_APPLICATION_PANEL
        else
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        // Full-screen window, never moved or resized: the card is positioned and
        // grown INSIDE it (resizing a live overlay flashes it at its gravity
        // anchor), and a tap anywhere off the card is caught + consumed so it
        // dismisses without leaking to the app/game beneath. The card follows
        // the finger via [LensRoot]'s internal lens translation, not the window.
        val lp = WindowManager.LayoutParams(
            screenW, windowH,
            windowType,
            zoomWindowFlags(),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        val attached = if (overlayHost != null) {
            overlayHost.addOverlayWindow(root, wm, lp, displayId, OverlayPlacement.Content)
        } else {
            try {
                wm.addView(root, lp)
                true
            } catch (_: Exception) { false }
        }
        if (!attached) return
        lensView = view
        lensRoot = root
        params = lp
        if (definitionsWanted) view.prepareDefinitions()
        // A bitmap attached before this view existed (deferred-reveal scene
        // flow) applies now; null is a harmless no-op-equivalent.
        view.setSourceBitmap(sourceBitmapForShow)
    }

    /**
     * Full-screen, transparent host that owns the lens window. [lensView] (the
     * card-width view that actually paints) is its only child, slid
     * horizontally inside it to follow the finger. The window spans the whole
     * display for two reasons: the card can grow on release without ever
     * resizing the window (resizing a live overlay flashes it at its gravity
     * anchor on some devices), and a tap anywhere off the card is caught and
     * consumed — dismissing the lens without leaking that tap to the app/game
     * behind it.
     */
    private class LensRoot(
        ctx: Context,
        val lens: LensView,
        private val lensW: Int,
    ) : FrameLayout(ctx) {
        /** True once the lens is sticky; off-card taps dismiss only then. */
        var interactive = false
        /** Invoked with the DOWN's screen position when a sticky-mode DOWN
         *  lands left/right of the lens column. (Taps inside the column but
         *  off the chrome band go through [lens]'s own dismiss request; all
         *  are consumed by this full-screen window.) */
        var onOffCardDown: ((rawX: Float, rawY: Float) -> Unit)? = null
        /** Invoked when a touch lands outside the window altogether
         *  (FLAG_WATCH_OUTSIDE_TOUCH). */
        var onOutside: (() -> Unit)? = null
        /** Controller B / back while the sticky lens holds window focus (it
         *  goes focusable when a controller is attached — [makeInteractive]).
         *  A separate hook from [onOffCardDown]: same dismissal, different
         *  intent. */
        var onDismissKey: (() -> Unit)? = null
        /** The insets last dispatched to this window, the one source for
         *  what the card keeps clear of: the system bars this window is
         *  dispatched (below them on the MediaProjection backend; an
         *  accessibility overlay lies above them and hears them only on
         *  Android 11, which sends every bar to every window) and the
         *  display cutout, which the host lays the window into. Null
         *  before the first dispatch, when the card clamps to the screen. */
        var windowInsets: WindowInsetsCompat? = null
            private set
        /** Fired when the bars or cutout this window hears changed, the
         *  first dispatch included (it lands after the reveal placed the
         *  card): the owner re-places the column and re-fits a sticky
         *  card. Not for every dispatch: a relayout of our own repeats the
         *  same insets. */
        var onInsetsChanged: (() -> Unit)? = null

        override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
            val next = WindowInsetsCompat.toWindowInsetsCompat(insets, this)
            val before = windowInsets?.getInsets(WATCHED_INSETS)
            windowInsets = next
            if (before != next.getInsets(WATCHED_INSETS)) onInsetsChanged?.invoke()
            return super.onApplyWindowInsets(insets)
        }

        private companion object {
            val WATCHED_INSETS = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        }

        /** True between a consumed B DOWN and its UP, so dismissal fires only
         *  for a press that began on this window. */
        private var backDownSeen = false

        /** Orphaned-UP guard: pairs tracked here, and the dismissal seam
         *  ([removeOverlayInternal]) lingers this window as an invisible key
         *  sink while a press is still in flight. */
        val keyGuard = WindowKeyPairGuard()

        override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
            if (keyGuard.isLingering) return keyGuard.lingerKey(ev)
            keyGuard.track(ev)
            if (interactive && ControllerKeys.isBack(ev.keyCode)) {
                // Fire on UP, not DOWN: dismissing removes this FOCUSED window,
                // and acting on the DOWN would orphan the UP into whatever sits
                // beneath — the game itself in the drag-lookup flow. Release-
                // to-dismiss is also the console idiom. The down-seen gate
                // keeps a B held from before the lens appeared from dismissing
                // it on release.
                when (ev.action) {
                    KeyEvent.ACTION_DOWN -> if (ev.repeatCount == 0) backDownSeen = true
                    KeyEvent.ACTION_UP -> if (backDownSeen) {
                        backDownSeen = false
                        onDismissKey?.invoke()
                    }
                }
                return true
            }
            if (interactive && lens.handleNavKey(ev)) return true
            return super.dispatchKeyEvent(ev)
        }

        init {
            // Transparent: only [lens] paints. The rest of the screen-sized
            // root is an invisible touch catcher.
            background = null
            // Absolute LEFT, not relative START: this is a root WindowManager
            // window, so ViewRootImpl stamps the configuration's layout
            // direction on it and an Arabic system locale turns it RTL — where
            // START resolves to RIGHT and lays the column out at
            // `viewW - lensW` before [setLensX]'s translationX is even added.
            // That also desynchronises [dispatchTouchEvent], which hit-tests
            // the column as `lens.translationX ..+ lensW` on the assumption
            // that lens.left is 0.
            addView(
                lens,
                LayoutParams(lensW, LayoutParams.MATCH_PARENT, Gravity.TOP or Gravity.LEFT),
            )
        }

        /** Horizontal offset of the lens column within the full-screen root. */
        fun setLensX(x: Int) {
            lens.translationX = x.toFloat()
        }

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (interactive) {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        val left = lens.translationX
                        if (ev.x < left || ev.x >= left + lensW) {
                            onOffCardDown?.invoke(ev.rawX, ev.rawY)
                            return true   // consume: the tap dismisses; it does not pass through
                        }
                    }
                    // A touch outside this window (FLAG_WATCH_OUTSIDE_TOUCH):
                    // on a navigation bar, which sits above an application
                    // overlay, or past the window's frame. The notification
                    // reaches the window's root, and a ViewGroup hands a
                    // non-DOWN event to no child while it has no touch target,
                    // so a listener on [lens] never saw one.
                    MotionEvent.ACTION_OUTSIDE -> {
                        onOutside?.invoke()
                        return true
                    }
                }
            }
            return super.dispatchTouchEvent(ev)
        }
    }

    /**
     * Card-width view for the redesigned lens. Sits inside [LensRoot] and is
     * slid horizontally to follow the finger; vertically the card is placed at
     * the screen Y given by [bodyTopOffset] and grows there. The pill/chip halo
     * overhangs one card edge and the arrow strip the other (top/bottom swap
     * with the flip state).
     *
     * Card panel (background + border) is painted on canvas in [draw] so we
     * can clip the zoomed pixels and the inset shadow to the rounded-rect
     * shape. Pill + chips are real child views overlaid on top.
     */
    private class LensView(
        ctx: Context,
        private val cardW: Int,
        private val viewW: Int,
        private val lensH: Int,
        private val chipHaloXPx: Int,
        private val pillChipOverhangPx: Int,
        private val cardCornerR: Float,
        private val zoom: Float,
        private val arrowSizePx: Int,
        private val pillHeightPx: Int,
        private val chipVisDiameterPx: Int,
        private val chipHitSizePx: Int,
        private val chipHaloPadPx: Int,
        private val density: Float,
        private val onOpenTap: () -> Unit,
        private val onSecondaryOpenTap: (Int) -> Unit,
        private val onLoadMoreTap: () -> Unit,
        private val onAnkiTap: () -> Unit,
        private val onAnkiLongPress: () -> Unit,
        private val onSpeakTap: () -> Unit,
        private val showAnkiChip: Boolean,
        /** The body the card should fit changed height (the definitions
         *  page reported a layout). The owner re-runs its card-height fit,
         *  which is grow-only, so calling it when the body already fits is
         *  a no-op (LensView is not an inner class). */
        private val onBodyHeightChanged: () -> Unit,
    ) : FrameLayout(ctx) {
        private fun dp(v: Float): Int = (density * v).toInt()
        /** sp → px through the display metrics, so the pill's fit measures text
         *  the way [TextView.setTextSize] will render it. [density] alone
         *  ignores the user's font scale and under-measures every string on a
         *  large-text device — which lets the fitted pill overrun its cap. */
        private fun spPx(v: Float): Float =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
        /** Replace the alpha byte of [color] with [alpha] (0..255). Used to
         *  layer the spec's design alphas onto themed RGB tokens — e.g.
         *  the card border is the theme's primary-text color at 16%. */
        private fun withAlpha(color: Int, alpha: Int): Int =
            (color and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)
        private val cardBorderPx = density * 1f

        private enum class Mode { ZOOM, DEFINITIONS, LOADING }
        private var mode: Mode = Mode.ZOOM

        private var lensFlipped = false
        /** Current card-body height. Defaults to [lensH] (the zoom card and
         *  every drag/dwell surface stay at the base height); the controller
         *  grows it via [setCardHeight] after release so the dictionary can
         *  show more rows than the base 120dp allows. The grown card never
         *  appears in ZOOM mode, so the ZOOM-only chrome (inset shadow, clip
         *  path, zoom blit, crosshair) intentionally stays pinned to [lensH]. */
        private var cardHeightPx: Int = lensH
        /** Override for the card's top edge within the view. Non-null only
         *  while the card grows inside a pre-sized window (the not-flipped
         *  case, where the card is bottom-anchored, so its top must ride up
         *  as it grows rather than sitting at the fixed pill/chip overhang).
         *  Null restores the flip-default. */
        private var bodyTopOverride: Int? = null
        /** Y of the card's top edge within the view. */
        private val bodyTopOffset: Int
            get() = bodyTopOverride ?: if (lensFlipped) arrowSizePx else pillChipOverhangPx
        private val cardBottomInView: Int get() = bodyTopOffset + cardHeightPx
        /** Y of the line the pill is centered on — the card edge opposite
         *  the arrow (top when not flipped, bottom when flipped). */
        private val pillAnchorY: Int
            get() = if (lensFlipped) cardBottomInView else bodyTopOffset
        private val cardLeftInView: Int get() = chipHaloXPx
        private val cardRightInView: Int get() = chipHaloXPx + cardW
        /** Vertical extent of the lens chrome (card body + the pill/chip
         *  overhang and arrow on its two edges) within the full-height window.
         *  Taps outside this band — the transparent area above/below the lens —
         *  dismiss in sticky mode. */
        private val contentTopInView: Int
            get() = bodyTopOffset - if (lensFlipped) arrowSizePx else pillChipOverhangPx
        private val contentBottomInView: Int
            get() = cardBottomInView + if (lensFlipped) pillChipOverhangPx else arrowSizePx

        private var arrowVisible = false
        private var arrowOffsetX = 0

        // Every color comes from the theme stack (resolved through the
        // ContextThemeWrapper [MagnifierLens] built around the raw service
        // context). The design's hex tokens — BWI_PANEL #15181B, the
        // white-16% border, the brand #8B3F2D arrow — are the dark theme
        // pt_* palette in disguise; using R.attr.pt* keeps the lens
        // tracking the user's mode + accent instead of pinning to coral.
        private val accentColor = ctx.themeColor(R.attr.ptAccent)
        private val accentOnColor = ctx.themeColor(R.attr.ptAccentOn)
        private val cardBgColor = ctx.themeColor(R.attr.ptSurface)
        private val cardBorderColor = ctx.themeColor(R.attr.ptOutline)
        // Sticky-mode arrow's fill matches the card panel so the
        // triangle reads as a contiguous extension of the card. The
        // two slanted edges are stroked with the same card border so
        // the outline wraps the arrow continuously with the panel.
        // The base is left unstroked — it sits on the card edge and
        // the card border carries through underneath.
        private val chipBgColor = withAlpha(ctx.themeColor(R.attr.ptSurface), 240)  // 0.94
        private val chipBorderColor = withAlpha(ctx.themeColor(R.attr.ptText), 56)  // 0.22
        private val chipIconColor = withAlpha(ctx.themeColor(R.attr.ptText), 209)  // 0.82
        // Pill ink alphas mirror the spec (1.0 / 0.22 / 0.72 / 0.5) applied
        // over the accent-paired ink color so a non-coral accent still
        // gets readable ink on its pill.
        private val pillInkColor = accentOnColor
        private val pillInkDivider = withAlpha(accentOnColor, 0x38)
        private val pillInkReading = withAlpha(accentOnColor, 0xB8)
        private val panelPrimaryText = ctx.themeColor(R.attr.ptText)
        private val panelSecondaryText = ctx.themeColor(R.attr.ptTextMuted)
        // Badge uses ptCard (one step lighter than the lens panel's
        // ptSurface) so it visibly separates from the body behind it.
        private val panelBadgeBg = ctx.themeColor(R.attr.ptCard)
        private val panelWarnColor = ctx.themeColor(R.attr.ptWarning)

        private val cardBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = cardBgColor
            style = Paint.Style.FILL
        }
        private val cardBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = cardBorderColor
            style = Paint.Style.STROKE
            strokeWidth = cardBorderPx
        }
        private val backgroundPaint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }
        private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val arrowFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = cardBgColor
            style = Paint.Style.FILL
        }
        private val arrowPath = Path()
        private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentColor
            style = Paint.Style.STROKE
            strokeWidth = density * 1.5f
            strokeCap = Paint.Cap.ROUND
        }
        private val crosshairHalfLen = density * 6f

        /** Soft inner shadow that recesses the zoom under the card border.
         *  Pre-rendered into a software-allocated ARGB bitmap because the
         *  BlurMaskFilter only produces its blur on a software canvas — by
         *  baking the shadow once here, the host view can stay on the
         *  default (hardware-accelerated) layer pipeline, which was the
         *  source of the lightly-translucent ghost that previously
         *  followed the lens during drag. */
        private val insetShadowBitmap: Bitmap = run {
            val bitmap = createBitmap(cardW, lensH, Bitmap.Config.ARGB_8888)
            val bmCanvas = Canvas(bitmap)
            val shadowClip = Path().apply {
                addRoundRect(
                    0f, 0f, cardW.toFloat(), lensH.toFloat(),
                    cardCornerR, cardCornerR, Path.Direction.CW,
                )
            }
            bmCanvas.clipPath(shadowClip)
            val inset = density * 4f
            val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(45, 0, 0, 0)
                style = Paint.Style.STROKE
                strokeWidth = density * 14f
                maskFilter = BlurMaskFilter(density * 8f, BlurMaskFilter.Blur.NORMAL)
            }
            bmCanvas.drawRoundRect(
                inset, inset, cardW - inset, lensH - inset,
                cardCornerR - inset, cardCornerR - inset,
                shadowPaint,
            )
            bitmap
        }

        // -----------------------------------------------------------------
        // Pill: word | reading
        // -----------------------------------------------------------------
        private val pillPaddingLead = dp(18f)
        private val pillPaddingTrail = dp(14f)
        private val pillGap = dp(12f)
        private val pillDividerWidth = dp(2f)
        private val pillDividerHeight = dp(22f)
        private val pillPlaceholderIconSize = dp(22f)
        private val pillPlaceholderGap = dp(8f)
        private val pillPlaceholderText = "Find a word"
        private val pillWordSp = 24f
        private val pillReadingMaxSp = 14f
        private val pillReadingMinSp = 11f
        /** Placeholder icon shown when the finger isn't over a token. */
        private val pillPlaceholderIconView = ImageView(ctx).apply {
            val d = AppCompatResources.getDrawable(ctx, R.drawable.ic_lens_search)?.mutate()
            if (d != null) {
                DrawableCompat.setTint(d, pillInkColor)
                setImageDrawable(d)
            }
            val params = LinearLayout.LayoutParams(pillPlaceholderIconSize, pillPlaceholderIconSize)
            // Pull the placeholder icon + label 8dp left of the leading
            // padding so the icon's left edge optically aligns with the
            // word-state's leading edge. The pill turns off
            // clipToPadding so this negative inset is honored (otherwise
            // the icon would be cut off at the padding boundary).
            params.marginStart = -dp(8f)
            params.marginEnd = pillPlaceholderGap
            layoutParams = params
            visibility = GONE
        }
        /** Spinner shown alone in the pill while the drag-start OCR job
         *  runs — no accompanying label. The icon's negative leading inset
         *  (for optical alignment with the Word state's leading edge) is
         *  intentionally omitted: the spinner stands alone, so the pill's
         *  natural padding centers it cleanly. */
        private val pillPlaceholderSpinnerView = ProgressBar(ctx).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(pillInkColor)
            layoutParams = LinearLayout.LayoutParams(pillPlaceholderIconSize, pillPlaceholderIconSize)
            visibility = GONE
        }
        /** Placeholder label paired with [pillPlaceholderIconView]. */
        private val pillPlaceholderTextView = TextView(ctx).apply {
            text = pillPlaceholderText
            setTextColor(pillInkColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, pillReadingMaxSp)
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            visibility = GONE
        }
        private val pillWordView = TextView(ctx).apply {
            setTextColor(pillInkColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, pillWordSp)
            typeface = Typeface.DEFAULT_BOLD
            // CSS letter-spacing 0.3 at 24px ≈ 0.0125 em.
            letterSpacing = 0.0125f
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        private val pillDividerView = View(ctx).apply {
            setBackgroundColor(pillInkDivider)
            val params = LinearLayout.LayoutParams(pillDividerWidth, pillDividerHeight)
            params.marginStart = pillGap
            params.marginEnd = pillGap
            layoutParams = params
        }
        private val pillReadingView = TextView(ctx).apply {
            setTextColor(pillInkReading)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, pillReadingMaxSp)
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        private val pillView = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pillPaddingLead, 0, pillPaddingTrail, 0)
            // Allow the placeholder icon's negative marginStart to draw
            // into the leading padding area instead of being clipped at
            // the padding boundary.
            clipToPadding = false
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(accentColor)
                // Hairline outline matching the card border so the pill
                // reads as part of the same family.
                setStroke(density.toInt().coerceAtLeast(1), cardBorderColor)
                // 99dp in spec; equivalent to "fully rounded capsule" — i.e.
                // corner radius >= half the pill height.
                cornerRadius = pillHeightPx / 2f
            }
            // Placeholder children added first so they pack to the leading
            // edge when shown; they're GONE in word state, leaving the
            // word/divider/reading to take their place.
            addView(pillPlaceholderIconView)
            addView(pillPlaceholderSpinnerView)
            addView(pillPlaceholderTextView)
            addView(pillWordView)
            addView(pillDividerView)
            addView(pillReadingView)
            // Pill is hidden until [setLabel] applies a state (placeholder
            // or word) on the controller's first label call after show().
            visibility = GONE
        }
        // Manual sizing for the reading: shrink 1sp at a time down to 11sp
        // so a long reading still fits inside the pill, rather than
        // ellipsizing or pushing the capsule past its width cap. Word
        // stays at 24sp because the spec considers the word the headline.
        private val pillWordSizingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.0125f
        }
        private val pillReadingSizingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT_BOLD
        }

        // -----------------------------------------------------------------
        // Chips: Speak (left) and Anki (right) route through the lens's
        // [onSpeakTap] / [onAnkiTap] callbacks — wired in [DragLookupController].
        // -----------------------------------------------------------------
        /** Gap between the chip's visible disk and the pill's outer edge
         *  in the resting (post-reveal) layout. */
        private val chipPillGapPx = dp(14f)
        /** Horizontal space one chip claims beside the pill: the outer half of
         *  its hit halo, its visible disk, and the gap to the pill. */
        private val chipLanePx = chipHaloPadPx + chipVisDiameterPx + chipPillGapPx
        /** Hard cap on the pill's width — what's left of the host view once
         *  both chip lanes are reserved. [fitPillText] sizes and bounds the
         *  pill's text against it, which is what keeps [revealChips] able to
         *  seat both chips inside the frame however long the word is. */
        private val pillMaxWidthPx = computeMaxPillWidth(viewW, chipLanePx)

        // Speak chip's loading spinner — swapped in for the icon while a TTS
        // request is in flight (see [setSpeakChipLoading]).
        private val leftChipSpinner = ProgressBar(
            context, null, android.R.attr.progressBarStyleSmall,
        ).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(chipIconColor)
            layoutParams = LayoutParams(dp(16f), dp(16f), Gravity.CENTER)
            visibility = GONE
        }
        private val leftChipIcon = makeChipIcon(R.drawable.ic_lens_speak)
        private val leftChip = makeChip(
            { onSpeakTap() },
            null,
            leftChipIcon, leftChipSpinner,
        )
        // Anki chip: tap fires onAnkiTap (which one-tap or sheet-open),
        // long-press always fires onAnkiLongPress (which opens the
        // editable review sheet) — wired to whichever callback the
        // surrounding flow supplies.
        private val rightChip = makeChip(
            { onAnkiTap() },
            { onAnkiLongPress() },
            makeChipIcon(R.drawable.ic_card_stack_add),
        )

        /** Build a chip: a clickable disk with [content] views centered on it.
         *  Content (icon, optional spinner) is supplied so the caller can keep
         *  references for state swaps. [onLongClick], when non-null, fires
         *  on long-press and consumes the gesture (returns true). */
        private fun makeChip(
            onClick: () -> Unit,
            onLongClick: (() -> Unit)? = null,
            vararg content: View,
        ): FrameLayout {
            val chip = FrameLayout(context).apply {
                isClickable = true
                setOnClickListener { onClick() }
                if (onLongClick != null) {
                    setOnLongClickListener {
                        onLongClick()
                        true
                    }
                }
                visibility = GONE
            }
            val disk = View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(chipBgColor)
                    setStroke(density.toInt().coerceAtLeast(1), chipBorderColor)
                }
                layoutParams = LayoutParams(
                    chipVisDiameterPx, chipVisDiameterPx,
                    Gravity.CENTER,
                )
            }
            chip.addView(disk)
            content.forEach { chip.addView(it) }
            return chip
        }

        private fun makeChipIcon(iconRes: Int): ImageView = ImageView(context).apply {
            val d = AppCompatResources.getDrawable(context, iconRes)?.mutate()
            if (d != null) {
                DrawableCompat.setTint(d, chipIconColor)
                setImageDrawable(d)
            }
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            layoutParams = LayoutParams(dp(16f), dp(16f), Gravity.CENTER)
        }

        /** Swap the Speak chip between its icon and a loading spinner, shown
         *  while a TTS request is in flight. */
        fun setSpeakChipLoading(loading: Boolean) {
            leftChipIcon.visibility = if (loading) GONE else VISIBLE
            leftChipSpinner.visibility = if (loading) VISIBLE else GONE
        }

        // -----------------------------------------------------------------
        // Body: definitions ScrollView, occupies the full card area.
        //   - Top padding clears the pill so the first row doesn't start
        //     under it. With `clipToPadding = false`, scrolling reveals
        //     the content sliding under the pill (the pill is drawn on
        //     top of the ScrollView in the FrameLayout z-order).
        //   - Horizontal padding sits on the inner `definitionsContent`
        //     so the scrolling surface itself extends to the card's
        //     left and right edges, while the rows stay inset.
        // -----------------------------------------------------------------
        private val bodyHPaddingPx = dp(18f)
        private val bodyPillSidePadPx = dp(26f)
        private val bodyOuterSidePadPx = dp(12f)
        /** Tiny buffer between the scroll view and the card's inner
         *  edges on all four sides, so the rounded corners don't graze
         *  the scrollbar / content edges, and scrolled glyphs clip
         *  short of the 1dp card border instead of overdrawing it. */
        private val bodyEdgeBufferPx = dp(2f)
        /** The LOADING body: a spinner and "Looking up…"
         *  ([populateLoading]). Native, so it shows the instant a lookup
         *  starts, whether or not a page is loaded yet. */
        private val loadingContent = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(bodyHPaddingPx - dp(6f), 0, bodyHPaddingPx + dp(2f), 0)
            visibility = GONE
        }

        /** Where the definitions document's view lives. */
        private val documentSlot = FrameLayout(ctx)

        /** The definitions: ONE document for whatever the lens shows of a
         *  lookup (the word alone, or the word with its phrase, its member
         *  words and its other entries, and the "Load more" row), each a
         *  row whose tap the page reports with the row it landed in. The
         *  document never scrolls itself: [definitionsScroll] stays the one
         *  scroller, so the stick scroll and the grow animation are
         *  untouched. Its jobs run on [docScope], cancelled with the window
         *  ([releaseDefinitions]). */
        private val docScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val lensDoc = DefinitionDocument(
            ctx, documentSlot, docScope,
            theme = {
                DefinitionsDocument.DocTheme(
                    DefinitionsDocument.Tokens(
                        text = context.themeColor(R.attr.ptText),
                        textMuted = context.themeColor(R.attr.ptTextMuted),
                        textHint = context.themeColor(R.attr.ptTextHint),
                        accent = accentColor,
                        panel = context.themeColor(R.attr.ptSurface),
                        baseFontSizePx = 16.5f * LENS_DEFINITIONS_SCALE,
                    ),
                    divider = cardBorderColor,
                    band = 0,
                    link = context.themeColor(R.attr.ptTextLink),
                )
            },
            onAction = { row, _ -> rowFires[row.id]?.invoke() },
            onLayout = ::onDocumentLayout,
        )

        /** What the document shows: the last bind's sections, kept so the
         *  "Load more" row can be redrawn ([setLoadMoreLoading]). */
        private class Bind(
            val primary: LensSection,
            val secondaries: List<LensSection>,
            val secondariesOnTop: Boolean,
            val loadMore: LensLoadMore?,
        )
        private var bind: Bind? = null

        /** Each tappable row's (already debounced) action, by row id: one
         *  wiring for touch and controller. */
        private var rowFires: Map<String, () -> Unit> = emptyMap()

        /** The tappable rows' ids in VISUAL order (top to bottom): the
         *  controller walk's candidates. Sections that do not open are not
         *  listed; the "Load more" row, when bound, is listed last. */
        private var navRowIds: List<String> = emptyList()

        /** Every secondary's row id, opening or not, in the host's order. */
        private var secondaryRowIds: List<String> = emptyList()

        /** A row to scroll to once the page has laid it out
         *  ([LensScroll.Reveal]); null for none. */
        private var pendingReveal: String? = null

        /** The scroll's single child: the loading body and the document's
         *  slot. Keeping the ScrollView as the ONE scroller preserves the
         *  stick-scroll repeater and the clip/translation grow animation. */
        private val bodyContainer = FrameLayout(ctx).apply {
            addView(loadingContent)
            addView(
                documentSlot,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
            )
        }
        /** Not private: [MagnifierLens.scrollYForTest] and
         *  [MagnifierLens.scrollToForTest] read and set its offset. */
        val definitionsScroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = true
            isFillViewport = false
            // clipToPadding stays false so scrolled rows fill the body
            // edge to edge rather than stopping at the padding strip.
            // Text is kept off the card border a different way: the
            // scroll view's layout bounds are inset by [bodyEdgeBufferPx]
            // on every side (see [updateChromeLayout]), so glyphs clip a
            // hair short of the 1dp border instead of overdrawing it and
            // leaving white pixel fragments along the bottom edge.
            clipToPadding = false
            // Default pad: pill is at the top, so the bigger pad is on top.
            setPadding(0, bodyPillSidePadPx, 0, bodyOuterSidePadPx)
            addView(bodyContainer)
            visibility = GONE
        }

        // ── The definitions document ──────────────────────────────────

        /** The user is about to look a word up through this lens (a drag
         *  has started): load the page while they move toward it. */
        fun prepareDefinitions() = lensDoc.prepare()

        /** Window teardown ([removeOverlayInternal]): the page and its
         *  shell go. A dropped but undestroyed WebView holds renderer
         *  resources and its context graph until GC. Idempotent. */
        fun releaseDefinitions() {
            lensDoc.release()
            docScope.coroutineContext.cancelChildren()
            bind = null
            rowFires = emptyMap()
            navRowIds = emptyList()
            secondaryRowIds = emptyList()
            pendingReveal = null
            rowCursor = null
            shownRowCursor = null
        }

        /** The page laid the rows out: the loading body steps aside (it
         *  stood until the definitions had pixels), a pending reveal lands,
         *  the card re-fits and the cursor follows its row. */
        private fun onDocumentLayout() {
            if (mode != Mode.DEFINITIONS) return
            loadingContent.visibility = GONE
            pendingReveal?.let { id ->
                lensDoc.rowTopPx(id)?.let { top ->
                    pendingReveal = null
                    // The report has just resized the document; the scroll
                    // clamps to the content it has laid out, so the offset
                    // lands after that layout (in the same pass, before it
                    // draws). A newer bind makes this one's scroll moot.
                    val forBind = bind
                    if (definitionsScroll.isLayoutRequested) {
                        definitionsScroll.doOnNextLayout {
                            if (bind === forBind) definitionsScroll.scrollTo(0, top)
                        }
                    } else {
                        definitionsScroll.scrollTo(0, top)
                    }
                }
            }
            onBodyHeightChanged()
            shownRowCursor = null
            syncNavRing()
        }

        /** No rows: the document empties (its page is kept for the next
         *  lookup) and nothing of the last bind stays reachable. */
        private fun clearRows() {
            bind = null
            rowFires = emptyMap()
            navRowIds = emptyList()
            secondaryRowIds = emptyList()
            pendingReveal = null
            rowCursor = null
            lensDoc.setRows(emptyList(), "")
        }

        private val clipPath = Path()
        private val srcRect = Rect()
        private val dstRect = RectF()
        private val cardRect = RectF()
        private val cardStrokeRect = RectF()

        private var sourceBitmap: Bitmap? = null
        private var sourceX = 0f
        private var sourceY = 0f
        private var sourceScreenW = 0
        private var sourceScreenH = 0

        /** Controller cursor's ring — same renderer as the capture sheet's. */
        private val focusRing = FocusRingView(ctx)

        init {
            setWillNotDraw(false)
            isFocusable = true
            isFocusableInTouchMode = true
            // Disable the default focus highlight — the framework otherwise
            // paints a translucent rectangle over the entire focusable view
            // when it gains focus (e.g. when [attachInteractiveListeners]
            // calls requestFocus), which reads as a screen-shaped darkening
            // over the lens area.
            defaultFocusHighlightEnabled = false
            // No background — only the rounded card region painted in
            // onDraw should be opaque. Explicit to defend against any
            // default selector that a Material-themed context might apply
            // to focusable views.
            background = null
            addView(definitionsScroll)
            addView(leftChip)
            addView(rightChip)
            addView(pillView)
            // Topmost; a plain non-clickable View, so lens touches fall
            // through it to the pill/chips/card beneath.
            addView(focusRing, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            rebuildClipPath()
            updateChromeLayout()
        }

        /** Rounded-rect path that clips the card body (zoom pixels + inset
         *  shadow) to the card's shape. Called from init and whenever the
         *  flip state changes. */
        private fun rebuildClipPath() {
            clipPath.reset()
            val top = bodyTopOffset.toFloat()
            val left = cardLeftInView.toFloat()
            clipPath.addRoundRect(
                left, top, left + cardW.toFloat(), top + lensH.toFloat(),
                cardCornerR, cardCornerR, Path.Direction.CW,
            )
        }

        /** Recompute layout params for the definitions scroll, pill, and
         *  chips based on [lensFlipped]. The pill sits on the card edge
         *  opposite the arrow; the chips share its vertical center; the
         *  scroll's larger top/bottom pad always faces the pill so the
         *  first content row clears it. */
        private fun updateChromeLayout() {
            // Scroll view occupies the card region inset by
            // [bodyEdgeBufferPx] on every side — the inset keeps
            // scrolled glyphs off the 1dp card border. Its top/bottom
            // padding flips with the lens so the larger pad (which
            // clears the pill) always faces the pill side.
            val scrollTopPad = if (lensFlipped) bodyOuterSidePadPx else bodyPillSidePadPx
            val scrollBottomPad = if (lensFlipped) bodyPillSidePadPx else bodyOuterSidePadPx
            definitionsScroll.setPadding(0, scrollTopPad, 0, scrollBottomPad)
            // Absolute LEFT + leftMargin throughout this method (never
            // START/marginStart): every offset here is a view-pixel coordinate
            // shared with the Canvas geometry — [cardLeftInView], the clip
            // path, the crosshair — and canvas coordinates do not mirror. Under
            // an RTL system locale the relative spelling would slide the placed
            // chrome off the card the clip path still paints in place.
            definitionsScroll.layoutParams = LayoutParams(
                cardW - 2 * bodyEdgeBufferPx, cardHeightPx - 2 * bodyEdgeBufferPx,
                Gravity.LEFT or Gravity.TOP,
            ).apply {
                leftMargin = chipHaloXPx + bodyEdgeBufferPx
                topMargin = bodyTopOffset + bodyEdgeBufferPx
            }

            // Pill — centered on pillAnchorY (= card top or card bottom).
            val pillTopMargin = pillAnchorY - pillHeightPx / 2
            pillView.layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT, pillHeightPx,
                Gravity.CENTER_HORIZONTAL or Gravity.TOP,
            ).apply { topMargin = pillTopMargin }

            // Chips — vertically centered on the pill's vertical center
            // (which is the card edge the pill is anchored to). Same
            // formula in both flip states.
            val chipTopMargin = pillAnchorY - chipHitSizePx / 2
            leftChip.layoutParams = LayoutParams(
                chipHitSizePx, chipHitSizePx,
                Gravity.LEFT or Gravity.TOP,
            ).apply { topMargin = chipTopMargin }
            rightChip.layoutParams = LayoutParams(
                chipHitSizePx, chipHitSizePx,
                Gravity.RIGHT or Gravity.TOP,
            ).apply { topMargin = chipTopMargin }
        }

        /** Apply only the [cardHeightPx]-dependent geometry: the scroll
         *  viewport height and the pill/chip vertical anchor. Mutates the
         *  existing layout params in place rather than replacing them, so a
         *  concurrent pill-WIDTH tween ([animatePillStateTransition], which
         *  runs on the same release) isn't clobbered back to WRAP_CONTENT.
         *  The scroll's width/margins and the pill's width/gravity were set
         *  by the full [updateChromeLayout] (init + flip) and don't change
         *  with height; only the scroll height, and — when flipped — the
         *  pill/chip top margins, move. */
        private fun applyCardHeightGeometry(
            layoutHeightPx: Int = cardHeightPx,
            layoutTopPx: Int = bodyTopOffset,
        ) {
            // Scroll viewport tracks the card body: height = card height (less
            // the edge inset); top rides the card top (which moves when the
            // card is bottom-anchored mid-grow). The grow path passes the END
            // state here once and animates draw-only (see [beginCardGrow]).
            (definitionsScroll.layoutParams as? LayoutParams)?.let {
                it.height = layoutHeightPx - 2 * bodyEdgeBufferPx
                it.topMargin = layoutTopPx + bodyEdgeBufferPx
                definitionsScroll.layoutParams = it
            }
            val pillAnchor = if (lensFlipped) layoutTopPx + layoutHeightPx else layoutTopPx
            (pillView.layoutParams as? LayoutParams)?.let {
                it.topMargin = pillAnchor - pillHeightPx / 2
                pillView.layoutParams = it
            }
            val chipTopMargin = pillAnchor - chipHitSizePx / 2
            (leftChip.layoutParams as? LayoutParams)?.let {
                it.topMargin = chipTopMargin
                leftChip.layoutParams = it
            }
            (rightChip.layoutParams as? LayoutParams)?.let {
                it.topMargin = chipTopMargin
                rightChip.layoutParams = it
            }
        }

        /** Resize the card body and optionally reposition its top within the
         *  window. The controller grows the card inside a window it pre-sized
         *  to the final bounds, so no per-frame window resize is needed (which
         *  would flash the overlay at its gravity anchor). [topOverride]
         *  non-null bottom-anchors the card (the not-flipped grow, where the
         *  card top rides up as it grows); null restores the flip-default.
         *  The card fill, border, and arrow read [cardHeightPx]/[bodyTopOffset]
         *  on the next draw, so an [invalidate] follows the relayout. */
        fun setCardGeometry(heightPx: Int, topOverride: Int?) {
            // A direct geometry set supersedes any in-flight grow's pinned
            // end-state layout — reset the draw-only ride first.
            if (growLayoutTarget != null) clearGrowDrawState()
            if (heightPx == cardHeightPx && topOverride == bodyTopOverride) return
            cardHeightPx = heightPx
            bodyTopOverride = topOverride
            applyCardHeightGeometry()
            // The card now moves within the fixed full-height window (its top
            // shifts as the finger moves and as it grows), so the zoom clip
            // must follow it — it's keyed off bodyTopOffset.
            rebuildClipPath()
            invalidate()
        }

        /** Non-null while the release grow animates: (final card height, final
         *  card top). [beginCardGrow] lays the scroll/pill/chips out ONCE at
         *  that end-state; each frame is then draw-only — the card chrome reads
         *  [cardHeightPx]/[bodyTopOverride] at draw time, and the children ride
         *  translationY + clipBounds ([setGrowFrame]) instead of re-measuring
         *  the dictionary subtree per frame. */
        private var growLayoutTarget: Pair<Int, Int>? = null

        /** Start a draw-only grow toward [finalHeightPx]/[finalTopPx]: one
         *  layout traversal at the end-state, then [setGrowFrame] per frame. */
        fun beginCardGrow(finalHeightPx: Int, finalTopPx: Int) {
            growLayoutTarget = finalHeightPx to finalTopPx
            applyCardHeightGeometry(finalHeightPx, finalTopPx)
        }

        /** One grow-animation frame: update the drawn card rect and slide the
         *  end-state-laid-out children to match via translation + clip. Falls
         *  back to a full [setCardGeometry] if no grow is pinned. */
        fun setGrowFrame(heightPx: Int, topPx: Int) {
            val (finalH, finalTop) = growLayoutTarget ?: run {
                setCardGeometry(heightPx, topPx)
                return
            }
            cardHeightPx = heightPx
            bodyTopOverride = topPx
            // Content rides the card top (laid out at the final top, shifted
            // back to the animated top), clipped to the animated card body.
            val scrollShift = (topPx - finalTop).toFloat()
            definitionsScroll.translationY = scrollShift
            definitionsScroll.clipBounds = Rect(
                0, 0, definitionsScroll.width,
                (heightPx - 2 * bodyEdgeBufferPx).coerceAtLeast(0),
            )
            // Pill + chips are anchored to the card edge opposite the arrow —
            // top when not flipped (rides with the content), bottom when
            // flipped (rides the growing bottom edge).
            val pillShift = if (lensFlipped) (heightPx - finalH).toFloat() else scrollShift
            pillView.translationY = pillShift
            leftChip.translationY = pillShift
            rightChip.translationY = pillShift
            rebuildClipPath()
            invalidate()
        }

        /** Settle a finished (or cancelled) grow: snap the drawn card rect to
         *  the pinned end-state and drop the draw-only ride. */
        fun endCardGrow() {
            val (finalH, finalTop) = growLayoutTarget ?: return
            cardHeightPx = finalH
            bodyTopOverride = finalTop
            clearGrowDrawState()
            rebuildClipPath()
            invalidate()
        }

        private fun clearGrowDrawState() {
            growLayoutTarget = null
            definitionsScroll.translationY = 0f
            definitionsScroll.clipBounds = null
            pillView.translationY = 0f
            leftChip.translationY = 0f
            rightChip.translationY = 0f
        }

        /** Resize the card body, restoring the flip-default top position. */
        fun setCardHeight(px: Int) = setCardGeometry(px, null)

        /** Card-body height that would show the full bound definitions
         *  without scrolling: the content's natural height at the scroll's
         *  content width, plus the scroll's pill-side + outer vertical pads
         *  (their sum is flip-invariant) and the 2px edge inset on top and
         *  bottom. Call only after a bind. */
        fun desiredCardHeightForContent(): Int {
            val chrome = bodyPillSidePadPx + bodyOuterSidePadPx + 2 * bodyEdgeBufferPx
            // The definitions: a page can't be measured synchronously, so
            // this is its own reported height (the report re-runs
            // [MagnifierLens.fitHeightToContent], so the card converges
            // though the number arrives late). Measured through the slot,
            // which also holds the notice when no page can draw.
            val body = if (mode == Mode.DEFINITIONS && loadingContent.visibility == GONE) documentSlot else loadingContent
            body.measure(
                MeasureSpec.makeMeasureSpec(cardW - 2 * bodyEdgeBufferPx, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            )
            return body.measuredHeight + chrome
        }

        /** Debounce so the open handlers can't fire twice from a single
         *  gesture that crosses the open detector and any other receiver —
         *  shared by the opens and the "Load more" row (one gesture, one action). */
        private var lastOpenTapMs = 0L
        private fun fireOpenTap() {
            val now = SystemClock.uptimeMillis()
            if (now - lastOpenTapMs < 300L) return
            lastOpenTapMs = now
            onOpenTap()
        }

        private fun fireSecondaryOpenTap(index: Int) {
            val now = SystemClock.uptimeMillis()
            if (now - lastOpenTapMs < 300L) return
            lastOpenTapMs = now
            onSecondaryOpenTap(index)
        }

        /** The "Load more" row's click: inert while the host resolves the
         *  sections ([LensLoadMore.loading]), debounced with the opens. */
        private fun fireLoadMoreTap() {
            if (bind?.loadMore?.loading == true) return
            val now = SystemClock.uptimeMillis()
            if (now - lastOpenTapMs < 300L) return
            lastOpenTapMs = now
            onLoadMoreTap()
        }

        private val tapDetector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                fireOpenTap()
                return false
            }
        })

        /** True when the current gesture's DOWN landed on a tap-eligible
         *  region (card body OR pill, NOT on chips OR arrow strip). Gated
         *  on DOWN so a stray UP elsewhere can't fire the open handler. */
        private var tapGestureActive = false
        /** Dismiss callback wired by [attachInteractiveListeners], with the
         *  DOWN's screen position; used when a sticky-mode tap lands off the
         *  lens chrome (the transparent area of the full-height window
         *  above/below the card). */
        private var dismissRequest: ((rawX: Float, rawY: Float) -> Unit)? = null
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            val handled = super.dispatchTouchEvent(ev)
            if (isInteractive) {
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                    // The window spans the full screen height; a DOWN outside
                    // the chrome band is an "off the lens" tap → dismiss
                    // (mirrors the ACTION_OUTSIDE dismissal beyond the edges).
                    if (ev.y < contentTopInView || ev.y >= contentBottomInView) {
                        tapGestureActive = false
                        dismissRequest?.invoke(ev.rawX, ev.rawY)
                        return handled
                    }
                    tapGestureActive = isTapEligible(ev.x, ev.y)
                }
                if (tapGestureActive) {
                    tapDetector.onTouchEvent(ev)
                    // If no child claimed the gesture, claim it here so the parent
                    // keeps delivering MOVE/UP to the tap detector. A tap on the
                    // pill (or chrome overhang) over the transparent area BELOW the
                    // card — the flipped case, where the pill sits at the card's
                    // bottom edge with no scroll view beneath it — leaves [handled]
                    // false; without claiming it, the DOWN isn't consumed, the
                    // parent never delivers the UP, and onSingleTapUp (hence the
                    // open-detail tap) never fires. When a child DID consume (the
                    // card-body scroll, e.g. the not-flipped pill overlapping it),
                    // defer to it so scrolling still works — the detector runs in
                    // parallel and only fires on a real tap.
                    if (!handled) return true
                }
            }
            return handled
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            // The lens window was just torn down. Cancel any in-flight
            // pill-width animation so its end-listener can't fire a
            // layout call on the now-detached view.
            pillAnimator?.cancel()
            pillAnimator = null
        }

        private fun isTapEligible(x: Float, y: Float): Boolean {
            // Arrow strip: opposite side from the pill/chip chrome.
            val arrowTop = if (lensFlipped) 0f else cardBottomInView.toFloat()
            val arrowBottom = arrowTop + arrowSizePx
            if (y >= arrowTop && y < arrowBottom) return false
            if (isInChipBounds(leftChip, x, y)) return false
            if (isInChipBounds(rightChip, x, y)) return false
            // While definitions show, the body belongs to the document:
            // the page reports what a tap actually hit (a link navigates
            // and hands off to the browser, anything else comes back as its
            // row's tap). Running the detector here too would race that
            // handoff: the open tap lands first, tears the lens down, and
            // destroys the WebView before the click ever processes.
            if (isInDocument(x, y)) return false
            // The pill is hidden while rows show (the first row names the
            // word); its place is not a way to open the word.
            if (mode == Mode.DEFINITIONS &&
                x >= pillView.left && x < pillView.right && y >= pillView.top && y < pillView.bottom
            ) {
                return false
            }
            return true
        }

        private fun isInDocument(x: Float, y: Float): Boolean =
            mode == Mode.DEFINITIONS &&
                x >= definitionsScroll.left && x < definitionsScroll.right &&
                y >= definitionsScroll.top && y < definitionsScroll.bottom

        private fun isInChipBounds(chip: View, x: Float, y: Float): Boolean {
            if (chip.visibility != VISIBLE) return false
            return x >= chip.left && x < chip.right && y >= chip.top && y < chip.bottom
        }

        fun setSourceBitmap(bitmap: Bitmap?) {
            sourceBitmap = bitmap
            invalidate()
        }

        fun setSourcePoint(x: Float, y: Float, screenW: Int, screenH: Int) {
            sourceX = x
            sourceY = y
            sourceScreenW = screenW
            sourceScreenH = screenH
            invalidate()
        }

        fun setLensFlipped(flipped: Boolean) {
            if (lensFlipped == flipped) return
            lensFlipped = flipped
            updateChromeLayout()
            rebuildClipPath()
            invalidate()
        }

        fun setArrowVisible(visible: Boolean, offsetX: Int) {
            if (arrowVisible == visible && arrowOffsetX == offsetX) return
            arrowVisible = visible
            arrowOffsetX = offsetX
            invalidate()
        }

        private enum class PillState { None, Placeholder, Word }
        private var pillState: PillState = PillState.None
        /** Last (word, reading) the pill was rendered with. Used so that
         *  a redundant setLabel call (same content) is a no-op, while
         *  any actual content change — across words OR across state —
         *  drives the width animation. */
        private var pillWord: String = ""
        private var pillReading: String = ""
        private var pillPitch: List<Int> = emptyList()
        private var pillAnimator: ValueAnimator? = null
        /** Decorates the Placeholder pill: when true, the magnifying-glass
         *  icon + "Find a word" prompt are hidden and
         *  [pillPlaceholderSpinnerView] is shown alone. Set by
         *  [setPillLoading] from [DragLookupController] for the duration
         *  of the drag-start OCR job. Word state ignores the flag. */
        private var pillLoading: Boolean = false

        fun setLabel(word: String?, reading: String?, pitch: List<Int>? = null) {
            val w = word?.takeIf { it.isNotEmpty() }
            val r = reading?.takeIf { it.isNotEmpty() }
            // pitch == null → the caller doesn't KNOW (drag tracking, the
            // loading skin, deck refreshes): preserve the accent the last
            // definitions lookup established while the word is unchanged,
            // reset on word change. A non-null list (definitions paths) is
            // authoritative — including empty, which clears. Without this,
            // interleaved tracking/loading calls strip the accent whenever
            // they happen to land after setDefinitions.
            val knownPitch = pitch ?: if (w.orEmpty() == pillWord) pillPitch else emptyList()
            // Kana-only words have no separate reading (the kana IS the
            // word); reuse it in the reading slot so the accent contour has
            // a home — mirrors the word-list row. The all-kana guard is
            // load-bearing: the contour maps morae onto whatever string it
            // draws over, so it must never cover kanji.
            val pitchKana = r ?: w?.takeIf { knownPitch.isNotEmpty() && it.all(Deinflector::isKana) }
            val newPitch = if (pitchKana != null) knownPitch else emptyList()
            val newState = if (w == null) PillState.Placeholder else PillState.Word
            val newWord = w.orEmpty()
            val newReading = pitchKana.orEmpty()
            // Kana-only: the reading just repeats the kana title. Draw the accent
            // on the WORD itself (below) and hide the separate reading slot.
            val kanaOnly = newReading.isNotEmpty() && newReading == newWord
            val showReading = pitchKana != null && newState == PillState.Word && !kanaOnly

            if (newState == pillState && newWord == pillWord && newReading == pillReading &&
                newPitch == pillPitch
            ) {
                return
            }

            val prevState = pillState
            pillState = newState
            pillWord = newWord
            pillReading = newReading
            pillPitch = newPitch

            // Push the new content into the views before measuring the
            // pill's natural width — the new word's text width is what
            // we're animating toward.
            if (kanaOnly) {
                // Accent rides on the word itself. Two things the hidden reading
                // slot used to handle: (1) fit the word + its [n] suffix to the
                // card budget so a long kana word can't overflow/ellipsize; (2)
                // a top pad shifts it down so the overline clears the capsule top
                // (the contour draws ~0.16×size above the text). Top-only, since
                // symmetric padding would push a 24sp word past the 40dp pill.
                val suffix = if (newPitch.isNotEmpty())
                    newPitch.joinToString("·") { "[$it]" } else ""
                if (newPitch.isNotEmpty()) {
                    pillWordView.text = buildPitchAnnotatedReading(newWord, newPitch)
                } else {
                    pillWordView.text = newWord
                }
                fitPillText(
                    word = if (suffix.isEmpty()) newWord else "$newWord $suffix",
                    reading = "",
                )
                val pad = if (newPitch.isNotEmpty())
                    (pillWordView.textSize * 0.22f).toInt() else 0
                pillWordView.setPadding(0, pad, 0, 0)
            } else {
                // Reset the headline to full size + no padding — a prior kana-only
                // label may have shrunk / padded it.
                pillWordView.setTextSize(TypedValue.COMPLEX_UNIT_SP, pillWordSp)
                pillWordView.setPadding(0, 0, 0, 0)
                pillWordView.text = newWord
                if (newPitch.isEmpty()) {
                    pillReadingView.text = newReading
                    fitPillText(newWord, newReading)
                    pillReadingView.setPadding(0, 0, 0, 0)
                } else {
                    pillReadingView.text = buildPitchAnnotatedReading(newReading, newPitch)
                    fitPillText(
                        newWord,
                        newReading + " " + newPitch.joinToString("·") { "[$it]" },
                    )
                    // Symmetric padding: headroom for the overline above,
                    // mirrored below so the glyphs stay centered in the fixed-
                    // height pill. Scales with the post-fit text size.
                    val pad = (pillReadingView.textSize * 0.30f).toInt()
                    pillReadingView.setPadding(0, pad, 0, pad)
                }
            }

            if (prevState == PillState.None) {
                // First state assignment after show()/teardown rebuild —
                // just snap, no animation.
                applyPillStateVisibility(newState, showReading)
                pillView.visibility = if (mode == Mode.DEFINITIONS) INVISIBLE else VISIBLE
            } else {
                animatePillStateTransition(newState, showReading)
            }
        }

        /** Toggle child visibility for the requested state without
         *  touching the pill's own width or animator. The Placeholder
         *  state has two skins keyed off [pillLoading]: the magnifying-
         *  glass icon + "Find a word" prompt at rest, or the spinner
         *  alone (no label) while drag-start OCR is in flight. */
        private fun applyPillStateVisibility(state: PillState, showReading: Boolean) {
            val placeholderVisible = state == PillState.Placeholder
            val wordVisible = state == PillState.Word
            val loadingSkin = placeholderVisible && pillLoading
            val idlePlaceholder = placeholderVisible && !pillLoading
            pillPlaceholderIconView.visibility = if (idlePlaceholder) VISIBLE else GONE
            pillPlaceholderSpinnerView.visibility = if (loadingSkin) VISIBLE else GONE
            pillPlaceholderTextView.visibility = if (idlePlaceholder) VISIBLE else GONE
            pillWordView.visibility = if (wordVisible) VISIBLE else GONE
            pillDividerView.visibility = if (wordVisible && showReading) VISIBLE else GONE
            pillReadingView.visibility = if (wordVisible && showReading) VISIBLE else GONE
        }

        /** Animate the pill's width from its current measured width to the
         *  natural width of [newState]'s children. Children are switched
         *  to the new state's visibility immediately; the pill's clip
         *  reveals (or hides) them as it grows (or shrinks). */
        private fun animatePillStateTransition(newState: PillState, showReading: Boolean) {
            pillAnimator?.cancel()
            val oldWidth = if (pillView.width > 0) pillView.width else measurePillNaturalWidth()
            applyPillStateVisibility(newState, showReading)
            val newWidth = measurePillNaturalWidth()
            pillView.visibility = if (mode == Mode.DEFINITIONS) INVISIBLE else VISIBLE
            val params = pillView.layoutParams
            params.width = oldWidth
            pillView.layoutParams = params
            pillAnimator = ValueAnimator.ofInt(oldWidth, newWidth).apply {
                duration = 180L
                interpolator = android.view.animation.DecelerateInterpolator()
                addUpdateListener { anim ->
                    val w = anim.animatedValue as Int
                    val lp = pillView.layoutParams
                    lp.width = w
                    pillView.layoutParams = lp
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        // Restore WRAP_CONTENT so subsequent content
                        // changes within the same state can resize the
                        // pill naturally.
                        val lp = pillView.layoutParams
                        lp.width = LinearLayout.LayoutParams.WRAP_CONTENT
                        pillView.layoutParams = lp
                    }
                })
                start()
            }
        }

        /** Measure the pill's natural (WRAP_CONTENT) width with the
         *  current child visibilities + content, height pinned at the
         *  fixed pill height. */
        private fun measurePillNaturalWidth(): Int {
            pillView.measure(
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                MeasureSpec.makeMeasureSpec(pillHeightPx, MeasureSpec.EXACTLY),
            )
            return pillView.measuredWidth
        }

        /**
         * Size and bound the pill's headline and reading so the capsule can
         * never outgrow [pillMaxWidthPx] — the width past which [revealChips]
         * has nowhere left to seat the chips but off the frame.
         *
         * [word] and [reading] are the strings the two slots will draw, pitch
         * `[n]` suffix included (measured at full size though it renders at
         * 0.75×, which errs roomy). Pass an empty [reading] for the states with
         * no reading slot: the kana-only pill, where the headline carries the
         * accent itself, and words that have no reading at all.
         *
         * The reading yields first, shrinking 14→11sp against a full-size
         * headline — the long-standing rule, so a pill that fits today is
         * untouched. The headline then yields against what the reading settled
         * at, shrinking 24sp down to the same 11sp floor rather than
         * ellipsizing away the identity of the word. Whatever neither step can
         * save is ellipsized by the maxWidths, which is also what makes the
         * capsule's natural width bounded by construction rather than by
         * measurement luck.
         *
         * Stateless across calls: every call starts its search from the max
         * sizes, so a short label returns to full size on its own.
         */
        private fun fitPillText(word: String, reading: String) {
            val showReading = reading.isNotEmpty()
            val fixedChrome = pillPaddingLead + pillPaddingTrail +
                if (showReading) pillDividerWidth + 2 * pillGap else 0
            val textBudget = (pillMaxWidthPx - fixedChrome).coerceAtLeast(0).toFloat()

            pillWordSizingPaint.textSize = spPx(pillWordSp)
            val readingRoom = textBudget - pillWordSizingPaint.measureText(word)
            var readingSp = pillReadingMaxSp
            if (showReading) {
                while (readingSp > pillReadingMinSp) {
                    pillReadingSizingPaint.textSize = spPx(readingSp)
                    if (pillReadingSizingPaint.measureText(reading) <= readingRoom) break
                    readingSp -= 1f
                }
                pillReadingSizingPaint.textSize = spPx(readingSp)
            }
            val readingWidth =
                if (showReading) pillReadingSizingPaint.measureText(reading) else 0f
            val (wordAllot, readingAllot) = computePillTextAllotment(
                maxPillWidth = pillMaxWidthPx,
                fixedChromeWidth = fixedChrome,
                readingWidth = readingWidth.roundToInt(),
            )

            var wordSp = pillWordSp
            while (wordSp > pillReadingMinSp) {
                pillWordSizingPaint.textSize = spPx(wordSp)
                if (pillWordSizingPaint.measureText(word) <= wordAllot) break
                wordSp -= 1f
            }
            pillWordView.setTextSize(TypedValue.COMPLEX_UNIT_SP, wordSp)
            pillWordView.maxWidth = wordAllot
            pillReadingView.setTextSize(TypedValue.COMPLEX_UNIT_SP, readingSp)
            pillReadingView.maxWidth = readingAllot
        }

        fun setDefinitions(data: WordDefinitionData?, label: String?, opens: Boolean) {
            if (data == null) {
                if (mode == Mode.ZOOM) return
                mode = Mode.ZOOM
                clearRows()
                loadingContent.visibility = GONE
                definitionsScroll.visibility = GONE
                leftChip.visibility = GONE
                rightChip.visibility = GONE
                syncPill()
                invalidate()
                return
            }
            bindRows(Bind(LensSection(data, label, opens), emptyList(), secondariesOnTop = false, loadMore = null), LensScroll.Top)
        }

        /** See [MagnifierLens.setSplitDefinitions]. */
        fun setSplitDefinitions(
            primary: LensSection,
            secondaries: List<LensSection>,
            secondariesOnTop: Boolean,
            loadMore: LensLoadMore?,
            scroll: LensScroll,
        ) = bindRows(Bind(primary, secondaries, secondariesOnTop, loadMore), scroll)

        /**
         * The one bind: [next]'s sections as rows of the document, in their
         * visual order ([Bind.secondariesOnTop] puts the longer unit on top
         * on every surface), then the "Load more" row. Every section is a
         * row with its own headword, the tapped unit's included: the pill,
         * which named it while the lens was a magnifier, is hidden as soon
         * as a row shows. A row that opens is a tap target for its section's
         * open action; one that does not is inert.
         *
         * A rebind of the same sections (the deck back-fill, the "Load
         * more" row's text) reaches the document as the same row ids, so it
         * patches the rows that changed and the body neither collapses nor
         * jumps; a different lookup is a new row set.
         */
        private fun bindRows(next: Bind, scroll: LensScroll) {
            val fromLoading = mode == Mode.LOADING
            mode = Mode.DEFINITIONS
            bind = next
            // Kept current under the hidden pill: a drag's next magnifier
            // shows it again, and the chips are placed against its width.
            setLabel(next.primary.data.word, next.primary.data.reading, next.primary.data.pitch)
            // Read before the rows change: the cursor's place among the
            // targets, -1 when it is elsewhere.
            val cursorIndex = rowCursor?.let { navRowIds.indexOf(it) } ?: -1

            val rows = ArrayList<DefRow>()
            val fires = HashMap<String, () -> Unit>()
            val nav = ArrayList<String>()
            val secondaryIds = ArrayList<String>()
            val empty = context.getString(R.string.word_detail_no_definitions)
            fun add(section: LensSection, secondaryIndex: Int?) {
                val d = section.data
                val id = (if (secondaryIndex == null) "p" else "s$secondaryIndex") +
                    "\u0001" + d.word + "\u0001" + d.reading.orEmpty()
                rows += DefRow.Word(
                    id, DefRow.Variant.LENS, d,
                    opens = section.opens, label = section.label, emptyText = empty,
                )
                if (section.opens) {
                    fires[id] = if (secondaryIndex == null) ::fireOpenTap else ({ fireSecondaryOpenTap(secondaryIndex) })
                    nav += id
                }
                if (secondaryIndex != null) secondaryIds += id
            }
            if (next.secondariesOnTop) {
                next.secondaries.forEachIndexed { i, sec -> add(sec, i) }
                add(next.primary, null)
            } else {
                add(next.primary, null)
                next.secondaries.forEachIndexed { i, sec -> add(sec, i) }
            }
            next.loadMore?.let { state ->
                rows += DefRow.More(MORE_ROW_ID, loadMoreText(state))
                fires[MORE_ROW_ID] = ::fireLoadMoreTap
                nav += MORE_ROW_ID
            }
            rowFires = fires
            navRowIds = nav
            secondaryRowIds = secondaryIds

            // The loading body stands until the rows have pixels
            // ([onDocumentLayout]); from the magnifier there is none to hold.
            if (!fromLoading) loadingContent.visibility = GONE
            lensDoc.setRows(rows, next.primary.data.definitionLang)
            // No page can draw: the notice is in the slot now, and no layout
            // report will come to take the loading body away.
            if (lensDoc.showsNotice) loadingContent.visibility = GONE
            definitionsScroll.visibility = VISIBLE
            when (scroll) {
                LensScroll.Top -> {
                    pendingReveal = null
                    definitionsScroll.scrollTo(0, 0)
                }
                // The scroll keeps its offset by itself: the document keeps
                // its height until the rebind's own layout lands.
                LensScroll.Keep -> pendingReveal = null
                // Lands with the layout that holds the row ([onDocumentLayout]):
                // its top is its resting scroll offset, the same one the
                // controller scrolls a selected row to.
                is LensScroll.Reveal -> pendingReveal = secondaryIds.getOrNull(scroll.secondaryIndex)
            }
            // A controller cursor on a row keeps its place: on the revealed
            // row when the rebind reveals one that is a target (the host
            // binding the sections it loaded, whether the row was activated
            // by the cursor or by touch), else on the target at its index,
            // clamped, which is the same section after a deck back-fill and
            // the "Load more" row while it reads "Looking up...".
            if (cursorIndex >= 0) {
                rowCursor = pendingReveal?.takeIf { it in nav }
                    ?: nav.getOrNull(cursorIndex.coerceAtMost(nav.lastIndex))
                shownRowCursor = null
                syncNavRing()
            }
            syncPill()
            invalidate()
        }

        private fun loadMoreText(state: LensLoadMore): String =
            if (state.loading) context.getString(R.string.lens_loading)
            else context.getString(R.string.load_more_count, state.count)

        /** See [MagnifierLens.setLoadMoreLoading]. */
        fun setLoadMoreLoading(loading: Boolean) {
            val b = bind ?: return
            val state = b.loadMore ?: return
            bindRows(Bind(b.primary, b.secondaries, b.secondariesOnTop, state.copy(loading = loading)), LensScroll.Keep)
        }

        /** The "Load more" row's text as bound, null when there is none. */
        fun loadMoreTextOrNull(): String? = bind?.loadMore?.let(::loadMoreText)

        // What a JVM test needs to stand in for the page and read the result.
        fun layoutRowsForTest(rowHeightCssPx: Int) = lensDoc.layoutForTest(rowHeightCssPx)
        fun shownRowsForTest(): List<DefRow> = lensDoc.shownRows
        fun cursorRowForTest(): String? = rowCursor
        fun rowTopForTest(id: String): Int? = lensDoc.rowTopPx(id)
        fun tapRowForTest(id: String) { rowFires[id]?.invoke() }
        fun pillShownForTest(): Boolean = pillView.visibility == VISIBLE

        fun setLoading(word: String?, reading: String?) {
            mode = Mode.LOADING
            setLabel(word, reading)
            clearRows()
            populateLoading()
            loadingContent.visibility = VISIBLE
            definitionsScroll.scrollTo(0, 0)
            definitionsScroll.visibility = VISIBLE
            syncPill()
            invalidate()
        }

        /** The pill names the word while the lens is a magnifier or is
         *  loading; once a definition row shows, the row names it and the
         *  pill is hidden. INVISIBLE, never GONE: the chips are placed and
         *  revealed against its laid-out width. */
        private fun syncPill() {
            if (pillView.visibility == GONE) return
            pillView.visibility = if (mode == Mode.DEFINITIONS) INVISIBLE else VISIBLE
        }

        /** Toggle the spinner skin on the Placeholder pill. Loading is a
         *  decoration of [PillState.Placeholder] — when the pill is
         *  currently showing a word the flag is stored but has no visual
         *  effect; the next transition back to Placeholder will honor it.
         *
         *  Within-Placeholder skin swaps are always width-animated (same
         *  machinery as state changes via [setLabel]). This matters in
         *  both directions:
         *   - Loading off: the narrow spinner pill smoothly grows into
         *     "Find a word" (or, if pretokenize lands the user's line
         *     mid-animation, [setLabel] cancels and redirects to the
         *     Word-state width).
         *   - Loading on: in the re-drag path, [resetToZoom] has just
         *     animated Word→Placeholder toward the wide idle width;
         *     animating again here cancels that tween and redirects to
         *     the narrow spinner-only width so the spinner doesn't sit
         *     in an oversized pill for the OCR duration.
         *
         *  The very first drag activates while pillState is still None
         *  and this early-returns — the spinner shows as part of the
         *  initial setLabel snap. */
        fun setPillLoading(loading: Boolean) {
            if (pillLoading == loading) return
            pillLoading = loading
            if (pillState != PillState.Placeholder) return
            animatePillStateTransition(PillState.Placeholder, showReading = false)
        }

        var isInteractive: Boolean = false
            private set

        fun attachInteractiveListeners(onDismissRequest: (rawX: Float, rawY: Float) -> Unit) {
            dismissRequest = onDismissRequest
            // A touch outside the window is the root's to hear
            // ([LensRoot.dispatchTouchEvent]). TalkBack users dismiss the
            // lens via the back gesture.
            // Right stick scrolls the definitions — the sheet's scroll hand
            // (the left stick was the original driver, and before that a
            // nudge-to-dismiss; B carries dismissal now, and a dictionary
            // long enough to scroll is exactly when a stick flick must not
            // close it).
            setOnGenericMotionListener { _, event -> handleNavMotion(event) }
            navPreDraw = ViewTreeObserver.OnPreDrawListener { syncNavRing(); true }.also {
                viewTreeObserver.addOnPreDrawListener(it)
            }
            requestFocus()
            isInteractive = true
            // Chips become visible only in the sticky state (per the
            // design — they're quick actions for a settled lookup, not
            // drag-time UI). Reveal is deferred until the pill's
            // width animation (placeholder → word) completes, so the
            // chips' "under-pill" start position is actually under the
            // pill at its final word-state width.
            if (mode == Mode.DEFINITIONS) scheduleChipReveal()
        }

        fun detachInteractiveListeners() {
            setOnGenericMotionListener(null)
            navPreDraw?.let { viewTreeObserver.removeOnPreDrawListener(it) }
            navPreDraw = null
            stopNavScroll()
            keyPressView?.let { ConfirmKeyPress.cancel(it) }
            keyPressView = null
            navCursor = null
            rowCursor = null
            navConsumedDown.clear()
            syncNavRing()
            focusRing.setTarget(null, null)
            dismissRequest = null
            clearFocus()
            isInteractive = false
            leftChip.animate().cancel()
            rightChip.animate().cancel()
            leftChip.translationX = 0f
            rightChip.translationX = 0f
            leftChip.visibility = GONE
            rightChip.visibility = GONE
        }

        // ── Controller nav: cursor over the chrome and the rows, stick scroll ──

        /** The cursor when it is on the chrome (a chip, or the pill while
         *  it shows); null when it is on a row or nowhere. */
        private var navCursor: View? = null

        /** The cursor when it is on a row of the document: its row id. At
         *  most one of this and [navCursor] is set. */
        private var rowCursor: String? = null

        /** The row the page is drawing the cursor on, so the per-preDraw
         *  [syncNavRing] only talks to the page on an actual change. */
        private var shownRowCursor: String? = null

        private var navPreDraw: ViewTreeObserver.OnPreDrawListener? = null
        /** Keycodes whose DOWN we consumed, so the matching UP is too — and
         *  so the A that OPENED the lens (pressed on the sheet, released over
         *  this freshly-focused window) can't instantly activate a target. */
        private val navConsumedDown = mutableSetOf<Int>()
        private val navItemLoc = IntArray(2)
        private val navSelfLoc = IntArray(2)
        private val navTmp = Rect()

        /** Where a fresh cursor lands, and the auto-select of a
         *  controller-opened lens, so the very next A drills into the
         *  detail screen: the pill while it shows (a lens still loading),
         *  else the row nearest the chrome, else a chip. */
        fun focusStart() {
            val rows = bodyWalk()
            when {
                pillView.isShown -> setCursor(pillView, null)
                rows.isNotEmpty() -> setCursor(null, rows.first())
                else -> setCursor(navChromeRow().firstOrNull(), null)
            }
        }

        private fun setCursor(view: View?, row: String?) {
            navCursor = view
            rowCursor = row
            syncNavRing()
        }

        private fun hasCursor(): Boolean =
            navCursor?.isShown == true || rowCursor?.let { it in navRows() } == true

        private fun navChromeRow(): List<View> =
            listOf(leftChip, pillView, rightChip).filter { it.isShown }

        /** The tappable rows the page has laid out, top to bottom. */
        fun navRows(): List<String> {
            val laidOut = lensDoc.targets.mapTo(HashSet()) { it.rowId }
            return navRowIds.filter { it in laidOut }
        }

        /** Walk order into and through the body follows the physical axis:
         *  chrome on top enters at the stack's top and walks down; a flipped
         *  lens (chrome at the bottom) enters at the stack's bottom and
         *  walks up. */
        private fun bodyWalk(): List<String> = if (lensFlipped) navRows().reversed() else navRows()

        /** Item rect in THIS view's coordinates. Chips ring their 32dp visible
         *  disk, not the 48dp hit halo. */
        private fun navRectInView(v: View, out: Rect): Boolean {
            if (!v.isShown || v.width <= 0 || v.height <= 0) return false
            v.getLocationOnScreen(navItemLoc)
            getLocationOnScreen(navSelfLoc)
            val l = navItemLoc[0] - navSelfLoc[0]
            val t = navItemLoc[1] - navSelfLoc[1]
            out.set(l, t, l + v.width, t + v.height)
            if (v === leftChip || v === rightChip) {
                val inset = (chipHitSizePx - chipVisDiameterPx) / 2
                out.inset(inset, inset)
            }
            return true
        }

        /** Draws the cursor where it is. A row renders its selection in the
         *  page, as a soft accent wash inside the section (a full-width
         *  definitions cell wants a background, not chrome); a chip or the
         *  pill gets the boundary ring. */
        private fun syncNavRing() {
            val row = rowCursor?.takeIf { it in navRowIds }
            if (row != shownRowCursor) {
                shownRowCursor = row
                lensDoc.setCursor(row?.let { id -> lensDoc.targets.firstOrNull { it.rowId == id && it.act.isNotEmpty() } })
            }
            val cur = navCursor
            if (row != null || cur == null || !navRectInView(cur, navTmp)) {
                focusRing.setTarget(null, null)
                return
            }
            focusRing.setTarget(navTmp, null)
        }

        /** Dpad + A for the sticky lens (B stays in [LensRoot]). On the Anki
         *  chip the A is delivered as a real press on the chip
         *  ([ConfirmKeyPress]), so a held A runs the chip's own long-press
         *  (the headless one-tap) at the long-press timeout, exactly as a
         *  held finger does; an early release is the chip's click (the
         *  review sheet). Everything else activates on the consumed UP.
         *  Any of these — the timeout-fired one-tap included — may dismiss
         *  this focused window mid-press: the dismissal seam lingers the
         *  window as an invisible key sink until every tracked press
         *  releases ([WindowKeyPairGuard] on [LensRoot]), so a still-held
         *  key's UP cannot orphan into the app beneath. */
        fun handleNavKey(ev: KeyEvent): Boolean {
            if (!isInteractive) return false
            if (ev.action == KeyEvent.ACTION_UP) {
                if (!navConsumedDown.remove(ev.keyCode)) return false
                if (ControllerKeys.isActivate(ev.keyCode)) {
                    val held = keyPressView
                    if (held != null) {
                        keyPressView = null
                        ConfirmKeyPress.up(held, ev)
                    } else {
                        // First A selects; only a second activates.
                        if (!hasCursor()) focusStart() else activateNav()
                    }
                }
                return true
            }
            if (ev.action != KeyEvent.ACTION_DOWN) return false
            val dir = ControllerKeys.direction(ev.keyCode)
            if (dir == null && !ControllerKeys.isActivate(ev.keyCode)) return false
            if (dir != null) {
                // Cursor leaving the chip mid-press: cancel the view press
                // (no click, no pending long-press). The ref is deliberately
                // KEPT — the eventual A-UP then routes to the now-unpressed
                // chip and no-ops, instead of activating the moved-to item.
                keyPressView?.let { ConfirmKeyPress.cancel(it) }
                if (!hasCursor()) focusStart() else moveNav(dir)
            } else if (ev.repeatCount == 0 && keyPressView == null &&
                navCursor === rightChip && rightChip.isShown
            ) {
                keyPressView = rightChip
                ConfirmKeyPress.down(rightChip, ev)
            }
            navConsumedDown.add(ev.keyCode)
            return true
        }

        /** The view holding an in-flight forwarded key press (the Anki chip —
         *  the lens's one hold-capable item). See [ConfirmKeyPress]. */
        private var keyPressView: View? = null

        /** Explicit nav graph instead of spatial scoring. The lens is two
         *  strips: the chrome row (Speak chip · pill while it shows · Anki
         *  chip) along one card edge, and the body's rows stacked on the
         *  body side. Spatial scoring breaks on this layout — a row is a
         *  card-wide rect centered like the pill, so once the body has
         *  scrolled a row can beat a chip on a LEFT/RIGHT move with a
         *  near-zero axis distance. LEFT/RIGHT walk the chrome row only;
         *  the vertical axis moves between the row and the rows (direction
         *  flip-aware: the body is below the chrome when the lens sits
         *  above the word, above it when flipped). */
        private fun moveNav(dir: SheetNavGeometry.Dir) {
            val chrome = navChromeRow()
            val rows = bodyWalk()
            val view = navCursor?.takeIf { it in chrome }
            val row = rowCursor?.takeIf { it in rows }
            if (view == null && row == null) {
                focusStart()
                return
            }
            val towardBody =
                if (lensFlipped) SheetNavGeometry.Dir.UP else SheetNavGeometry.Dir.DOWN
            val towardChrome =
                if (lensFlipped) SheetNavGeometry.Dir.DOWN else SheetNavGeometry.Dir.UP
            var nextView: View? = null
            var nextRow: String? = null
            when {
                view != null &&
                    (dir == SheetNavGeometry.Dir.LEFT || dir == SheetNavGeometry.Dir.RIGHT) -> {
                    val i = chrome.indexOf(view)
                    nextView = chrome.getOrNull(if (dir == SheetNavGeometry.Dir.LEFT) i - 1 else i + 1)
                }
                view != null && dir == towardBody -> nextRow = rows.firstOrNull()
                row != null && dir == towardBody -> nextRow = rows.getOrNull(rows.indexOf(row) + 1)
                row != null && dir == towardChrome -> {
                    nextRow = rows.getOrNull(rows.indexOf(row) - 1)
                    // Off the body's first row: onto the chrome, the pill
                    // while it shows, else the nearer chip.
                    if (nextRow == null) nextView = pillView.takeIf { it.isShown } ?: chrome.firstOrNull()
                }
            }
            if (nextView == null && nextRow == null) return
            // A selected row reads top-aligned: scroll its top to where the
            // first row rests at scroll 0 (a row's top in the document IS
            // its resting scroll offset). Stepping back out to the chrome
            // restores the resting scroll.
            if (nextRow != null) {
                lensDoc.rowTopPx(nextRow)?.let { definitionsScroll.smoothScrollTo(0, it) }
            } else if (row != null) {
                definitionsScroll.smoothScrollTo(0, 0)
            }
            setCursor(nextView, nextRow)
        }

        private fun activateNav() {
            val row = rowCursor
            val v = navCursor
            when {
                // A row carries its own (debounced) action: one wiring for
                // touch and controller.
                row != null -> rowFires[row]?.invoke()
                v === pillView -> fireOpenTap()
                v === leftChip -> onSpeakTap()
                v === rightChip -> onAnkiTap()
            }
        }

        // Stick scroll of the definitions body — the sheet's repeater pattern
        // (dt-clamped, shared speed/dead-zone dials), minus the modal checks
        // the lens doesn't have. Stopped by [detachInteractiveListeners],
        // which [removeOverlayInternal] runs on every dismissal (the repeater
        // re-posts itself, so an un-detached removal would leak the view).

        private var navScrollActive = false
        private var navScrollY = 0f
        private var navScrollDead = CaptureSheetControllerNav.STICK_DEAD_ZONE
        private var navScrollRepeating = false
        private var navScrollLastNs = 0L
        private val navScrollFrame = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!navScrollRepeating) return
                val dt = ((frameTimeNanos - navScrollLastNs) / 1e9f).coerceIn(0f, 0.05f)
                navScrollLastNs = frameTimeNanos
                val mag = ((abs(navScrollY) - navScrollDead) / (1f - navScrollDead)).coerceIn(0f, 1f)
                definitionsScroll.scrollBy(
                    0,
                    (sign(navScrollY) * mag *
                        CaptureSheetControllerNav.STICK_MAX_DP_PER_SEC * density * dt).roundToInt(),
                )
                Choreographer.getInstance().postFrameCallback(this)
            }
        }

        private fun handleNavMotion(event: MotionEvent): Boolean {
            if (!isInteractive) return false
            if (event.actionMasked != MotionEvent.ACTION_MOVE) return false
            if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) {
                return false
            }
            // Hat-driven dpads must keep synthesizing DPAD keys — leave their
            // MOVEs unconsumed (same guard as the sheet).
            if (event.getAxisValue(MotionEvent.AXIS_HAT_X) != 0f ||
                event.getAxisValue(MotionEvent.AXIS_HAT_Y) != 0f
            ) {
                return false
            }
            // The scroll rides the RIGHT stick's vertical axis, as the
            // sheet's does: Z/RZ under the standard mapping, RX/RY on a
            // controller that declares no RZ range.
            val device = event.device
            val ryAxis =
                if (device?.getMotionRange(MotionEvent.AXIS_RZ, event.source) != null) {
                    MotionEvent.AXIS_RZ
                } else {
                    MotionEvent.AXIS_RY
                }
            val flat = device?.getMotionRange(ryAxis, event.source)?.flat ?: 0f
            navScrollDead = maxOf(flat, CaptureSheetControllerNav.STICK_DEAD_ZONE)
            val ry = event.getAxisValue(ryAxis)
            if (abs(ry) <= navScrollDead) {
                val was = navScrollActive
                navScrollActive = false
                stopNavScroll()
                // Consume the centering event of a deflection we owned — and
                // any left-stick push, which drives nothing here now but is
                // still claimed: X/Y are the axes ViewRootImpl synthesizes
                // DPAD keys from, and the cursor walks by hat/dpad alone.
                val leftDead = maxOf(
                    device?.getMotionRange(MotionEvent.AXIS_Y, event.source)?.flat ?: 0f,
                    CaptureSheetControllerNav.STICK_DEAD_ZONE,
                )
                return was ||
                    abs(event.getAxisValue(MotionEvent.AXIS_Y)) > leftDead ||
                    abs(event.getAxisValue(MotionEvent.AXIS_X)) > leftDead
            }
            navScrollActive = true
            navScrollY = ry
            if (!navScrollRepeating) {
                navScrollRepeating = true
                navScrollLastNs = System.nanoTime()
                Choreographer.getInstance().postFrameCallback(navScrollFrame)
            }
            return true
        }

        private fun stopNavScroll() {
            if (!navScrollRepeating) return
            navScrollRepeating = false
            Choreographer.getInstance().removeFrameCallback(navScrollFrame)
        }

        /** Run [revealChips] now if no pill width animation is in flight;
         *  otherwise wait for the pill animator to end and run it then.
         *  This ensures the chips' "under-pill" start position is at the
         *  pill's settled word-state width, not at a mid-animation width
         *  (which would leave the chips visibly exposed). */
        private fun scheduleChipReveal() {
            val anim = pillAnimator
            if (anim == null || !anim.isRunning) {
                revealChips()
                return
            }
            anim.addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    animation.removeListener(this)
                    if (isInteractive) revealChips()
                }
            })
        }

        /** Lay the chips at their final positions — visible disks
         *  [chipPillGapPx] away from the pill's left and right edges —
         *  then translate them back inward so they sit under the pill,
         *  set visibility, and animate translationX back to 0 so they
         *  slide out from under the pill to their resting places. */
        private fun revealChips() {
            // [fitPillText] bounds the capsule at [pillMaxWidthPx], so both
            // lanes come out ≥ 0 however long the word is — a wide pill walks
            // the chips out to the host's edges and stops there, instead of
            // walking them past the frame that clips them.
            val (leftLaneMargin, rightLaneMargin) = computeChipLaneMargins(
                viewW = viewW,
                pillWidth = measurePillNaturalWidth(),
                chipLane = chipLanePx,
            )
            // The vertical anchor is OWNED by applyCardHeightGeometry — keep
            // whatever it last wrote. Recomputing from the live pillAnchorY
            // here reads the ANIMATED card top when the reveal was deferred
            // past a pill tween that ends mid-grow (the loading→definitions
            // path: setLoading's pill tween is still running at
            // makeInteractive), and endCardGrow only clears the draw-only
            // ride — the mid-grow margin then strands the chips inside the
            // grown card. The existing margin is always right: the grow's
            // end-state (written at beginCardGrow) or the settled anchor.
            val chipTopMargin = (leftChip.layoutParams as? LayoutParams)?.topMargin
                ?: (pillAnchorY - chipHitSizePx / 2)

            // Absolute LEFT/RIGHT + left/rightMargin: the lane margins are
            // view-pixel coordinates measured off [viewW], so pairing them with
            // relative START/END margins would mirror the pair under an RTL
            // locale and swap the two chips — prev/next would read backwards.
            leftChip.layoutParams = LayoutParams(
                chipHitSizePx, chipHitSizePx,
                Gravity.LEFT or Gravity.TOP,
            ).apply {
                leftMargin = leftLaneMargin
                topMargin = chipTopMargin
            }
            rightChip.layoutParams = LayoutParams(
                chipHitSizePx, chipHitSizePx,
                Gravity.RIGHT or Gravity.TOP,
            ).apply {
                rightMargin = rightLaneMargin
                topMargin = chipTopMargin
            }

            leftChip.animate().cancel()
            rightChip.animate().cancel()
            // Initial offset accounts for both the chip's visible
            // diameter AND the resting gap, so the disk is fully tucked
            // under the pill at t=0 regardless of the gap.
            val initialOffset = (chipVisDiameterPx + chipPillGapPx).toFloat()
            leftChip.translationX = initialOffset
            rightChip.translationX = -initialOffset
            leftChip.visibility = VISIBLE
            rightChip.visibility = if (showAnkiChip) VISIBLE else GONE
            val interp = android.view.animation.DecelerateInterpolator()
            leftChip.animate().translationX(0f).setDuration(220L).setInterpolator(interp).start()
            rightChip.animate().translationX(0f).setDuration(220L).setInterpolator(interp).start()
        }

        private fun populateLoading() {
            val ctx = context
            loadingContent.removeAllViews()
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val spinnerSize = dp(16f)
            row.addView(ProgressBar(ctx).apply {
                isIndeterminate = true
                indeterminateTintList = ColorStateList.valueOf(accentColor)
                layoutParams = LinearLayout.LayoutParams(spinnerSize, spinnerSize)
            })
            row.addView(TextView(ctx).apply {
                text = ctx.getString(R.string.lens_loading)
                setTextColor(panelSecondaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(dp(8f), 0, 0, 0)
            })
            loadingContent.addView(row)
        }

        /** Card chrome: bg fill, zoomed pixels (ZOOM only, clipped to the
         *  rounded shape), card border. Drawn here — BEFORE [dispatchDraw]
         *  — so the pill (a child) can overhang the card and visually sit
         *  on top of the border at the top edge. The crosshair and arrow
         *  paint AFTER children in [draw] for the opposite reason. */
        override fun onDraw(canvas: Canvas) {
            cardRect.set(
                cardLeftInView.toFloat(), bodyTopOffset.toFloat(),
                cardRightInView.toFloat(), cardBottomInView.toFloat(),
            )
            canvas.drawRoundRect(cardRect, cardCornerR, cardCornerR, cardBgPaint)

            if (mode == Mode.ZOOM) {
                canvas.withClip(clipPath) {
                    translate(cardLeftInView.toFloat(), bodyTopOffset.toFloat())
                    drawZoom(this, cardW.toFloat(), lensH.toFloat())
                }
                // The inset shadow bitmap is already pre-clipped to the
                // rounded card shape; transparent pixels outside the
                // rounded edges composite as transparent.
                canvas.drawBitmap(
                    insetShadowBitmap,
                    cardLeftInView.toFloat(),
                    bodyTopOffset.toFloat(),
                    null,
                )
            }

            val inset = cardBorderPx / 2f
            val bodyTop = bodyTopOffset.toFloat()
            cardStrokeRect.set(
                cardLeftInView + inset, bodyTop + inset,
                cardRightInView - inset, bodyTop + cardHeightPx - inset,
            )
            canvas.drawRoundRect(
                cardStrokeRect,
                cardCornerR - inset, cardCornerR - inset,
                cardBorderPaint,
            )
        }

        /** Crosshair (ZOOM only) and sticky-mode arrow paint AFTER children
         *  so they layer above the pill overhang at the same y. (The pill
         *  and the crosshair don't overlap in practice — pill at the card
         *  edge, crosshair at the card center — but the ordering keeps the
         *  visual rule simple.) */
        override fun draw(canvas: Canvas) {
            super.draw(canvas)

            if (mode == Mode.ZOOM) {
                val crosshairCx = cardLeftInView + cardW / 2f
                val crosshairCy = bodyTopOffset + lensH / 2f
                canvas.drawLine(
                    crosshairCx - crosshairHalfLen, crosshairCy,
                    crosshairCx + crosshairHalfLen, crosshairCy, crosshairPaint,
                )
                canvas.drawLine(
                    crosshairCx, crosshairCy - crosshairHalfLen,
                    crosshairCx, crosshairCy + crosshairHalfLen, crosshairPaint,
                )
            }

            if (arrowVisible) drawArrow(canvas)
        }

        private fun drawArrow(canvas: Canvas) {
            val cx = arrowOffsetX.toFloat()
            val halfBase = arrowSizePx.toFloat()
            val baseY: Float
            val tipY: Float
            if (lensFlipped) {
                // Arrow rises from the card top toward the finger above it.
                baseY = bodyTopOffset.toFloat()
                tipY = baseY - arrowSizePx
            } else {
                baseY = cardBottomInView.toFloat()
                tipY = baseY + arrowSizePx
            }
            arrowPath.reset()
            arrowPath.moveTo(cx - halfBase, baseY)
            arrowPath.lineTo(cx + halfBase, baseY)
            arrowPath.lineTo(cx, tipY)
            arrowPath.close()
            canvas.drawPath(arrowPath, arrowFillPaint)

            // Stroke only the two slanted edges; the base sits on the
            // card edge and the card border draws through underneath.
            arrowPath.reset()
            arrowPath.moveTo(cx - halfBase, baseY)
            arrowPath.lineTo(cx, tipY)
            arrowPath.lineTo(cx + halfBase, baseY)
            canvas.drawPath(arrowPath, cardBorderPaint)
        }

        private fun drawZoom(canvas: Canvas, w: Float, h: Float) {
            val bitmap = sourceBitmap
            val boundsW = if (bitmap != null && !bitmap.isRecycled) bitmap.width else sourceScreenW
            val boundsH = if (bitmap != null && !bitmap.isRecycled) bitmap.height else sourceScreenH

            val srcW = (w / zoom).toInt().coerceAtLeast(1)
            val srcH = (h / zoom).toInt().coerceAtLeast(1)
            val cx = sourceX.toInt()
            val cy = sourceY.toInt()
            val srcLeft = cx - srcW / 2
            val srcTop = cy - srcH / 2
            val srcRight = srcLeft + srcW
            val srcBottom = srcTop + srcH

            val cSrcLeft = srcLeft.coerceAtLeast(0)
            val cSrcTop = srcTop.coerceAtLeast(0)
            val cSrcRight = srcRight.coerceAtMost(boundsW)
            val cSrcBottom = srcBottom.coerceAtMost(boundsH)

            val haveInSlice = cSrcLeft < cSrcRight && cSrcTop < cSrcBottom
            if (haveInSlice) {
                val srcWf = srcW.toFloat()
                val srcHf = srcH.toFloat()
                val dstInLeft = w * (cSrcLeft - srcLeft) / srcWf
                val dstInTop = h * (cSrcTop - srcTop) / srcHf
                val dstInRight = w * (cSrcRight - srcLeft) / srcWf
                val dstInBottom = h * (cSrcBottom - srcTop) / srcHf

                if (dstInTop > 0f) canvas.drawRect(0f, 0f, w, dstInTop, backgroundPaint)
                if (dstInBottom < h) canvas.drawRect(0f, dstInBottom, w, h, backgroundPaint)
                if (dstInLeft > 0f) canvas.drawRect(0f, dstInTop, dstInLeft, dstInBottom, backgroundPaint)
                if (dstInRight < w) canvas.drawRect(dstInRight, dstInTop, w, dstInBottom, backgroundPaint)

                if (bitmap != null && !bitmap.isRecycled) {
                    srcRect.set(cSrcLeft, cSrcTop, cSrcRight, cSrcBottom)
                    dstRect.set(dstInLeft, dstInTop, dstInRight, dstInBottom)
                    canvas.drawBitmap(bitmap, srcRect, dstRect, bitmapPaint)
                }
            } else {
                canvas.drawRect(0f, 0f, w, h, backgroundPaint)
            }
        }
    }
}
