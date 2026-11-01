package com.nik.voiceassistant.tools

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PlanEngine {

    companion object {
        const val MAX_EVENTS = 12
    }

    private val _plan = MutableStateFlow<TaskPlan?>(null)
    val plan: StateFlow<TaskPlan?> = _plan.asStateFlow()

    fun start(goal: String, steps: List<PlanStep>): TaskPlan {
        val runningSteps = steps.mapIndexed { idx, s ->
            if (idx == 0) s.copy(status = StepStatus.RUNNING, startedAt = System.currentTimeMillis())
            else s.copy(status = StepStatus.PENDING)
        }
        val taskPlan = TaskPlan(goal = goal, steps = runningSteps, status = "working")
        _plan.value = taskPlan
        return taskPlan
    }

    fun replan(newSteps: List<PlanStep>, note: String? = null): TaskPlan {
        val current = _plan.value
        val doneSteps = current?.steps?.filter { it.status == StepStatus.DONE || it.status == StepStatus.FAILED || it.status == StepStatus.SKIPPED } ?: emptyList()
        val allSteps = doneSteps + newSteps.mapIndexed { idx, s ->
            if (idx == 0 && doneSteps.isEmpty() || idx == 0) {
                s.copy(status = StepStatus.RUNNING, startedAt = System.currentTimeMillis())
            } else {
                s.copy(status = StepStatus.PENDING)
            }
        }
        // Advance first new step to RUNNING if there are done steps before it
        val adjusted = if (doneSteps.isNotEmpty()) {
            allSteps.mapIndexed { idx, s ->
                if (s.status == StepStatus.PENDING && allSteps.take(idx).all { it.status == StepStatus.DONE || it.status == StepStatus.FAILED || it.status == StepStatus.SKIPPED }) {
                    s.copy(status = StepStatus.RUNNING, startedAt = System.currentTimeMillis())
                } else s
            }.let { steps ->
                // Only first PENDING after dones should be RUNNING
                var foundRunning = steps.any { it.status == StepStatus.DONE }
                steps.map { s ->
                    if (s.status == StepStatus.RUNNING && foundRunning) {
                        foundRunning = false; s
                    } else if (s.status == StepStatus.RUNNING && !foundRunning) {
                        foundRunning = true; s
                    } else s
                }
            }
        } else allSteps

        val updated = (current ?: TaskPlan(goal = "", steps = emptyList(), status = "working")).copy(
            steps = adjusted,
            revision = (current?.revision ?: 0) + 1,
            note = note ?: current?.note
        )
        _plan.value = updated
        return updated
    }

    fun onToolStart(stepId: String, toolName: String) {
        val current = _plan.value ?: return
        val event = PlanEvent(at = System.currentTimeMillis(), kind = "TOOL", text = "$toolName started")
        _plan.value = current.copy(events = ringAppend(current.events, event))
    }

    fun onToolResult(stepId: String, toolName: String, result: Map<String, Any?>) {
        val current = _plan.value ?: return
        val summary = result["summary"]?.toString() ?: result["result"]?.toString() ?: "done"
        val event = PlanEvent(at = System.currentTimeMillis(), kind = "TOOL", text = "$toolName → $summary".take(120))
        _plan.value = current.copy(events = ringAppend(current.events, event))
    }

    fun requestComplete(stepId: String, summary: String, verifyResult: VerifyResult?): String? {
        val current = _plan.value ?: return "no active plan"
        val stepIdx = current.steps.indexOfFirst { it.id == stepId }
        if (stepIdx < 0) return "step '$stepId' not found"
        val step = current.steps[stepIdx]
        if (step.status == StepStatus.DONE) return "step '$stepId' already done"
        if (step.status != StepStatus.RUNNING && step.status != StepStatus.VERIFYING) {
            return "step '$stepId' is not running (status=${step.status})"
        }
        if (verifyResult?.verified == "no") {
            return "verify failed: ${verifyResult.evidence}"
        }

        val now = System.currentTimeMillis()
        val updatedSteps = current.steps.toMutableList()
        updatedSteps[stepIdx] = step.copy(
            status = StepStatus.DONE,
            endedAt = now,
            evidence = summary
        )
        // Advance next PENDING step to RUNNING
        val nextIdx = updatedSteps.indexOfFirst { it.status == StepStatus.PENDING }
        if (nextIdx >= 0) {
            updatedSteps[nextIdx] = updatedSteps[nextIdx].copy(status = StepStatus.RUNNING, startedAt = now)
        }
        val event = PlanEvent(at = now, kind = "TOOL", text = "step $stepId done: $summary".take(120))
        _plan.value = current.copy(
            steps = updatedSteps,
            events = ringAppend(current.events, event)
        )
        return null
    }

    fun fail(stepId: String, reason: String) {
        val current = _plan.value ?: return
        val stepIdx = current.steps.indexOfFirst { it.id == stepId }
        if (stepIdx < 0) return
        val now = System.currentTimeMillis()
        val updatedSteps = current.steps.toMutableList()
        updatedSteps[stepIdx] = updatedSteps[stepIdx].copy(
            status = StepStatus.FAILED,
            endedAt = now,
            evidence = reason
        )
        val event = PlanEvent(at = now, kind = "BLOCKER", text = "step $stepId failed: $reason".take(120))
        _plan.value = current.copy(
            steps = updatedSteps,
            status = "blocked",
            events = ringAppend(current.events, event)
        )
    }

    fun finish(status: String) {
        val current = _plan.value ?: return
        _plan.value = current.copy(status = status)
    }

    fun reset() {
        _plan.value = null
    }

    private fun ringAppend(events: List<PlanEvent>, event: PlanEvent): List<PlanEvent> {
        val combined = events + event
        return if (combined.size > MAX_EVENTS) combined.drop(combined.size - MAX_EVENTS) else combined
    }
}
