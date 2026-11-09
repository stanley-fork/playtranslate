package com.playtranslate.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [VectorDrawableSvg]: a row icon is the drawable itself, read at run time.
 * Pins the three shapes the icons use: a filled path, stroked paths, and a
 * clip inside a translated group.
 */
@RunWith(RobolectricTestRunner::class)
class VectorDrawableSvgTest {

    private val res = ApplicationProvider.getApplicationContext<Context>().resources

    @Test fun `a filled icon is its path in the current colour`() {
        val svg = VectorDrawableSvg.svg(res, R.drawable.ic_chevron_right, "ic")
        assertTrue(svg, svg.startsWith("<svg class=\"ic\" viewBox=\"0 0 24 24\" aria-hidden=\"true\">"))
        assertTrue(svg, svg.contains("<path d=\"M9.29,6.71a1,1 0,0 0,0 1.41L13.17,12"))
        assertTrue(svg, svg.contains("fill=\"currentColor\""))
        assertTrue(svg, svg.endsWith("</svg>"))
        assertEquals("no stroke on a filled path", -1, svg.indexOf("stroke="))
    }

    @Test fun `a stroked path keeps its width, cap and join and takes no fill`() {
        val svg = VectorDrawableSvg.svg(res, R.drawable.ic_lens_speak, "ic")
        // The speaker body is filled, the two waves are strokes.
        assertTrue(svg, svg.contains("<path d=\"M3,10 L7,10 L12,5 L12,19 L7,14 L3,14 Z\" fill=\"currentColor\"/>"))
        assertTrue(
            svg,
            svg.contains(
                "<path d=\"M16,8 a5,5 0 0 1 0,8\" fill=\"none\" stroke=\"currentColor\" " +
                    "stroke-width=\"2.2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/>",
            ),
        )
    }

    @Test fun `a clip-path clips what follows it in its group, and a group keeps its translation`() {
        val svg = VectorDrawableSvg.svg(res, R.drawable.ic_card_stack_add, "ic")
        assertTrue(svg, svg.contains("viewBox=\"0 0 960 960\""))
        val clip = svg.indexOf("<clipPath id=")
        val clipped = svg.indexOf("<g clip-path=\"url(#")
        val moved = svg.indexOf("<g transform=\"translate(0 960)\">")
        assertTrue(svg, clip in 0 until clipped && clipped < moved)
        // Every group that was opened is closed.
        assertEquals(svg, Regex("<g[ >]").findAll(svg).count(), Regex("</g>").findAll(svg).count())
    }

    @Test fun `the class is the caller's and the conversion is cached`() {
        val a = VectorDrawableSvg.svg(res, R.drawable.ic_visibility, "a")
        val b = VectorDrawableSvg.svg(res, R.drawable.ic_visibility, "b")
        assertEquals(a.removePrefix("<svg class=\"a\""), b.removePrefix("<svg class=\"b\""))
    }
}
