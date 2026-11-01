package com.nik.voiceassistant.tools

import org.junit.Assert.*
import org.junit.Test

class ElementVerifierTest {

    @Test
    fun `exact element text match returns yes`() {
        val result = ElementVerifier.check(
            expect = "Settings screen visible",
            elements = listOf("[1] TextView \"Settings\"", "[2] Button \"Back\""),
            app = "com.android.settings"
        )
        assertNotNull(result)
        assertEquals("yes", result!!.verified)
        assertEquals("elements", result.via)
    }

    @Test
    fun `no matching elements returns null`() {
        val result = ElementVerifier.check(
            expect = "WhatsApp chat open",
            elements = listOf("[1] Button \"Home\""),
            app = "com.android.launcher"
        )
        assertNull(result)
    }

    @Test
    fun `empty elements list returns null`() {
        val result = ElementVerifier.check("anything visible", emptyList(), "com.test")
        assertNull(result)
    }

    @Test
    fun `error toast text in elements returns no with error_toast blocker`() {
        val result = ElementVerifier.check(
            expect = "Message sent",
            elements = listOf("[1] TextView \"Failed to send\"", "[2] Button \"Retry\""),
            app = "com.whatsapp"
        )
        assertNotNull(result)
        assertEquals("no", result!!.verified)
        assertEquals("error_toast", result.blocker)
    }

    @Test
    fun `partial keyword match with single word returns null (inconclusive)`() {
        val result = ElementVerifier.check(
            expect = "YouTube playing video",
            elements = listOf("[1] Button \"Share\""),
            app = "com.google.android.youtube"
        )
        // Only "Share" matches nothing in expect; should be null
        assertNull(result)
    }

    @Test
    fun `multiple keyword matches return yes`() {
        val result = ElementVerifier.check(
            expect = "WhatsApp message sent to Nikhil",
            elements = listOf(
                "[1] TextView \"Nikhil\"",
                "[2] ImageView (sent checkmarks)",
                "[3] EditText \"Type a message\"",
                "[4] TextView \"message\""
            ),
            app = "com.whatsapp"
        )
        assertNotNull(result)
        assertEquals("yes", result!!.verified)
    }

    @Test
    fun `VerifyResult has correct defaults`() {
        val vr = VerifyResult(
            verified = "yes",
            via = "elements",
            evidence = "Settings is visible",
            blocker = null,
            stepDone = true,
            nextAction = null,
            elementRefs = emptyList()
        )
        assertEquals("yes", vr.verified)
        assertEquals("elements", vr.via)
        assertNull(vr.blocker)
        assertTrue(vr.stepDone)
        assertNull(vr.nextAction)
    }
}
