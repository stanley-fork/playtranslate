package com.playtranslate.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.card.MaterialCardView
import com.playtranslate.Prefs
import com.playtranslate.AnkiManager
import com.playtranslate.R
import com.playtranslate.language.ChineseScriptVariant
import com.playtranslate.language.SourceLangId
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Standalone dictionary-lookup screen: type or paste source-language text and
 * get live word results. The results are a [WordRowList], the row the
 * translation result's Words card draws, with its tap / speak / Anki actions, driven by [DictionaryLookupViewModel] (which
 * auto-selects prefix-completion vs. sentence-segmentation per query).
 *
 * Reached from Settings → Debug for now (debug builds only). Subclasses
 * [SettingsSubPageActivity] for the themed toolbar, edge-to-edge insets
 * (including IME), and back navigation.
 */
class DictionaryLookupActivity : SettingsSubPageActivity() {

    override val layoutResId = R.layout.activity_dictionary_lookup

    private val vm: DictionaryLookupViewModel by viewModels()
    private val prefs by lazy { Prefs(this) }

    private lateinit var resultsContainer: LinearLayout
    private lateinit var resultsCard: MaterialCardView
    private lateinit var countView: TextView
    private lateinit var loadingView: View

    /** The results: one definitions document, with the deck badges and each
     *  row's speak spinner. */
    private lateinit var list: WordRowList<RowState>

    /** Identity guard so a repeated row list isn't drawn again. */
    private var lastRenderedRows: List<RowState>? = null

    override fun onContentCreated(savedInstanceState: Bundle?) {
        resultsContainer = findViewById(R.id.llResults)
        resultsCard = findViewById(R.id.cardResults)
        countView = findViewById(R.id.tvCount)
        loadingView = findViewById(R.id.pbLoading)
        list = wordRowList(
            this, resultsContainer, lifecycleScope,
            object : WordRowList.Host<RowState> {
                override val isAlive: Boolean get() = !isDestroyed
                override val deckBadges: Boolean get() = true

                // Dictionary results never stub or hide: the user searched
                // for this word on purpose. The second button adds it to Anki.
                override fun trailing(row: RowState): DefRow.Trailing = DefRow.Trailing.ANKI
                override fun onOpen(row: RowState) = openWordDetail(row)
                override fun onTrailing(row: RowState, shown: DefRow.Trailing) = launchAnki(row)
                override suspend fun speak(row: RowState) = speakWord(
                    TtsAlertTarget.InActivity(this@DictionaryLookupActivity),
                    SpeakRequest(row.displayWord, prefs.sourceLangId, reading = row.reading.ifEmpty { null }),
                )
            },
            WordRowList.LARGE_SCALE,
        )

        findViewById<TextView>(R.id.tvLangPair).text = languagePairLabel()

        val search = findViewById<EditText>(R.id.etSearch)
        val clear = findViewById<ImageButton>(R.id.btnClear)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString().orEmpty()
                clear.isVisible = text.isNotEmpty()
                vm.setQuery(text)
            }
        })
        clear.setOnClickListener { search.setText("") }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect { render(it) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Deck membership can change while away (added here or in AnkiDroid);
        // re-evaluate so badges aren't stuck on a stale pre-add state.
        list.refreshDeckBadges()
    }

    override fun onDestroy() {
        list.release()
        super.onDestroy()
    }

    // ── Rendering ─────────────────────────────────────────────────────────

    private fun render(state: DictionaryLookupViewModel.UiState) {
        // The count label is the single status surface: it shows the entry
        // count, "No results", or an error — and is replaced in place by a
        // small spinner while loading. The idle (blank-query) state shows
        // nothing. The card is visible only when there are rows to show.
        loadingView.isVisible = state.isLoading
        countView.isVisible = !state.isLoading
        countView.text = when {
            state.isLoading -> ""
            state.rows.isNotEmpty() -> resources.getQuantityString(R.plurals.dictionary_entries_count, state.rows.size, state.rows.size)
            state.hasError -> getString(R.string.dictionary_status_error)
            state.query.isBlank() -> ""
            else -> getString(R.string.dictionary_status_no_results)
        }
        resultsCard.isVisible = state.rows.isNotEmpty()

        renderRows(state.rows)
    }

    private fun renderRows(rows: List<RowState>) {
        // Same list instance (loading emission keeps prior rows) → no rebuild.
        if (rows === lastRenderedRows) return
        lastRenderedRows = rows

        // Under the language the query was looked up in: the list fetches
        // each row's dictionary content with it. No rows lets the page go.
        list.setRows(rows, prefs.sourceLangId.yomitanConsumingLang())
    }

    private fun openWordDetail(rowState: RowState) {
        val state = vm.state.value
        // Sentence context only makes sense for the segmentation mode, where
        // the query IS a sentence; prefix completions have no enclosing text.
        val sentenceOriginal =
            if (state.mode == DictionaryLookupViewModel.Mode.SEGMENTATION) state.query else null
        WordDetailBottomSheet.newInstance(
            word = rowState.displayWord,
            reading = rowState.reading.ifEmpty { null },
            surface = rowState.surface,
            screenshotPath = null,
            sentenceOriginal = sentenceOriginal,
            sentenceTranslation = null,
            sentenceWordResults = state.rows.toLegacyMap(),
            lookupForm = rowState.lookupForm,
            lookupReading = rowState.lookupReading,
        ).show(supportFragmentManager, WordDetailBottomSheet.TAG)
    }

    private fun launchAnki(rowState: RowState) {
        val ankiManager = AnkiManager(this)
        if (!ankiManager.isAnkiDroidInstalled()) {
            showAnkiNotInstalledDialog(this)
            return
        }
        val state = vm.state.value
        val readingForExtra = rowState.reading
            .takeIf { it.isNotEmpty() && it != rowState.displayWord } ?: ""
        val intent = Intent(this, AnkiPermissionActivity::class.java).apply {
            putExtra(WordAnkiReviewActivity.EXTRA_WORD, rowState.displayWord)
            putExtra(WordAnkiReviewActivity.EXTRA_READING, readingForExtra)
            putExtra(WordAnkiReviewActivity.EXTRA_POS, rowState.ankiPos)
            putExtra(WordAnkiReviewActivity.EXTRA_DEFINITION, rowState.meaning)
            putExtra(WordAnkiReviewActivity.EXTRA_FREQ_SCORE, rowState.freqScore)
            if (state.mode == DictionaryLookupViewModel.Mode.SEGMENTATION) {
                putExtra(WordAnkiReviewActivity.EXTRA_SENTENCE_ORIGINAL, state.query)
            }
            putExtra(WordAnkiReviewActivity.EXTRA_SOURCE_LANG, prefs.sourceLangId.code)
        }
        startActivity(intent)
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** "Japanese → English"-style label, matching how Settings resolves the
     *  source/target display names (system locale). */
    private fun languagePairLabel(): String {
        val source = SourceLangId.fromCode(prefs.sourceLang)?.displayName()
            ?: Locale.forLanguageTag(prefs.sourceLang)
                .getDisplayLanguage(Locale.getDefault())
                .replaceFirstChar { it.uppercase(Locale.getDefault()) }
        val target = ChineseScriptVariant.targetDisplayName(
            prefs.targetLang,
            prefs.targetChineseVariant,
            Locale.forLanguageTag(prefs.targetLang),
        )
        return getString(R.string.dictionary_lang_pair, source, target)
    }
}
