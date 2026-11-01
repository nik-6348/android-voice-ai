package com.nik.voiceassistant.tools

import org.junit.Assert.*
import org.junit.Test

class PlanEngineTest {
    private val engine = PlanEngine()

    @Test fun `start creates plan with first step RUNNING`() {
        engine.start("Open Settings", listOf(PlanStep("s1", "Tap Settings")))
        val plan = engine.plan.value!!
        assertEquals(StepStatus.RUNNING, plan.steps[0].status)
    }

    @Test fun `requestComplete on running step with yes verify marks DONE`() {
        engine.start("g", listOf(PlanStep("s1", "Step 1"), PlanStep("s2", "Step 2")))
        val vr = VerifyResult("yes", "elements", "Settings open", null, true, null, emptyList())
        val err = engine.requestComplete("s1", "Done", vr)
        assertNull(err)
        val plan = engine.plan.value!!
        assertEquals(StepStatus.DONE, plan.steps[0].status)
        assertEquals(StepStatus.RUNNING, plan.steps[1].status)
    }

    @Test fun `requestComplete on already done step returns error`() {
        engine.start("g", listOf(PlanStep("s1", "Step 1")))
        val vr = VerifyResult("yes", "elements", "ok", null, true, null, emptyList())
        engine.requestComplete("s1", "done", vr)
        val err = engine.requestComplete("s1", "done again", vr)
        assertNotNull(err)
        assertTrue(err!!.contains("already"))
    }

    @Test fun `replan preserves done steps`() {
        engine.start("g", listOf(PlanStep("s1", "Step 1"), PlanStep("s2", "Step 2")))
        val vr = VerifyResult("yes", "elements", "ok", null, true, null, emptyList())
        engine.requestComplete("s1", "done", vr)
        engine.replan(listOf(PlanStep("s3", "New step")))
        val plan = engine.plan.value!!
        assertEquals(StepStatus.DONE, plan.steps.first { it.id == "s1" }.status)
        assertTrue(plan.steps.any { it.id == "s3" })
    }

    @Test fun `events ring buffer keeps max 12`() {
        engine.start("g", listOf(PlanStep("s1", "Step 1")))
        repeat(20) { engine.onToolStart("s1", "tap") }
        assertTrue(engine.plan.value!!.events.size <= PlanEngine.MAX_EVENTS)
    }
}
