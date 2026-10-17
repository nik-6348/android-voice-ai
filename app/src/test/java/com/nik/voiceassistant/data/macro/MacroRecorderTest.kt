package com.nik.voiceassistant.data.macro

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Earlier versions finished recording at the AI's first spoken line, before any tool had run, saving
 * an empty macro every time. These pin the fixed behaviour: recording runs until the plan is done.
 */
class MacroRecorderTest {

    @After
    fun cleanup() = MacroRecorder.cancel()

    @Test
    fun doesNotFinishOnAnUnrelatedPlanUpdate() {
        MacroRecorder.start("YouTube par lofi chalao")
        MacroRecorder.recordStep("open_app", mapOf("app_name" to "YouTube"), "opened")
        // update_plan with status "working" (still going) must not end the recording
        assertNull(MacroRecorder.note("working"))
        assertTrue(MacroRecorder.isRecording())
    }

    @Test
    fun finishesAndSavesOnDoneWithSteps() {
        MacroRecorder.start("YouTube par lofi chalao")
        MacroRecorder.recordStep("open_app", mapOf("app_name" to "YouTube"), "opened")
        MacroRecorder.recordStep("tap", mapOf("text" to "Search"), "tapped")
        val macro = MacroRecorder.note("done")
        requireNotNull(macro)
        assertFalse(MacroRecorder.isRecording())
        val steps = (macro.action as Action.Replay).steps
        assertEquals(2, steps.size)
        assertEquals("open_app", steps[0].toolName)
        assertEquals("YouTube par lofi chalao", macro.instruction)
        assertEquals("YouTube par lofi chalao", (macro.triggers.single() as Trigger.Phrase).phrase)
    }

    @Test
    fun nothingIsSavedIfDoneWithNoSteps() {
        MacroRecorder.start("kuch karo")
        // The AI only spoke, never called a tool (the exact scenario that used to produce an empty macro)
        assertNull(MacroRecorder.note("done"))
        assertFalse(MacroRecorder.isRecording())
    }

    @Test
    fun failedPlanDiscardsTheRecording() {
        MacroRecorder.start("kuch karo")
        MacroRecorder.recordStep("open_app", mapOf("app_name" to "X"), "opened")
        assertNull(MacroRecorder.note("failed"))
        assertFalse(MacroRecorder.isRecording())
    }

    @Test
    fun readScreenAndOtherInfoToolsAreNotRecordedAsSteps() {
        MacroRecorder.start("batao kya chal raha hai")
        MacroRecorder.recordStep("read_screen", emptyMap(), "screen")
        MacroRecorder.recordStep("open_app", mapOf("app_name" to "X"), "opened")
        MacroRecorder.recordStep("look_at_screen", emptyMap(), "looked")
        val macro = MacroRecorder.note("done")
        val steps = (macro?.action as Action.Replay).steps
        assertEquals(1, steps.size)
        assertEquals("open_app", steps[0].toolName)
    }

    @Test
    fun conversationEndingMidTaskStillSavesWhatRan() {
        MacroRecorder.start("kuch karo")
        MacroRecorder.recordStep("open_app", mapOf("app_name" to "X"), "opened")
        // No update_plan("done") ever arrived (e.g. the user said bye early); the service still saves progress
        val macro = MacroRecorder.finishOnConversationEnd()
        requireNotNull(macro)
        assertEquals(1, (macro.action as Action.Replay).steps.size)
    }

    @Test
    fun replacingIdIsCarriedThroughToTheFinishedMacro() {
        MacroRecorder.start("subah ka alarm", replacingId = "old-id-123")
        MacroRecorder.recordStep("launch_intent", mapOf("action" to "android.intent.action.SET_ALARM"), "set")
        val macro = MacroRecorder.note("done")
        assertEquals("old-id-123", macro?.id)
    }
}
