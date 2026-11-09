package com.playtranslate.ui

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.language.SentenceAnnotation
import com.playtranslate.language.SourceLangId
import com.playtranslate.model.ImportedSense
import com.playtranslate.model.ImportedSenseGroup
import com.playtranslate.themeColor
import com.playtranslate.vocab.HiddenWordsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * Pins [WordRowsBinder]'s hidden-words contract, the results page's rules
 * now shared with the workspace's Sentence page: a fresh list drawn against
 * a LOADED set orders visible words first and hidden words last, each in
 * lookup order, with the hidden ones as stubs; a store change after the
 * render re-stubs the row in place (a toggle never moves a row); a list
 * drawn BEFORE the language's set loaded is provisional, and the load's
 * bump re-renders it ordered exactly once; a row tap reaches the host with
 * the row; Loading / Idle clear the rows and keep the page for the lookup
 * that is running; and a lookup that finds no words lets the page go.
 *
 * And the card's own visibility: the effective hidden state is the pref OR
 * the live session's auto-hide; an Analyzed lookup asks for its rows only
 * while the card is shown; live start auto-hides without writing the pref
 * and is edge-triggered; the eye writes the opposite of what the card is
 * showing and spends the auto-hide; and the header's label recedes to the
 * hint colour while the card is hidden (by the eye or the auto-hide) and is
 * muted while it shows.
 */
@RunWith(RobolectricTestRunner::class)
class WordRowsBinderTest {

    class Host : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_PlayTranslate)
            super.onCreate(savedInstanceState)
        }
    }

    private val app: Context = ApplicationProvider.getApplicationContext()

    private lateinit var controller: ActivityController<Host>
    private lateinit var activity: Host
    private lateinit var root: View
    private lateinit var binder: WordRowsBinder
    private val tapped = mutableListOf<String>()
    /** [WordRowsBinder.onRowsWanted] invocations. */
    private var rowsWanted = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val rows = listOf(
        row("猫", "ねこ", "cat"),
        row("食べる", "たべる", "to eat"),
        row("魚", "さかな", "fish"),
    )

    private fun row(word: String, reading: String, meaning: String) = RowState(
        displayWord = word, reading = reading, meaning = meaning, senses = emptyList(),
        freqScore = 3, isCommon = true, surface = word,
    )

    @Before
    fun setUp(): Unit = runBlocking {
        HiddenWordsStore.resetForTest(app)
        clearPrefs()
        // A new install starts with the card collapsed; these tests are about a shown card.
        Prefs(app).hideWordsSection = false
        controller = Robolectric.buildActivity(Host::class.java).setup()
        activity = controller.get()
        root = LayoutInflater.from(activity).inflate(R.layout.fragment_translation_result, null)
        binder = newBinder()
    }

    private fun newBinder() = WordRowsBinder(
        root, activity, Prefs(activity),
        object : WordRowsBinder.Host {
            override val isAlive: Boolean get() = true
            override val scope: CoroutineScope get() = this@WordRowsBinderTest.scope
            override val ttsAlertTarget: TtsAlertTarget get() = TtsAlertTarget.InActivity(activity)
            override fun onWordTapped(row: RowState) { tapped += row.displayWord }
        },
    ).also { it.onRowsWanted = { rowsWanted++ } }

    @After
    fun tearDown(): Unit = runBlocking {
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
        HiddenWordsStore.resetForTest(app)
        clearPrefs()
    }

    private fun settled() = WordLookupsState.Settled(rows, emptyList(), emptyMap())

    /** The rows the card has published to its document, in drawn order. */
    private fun shown(): List<DefRow.Word> = binder.shownRows

    private fun order(): List<String> = shown().map { it.data.word }

    /** A hidden word's row: the stub, whose eye shows it again. */
    private fun isStub(row: DefRow.Word): Boolean = row.trailing == DefRow.Trailing.SHOW

    @Test
    fun `a fresh list against a loaded set draws hidden words last as stubs`(): Unit = runBlocking {
        HiddenWordsStore.setHidden(app, SourceLangId.JA, "猫", "ねこ", hidden = true)
        binder.render(settled())
        assertEquals(listOf("食べる", "魚", "猫"), order())
        assertTrue(isStub(shown()[2]))
        assertFalse(isStub(shown()[0]))
        assertFalse(root.findViewById<View>(R.id.tvNoWords).isShown && binder.isEmpty)
    }

    @Test
    fun `a store change after the render re-stubs in place without moving the row`(): Unit = runBlocking {
        HiddenWordsStore.loaded(app, SourceLangId.JA)
        binder.render(settled())
        assertEquals(listOf("猫", "食べる", "魚"), order())
        HiddenWordsStore.setHidden(app, SourceLangId.JA, "食べる", "たべる", hidden = true)
        binder.applyHiddenState()
        assertEquals("a toggle never moves a row", listOf("猫", "食べる", "魚"), order())
        assertTrue(isStub(shown()[1]))
        HiddenWordsStore.setHidden(app, SourceLangId.JA, "食べる", "たべる", hidden = false)
        binder.applyHiddenState()
        assertFalse(isStub(shown()[1]))
    }

    @Test
    fun `a list drawn before the set loaded is re-rendered ordered once the load lands`(): Unit = runBlocking {
        HiddenWordsStore.setHidden(app, SourceLangId.JA, "猫", "ねこ", hidden = true)
        HiddenWordsStore.dropCacheForTest()
        assertFalse(HiddenWordsStore.isLoaded(SourceLangId.JA))
        binder.render(settled())
        assertEquals("provisional: lookup order", listOf("猫", "食べる", "魚"), order())
        HiddenWordsStore.loaded(app, SourceLangId.JA)
        binder.applyHiddenState()
        assertEquals(listOf("食べる", "魚", "猫"), order())
        assertTrue(isStub(shown()[2]))
        // A later toggle re-stubs in place: the ordering ran exactly once.
        HiddenWordsStore.setHidden(app, SourceLangId.JA, "魚", "さかな", hidden = true)
        binder.applyHiddenState()
        assertEquals(listOf("食べる", "魚", "猫"), order())
    }

    @Test
    fun `the rows are one document, kept across rebuilds and gone for good on release`() {
        binder.render(settled())
        assertEquals(3, shown().size)
        assertEquals("one view holds every row", 1, binder.container.childCount)
        val view = binder.container.getChildAt(0)
        assertTrue(view is DefinitionDocumentView)

        // A rebuild (a capture in live mode) empties the page and fills it
        // again in the same view: no new WebView per capture.
        binder.render(WordLookupsState.Loading)
        assertTrue(shown().isEmpty())
        binder.render(settled())
        assertEquals(3, shown().size)
        assertTrue(view === binder.container.getChildAt(0))

        // Teardown is terminal and idempotent: nothing on screen, and a
        // render after it draws nothing.
        binder.release()
        assertEquals(0, binder.container.childCount)
        binder.release()
        binder.render(settled())
        assertEquals(0, binder.container.childCount)
        assertTrue(shown().isEmpty())
    }

    @Test
    fun `a lookup that finds no words lets the page go, and the spare it had asked for`() {
        val none = WordLookupsState.Settled(emptyList(), emptyList(), emptyMap())

        // A lookup is running: the card asks for a page, which loads as the
        // spare.
        binder.prepareRows()
        binder.render(WordLookupsState.Loading)
        assertNotNull(DefinitionShells.spareForTest)
        // It finds nothing: the card's claim on a page ends, and with the
        // last claim the spare goes.
        binder.render(none)
        assertNull(DefinitionShells.spareForTest)
        assertEquals(0, binder.container.childCount)

        // A page that was drawing rows goes the same way...
        binder.render(settled())
        assertEquals(1, binder.container.childCount)
        binder.render(WordLookupsState.Loading)
        assertEquals("kept while the next lookup runs", 1, binder.container.childCount)
        binder.render(none)
        assertEquals(0, binder.container.childCount)
        // ...and the next words draw again.
        binder.render(settled())
        assertEquals(3, shown().size)
        assertEquals(1, binder.container.childCount)
    }

    @Test
    fun `a row carries its imported dictionaries, its eye and its speak button`() {
        val imported = listOf(
            ImportedSenseGroup("Jitendex", listOf(ImportedSense("cat", scRowid = 1L)), dictId = "jt"),
        )
        binder.render(
            WordLookupsState.Settled(
                listOf(row("猫", "ねこ", "cat").copy(importedGroups = imported)),
                emptyList(), emptyMap(), definitionLang = "ja",
            ),
        )
        val row = shown().single()
        assertEquals(imported, row.data.importedGroups)
        assertEquals(DefRow.Trailing.HIDE, row.trailing)
        assertEquals(DefRow.Speak.IDLE, row.speak)
        assertTrue(row.opens)
    }

    @Test
    fun `the eye writes the opposite of what its row was showing`(): Unit = runBlocking {
        HiddenWordsStore.loaded(app, SourceLangId.JA)
        binder.render(settled())
        binder.tapForTest(1, DefAction.TRAILING)
        awaitHidden("食べる", true)
        binder.applyHiddenState()
        assertTrue(isStub(shown()[1]))
        // The stub's eye shows the word again.
        binder.tapForTest(1, DefAction.TRAILING)
        awaitHidden("食べる", false)
    }

    /** The eye's store write runs off the main thread; wait for it to land. */
    private fun awaitHidden(word: String, hidden: Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if ((word in HiddenWordsStore.snapshot(app, SourceLangId.JA)) == hidden) return
            Thread.sleep(10)
        }
        throw AssertionError("$word never became hidden=$hidden")
    }

    @Test
    fun `row taps reach the host and Loading clears the rows`() {
        binder.render(settled())
        binder.tapForTest(1, DefAction.OPEN)
        assertEquals(listOf("食べる"), tapped)
        binder.render(WordLookupsState.Loading)
        assertTrue(binder.isEmpty)
        assertTrue(root.findViewById<View>(R.id.tvMainWordsLoading).visibility == View.VISIBLE)
        binder.render(WordLookupsState.Idle)
        assertTrue(binder.isEmpty)
    }

    private fun analyzed() = WordLookupsState.Analyzed(
        tokenSpans = emptyList(),
        annotation = SentenceAnnotation("猫", SourceLangId.JA, 0, emptyList()),
        phrases = emptyList(),
    )

    private val card: View get() = root.findViewById(R.id.cardWords)
    private val eye: View get() = root.findViewById(R.id.btnToggleWords)
    private val hidePref: Boolean get() = Prefs(app).hideWordsSection
    private val label: String get() = root.findViewById<android.widget.TextView>(R.id.labelWords).text.toString()

    @Test
    fun `the header counts the rows only while the card is showing them`() {
        val plain = activity.getString(R.string.section_words)
        assertEquals("nothing looked up yet", plain, label)
        binder.render(WordLookupsState.Loading)
        assertEquals("a lookup still running has no count", plain, label)
        binder.render(settled())
        assertEquals(activity.getString(R.string.section_words_count, 3), label)
        // Hidden: the rows are kept for the reveal, but none are on screen.
        eye.performClick()
        assertEquals(View.GONE, card.visibility)
        assertEquals(plain, label)
        eye.performClick()
        assertEquals(activity.getString(R.string.section_words_count, 3), label)
        // A lookup that finds no words has nothing to count.
        binder.render(WordLookupsState.Settled(emptyList(), emptyList(), emptyMap()))
        assertEquals(plain, label)
    }

    @Test
    fun `a hidden card's label recedes to the hint colour, and a shown one's is muted`() {
        val muted = activity.themeColor(R.attr.ptTextMuted)
        val hint = activity.themeColor(R.attr.ptTextHint)
        assertNotEquals("the two colours differ in this theme", muted, hint)
        val labelView = root.findViewById<android.widget.TextView>(R.id.labelWords)
        binder.applyWordsVisibility()
        assertEquals("shown", muted, labelView.currentTextColor)
        eye.performClick()
        assertEquals(View.GONE, card.visibility)
        assertEquals("hidden by the eye", hint, labelView.currentTextColor)
        eye.performClick()
        assertEquals(View.VISIBLE, card.visibility)
        assertEquals("shown again", muted, labelView.currentTextColor)
        // The live session's auto-hide hides the card too, so the label
        // recedes with it, and comes back when the session ends.
        binder.setLiveMode(true)
        assertEquals(View.GONE, card.visibility)
        assertEquals("auto-hidden by live mode", hint, labelView.currentTextColor)
        binder.setLiveMode(false)
        assertEquals(muted, labelView.currentTextColor)
    }

    @Test
    fun `a hidden card never asks for rows and holds no page, and a Settled landing while hidden is drawn on the reveal`() {
        Prefs(app).hideWordsSection = true
        binder.applyWordsVisibility()
        assertEquals(View.GONE, card.visibility)
        binder.render(analyzed())
        assertEquals(0, rowsWanted)
        assertTrue("Analyzed draws like Loading", binder.isEmpty)
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.tvMainWordsLoading).visibility)
        // Rows requested earlier, card hidden before they settled: kept (the
        // lookup was paid), but a hidden card takes no page for them.
        binder.render(settled())
        assertFalse(binder.isEmpty)
        assertTrue(shown().isEmpty())
        assertEquals(0, binder.container.childCount)
        assertEquals(View.GONE, root.findViewById<View>(R.id.tvMainWordsLoading).visibility)
        // Outside live mode the eye reveals and persists the reveal, and the
        // kept rows are drawn.
        eye.performClick()
        assertEquals(View.VISIBLE, card.visibility)
        assertFalse(hidePref)
        assertEquals(1, rowsWanted)
        assertEquals(3, shown().size)
    }

    @Test
    fun `a shown card asks for rows on the reveal and on an Analyzed render`() {
        binder.applyWordsVisibility()
        assertEquals(View.VISIBLE, card.visibility)
        assertEquals(1, rowsWanted)
        binder.render(analyzed())
        assertEquals(2, rowsWanted)
        assertTrue(binder.isEmpty)
        binder.render(WordLookupsState.Loading)
        assertEquals("only Analyzed asks", 2, rowsWanted)
        binder.setLiveMode(false)
        assertEquals("a stop with no live session on record is no edge", 2, rowsWanted)
    }

    @Test
    fun `live start auto-hides without the pref, the eye reveals for the session, a second tap hides for good`() {
        binder.applyWordsVisibility()
        rowsWanted = 0
        binder.setLiveMode(true)
        assertEquals(View.GONE, card.visibility)
        assertTrue(binder.isHidden)
        assertFalse("the auto-hide is never persisted", hidePref)
        assertEquals(0, rowsWanted)
        binder.render(analyzed())
        assertEquals("auto-hidden: no rows", 0, rowsWanted)
        binder.setLiveMode(true)
        assertEquals("a repeat start changes nothing", View.GONE, card.visibility)
        eye.performClick()
        assertEquals(View.VISIBLE, card.visibility)
        assertFalse(hidePref)
        assertEquals("the reveal asks for the rows", 1, rowsWanted)
        eye.performClick()
        assertEquals(View.GONE, card.visibility)
        assertTrue("a hide from a shown card persists", hidePref)
        binder.setLiveMode(false)
        assertEquals("the stop leaves the persisted hide", View.GONE, card.visibility)
        assertEquals(1, rowsWanted)
    }

    @Test
    fun `live mode is edge-triggered and a stop restores the pref's state`() {
        binder.applyWordsVisibility()
        binder.setLiveMode(true)
        eye.performClick()
        assertEquals(View.VISIBLE, card.visibility)
        binder.setLiveMode(true)
        assertEquals("a repeat start does not re-hide a revealed card", View.VISIBLE, card.visibility)
        binder.setLiveMode(false)
        assertEquals(View.VISIBLE, card.visibility)

        binder.setLiveMode(true)
        assertEquals(View.GONE, card.visibility)
        rowsWanted = 0
        binder.setLiveMode(false)
        assertEquals("stop drops the auto-hide: back to the shown pref", View.VISIBLE, card.visibility)
        assertEquals(1, rowsWanted)
        assertFalse(hidePref)
    }

    @Test
    fun `the eye on a pref-hidden card under live mode reveals it and clears the pref`() {
        Prefs(app).hideWordsSection = true
        binder.applyWordsVisibility()
        binder.setLiveMode(true)
        assertEquals(View.GONE, card.visibility)
        binder.render(analyzed())
        assertEquals(0, rowsWanted)
        // A stop with no tap: the pref still hides it.
        binder.setLiveMode(false)
        assertEquals(View.GONE, card.visibility)
        assertEquals(0, rowsWanted)

        binder.setLiveMode(true)
        eye.performClick()
        assertEquals(View.VISIBLE, card.visibility)
        assertFalse(hidePref)
        assertEquals(1, rowsWanted)
        binder.setLiveMode(false)
        assertEquals(View.VISIBLE, card.visibility)
    }

    private fun clearPrefs() {
        app.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }
}
