package com.nik.voiceassistant.tools

import com.google.gson.GsonBuilder
import org.junit.Assert.*
import org.junit.Test

class TaskPlanModelTest {

    private val gson = GsonBuilder()
        .registerTypeAdapter(TaskPlan::class.java, TaskPlanDeserializer())
        .create()

    @Test fun `new PlanStep format deserializes correctly`() {
        val json = """{"goal":"g","steps":[{"id":"s1","title":"Open Settings","status":"PENDING"}],"status":"working"}"""
        val plan = gson.fromJson(json, TaskPlan::class.java)
        assertEquals("s1", plan.steps[0].id)
        assertEquals(StepStatus.PENDING, plan.steps[0].status)
    }

    @Test fun `old string steps format deserializes with auto ids`() {
        val json = """{"goal":"g","steps":["Open Settings","Tap Wi-Fi"],"status":"working","currentStep":1}"""
        val plan = gson.fromJson(json, TaskPlan::class.java)
        assertEquals(2, plan.steps.size)
        assertEquals("s1", plan.steps[0].id)
        assertEquals("Open Settings", plan.steps[0].title)
        assertEquals(StepStatus.RUNNING, plan.steps[0].status)
    }

    @Test fun `new TaskPlan is additive over old shape`() {
        val plan = TaskPlan(goal = "g", steps = listOf(PlanStep("s1", "Step 1")), status = "working")
        assertEquals(1, plan.steps.size)
        assertNull(plan.steps[0].evidence)
    }

    @Test fun `StepStatus defaults to PENDING`() {
        val step = PlanStep(id = "s1", title = "Title")
        assertEquals(StepStatus.PENDING, step.status)
    }

    @Test fun `PlanEvent fields are set correctly`() {
        val event = PlanEvent(at = 1000L, kind = "TOOL", text = "tapped")
        assertEquals("TOOL", event.kind)
        assertEquals(1000L, event.at)
    }
}
