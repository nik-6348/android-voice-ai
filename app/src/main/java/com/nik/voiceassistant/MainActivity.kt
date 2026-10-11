package com.nik.voiceassistant

import android.Manifest
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

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] == true
        if (audioGranted) {
            viewModel.startSession()
        } else {
            Toast.makeText(this, "Microphone permission is required for voice assistant", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        checkAndRequestPermissions()

        setContent {
            NikVoiceTheme {
                VoiceAssistantScreen(viewModel = viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The user may have just switched screen control on in Settings > Accessibility
        viewModel.refreshAgentStatus()
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.READ_CONTACTS
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
            // Permissions already granted: auto-start session immediately for hands-free experience!
            viewModel.startSession()
        }
    }
}
