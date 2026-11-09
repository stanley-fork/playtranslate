package com.playtranslate.ui

import android.content.Context
import com.playtranslate.Prefs
import com.playtranslate.language.SourceLanguageEngines
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One source text's word lookup, in its two tiers ([WordLookupsState]): the
 * analysis every text runs ([start]) and the dictionary rows only a visible
 * Words card asks for ([requestRows]). Both coroutines run on [scope], which
 * is the owner's: the results pages' [TranslationResultViewModel] (so a
 * rotation mid-lookup keeps its progress) and the over-game capture sheet
 * each hold one, and the lookup dies with its owner.
 *
 * It also holds the rule that pairs a settled lookup with its sentence's
 * translation in [LastSentenceCache] ([writeSentenceCache]), because the two
 * halves land in either order and only this class knows whether the rows of
 * a given text have settled. The owner knows the translation: it calls
 * [writeSentenceCache] when one lands, and again from [onRowsSettled].
 */
class WordLookupPipeline(private val scope: CoroutineScope) {

    private val _state = MutableStateFlow<WordLookupsState>(WordLookupsState.Idle)
    val state: StateFlow<WordLookupsState> = _state.asStateFlow()

    /** The rows of the current text just settled: the owner writes the
     *  sentence cache for the result it is showing ([writeSentenceCache]). */
    var onRowsSettled: (() -> Unit)? = null

    /** The analysis tier's coroutine ([start]). */
    private var analysisJob: Job? = null

    /** The row tier's coroutine ([requestRows]); active only while the
     *  current analysis's rows resolve (a new text or [reset] cancels it). */
    private var rowsJob: Job? = null

    /** The text handed to the latest [start]: the lookup on record covers it
     *  (analysis in flight, Analyzed, rows in flight, Settled). Null after
     *  [reset], and after a failure of either tier, so the same text asked
     *  for again re-runs a failed lookup ([covers]). */
    private var lookupText: String? = null

    /** The settled analysis of the latest [start] text, kept for the row
     *  tier (a rows failure clears [lookupText] but not this): the rows
     *  resolve [Analysis.tokens]' row tokens through the SAME language
     *  snapshot that tokenized them (see [WordLookupContext]). Set exactly
     *  when [WordLookupsState.Analyzed] is emitted, cleared with the jobs. */
    private var analysis: Analysis? = null

    private class Analysis(
        val text: String,
        val context: WordLookupContext,
        /** The source language of the same prefs snapshot as [context]; keys
         *  the styled payload's dictionary query. */
        val sourceLang: com.playtranslate.language.SourceLangId,
        val tokens: PhraseAwareTokens,
        val annotation: com.playtranslate.language.SentenceAnnotation,
    )

    /** The most recent settled rows paired with the source text they ran
     *  against. The [LastSentenceCache] words half needs BOTH this and a
     *  translation for the same text, and the two land in either order (the
     *  local dictionary lookup often settles before a network translation).
     *  Null until the rows of the current text settle, which happens only
     *  once they were requested ([requestRows]): a text whose rows nobody
     *  asked for is never cached here. */
    private var settledLookup: SettledLookup? = null

    private data class SettledLookup(
        val text: String,
        val data: LookupData,
        /** The analysis the tokens were projected from, forwarded into the
         *  cache write so an Anki send finds a matching annotation without
         *  re-annotating. */
        val annotation: com.playtranslate.language.SentenceAnnotation? = null,
    )

    /** Whether the lookup on record is for [text], whichever tier it has
     *  reached. False after a failed lookup of either tier, so its text is
     *  looked up again. [settledLookup] can't answer this: the rows are
     *  lazy, so it stays null for a text whose card is hidden. */
    fun covers(text: String): Boolean = lookupText == text

    /**
     * Run the analysis tier for [text]: tokenize + phrase detection, no
     * dictionary. Cancels any in-flight lookup of either tier. Emits
     * [WordLookupsState.Loading] immediately and [WordLookupsState.Analyzed]
     * when the analysis lands; the rows follow only through [requestRows].
     */
    fun start(text: String, appCtx: Context) {
        analysisJob?.cancel()
        rowsJob?.cancel()
        analysis = null
        // Invalidate the prior settled lookup until this text's rows land, so
        // a translation landing mid-flight can't pair the cache write with
        // stale word data.
        settledLookup = null
        lookupText = text
        _state.value = WordLookupsState.Loading
        analysisJob = scope.launch {
            try {
                val a = analyze(appCtx, text)
                analysis = a
                _state.value = WordLookupsState.Analyzed(
                    tokenSpans = a.tokens.wordTokens,
                    annotation = a.annotation,
                    phrases = a.tokens.phrases,
                )
            } catch (e: CancellationException) {
                // Caller cancelled (e.g. new text arrived) — let the next
                // emission drive state. Don't write Settled here.
                throw e
            } catch (_: Exception) {
                // Unexpected analysis failure — stop the spinner with an
                // empty result so the UI doesn't hang on Loading forever, and
                // drop the text from the record so the next ask re-runs it.
                lookupText = null
                _state.value = WordLookupsState.Settled(
                    rows = emptyList(),
                    tokenSpans = emptyList(),
                    lookupToReading = emptyMap(),
                )
            }
        }
    }

    /**
     * Run the row tier for the current analysis: one dictionary lookup per
     * unique row token (with the machine-translation fallback tiers) plus
     * the Words card's styled payload, then [WordLookupsState.Settled].
     * Called by the Words card whenever it is visible and may need rows
     * (see [WordRowsBinder.onRowsWanted]), so it is idempotent: a no-op
     * unless the state is [WordLookupsState.Analyzed] and no rows job is
     * active for it (Idle / Loading / Settled, or a repeat call while the
     * rows resolve).
     *
     * On settle, records the settled rows and tells the owner
     * ([onRowsSettled]) so its sentence cache stays in step.
     */
    fun requestRows(appCtx: Context) {
        // Analyzed is emitted only beside [analysis], and every path that
        // clears it leaves Analyzed in the same step.
        val a = analysis ?: return
        if (_state.value !is WordLookupsState.Analyzed || rowsJob?.isActive == true) return
        val wordTokens = a.tokens.wordTokens
        rowsJob = scope.launch {
            try {
                // The SAME snapshot that tokenized resolves (see
                // [WordLookupContext]): a settings change since the analysis
                // must not resolve its tokens through another engine.
                val data = resolveWordRows(appCtx, a.context, a.tokens.rowTokens)
                // Tap spans project from the word tokens (phrase-free, see
                // [PhraseAwareTokens]); the phrase occurrences ride to the
                // surface so its span computation can add tap targets for
                // single-letter phrase members ("a" in "a great deal")
                // anchored by the PHRASE's displayed range
                // (SourceWordLookup.computeTapSpans).
                val settledData = data.copy(tokenSpans = wordTokens)
                _state.value = WordLookupsState.Settled(
                    rows = data.rows,
                    tokenSpans = wordTokens,
                    lookupToReading = data.lookupToReading,
                    annotation = a.annotation,
                    phrases = a.tokens.phrases,
                    definitionLang = a.sourceLang.yomitanConsumingLang(),
                )
                // Pair the settled rows with their source text and let the
                // owner (re)write the cache. If the translation has already
                // landed for the same text, this completes the snapshot now;
                // if not, the translation's own write will.
                settledLookup = SettledLookup(a.text, settledData, a.annotation)
                onRowsSettled?.invoke()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Unexpected row failure — stop the spinner with no rows but
                // KEEP the analysis: unlike an analysis failure (which has no
                // tokens to offer and settles fully empty), the tap spans are
                // already derivable and stay tappable. No settledLookup, so
                // only the translation half is cached for this text, not its
                // words; and the text leaves the record, as on an analysis
                // failure, so the same text asked for again retries.
                lookupText = null
                _state.value = WordLookupsState.Settled(
                    rows = emptyList(),
                    tokenSpans = wordTokens,
                    lookupToReading = emptyMap(),
                    annotation = a.annotation,
                    phrases = a.tokens.phrases,
                )
            }
        }
    }

    /** Leaving the text: both tiers' jobs cancelled, nothing on record (so
     *  the next text runs its own lookup), state Idle. */
    fun reset() {
        analysisJob?.cancel()
        rowsJob?.cancel()
        analysis = null
        lookupText = null
        settledLookup = null
        _state.value = WordLookupsState.Idle
    }

    /**
     * Write [LastSentenceCache] for a sentence whose [translation] is known,
     * each half as soon as it is known. The translation half is cached as
     * soon as it is non-blank ([LastSentenceCache.setTranslation]), so the
     * lens's open-in-app is served from the cache whether or not the rows
     * ever settle (the rows are lazy: a hidden Words card never asks for
     * them). The words half joins once the rows of the SAME source text have
     * settled, as one full snapshot
     * ([LastSentenceCache.setFromTranslationResult]).
     *
     * Called by the owner when a translation lands on what it shows and from
     * [onRowsSettled], so the two land in either order: rows that outrun the
     * translation write nothing until it arrives (the owner has no
     * translation to pass, so the cache never holds a null sentence), and a
     * translation that lands first is cached without rows of its own (words
     * the cache already holds for that sentence stay) until the settle
     * writes the full snapshot.
     *
     * A blank [translation] never writes either half: [LastSentenceCache]
     * treats a cached "" as a HIT (awaitOrStartTranslation), which would
     * poison every lazy Anki translation fill. Blank means a deferred result
     * or an error-path "" ; the eventual real translation writes.
     */
    fun writeSentenceCache(original: String, translation: String, translationSource: String?) {
        if (translation.isBlank()) return
        val settled = settledLookup
        if (settled == null || settled.text != original) {
            LastSentenceCache.setTranslation(
                original = original,
                translation = translation,
                translationSource = translationSource,
            )
            return
        }
        LastSentenceCache.setFromTranslationResult(
            original = original,
            translation = translation,
            translationSource = translationSource,
            wordResults = settled.data.rows.toLegacyMap(),
            surfaceForms = settled.data.surfaces,
            wordEnrichment = settled.data.rows.toEnrichmentMap(),
            annotation = settled.annotation,
        )
    }

    /** The analysis tier: one prefs snapshot, the engine's annotation of
     *  [text] and the phrase-aware tokens projected from it. No dictionary
     *  work; [requestRows] resolves the rows from the result. */
    private suspend fun analyze(appCtx: Context, text: String): Analysis {
        // Snapshot source/target prefs ONCE, before analyzing, so both tiers
        // run against one consistent language pair even if the user changes
        // settings mid-flight (see [WordLookupContext]).
        val prefs = Prefs(appCtx)
        val sourceLang = prefs.sourceLangId
        val engine = SourceLanguageEngines.get(appCtx, sourceLang)
        val context = WordLookupContext(engine, prefs.targetLang, prefs.targetChineseVariant)
        // ONE analysis: the same FULL-depth annotation the furigana display
        // renders — its spans project the per-occurrence tokens the shared
        // resolver hydrates (dedup → parallel-lookup → RowState, see
        // [resolveWordRows]); tokenSpans round-trips so the surface can
        // derive word spans against the displayed text.
        val annotation = withContext(Dispatchers.IO) { engine.annotate(text) }
        // Phrase-aware row tokens come from the SHARED producer — the
        // sentence cache's lookupWords builds from the same one, so the
        // words panel and every Anki words payload agree on phrase policy
        // by construction.
        val tokens = phraseAwareRowTokens(engine, text, annotation)
        return Analysis(text, context, sourceLang, tokens, annotation)
    }
}
