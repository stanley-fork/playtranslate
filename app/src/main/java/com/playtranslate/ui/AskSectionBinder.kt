package com.playtranslate.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.ask.AskPreset
import com.playtranslate.ask.AskPresets
import com.playtranslate.ask.AskQuestion
import com.playtranslate.ask.AskSession
import com.playtranslate.ask.AskState
import com.playtranslate.themeColor
import com.playtranslate.translation.TranslationBackendRegistry
import com.playtranslate.translation.TranslationError

/**
 * The results' Ask section (section_ask.xml): the user's LLM asked about the
 * sentence on show, with a ready-made question ([AskPresets]) or their own
 * words, and its answer. Host-agnostic like [WordRowsBinder]: every surface
 * that shows the results sections renders its [AskSession] through here, so
 * the section can't drift between them. Views are found from [root].
 *
 * The conversation is the session's and this binder only draws it: the host
 * collects [AskSession.state] into [render], and the last state rendered is
 * the one thing about it kept here. The card shows one of two groups. While
 * nothing is asked, the questions: the presets as chips, a hairline, then
 * the field and its send button. Otherwise, the question on screen: its
 * title with an X that puts it away, in a row as tall as the chips', then
 * who is being asked, the answer and who gave it, or the failure and Try
 * again. An answer takes the questions' place rather than sitting under
 * them; X brings them back.
 *
 * Hidden is DERIVED, never stored ([isHidden]): the eye's pref AND a session
 * with nothing on screen. A collapsed section shows its card exactly while a
 * question is on screen, so a question asked from the collapsed header shows
 * its answer without the pref being written, and putting the answer away
 * collapses the section again. The eye acts on what the card is SHOWING:
 *
 * | pref hidden | state    | card               | eye     | a tap on the eye               |
 * |-------------|----------|--------------------|---------|--------------------------------|
 * | no          | Idle     | the questions      | open    | pref = hidden                  |
 * | no          | not Idle | the answer         | open    | pref = hidden, session closed  |
 * | yes         | Idle     | GONE (header only) | crossed | pref = shown                   |
 * | yes         | not Idle | the answer         | open    | session closed, pref untouched |
 *
 * The header is a [SectionHeaderRow] without ⋯. Collapsed, it carries the
 * presets as text pills, in their order and as many as fit beside the label
 * and the eye, so a question is one tap away without opening the section; a
 * pill that doesn't fit is simply not shown, since the opened section lists
 * every preset. Expanded, it is the label and the eye. The pills are fixed
 * slots, one per preset, given the preset in their place whenever the order
 * is read ([applyPresets]).
 *
 * Nothing here shows an alert, starts an Activity or touches a window. A
 * question reached for with no LLM to put it to (a preset, the field, a
 * send) is the host's to explain ([Host.onLlmNeeded]), and a field that
 * needs a keyboard is the host's to give one ([Host.onFieldFocusChanged]).
 */
class AskSectionBinder(
    root: View,
    private val ctx: Context,
    private val prefs: Prefs,
    private val session: AskSession,
    private val host: Host,
    /** Whether the user has an LLM a question can be put to. */
    private val hasLlm: () -> Boolean = { TranslationBackendRegistry.hasCompleter() },
) {
    interface Host {
        /** Any user interaction with the section (live-mode hosts pause on it). */
        fun onInteraction() {}

        /** The user reached for a question (a preset, or the field) with no
         *  LLM set up: tell them. */
        fun onLlmNeeded()

        /** The question field took or lost input focus: a host whose window
         *  must change to take a keyboard does it here. */
        fun onFieldFocusChanged(focused: Boolean) {}

        /** The card's height or visibility may have changed (a host that
         *  sizes itself to its content). It may not have laid out yet. */
        fun onHeightChanged() {}
    }

    private val header: SectionHeaderRow = root.findViewById(R.id.askHeaderRow)
    private val card: View = root.findViewById(R.id.cardAsk)
    private val idleGroup: View = root.findViewById(R.id.askIdle)
    private val chipRow: FlowLayout = root.findViewById(R.id.askPresetChips)
    private val questionField: EditText = root.findViewById(R.id.etAskQuestion)
    private val sendButton: ImageButton = root.findViewById(R.id.btnAskSend)
    private val answerGroup: View = root.findViewById(R.id.askAnswer)
    private val titleText: TextView = root.findViewById(R.id.tvAskTitle)
    private val closeButton: ImageButton = root.findViewById(R.id.btnAskClose)
    private val warningText: TextView = root.findViewById(R.id.tvAskWarning)
    private val bodyText: TextView = root.findViewById(R.id.tvAskBody)
    private val retryButton: TextView = root.findViewById(R.id.btnAskRetry)
    private val footerText: TextView = root.findViewById(R.id.tvAskFooter)

    private val chipGapPx = (CHIP_GAP_DP * ctx.resources.displayMetrics.density).toInt()

    /** The collapsed header's pills: one slot per preset there is now, in
     *  order. A slot is a place, not a preset: [applyPresets] gives each the
     *  preset in its place. The icon is what a ⋯ menu row would show, and
     *  this header has no ⋯, so any glyph serves. */
    private val slots: List<HeaderAction> = AskPresets.ordered(ctx, prefs).map { preset ->
        HeaderAction(
            View.generateViewId(), R.drawable.ic_send, preset.title,
            presentation = HeaderAction.Presentation.TEXT_PILL,
        )
    }

    /** The section's eye: pinned, so it sits at the header's end like the
     *  other sections' eyes. The row tints and draws it from this model. */
    private val eye = HeaderAction(
        R.id.btnToggleAsk, R.drawable.ic_visibility,
        ctx.getString(R.string.cd_toggle_ask_visibility), pinned = true,
    )

    /** The state [render] last drew: Idle until the first render. */
    private var state: AskState = AskState.Idle

    /** Whether the card is hidden: the pref, and nothing on screen (class
     *  doc). A question on screen keeps the card up whatever the pref says. */
    val isHidden: Boolean get() = prefs.hideAskSection && state is AskState.Idle

    val fieldHasFocus: Boolean get() = questionField.isFocused

    init {
        header.overflowEnabled = false
        header.setActions(slots + eye, R.id.btnMoreAsk)
        eye.onClick = {
            if (isHidden) {
                prefs.hideAskSection = false
            } else {
                // A shown card is hidden. The pref says so unless it already
                // does (a collapsed section a question keeps open), and the
                // question goes, or it would keep the card up.
                clearFieldFocus()
                if (!prefs.hideAskSection) prefs.hideAskSection = true
                session.close()
            }
            // From the state rendered so far: the session's emission, when
            // it closes, re-renders through the host.
            applyVisibility()
            host.onHeightChanged()
        }

        chipRow.lineSpacingPx = chipGapPx
        // Tinted in code, not with app:tint: the over-game panel's plain
        // inflater drops app:tint (TranslationSectionBinder's gear).
        closeButton.imageTintList = ColorStateList.valueOf(ctx.themeColor(R.attr.ptTextMuted))
        closeButton.setOnClickListener {
            host.onInteraction()
            session.close()
        }
        retryButton.setOnClickListener {
            host.onInteraction()
            session.retry()
        }

        // With no LLM a touch on the field leads to the host's explanation,
        // not to a keyboard: every event of it is kept from the field, so it
        // neither takes focus nor starts a selection, and its end is a click
        // (below). Decided where the touch begins, for the whole of it: the
        // answer costs a file read per on-device model, which a drag that
        // starts on the field would pay at every move.
        var keptFromField = false
        questionField.setOnTouchListener { v, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) keptFromField = !hasLlm()
            if (!keptFromField) return@setOnTouchListener false
            if (event.actionMasked == MotionEvent.ACTION_UP) v.performClick()
            true
        }
        // A click is how the field is reached without a finger on it: a
        // controller's A clicks its target ([NavAction.activate]), and so
        // does the end of a touch with no LLM. With an LLM a finger's first
        // touch focuses the field itself and clicks nothing.
        questionField.setOnClickListener { reachForField() }
        questionField.setOnFocusChangeListener { _, focused ->
            // Starting to type is an interaction, like a tap on a chip: a
            // live host holds the sentence the question is being written
            // about, which the next line would otherwise replace under it.
            if (focused) host.onInteraction()
            host.onFieldFocusChanged(focused)
        }
        questionField.doAfterTextChanged { syncSend() }
        questionField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_SEND) return@setOnEditorActionListener false
            send()
            true
        }
        sendButton.setOnClickListener { send() }

        syncSend()
        applyPresets()
        applyVisibility()
    }

    /** Draw [state]: the host's collection of the session's state calls
     *  this with every state, the current one first. */
    fun render(state: AskState) {
        this.state = state
        // A question on screen takes the field's place.
        if (state !is AskState.Idle) clearFieldFocus()
        idleGroup.isVisible = state is AskState.Idle
        answerGroup.isVisible = state !is AskState.Idle
        when (state) {
            AskState.Idle -> applyPresets()
            is AskState.Asking -> showQuestion(
                state.question,
                state.service?.let { ctx.getString(R.string.ask_status_asking, it) }
                    ?: ctx.getString(R.string.ask_status_asking_unnamed),
                R.attr.ptText,
            )
            is AskState.Answered -> showQuestion(
                state.question, state.text, R.attr.ptText,
                footer = ctx.getString(R.string.ask_answered_by, state.answeredBy),
            )
            is AskState.Failed -> showQuestion(
                state.question, failureText(state), R.attr.ptTextMuted, retry = true,
            )
        }
        applySpoilerWarning()
        applyVisibility()
        host.onHeightChanged()
    }

    /** Re-read whether the Translation section is hidden, which is what
     *  shows the spoiler line over a question. The host calls this when that
     *  section's eye is tapped; the line changes the card's height, so the
     *  host hears of it when it comes or goes. */
    fun syncSpoilerWarning() {
        if (applySpoilerWarning()) host.onHeightChanged()
    }

    /** Drop the field's focus and its keyboard: a host's Back or B, and here
     *  whenever the field is about to go out of sight (a question takes its
     *  place, the eye hides the card). A view that stops showing drops its
     *  focus by itself and leaves the keyboard up, so each of those asks
     *  first. A no-op when the field has no focus. */
    fun clearFieldFocus() {
        if (!questionField.isFocused) return
        // The keyboard first, while the field is still the view it serves.
        ctx.getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(questionField.windowToken, 0)
        questionField.clearFocus()
    }

    /** What a controller cursor can reach here, in visual order, and only
     *  what is on screen: the header's (its shown pills, then the eye), then
     *  the card's. One of the card's two groups shows, so keeping the shown
     *  views of both gives the chips, the field and send, or X and Try
     *  again; send has nothing to do (and is no target) while disabled. The
     *  field's A is a click ([reachForField]). */
    fun navigableActions(): List<NavAction> {
        val chips = (0 until chipRow.childCount).map(chipRow::getChildAt)
        val inCard = chips + listOf(questionField, sendButton, closeButton, retryButton)
        return header.navActions() + inCard.filter { it.isShown && it.isEnabled }.map { NavAction(it) }
    }

    // ── Rendering ────────────────────────────────────────────────────────

    /** Reflect [isHidden]: the card, the eye, the label, and the header's
     *  pills, which are offered only while the card is hidden. */
    private fun applyVisibility() {
        val hidden = isHidden
        card.visibility = if (hidden) View.GONE else View.VISIBLE
        eye.icon = if (hidden) R.drawable.ic_visibility_off else R.drawable.ic_visibility
        header.labelDimmed = hidden
        for (slot in slots) slot.available = hidden
    }

    /** The spoiler line shows over a question while the Translation section
     *  is hidden, and never with nothing asked. Returns whether it changed. */
    private fun applySpoilerWarning(): Boolean {
        val show = state !is AskState.Idle && prefs.hideTranslationSection
        if (warningText.isVisible == show) return false
        warningText.isVisible = show
        return true
    }

    /** The presets in [AskPresets]' order, read afresh: the card's chips
     *  rebuilt, and each header slot given the preset in its place. Run at
     *  construction and on every Idle render, the first moment a preset just
     *  asked (now leading the order) can be seen again. */
    private fun applyPresets() {
        val presets = AskPresets.ordered(ctx, prefs)
        chipRow.removeAllViews()
        val inflater = LayoutInflater.from(chipRow.context)
        for (preset in presets) {
            val chip = inflater.inflate(R.layout.ask_preset_chip, chipRow, false) as TextView
            (chip.layoutParams as ViewGroup.MarginLayoutParams).marginEnd = chipGapPx
            chip.text = preset.title
            chip.setOnClickListener { askPreset(preset) }
            chipRow.addView(chip)
        }
        for ((slot, preset) in slots.zip(presets)) {
            slot.label = preset.title
            slot.contentDescription = preset.title
            slot.onClick = { askPreset(preset) }
        }
    }

    /** The answer group for [question]: its title, then [body] in
     *  [bodyColor], with [footer] under it and Try again where given. */
    private fun showQuestion(
        question: AskQuestion,
        body: CharSequence,
        @AttrRes bodyColor: Int,
        footer: CharSequence? = null,
        retry: Boolean = false,
    ) {
        titleText.text = question.title
        titleText.setTypeface(null, if (readsInItalic(question.title)) Typeface.ITALIC else Typeface.NORMAL)
        bodyText.text = body
        bodyText.setTextColor(ctx.themeColor(bodyColor))
        footerText.text = footer
        footerText.isVisible = footer != null
        retryButton.isVisible = retry
    }

    /** Whether [text] is drawn in italic: when every letter in it is Latin
     *  script, the rule WordDetailBinder's example translations follow by
     *  language (targetSupportsItalics). CJK has no italic, so Android
     *  would slant its glyphs, and Cyrillic's and Greek's italics are
     *  different letterforms. The text decides rather than a language
     *  setting: a preset's title is in the app's language, a question of
     *  the user's own in whatever they typed. */
    private fun readsInItalic(text: CharSequence): Boolean =
        text.codePoints().noneMatch {
            Character.isLetter(it) && Character.UnicodeScript.of(it) != Character.UnicodeScript.LATIN
        }

    /** The failure in words: the generic line, and under it the service's
     *  reason when the session says which online service refused and how. */
    private fun failureText(failed: AskState.Failed): String {
        val generic = ctx.getString(R.string.ask_failed)
        val service = failed.service ?: return generic
        val failure = failed.failure ?: return generic
        // The error pill's words for that refusal. An error's id only keys
        // the pill's once-per-outage rule, which an answer never enters, and
        // the session carries the service's name alone, so the name stands
        // in for it.
        val reason = TranslationErrorMessages.text(ctx, TranslationError.Service(service, service, failure))
        return "$generic\n$reason"
    }

    // ── Asking ───────────────────────────────────────────────────────────

    private fun askPreset(preset: AskPreset) {
        host.onInteraction()
        if (!hasLlm()) {
            host.onLlmNeeded()
            return
        }
        AskPresets.markAsked(prefs, preset)
        session.ask(AskQuestion.Preset(preset))
    }

    /** The field clicked: a controller's A ([navigableActions]), or the end
     *  of a touch with no LLM. With no LLM, the host's explanation instead
     *  of a keyboard; with one, the focus a finger's touch would give it. */
    private fun reachForField() {
        if (!hasLlm()) {
            host.onInteraction()
            host.onLlmNeeded()
            return
        }
        questionField.requestFocus()
    }

    /** Send the typed question, trimmed: nothing for a blank one. */
    private fun send() {
        val text = questionField.text.toString().trim()
        if (text.isEmpty()) return
        host.onInteraction()
        if (!hasLlm()) {
            host.onLlmNeeded()
            return
        }
        questionField.text.clear()
        session.ask(AskQuestion.Custom(text))
    }

    /** Send is offered while there is a question to send: enabled and in
     *  the accent, otherwise disabled in the hint colour. */
    private fun syncSend() {
        val sendable = questionField.text.isNotBlank()
        sendButton.isEnabled = sendable
        sendButton.imageTintList = ColorStateList.valueOf(
            ctx.themeColor(if (sendable) R.attr.ptAccent else R.attr.ptTextHint),
        )
    }

    private companion object {
        /** Between two chips, and between two lines of them. */
        const val CHIP_GAP_DP = 6f
    }
}
