package com.nik.voiceassistant.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.ContactsContract
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.nik.voiceassistant.VoiceSession
import com.nik.voiceassistant.data.macro.Macro
import com.nik.voiceassistant.data.macro.MacroDispatch
import com.nik.voiceassistant.data.macro.MacroEngine
import com.nik.voiceassistant.data.macro.Trigger

/**
 * Matches a ringing or missed call against saved call triggers. Incoming-call detection uses the
 * phone's own call-state callback; missed calls are read from the call log instead of inferred from
 * state timing, since that is what Android itself considers "missed" and gives the number reliably.
 *
 * Call *answering* (Action.HandleCall: the agent picks up and talks) isn't implemented here — this
 * class only detects and matches; onIncomingCallMacroMatched is where that would hook in.
 */
object CallTriggerMonitor {
    private const val TAG = "CallTriggerMonitor"

    /** A macro with an IncomingCall trigger and an Action.HandleCall action matched a ringing call. */
    @Volatile var onIncomingCallMacroMatched: ((macro: Macro, number: String?, contactName: String?) -> Unit)? = null

    @Volatile private var appContext: Context? = null
    @Volatile private var telephonyManager: TelephonyManager? = null
    @Volatile private var callback: Any? = null // TelephonyCallback or PhoneStateListener, kept to unregister
    @Volatile private var callLogObserver: ContentObserver? = null
    @Volatile private var lastMissedCallLogId: Long = -1
    private val main = Handler(Looper.getMainLooper())

    fun start(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        try {
            registerCallState(app)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't watch call state: ${e.javaClass.simpleName}")
        }
        try {
            registerCallLogWatch(app)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't watch the call log: ${e.javaClass.simpleName}")
        }
    }

    fun stop() {
        val app = appContext ?: return
        try {
            val tm = telephonyManager
            val cb = callback
            if (tm != null && cb != null) unregisterCallback(tm, cb)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't stop watching calls: ${e.javaClass.simpleName}")
        }
        callLogObserver?.let { runCatching { app.contentResolver.unregisterContentObserver(it) } }
        telephonyManager = null
        callback = null
        callLogObserver = null
        appContext = null
    }

    @Suppress("DEPRECATION")
    private fun unregisterCallback(tm: TelephonyManager, cb: Any) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && cb is TelephonyCallback) tm.unregisterTelephonyCallback(cb)
        else if (cb is PhoneStateListener) tm.listen(cb, PhoneStateListener.LISTEN_NONE)
    }

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    // ---- Ringing: matched against IncomingCall triggers ----

    private fun registerCallState(context: Context) {
        if (!granted(context, Manifest.permission.READ_PHONE_STATE)) return
        val tm = context.getSystemService(TelephonyManager::class.java) ?: return
        telephonyManager = tm
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) = onState(context, state, null)
            }
            tm.registerTelephonyCallback(context.mainExecutor, cb)
            callback = cb
        } else {
            @Suppress("DEPRECATION")
            val listener = object : PhoneStateListener() {
                @Suppress("DEPRECATION")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) = onState(context, state, phoneNumber)
            }
            @Suppress("DEPRECATION")
            tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
            callback = listener
        }
    }

    private fun onState(context: Context, state: Int, phoneNumber: String?) {
        // API 29+ doesn't hand the number to this callback without READ_CALL_LOG / being the default
        // dialer; a blank number still matches a trigger with no number filter ("any call")
        if (state != TelephonyManager.CALL_STATE_RINGING) return
        main.post {
            try {
                matchIncoming(context, phoneNumber?.takeIf { it.isNotBlank() })
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't match the incoming call: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun matchIncoming(context: Context, number: String?) {
        val contactName = number?.let { lookupContact(context, it) }
        val matches = MacroEngine.matching(context) { t -> t is Trigger.IncomingCall && callTriggerMatches(t.contactFilter, number, contactName) }
        if (matches.isEmpty()) return
        val executor = VoiceSession.get(context).getToolExecutor()
        for (macro in matches) {
            if (macro.action is com.nik.voiceassistant.data.macro.Action.HandleCall) onIncomingCallMacroMatched?.invoke(macro, number, contactName)
            else MacroDispatch.run(context, macro, executor)
        }
    }

    // ---- Missed calls: from the call log, which is what decides "missed" reliably ----

    private fun registerCallLogWatch(context: Context) {
        if (!granted(context, Manifest.permission.READ_CALL_LOG)) return
        lastMissedCallLogId = latestCallLogId(context)
        val observer = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) {
                try {
                    checkForNewMissedCall(context)
                } catch (e: Exception) {
                    Log.w(TAG, "Couldn't read the call log: ${e.javaClass.simpleName}")
                }
            }
        }
        context.contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, observer)
        callLogObserver = observer
    }

    private fun latestCallLogId(context: Context): Long {
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls._ID), null, null, "${CallLog.Calls._ID} DESC LIMIT 1"
        )?.use { c -> if (c.moveToFirst()) return c.getLong(0) }
        return -1
    }

    private fun checkForNewMissedCall(context: Context) {
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.TYPE),
            "${CallLog.Calls._ID} > ?", arrayOf(lastMissedCallLogId.toString()),
            "${CallLog.Calls._ID} DESC"
        )?.use { c ->
            var newest = lastMissedCallLogId
            while (c.moveToNext()) {
                val id = c.getLong(0)
                newest = maxOf(newest, id)
                if (c.getInt(2) == CallLog.Calls.MISSED_TYPE) {
                    val number = c.getString(1)?.takeIf { it.isNotBlank() }
                    val contactName = number?.let { lookupContact(context, it) }
                    val matches = MacroEngine.matching(context) { t -> t is Trigger.MissedCall && callTriggerMatches(t.contactFilter, number, contactName) }
                    if (matches.isNotEmpty()) {
                        val executor = VoiceSession.get(context).getToolExecutor()
                        for (macro in matches) MacroDispatch.run(context, macro, executor)
                    }
                }
            }
            lastMissedCallLogId = newest
        }
    }

    // ---- Shared ----

    /** filter null/blank = any caller; "unknown" = no contact match; otherwise compared as digits, or against the name. */
    private fun callTriggerMatches(filter: String?, number: String?, contactName: String?): Boolean {
        if (filter.isNullOrBlank()) return true
        if (filter.equals("unknown", ignoreCase = true)) return number != null && contactName == null
        val digits = filter.filter { it.isDigit() }
        if (digits.isNotEmpty() && number != null && number.filter { it.isDigit() }.endsWith(digits.takeLast(10))) return true
        return contactName?.contains(filter, ignoreCase = true) == true
    }

    private fun lookupContact(context: Context, number: String): String? {
        if (!granted(context, Manifest.permission.READ_CONTACTS)) return null
        return try {
            val uri = android.net.Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, android.net.Uri.encode(number))
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
    }
}
