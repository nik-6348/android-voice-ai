package com.nik.voiceassistant.ui.components

import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchCardTest {
    @Test fun `known search domains produce labeled sources`() {
        val sources = defaultResearchSources("flight booking")
        assertTrue(sources.any { it.contains("Google") })
    }

    @Test fun `empty query returns non-empty source list`() {
        val sources = defaultResearchSources("")
        assertTrue(sources.isNotEmpty())
    }
}
