package com.playtranslate.ui

import java.util.Locale
import android.content.Context
import com.playtranslate.R
import com.playtranslate.dictionary.Deinflector
import com.playtranslate.model.ReadingRow

/**
 * A [DefRow] as the markup of one element of a definitions document. The
 * row's chrome (headword, readings, buttons, stub) is built here; its
 * definitions come from [DefinitionsDocument.contentHtml], the one sense
 * loop, so a row and every other document share it.
 *
 * Needs a themed [Context] for the strings and the chip colours, which is
 * why it lives beside the Context-free [DefinitionsDocument] and not in it.
 * Reads resources only, so it may run off the main thread.
 *
 * Every tappable element carries `data-act` with a [DefAction]; the row
 * element carries `data-row` and its DOM id, which is how the page names
 * the row a tap or a rect belongs to. `data-inert` makes a link inside the
 * row a tap on the row: a list row is one tap target that opens its word,
 * and a link in a clamped preview would otherwise take that tap away.
 */
internal object DefRowHtml {

    /**
     * [row] as one element with the DOM id [domId]. [structured] maps a
     * sense's `scRowid` to its glossary JSON; a sense missing from it draws
     * its flat text. [sourceLang] is the language of the headword and its
     * readings, so the engine picks that language's glyphs for them.
     */
    fun html(
        ctx: Context,
        row: DefRow,
        domId: String,
        structured: Map<Long, String>,
        sourceLang: String,
    ): String = when (row) {
        is DefRow.Word -> when (row.variant) {
            DefRow.Variant.CELL -> word(ctx, row, domId, structured, sourceLang)
            DefRow.Variant.LENS -> lens(ctx, row, domId, structured, sourceLang)
        }
        is DefRow.More -> more(row, domId)
        is DefRow.Imported -> imported(ctx, row, domId, structured)
        is DefRow.Sense -> sense(ctx, row, domId)
        is DefRow.SheetWord -> sheetWord(ctx, row, domId, structured, sourceLang)
    }

    /**
     * A word of the Anki sentence sheet. Top to bottom in one element, so
     * the target's wash covers all of it: the title line (the word, its
     * reading under its accent contour, the stars, and the eye at the line's
     * end), the audio row while the word is a target, then the definition,
     * kept clear of the eye's column. The row is the target toggle; the
     * audio row and its two controls, and the eye, each take their own tap.
     * Links are inert: a tap on the definition is a tap on the row.
     */
    private fun sheetWord(
        ctx: Context,
        row: DefRow.SheetWord,
        domId: String,
        structured: Map<Long, String>,
        sourceLang: String,
    ): String {
        val lang = htmlEscape(sourceLang)
        val sb = StringBuilder(1024)
        sb.append("<div class=\"row sw")
            .append(if (row.hidden) " stub" else if (row.target) " tgt" else "")
            .append("\" id=\"").append(domId).append("\" data-row")
        if (!row.hidden) sb.append(" data-inert data-act=\"").append(DefAction.TARGET).append('"')
        sb.append("><div class=\"swt\"><div class=\"swl\" lang=\"").append(lang).append("\">")
            .append("<span class=\"sww\">").append(htmlEscape(row.word)).append("</span>")
        if (!row.hidden) {
            // A kana word has no separate reading but may have an accent:
            // the kana is repeated as the contour's surface.
            val pitchKana = row.reading.takeIf { it.isNotBlank() }
                ?: row.word.takeIf { row.pitch.isNotEmpty() && row.word.all(Deinflector::isKana) }
            if (pitchKana != null) {
                sb.append("<span class=\"swr\">")
                    .append(PitchAccentHtml.pitchAccentHtml(pitchKana, row.pitch).ifEmpty { htmlEscape(pitchKana) })
                    .append("</span>")
            }
            if (row.stars.isNotEmpty()) {
                sb.append("<span class=\"sws\">").append(htmlEscape(row.stars)).append("</span>")
            }
        }
        sb.append("</div>")
        val (eye, eyeLabel) = if (row.hidden) {
            R.drawable.ic_visibility_off to R.string.hidden_word_show_content_description
        } else {
            R.drawable.ic_visibility to R.string.hidden_word_hide_content_description
        }
        sb.append("<button type=\"button\" class=\"swe\" data-act=\"").append(DefAction.TRAILING)
            .append("\" aria-label=\"").append(htmlEscape(ctx.getString(eyeLabel))).append("\">")
            .append(icon(ctx, eye)).append("</button></div>")
        if (row.hidden) return sb.append("</div>").toString()

        row.audio?.let { appendAudioRow(ctx, sb, it) }

        val styled = row.importedGroups.any { g -> g.senses.any { it.scRowid?.let(structured::containsKey) == true } }
        if (styled) {
            sb.append("<div class=\"swd\">")
                .append(
                    DefinitionsDocument.contentHtml(
                        WordDefinitionData(
                            word = row.word, reading = null, senses = emptyList(), freqScore = 0, isCommon = false,
                            importedGroups = row.importedGroups,
                        ),
                        structured,
                        localizePos = { it.joinToString(" \u00b7 ") },
                    ),
                )
                .append("</div>")
        } else if (row.meaning.isNotBlank()) {
            sb.append("<div class=\"swd swm\">")
                .append(htmlEscape(row.meaning.lines().firstOrNull { it.isNotBlank() } ?: row.meaning))
                .append("</div>")
        }
        return sb.append("</div>").toString()
    }

    /**
     * A target's audio row: the preview control (a ringed speaker, a spinner
     * while its audio loads, a pause while it plays), the pill naming the
     * audio source, the row's title and its switch. The whole row is the
     * switch; the preview and the pill are their own targets inside it. The
     * preview is lit while it is busy or the switch is on, the pill and the
     * title while the switch is on.
     */
    private fun appendAudioRow(ctx: Context, sb: StringBuilder, audio: DefRow.Audio) {
        val busy = audio.play != DefRow.Play.IDLE
        sb.append("<div class=\"au").append(if (audio.on) " on" else "")
            .append("\" data-act=\"").append(DefAction.AUDIO)
            .append("\" role=\"switch\" aria-checked=\"").append(audio.on).append("\">")
        sb.append("<button type=\"button\" class=\"auc").append(if (audio.on || busy) " lit" else "")
            .append("\" data-act=\"").append(DefAction.SPEAK).append("\" aria-label=\"")
            .append(htmlEscape(ctx.getString(R.string.anki_audio_preview_content_description)))
            .append("\"><span class=\"aur\">")
        sb.append(
            when (audio.play) {
                DefRow.Play.IDLE -> icon(ctx, R.drawable.ic_lens_speak)
                DefRow.Play.LOADING -> "<span class=\"spin\"></span>"
                DefRow.Play.PLAYING -> icon(ctx, R.drawable.ic_pause)
            },
        )
        sb.append("</span></button>")
        sb.append("<button type=\"button\" class=\"aup\" data-act=\"").append(DefAction.SOURCE)
            .append("\"><span>").append(htmlEscape(audio.source)).append("</span></button>")
        sb.append("<span class=\"aut\">")
            .append(htmlEscape(ctx.getString(R.string.anki_word_audio_row_title)))
            .append("</span><span class=\"tog\"></span></div>")
    }

    /**
     * The imported block of a word's page. With structured content it is the
     * dictionaries' own markup through the one sense loop, headers as the
     * dictionaries wrote them. With none (plain-text dictionaries, or their
     * styling switched off) each definition reads as a sense of the page:
     * the dictionary's name and the part of speech as its eyebrow, with a
     * hairline between them. Under a curation the removed definitions are
     * left out, a dictionary's name rides its first KEPT definition (so
     * removing the one that carried it hands it on), and each kept one
     * carries its ✕.
     */
    private fun imported(ctx: Context, row: DefRow.Imported, domId: String, structured: Map<Long, String>): String {
        val sheet = if (row.look == DefRow.SenseLook.SHEET) " sheet" else ""
        val removeLabel = ctx.getString(R.string.anki_word_remove_content_description)
        val styled = row.groups.any { g -> g.senses.any { s -> s.scRowid?.let { it in structured } == true } }
        if (styled) {
            return "<div class=\"row impf$sheet\" id=\"$domId\" data-row>" +
                DefinitionsDocument.contentHtml(
                    WordDefinitionData(
                        word = "", reading = null, senses = emptyList(), freqScore = 0, isCommon = false,
                        importedGroups = row.groups,
                    ),
                    structured,
                    localizePos = { it.joinToString(" \u00b7 ") },
                    importedCuration = row.curation?.let {
                        DefinitionsDocument.ImportedCuration(it.removed, it.removable, removeLabel)
                    },
                ) + "</div>"
        }
        val sb = StringBuilder(512)
        sb.append("<div class=\"row impl\" id=\"").append(domId).append("\" data-row>")
        var flat = 0
        for (group in row.groups) {
            var labelled = false
            for (sense in group.senses) {
                val index = flat++
                if (row.curation?.removed?.contains(index) == true) continue
                val eyebrow = buildList {
                    if (!labelled) add(group.source)
                    if (sense.pos.isNotBlank()) add(sense.pos)
                }.joinToString(" \u00b7 ").uppercase(Locale.ROOT)
                labelled = true
                sb.append("<div class=\"sense").append(sheet).append("\">")
                appendSense(
                    sb, null, eyebrow,
                    group.accentColor.takeIf { row.look == DefRow.SenseLook.PAGE },
                    sense.definition, null, emptyList(), removeLabel,
                    removeAct = if (row.curation?.removable == true) DefAction.removeItem(index) else null,
                )
                sb.append("</div>")
            }
        }
        return sb.append("</div>").toString()
    }

    private fun sense(ctx: Context, row: DefRow.Sense, domId: String): String {
        val sb = StringBuilder(512)
        sb.append("<div class=\"row sense")
            .append(if (row.look == DefRow.SenseLook.SHEET) " sheet" else "")
            .append(if (row.number != null) " num" else "")
            .append("\" id=\"").append(domId).append("\" data-row>")
        appendSense(
            sb, row.number, row.pos, row.posColor, row.gloss, row.misc, row.examples,
            ctx.getString(R.string.anki_word_remove_content_description),
            removeAct = if (row.removable) DefAction.REMOVE else null,
        )
        return sb.append("</div>").toString()
    }

    /** What a sense holds: the number column, then the eyebrow, the gloss,
     *  the register note and each example behind its rail, then the ✕ that
     *  removes it ([removeAct], null for none). An example's own ✕ sits
     *  beside it. */
    private fun appendSense(
        sb: StringBuilder,
        number: Int?,
        pos: String,
        posColor: Int?,
        gloss: String,
        misc: String?,
        examples: List<DefRow.Example>,
        removeLabel: String,
        removeAct: String?,
    ) {
        number?.let {
            sb.append("<div class=\"sn\">").append(String.format(Locale.getDefault(), "%d", it)).append("</div>")
        }
        sb.append("<div class=\"sb\">")
        if (pos.isNotEmpty()) {
            sb.append("<div class=\"sp\"")
            posColor?.let { sb.append(" style=\"color:").append(DefinitionsDocument.cssHex(it)).append('"') }
            sb.append('>').append(htmlEscape(pos)).append("</div>")
        }
        sb.append("<div class=\"sg\">").append(htmlEscape(gloss)).append("</div>")
        misc?.let { sb.append("<div class=\"sm\">").append(htmlEscape(it)).append("</div>") }
        for (ex in examples) {
            sb.append("<div class=\"ex\"><div class=\"exi\"><div class=\"exs\">")
                .append(htmlEscape(ex.text)).append("</div>")
            if (ex.translation.isNotBlank()) {
                sb.append("<div class=\"ext").append(if (ex.italic) " it" else "").append("\">")
                    .append(htmlEscape(ex.translation)).append("</div>")
            }
            sb.append("</div>")
            ex.removeIndex?.let { appendRemove(sb, DefAction.removeItem(it), removeLabel) }
            sb.append("</div>")
        }
        sb.append("</div>")
        removeAct?.let { appendRemove(sb, it, removeLabel) }
    }

    private fun appendRemove(sb: StringBuilder, act: String, label: String) {
        sb.append("<button type=\"button\" class=\"sx\" data-act=\"").append(act)
            .append("\" aria-label=\"").append(htmlEscape(label)).append("\">\u2715</button>")
    }

    /**
     * A section of the lens's card: the word and its reading on one line
     * (the reading under its accent contour, or the word itself when it is
     * kana and so is its own reading), the chevron at the line's end when
     * the section opens, then everything the lens shows of the word: the
     * conjugation lines, the chips, the warning label, the imported
     * dictionaries in full and the pack's senses. Links are live here.
     */
    private fun lens(
        ctx: Context,
        row: DefRow.Word,
        domId: String,
        structured: Map<Long, String>,
        sourceLang: String,
    ): String {
        val d = row.data
        val reading = d.reading?.takeIf { it.isNotEmpty() }
        val kanaOnly = reading == null && d.pitch.isNotEmpty() && d.word.all(Deinflector::isKana)
        val lang = htmlEscape(sourceLang)
        val sb = StringBuilder(1024)
        sb.append("<div class=\"row lens\" id=\"").append(domId).append("\" data-row")
        if (row.opens) sb.append(" data-act=\"").append(DefAction.OPEN).append('"')
        sb.append("><div class=\"lhead\"><span class=\"lw\" lang=\"").append(lang).append("\">")
        val wordPitch = if (kanaOnly) PitchAccentHtml.pitchAccentHtml(d.word, d.pitch) else ""
        sb.append(wordPitch.ifEmpty { htmlEscape(d.word) }).append("</span>")
        if (reading != null) {
            sb.append("<span class=\"lr\" lang=\"").append(lang).append("\">")
                .append(PitchAccentHtml.pitchAccentHtml(reading, d.pitch).ifEmpty { htmlEscape(reading) })
                .append("</span>")
        }
        if (row.opens) {
            sb.append("<span class=\"lchev\">").append(icon(ctx, R.drawable.ic_lens_chevron)).append("</span>")
        }
        sb.append("</div>")
        sb.append(
            DefinitionsDocument.contentHtml(
                d, structured,
                localizePos = { ctx.localizePos(it) },
                showMisc = false,
                metaChips = styledMetaChips(ctx, d),
                label = row.label,
                inflectionLines = d.inflectedForms.map { InflectionChain.format(ctx, it) },
            ),
        )
        if (d.senses.isEmpty() && d.importedGroups.isEmpty() && row.emptyText != null) {
            sb.append("<div class=\"empty\">").append(htmlEscape(row.emptyText)).append("</div>")
        }
        return sb.append("</div>").toString()
    }

    private fun more(row: DefRow.More, domId: String): String {
        val sb = StringBuilder()
        sb.append("<div class=\"row more\" id=\"").append(domId).append("\" data-row data-act=\"")
            .append(DefAction.MORE).append("\">")
        return sb.append(htmlEscape(row.text)).append("</div>").toString()
    }

    private fun word(
        ctx: Context,
        row: DefRow.Word,
        domId: String,
        structured: Map<Long, String>,
        sourceLang: String,
    ): String {
        val d = row.data
        val stub = row.trailing == DefRow.Trailing.SHOW
        // Every reading in common-use order, or the lone one: the same rule,
        // and the same kana-only case, as the native cell this replaces.
        val readings = d.readingRows.ifEmpty {
            val inline = d.reading?.takeIf { it.isNotEmpty() }
                ?: d.word.takeIf { d.pitch.isNotEmpty() && d.word.all(Deinflector::isKana) }
            if (inline != null) listOf(ReadingRow(d.word, inline, d.pitch, false)) else emptyList()
        }
        // Kana-only: the reading would repeat the title, so the title itself
        // carries the accent.
        val kanaOnly = readings.size == 1 && readings[0].reading == d.word

        val sb = StringBuilder(1024)
        sb.append("<div class=\"row cell").append(if (stub) " stub" else "")
            .append("\" id=\"").append(domId).append("\" data-row data-inert")
        if (row.opens) sb.append(" data-act=\"").append(DefAction.OPEN).append('"')
        sb.append("><div class=\"head\"><div class=\"title\" lang=\"").append(htmlEscape(sourceLang)).append("\">")
        sb.append("<span class=\"w\">")
        val titlePitch = if (!stub && kanaOnly) PitchAccentHtml.pitchAccentHtml(d.word, readings[0].pitch) else ""
        sb.append(titlePitch.ifEmpty { htmlEscape(d.word) }).append("</span>")
        if (!stub && readings.size == 1 && !kanaOnly) appendReading(sb, readings[0], bold = false)
        sb.append("</div>")

        row.speak?.let { speak ->
            if (stub) {
                // The column keeps its place, so the eye does not move when
                // the row is toggled.
                sb.append("<span class=\"act speak ghost\"></span>")
            } else {
                sb.append("<button class=\"act speak\" data-act=\"").append(DefAction.SPEAK).append("\">")
                if (speak == DefRow.Speak.LOADING) sb.append("<span class=\"spin\"></span>")
                else sb.append(icon(ctx, R.drawable.ic_lens_speak))
                sb.append("</button>")
            }
        }
        row.trailing?.let { trailing ->
            val (drawable, label) = when (trailing) {
                DefRow.Trailing.ANKI -> R.drawable.ic_card_stack_add to R.string.cd_add_to_anki
                DefRow.Trailing.HIDE -> R.drawable.ic_visibility to R.string.hidden_word_hide_content_description
                DefRow.Trailing.SHOW -> R.drawable.ic_visibility_off to R.string.hidden_word_show_content_description
            }
            sb.append("<button class=\"act\" data-act=\"").append(DefAction.TRAILING)
                .append("\" aria-label=\"").append(htmlEscape(ctx.getString(label))).append("\">")
                .append(icon(ctx, drawable)).append("</button>")
        }
        if (row.opens) {
            sb.append("<span class=\"act chev\">").append(icon(ctx, R.drawable.ic_chevron_right)).append("</span>")
        }
        sb.append("</div>")
        if (stub) return sb.append("</div>").toString()

        if (readings.size > 1) {
            sb.append("<div class=\"rds\" lang=\"").append(htmlEscape(sourceLang)).append("\">")
            readings.forEach { appendReading(sb, it, bold = it.bolded) }
            sb.append("</div>")
        }
        if (d.inflectedForms.isNotEmpty()) {
            // Capped, so a lemma seen in many forms cannot grow the row
            // without bound; the rest collapse into one "+N more" line.
            val (shown, overflow) = capInflectionForms(d.inflectedForms)
            val lines = shown.map { InflectionChain.format(ctx, it) } +
                listOfNotNull(ctx.getString(R.string.inflection_more, overflow).takeIf { overflow > 0 })
            sb.append("<div class=\"infl\">")
                .append(lines.joinToString("<br>") { htmlEscape(it) }).append("</div>")
        }

        val body = body(ctx, d, structured)
        if (body.isNotEmpty()) sb.append("<div class=\"body\">").append(body).append("</div>")
        return sb.append("</div>").toString()
    }

    /**
     * The definitions under a list row: the meta chips, then the imported
     * dictionaries, then the pack's own senses. An imported block that holds
     * structured content is clamped to a preview (`clamp`), because the row
     * taps through to the word's own page and one long entry must not
     * out-size the list; the pack's senses are never clamped.
     */
    private fun body(ctx: Context, d: WordDefinitionData, structured: Map<Long, String>): String {
        val localize: (List<String>) -> String = { ctx.localizePos(it) }
        val none = WordDefinitionData(word = "", reading = null, senses = emptyList(), freqScore = 0, isCommon = false)
        val sb = StringBuilder()
        val chips = styledMetaChips(ctx, d)
        if (chips.isNotEmpty()) {
            sb.append(DefinitionsDocument.contentHtml(none, emptyMap(), localize, metaChips = chips))
        }
        if (d.importedGroups.isNotEmpty()) {
            val styled = d.importedGroups.any { g -> g.senses.any { it.scRowid?.let(structured::containsKey) == true } }
            sb.append("<div class=\"imp").append(if (styled) " clamp" else "").append("\">")
                .append(DefinitionsDocument.contentHtml(none.copy(importedGroups = d.importedGroups), structured, localize))
                .append("</div>")
        }
        sb.append(
            DefinitionsDocument.contentHtml(
                none.copy(senses = d.senses), emptyMap(), localize,
                showMisc = true, renderMisc = { ctx.renderMiscText(it) },
            ),
        )
        return sb.toString()
    }

    private fun appendReading(sb: StringBuilder, row: ReadingRow, bold: Boolean) {
        sb.append("<span class=\"rd").append(if (bold) " b" else "").append("\">")
            .append(PitchAccentHtml.pitchAccentHtml(row.reading, row.pitch).ifEmpty { htmlEscape(row.reading) })
            .append("</span>")
    }

    private fun icon(ctx: Context, drawable: Int): String =
        VectorDrawableSvg.svg(ctx.resources, drawable, "ic")
}
