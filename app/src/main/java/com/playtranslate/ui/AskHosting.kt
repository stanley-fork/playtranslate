package com.playtranslate.ui

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import com.playtranslate.PlayTranslateApplication
import com.playtranslate.R
import com.playtranslate.ask.AskSentence
import com.playtranslate.language.ChineseScriptVariant
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.model.TranslationLangContext
import com.playtranslate.themeColor

// What every surface that hosts the Ask section needs beside its binder
// ([AskSectionBinder]): how a result's text becomes the sentence a question
// is about, and the one alert the section can raise. A surface supplies only
// what is its own: where the alert shows, and how Translation services opens
// from there.

/** The sentence an Ask question is about, for [text] read under
 *  [langContext]: the pair in the terms the prompts take, the source as the
 *  code translation requests name it by, and the target with its script,
 *  a Chinese target's variant code. Translation requests all say "zh" and
 *  are converted to the reader's script afterwards; an answer is written
 *  in it as it comes, so its prompt has to name the script. */
internal fun askSentence(text: String, langContext: TranslationLangContext): AskSentence =
    AskSentence(
        text = text,
        sourceLang = SourceLanguageProfiles[langContext.sourceLangId].translationCode,
        targetLang = if (ChineseScriptVariant.isChineseTarget(langContext.targetLang)) {
            langContext.chineseVariant.code
        } else {
            langContext.targetLang
        },
    )

/**
 * The "Ask needs an AI model" alert, on this builder: its words, and a
 * button that opens Translation services, where an online service is added
 * or an on-device model switched on. Shown when the user reaches for a
 * question (a ready-made one, or the field) with nothing that could answer
 * it. The host shows the built alert its own way and passes how the
 * settings page opens from its surface.
 */
internal fun OverlayAlert.Builder.askNeedsLlm(
    ctx: Context,
    openTranslationServices: () -> Unit,
): OverlayAlert.Builder =
    setTitle(ctx.getString(R.string.ask_no_llm_title))
        .setMessage(ctx.getString(R.string.ask_no_llm_message))
        .addButton(
            ctx.getString(R.string.ask_no_llm_open_services),
            ctx.themeColor(R.attr.ptAccent),
        ) { openTranslationServices() }
        .addCancelButton(ctx.getString(R.string.btn_cancel))

/**
 * The "Ask needs an AI model" alert on [target], for a surface with no Ask
 * section whose alert it could borrow (the drag lens: over a game, on the
 * camera's frozen frame, on an imported image). Its button starts
 * Translation services from the activity where the surface lives in one,
 * and from an over-game surface as a new task on the foreground display
 * ([launchOnForegroundDisplay]).
 *
 * Reached only from a tap on a row's explain button and then a tap on the
 * alert's own button, so the launch is the user's, with our alert on screen
 * a moment before: never from a lifecycle event, a reconnect or a timer.
 */
internal fun showAskNeedsLlm(target: TtsAlertTarget) = showAlertOn(target) {
    askNeedsLlm(target.context) {
        when (target) {
            is TtsAlertTarget.InActivity ->
                target.activity.startActivity(Intent(target.activity, TranslationServicesActivity::class.java))
            is TtsAlertTarget.Overlay -> launchOnForegroundDisplay(
                target.context,
                Intent(target.context.applicationContext, TranslationServicesActivity::class.java),
                target.displayId,
            )
        }
    }
}

/** Starts [intent] as a new task on the foreground display (else
 *  [displayId], the launching surface's own): the one way an app page opens
 *  from an over-game surface, the capture sheet's pages and the drag lens's
 *  alert alike. */
internal fun launchOnForegroundDisplay(ctx: Context, intent: Intent, displayId: Int) {
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val targetDisplay = PlayTranslateApplication.foregroundDisplayId() ?: displayId
    val opts = ActivityOptions.makeBasic().setLaunchDisplayId(targetDisplay).toBundle()
    ctx.applicationContext.startActivity(intent, opts)
}
