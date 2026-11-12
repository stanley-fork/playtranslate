package com.playtranslate.ui

import android.os.Bundle
import com.playtranslate.ask.AskQuestion
import com.playtranslate.ask.AskState
import com.playtranslate.language.SourceLangId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The note an Anki card starts with when its word's explain question was
 * answered: what a sentence card's note is made of ([sentenceCardNote]),
 * and the one key the text travels under into both editors' launch args
 * ([putAnkiNotes], [getAnkiNotes]).
 */
@RunWith(RobolectricTestRunner::class)
class AnkiExplainedNotesTest {

    private fun asked(title: String, text: String) =
        AskState.Answered(AskQuestion.Custom(title), text, answeredBy = "Test LLM")

    // Mutations: label a lone answer; join several with no label, or with
    // one line break; reverse the order.
    @Test
    fun `one answer is the note as it is, several are each under their word, none is no note`() {
        assertEquals("", sentenceCardNote(null, emptyList()))
        assertEquals(
            "It is the plain past.",
            sentenceCardNote(null, listOf(ExplainedWord("食べる", "It is the plain past."))),
        )
        assertEquals(
            "猫\nThe subject.\n\n食べる\nIt is the plain past.\nOf to eat.",
            sentenceCardNote(
                null,
                listOf(
                    ExplainedWord("猫", "The subject."),
                    ExplainedWord("食べる", "It is the plain past.\nOf to eat."),
                ),
            ),
        )
    }

    // Mutations: leave the Ask section's answer out; put its question over
    // it when it stands alone; put it after the words; head it with
    // something other than its question; take a blank answer for one.
    @Test
    fun `the Ask section's answer is the note alone, and with explained words it leads, each under what it answers`() {
        assertEquals(
            "が marks the subject.\nを marks the object.",
            sentenceCardNote(asked("Grammar breakdown", "が marks the subject.\nを marks the object."), emptyList()),
        )
        assertEquals(
            "Grammar breakdown\nが marks the subject.\n\n食べる\nIt is the plain past.",
            sentenceCardNote(
                asked("Grammar breakdown", "が marks the subject."),
                listOf(ExplainedWord("食べる", "It is the plain past.")),
            ),
        )
        assertEquals(
            "a blank answer is none, so the word's stands alone",
            "It is the plain past.",
            sentenceCardNote(asked("Why が?", "  "), listOf(ExplainedWord("食べる", "It is the plain past."))),
        )
    }

    // Mutations: write a blank text; read another key.
    @Test
    fun `a note round-trips through a bundle, and a blank one writes nothing`() {
        assertEquals("the cat's", Bundle().apply { putAnkiNotes("the cat's") }.getAnkiNotes())
        assertEquals("", Bundle().getAnkiNotes())
        for (blank in listOf(null, "", "  \n")) {
            val bundle = Bundle().apply { putAnkiNotes(blank) }
            assertEquals("", bundle.getAnkiNotes())
            assertFalse("nothing written for '$blank'", bundle.keySet().iterator().hasNext())
        }
    }

    // Mutations: write the text uncut; cut it short of the limit, or with
    // no sign that it goes on; cut a text that is exactly at the limit;
    // split a character that takes two code units.
    @Test
    fun `a note rides in a bundle whole up to the limit, and past it is cut there with an ellipsis`() {
        val max = ANKI_NOTE_MAX_CHARS
        val atLimit = "a".repeat(max)
        assertEquals(atLimit, Bundle().apply { putAnkiNotes(atLimit) }.getAnkiNotes())

        val over = "a".repeat(max) + "tail"
        assertEquals("a".repeat(max) + "…", Bundle().apply { putAnkiNotes(over) }.getAnkiNotes())

        // 😀 is two code units: with the limit falling between them the
        // cut comes before the pair, never through it.
        val pairAtTheCut = "a".repeat(max - 1) + "😀" + "tail"
        assertEquals("a".repeat(max - 1) + "…", ankiNoteCapped(pairAtTheCut))
        val pairBeforeTheCut = "a".repeat(max - 2) + "😀" + "tail"
        assertEquals("a".repeat(max - 2) + "😀" + "…", ankiNoteCapped(pairBeforeTheCut))
    }

    // Mutation: a blank write leaves an earlier note in the bundle.
    @Test
    fun `a blank note takes away the one written before it`() {
        val bundle = Bundle().apply { putAnkiNotes("about the old sentence") }
        bundle.putAnkiNotes(null)
        assertEquals("", bundle.getAnkiNotes())
        assertFalse(bundle.keySet().iterator().hasNext())
    }

    // Mutations: drop the note from any one of the four builders.
    @Test
    fun `both editors' launch args carry the note they are opened with`() {
        assertEquals(
            "n",
            WordAnkiReviewBinder.buildArgs("弾く", "ひく", "", "to play", null, notes = "n").getAnkiNotes(),
        )
        assertEquals(
            "n",
            WordAnkiReviewSheet.newInstance("弾く", "ひく", "", "to play", null, notes = "n")
                .requireArguments().getAnkiNotes(),
        )
        assertEquals(
            "n",
            SentenceAnkiContentView.buildArgs("一番うまく弾けた", "I played it best", emptyList(), notes = "n").getAnkiNotes(),
        )
        assertEquals(
            "n",
            AnkiReviewBottomSheet.newInstance(
                "一番うまく弾けた", "I played it best", emptyMap(), emptyMap(), emptyMap(), null, SourceLangId.JA,
                notes = "n",
            ).requireArguments().getAnkiNotes(),
        )
        // And none where none is given.
        assertEquals("", WordAnkiReviewBinder.buildArgs("弾く", "ひく", "", "to play", null).getAnkiNotes())
        assertEquals("", SentenceAnkiContentView.buildArgs("一番うまく弾けた", "", emptyList()).getAnkiNotes())
    }
}
