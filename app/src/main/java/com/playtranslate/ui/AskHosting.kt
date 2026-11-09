package com.playtranslate.ui

import android.content.Context
import com.playtranslate.R
import com.playtranslate.ask.AskSentence
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
 *  code translation requests name it by. */
internal fun askSentence(text: String, langContext: TranslationLangContext): AskSentence =
    AskSentence(
        text = text,
        sourceLang = SourceLanguageProfiles[langContext.sourceLangId].translationCode,
        targetLang = langContext.targetLang,
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
