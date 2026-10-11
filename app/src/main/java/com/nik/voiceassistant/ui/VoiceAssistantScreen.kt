package com.nik.voiceassistant.ui

import android.content.Intent
import android.provider.Settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nik.voiceassistant.ConnectionStatus
import com.nik.voiceassistant.TranscriptItem
import com.nik.voiceassistant.VoiceAssistantViewModel
import com.nik.voiceassistant.ui.components.SiriWaveVisualizer
import com.nik.voiceassistant.ui.theme.BgDark
import com.nik.voiceassistant.ui.theme.CyanAccent
import com.nik.voiceassistant.ui.theme.CyanGlow
import com.nik.voiceassistant.ui.theme.GreenActive
import com.nik.voiceassistant.ui.theme.PurpleAccent
import com.nik.voiceassistant.ui.theme.PurpleGlow
import com.nik.voiceassistant.ui.theme.RedStop
import com.nik.voiceassistant.ui.theme.SurfaceCard
import com.nik.voiceassistant.ui.theme.SurfaceDark
import com.nik.voiceassistant.ui.theme.TextMuted
import com.nik.voiceassistant.ui.theme.TextPrimary
import com.nik.voiceassistant.ui.theme.TextSecondary

@Composable
fun VoiceAssistantScreen(viewModel: VoiceAssistantViewModel) {
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val micAmp by viewModel.micAmplitude.collectAsState()
    val aiAmp by viewModel.aiAmplitude.collectAsState()
    val transcripts by viewModel.transcripts.collectAsState()

    val currentModel by viewModel.selectedModel.collectAsState()
    val currentVoice by viewModel.selectedVoice.collectAsState()
    val isSearchEnabled by viewModel.isSearchEnabled.collectAsState()
    val currentServerUrl by viewModel.serverUrl.collectAsState()
    val currentApiKey by viewModel.apiKey.collectAsState()
    val isAgentEnabled by viewModel.isAgentEnabled.collectAsState()
    val context = LocalContext.current

    val listState = rememberLazyListState()
    var showSettingsDialog by remember { mutableStateOf(false) }

    // Auto-scroll on new transcript
    LaunchedEffect(transcripts.size) {
        if (transcripts.isNotEmpty()) {
            listState.animateScrollToItem(transcripts.size - 1)
        }
    }

    val displayAmp = maxOf(micAmp, aiAmp)

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = BgDark
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 20.dp)
        ) {
            // Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "NIK JARVIS",
                        color = TextPrimary,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 2.sp
                    )

                    // Active Status & Model Badges Row
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val statusColor = when (connectionStatus) {
                            ConnectionStatus.CONNECTED -> GreenActive
                            ConnectionStatus.CONNECTING -> CyanAccent
                            ConnectionStatus.DISCONNECTED -> TextMuted
                            ConnectionStatus.ERROR -> RedStop
                        }

                        // Connection Status Dot
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(statusColor)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = when (connectionStatus) {
                                    ConnectionStatus.CONNECTED -> "Live"
                                    ConnectionStatus.CONNECTING -> "Connecting"
                                    ConnectionStatus.DISCONNECTED -> "Offline"
                                    ConnectionStatus.ERROR -> "Failed"
                                },
                                color = statusColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        // Model Chip Badge
                        val shortModel = when {
                            currentModel.contains("2.5") -> "⚡ 2.5 Flash"
                            currentModel.contains("3.1") -> "✨ 3.1 Flash"
                            currentModel.contains("3.8") -> "🔥 3.8 Live"
                            else -> currentModel.take(12)
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(SurfaceDark)
                                .border(1.dp, SurfaceCard, RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "$shortModel ($currentVoice)",
                                color = CyanAccent,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        // Search Grounding Badge
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSearchEnabled) GreenActive.copy(alpha = 0.15f) else SurfaceDark)
                                .border(
                                    1.dp,
                                    if (isSearchEnabled) GreenActive.copy(alpha = 0.5f) else SurfaceCard,
                                    RoundedCornerShape(6.dp)
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = if (isSearchEnabled) "🌐 Search ON" else "⚡ Fast",
                                color = if (isSearchEnabled) GreenActive else TextMuted,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                Row {
                    IconButton(onClick = { viewModel.clearTranscripts() }) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Clear History",
                            tint = TextSecondary
                        )
                    }
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = CyanAccent
                        )
                    }
                }
            }

            // Screen control is off: the assistant can't read or tap the screen until it's enabled
            if (!isAgentEnabled) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(PurpleAccent.copy(alpha = 0.12f))
                        .border(1.dp, PurpleAccent.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                        .clickable {
                            context.startActivity(
                                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Screen control is off. Tap → Accessibility → Nik Jarvis Voice → On, so I can tap, type and navigate for you.",
                        color = TextPrimary,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Siri Waveform Visualizer
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(175.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(SurfaceDark.copy(alpha = 0.6f))
                    .border(1.dp, SurfaceCard, RoundedCornerShape(24.dp))
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                SiriWaveVisualizer(
                    amplitude = displayAmp,
                    activeColor1 = if (aiAmp > 0.05f) PurpleAccent else CyanAccent,
                    activeColor2 = if (aiAmp > 0.05f) CyanAccent else PurpleAccent
                )

                Text(
                    text = when {
                        aiAmp > 0.05f -> "AI Speaking..."
                        isRecording && micAmp > 0.05f -> "Listening to you..."
                        isRecording -> "Listening for voice..."
                        else -> "Tap Microphone to Start"
                    },
                    color = TextSecondary.copy(alpha = 0.8f),
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Transcript & Logs Stream
            Text(
                text = "CONVERSATION & HARDWARE ACTIONS",
                color = TextMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
            )

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(SurfaceDark.copy(alpha = 0.4f))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (transcripts.isEmpty()) {
                    item {
                        Text(
                            text = "Say 'Hi Nik' or tap mic to speak.\nYou can say: 'WiFi settings kholo', 'YouTube pe lo-fi songs chalao', 'Is screen pe kya hai?', or ask current news.",
                            color = TextMuted,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 36.dp)
                        )
                    }
                }
                items(transcripts, key = { it.id }) { item ->
                    TranscriptBubble(item)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Bottom Actions & Large Mic Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Stop/Interrupt Button
                IconButton(
                    onClick = { viewModel.interruptAssistant() },
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(SurfaceDark)
                        .border(1.dp, SurfaceCard, CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.StopCircle,
                        contentDescription = "Interrupt AI",
                        tint = if (aiAmp > 0.05f) RedStop else TextMuted,
                        modifier = Modifier.size(28.dp)
                    )
                }

                // Main Mic Toggle Button
                val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                val pulseScale by infiniteTransition.animateFloat(
                    initialValue = 1f,
                    targetValue = if (isRecording) 1.08f else 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(800),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "pulseScale"
                )

                Box(
                    modifier = Modifier
                        .scale(pulseScale)
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = if (isRecording) listOf(
                                    CyanAccent,
                                    PurpleAccent
                                ) else listOf(SurfaceCard, SurfaceDark)
                            )
                        )
                        .border(
                            width = 2.dp,
                            color = if (isRecording) CyanAccent else TextMuted,
                            shape = CircleShape
                        )
                        .shadow(
                            elevation = if (isRecording) 16.dp else 0.dp,
                            shape = CircleShape,
                            ambientColor = CyanAccent,
                            spotColor = PurpleAccent
                        )
                        .clickable { viewModel.toggleSession() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isRecording) Icons.Default.Mic else Icons.Default.MicOff,
                        contentDescription = "Toggle Mic",
                        tint = if (isRecording) BgDark else TextPrimary,
                        modifier = Modifier.size(38.dp)
                    )
                }

                // Settings Shortcut Button
                IconButton(
                    onClick = { showSettingsDialog = true },
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(SurfaceDark)
                        .border(1.dp, SurfaceCard, CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = TextSecondary,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        }
    }

    // Modern Settings Dialog with Model Switching & Web Search Grounding
    if (showSettingsDialog) {
        var selectedModelId by remember { mutableStateOf(currentModel) }
        var selectedVoiceName by remember { mutableStateOf(currentVoice) }
        var searchEnabledState by remember { mutableStateOf(isSearchEnabled) }
        var editUrl by remember { mutableStateOf(currentServerUrl) }
        var editKey by remember { mutableStateOf(currentApiKey) }

        val scrollState = rememberScrollState()

        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            containerColor = SurfaceDark,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        tint = CyanAccent,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Voice & Model Settings",
                        color = TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 1. Model Switching Section
                    Text(
                        text = "GEMINI LIVE MODEL",
                        color = TextMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )

                    viewModel.availableModels.forEach { modelOpt ->
                        val isSelected = selectedModelId == modelOpt.id
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSelected) CyanGlow.copy(alpha = 0.25f) else BgDark)
                                .border(
                                    width = if (isSelected) 1.5.dp else 1.dp,
                                    color = if (isSelected) CyanAccent else SurfaceCard,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable { selectedModelId = modelOpt.id }
                                .padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.Top
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = modelOpt.displayName,
                                            color = if (isSelected) CyanAccent else TextPrimary,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(if (isSelected) CyanAccent.copy(alpha = 0.2f) else SurfaceCard)
                                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = modelOpt.badge,
                                                color = if (isSelected) CyanAccent else TextMuted,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = modelOpt.description,
                                        color = TextSecondary,
                                        fontSize = 11.sp,
                                        lineHeight = 15.sp
                                    )
                                }
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Selected",
                                        tint = CyanAccent,
                                        modifier = Modifier.size(18.dp).padding(start = 6.dp)
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = SurfaceCard)

                    // 2. Voice Selection Section
                    Text(
                        text = "VOICE ACTOR",
                        color = TextMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        viewModel.availableVoices.forEach { voiceName ->
                            val isSelected = selectedVoiceName == voiceName
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) PurpleAccent else BgDark)
                                    .border(
                                        1.dp,
                                        if (isSelected) PurpleAccent else SurfaceCard,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .clickable { selectedVoiceName = voiceName }
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = voiceName,
                                    color = if (isSelected) BgDark else TextPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = SurfaceCard)

                    // 3. Google Search Grounding Toggle
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(BgDark)
                            .border(1.dp, SurfaceCard, RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Language,
                                        contentDescription = null,
                                        tint = if (searchEnabledState) GreenActive else TextMuted,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Live Google Search",
                                        color = TextPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = if (searchEnabledState)
                                        "ON: Answers with real-time news, current date & live info (Turn latency ~3-4s)."
                                    else
                                        "OFF: Instant low-latency voice (~1.2s reply, training cutoff date).",
                                    color = TextSecondary,
                                    fontSize = 11.sp,
                                    lineHeight = 14.sp
                                )
                            }

                            Switch(
                                checked = searchEnabledState,
                                onCheckedChange = { searchEnabledState = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = BgDark,
                                    checkedTrackColor = GreenActive,
                                    uncheckedThumbColor = TextMuted,
                                    uncheckedTrackColor = SurfaceCard
                                )
                            )
                        }
                    }

                    HorizontalDivider(color = SurfaceCard)

                    // 4. Server URL & API Key
                    Text(
                        text = "BACKEND GATEWAY",
                        color = TextMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )

                    OutlinedTextField(
                        value = editUrl,
                        onValueChange = { editUrl = it },
                        label = { Text("WebSocket Base URL", color = TextSecondary, fontSize = 12.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = CyanAccent,
                            unfocusedBorderColor = SurfaceCard
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = editKey,
                        onValueChange = { editKey = it },
                        label = { Text("API Key (X-API-Key)", color = TextSecondary, fontSize = 12.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = CyanAccent,
                            unfocusedBorderColor = SurfaceCard
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.saveSettings(
                            model = selectedModelId,
                            voice = selectedVoiceName,
                            searchEnabled = searchEnabledState,
                            url = editUrl,
                            key = editKey
                        )
                        showSettingsDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyanAccent)
                ) {
                    Text("Save & Apply", color = BgDark, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSettingsDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }
}

@Composable
fun TranscriptBubble(item: TranscriptItem) {
    when (item.sender) {
        "user" -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp, 16.dp, 2.dp, 16.dp))
                        .background(CyanGlow)
                        .border(
                            1.dp,
                            CyanAccent.copy(alpha = 0.5f),
                            RoundedCornerShape(16.dp, 16.dp, 2.dp, 16.dp)
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Column {
                        Text(
                            text = item.text,
                            color = TextPrimary,
                            fontSize = 14.sp
                        )
                        Text(
                            text = item.timeFormatted,
                            color = TextMuted,
                            fontSize = 10.sp,
                            modifier = Modifier
                                .align(Alignment.End)
                                .padding(top = 2.dp)
                        )
                    }
                }
            }
        }

        "assistant" -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp, 16.dp, 16.dp, 2.dp))
                        .background(PurpleGlow)
                        .border(
                            1.dp,
                            PurpleAccent.copy(alpha = 0.5f),
                            RoundedCornerShape(16.dp, 16.dp, 16.dp, 2.dp)
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Column {
                        Text(
                            text = item.text,
                            color = TextPrimary,
                            fontSize = 14.sp
                        )
                        Text(
                            text = item.timeFormatted,
                            color = TextMuted,
                            fontSize = 10.sp,
                            modifier = Modifier
                                .align(Alignment.End)
                                .padding(top = 2.dp)
                        )
                    }
                }
            }
        }

        "tool" -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF003829))
                    .border(1.dp, GreenActive.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "⚙️ ${item.text}",
                    color = GreenActive,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        else -> {
            Text(
                text = "• ${item.text}",
                color = TextMuted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        }
    }
}
