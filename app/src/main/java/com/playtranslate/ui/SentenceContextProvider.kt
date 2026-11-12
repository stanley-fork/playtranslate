package com.playtranslate.ui

/**
 * Implemented by activities that host an embedded
 * [WordDetailBottomSheet] and can supply the current sentence
 * context for Anki export on demand. Replaces the timed push
 * pipeline (`updateSentenceContext`) that previously fed shadow
 * fields on the embedded sheet.
 *
 * The embedded sheet calls [currentSentenceContext] at Anki-button
 * tap time. Implementations read live state (e.g. their
 * [TranslationResultViewModel]) and fall back to launch-time intent
 * extras when the live state hasn't settled yet.
 */
interface SentenceContextProvider {
    fun currentSentenceContext(): SentenceContext
}

data class SentenceContext(
    val original: String?,
    val translation: String?,
    val wordResults: Map<String, Triple<String, String, Int>>?,
    /** Word → surface form, snapshotted atomically with
     *  [wordResults]. Required for one-tap Anki sends so the card
     *  doesn't pair the current sentence's words with stale surface
     *  forms from a different sentence sitting in
     *  [LastSentenceCache.surfaceForms]. Null when [wordResults] is
     *  null OR when the host can't supply matching surfaces from the
     *  same source. */
    val surfaceForms: Map<String, String>? = null,
    /** Word → pitch + frequencies, snapshotted atomically with
     *  [wordResults] (same rationale as [surfaceForms]); feeds the
     *  sentence-card pitch/frequency Anki fields. Null when unavailable. */
    val wordEnrichment: Map<String, WordEnrichment>? = null,
    /** The result's deferred-translation payload, snapshotted with
     *  [original] (it is only meaningful alongside its own result's text).
     *  Anki sends route it through the deferred completion so a
     *  hidden-section capture's History rows fill instead of a bare
     *  translateOnce leaving them null. Null when the result carries none. */
    val pending: com.playtranslate.model.PendingTranslation? = null,
    /** What the explain questions of this sentence's words brought, as far
     *  as whoever made the context held an answer: each under the word it
     *  is about, as a card names that word ([CardTarget]). The note a card
     *  made for one of those words starts with. They are answers about
     *  [original] and travel only with it: a context put together for
     *  another text takes none of them along ([explanationsAbout]). */
    val explanations: Map<CardTarget, String> = emptyMap(),
)

/**
 * This context's [SentenceContext.explanations] as they stand beside
 * [sentence]: all of them when [sentence] is the text they were asked
 * about ([SentenceContext.original]), none when it is another, such as a
 * sentence edited since. An answer about a word in one sentence is no
 * answer about it in another, as on the surface it was read from
 * ([WordRowList]). The one rule for every place that lays a live sentence
 * over the one a page was opened with
 * ([TranslationResultViewModel.sentenceContext],
 * [WordDetailBinder.pageSentence]), so that a card is never handed its
 * sentence from one of them and its note from the other.
 */
fun SentenceContext.explanationsAbout(sentence: String?): Map<CardTarget, String> =
    if (sentence == original) explanations else emptyMap()

/**
 * This context holding [answer] as well: what was answered about [target]
 * in its sentence, where the context is made (a row's answer at the row's
 * tap). The context itself with no answer.
 */
fun SentenceContext.explaining(target: CardTarget, answer: String?): SentenceContext =
    if (answer.isNullOrBlank()) this else copy(explanations = explanations + (target to answer))
