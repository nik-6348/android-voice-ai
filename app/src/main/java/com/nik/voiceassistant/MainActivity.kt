package com.nik.voiceassistant

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.nik.voiceassistant.ui.VoiceAssistantScreen
import com.nik.voiceassistant.ui.theme.NikVoiceTheme

class MainActivity : ComponentActivity() {

    private val viewModel: VoiceAssistantViewModel by viewModels()

    // Start listening once permissions are in: only on a fresh launch with "Start when the app opens" on
    private var autoStart = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] == true
        if (audioGranted) {
            if (autoStart) viewModel.startSession()
        } else {
            Toast.makeText(this, "Microphone permission is required for voice assistant", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Not while a conversation is already running (rotation, dark mode, language change
        // recreate this Activity mid-call): only start one when none is active. Android can also
        // recreate the Activity with a saved state well after the user reopens it from the launcher
        // (the system trimmed it in the background), so savedInstanceState alone can't tell "rotated"
        // from "genuinely reopened" — isActive can.
        autoStart = !VoiceSession.get(this).isActive && com.nik.voiceassistant.security.AgentPolicy.current.autoStartOnOpen
        checkAndRequestPermissions()
        // Only background "Hi Nik" listening needs this
        if (VoiceSession.get(this).isWakeWordEnabled.value) askToSkipBatteryOptimization()

        setContent {
            NikVoiceTheme {
                VoiceAssistantScreen(viewModel = viewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        VoiceSession.get(this).setAppVisible(true)
    }

    override fun onStop() {
        // Leaving for another app mid-conversation: the overlay panel takes over
        VoiceSession.get(this).setAppVisible(false)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // Back in this app: no payment app is open, so screen control can be on again
        com.nik.voiceassistant.service.PaymentGuard.resume(this)
        // The user may have just switched screen control on in Settings > Accessibility
        viewModel.refreshAgentStatus()
    }

    /** Listening for "Hi Nik" in the background only lasts if the phone doesn't put the app to sleep. */
    @SuppressLint("BatteryLife")
    private fun askToSkipBatteryOptimization() {
        val power = getSystemService(PowerManager::class.java) ?: return
        if (power.isIgnoringBatteryOptimizations(packageName)) return
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            // Some phones don't offer the dialog; the app still works while it's open
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_PHONE_NUMBERS
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        val missing = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            // Permissions already granted: start listening right away (hands-free)
            if (autoStart) viewModel.startSession()
        }
    }
}
