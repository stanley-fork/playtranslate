package com.playtranslate.ui

import android.app.Activity
import android.os.Bundle
import android.view.Display
import com.playtranslate.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController

/**
 * The lens over a primary with no entry: the tapped unit is still a row
 * that names it, with the "No definitions found." placeholder where its
 * senses would be, above the member rows, and only the rows that open are
 * controller targets. This is the lens a fused phrase no dictionary lists
 * gets once its members are offered anyway.
 */
@RunWith(RobolectricTestRunner::class)
class MagnifierLensNotFoundSplitTest {

    class Host : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_PlayTranslate)
            super.onCreate(savedInstanceState)
        }
    }

    private lateinit var controller: ActivityController<Host>
    private lateinit var lens: MagnifierLens

    @Before
    fun setUp() {
        DefinitionShells.reset()
        controller = Robolectric.buildActivity(Host::class.java).setup()
        val activity = controller.get()
        lens = MagnifierLens(activity, activity.windowManager, Display.DEFAULT_DISPLAY)
        val dm = activity.resources.displayMetrics
        lens.show(dm.widthPixels / 2, dm.heightPixels * 3 / 4, dm.widthPixels, dm.heightPixels)
        lens.makeInteractive()
    }

    @After
    fun tearDown() {
        lens.dismiss()
        controller.pause().stop().destroy()
        DefinitionShells.reset()
    }

    @Test
    fun `a primary with no entry is a row with the placeholder, above its member rows`() {
        val activity = controller.get()
        val placeholder = activity.getString(R.string.word_detail_no_definitions)
        val primary = LensSection(
            WordDefinitionData("星と深淵を目指せ", "ほしとしんえんをめざせ", emptyList(), freqScore = 0, isCommon = false),
            label = null, opens = false,
        )
        val member = LensSection(
            WordDefinitionData(
                "星", "ほし", listOf(SenseDisplay(pos = emptyList(), definition = "star", misc = emptyList())),
                freqScore = 0, isCommon = false,
            ),
            label = null, opens = true,
        )
        lens.setSplitDefinitions(primary, listOf(member), secondariesOnTop = false)

        val rows = lens.shownRowsForTest().filterIsInstance<DefRow.Word>()
        assertEquals(listOf("星と深淵を目指せ", "星"), rows.map { it.data.word })
        assertEquals(placeholder, rows[0].emptyText)

        // The markup the page draws: the placeholder under the word with no
        // entry, and not under the member, which has a sense.
        val unlisted = DefRowHtml.html(activity, rows[0], "k0", emptyMap(), "ja")
        val listed = DefRowHtml.html(activity, rows[1], "k1", emptyMap(), "ja")
        assertTrue(unlisted, unlisted.contains("星と深淵を目指せ") && unlisted.contains(placeholder))
        // Nothing to open into, so the row itself is no tap target; it can
        // still be spoken.
        assertFalse(unlisted, unlisted.contains("data-act=\"open\""))
        assertTrue(unlisted, unlisted.contains("data-act=\"speak\""))
        assertTrue(listed, listed.contains("star") && listed.contains("data-act=\"open\""))
        assertFalse(listed, listed.contains(placeholder))

        // Both rows are within the controller's reach: the unlisted word by
        // its Speak button, the member by its row.
        lens.layoutRowsForTest(100)
        assertEquals(listOf(rows[0].id, rows[1].id), lens.navRowsForTest())
        lens.focusForController()
        assertEquals(rows[0].id to DefAction.SPEAK, lens.cursorForTest())
    }
}
