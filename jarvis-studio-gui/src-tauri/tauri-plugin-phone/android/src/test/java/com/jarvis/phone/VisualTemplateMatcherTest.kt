package com.jarvis.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualTemplateMatcherTest {
    private fun canvas(width: Int, height: Int, color: Int = 0xff202020.toInt()) =
        IntArray(width * height) { color }

    private fun drawPlus(pixels: IntArray, width: Int, left: Int, top: Int, size: Int, color: Int) {
        val thickness = maxOf(2, size / 5)
        for (y in top until top + size) for (x in left until left + size) {
            val center = size / 2
            if (kotlin.math.abs(x - left - center) < thickness ||
                kotlin.math.abs(y - top - center) < thickness
            ) pixels[y * width + x] = color
        }
    }

    @Test
    fun learnsAndFindsAUserDesignatedIconCrop() {
        val source = canvas(100, 100)
        drawPlus(source, 100, 30, 30, 20, 0xffeeeeee.toInt())
        val template = VisualTemplateMatcher.capture(
            source, 100, 100, VisionBox(300, 300, 200, 200),
            "inventory-plus", "Inventory plus", "com.example.game",
        )
        assertNotNull(template)

        val later = canvas(200, 200)
        // Same icon at twice the pixel size, at a different screen position.
        drawPlus(later, 200, 120, 70, 40, 0xffeeeeee.toInt())
        val match = VisualTemplateMatcher.find(
            later, 200, 200, template!!, "com.example.game", threshold = 0.96f, stride = 2,
        )
        assertNotNull(match)
        assertTrue(match!!.confidence >= 0.96f)
        assertTrue(match.bounds.x in 590..620)
        assertTrue(match.bounds.y in 340..360)
    }

    @Test
    fun refusesWrongAppAndLowConfidenceMatches() {
        val source = canvas(100, 100)
        drawPlus(source, 100, 30, 30, 20, 0xffffffff.toInt())
        val template = VisualTemplateMatcher.capture(
            source, 100, 100, VisionBox(300, 300, 200, 200),
            "plus", "Plus", "com.example.game",
        )!!
        val blank = canvas(200, 160)
        assertNull(VisualTemplateMatcher.find(blank, 200, 160, template, "com.other.app"))
        assertNull(VisualTemplateMatcher.find(blank, 200, 160, template, "com.example.game", threshold = 0.98f))
    }

    @Test
    fun rejectsBlankAndTinyTemplates() {
        val blank = canvas(100, 100)
        assertNull(VisualTemplateMatcher.capture(
            blank, 100, 100, VisionBox(100, 100, 300, 300), "blank", "Blank", "com.example.game",
        ))
        val textured = canvas(100, 100)
        drawPlus(textured, 100, 30, 30, 20, 0xffffffff.toInt())
        assertNull(VisualTemplateMatcher.capture(
            textured, 100, 100, VisionBox(300, 300, 10, 10), "tiny", "Tiny", "com.example.game",
        ))
    }

    @Test
    fun confidenceAndCoordinateResultsAreBounded() {
        val source = canvas(100, 100)
        drawPlus(source, 100, 30, 30, 20, 0xffffffff.toInt())
        val template = VisualTemplateMatcher.capture(
            source, 100, 100, VisionBox(300, 300, 200, 200), "plus", "Plus", "com.example.game",
        )!!
        val match = VisualTemplateMatcher.find(
            source, 100, 100, template, "com.example.game", threshold = 0.90f, stride = 2,
        )
        assertNotNull(match)
        assertTrue(match!!.bounds.x in 0..1000)
        assertTrue(match.bounds.y in 0..1000)
        assertTrue(match.bounds.w in 1..1000)
        assertTrue(match.bounds.h in 1..1000)
        assertFalse(match.confidence.isNaN())
    }
}
