package com.playtranslate

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Words card's starting state ([Prefs.hideWordsSection]): collapsed on a
 * new install, as it was on an install that already ran, decided once and
 * never over a value the user set.
 */
@RunWith(RobolectricTestRunner::class)
class PrefsWordsDefaultTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun sp() =
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)

    @Before fun clearPrefs() { sp().edit().clear().commit() }
    @After fun tearDown() { sp().edit().clear().commit() }

    @Test fun `a new install starts with the card collapsed, and stays so on the next construction`() {
        assertTrue(Prefs(ctx).hideWordsSection)
        // The first construction left other keys behind; the second must not
        // read that as an install that already ran.
        assertTrue(Prefs(ctx).hideWordsSection)
        assertTrue(sp().getBoolean("hide_words_section", false))
    }

    @Test fun `an install that already ran keeps the card visible`() {
        // What every earlier launch leaves in the file, and no stored choice.
        sp().edit().putBoolean("secrets_encrypted_migrated", true).commit()

        assertFalse(Prefs(ctx).hideWordsSection)
        assertFalse(Prefs(ctx).hideWordsSection)
    }

    @Test fun `a stored choice is never rewritten`() {
        sp().edit().putBoolean("hide_words_section", false).commit()
        assertFalse(Prefs(ctx).hideWordsSection)

        sp().edit().clear().putBoolean("secrets_encrypted_migrated", true)
            .putBoolean("hide_words_section", true).commit()
        assertTrue(Prefs(ctx).hideWordsSection)
    }

    @Test fun `revealing the card on a new install is remembered`() {
        val prefs = Prefs(ctx)
        prefs.hideWordsSection = false

        assertFalse(Prefs(ctx).hideWordsSection)
    }
}
