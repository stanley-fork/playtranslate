package com.playtranslate.ui

import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.R
import com.playtranslate.language.SourceLangId
import com.playtranslate.vocab.HiddenWordsStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * The Notes box of the Anki review sheets ([addAnkiNotesSection]), on the
 * word sheet, which holds both editors at once: a sentence tab (the content
 * view every sentence host shares) and a word tab. Where each box sits, that
 * the two keep separate notes, that a typed note reaches the card data and
 * comes back after the sheet is rebuilt, and that a sheet opened with a note
 * ([putAnkiNotes]) starts both boxes with it, on this sheet and on the
 * sentence-only one. And the X at the end of each Notes header: there while
 * its box holds text, emptying that box alone.
 */
@RunWith(RobolectricTestRunner::class)
class AnkiNotesFieldTest {

    class Host : FragmentActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_PlayTranslate)
            super.onCreate(savedInstanceState)
        }
    }

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val opened = mutableListOf<ActivityController<Host>>()

    @Before
    fun setUp(): Unit = runBlocking {
        HiddenWordsStore.resetForTest(ctx)
        LastSentenceCache.clear()
    }

    @After
    fun tearDown() {
        opened.forEach { it.pause().stop().destroy() }
        opened.clear()
        shadowOf(Looper.getMainLooper()).idle()
        LastSentenceCache.clear()
        runBlocking { HiddenWordsStore.resetForTest(ctx) }
    }

    // Mutations: add the sentence box after the Words card; add the word
    // box after the screenshot group or above the definitions.
    @Test
    fun `the note sits under the translation on the sentence tab and under the definitions on the word tab`() {
        val controller = open()
        val view = view(controller)

        // Sentence tab: Translation's card, then the Notes header and card,
        // then the Words header.
        val sentenceNotes = view.findViewById<EditText>(R.id.etAnkiSentenceNotes)
        val sentenceRoot = groupParent(sentenceNotes)
        val translationAt = indexOfChildHolding(sentenceRoot, view.findViewById(R.id.etAnkiTranslation))
        assertEquals(translationAt + 2, indexOfChildHolding(sentenceRoot, sentenceNotes))
        assertEquals(
            ctx.getString(R.string.anki_group_notes),
            sentenceRoot.getChildAt(translationAt + 1).findViewById<android.widget.TextView>(R.id.tvGroupTitle).text.toString(),
        )

        // Word tab: the Definitions card, then the Notes header and card.
        val wordNotes = view.findViewById<EditText>(R.id.etAnkiWordNotes)
        val wordRoot = groupParent(wordNotes)
        val definitions = WordAnkiReviewBinder::class.java.getDeclaredField("definitionsCard")
            .apply { isAccessible = true }.get(binder(controller)) as View
        assertEquals(wordRoot.indexOfChild(definitions) + 2, indexOfChildHolding(wordRoot, wordNotes))
        assertNotEquals("two tabs, two boxes", sentenceRoot, wordRoot)
    }

    // Mutations: give both boxes one id; read the other tab's box; drop the
    // id (nothing restores).
    @Test
    fun `each tab keeps its own note, in the card data and across a rebuild of the sheet`() {
        val controller = open()
        view(controller).findViewById<EditText>(R.id.etAnkiSentenceNotes).setText("about the sentence")
        view(controller).findViewById<EditText>(R.id.etAnkiWordNotes).setText("about the word")

        assertEquals("about the sentence", content(controller).getCardData().notes)

        controller.recreate()
        settle()

        assertEquals("about the sentence", view(controller).findViewById<EditText>(R.id.etAnkiSentenceNotes).text.toString())
        assertEquals("about the word", view(controller).findViewById<EditText>(R.id.etAnkiWordNotes).text.toString())
        assertEquals("about the sentence", content(controller).getCardData().notes)
    }

    // Mutations: drop either IME flag; drop the click listener.
    @Test
    fun `the box starts empty with its hint, keeps the keyboard out of fullscreen and takes focus on a click`() {
        val controller = open()
        for (id in listOf(R.id.etAnkiSentenceNotes, R.id.etAnkiWordNotes)) {
            val field = view(controller).findViewById<EditText>(id)
            assertEquals("", field.text.toString())
            assertEquals(ctx.getString(R.string.anki_notes_hint), field.hint.toString())
            val flags = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            assertEquals(flags, field.imeOptions and flags)
            // A controller's A is a click on the row.
            assertTrue(field.hasOnClickListeners())
        }
        assertEquals("", content(controller).getCardData().notes)
    }

    // Mutations: start either box empty; start the sentence tab's from
    // another key than the word tab's.
    @Test
    fun `a sheet opened with a note starts both tabs' boxes with it, and the sentence card carries it`() {
        val controller = open(notes = "Here it is the potential form.")
        assertEquals("Here it is the potential form.", view(controller).findViewById<EditText>(R.id.etAnkiWordNotes).text.toString())
        assertEquals("Here it is the potential form.", view(controller).findViewById<EditText>(R.id.etAnkiSentenceNotes).text.toString())
        assertEquals("Here it is the potential form.", content(controller).getCardData().notes)
        // The hint is still the empty box's.
        assertEquals(ctx.getString(R.string.anki_notes_hint), view(controller).findViewById<EditText>(R.id.etAnkiWordNotes).hint.toString())
    }

    // Mutations: set the opener's note after the restore; skip the restore
    // of an empty text.
    @Test
    fun `a rebuilt sheet shows what each box held, an emptied one included, and never the opener's note again`() {
        val controller = open(notes = "Here it is the potential form.")
        view(controller).findViewById<EditText>(R.id.etAnkiSentenceNotes).setText("")
        view(controller).findViewById<EditText>(R.id.etAnkiWordNotes).setText("my own words")

        controller.recreate()
        settle()

        assertEquals("", view(controller).findViewById<EditText>(R.id.etAnkiSentenceNotes).text.toString())
        assertEquals("my own words", view(controller).findViewById<EditText>(R.id.etAnkiWordNotes).text.toString())
        assertEquals("", content(controller).getCardData().notes)
    }

    // Mutations: the sentence sheet drops the note on its way to its
    // content; its restored content starts the box from the opener again.
    @Test
    fun `the sentence sheet opened with a note starts its box with it, and an emptied box stays empty when it is rebuilt`() {
        val controller = Robolectric.buildActivity(Host::class.java).setup()
        opened += controller
        val sheet = AnkiReviewBottomSheet.newInstance(
            "一番うまく弾けた", "I played it best",
            mapOf("一番" to Triple("いちばん", "most", 5)), emptyMap(), emptyMap(), null, SourceLangId.JA,
            notes = "一番\nMost of all.\n\n弾く\nTo play.",
        )
        sheet.showNow(controller.get().supportFragmentManager, AnkiReviewBottomSheet.TAG)
        settle()
        fun box(): EditText =
            (controller.get().supportFragmentManager.findFragmentByTag(AnkiReviewBottomSheet.TAG) as AnkiReviewBottomSheet)
                .requireDialog().window!!.decorView.findViewById(R.id.etAnkiSentenceNotes)
        assertEquals("一番\nMost of all.\n\n弾く\nTo play.", box().text.toString())

        box().setText("")
        controller.recreate()
        settle()
        assertEquals("", box().text.toString())
    }

    // ── An edit of Original under a note ────────────────────────────────

    /** The Original text of the sentence tab is edited to [text] and the
     *  edit committed, as when the user taps away from the field. */
    private fun editOriginal(controller: ActivityController<Host>, text: String) {
        val original = view(controller).findViewById<EditText>(R.id.etAnkiOriginal)
        original.requestFocus()
        original.setText(text)
        original.clearFocus()
        settle()
    }

    // Mutations: keep the opener's note under an edited sentence, on
    // either tab; clear a note the user changed, on either tab; decide one
    // tab's box by what the other holds.
    @Test
    fun `an edit of Original takes the opener's untouched note with the sentence it was about on both tabs, and leaves a note the user changed`() {
        val untouched = open(notes = "Here it is the potential form.")
        editOriginal(untouched, "一番うまく弾いた")
        assertEquals("the sentence the note was about is gone", "", sentenceBox(untouched).text.toString())
        assertEquals(View.INVISIBLE, clearOf(sentenceBox(untouched)).visibility)
        assertEquals("", content(untouched).getCardData().notes)
        assertEquals("the word's answer was about the word in that sentence", "", wordBox(untouched).text.toString())
        assertEquals(View.INVISIBLE, clearOf(wordBox(untouched)).visibility)

        val changed = open(notes = "Here it is the potential form.")
        sentenceBox(changed).setText("Here it is the potential form. (my own words)")
        wordBox(changed).setText("my own note on the word")
        editOriginal(changed, "一番うまく弾いた")
        assertEquals("Here it is the potential form. (my own words)", sentenceBox(changed).text.toString())
        assertEquals("my own note on the word", wordBox(changed).text.toString())

        // Each box by its own text: one changed, the other not.
        val sentenceChanged = open(notes = "Here it is the potential form.")
        sentenceBox(sentenceChanged).setText("my own words")
        editOriginal(sentenceChanged, "一番うまく弾いた")
        assertEquals("my own words", sentenceBox(sentenceChanged).text.toString())
        assertEquals("", wordBox(sentenceChanged).text.toString())

        val wordChanged = open(notes = "Here it is the potential form.")
        wordBox(wordChanged).setText("my own note on the word")
        editOriginal(wordChanged, "一番うまく弾いた")
        assertEquals("", sentenceBox(wordChanged).text.toString())
        assertEquals("my own note on the word", wordBox(wordChanged).text.toString())
    }

    // Mutations: an edit that changes nothing clears the note; the note
    // comes back when the sheet is rebuilt; a note typed after the edit is
    // taken for the opener's at the next edit.
    @Test
    fun `the note cleared by an edit stays cleared on a rebuilt sheet, and nothing the user types after is taken for the opener's`() {
        val controller = open(notes = "Here it is the potential form.")
        // Focus in and out with the text as it was is no edit.
        editOriginal(controller, "一番うまく弾けた")
        assertEquals("Here it is the potential form.", sentenceBox(controller).text.toString())
        assertEquals("Here it is the potential form.", wordBox(controller).text.toString())

        editOriginal(controller, "一番うまく弾いた")
        assertEquals("", sentenceBox(controller).text.toString())
        assertEquals("", wordBox(controller).text.toString())
        controller.recreate()
        settle()
        assertEquals("", sentenceBox(controller).text.toString())
        assertEquals("", wordBox(controller).text.toString())

        // The same words typed by the user are the user's: a second edit
        // of Original leaves them, on either tab.
        sentenceBox(controller).setText("Here it is the potential form.")
        wordBox(controller).setText("Here it is the potential form.")
        editOriginal(controller, "うまく弾いた")
        assertEquals("Here it is the potential form.", sentenceBox(controller).text.toString())
        assertEquals("Here it is the potential form.", wordBox(controller).text.toString())
    }

    // ── The X at the end of the Notes header ────────────────────────────

    /** The X of [field]'s Notes group: the one button in the header that
     *  stands over the field's card. */
    private fun clearOf(field: View): ImageButton = headerOf(field).let { header ->
        (0 until header.childCount).map(header::getChildAt).filterIsInstance<ImageButton>().single()
    }

    private fun headerOf(field: View): ViewGroup {
        val column = groupParent(field)
        return column.getChildAt(indexOfChildHolding(column, field) - 1) as ViewGroup
    }

    private fun wordBox(c: ActivityController<Host>): EditText = view(c).findViewById(R.id.etAnkiWordNotes)
    private fun sentenceBox(c: ActivityController<Host>): EditText = view(c).findViewById(R.id.etAnkiSentenceNotes)

    // Mutations: show the X over an empty box; hide it over a filled one;
    // follow the other tab's box.
    @Test
    fun `the Notes header ends in an X while its box holds text, typed or filled in by the opener, and not while it is empty`() {
        val empty = open()
        for (box in listOf(wordBox(empty), sentenceBox(empty))) {
            assertEquals(View.INVISIBLE, clearOf(box).visibility)
            assertEquals(ctx.getString(R.string.cd_anki_notes_clear), clearOf(box).contentDescription.toString())
        }
        wordBox(empty).setText("typed")
        assertEquals(View.VISIBLE, clearOf(wordBox(empty)).visibility)
        assertEquals("the other tab's box is still empty", View.INVISIBLE, clearOf(sentenceBox(empty)).visibility)
        wordBox(empty).setText("")
        assertEquals(View.INVISIBLE, clearOf(wordBox(empty)).visibility)

        val filled = open(notes = "Here it is the potential form.")
        assertEquals(View.VISIBLE, clearOf(wordBox(filled)).visibility)
        assertEquals(View.VISIBLE, clearOf(sentenceBox(filled)).visibility)
    }

    // Mutations: the X empties the other tab's box, or both; it stays up
    // over the box it emptied.
    @Test
    fun `a press on the X empties its own box and leaves the other tab's, and the card then carries no note`() {
        val controller = open(notes = "Here it is the potential form.")

        clearOf(sentenceBox(controller)).performClick()

        assertEquals("", sentenceBox(controller).text.toString())
        assertEquals(View.INVISIBLE, clearOf(sentenceBox(controller)).visibility)
        assertEquals("", content(controller).getCardData().notes)
        assertEquals("Here it is the potential form.", wordBox(controller).text.toString())
        assertEquals(View.VISIBLE, clearOf(wordBox(controller)).visibility)
        // A finger's press leaves the focus where it was: no keyboard comes up.
        assertFalse(sentenceBox(controller).isFocused)
    }

    // Mutation: the X follows the opener's text and not what the rebuilt
    // box holds.
    @Test
    fun `on a rebuilt sheet each X follows what its box holds now`() {
        val controller = open(notes = "Here it is the potential form.")
        clearOf(sentenceBox(controller)).performClick()
        wordBox(controller).setText("my own words")

        controller.recreate()
        settle()

        assertEquals(View.INVISIBLE, clearOf(sentenceBox(controller)).visibility)
        assertEquals(View.VISIBLE, clearOf(wordBox(controller)).visibility)
    }

    // Mutation: a pressed X that held the focus drops it on nothing.
    @Test
    fun `an X pressed while it holds the focus hands the focus to its box`() {
        val controller = open(notes = "Here it is the potential form.")
        val x = clearOf(wordBox(controller))
        // This environment's windows are in touch mode, where a button takes
        // no focus; a controller's are not.
        x.isFocusableInTouchMode = true
        assertTrue("precondition: the X holds the focus", x.requestFocus() && x.isFocused)

        x.performClick()

        assertEquals("", wordBox(controller).text.toString())
        assertTrue("the box the X belongs to has it", wordBox(controller).isFocused)
    }

    private fun open(notes: String = ""): ActivityController<Host> {
        val controller = Robolectric.buildActivity(Host::class.java).setup()
        opened += controller
        WordAnkiReviewSheet.newInstance(
            "弾く", "ひく", "Godan verb", "to play", null,
            sentenceOriginal = "一番うまく弾けた", sentenceTranslation = "I played it best",
            sentenceWordResults = mapOf("一番" to Triple("いちばん", "most", 5)),
            sourceLangId = SourceLangId.JA,
            notes = notes,
        ).showNow(controller.get().supportFragmentManager, WordAnkiReviewSheet.TAG)
        settle()
        return controller
    }

    private fun sheet(controller: ActivityController<Host>): WordAnkiReviewSheet =
        controller.get().supportFragmentManager.findFragmentByTag(WordAnkiReviewSheet.TAG) as WordAnkiReviewSheet

    private fun view(controller: ActivityController<Host>): View = sheet(controller).requireDialog().window!!.decorView

    private fun binder(controller: ActivityController<Host>): WordAnkiReviewBinder =
        WordAnkiReviewSheet::class.java.declaredFields
            .first { it.type == WordAnkiReviewBinder::class.java }
            .apply { isAccessible = true }.get(sheet(controller)) as WordAnkiReviewBinder

    private fun content(controller: ActivityController<Host>): SentenceAnkiContentView =
        WordAnkiReviewBinder::class.java.getDeclaredField("contentView")
            .apply { isAccessible = true }.get(binder(controller)) as SentenceAnkiContentView

    /** The column of group headers and cards [field]'s card is a child of:
     *  the field sits directly in its card, the card in the column. */
    private fun groupParent(field: View): LinearLayout = (field.parent as View).parent as LinearLayout

    private fun indexOfChildHolding(parent: ViewGroup, view: View): Int {
        var child: View = view
        while (child.parent !== parent) child = child.parent as View
        return parent.indexOfChild(child)
    }

    private fun settle() {
        shadowOf(Looper.getMainLooper()).idle()
    }
}
