package com.nik.voiceassistant.tools

import org.junit.Assert.*
import org.junit.Test

class ScreenVerifierTest {

    @Test
    fun `isBlackImage returns true for all-zero bytes`() {
        // All-black JPEG-like byte array (simulated, not a real JPEG but tests the logic)
        val black = ByteArray(1000) { 0 }
        assertTrue(ScreenVerifier.isBlackImage(black))
    }

    @Test
    fun `isBlackImage returns false when less than 90 percent black`() {
        // 80% zeros + 20% non-zero → should NOT be black
        val mixed = ByteArray(1000) { if (it < 800) 0 else 1 }
        assertFalse(ScreenVerifier.isBlackImage(mixed))
    }

    @Test
    fun `isBlackImage returns true when exactly 90 percent black`() {
        // 900 zeros, 100 non-zero → 90% → should be detected as black
        val mostly = ByteArray(1000) { if (it < 900) 0 else 1 }
        assertTrue(ScreenVerifier.isBlackImage(mostly))
    }

    @Test
    fun `isBlackImage returns false for empty array`() {
        // Edge case: empty byte array should not crash
        assertFalse(ScreenVerifier.isBlackImage(ByteArray(0)))
    }

    @Test
    fun `isBlackImage returns true for single zero byte`() {
        // 100% black
        assertTrue(ScreenVerifier.isBlackImage(ByteArray(1) { 0 }))
    }

    @Test
    fun `isBlackImage returns false for mostly non-zero`() {
        // Only 50% zeros
        val half = ByteArray(100) { if (it < 50) 0 else 127 }
        assertFalse(ScreenVerifier.isBlackImage(half))
    }
}
