package com.nik.voiceassistant.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactorTest {

    @Test
    fun masksOtps() {
        val out = Redactor.mask("Your OTP is 482913. Do not share it.")
        assertFalse(out.contains("482913"))
        assertTrue(out.startsWith("Your OTP is "))
        assertFalse(Redactor.mask("123456 is your OTP for login").contains("123456"))
        assertFalse(Redactor.mask("Login code: 7781").contains("7781"))
    }

    @Test
    fun masksCardsKeepingLastFour() {
        val out = Redactor.mask("Card 4111 1111 1111 1234 charged")
        assertFalse(out.contains("4111 1111"))
        assertTrue(out.contains("1234"))
    }

    @Test
    fun leavesOrdinaryTextAlone() {
        listOf("Meeting at 10:30", "Call +91 98765 43210", "Year 2024 report", "Build number 7", "hello").forEach {
            assertEquals(it, Redactor.mask(it))
        }
    }

    @Test
    fun argsNeverKeepAPinOrPasswordText() {
        val args = Redactor.args(mapOf("pin" to "1234", "text" to "hunter2", "element_id" to 3), sensitive = true)
        assertEquals("***", args["pin"])
        assertFalse(args["text"].toString().contains("hunter2"))
        assertEquals(3, args["element_id"])
    }

    @Test
    fun modelTextIsMaskedOnlyWhenTurnedOn() {
        // Default: off, the agent can read an OTP to fill it in
        assertEquals("OTP 482913", Redactor.forModel("OTP 482913"))
    }
}
