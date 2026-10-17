package com.nik.voiceassistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.nik.voiceassistant.VoiceSession
import com.nik.voiceassistant.data.macro.MacroDispatch
import com.nik.voiceassistant.data.macro.MacroStore
import com.nik.voiceassistant.data.macro.Trigger

/**
 * Fires when a Condition-trigger macro's polling alarm is due. Wakes the service, then sends the
 * macro's checkInstruction to the AI as a special internal turn. The AI uses its tools to evaluate
 * the condition and calls report_condition(macroId, met) — that tool (in DeviceToolExecutor) decides
 * whether to fire the action and re-arm or disable the alarm.
 */
class ConditionPollingReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "ConditionPolling"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val macroId = intent?.getStringExtra(ConditionPollingEngine.EXTRA_MACRO_ID) ?: return
        val app = context.applicationContext

        val macro = MacroStore.getAll(app).firstOrNull { it.id == macroId && it.enabled }
        if (macro == null) {
            Log.i(TAG, "Condition macro $macroId not found or disabled; skipping")
            return
        }
        val trig = macro.triggers.firstOrNull { it is Trigger.Condition } as? Trigger.Condition ?: return

        Log.i(TAG, "Condition check due for '${macro.title}' — asking AI")

        // Wake the service so VoiceSession exists and onAgentGoalRequested is wired
        try {
            if (PhoneAgentService.isEnabled(app) && PhoneAgentService.instance != null) {
                PhoneAgentService.instance?.bringUpListening()
            } else {
                VoiceAssistantService.startListening(app)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't wake service: ${e.javaClass.simpleName}")
        }

        // Re-arm now so the next check is scheduled even if this one crashes
        ConditionPollingEngine.scheduleOne(app, macro)

        // Send the condition check instruction to the AI as an internal, silent turn
        val instruction = buildString {
            appendLine("(INTERNAL CONDITION CHECK — do NOT speak to the user, do NOT greet)")
            appendLine("Macro ID: $macroId")
            appendLine("Check: ${trig.checkInstruction}")
            appendLine()
            append("Use your tools to silently evaluate whether the above condition is currently true. ")
            append("When you have your answer, call report_condition(macro_id=\"$macroId\", met=true) ")
            append("if the condition IS met, or report_condition(macro_id=\"$macroId\", met=false) if not. ")
            append("Do not speak until after you call report_condition.")
        }

        MacroDispatch.onAgentGoalRequested?.invoke(instruction)
            ?: Log.w(TAG, "onAgentGoalRequested not set — is VoiceSession running?")
    }
}
