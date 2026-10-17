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
import java.util.Calendar

/**
 * Arms one exact alarm per enabled macro with a Scheduled trigger (a time of day, optionally
 * restricted to specific weekdays via Trigger.Scheduled.days, Calendar.DAY_OF_WEEK values
 * 1=Sunday..7=Saturday). An empty day set means "once": ScheduledMacroReceiver disables the macro
 * after it fires instead of re-arming it, so a one-off follow-up (schedule_followup) doesn't keep
 * re-asking every day at that time.
 */
object ScheduledTriggerMonitor {
    private const val TAG = "ScheduledTrigger"
    const val ACTION_FIRE = "com.nik.voiceassistant.action.SCHEDULED_MACRO"
    const val EXTRA_MACRO_ID = "macro_id"

    /** Re-arms every enabled Scheduled-trigger macro; call after boot and on app start. */
    fun rescheduleAll(context: Context) {
        val app = context.applicationContext
        try {
            MacroStore.getAll(app).filter { it.enabled }.forEach { scheduleOne(app, it) }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't reschedule macros: ${e.javaClass.simpleName}")
        }
    }

    /** Arms (or re-arms) this one macro's next occurrence; a no-op if it has no Scheduled trigger. */
    fun scheduleOne(context: Context, macro: Macro) {
        val trig = macro.triggers.firstOrNull { it is Trigger.Scheduled } as? Trigger.Scheduled ?: return
        val app = context.applicationContext
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        val next = nextOccurrence(trig)
        val pi = pendingIntent(app, macro.id)
        try {
            val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.timeInMillis, pi)
            } else {
                // No SCHEDULE_EXACT_ALARM grant: still fires, just not to-the-minute (Doze may delay it)
                am.set(AlarmManager.RTC_WAKEUP, next.timeInMillis, pi)
            }
            Log.i(TAG, "Armed '${macro.title}' for ${next.time}")
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't arm alarm for macro ${macro.id}: ${e.javaClass.simpleName}")
        }
    }

    /** Cancels this macro's alarm (call on delete, or when it's turned off). */
    fun cancel(context: Context, macroId: String) {
        val app = context.applicationContext
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        try {
            am.cancel(pendingIntent(app, macroId))
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't cancel alarm for macro $macroId: ${e.javaClass.simpleName}")
        }
    }

    private fun pendingIntent(context: Context, macroId: String): PendingIntent {
        val intent = Intent(context, ScheduledMacroReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra(EXTRA_MACRO_ID, macroId)
        }
        return PendingIntent.getBroadcast(
            context, macroId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** The next moment (today or up to a week out) this trigger's hour:minute (and day, if given) occurs. */
    private fun nextOccurrence(trig: Trigger.Scheduled): Calendar {
        val now = Calendar.getInstance()
        for (addDays in 0..7) {
            val candidate = (now.clone() as Calendar).apply {
                add(Calendar.DAY_OF_YEAR, addDays)
                set(Calendar.HOUR_OF_DAY, trig.hour)
                set(Calendar.MINUTE, trig.minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (candidate.timeInMillis <= now.timeInMillis) continue
            if (trig.days.isEmpty() || candidate.get(Calendar.DAY_OF_WEEK) in trig.days) return candidate
        }
        // Every matching day this week was already in the past somehow: fall back to tomorrow, same time
        return (now.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, trig.hour)
            set(Calendar.MINUTE, trig.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }
}
