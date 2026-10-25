package com.nik.voiceassistant.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskPlanTest {
    @Test fun `new fields default to null`() {
        val plan = TaskPlan(goal = "g", steps = listOf("s1"), currentStep = 1, status = "working", note = null)
        assertNull(plan.stepTimings)
        assertNull(plan.stepDetails)
        assertNull(plan.reasoningLines)
    }

    @Test fun `stepTimings survives round-trip Gson`() {
        val plan = TaskPlan(
            goal = "g", steps = listOf("s1", "s2"), currentStep = 2, status = "working", note = null,
            stepTimings = mapOf(1 to 5000L)
        )
        val json = com.google.gson.Gson().toJson(plan)
        val back = com.google.gson.Gson().fromJson(json, TaskPlan::class.java)
        assertEquals(5000L, back.stepTimings?.get(1))
        assertNull(back.stepTimings?.get(2))
    }
}
