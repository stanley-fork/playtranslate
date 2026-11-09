package com.playtranslate.ui

import android.content.res.Resources
import android.content.res.XmlResourceParser
import androidx.annotation.DrawableRes
import org.xmlpull.v1.XmlPullParser

/**
 * A vector drawable as inline SVG markup, for a definitions document's row
 * icons. The drawable stays the one source of the glyph: the page gets what
 * the native views draw, read from the same resource, with no path data
 * copied into Kotlin to drift.
 *
 * Covers what the app's icon drawables use: `path` (filled, or stroked when
 * it declares a stroke colour), a `group`'s translation, and `clip-path`,
 * which like the platform clips the siblings that follow it in its group.
 * Every colour becomes `currentColor`, so the page tints an icon with CSS
 * `color` as a native view does with a colour filter. Inline, never `<img>`:
 * the document's CSP allows images from the media origin only.
 */
internal object VectorDrawableSvg {

    private const val ANDROID = "http://schemas.android.com/apk/res/android"

    /** resId to everything after `<svg ` (the class is the caller's). */
    private val cache = HashMap<Int, String>()

    /** [id] as an `<svg class="[cssClass]">` element. */
    fun svg(res: Resources, @DrawableRes id: Int, cssClass: String): String {
        val rest = synchronized(cache) { cache.getOrPut(id) { convert(res, id) } }
        return "<svg class=\"$cssClass\" $rest"
    }

    private fun convert(res: Resources, id: Int): String {
        val sb = StringBuilder()
        // One entry per open drawable group: how many SVG groups it opened
        // (itself, plus one per clip-path met inside it).
        val open = ArrayDeque<Int>()
        var clips = 0
        res.getXml(id).use { xml ->
            var event = xml.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (xml.name) {
                        "vector" -> {
                            val w = xml.getAttributeFloatValue(ANDROID, "viewportWidth", 24f)
                            val h = xml.getAttributeFloatValue(ANDROID, "viewportHeight", 24f)
                            sb.append("viewBox=\"0 0 ").append(num(w)).append(' ').append(num(h))
                                .append("\" aria-hidden=\"true\">")
                            open.addLast(0)
                        }
                        "group" -> {
                            val tx = xml.getAttributeFloatValue(ANDROID, "translateX", 0f)
                            val ty = xml.getAttributeFloatValue(ANDROID, "translateY", 0f)
                            sb.append("<g")
                            if (tx != 0f || ty != 0f) {
                                sb.append(" transform=\"translate(").append(num(tx)).append(' ')
                                    .append(num(ty)).append(")\"")
                            }
                            sb.append('>')
                            open.addLast(1)
                        }
                        "clip-path" -> {
                            val clipId = "vd${id}c${clips++}"
                            sb.append("<clipPath id=\"").append(clipId).append("\"><path d=\"")
                                .append(xml.getAttributeValue(ANDROID, "pathData").orEmpty())
                                .append("\"/></clipPath><g clip-path=\"url(#").append(clipId).append(")\">")
                            open.addLast(open.removeLast() + 1)
                        }
                        "path" -> appendPath(sb, xml)
                    }
                } else if (event == XmlPullParser.END_TAG && (xml.name == "group" || xml.name == "vector")) {
                    repeat(open.removeLast()) { sb.append("</g>") }
                }
                event = xml.next()
            }
        }
        return sb.append("</svg>").toString()
    }

    private fun appendPath(sb: StringBuilder, xml: XmlResourceParser) {
        sb.append("<path d=\"").append(xml.getAttributeValue(ANDROID, "pathData").orEmpty()).append('"')
        val stroked = xml.getAttributeValue(ANDROID, "strokeColor") != null
        val filled = xml.getAttributeValue(ANDROID, "fillColor") != null
        sb.append(if (filled || !stroked) " fill=\"currentColor\"" else " fill=\"none\"")
        if (xml.getAttributeValue(ANDROID, "fillType") == "1") sb.append(" fill-rule=\"evenodd\"")
        if (stroked) {
            sb.append(" stroke=\"currentColor\" stroke-width=\"")
                .append(num(xml.getAttributeFloatValue(ANDROID, "strokeWidth", 0f)))
                .append('"')
            when (xml.getAttributeValue(ANDROID, "strokeLineCap")) {
                "1" -> sb.append(" stroke-linecap=\"round\"")
                "2" -> sb.append(" stroke-linecap=\"square\"")
            }
            when (xml.getAttributeValue(ANDROID, "strokeLineJoin")) {
                "1" -> sb.append(" stroke-linejoin=\"round\"")
                "2" -> sb.append(" stroke-linejoin=\"bevel\"")
            }
        }
        sb.append("/>")
    }

    /** A float without a trailing `.0`, in the root locale's digits. */
    private fun num(v: Float): String =
        if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()
}
