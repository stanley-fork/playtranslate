package com.playtranslate.ui

import android.app.Activity
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import com.playtranslate.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Proxy

/**
 * The Notes box's X under the workspace's controller cursor
 * ([WorkspaceControllerNav]): the X is a target while the box holds text,
 * and a press, which takes the X off the screen, puts the cursor on the
 * box, through the hook the editors' hosts pass to the workspace
 * ([SentenceAnkiContentView.Host.onControlGone]). Never on another button:
 * the editors have remove buttons, and a cursor that walked onto one by
 * itself would delete on the next press.
 */
@RunWith(RobolectricTestRunner::class)
class AnkiNotesClearNavTest {

    class Host : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_PlayTranslate)
            super.onCreate(savedInstanceState)
        }
    }

    private class FakeNavHost(private val root: () -> View) : WorkspaceNavHost {
        var ring: Rect? = null
        override val isModalUp = false
        override val isEditing = false
        override val isPopoverOpen = false
        override fun onControllerBack() = Unit
        // Read at each key, as the workspace reads its page.
        override fun navActions(): List<NavAction> = collectWorkspaceNavActions(root())
        override fun headerNavActions(): List<NavAction> = emptyList()
        override fun scrollViewportOnScreen(out: Rect) = false
        override fun scrollBy(dy: Int) = Unit
        override fun ensureVisible(itemOnScreen: Rect) = Unit
        override fun viewInScrollViewport(v: View) = false
        override fun setRing(itemOnScreen: Rect?, clipOnScreen: Rect?) {
            ring = itemOnScreen?.let(::Rect)
        }
    }

    private lateinit var activity: Host
    private lateinit var column: LinearLayout
    private lateinit var navHost: FakeNavHost
    private lateinit var nav: WorkspaceControllerNav
    private lateinit var field: EditText
    private lateinit var remove: View

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(Host::class.java).setup().get()
        column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        navHost = FakeNavHost { column }
        nav = WorkspaceControllerNav(activity, navHost)
    }

    /** The Notes group over a remove button of some other group, as the
     *  editors stack theirs, the X wired to [onCleared]. */
    private fun build(onCleared: (View) -> Unit) {
        field = addAnkiNotesSection(column, R.id.etAnkiWordNotes, "Here it is the potential form.", onCleared)
        remove = View(activity).apply { isClickable = true }
        column.addView(remove, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 48))
        activity.setContentView(column)
        layout()
    }

    private fun layout() {
        column.measure(
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
        )
        column.layout(0, 0, 1000, 1000)
    }

    private fun clear(): ImageButton {
        val header = column.getChildAt(0) as LinearLayout
        return (0 until header.childCount).map(header::getChildAt).filterIsInstance<ImageButton>().single()
    }

    private fun press(code: Int) {
        nav.handleKey(KeyEvent(KeyEvent.ACTION_DOWN, code))
        nav.handleKey(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun rectOf(v: View): Rect {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
    }

    // Mutations: the X is no target; the press leaves the cursor on the
    // gone X; the cursor lands on the nearest other button.
    @Test
    fun `the cursor reaches the X, and a press empties the box and puts the cursor on the box`() {
        build(onCleared = { nav.revalidateCursor(prefer = it) })
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("the first target of the group is its X", rectOf(clear()), navHost.ring)

        press(KeyEvent.KEYCODE_BUTTON_A)

        assertEquals("", field.text.toString())
        assertEquals(View.INVISIBLE, clear().visibility)
        assertEquals("on the box, not on the remove button under it", rectOf(field), navHost.ring)
    }

    // What the hook is for: without it the cursor is left on a control
    // that is no longer there.
    @Test
    fun `with no host to move it the cursor is left on nothing, and the X is no target over an empty box`() {
        build(onCleared = {})
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals("", field.text.toString())
        nav.syncRing()
        assertNull(navHost.ring)

        assertEquals(
            "the box and the other group's button, and no X",
            listOf(field, remove), collectWorkspaceNavActions(column).map { it.view },
        )
    }

    // Mutations: let the X count for more of the header's height; shrink
    // its touch area; clip it to the header's line of text; take it out of
    // the layout over an empty box.
    @Test
    fun `the X is the Ask answer's size at the header's end, and counts for less of the header's height than its title does`() {
        build(onCleared = {})
        val plain = ankiGroupHeader(column, "Definitions")
        layout()
        val notesHeader = column.getChildAt(0) as LinearLayout
        val density = activity.resources.displayMetrics.density
        assertEquals((36 * density).toInt() to (32 * density).toInt(), clear().width to clear().height)
        assertEquals("at the header's end", notesHeader.width - notesHeader.paddingRight, clear().right)
        // This environment has no real fonts (its line of 11sp text is 41px
        // tall), so the rule is pinned as its numbers: the button counts
        // 12dp toward the row, under the 13dp such a line is at its
        // shortest on a device, and is drawn over the header's padding.
        val lp = clear().layoutParams as LinearLayout.LayoutParams
        assertEquals((12 * density).toInt(), lp.height + lp.topMargin + lp.bottomMargin)
        assertEquals("centred on the title's line", lp.topMargin, lp.bottomMargin)
        assertFalse(notesHeader.clipToPadding)
        assertTrue("precondition: the column is laid out", plain.height > 0)
        assertEquals(plain.height, notesHeader.height)

        // Over an empty box the X keeps its place, so nothing moves.
        field.setText("")
        layout()
        assertEquals(View.INVISIBLE, clear().visibility)
        assertEquals((36 * density).toInt(), clear().width)
        assertEquals(plain.height, notesHeader.height)
    }

    // Mutation: a workspace editor's host does not pass the hook on.
    @Test
    fun `both workspace editors hand a gone control to the workspace's cursor, and the word editor's sentence tab to its own host`() {
        val preferred = mutableListOf<View?>()
        val workspace = Proxy.newProxyInstance(
            WorkspaceHost::class.java.classLoader, arrayOf(WorkspaceHost::class.java),
        ) { _, method, args ->
            if (method.name == "invalidateNav") preferred += args?.get(0) as View?
            null
        } as WorkspaceHost
        val box = View(activity)

        val editor = AnkiEditorPage(Bundle())
        (inner(editor, "EditorHost", activity as Context, workspace) as WordAnkiReviewBinder.Host).onControlGone(box)
        val sentenceEditor = AnkiSentenceEditorPage(
            "original", "translation", emptyMap(), emptyMap(), emptyMap(), null,
            com.playtranslate.language.SourceLangId.JA,
        )
        (inner(sentenceEditor, "ContentHost", workspace) as SentenceAnkiContentView.Host).onControlGone(box)
        assertEquals(listOf<View?>(box, box), preferred)

        var told: View? = null
        val binderHost = Proxy.newProxyInstance(
            WordAnkiReviewBinder.Host::class.java.classLoader, arrayOf(WordAnkiReviewBinder.Host::class.java),
        ) { _, method, args ->
            if (method.name == "onControlGone") told = args[0] as View
            null
        } as WordAnkiReviewBinder.Host
        val binder = WordAnkiReviewBinder(
            activity, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate), Bundle(), binderHost,
        )
        (inner(binder, "ContentHost") as SentenceAnkiContentView.Host).onControlGone(box)
        assertSame(box, told)
    }

    // Mutations: either tab builds its Notes group without its host's hook.
    @Test
    fun `the word editor tells its host of an X pressed on either tab, with that tab's box`() {
        val gone = mutableListOf<View>()
        val host = object : WordAnkiReviewBinder.Host {
            override val isAlive = true
            override fun openAudioPicker(intent: android.content.Intent, onPicked: (com.playtranslate.audio.AudioSelection) -> Unit) = Unit
            override fun openScreenshotCrop(state: AnkiScreenshotState) = Unit
            override fun openDeckPicker(onPicked: (deckId: Long, deckName: String) -> Unit) = Unit
            override fun openCardTypePicker(onPicked: (modelId: Long, modelName: String) -> Unit) = Unit
            override fun openFieldMapping(model: com.playtranslate.AnkiManager.ModelInfo, mode: CardMode) = Unit
            override fun presentAlert(builder: OverlayAlert.Builder) = Unit
            override fun dismiss() = Unit
            override fun onControlGone(next: View) { gone += next }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val root = android.view.LayoutInflater.from(activity).inflate(R.layout.sheet_word_anki_review, null)
        val binder = WordAnkiReviewBinder(
            activity, scope,
            WordAnkiReviewBinder.buildArgs(
                "弾く", "ひく", "Godan verb", "to play", null,
                sentenceOriginal = "一番うまく弾けた", sentenceTranslation = "I played it best",
                sentenceWordResults = mapOf("一番" to Triple("いちばん", "most", 5)),
                sourceLangId = com.playtranslate.language.SourceLangId.JA,
                notes = "Here it is the potential form.",
            ),
            host,
        )
        try {
            binder.bind(root, null)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            val sentenceBox = root.findViewById<EditText>(R.id.etAnkiSentenceNotes)
            val wordBox = root.findViewById<EditText>(R.id.etAnkiWordNotes)
            fun clearOf(box: View): ImageButton {
                val card = box.parent as View
                val group = card.parent as LinearLayout
                val header = group.getChildAt(group.indexOfChild(card) - 1) as LinearLayout
                return (0 until header.childCount).map(header::getChildAt).filterIsInstance<ImageButton>().single()
            }

            clearOf(sentenceBox).performClick()
            clearOf(wordBox).performClick()

            assertEquals(listOf<View>(sentenceBox, wordBox), gone)
            assertEquals("" to "", sentenceBox.text.toString() to wordBox.text.toString())
        } finally {
            binder.release(releasePin = true)
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    /** An instance of [owner]'s inner class [name], built with [args]. */
    private fun inner(owner: Any, name: String, vararg args: Any): Any =
        Class.forName(owner.javaClass.name + "$" + name).declaredConstructors.single()
            .apply { isAccessible = true }.newInstance(owner, *args)
}
