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
 * Fires when a Scheduled-trigger macro's alarm (armed by ScheduledTriggerMonitor) comes due. Wakes
 * the background service first (the process may have been killed since the alarm was armed, possibly
 * hours earlier), so VoiceSession exists and MacroDispatch.onAgentGoalRequested is wired before the
 * macro is dispatched.
 */
class ScheduledMacroReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "ScheduledTrigger"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val macroId = intent?.getStringExtra(ScheduledTriggerMonitor.EXTRA_MACRO_ID) ?: return
        val app = context.applicationContext
        Log.i(TAG, "Scheduled alarm fired for macro $macroId")

        try {
            if (PhoneAgentService.isEnabled(app) && PhoneAgentService.instance != null) {
                PhoneAgentService.instance?.bringUpListening()
            } else {
                VoiceAssistantService.startListening(app)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't wake the background service: ${e.javaClass.simpleName}")
        }

        val macro = MacroStore.getAll(app).firstOrNull { it.id == macroId && it.enabled }
        if (macro == null) {
            Log.i(TAG, "Macro $macroId was deleted or disabled since the alarm was armed; nothing to do")
            return
        }
        val trig = macro.triggers.firstOrNull { it is Trigger.Scheduled } as? Trigger.Scheduled

        MacroDispatch.run(app, macro, VoiceSession.get(app).getToolExecutor())

        when {
            trig == null -> {}
            trig.days.isEmpty() -> MacroStore.update(app, macroId) { it.copy(enabled = false) }
            else -> ScheduledTriggerMonitor.scheduleOne(app, macro)
        }
    }
}
