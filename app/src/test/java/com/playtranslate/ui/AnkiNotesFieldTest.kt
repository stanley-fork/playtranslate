package com.playtranslate.ui

import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.R
import com.playtranslate.language.SourceLangId
import com.playtranslate.vocab.HiddenWordsStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
 * comes back after the sheet is rebuilt.
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

    private fun open(): ActivityController<Host> {
        val controller = Robolectric.buildActivity(Host::class.java).setup()
        opened += controller
        WordAnkiReviewSheet.newInstance(
            "弾く", "ひく", "Godan verb", "to play", null,
            sentenceOriginal = "一番うまく弾けた", sentenceTranslation = "I played it best",
            sentenceWordResults = mapOf("一番" to Triple("いちばん", "most", 5)),
            sourceLangId = SourceLangId.JA,
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
