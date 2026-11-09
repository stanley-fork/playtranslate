package com.playtranslate.ui

import android.content.Context
import android.graphics.Rect
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * The view of a [DefinitionDocument]: a frame that shows one
 * [DefinitionShell] at the height the page reports. It never scrolls
 * itself; the host's ScrollView does, as it does for every native row
 * around it.
 *
 * It is the ONE focus stop of its document. The WebView inside never takes
 * focus (see [DefinitionShell.create]), so on an Activity page the D-pad's
 * keys arrive here and [keys] walks the document's rows with them, handing
 * focus on at the first and last. A focused frame as tall as a whole word
 * list would make the host scroll to its top, so the focus rect it reports
 * is the row the cursor is on ([getFocusedRect]), and it asks for no reveal
 * of its own on focus.
 *
 * All behaviour is the document's; this class only forwards.
 */
internal class DefinitionDocumentView(context: Context) : FrameLayout(context) {

    /** The document's side of focus and keys. */
    interface Keys {
        /** A key came down while this view has focus. True when the
         *  document used it; false lets focus move on. */
        fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean

        /** Focus arrived (moving in [direction], a `View.FOCUS_*` value) or left. */
        fun onFocus(gained: Boolean, direction: Int)

        /** The cursor's rect in this view's coordinates; false for none. */
        fun cursorRect(out: Rect): Boolean

        /** The document's targets for a virtual cursor (the workspace's). */
        fun navActions(): List<NavAction>
    }

    var keys: Keys? = null

    init {
        isFocusable = true
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        // The page draws the cursor on the row it is on; the platform's
        // highlight would wash the whole list.
        defaultFocusHighlightEnabled = false
        setRevealOnFocusHint(false)
    }

    /** Shows [shell]'s page in this frame, in place of any other. */
    fun show(shell: DefinitionShell) {
        removeAllViews()
        (shell.webView.parent as? ViewGroup)?.removeView(shell.webView)
        addView(shell.webView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun navActions(): List<NavAction> = keys?.navActions().orEmpty()

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        keys?.onKeyDown(keyCode, event) == true || super.onKeyDown(keyCode, event)

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        keys?.onFocus(gainFocus, direction)
    }

    override fun getFocusedRect(r: Rect) {
        if (keys?.cursorRect(r) != true) super.getFocusedRect(r)
    }
}
