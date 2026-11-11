package com.playtranslate.ui

import com.playtranslate.model.ImportedSenseGroup
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The definitions page: the SHELL every definitions document loads once
 * ([documentShellHtml]: the base stylesheet, the row stylesheet, the
 * scoping script, the page's protocol, the CSP), the theme it is given at
 * bind ([themeJs]), and the markup of a word's definitions
 * ([contentHtml], the one sense loop every row of every surface goes
 * through; the rows' own chrome is [DefRowHtml]'s).
 *
 * Dictionary CSS scoping happens in the page, with the engine's own
 * parser: `ptApplyDictCss` wraps a dictionary's styles.css in
 * `[data-dictionary="<id>"] { … }`, parses it with a constructable
 * stylesheet (native CSS nesting keeps Jitendex-style `&` rules and
 * `@media` blocks intact — per-selector prefixing would lose them), then
 * DELETES every top-level rule other than the single scope wrapper. That
 * post-parse sweep is the scope-escape fix: a stylesheet carrying a stray
 * `}` parses into extra top-level rules instead of silently going global
 * (the known hole in Yomitan's string-concat scoping). Scope keys are our
 * opaque dict ids — no title escaping hazards.
 *
 * The scope sits per glossary, on `.gloss-scope`, never on a whole group:
 * a dictionary's stylesheet reaches its own structured content and nothing
 * of ours (the headers, the flat rows, the curation wrapper and its ✕), the
 * same boundary the Anki card draws with `.gl-sc[data-dictionary]`
 * ([WordCardDefinition]). Scoped to the group's section, a rule on a bare
 * element (`button`, `div`, `*`) would restyle that chrome.
 *
 * Engine floor: constructable stylesheets + CSS nesting = Chromium 120
 * (2023). On an older WebView the dictionary's own CSS quietly doesn't
 * apply; the structured layout + base stylesheet still render.
 *
 * Content building is pure (no Context) — theme colors arrive as a
 * [Tokens] value the host resolves from its themed context.
 */
internal object DefinitionsDocument {

    /** Theme palette for the shell, resolved by the host surface from its
     *  own themed context ([com.playtranslate.themeColor] + [cssHex]).
     *  [panel] must be the surface's real panel color (not transparent):
     *  it backs `--background-color`, which dictionary dark-mode CSS
     *  composites against (Jitendex mixes it into its info boxes). */
    class Tokens(
        val text: Int,
        val textMuted: Int,
        val textHint: Int,
        val accent: Int,
        val panel: Int,
        /** Root font size in CSS px (surface scale already applied). */
        val baseFontSizePx: Float,
    )

    /**
     * [argb]'s RGB at [alpha] (0..1), as a CSS color.
     *
     * The chip fills are washes of a theme color, which `color-mix(in srgb,
     * X p%, transparent)` expresses exactly — and which is also exactly X at
     * p% alpha, so we compute it here instead. That is not a style
     * preference: a value containing `var()` cannot be validated at PARSE
     * time, so the declaration wins the cascade and only fails later, at
     * computed-value time, where an unsupported `color-mix` leaves the
     * property at its INITIAL value. A second `background:` written above it
     * as a fallback is never consulted — it lost the cascade before the
     * failure happened. Field symptom: on the Thor's Chromium 109
     * (color-mix landed in 111) every meta chip rendered with NO fill at
     * all, visible the moment a structured dictionary put the panel on the
     * styled renderer. Same rule as [PtCardTemplates]' glossary CSS: the
     * page gets concrete colors, never an engine feature.
     */
    fun cssAlpha(argb: Int, alpha: Float): String =
        cssHex(((alpha.coerceIn(0f, 1f) * 255f).roundToInt() shl 24) or (argb and 0xFFFFFF))

    /**
     * The page-side dictionary CSS scoping (`ptApplyDictCss`): the nesting
     * probe, the wrapper path and the legacy per-selector path. No template
     * tokens: plain script text.
     */
    private const val SCOPING_JS = """var appliedDicts = {};

  // CSS nesting = Chromium 120+. NOT theoretical: AOSP-bundled WebViews in
  // the field sit far below that (AYN Thor ships Chromium 109), and on
  // such engines the nested wrapper parses to an EMPTY scope rule — every
  // dictionary rule silently vanishes. Those engines take the per-selector
  // prefix path instead.
  // BEHAVIOR probe, not a feature flag: the supports-query for the '&'
  // selector LIES on Chromium ~105-119 (the Thor's 109 says yes) — the
  // selector parser accepts '&' several versions before nested rules
  // actually parse, so the wrapper path would keep its scope rule and
  // silently drop every rule inside it. Parse a real nested rule and
  // count its children instead.
  function probeNesting() {
    try {
      var el = document.createElement('style');
      el.media = 'not all';
      el.textContent = '#pt-nest-probe { & span { color: red } }';
      document.head.appendChild(el);
      var r = el.sheet && el.sheet.cssRules;
      var ok = !!(r && r.length === 1 && r[0].cssRules && r[0].cssRules.length === 1);
      document.head.removeChild(el);
      return ok;
    } catch (e) { return false; }
  }
  var supportsNesting = probeNesting();
  // Field-trace seam 3/3 boots with the shell: console lines land in
  // logcat as chromium INFO:CONSOLE, so a single tap traces end to end.
  console.log('pt-shell: up, nesting=' + supportsNesting);

  // Everything below runs on <style> ELEMENTS and their .sheet CSSOM —
  // never the constructable-stylesheet APIs, which Android WebView gained
  // far later than desktop Chrome; on an AOSP WebView those throw and the
  // swallow-catch made every dictionary silently unstyled. Element sheets
  // parse synchronously on appendChild and are CSSOM Level 1 — the oldest
  // thing in the room supports them.
  // Legacy scoping: parse the RAW sheet through a detached-media temp
  // element (flat rules parse fine on old engines), then emit
  // per-selector-prefixed text — escape-proof by construction, since each
  // emitted selector carries the scope. The dictionary's own '&'-nested
  // rules are lost on this path; flat rules and @media survive.
  function applyLegacy(dictId, scope, cssText) {
    var tmp = document.createElement('style');
    tmp.media = 'not all'; // parsed but never applied while we read it
    tmp.textContent = cssText;
    document.head.appendChild(tmp);
    var out = '';
    var prefixed = function (rule) {
      return rule.selectorText.split(',').map(function (s) {
        return scope + ' ' + s.trim();
      }).join(', ') + ' { ' + rule.style.cssText + ' }\n';
    };
    try {
      var rules = (tmp.sheet && tmp.sheet.cssRules) || [];
      for (var j = 0; j < rules.length; j++) {
        var r = rules[j];
        if (r.type === CSSRule.STYLE_RULE) {
          out += prefixed(r);
        } else if (r.type === CSSRule.MEDIA_RULE) {
          var inner = '';
          for (var k = 0; k < r.cssRules.length; k++) {
            if (r.cssRules[k].type === CSSRule.STYLE_RULE) {
              inner += prefixed(r.cssRules[k]);
            }
          }
          if (inner) out += '@media ' + r.conditionText + ' {\n' + inner + '}\n';
        }
        // Other at-rules (@import, @font-face, @keyframes) drop — same
        // policy as Yomitan's legacy path.
      }
    } finally {
      document.head.removeChild(tmp);
    }
    var applied = document.createElement('style');
    applied.textContent = out;
    document.head.appendChild(applied);
    console.log('ptCss[' + dictId + ']: legacy, in=' + cssText.length +
      'ch rules=' + ((applied.sheet && applied.sheet.cssRules) || []).length +
      ' outChars=' + out.length);
  }

  window.ptApplyDictCss = function (dictId, cssText) {
    if (appliedDicts[dictId]) { console.log('ptCss[' + dictId + ']: dedup'); return; }
    appliedDicts[dictId] = true;
    try {
      var scope = '[data-dictionary="' + dictId + '"]';
      if (supportsNesting) {
        var el = document.createElement('style');
        el.textContent = scope + ' {\n' + cssText + '\n}';
        document.head.appendChild(el);
        // Post-parse scope enforcement: legitimate CSS nests entirely
        // inside the one wrapper rule; anything else at the top level is
        // an escape attempt (stray '}') and is deleted.
        var sheet = el.sheet;
        for (var i = sheet.cssRules.length - 1; i >= 0; i--) {
          if (sheet.cssRules[i].selectorText !== scope) sheet.deleteRule(i);
        }
        // Outcome verification, independent of any probe: if the engine
        // kept the wrapper but parsed NOTHING inside it (the exact shape
        // of the selector(&)-lies engines), the nested product is an
        // empty husk — tear it down and go legacy. No engine lie may
        // silently unstyle a dictionary again.
        var innerCount = 0;
        for (var n = 0; n < sheet.cssRules.length; n++) {
          var cr = sheet.cssRules[n].cssRules;
          if (cr) innerCount += cr.length;
        }
        if (innerCount === 0) {
          document.head.removeChild(el);
          console.log('ptCss[' + dictId + ']: nested kept ' +
            sheet.cssRules.length + ' but 0 inner rules; falling to legacy');
          applyLegacy(dictId, scope, cssText);
          return;
        }
        console.log('ptCss[' + dictId + ']: nested, in=' + cssText.length +
          'ch kept=' + sheet.cssRules.length + ' inner=' + innerCount);
      } else {
        applyLegacy(dictId, scope, cssText);
      }
    } catch (e) {
      // NEVER silent again: three device-refuted fixes in a row hid here.
      console.log('ptCss[' + dictId + '] FAILED: ' + e);
    }
  };
"""

    /**
     * The stylesheet of the content itself: the headers, the meta row, the
     * sense rows, the curation wrapper and the structured-content classes.
     * Shared by both shells. [chipFill] and [commonFill] are CSS colour
     * values: concrete rgba from the old shell (see [cssAlpha] for why a
     * computed wash is never left to the engine), variables holding the
     * same from the document shell, which takes its theme at bind.
     */
    private fun contentCss(chipFill: String, commonFill: String): String = """.pos-h {
  font-size: .72em; font-weight: 600; letter-spacing: .07em;
  text-transform: uppercase; color: var(--pt-secondary);
  margin: .65em 0 .2em;
}
.pos-h:first-child { margin-top: .1em; }
.label-warn { font-size: .8em; color: var(--pt-secondary); margin: .1em 0 .3em; }
/* The conjugation line, sized like the label. The right margin is the native
   line's 17dp, clearing the open chevron the lens floats over the page's
   top-right corner (page px track dp: the root size is the flat body's sp). */
.meta-row {
  display: flex; flex-wrap: wrap; align-items: center;
  margin: .1em 0 .15em;
}
/* Spacing by margins, never flex `gap`: that needs Chromium 84, and an
   Android 10 WebView that was never updated is older. */
.meta-row > * { margin: 0 .3em .3em 0; }
.meta-chip {
  font-size: .7em; color: var(--pt-secondary);
  /* Concrete rgba, resolved Android-side — see [cssAlpha]. NEVER color-mix
     with a var() operand: the fallback declaration above it can't save an
     engine that lacks the function, and the chip loses its fill entirely. */
  background: $chipFill;
  border-radius: 4px; padding: .18em .55em;
}
.meta-chip.common {
  color: var(--pt-accent);
  background: $commonFill;
  border-radius: 1em;
}
.meta-chip.stars { background: none; padding-left: 0; padding-right: 0; }
/* Icon-bearing chips only: a text-only chip stays a plain inline box so a
   long frequency source name can still wrap inside it. */
.meta-chip.with-icon { display: inline-flex; align-items: center; }
/* 1em square, matching the native pill's icon-sized-to-its-text rule
   ([AnkiDeckBadge.buildPill] bounds the drawable at the text size). */
.chip-icon { width: 1em; height: 1em; flex: none; margin-right: .35em; }
.def-row { display: flex; margin: .18em 0; }
.def-num { color: var(--pt-hint); font-size: .85em; padding-top: .12em; margin-right: .4em; }
.def-text { flex: 1; min-width: 0; }
.def-misc { font-size: .76em; font-style: italic; color: var(--pt-hint); margin-top: .12em; }
.dict-group { margin: 0 0 .4em; }
.gloss-item + .gloss-item { margin-top: .4em; }
/* Consecutive structured senses were spaced by the rule above while their
   items were direct siblings in the section; each sense's items now live in
   their own dictionary scope, so the scopes carry that gap. */
.gloss-scope + .gloss-scope { margin-top: .4em; }
/* The Anki word sheet's imported-row curation only ([ImportedCuration]); no
   other surface emits these classes. The ✕ is a plain box in the row's flex
   line: no flex of its own (unverified on the API 30 WebView) and no negative
   margin, since a hit box reaching past its own row could remove a neighbour. */
.imp-sense { display: flex; align-items: flex-start; }
.imp-sense + .imp-sense { margin-top: .4em; }   /* replaces the cross-sense .gloss-scope + .gloss-scope gap */
.imp-body { flex: 1; min-width: 0; }
/* Under curation the sibling rule above owns the spacing; a flat row's own
   margins would no longer collapse (its body is a flex item) and would
   double the gap, so they go. */
.imp-body > .def-row { margin: 0; }
.imp-sense > .imp-x {
  flex: none; width: 40px; min-height: 40px; margin: 0 0 0 .25em; padding: .35em 0 0;
  border: 0; background: none; font: inherit; font-size: .875em; text-align: center;
  color: var(--pt-secondary);
}
.gloss-link { color: var(--pt-accent); text-decoration: underline; }
.gloss-image { max-width: 100%; height: auto; border-radius: 4px; vertical-align: text-bottom; }
.gloss-sc-table-container { overflow-x: auto; max-width: 100%; }
.gloss-sc-table { border-collapse: collapse; }
.gloss-sc-th, .gloss-sc-td {
  border: 1px solid var(--pt-hint); padding: .25em .45em; vertical-align: top;
}
.gloss-sc-ul, .gloss-sc-ol { margin: .25em 0; padding-left: 1.5em; }
.gloss-sc-li { margin: .1em 0; }
.gloss-sc-details { margin: .2em 0; }
.gloss-sc-summary { cursor: pointer; color: var(--pt-secondary); }
ruby > rt { font-size: .5em; }
"""

    /** ARGB Int → CSS color. Alpha-aware: opaque renders #RRGGBB, else
     *  rgba(). */
    fun cssHex(argb: Int): String {
        val a = argb ushr 24
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        // Locale.ROOT is load-bearing, not hygiene: the default-locale
        // formatter localizes BOTH conversions here — '%.3f' takes a comma
        // decimal separator (de, fr, ru, pt) and '%d' can take non-Latin
        // digits (ar). Either turns the value into something the CSS parser
        // rejects, the declaration drops, and background falls back to
        // transparent — the same no-fill failure as the color-mix trap
        // above, reached by a different door and only on some devices.
        return if (a == 0xFF) {
            String.format(Locale.ROOT, "#%02X%02X%02X", r, g, b)
        } else {
            String.format(Locale.ROOT, "rgba(%d,%d,%d,%.3f)", r, g, b, a / 255f)
        }
    }

    // ── The document shell ──────────────────────────────────────────────

    /**
     * A document's theme: the shell's [Tokens] plus the two colours only
     * rows use, the hairline between them ([divider]) and the sunk band of
     * a hidden word's stub ([band]). Resolved by the host from its themed
     * context, like [Tokens].
     */
    class DocTheme(
        val tokens: Tokens,
        val divider: Int,
        val band: Int,
        /** The colour of an action row's text ([DefRow.More]); the accent
         *  where the surface has no link colour of its own. */
        val link: Int = tokens.accent,
        /** The colour of a control that is switched off (an audio row's
         *  ring, pill and title, its switch's outline). */
        val outline: Int = tokens.textHint,
        /** The fill behind a row that is the card's target. */
        val wash: Int = 0,
        /** Whether the rows take two columns on a page at least 560px wide
         *  (a landscape screen). The results' Words card asks for it; every
         *  other surface's rows are one column at any width. */
        val twoColumnsWhenWide: Boolean = false,
    )

    /**
     * The `ptTheme` call that gives a loaded shell [theme]. Every colour is
     * a concrete value computed here, the washes included (see [cssAlpha]):
     * the stylesheet only ever reads a variable that already holds a colour
     * the engine can use as is. The call also says whether the rows take
     * two columns on a wide page ([DocTheme.twoColumnsWhenWide]), true or
     * false, never left out.
     */
    fun themeJs(theme: DocTheme, rtl: Boolean): String {
        val t = theme.tokens
        val fg = cssHex(t.text)
        val panel = cssHex(t.panel)
        val vars = listOf(
            "--pt-fg" to fg,
            "--pt-secondary" to cssHex(t.textMuted),
            "--pt-hint" to cssHex(t.textHint),
            "--pt-accent" to cssHex(t.accent),
            "--pt-panel" to panel,
            "--pt-divider" to cssHex(theme.divider),
            "--pt-band" to cssHex(theme.band),
            "--pt-outline" to cssHex(theme.outline),
            "--pt-wash" to cssHex(theme.wash),
            "--pt-press" to cssAlpha(t.text, 0.08f),
            "--pt-rail" to cssAlpha(t.accent, 0.35f),
            "--pt-link" to cssHex(theme.link),
            "--pt-chip" to cssAlpha(t.text, 0.10f),
            "--pt-common" to cssAlpha(t.accent, 0.16f),
            // Yomitan's theme contract: dictionary CSS adapts to dark mode
            // through these.
            "--text-color" to fg,
            "--fg" to fg,
            "--background-color" to panel,
            "--canvas" to panel,
        ).joinToString(",") { (k, v) -> "\"$k\":\"$v\"" }
        return "ptTheme({vars:{$vars},dir:\"${if (rtl) "rtl" else "ltr"}\",fontPx:${t.baseFontSizePx}," +
            "cols:${theme.twoColumnsWhenWide}})"
    }

    /**
     * The shell of a [DefinitionDocumentView]: one page that holds a whole
     * surface's rows ([DefRowHtml]) and is told its theme at bind
     * (`ptTheme`), so a loaded shell fits any surface and can be kept ready.
     *
     * To the page: `ptTheme`, `ptApplyDictCss`, `ptSet(html, gen)` (all the
     * rows), `ptAppend(html, gen)` (more rows at the end), `ptReplace(id,
     * html, gen)` (one element), `ptCursor(rowId, act)` (the controller's
     * highlight). From the page: `PTBridge.onLayout(gen, height, targets)`
     * after every change, `targets` a flat JSON array of rowId, act, left,
     * top, width, height (CSS px from the root's corner) for every row and
     * every tappable element; and `PTBridge.onAction(rowId, act)` for a tap.
     *
     * The page is the authority on what a tap hit, as in the first shell: a
     * link navigates (the client hands it to the browser) unless its row is
     * `data-inert`, and then the tap is the row's. The height is the root's
     * own rect, never the document's scroll height, and nothing is reported
     * while the viewport has no width (both rules are the first shell's,
     * for the same reasons).
     */
    fun documentShellHtml(): String = """<!doctype html>
<html><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; img-src ${YomitanContentHtml.MEDIA_ORIGIN}; script-src 'unsafe-inline'">
<style>
* { box-sizing: border-box; }
html, body { margin: 0; padding: 0; background: transparent; }
#root { display: flow-root; }
body {
  color: var(--pt-fg);
  font-family: sans-serif;
  line-height: 1.4;
  word-wrap: break-word;
  -webkit-tap-highlight-color: transparent;
}
${contentCss("var(--pt-chip)", "var(--pt-common)")}
${PitchAccentHtml.PITCH_CSS}
$ROW_CSS
</style>
<script>
(function () {
  'use strict';
  $SCOPING_JS
$DOCUMENT_JS
})();
</script>
</head><body><div id="root"></div></body></html>"""

    /**
     * The rows' own chrome. Lengths that the native rows gave in dp are px
     * here (the viewport is device-width at scale 1, so a CSS px is a dp);
     * text sizes are em of the body size, which is the surface's scaled
     * gloss size. Spacing is by margins, never flex `gap` (see [contentCss]),
     * and the physical left/right pairs are mirrored for right-to-left by
     * hand, since the logical properties need a newer engine than the floor.
     */
    private const val ROW_CSS = """
.row { position: relative; }
/* The hairline between rows, inset from the start like the native divider. */
.row + .row::before {
  content: ''; position: absolute; top: 0; left: 16px; right: 0; height: 1px;
  background: var(--pt-divider);
}
[dir=rtl] .row + .row::before { left: 0; right: 16px; }
.cell { padding: 14px 4px 14px 16px; }
[dir=rtl] .cell { padding: 14px 16px 14px 4px; }
/* Pressed only when the press is the row's own (the page marks the others
   np): an element is :active while anything inside it is, so the row would
   light up behind its own button. */
.cell[data-act]:active:not(.np), .row.cur { background: var(--pt-press); }
.cell.stub { padding-top: 8px; padding-bottom: 8px; background: var(--pt-band); }
.head { display: flex; align-items: center; }
.title { flex: 1; min-width: 0; display: flex; flex-wrap: wrap; align-items: baseline; }
.w { font-size: 1.636em; font-weight: 700; letter-spacing: -.02em; margin-right: 10px; }
[dir=rtl] .w { margin-right: 0; margin-left: 10px; }
.stub .w { font-size: 1.064em; color: var(--pt-secondary); }
.rd { font-size: .909em; color: var(--pt-secondary); }
.rd.b { color: var(--pt-fg); font-weight: 700; }
/* Bold in a CJK language is drawn here, never asked of the engine. The
   system's CJK font has one weight, and the engine's made-up bold thickens
   an unhinted outline: at these sizes the strokes of a dense character
   (量, 鬱) close into a block. The native rows' fake bold was hinted, which
   kept a pixel between the strokes. So the regular weight under a stroke
   of our own, two thirds of the platform's, light enough to keep the gaps
   open. The language is the element's own (its lang attribute). */
.w:lang(ja), .w:lang(zh), .w:lang(ko),
.rd.b:lang(ja), .rd.b:lang(zh), .rd.b:lang(ko),
.sww:lang(ja), .sww:lang(zh), .sww:lang(ko) { font-weight: 400; -webkit-text-stroke: .02em; }
.rds { margin: 2px 12px 0 0; }
.rds .rd { display: inline-block; margin: 0 12px 6px 0; }
[dir=rtl] .rds { margin: 2px 0 0 12px; }
[dir=rtl] .rds .rd { margin: 0 0 6px 12px; }
.act {
  flex: none; width: 36px; height: 40px; margin: 0 0 0 16px; padding: 0;
  border: 0; border-radius: 50%; background: none; color: var(--pt-secondary);
  display: flex; align-items: center; justify-content: center;
}
[dir=rtl] .act { margin: 0 16px 0 0; }
.act.speak, [dir=rtl] .act.speak { margin: 0; }
.act.chev { color: var(--pt-hint); }
button.act:active, .act.cur { background: var(--pt-press); }
.ic { width: 22px; height: 22px; }
.spin {
  width: 20px; height: 20px; border-radius: 50%;
  border: 2px solid var(--pt-accent); border-right-color: transparent;
  animation: ptspin .8s linear infinite;
}
@keyframes ptspin { to { transform: rotate(360deg); } }
.infl { font-size: .788em; color: var(--pt-secondary); margin: 2px 12px 0 0; }
.body { margin: .606em 12px 0 0; }
[dir=rtl] .infl { margin: 2px 0 0 12px; }
[dir=rtl] .body { margin: .606em 0 0 12px; }
/* An imported block with structured content is a preview: capped, with a
   fade when it is cut (the page marks `over` once it knows). */
.clamp { position: relative; max-height: 8em; overflow: hidden; }
.clamp.over::after {
  content: ''; position: absolute; left: 0; right: 0; bottom: 0; height: 28px;
  background: linear-gradient(transparent, var(--pt-panel));
}
/* In a preview a flat imported sense keeps to four lines. */
.pv > .dict-group > .def-row > .def-text {
  display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 4; overflow: hidden;
}
.empty { font-size: 1.06em; color: var(--pt-secondary); }
/* A one-line action under the rows ("Load more"). */
.more {
  min-height: 44px; padding: 0 16px; display: flex; align-items: center; justify-content: center;
  font-size: .985em; color: var(--pt-link); white-space: nowrap;
}
.more:active { background: var(--pt-press); }
/* Two columns on a page at least 560px wide (a landscape screen), for the
   surface whose theme asks (the results' Words card: ptTheme sets and
   clears `cols`). The columns are equal, and a long word cannot widen one.
   A word row is its own cell, and a grid item stretches to its line's
   height, so a press, a stub's band and the controller's highlight cover
   the whole cell beside a taller neighbour. Each row keeps its hairline at
   the top of its own cell; the second row is the first of its column and
   has none (unless it is the action row, which is no column's first). The gap is given by both its names: grid-column-gap, the old
   one, is the one every engine with grid takes. The action row spans both
   columns. */
@media (min-width: 560px) {
  .cols #root {
    display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
    grid-column-gap: 8px; column-gap: 8px;
  }
  .cols #root > .row:nth-child(2):not(.more)::before { content: none; }
  .cols #root > .more { grid-column: 1 / -1; }
}
/* A word's own page: the imported block in full, then one row per sense.
   Sizes are the native rows' sp, as px (the page's text zoom is the
   system's font scale). A numbered row's hairline starts at its gloss. */
.impf { padding: 10px 16px; }
.sense { position: relative; display: flex; min-height: 56px; padding: 14px 16px; }
.row + .sense.num::before { left: 42px; }
[dir=rtl] .row + .sense.num::before { left: 0; right: 42px; }
.impl > .sense + .sense::before {
  content: ''; position: absolute; top: 0; left: 16px; right: 0; height: 1px;
  background: var(--pt-divider);
}
[dir=rtl] .impl > .sense + .sense::before { left: 0; right: 16px; }
.sn {
  flex: none; min-width: 16px; margin: 2px 10px 0 0;
  font-family: monospace; font-size: 12px; font-weight: 700; color: var(--pt-accent);
}
[dir=rtl] .sn { margin: 2px 0 0 10px; }
.sb { flex: 1; min-width: 0; }
.sp { font-size: 10px; font-weight: 500; line-height: 1.3; letter-spacing: .12em; color: var(--pt-secondary); }
.sg { font-size: 18px; font-weight: 500; line-height: 1.25; }
.sp + .sg { margin-top: 6px; }
.sm { font-size: 12px; line-height: 1.3; font-style: italic; color: var(--pt-hint); margin-top: 4px; }
.sg, .sm, .exs, .ext { white-space: pre-wrap; }
/* An example: a rail and, pushed back by its muted type, the source line
   over its translation. */
.ex { display: flex; margin-top: 10px; }
.ex + .ex { margin-top: 2px; }
.ex::before { content: ''; flex: none; width: 2px; background: var(--pt-rail); }
.exi { flex: 1; min-width: 0; margin-left: 12px; }
[dir=rtl] .exi { margin-left: 0; margin-right: 12px; }
.exs { font-size: 14px; font-weight: 500; line-height: 1.5; color: var(--pt-secondary); }
.ext { font-size: 13px; line-height: 1.45; color: var(--pt-secondary); margin-top: 2px; }
.ext.it { font-style: italic; }
/* The Anki word sheet's card: a notch denser than the page, with a ✕ on
   everything that can be taken off the card. The ✕ is a plain box at the
   top of its line, inside its own row, so its hit box never reaches a
   neighbour. */
.impf.sheet { padding: 8px 16px; }
.sense.sheet { min-height: 0; }
.sheet .sg { font-size: 16px; }
.sheet .exs { color: var(--pt-fg); }
.sx {
  flex: none; align-self: flex-start; margin: 0 0 0 8px; padding: 4px 10px;
  border: 0; border-radius: 6px; background: none;
  font: inherit; font-size: 14px; line-height: 1.3; color: var(--pt-secondary);
}
[dir=rtl] .sx { margin: 0 8px 0 0; }
.ex > .sx { margin: 0 0 0 4px; }
[dir=rtl] .ex > .sx { margin: 0 4px 0 0; }
.sx:active, .sx.cur, .imp-x:active, .imp-x.cur { background: var(--pt-press); }
/* The Anki sentence sheet's words. The row clips, so the eye's widened tap
   area never reaches a neighbour. */
.sw { padding: 12px 0; overflow: hidden; }
.sw.tgt { background: var(--pt-wash); }
.sw.stub { padding: 8px 0; background: var(--pt-band); }
.swt { display: flex; align-items: center; padding: 0 12px 0 16px; }
[dir=rtl] .swt { padding: 0 16px 0 12px; }
.swl { flex: 1; min-width: 0; display: flex; align-items: center; }
.sww { min-width: 0; font-size: 16px; font-weight: 700; line-height: 1.3; }
.tgt .sww { color: var(--pt-accent); }
.stub .sww { color: var(--pt-secondary); }
.swr, .sws { flex: none; margin-left: 8px; color: var(--pt-hint); }
.swr { font-size: 12px; }
.sws { font-size: 11px; }
[dir=rtl] .swr, [dir=rtl] .sws { margin-left: 0; margin-right: 8px; }
/* The eye: a 20px glyph in a 40px column, level with the first line of a
   word that wraps. Its tap area is a 48px square around the glyph (the
   pseudo-element), which leaves the column, and so the title's width, as
   they are. */
.swe {
  flex: none; align-self: flex-start; position: relative; width: 40px; height: 20px;
  padding: 0; border: 0; border-radius: 6px; background: none; color: var(--pt-secondary);
  display: flex; align-items: center; justify-content: center;
}
.stub .swe { align-self: center; }
.swe .ic { width: 20px; height: 20px; }
.swe::after { content: ''; position: absolute; top: -14px; bottom: -14px; left: -4px; right: -4px; }
.swe.cur { background: var(--pt-press); }
/* The definition keeps clear of the eye's column: text there would turn a
   tap meant for the row into a hide. */
.swd { margin-top: 3px; padding: 0 52px 0 16px; }
[dir=rtl] .swd { padding: 0 16px 0 52px; }
.swm { font-size: 13px; color: var(--pt-secondary); }
/* A target's audio row, above the eye's tap area so what the square overlaps
   of the row stays the row's. */
.au {
  position: relative; z-index: 1; display: flex; align-items: center;
  min-height: 44px; padding: 4px 16px;
}
.au:active, .au.cur { background: var(--pt-press); }
.auc {
  flex: none; width: 44px; height: 44px; margin: 0 7px 0 -7px; padding: 0;
  border: 0; background: none; color: var(--pt-outline);
  display: flex; align-items: center; justify-content: center;
}
[dir=rtl] .auc { margin: 0 -7px 0 7px; }
.auc.lit { color: var(--pt-accent); }
.aur {
  width: 30px; height: 30px; border-radius: 50%; border: 1px solid currentColor;
  display: flex; align-items: center; justify-content: center;
}
.aur .ic, .aur .spin { width: 16px; height: 16px; }
.aup {
  flex: 0 1 auto; min-width: 0; height: 44px; margin: 0 12px 0 0; padding: 0;
  border: 0; background: none; font: inherit; color: var(--pt-outline);
  display: flex; align-items: center;
}
[dir=rtl] .aup { margin: 0 0 0 12px; }
.au.on .aup { color: var(--pt-accent); }
.aup > span {
  min-width: 0; height: 30px; line-height: 28px; padding: 0 10px;
  border: 1px solid currentColor; border-radius: 15px; font-size: 12px;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.auc.cur .aur, .aup.cur > span { background: var(--pt-press); }
.aut {
  flex: 1; min-width: 0; margin: 0 16px 0 0; font-size: 15px; font-weight: 500;
  color: var(--pt-outline); white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
[dir=rtl] .aut { margin: 0 0 0 16px; }
.au.on .aut { color: var(--pt-fg); }
/* The switch, drawn as the app's own: a small thumb in an outlined track
   when off, a large one in the accent track when on. */
.tog {
  flex: none; position: relative; width: 52px; height: 32px; border-radius: 16px;
  border: 2px solid var(--pt-outline); background: var(--pt-divider);
}
.tog::after {
  content: ''; position: absolute; top: 6px; left: 6px; width: 16px; height: 16px;
  border-radius: 50%; background: var(--pt-secondary);
}
.au.on .tog { border-color: transparent; background: var(--pt-accent); }
.au.on .tog::after { top: 2px; left: 22px; width: 24px; height: 24px; background: #DADADA; }
[dir=rtl] .tog::after { left: auto; right: 6px; }
[dir=rtl] .au.on .tog::after { left: auto; right: 22px; }
"""

    /**
     * The document shell's own script: the layout report, the row set,
     * append and replace entry points, the cursor and the click listener.
     */
    private const val DOCUMENT_JS = """
  // Render generation: stamped by ptSet and echoed with every layout
  // report, so the Kotlin side drops a report from a row set that has
  // since been replaced. ptAppend and ptReplace carry the generation they
  // were built for and do nothing when it is no longer the page's.
  var gen = 0;
  var cursor = null;

  function rootEl() { return document.getElementById('root'); }

  // A clamped preview shows its fade only when something is cut.
  function markOverflow(r) {
    var c = r.querySelectorAll('.clamp');
    for (var i = 0; i < c.length; i++) {
      var over = c[i].scrollHeight > c[i].clientHeight + 1;
      if (over !== c[i].classList.contains('over')) c[i].classList.toggle('over');
    }
  }

  function report() {
    var r = rootEl();
    if (!r) return;
    // Not laid out yet (zero-width viewport): content would wrap at every
    // character and report an enormous bogus height. The resize listener
    // reports the moment the real width arrives.
    if (document.documentElement.clientWidth < 1) return;
    markOverflow(r);
    // The root's own rect, never the document's scroll height: that is
    // max(content, viewport), and would echo an oversized view back forever.
    var base = r.getBoundingClientRect();
    var out = [];
    var els = r.querySelectorAll('[data-row],[data-act]');
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      var row = el.closest('[data-row]');
      if (!row) continue;
      var b = el.getBoundingClientRect();
      if (b.width <= 0 || b.height <= 0) continue;
      out.push(row.id, el.getAttribute('data-act') || '',
        Math.round(b.left - base.left), Math.round(b.top - base.top),
        Math.round(b.width), Math.round(b.height));
    }
    if (window.PTBridge && window.PTBridge.onLayout) {
      window.PTBridge.onLayout(gen, Math.ceil(base.height), JSON.stringify(out));
    }
  }

  // Now (getBoundingClientRect forces layout, and this works while the view
  // is not yet drawn, when no animation frame ticks), then two frames on,
  // when images with declared ratios have taken their boxes.
  function settle() {
    report();
    requestAnimationFrame(function () { requestAnimationFrame(report); });
  }

  function applyCursor() {
    var old = document.querySelectorAll('.cur');
    for (var i = 0; i < old.length; i++) old[i].classList.remove('cur');
    if (!cursor) return;
    var row = document.getElementById(cursor[0]);
    if (!row) return;
    var el = row.getAttribute('data-act') === cursor[1]
      ? row : row.querySelector('[data-act="' + cursor[1] + '"]');
    if (el) el.classList.add('cur');
  }

  window.ptTheme = function (t) {
    var s = document.documentElement.style;
    for (var k in t.vars) s.setProperty(k, t.vars[k]);
    document.documentElement.setAttribute('dir', t.dir);
    document.body.style.fontSize = t.fontPx + 'px';
    // Set or cleared on every call, never only set: a loaded shell is made
    // to fit any surface, and each call is a whole theme, so a surface whose
    // rows are one column never keeps two from a theme before it.
    document.documentElement.classList.toggle('cols', !!t.cols);
  };

  window.ptSet = function (html, g) {
    gen = g;
    rootEl().innerHTML = html;
    applyCursor();
    settle();
  };

  window.ptAppend = function (html, g) {
    if (g !== gen) return;
    rootEl().insertAdjacentHTML('beforeend', html);
    settle();
  };

  window.ptReplace = function (id, html, g) {
    if (g !== gen) return;
    var el = document.getElementById(id);
    if (!el) return;
    el.outerHTML = html;
    applyCursor();
    settle();
  };

  window.ptCursor = function (rowId, act) {
    cursor = rowId ? [rowId, act] : null;
    applyCursor();
  };

  // A press lights a row only when the row itself is what the tap would
  // fire: not one of its buttons, and not a link that is live. The mark
  // stays until the next touch begins, because the engine keeps a quick
  // tap's pressed look for a moment after the finger has lifted.
  var unpressed = null;
  document.addEventListener('touchstart', function (e) {
    if (unpressed) { unpressed.classList.remove('np'); unpressed = null; }
    var t = e.target;
    var row = t && t.closest ? t.closest('[data-row]') : null;
    if (!row) return;
    var link = t.closest('a[href]');
    var own = t.closest('[data-act]') === row && (!link || row.hasAttribute('data-inert'));
    if (!own) { unpressed = row; row.classList.add('np'); }
  }, { passive: true });

  // A press held on an element that asks for it (data-hold) reports that
  // element's action as held, and is not also a tap. The timer dies with
  // the touch: a finger that moves away, lifts, or loses the gesture to the
  // host's scroll.
  var holdTimer = null, held = false, holdX = 0, holdY = 0;
  function endHold() {
    if (holdTimer) { clearTimeout(holdTimer); holdTimer = null; }
  }
  document.addEventListener('touchstart', function (e) {
    endHold();
    held = false;
    var t = e.target;
    var el = t && t.closest ? t.closest('[data-hold]') : null;
    var row = el ? el.closest('[data-row]') : null;
    if (!row || e.touches.length !== 1) return;
    holdX = e.touches[0].clientX;
    holdY = e.touches[0].clientY;
    holdTimer = setTimeout(function () {
      holdTimer = null;
      held = true;
      if (window.PTBridge && window.PTBridge.onAction) {
        window.PTBridge.onAction(row.id, el.getAttribute('data-act') + ':hold');
      }
    }, 500);
  }, { passive: true });
  document.addEventListener('touchmove', function (e) {
    if (!holdTimer) return;
    var p = e.touches[0];
    if (Math.abs(p.clientX - holdX) > 10 || Math.abs(p.clientY - holdY) > 10) endHold();
  }, { passive: true });
  document.addEventListener('touchend', endHold, { passive: true });
  document.addEventListener('touchcancel', endHold, { passive: true });

  document.addEventListener('click', function (e) {
    if (held) {
      held = false;
      e.preventDefault();
      return;
    }
    var t = e.target;
    if (!t || !t.closest) return;
    var row = t.closest('[data-row]');
    var a = t.closest('a[href]');
    if (a) {
      if (!row || !row.hasAttribute('data-inert')) return;
      e.preventDefault();
    }
    if (!row) return;
    var el = t.closest('[data-act]');
    if (!el) return;
    if (window.PTBridge && window.PTBridge.onAction) {
      window.PTBridge.onAction(row.id, el.getAttribute('data-act'));
    }
  });

  // Direct, not via a frame: this is what fires when a hidden view first
  // gets its real width.
  window.addEventListener('resize', report);
"""

    // ── Content ─────────────────────────────────────────────────────────

    /** One chip of a document's meta row: the Common pill, the ★ run, a
     *  frequency chip or the deck badge (built Android-side, where strings
     *  and theme live). */
    class MetaChip(
        val text: String,
        val kind: Kind = Kind.NEUTRAL,
        /** A frequency chip's accent-override colours from [freqChipColors]
         *  — BOTH halves, so this document can only ever paint the pair the
         *  native rows paint. Null = no override, and the stylesheet's
         *  neutral chip treatment stands. */
        val tint: MetaChipColors? = null,
        /** Leading glyph, drawn in the chip's own colour. Named, never
         *  markup: the page's icons stay this document's to author, so a
         *  chip builder can't inject into it. */
        val icon: Icon = Icon.NONE,
    ) {
        enum class Kind { NEUTRAL, COMMON, STARS }

        /** Glyphs the meta row can draw. Each has a native counterpart the
         *  flat renderer draws for the SAME chip — add one only alongside
         *  the builder that owns both mediums (see [AnkiDeckBadge]). */
        enum class Icon { NONE, CARD_STACK }
    }

    /**
     * The Anki word sheet's per-card removal of imported rows inside a block
     * with structured content, the counterpart of the ✕ each pack sense
     * carries ([DefRow.Sense.removable]). Every other surface passes none
     * and keeps its markup exactly as it was.
     *
     * A row's identity is its flat index across ALL the entry's imported
     * senses in group order ([WordCardDefinition.removedImported]'s key),
     * and the page reports that ORIGINAL index, never a kept position: kept
     * positions shift with every removal, so a tap landing on a swap the
     * sheet has since superseded would name a different row.
     */
    class ImportedCuration(
        /** Flat indexes already removed; those senses are not emitted. */
        val removed: Set<Int>,
        /** False when the card is down to its last visible row, imported and
         *  pack alike: the rows still carry their index, but no ✕ is drawn. */
        val removable: Boolean,
        /** The ✕'s accessible name
         *  (`R.string.anki_word_remove_content_description`). */
        val removeLabel: String,
    )

    /**
     * A word's definitions as markup, the body of a row of the page
     * ([DefRowHtml]): imported groups first (in
     * their given order — [TermMerge] already ordered them), each in its
     * own `<section class="dict-group">`, then the pack's own senses with
     * the classic numbered layout. A structured sense's glossary is wrapped
     * in `<div class="gloss-scope" data-dictionary>`, the one element its
     * dictionary's scoped CSS is keyed to, so the stylesheet reaches its own
     * structured content and nothing of ours (the headers, the flat rows,
     * the curation wrapper and its ✕), the same boundary the card draws with
     * `.gl-sc[data-dictionary]`. A flat sense's row carries no scope: its
     * text is our rendering, not the dictionary's content. [data]'s
     * flattened imported rows are skipped here — the groups ARE those rows
     * in structured form.
     *
     * [structured] maps [com.playtranslate.model.ImportedSense.scRowid] →
     * glossary JSON; senses without an entry render their flat text.
     * [localizePos] is the pack-sense POS localizer (imported headers stay
     * verbatim).
     *
     * [importedCuration] (the Anki word sheet only) leaves its removed
     * senses out, a group with none left dropping its section, and wraps
     * each kept one in `.imp-sense` whose `data-imp` is the sense's ORIGINAL
     * flat index (see [ImportedCuration]), with the ✕ beside the body when
     * removal is allowed. The ✕ carries `data-act`
     * ([DefAction.removeItem] of that index), which is how the page reports
     * its tap. Null emits no wrapper at all.
     */
    fun contentHtml(
        data: WordDefinitionData,
        structured: Map<Long, String>,
        localizePos: (List<String>) -> String,
        showMisc: Boolean = false,
        renderMisc: (List<String>) -> String? = { null },
        metaChips: List<MetaChip> = emptyList(),
        label: String? = null,
        importedCuration: ImportedCuration? = null,
    ): String {
        val sb = StringBuilder()
        label?.takeIf { it.isNotBlank() }?.let {
            sb.append("<div class=\"label-warn\">").append(htmlEscape(it)).append("</div>")
        }
        if (metaChips.isNotEmpty()) {
            sb.append("<div class=\"meta-row\">")
            for (chip in metaChips) {
                val cls = when (chip.kind) {
                    MetaChip.Kind.COMMON -> "meta-chip common"
                    MetaChip.Kind.STARS -> "meta-chip stars"
                    MetaChip.Kind.NEUTRAL -> "meta-chip"
                }
                val tint = chip.tint?.let {
                    " style=\"background:${cssHex(it.fill)};color:${cssHex(it.text)}\""
                }.orEmpty()
                val withIcon = if (chip.icon == MetaChip.Icon.NONE) "" else " with-icon"
                sb.append("<span class=\"$cls$withIcon\"$tint>")
                    .append(iconSvg(chip.icon))
                    .append(htmlEscape(chip.text)).append("</span>")
            }
            sb.append("</div>")
        }
        // The flat index of each group's first sense, counted over removed
        // senses too: the curation's identity names a row across every group.
        var groupBase = 0
        for (group in data.importedGroups) {
            val base = groupBase
            groupBase += group.senses.size
            val kept = group.senses.withIndex()
                .filter { importedCuration == null || base + it.index !in importedCuration.removed }
            // Every sense removed: the group drops out, as it does from the
            // card ([WordCardDefinition.importedGroups]).
            if (group.senses.isNotEmpty() && kept.isEmpty()) continue
            sb.append("<section class=\"dict-group\">")
            // The dictionary's scope opens on its glossary alone: on the
            // section, its rules could reach the headers, a flat row and the
            // curation's wrapper and ✕ (see the file header).
            val scopeOpen = "<div class=\"gloss-scope\" data-dictionary=\"" + htmlEscape(group.dictId) + "\">"
            val headerColor = group.accentColor?.let { " style=\"color:${cssHex(it)}\"" }.orEmpty()
            var previousHeader: String? = null
            for ((i, sense) in kept) {
                // Consecutive senses sharing a header emit it once. Run over the kept
                // senses and outside the curation wrapper, so removing a
                // run's first row hands its header to the next.
                val header = importedHeader(group.source, sense.pos)
                if (header != previousHeader) {
                    sb.append("<div class=\"pos-h\"$headerColor>")
                        .append(htmlEscape(header)).append("</div>")
                    previousHeader = header
                }
                val body = sense.scRowid?.let { structured[it] }
                    ?.let { YomitanContentHtml.glossaryHtml(it, group.dictId) }
                    ?.let { scopeOpen + it + "</div>" }
                    ?: ("<div class=\"def-row\"><div class=\"def-text\">" +
                        htmlEscape(sense.definition).replace("\n", "<br>") + "</div></div>")
                if (importedCuration == null) {
                    sb.append(body)
                } else {
                    appendCuratedSense(sb, base + i, body, importedCuration)
                }
            }
            sb.append("</section>")
        }
        val packSenses = data.senses.filter { !it.imported }
        var previousPos: List<String>? = null
        packSenses.forEachIndexed { i, sense ->
            if (sense.pos.isNotEmpty() && sense.pos != previousPos) {
                sb.append("<div class=\"pos-h\">")
                    .append(htmlEscape(localizePos(sense.pos))).append("</div>")
                previousPos = sense.pos
            }
            sb.append("<div class=\"def-row\">")
                .append("<div class=\"def-num\">").append(i + 1).append(".</div>")
                .append("<div class=\"def-text\">")
                .append(htmlEscape(sense.definition).replace("\n", "<br>"))
            if (showMisc) {
                renderMisc(sense.misc)?.let {
                    sb.append("<div class=\"def-misc\">").append(htmlEscape(it)).append("</div>")
                }
            }
            sb.append("</div></div>")
        }
        return sb.toString()
    }

    /**
     * One kept imported sense under [curation]: [body] (its glossary or flat
     * row, unchanged) in `.imp-body`, then the ✕ when removal is allowed.
     * The ✕ sits OUTSIDE the body, so the shell's listener tells the two
     * apart by `closest` and a tap on the definition is never a removal.
     */
    private fun appendCuratedSense(
        sb: StringBuilder,
        flatIndex: Int,
        body: String,
        curation: ImportedCuration,
    ) {
        sb.append("<div class=\"imp-sense\" data-imp=\"").append(flatIndex)
            .append("\"><div class=\"imp-body\">").append(body).append("</div>")
        if (curation.removable) {
            // `data-act` is what the page reports the tap by
            // ([DefAction.removeItem]): the sense's ORIGINAL flat index.
            sb.append("<button type=\"button\" class=\"imp-x\" data-act=\"")
                .append(DefAction.removeItem(flatIndex)).append("\" aria-label=\"")
                .append(htmlEscape(curation.removeLabel)).append("\">✕</button>")
        }
        sb.append("</div>")
    }

    /**
     * [icon]'s markup: an INLINE `<svg>`, not an `<img>`. The shell's CSP
     * is `img-src ${YomitanContentHtml.MEDIA_ORIGIN}` — no `data:` — so a
     * rasterized drawable would be blocked outright and a broken chip is
     * exactly what an icon-less one already looks like. Inline markup also
     * takes the chip's own colour through `currentColor`, which is how the
     * native pill's icon gets tinted to the same ink.
     *
     * `aria-hidden`: the glyph is decorative, and the chip's visible text
     * already names the deck (the native pill's contentDescription has no
     * dependable span-level counterpart — see [AnkiDeckBadge.metaChip]).
     */
    private fun iconSvg(icon: MetaChip.Icon): String = when (icon) {
        MetaChip.Icon.NONE -> ""
        MetaChip.Icon.CARD_STACK ->
            "<svg class=\"chip-icon\" viewBox=\"0 -960 960 960\" aria-hidden=\"true\">" +
                "<path fill=\"currentColor\" d=\"$CARD_STACK_PATH\"/></svg>"
    }

    /**
     * The `cards_stack` glyph, in the Material source SVG's own negative-Y
     * viewBox — the very geometry `R.drawable.ic_card_stack` carries, which
     * only has to park it in a translated group because a vector drawable's
     * viewport must be positive-Y and an SVG's need not be.
     *
     * Copied rather than shared because nothing can hand one path to both
     * `res/` and a page string at build time; `DefinitionsDocumentIconTest`
     * fails the build if the drawable and this constant ever drift.
     */
    internal const val CARD_STACK_PATH =
        "M130,-189 81,-546q-5,-32 15.5,-58t52.5,-31l61,435 283,-40h267q-8,21 -24.5,35.5T695,-187l-477,66q-33,5 -58,-15t-30,-53Zm190,-127q-33,0 -56.5,-23.5T240,-396v-364q0,-33 23.5,-56.5T320,-840h480q33,0 56.5,23.5T880,-760v364q0,33 -23.5,56.5T800,-316H320Zm0,-80h480v-364H320v364Zm0,0v-364 364ZM210,-200Zm190,-400h320v-80H400v80Zm0,120h200v-80H400v80Z"

    /** Whether [data] warrants the styled path at all: at least one
     *  imported sense with a fetchable structured glossary. */
    fun hasStructuredContent(data: WordDefinitionData): Boolean =
        data.importedGroups.any { g -> g.senses.any { it.scRowid != null } }

    /** Rowids the content needs fetched ([YomitanDataStore.structuredGlossaries]). */
    fun structuredRowids(data: WordDefinitionData): List<Long> =
        data.importedGroups.flatMap { g -> g.senses.mapNotNull { it.scRowid } }

    /** Group label · pos — mirrored from [SenseDisplays.importedHeader] for
     *  the group-structured render path. */
    private fun importedHeader(source: String, pos: String): String =
        if (pos.isBlank()) source else "$source · $pos"
}
