package com.nik.voiceassistant.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nik.voiceassistant.VoiceAssistantViewModel
import com.nik.voiceassistant.service.PhoneAgentService
import com.nik.voiceassistant.SecurePrefs
import com.nik.voiceassistant.VoiceSession
import com.nik.voiceassistant.ui.theme.*

@Composable
fun SettingsTab(
    viewModel: VoiceAssistantViewModel
) {
    val context = LocalContext.current
    val currentModel by viewModel.selectedModel.collectAsState()
    val currentVoice by viewModel.selectedVoice.collectAsState()
    val isSearchEnabled by viewModel.isSearchEnabled.collectAsState()
    val currentServerUrl by viewModel.serverUrl.collectAsState()
    val currentApiKey by viewModel.apiKey.collectAsState()
    val hasGeminiKey by viewModel.hasGeminiKey.collectAsState()
    val currentMode by viewModel.connectionMode.collectAsState()
    val currentLlmBase by viewModel.llmBaseUrl.collectAsState()
    val currentVision by viewModel.visionFrames.collectAsState()
    val models by viewModel.availableModels.collectAsState()
    val modelsLoading by viewModel.modelsLoading.collectAsState()
    val isWakeWordEnabled by viewModel.isWakeWordEnabled.collectAsState()
    val assistantName by viewModel.assistantName.collectAsState()
    val autoSleep by viewModel.autoSleep.collectAsState()
    val autoMute by viewModel.autoMute.collectAsState()

    var model by remember { mutableStateOf(currentModel) }
    var voice by remember { mutableStateOf(currentVoice) }
    var searchEnabled by remember { mutableStateOf(isSearchEnabled) }
    var serverUrl by remember { mutableStateOf(currentServerUrl) }
    // Key fields start empty: a saved key is never shown again; typing a new one replaces it
    var apiKey by remember { mutableStateOf("") }
    var geminiKey by remember { mutableStateOf("") }
    var showApiKey by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(currentMode) }
    var llmBase by remember { mutableStateOf(currentLlmBase) }
    var vision by remember { mutableStateOf(currentVision) }

    var nameInput by remember { mutableStateOf(assistantName) }
    var pinInput by remember { mutableStateOf("") }
    var pinSaved by remember { mutableStateOf(viewModel.hasPin()) }
    var autoUnlock by remember { mutableStateOf(viewModel.isAutoUnlockEnabled()) }

    val isAgentRunning = PhoneAgentService.instance != null

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 90.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            // Header
            Column {
                Text(
                    text = "Settings & Security",
                    color = TextPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Configure voice assistant, auto-unlock, and AI model",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }
        }

        // ---- 1. SECURITY & AUTO-UNLOCK ----
        item {
            SettingsCard(title = "Security & Screen Unlock", icon = Icons.Default.Lock) {
                // Auto-unlock switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Auto-unlock phone", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (autoUnlock)
                                "ON (Testing / Hands-free): Automatically enters PIN and unlocks when screen is locked."
                            else
                                "OFF (Secure mode): AI will say 'Phone locked hai, pehle unlock karein' and wait.",
                            color = TextSecondary,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }

                    Switch(
                        checked = autoUnlock,
                        enabled = pinSaved,
                        onCheckedChange = {
                            autoUnlock = it
                            viewModel.setAutoUnlock(it)
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = BgDark, checkedTrackColor = GreenActive)
                    )
                }

                HorizontalDivider(color = GlassBorder, modifier = Modifier.padding(vertical = 12.dp))

                // PIN Setup
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Device PIN", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        if (pinSaved) {
                            Text("✓ PIN Saved (Encrypted)", color = GreenActive, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Stored encrypted on this device using Android Keystore. Never sent to cloud or AI.",
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = pinInput,
                            onValueChange = { if (it.all { c -> c.isDigit() } && it.length <= 12) pinInput = it },
                            placeholder = { Text(if (pinSaved) "••••" else "Enter PIN", color = TextMuted) },
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                                focusedBorderColor = CyanAccent, unfocusedBorderColor = GlassBorder,
                                focusedContainerColor = SurfaceDark, unfocusedContainerColor = SurfaceDark
                            )
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        Button(
                            onClick = {
                                if (pinInput.isNotBlank()) {
                                    viewModel.savePin(pinInput)
                                    pinSaved = true
                                    pinInput = ""
                                    Toast.makeText(context, "PIN saved securely!", Toast.LENGTH_SHORT).show()
                                }
                            },
                            enabled = pinInput.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Save", color = BgDark, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // ---- 2. VOICE & ASSISTANT ----
        item {
            SettingsCard(title = "Voice & Wake Word", icon = Icons.Default.RecordVoiceOver) {
                // Wake word toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("\"Hi $assistantName\" Wake Word", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Listens on the phone (offline) even when screen is locked or app is closed.",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }

                    Switch(
                        checked = isWakeWordEnabled,
                        onCheckedChange = { viewModel.setWakeWordEnabled(it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = BgDark, checkedTrackColor = GreenActive)
                    )
                }

                HorizontalDivider(color = GlassBorder, modifier = Modifier.padding(vertical = 12.dp))

                // Auto-mute: mic off while the assistant talks or works
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Auto-mute mic while assistant works", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Mic turns off while $assistantName works on your request, so noise can't interrupt, and turns back on by itself when it's done. Tap the mic to speak anytime.",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }

                    Switch(
                        checked = autoMute,
                        onCheckedChange = { viewModel.setAutoMute(it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = BgDark, checkedTrackColor = GreenActive)
                    )
                }

                HorizontalDivider(color = GlassBorder, modifier = Modifier.padding(vertical = 12.dp))

                // Assistant Name
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Assistant Name", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("What the assistant calls itself", color = TextMuted, fontSize = 11.sp)
                    }

                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = {
                            nameInput = it
                            if (it.isNotBlank()) viewModel.setAssistantName(it)
                        },
                        modifier = Modifier.width(130.dp),
                        shape = RoundedCornerShape(10.dp),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                            focusedBorderColor = CyanAccent, unfocusedBorderColor = GlassBorder,
                            focusedContainerColor = SurfaceDark, unfocusedContainerColor = SurfaceDark
                        )
                    )
                }

                HorizontalDivider(color = GlassBorder, modifier = Modifier.padding(vertical = 12.dp))

                // Voice selection
                Text("Assistant Voice", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    viewModel.availableVoices.take(3).forEach { v ->
                        val sel = voice == v
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (sel) BrandViolet.copy(alpha = 0.25f) else SurfaceDark)
                                .border(1.dp, if (sel) BrandViolet else GlassBorder, RoundedCornerShape(10.dp))
                                .clickable { voice = v }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(v, color = if (sel) BrandViolet else TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    viewModel.availableVoices.drop(3).take(3).forEach { v ->
                        val sel = voice == v
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (sel) BrandViolet.copy(alpha = 0.25f) else SurfaceDark)
                                .border(1.dp, if (sel) BrandViolet else GlassBorder, RoundedCornerShape(10.dp))
                                .clickable { voice = v }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(v, color = if (sel) BrandViolet else TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // ---- 3. AI MODEL & SEARCH ----
        item {
            SettingsCard(title = "AI Model & Search", icon = Icons.Default.Psychology) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Gemini Live Model", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (hasGeminiKey) {
                        TextButton(onClick = {
                            viewModel.refreshLiveModels { ok ->
                                Toast.makeText(context, if (ok) "Models updated from Google" else "Couldn't load models (check the Gemini key)", Toast.LENGTH_SHORT).show()
                            }
                        }) {
                            Text(if (modelsLoading) "Loading…" else "Refresh from Google", color = CyanAccent, fontSize = 12.sp)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                models.forEach { opt ->
                    val sel = model == opt.id
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .border(1.dp, if (sel) CyanAccent else GlassBorder, RoundedCornerShape(12.dp))
                            .clickable { model = opt.id },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = if (sel) CyanAccent.copy(alpha = 0.1f) else SurfaceDark)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(opt.displayName, color = if (sel) CyanAccent else TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                Box(
                                    modifier = Modifier
                                        .background(Glass, RoundedCornerShape(6.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(opt.badge, color = TextMuted, fontSize = 9.sp)
                                }
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(opt.description, color = TextSecondary, fontSize = 11.sp, lineHeight = 14.sp)
                        }
                    }
                }

                HorizontalDivider(color = GlassBorder, modifier = Modifier.padding(vertical = 12.dp))

                // Search Grounding toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Real-time Google Search", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("Ground responses with live web results", color = TextMuted, fontSize = 11.sp)
                    }

                    Switch(
                        checked = searchEnabled,
                        onCheckedChange = { searchEnabled = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = BgDark, checkedTrackColor = GreenActive)
                    )
                }
            }
        }

        // ---- 4. CONNECTION & KEYS ----
        item {
            SettingsCard(title = "Connection & Keys", icon = Icons.Default.Cloud) {
                Text("How to reach the AI", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(8.dp))
                listOf(
                    Triple(VoiceSession.MODE_DIRECT, "Direct to Gemini Live", "Fastest: the phone talks to Google itself. Needs a Gemini API key. Sends screen images to the AI."),
                    Triple(VoiceSession.MODE_SERVER, "Through your server", "Via the WebSocket server below (its own Hindi speech-to-text). Uses the LLM key.")
                ).forEach { (id, title, desc) ->
                    val sel = mode == id
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .border(1.dp, if (sel) CyanAccent else GlassBorder, RoundedCornerShape(12.dp))
                            .clickable { mode = id },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = if (sel) CyanAccent.copy(alpha = 0.1f) else SurfaceDark)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(title, color = if (sel) CyanAccent else TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text(desc, color = TextSecondary, fontSize = 11.sp, lineHeight = 14.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                if (mode == VoiceSession.MODE_DIRECT) {
                    SecretField(
                        value = geminiKey, onValueChange = { geminiKey = it }, label = "Gemini API key",
                        saved = hasGeminiKey, visible = showApiKey, onToggleVisible = { showApiKey = !showApiKey },
                        onClear = { viewModel.clearKey(SecurePrefs.GEMINI_API_KEY) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Let the AI see the screen", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("An image of the app after each action, so it spots popups, ads and buttons the text list misses", color = TextMuted, fontSize = 11.sp)
                        }
                        Switch(
                            checked = vision, onCheckedChange = { vision = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = BgDark, checkedTrackColor = GreenActive)
                        )
                    }
                } else {
                    SettingsTextField(serverUrl, { serverUrl = it }, "WebSocket server URL")
                }

                HorizontalDivider(color = GlassBorder, modifier = Modifier.padding(vertical = 12.dp))
                Text("LLM gateway (research & screen reading)", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(8.dp))
                SettingsTextField(llmBase, { llmBase = it }, "LLM base URL")
                Spacer(modifier = Modifier.height(8.dp))
                SecretField(
                    value = apiKey, onValueChange = { apiKey = it }, label = "LLM API key",
                    saved = currentApiKey.isNotBlank(), visible = showApiKey, onToggleVisible = { showApiKey = !showApiKey },
                    onClear = { viewModel.clearKey(SecurePrefs.LLM_API_KEY) }
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = GreenActive, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Keys are encrypted on this phone (Android Keystore) and never shown again.", color = TextMuted, fontSize = 11.sp)
                }
            }
        }

        // ---- 5. PERMISSIONS STATUS ----
        item {
            SettingsCard(title = "System Permissions", icon = Icons.Default.Security) {
                PermissionStatusRow("Accessibility Service (Screen Agent)", isAgentRunning) {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                Spacer(modifier = Modifier.height(8.dp))
                PermissionStatusRow("Overlay Permission (Draw Over Apps)", Settings.canDrawOverlays(context)) {
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                    context.startActivity(intent)
                }
            }
        }

        // ---- Save Button ----
        item {
            Button(
                onClick = {
                    val stored = viewModel.saveSettings(
                        model, voice, searchEnabled, serverUrl, mode,
                        geminiKey.ifBlank { null }, llmBase, apiKey.ifBlank { null }, vision
                    )
                    geminiKey = ""
                    apiKey = ""
                    Toast.makeText(
                        context,
                        if (stored) "Settings saved" else "Settings saved, but the keys couldn't be stored securely on this phone",
                        Toast.LENGTH_SHORT
                    ).show()
                },
                colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Icon(Icons.Default.Check, contentDescription = null, tint = BgDark)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Save & Apply Settings", color = BgDark, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun SettingsCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, GlassBorder, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = CyanAccent, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
fun PermissionStatusRow(
    name: String,
    granted: Boolean,
    onOpenSettings: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(name, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text(if (granted) "Active" else "Missing / Tap to fix", color = if (granted) GreenActive else RedStop, fontSize = 10.sp)
        }

        if (!granted) {
            TextButton(
                onClick = onOpenSettings,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text("Enable →", color = CyanAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        } else {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = GreenActive, modifier = Modifier.size(16.dp))
        }
    }
}

/** A key field: empty means "keep the saved key"; shows that one is saved without ever showing it. */
@Composable
private fun SecretField(
    value: String, onValueChange: (String) -> Unit, label: String, saved: Boolean,
    visible: Boolean, onToggleVisible: () -> Unit, onClear: () -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = TextSecondary) },
        placeholder = { Text(if (saved) "•••••••• saved (type to replace)" else "Paste key", color = TextMuted) },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            Row {
                IconButton(onClick = onToggleVisible) {
                    Icon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = "Show", tint = TextMuted)
                }
                if (saved) IconButton(onClick = onClear) {
                    Icon(Icons.Default.Delete, contentDescription = "Remove saved key", tint = TextMuted)
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
            focusedBorderColor = CyanAccent, unfocusedBorderColor = GlassBorder,
            focusedContainerColor = SurfaceDark, unfocusedContainerColor = SurfaceDark
        )
    )
}

@Composable
private fun SettingsTextField(value: String, onValueChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = TextSecondary) },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
            focusedBorderColor = CyanAccent, unfocusedBorderColor = GlassBorder,
            focusedContainerColor = SurfaceDark, unfocusedContainerColor = SurfaceDark
        )
    )
}
