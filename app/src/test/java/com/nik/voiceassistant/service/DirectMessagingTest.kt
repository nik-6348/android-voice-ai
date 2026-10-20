package com.nik.voiceassistant.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DirectMessagingTest {

    @Test
    fun directSmsValidation() {
        assertEquals("Invalid phone number: ''", DirectSmsManager.validate("", "Hello"))
        assertEquals("Invalid phone number: '   '", DirectSmsManager.validate("   ", "Hello"))
        assertEquals("SMS message text cannot be empty", DirectSmsManager.validate("+919876543210", ""))
        assertEquals("SMS message text cannot be empty", DirectSmsManager.validate("+919876543210", "   "))
        assertNull(DirectSmsManager.validate("+919876543210", "Valid message"))
    }

    @Test
    fun directReplyValidation() {
        assertEquals("Reply message cannot be empty", DirectReplyManager.validate(""))
        assertEquals("Reply message cannot be empty", DirectReplyManager.validate("   "))
        assertNull(DirectReplyManager.validate("Valid reply"))
    }

    @Test
    fun listActiveReplyableInitiallyEmpty() {
        // DirectReplyManager should start with empty list or clean sessions
        val list = DirectReplyManager.listActiveReplyable()
        // Should not throw and returns a list
        assertEquals(0, list.size)
    }
}
