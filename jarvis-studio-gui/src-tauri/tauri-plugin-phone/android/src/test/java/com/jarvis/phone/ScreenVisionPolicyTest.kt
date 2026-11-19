package com.jarvis.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenVisionPolicyTest {
    @Test
    fun normalizesAndClampsScreenshotBounds() {
        assertEquals(VisionBox(100, 200, 300, 200),
            ScreenVisionPolicy.normalizeBounds(100, 200, 400, 400, 1000, 1000))
        assertEquals(VisionBox(0, 0, 1000, 1000),
            ScreenVisionPolicy.normalizeBounds(-20, -10, 1200, 1400, 1000, 1000))
        assertEquals(VisionBox(0, 0, 0, 0),
            ScreenVisionPolicy.normalizeBounds(1, 1, 2, 2, 0, 100))
    }

    @Test
    fun masksCredentialLabelsAndEmailAddresses() {
        assertEquals("password: [REDACTED]", ScreenVisionPolicy.sanitizeLine("Password: hunter2"))
        assertEquals("otp: [REDACTED]", ScreenVisionPolicy.sanitizeLine("Your OTP is 482913"))
        assertEquals("Contact [REDACTED EMAIL]", ScreenVisionPolicy.sanitizeLine("Contact user@example.com"))
        assertEquals("Bank balance 125", ScreenVisionPolicy.sanitizeLine("Bank balance 125"))
        assertNull(ScreenVisionPolicy.sanitizeLine("   "))
    }

    @Test
    fun formattedOutputUsesNormalizedBoxesAndSkipsValueAfterCredentialHeading() {
        val rows = listOf(
            VisionBox(10, 20, 100, 30) to "Password",
            VisionBox(10, 60, 100, 30) to "hunter2",
            VisionBox(30, 80, 100, 30) to "Inventory",
        )
        val output = ScreenVisionPolicy.formatLines(rows)
        assertTrue(output.contains("[x=10 y=20 w=100 h=30]"))
        assertTrue(output.contains("password: [REDACTED]"))
        assertTrue(!output.contains("hunter2"))
        assertTrue(output.contains("Inventory"))
    }

    @Test
    fun capsOutputAndEscapesQuotes() {
        val output = ScreenVisionPolicy.formatLines(
            listOf(VisionBox(0, 0, 10, 10) to "A \"quoted\" label"),
            maxChars = 100,
        )
        assertTrue(output.contains("\\\"quoted\\\""))
        assertTrue(ScreenVisionPolicy.formatLines(
            listOf(VisionBox(0, 0, 10, 10) to "long line"),
            maxChars = 2,
        ).isEmpty())
    }
}
