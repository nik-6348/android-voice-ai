package com.nik.voiceassistant.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.nik.voiceassistant.data.macro.Macro
import com.nik.voiceassistant.data.macro.MacroStore
import com.nik.voiceassistant.data.macro.Trigger

/**
 * Arms repeating alarms for every enabled Trigger.Condition macro. When an alarm fires,
 * ConditionPollingReceiver wakes up, sends the checkInstruction to the AI, and the AI calls the
 * report_condition tool with met=true/false. If met, the macro's action instruction runs; if
 * stopOnMet=true the macro is disabled so it never fires again.
 */
object ConditionPollingEngine {
    private const val TAG = "ConditionPolling"
    const val ACTION_CHECK = "com.nik.voiceassistant.action.CONDITION_CHECK"
    const val EXTRA_MACRO_ID = "macro_id"

    fun rescheduleAll(context: Context) {
        val app = context.applicationContext
        try {
            MacroStore.getAll(app).filter { it.enabled }.forEach { scheduleOne(app, it) }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't reschedule condition polls: ${e.javaClass.simpleName}")
        }
    }

    fun scheduleOne(context: Context, macro: Macro) {
        val trig = macro.triggers.firstOrNull { it is Trigger.Condition } as? Trigger.Condition ?: return
        val app = context.applicationContext
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        val intervalMs = trig.intervalMinutes.coerceAtLeast(1).toLong() * 60_000L
        val firstAt = System.currentTimeMillis() + intervalMs
        val pi = pendingIntent(app, macro.id)
        try {
            val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, firstAt, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, firstAt, pi)
            }
            Log.i(TAG, "Condition check scheduled for '${macro.title}' in ${trig.intervalMinutes} min")
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't arm condition alarm for ${macro.id}: ${e.javaClass.simpleName}")
        }
    }

    fun cancel(context: Context, macroId: String) {
        val app = context.applicationContext
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        runCatching { am.cancel(pendingIntent(app, macroId)) }
    }

    private fun pendingIntent(context: Context, macroId: String): PendingIntent {
        val intent = Intent(ACTION_CHECK).apply {
            putExtra(EXTRA_MACRO_ID, macroId)
            setPackage(context.packageName)
        }
        return PendingIntent.getBroadcast(
            context, macroId.hashCode(),
            intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
