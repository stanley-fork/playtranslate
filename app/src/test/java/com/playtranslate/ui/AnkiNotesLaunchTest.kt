package com.playtranslate.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.AnkiManager
import com.playtranslate.R
import com.playtranslate.language.SourceLangId
import com.playtranslate.vocab.HiddenWordsStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.lang.reflect.Proxy

/**
 * The far end of an Anki launch that carries a note ([putAnkiNotes]): the
 * two review activities the permission trampoline forwards its extras to
 * start their sheets' Notes boxes with it, and the workspace's sentence
 * editor page is handed it.
 */
@RunWith(RobolectricTestRunner::class)
class AnkiNotesLaunchTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp(): Unit = runBlocking {
        HiddenWordsStore.resetForTest(ctx)
        LastSentenceCache.clear()
    }

    @After
    fun tearDown() {
        shadowOf(Looper.getMainLooper()).idle()
        LastSentenceCache.clear()
        runBlocking { HiddenWordsStore.resetForTest(ctx) }
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idle()

    // Mutation: the activity builds its sheet without the extras' note.
    @Test
    fun `the word review activity starts both of its sheet's boxes with the note in its extras`() {
        val intent = Intent(ctx, WordAnkiReviewActivity::class.java).apply {
            putExtra(WordAnkiReviewActivity.EXTRA_WORD, "弾く")
            putExtra(WordAnkiReviewActivity.EXTRA_READING, "ひく")
            putExtra(WordAnkiReviewActivity.EXTRA_DEFINITION, "to play")
            putExtra(WordAnkiReviewActivity.EXTRA_SENTENCE_ORIGINAL, "一番うまく弾けた")
            putExtra(WordAnkiReviewActivity.EXTRA_SENTENCE_TRANSLATION, "I played it best")
            putExtra(WordAnkiReviewActivity.EXTRA_SOURCE_LANG, SourceLangId.JA.code)
            putExtras(Bundle().apply { putAnkiNotes("Here it is the potential form.") })
        }
        val controller = Robolectric.buildActivity(WordAnkiReviewActivity::class.java, intent).setup()
        settle()
        val sheet = controller.get().supportFragmentManager
            .findFragmentByTag(WordAnkiReviewSheet.TAG) as WordAnkiReviewSheet
        val decor = sheet.requireDialog().window!!.decorView
        assertEquals("Here it is the potential form.", decor.findViewById<EditText>(R.id.etAnkiWordNotes).text.toString())
        assertEquals("Here it is the potential form.", decor.findViewById<EditText>(R.id.etAnkiSentenceNotes).text.toString())
        controller.pause().stop().destroy()
    }

    // Mutation: the activity builds its sheet without the extras' note.
    @Test
    fun `the sentence review activity starts its sheet's box with the note in its extras`() {
        val intent = Intent(ctx, SentenceAnkiReviewActivity::class.java).apply {
            putExtra(SentenceAnkiReviewActivity.EXTRA_SENTENCE, "一番うまく弾けた")
            putExtra(SentenceAnkiReviewActivity.EXTRA_TRANSLATION, "I played it best")
            putExtra(SentenceAnkiReviewActivity.EXTRA_SOURCE_LANG, SourceLangId.JA.code)
            putExtra(SentenceAnkiReviewActivity.EXTRA_WORDS, arrayOf("一番"))
            putExtra(SentenceAnkiReviewActivity.EXTRA_READINGS, arrayOf("いちばん"))
            putExtra(SentenceAnkiReviewActivity.EXTRA_MEANINGS, arrayOf("most"))
            putExtra(SentenceAnkiReviewActivity.EXTRA_FREQ_SCORES, intArrayOf(5))
            putExtras(Bundle().apply { putAnkiNotes("一番\nMost of all.") })
        }
        val controller = Robolectric.buildActivity(SentenceAnkiReviewActivity::class.java, intent).setup()
        settle()
        val sheet = controller.get().supportFragmentManager
            .findFragmentByTag(AnkiReviewBottomSheet.TAG) as AnkiReviewBottomSheet
        assertEquals(
            "一番\nMost of all.",
            sheet.requireDialog().window!!.decorView.findViewById<EditText>(R.id.etAnkiSentenceNotes).text.toString(),
        )
        controller.pause().stop().destroy()
    }

    // Mutation: the workspace's editor page is built without the note.
    @Test
    fun `with the permission held the workspace's sentence editor page is handed the note`() {
        shadowOf(ctx as Application).grantPermissions(AnkiManager.PERMISSION)
        val pushed = mutableListOf<WorkspacePage>()
        val host = Proxy.newProxyInstance(
            WorkspaceHost::class.java.classLoader, arrayOf(WorkspaceHost::class.java),
        ) { _, method, args ->
            if (method.name == "push") pushed += args[0] as WorkspacePage
            null
        } as WorkspaceHost

        val inWorkspace = presentSentenceAnkiReview(
            ctx, 0, WorkspaceRoute.PushInto(host),
            SentenceAnkiArgs(
                original = "一番うまく弾けた", translation = "I played it best", screenshotPath = null,
                sourceLangId = SourceLangId.JA, pendingTranslation = null, audioAnchorMs = null, words = null,
                notes = "一番\nMost of all.",
            ),
        )

        assertEquals(true, inWorkspace)
        val page = pushed.single() as AnkiSentenceEditorPage
        assertEquals(
            "一番\nMost of all.",
            AnkiSentenceEditorPage::class.java.getDeclaredField("notes").apply { isAccessible = true }.get(page),
        )
    }
}
