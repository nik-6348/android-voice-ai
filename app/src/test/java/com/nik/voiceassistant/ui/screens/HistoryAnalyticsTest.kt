package com.nik.voiceassistant.ui.screens

import com.nik.voiceassistant.TranscriptItem
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryAnalyticsTest {
    @Test fun `action count from tool messages`() {
        val msg1 = TranscriptItem(id="1", sender="tool", text="Executed open_app: WhatsApp")
        val msg2 = TranscriptItem(id="2", sender="tool", text="Executed tap: confirm")
        val msg3 = TranscriptItem(id="3", sender="user", text="hello")
        val count = countActions(listOf(msg1, msg2, msg3))
        assertEquals(2, count)
    }

    @Test fun `app open count distinct apps`() {
        val msg1 = TranscriptItem(id="1", sender="tool", text="Executed open_app: WhatsApp")
        val msg2 = TranscriptItem(id="2", sender="tool", text="Executed open_app: WhatsApp")
        val msg3 = TranscriptItem(id="3", sender="tool", text="Executed launch_app: YouTube")
        val count = countDistinctApps(listOf(msg1, msg2, msg3))
        assertEquals(2, count)
    }
}
