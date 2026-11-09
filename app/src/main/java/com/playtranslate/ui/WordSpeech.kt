package com.playtranslate.ui

import com.playtranslate.audio.AudioRequest
import com.playtranslate.audio.PlayOutcome
import com.playtranslate.audio.PronunciationPlayer
import com.playtranslate.language.SourceLangId
import com.playtranslate.tts.ttsTextForWord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A word to speak and the language it is in. [reading] is the kana reading
 *  shown to the user, when known; for Japanese it is spoken in place of the
 *  kanji surface so the audio matches the displayed reading (see
 *  [ttsTextForWord]). Null when there is no reading to prefer. */
data class SpeakRequest(val word: String, val lang: SourceLangId, val reading: String? = null)

/**
 * Binds the Speak buttons of a [MagnifierLens]'s rows to the audio engine.
 *
 * The constructor installs the lens's `onSpeak`: it speaks the tapped row's
 * word and returns when the audio is done; if no engine is available, or it
 * has no voice for the language, the standard TTS alert is shown on
 * [alertTarget]. The row's spinner and the guard against overlapping taps
 * are the lens's word list's ([WordRowList]). One instance per lens; call
 * [release] when the lens is torn down.
 *
 * [request] supplies the word of a row (null for the tapped unit, else the
 * secondary's index, as the lens's callbacks count them) and its language,
 * evaluated at tap time, or null when there is nothing to speak.
 */
class LensSpeech(
    lens: MagnifierLens,
    alertTarget: TtsAlertTarget,
    request: (secondary: Int?) -> SpeakRequest?,
) {
    init {
        lens.onSpeak = { secondary ->
            request(secondary)?.let { speakWord(alertTarget, it) { lens.dismiss() } }
        }
    }

    /** Stop playback. Call when the lens is gone (its list cancels the
     *  speak still in flight with its window). */
    fun release() {
        PronunciationPlayer.stop()
    }
}

/**
 * Speak a single dictionary word and surface the standard TTS alerts on
 * [target]. Shared by every word row's Speak button, the lens's
 * ([LensSpeech]) and the word lists', so the paths can't diverge. The
 * caller owns the loading indicator and re-entrancy guard — this just
 * performs the request and presents any alert. [onNoEngine] runs after the
 * "no engine" alert is shown (the lens dismisses itself; a word list passes
 * a no-op).
 */
suspend fun speakWord(
    target: TtsAlertTarget,
    request: SpeakRequest,
    onNoEngine: () -> Unit = {},
) {
    val outcome = PronunciationPlayer.play(
        target.context,
        AudioRequest.word(request.word, request.reading, request.lang),
    )
    withContext(Dispatchers.Main) {
        when (outcome) {
            PlayOutcome.TtsNoEngine -> showTtsNoEngineDialog(target, onNoEngine)
            is PlayOutcome.TtsLanguageUnsupported ->
                showTtsLanguageUnsupportedDialog(target, request.lang, outcome.engineLabel)
            else -> { /* played, fell back to a recording, or a recoverable miss */ }
        }
    }
}
