package com.nik.voiceassistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.nik.voiceassistant.data.macro.MacroDispatch
import com.nik.voiceassistant.data.macro.MacroStore
import com.nik.voiceassistant.data.macro.Trigger
import com.nik.voiceassistant.tools.DeviceToolExecutor

/**
 * A single BroadcastReceiver that covers every Trigger.BroadcastEvent macro — no new code needed
 * when a new event type is added. On start (or after any macro change) it reads all enabled macros,
 * collects the unique set of Android intent action strings they need, and registers dynamically.
 *
 * Extras matching: every key-value pair in Trigger.BroadcastEvent.extras must be present in the
 * received intent. Blank value means "key must exist"; non-blank value must equal getStringExtra().
 */
object GenericBroadcastMonitor {
    private const val TAG = "BroadcastMonitor"

    private var receiver: BroadcastReceiver? = null
    private var registeredActions: Set<String> = emptySet()

    fun start(context: Context, toolExecutor: () -> DeviceToolExecutor?) {
        refresh(context.applicationContext, toolExecutor)
    }

    /**
     * Call whenever macros change (add, delete, enable/disable) so the registered filter stays
     * in sync. Safe to call repeatedly — unregisters the old receiver first.
     */
    fun refresh(context: Context, toolExecutor: () -> DeviceToolExecutor?) {
        val app = context.applicationContext
        val actions = try {
            MacroStore.getAll(app)
                .filter { it.enabled }
                .flatMap { it.triggers }
                .filterIsInstance<Trigger.BroadcastEvent>()
                .map { it.action }
                .toSet()
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't read macros: ${e.javaClass.simpleName}")
            return
        }

        // Nothing changed — don't re-register unnecessarily
        if (actions == registeredActions && receiver != null) return

        // Unregister old
        receiver?.let { runCatching { app.unregisterReceiver(it) } }
        receiver = null
        registeredActions = emptySet()

        if (actions.isEmpty()) return

        val filter = IntentFilter().also { f -> actions.forEach { f.addAction(it) } }
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent?) {
                onBroadcast(ctx.applicationContext, intent, toolExecutor)
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                app.registerReceiver(r, filter)
            }
            receiver = r
            registeredActions = actions
            Log.i(TAG, "Listening for ${actions.size} broadcast action(s): $actions")
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't register receiver: ${e.javaClass.simpleName}")
        }
    }

    fun stop(context: Context) {
        receiver?.let { runCatching { context.applicationContext.unregisterReceiver(it) } }
        receiver = null
        registeredActions = emptySet()
    }

    private fun onBroadcast(context: Context, intent: Intent?, toolExecutor: () -> DeviceToolExecutor?) {
        val action = intent?.action ?: return
        Log.i(TAG, "Broadcast received: $action")

        val macros = try { MacroStore.getAll(context) } catch (e: Exception) { return }
        val executor = toolExecutor()

        macros.filter { macro ->
            macro.enabled && macro.triggers.any { trig ->
                trig is Trigger.BroadcastEvent &&
                    trig.action == action &&
                    extrasMatch(intent, trig.extras)
            }
        }.forEach { macro ->
            Log.i(TAG, "Broadcast matched macro '${macro.title}'")
            MacroDispatch.run(context, macro, executor ?: return@forEach)
        }
    }

    private fun extrasMatch(intent: Intent, extras: Map<String, String>): Boolean {
        if (extras.isEmpty()) return true
        return extras.all { (key, value) ->
            if (value.isBlank()) intent.hasExtra(key)
            else intent.getStringExtra(key) == value
        }
    }
}
