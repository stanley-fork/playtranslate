package com.playtranslate

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.ui.ContentSource
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The one-shot move of saved Lapis and JPMN field mappings onto the
 * Yomitan-format definition sources and the Notes source
 * (`Prefs.migrateAnkiGlossaryNotesFieldMappings`, run from
 * [Prefs.migrateLegacyPrefs]).
 *
 * WHICH fields move is
 * [com.playtranslate.ui.AnkiCardTypeMapper.withGlossaryAndNotesSources],
 * pinned cell by cell in AnkiCardTypeMapperTest. This file pins what the
 * prefs side adds: it reaches every saved card type, leaves the others'
 * stored text as it was, and runs once.
 *
 * Raw JSON is seeded directly (bypassing [Prefs.setAnkiFieldMapping]) so the
 * migration sees exactly the shape an older build persisted.
 */
@RunWith(RobolectricTestRunner::class)
class PrefsAnkiGlossaryNotesMigrationTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun sp() =
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)

    @Before fun clearPrefs() { sp().edit().clear().commit() }
    @After fun tearDown() { sp().edit().clear().commit() }

    private fun seedRawMappings(root: JSONObject) {
        sp().edit().putString("anki_field_mappings", root.toString()).commit()
    }

    private fun mappingsOf(vararg models: Pair<Long, JSONObject>) =
        JSONObject().apply { models.forEach { (id, obj) -> put(id.toString(), obj) } }

    private fun fields(vararg pairs: Pair<String, String>) =
        JSONObject().apply { pairs.forEach { (k, v) -> put(k, v) } }

    private val lapis = 1700000001L
    private val jpmn = 1700000002L
    private val custom = 1700000003L

    // Mutations: stop after the first card type; write a field that did
    // not move; skip the marker.
    @Test fun `every saved card type is brought up to the new sources, once`() {
        seedRawMappings(mappingsOf(
            lapis to fields(
                "Expression"     to "EXPRESSION",
                "MainDefinition" to "DEFINITION",   // the old automatic default
                "Glossary"       to "NONE",
                "MiscInfo"       to "NONE",
                "Sentence"       to "SENTENCE",
            ),
            jpmn to fields(
                "Word"              to "EXPRESSION",
                "PrimaryDefinition" to "DEFINITION",
                "AdditionalNotes"   to "NONE",
            ),
            // The same field names on a note type that is neither.
            custom to fields(
                "Front"          to "EXPRESSION",
                "MainDefinition" to "DEFINITION",
                "Glossary"       to "NONE",
                "MiscInfo"       to "NONE",
            ),
        ))

        val prefs = Prefs(ctx)

        val movedLapis = prefs.getAnkiFieldMapping(lapis)
        assertEquals(ContentSource.GLOSSARY_PRIMARY, movedLapis["MainDefinition"])
        assertEquals(ContentSource.GLOSSARY, movedLapis["Glossary"])
        assertEquals(ContentSource.NOTES, movedLapis["MiscInfo"])
        assertEquals(ContentSource.SENTENCE, movedLapis["Sentence"])
        val movedJpmn = prefs.getAnkiFieldMapping(jpmn)
        assertEquals(ContentSource.NOTES, movedJpmn["AdditionalNotes"])
        assertEquals(ContentSource.DEFINITION, movedJpmn["PrimaryDefinition"])
        val keptCustom = prefs.getAnkiFieldMapping(custom)
        assertEquals(ContentSource.DEFINITION, keptCustom["MainDefinition"])
        assertEquals(ContentSource.NONE, keptCustom["Glossary"])
        assertEquals(ContentSource.NONE, keptCustom["MiscInfo"])

        // Once: a field the user sets back afterwards stays as they set it.
        assertTrue(sp().contains("anki_glossary_notes_mapping_migrated"))
        prefs.setAnkiFieldMapping(lapis, movedLapis + ("MainDefinition" to ContentSource.DEFINITION) +
            ("Glossary" to ContentSource.NONE) + ("MiscInfo" to ContentSource.NONE))
        val again = Prefs(ctx).getAnkiFieldMapping(lapis)
        assertEquals(ContentSource.DEFINITION, again["MainDefinition"])
        assertEquals(ContentSource.NONE, again["Glossary"])
        assertEquals(ContentSource.NONE, again["MiscInfo"])
    }

    // A stored name this build reads through its legacy table, on a field
    // the migration does not move, must come out as it went in.
    // Mutation: rewrite every field of a touched card type from its parsed value.
    @Test fun `a field that does not move keeps its stored text`() {
        seedRawMappings(mappingsOf(
            lapis to fields(
                "Expression"       to "EXPRESSION",
                "MainDefinition"   to "DEFINITION",
                "Glossary"         to "NONE",
                "SentenceFurigana" to "SENTENCE_ANKI_FURIGANA",   // a retired name
                "Hint"             to "SOMETHING_FROM_A_NEWER_BUILD",
            ),
        ))

        Prefs(ctx)

        val stored = JSONObject(sp().getString("anki_field_mappings", "")!!).getJSONObject(lapis.toString())
        assertEquals("GLOSSARY_PRIMARY", stored.getString("MainDefinition"))
        assertEquals("GLOSSARY", stored.getString("Glossary"))
        assertEquals("SENTENCE_ANKI_FURIGANA", stored.getString("SentenceFurigana"))
        assertEquals("SOMETHING_FROM_A_NEWER_BUILD", stored.getString("Hint"))
    }

    // Mutation: let a parse failure leave the marker unset.
    @Test fun `no saved mappings, or a corrupt blob, still spends the marker`() {
        Prefs(ctx)
        assertTrue(sp().contains("anki_glossary_notes_mapping_migrated"))

        sp().edit().clear().putString("anki_field_mappings", "{not json").commit()
        Prefs(ctx)
        assertTrue(sp().contains("anki_glossary_notes_mapping_migrated"))
    }
}
