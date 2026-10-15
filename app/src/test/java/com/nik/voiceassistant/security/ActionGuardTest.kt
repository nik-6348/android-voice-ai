package com.nik.voiceassistant.security

import com.nik.voiceassistant.security.ActionGuard.Risk
import com.nik.voiceassistant.security.AgentPolicy.ConfirmLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionGuardTest {

    @Test
    fun paymentButtons() {
        listOf("Pay now", "PAY ₹500", "Pay 499", "Pay", "Proceed to pay", "Place order", "Buy now", "Confirm payment", "भुगतान करें")
            .forEach { assertEquals(it, Risk.PAYMENT, ActionGuard.classifyTap(it)) }
    }

    @Test
    fun ordinaryButtonsAreNotRisky() {
        listOf("Google Pay", "Pay bills", "Search", "Settings", "Call log", "Calls", "Posts", "Share", "Remove filter", "Back", "Subscribe")
            .forEach { assertNull(it, ActionGuard.classifyTap(it)) }
    }

    @Test
    fun sendCallDeleteWipe() {
        assertEquals(Risk.SEND, ActionGuard.classifyTap("Send"))
        assertEquals(Risk.SEND, ActionGuard.classifyTap(null, "com.whatsapp:id/send"))
        assertEquals(Risk.CALL, ActionGuard.classifyTap("Voice call"))
        assertEquals(Risk.CALL, ActionGuard.classifyTap("Call"))
        assertEquals(Risk.DELETE, ActionGuard.classifyTap("Delete for everyone"))
        assertEquals(Risk.WIPE, ActionGuard.classifyTap("Factory data reset"))
        // A payment wins over send
        assertEquals(Risk.PAYMENT, ActionGuard.classifyTap("Send money"))
    }

    @Test
    fun dialCodes() {
        assertTrue(ActionGuard.isDialCode("*21*9876543210#"))
        assertTrue(ActionGuard.isDialCode("*123#"))
        assertFalse(ActionGuard.isDialCode("+919876543210"))
        assertFalse(ActionGuard.isDialCode(null))
    }

    @Test
    fun paymentsAndWipesAlwaysAsk() {
        for (level in ConfirmLevel.values()) {
            assertTrue(ActionGuard.needsYes(Risk.PAYMENT, level, "pay karo", trusted = true))
            assertTrue(ActionGuard.needsYes(Risk.WIPE, level, "reset karo"))
            assertTrue(ActionGuard.needsYes(Risk.DIAL_CODE, level, "call karo"))
        }
    }

    @Test
    fun balancedAsksOnlyWhenTheUserDidNotAsk() {
        assertFalse(ActionGuard.needsYes(Risk.CALL, ConfirmLevel.BALANCED, "Rahul ko call lagao"))
        assertFalse(ActionGuard.needsYes(Risk.CALL, ConfirmLevel.BALANCED, "मम्मी को फोन करो"))
        assertTrue(ActionGuard.needsYes(Risk.CALL, ConfirmLevel.BALANCED, "YouTube kholo"))
        assertFalse(ActionGuard.needsYes(Risk.SEND, ConfirmLevel.BALANCED, "WhatsApp pe hi bhej do"))
        assertTrue(ActionGuard.needsYes(Risk.SEND, ConfirmLevel.BALANCED, "screen padho"))
        assertFalse(ActionGuard.needsYes(Risk.DELETE, ConfirmLevel.BALANCED, "ye photo delete karo"))
        // An automation the user ran counts as asking
        assertFalse(ActionGuard.needsYes(Risk.SEND, ConfirmLevel.BALANCED, "", trusted = true))
    }

    @Test
    fun relaxedAndStrict() {
        assertFalse(ActionGuard.needsYes(Risk.CALL, ConfirmLevel.RELAXED, ""))
        assertFalse(ActionGuard.needsYes(Risk.SEND, ConfirmLevel.RELAXED, ""))
        assertTrue(ActionGuard.needsYes(Risk.CALL, ConfirmLevel.STRICT, "call karo"))
        assertTrue(ActionGuard.needsYes(Risk.SEND, ConfirmLevel.STRICT, "bhej do", trusted = true))
    }
}
