package com.nik.voiceassistant.tools

import com.google.gson.GsonBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskPlanTest {
    private val gson = GsonBuilder()
        .registerTypeAdapter(TaskPlan::class.java, TaskPlanDeserializer())
        .create()

    @Test fun `PlanStep defaults are correct`() {
        val step = PlanStep(id = "s1", title = "Open Settings")
        assertEquals(StepStatus.PENDING, step.status)
        assertNull(step.evidence)
        assertEquals("", step.detail)
    }

    @Test fun `currentStep computed from RUNNING step`() {
        val plan = TaskPlan(
            goal = "g",
            steps = listOf(
                PlanStep("s1", "Step 1", status = StepStatus.DONE),
                PlanStep("s2", "Step 2", status = StepStatus.RUNNING),
                PlanStep("s3", "Step 3", status = StepStatus.PENDING)
            ),
            status = "working"
        )
        assertEquals(2, plan.currentStep)
    }

    @Test fun `currentStep falls back to first PENDING when no RUNNING`() {
        val plan = TaskPlan(
            goal = "g",
            steps = listOf(
                PlanStep("s1", "Step 1", status = StepStatus.DONE),
                PlanStep("s2", "Step 2", status = StepStatus.PENDING)
            ),
            status = "working"
        )
        assertEquals(2, plan.currentStep)
    }
}
