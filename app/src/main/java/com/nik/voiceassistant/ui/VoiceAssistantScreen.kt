package com.nik.voiceassistant.ui

import android.content.Intent
import android.provider.Settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.nik.voiceassistant.ui.components.VoiceOrb
import com.nik.voiceassistant.ui.theme.BrandCyan
import com.nik.voiceassistant.ui.theme.BrandPink
import com.nik.voiceassistant.ui.theme.BrandViolet
import com.nik.voiceassistant.ui.theme.Glass
import com.nik.voiceassistant.ui.theme.GlassBorder
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
    val isWakeWordEnabled by viewModel.isWakeWordEnabled.collectAsState()
    val choices by viewModel.choices.collectAsState()
    val context = LocalContext.current

    val listState = rememberLazyListState()
    // First launch (no API key yet): open Settings straight away
    var showSettingsDialog by remember { mutableStateOf(currentApiKey.isBlank()) }

    // Auto-scroll on new transcript
    LaunchedEffect(transcripts.size) {
        if (transcripts.isNotEmpty()) {
            listState.animateScrollToItem(transcripts.size - 1)
        }
    }

    val displayAmp = maxOf(micAmp, aiAmp)
    val live = isRecording
    val speaking = aiAmp > 0.03f
    var typing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    val conversationStarted = transcripts.any { it.sender == "user" || it.sender == "assistant" }
    val orbSize by animateDpAsState(if (conversationStarted) 132.dp else 230.dp, tween(450), label = "orbSize")

    val statusLine = when {
        connectionStatus == ConnectionStatus.CONNECTING -> "Connecting…"
        connectionStatus == ConnectionStatus.ERROR -> "Couldn't connect"
        speaking -> "Speaking"
        live && micAmp > 0.03f -> "Listening to you"
        live -> "Listening"
        isWakeWordEnabled -> "Sleeping · say \"Hi Nik\""
        else -> "Tap the orb to talk"
    }
    val modelName = when {
        currentModel.contains("2.5") -> "2.5 Flash"
        currentModel.contains("3.1") -> "3.1 Flash"
        currentModel.contains("3.8") -> "3.8 Live"
        else -> currentModel.take(12)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            // Two soft colour washes behind everything
            .background(Brush.radialGradient(listOf(BrandViolet.copy(alpha = 0.18f), Color.Transparent), center = Offset(0f, 0f), radius = 1100f))
            .background(Brush.radialGradient(listOf(BrandCyan.copy(alpha = 0.10f), Color.Transparent), center = Offset(1400f, 2600f), radius = 1300f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .padding(horizontal = 18.dp)
        ) {
            // ---- Top bar ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(BrandCyan, BrandViolet, BrandPink))),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Mic, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Nik's voice", color = TextPrimary, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val dot = when (connectionStatus) {
                            ConnectionStatus.CONNECTED -> GreenActive
                            ConnectionStatus.CONNECTING -> BrandCyan
                            ConnectionStatus.ERROR -> RedStop
                            ConnectionStatus.DISCONNECTED -> TextMuted
                        }
                        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(dot))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "$statusLine · $modelName · $currentVoice${if (isSearchEnabled) " · Web" else ""}",
                            color = TextSecondary, fontSize = 11.5.sp, maxLines = 1
                        )
                    }
                }
                RoundIconButton(Icons.Default.DeleteOutline, "Clear conversation") { viewModel.clearTranscripts() }
                Spacer(modifier = Modifier.width(8.dp))
                RoundIconButton(Icons.Default.Settings, "Settings") { showSettingsDialog = true }
            }

            // ---- Screen control off ----
            AnimatedVisibility(visible = !isAgentEnabled) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Brush.horizontalGradient(listOf(BrandViolet.copy(alpha = 0.22f), BrandPink.copy(alpha = 0.14f))))
                        .border(1.dp, BrandViolet.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                        .clickable {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.TouchApp, contentDescription = null, tint = BrandPink, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Turn on screen control", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("So I can tap, type and open things for you", color = TextSecondary, fontSize = 11.5.sp)
                    }
                    Text("Enable", color = BrandCyan, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            // ---- Orb ----
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = if (conversationStarted) 4.dp else 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                VoiceOrb(
                    level = displayAmp,
                    live = live,
                    speaking = speaking,
                    modifier = Modifier
                        .size(orbSize)
                        .clip(CircleShape)
                        .clickable { viewModel.toggleSession() }
                )
                AnimatedContent(targetState = statusLine, transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) }, label = "status") { text ->
                    Text(text, color = TextSecondary, fontSize = 13.sp, letterSpacing = 0.3.sp)
                }
            }

            // ---- Conversation, or a welcome with suggestions ----
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (!conversationStarted) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(top = 18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Namaste 👋", color = TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "Bolo ya likho — main phone chala sakta hoon,\napps khol sakta hoon, aur sawaalon ke jawab de sakta hoon.",
                            color = TextSecondary, fontSize = 13.5.sp, textAlign = TextAlign.Center, lineHeight = 19.sp
                        )
                        Spacer(modifier = Modifier.height(18.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf("🎵 Mahakal bhajan chalao", "📶 WiFi band karo", "👀 Screen pe kya hai?", "🔊 Volume 50 karo", "🌦 Aaj ka mausam?", "💬 WhatsApp kholo")
                                .forEach { chip ->
                                    SuggestionChip(chip) { viewModel.sendText(chip.substringAfter(' ')) }
                                }
                        }
                        // Other transcript lines (e.g. "Connected", "Add your API key") before anyone has spoken
                        transcripts.lastOrNull { it.sender == "system" }?.let {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(it.text, color = TextMuted, fontSize = 11.5.sp, textAlign = TextAlign.Center)
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(transcripts, key = { it.id }) { item -> TranscriptBubble(item) }
                    }
                }
            }

            // ---- A list to pick from (show_choices): tap one, or say it ----
            AnimatedVisibility(visible = choices != null) {
                val open = choices ?: return@AnimatedVisibility
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(SurfaceDark.copy(alpha = 0.95f))
                        .border(1.dp, BrandViolet.copy(alpha = 0.35f), RoundedCornerShape(22.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(open.question, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text("tap or say it", color = TextMuted, fontSize = 11.sp)
                    }
                    open.options.forEachIndexed { i, option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(Glass)
                                .border(1.dp, GlassBorder, RoundedCornerShape(14.dp))
                                .clickable { viewModel.pickChoice(option) }
                                .padding(horizontal = 12.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(Brush.linearGradient(listOf(BrandCyan, BrandViolet))),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("${i + 1}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(option, color = TextPrimary, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }

            // ---- Typing ----
            AnimatedVisibility(visible = typing) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                        .clip(RoundedCornerShape(26.dp))
                        .background(Glass)
                        .border(1.dp, GlassBorder, RoundedCornerShape(26.dp))
                        .padding(start = 18.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        singleLine = true,
                        textStyle = TextStyle(color = TextPrimary, fontSize = 15.sp),
                        cursorBrush = SolidColor(BrandCyan),
                        modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                        decorationBox = { field ->
                            if (draft.isEmpty()) Text("Type a request…", color = TextMuted, fontSize = 15.sp)
                            field()
                        }
                    )
                    IconButton(
                        onClick = { viewModel.sendText(draft); draft = "" },
                        enabled = draft.isNotBlank(),
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(if (draft.isNotBlank()) Brush.linearGradient(listOf(BrandCyan, BrandViolet)) else Brush.linearGradient(listOf(SurfaceCard, SurfaceCard)))
                    ) {
                        Icon(Icons.Default.Send, contentDescription = "Send", tint = Color.White, modifier = Modifier.size(19.dp))
                    }
                }
            }

            // ---- Bottom controls ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 14.dp, top = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                RoundIconButton(if (typing) Icons.Default.Close else Icons.Default.Keyboard, "Type", size = 52.dp) { typing = !typing }

                // Big mic: gradient while live, with a pulsing ring
                val pulse = rememberInfiniteTransition(label = "mic")
                val ring by pulse.animateFloat(1f, 1.35f, infiniteRepeatable(tween(1400), RepeatMode.Restart), label = "ring")
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(96.dp)) {
                    if (live) {
                        Box(
                            modifier = Modifier
                                .size(74.dp)
                                .scale(ring)
                                .clip(CircleShape)
                                .background(BrandCyan.copy(alpha = (1.35f - ring) * 0.5f))
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(74.dp)
                            .shadow(if (live) 18.dp else 4.dp, CircleShape, ambientColor = BrandViolet, spotColor = BrandCyan)
                            .clip(CircleShape)
                            .background(
                                if (live) Brush.linearGradient(listOf(BrandCyan, BrandViolet, BrandPink))
                                else Brush.linearGradient(listOf(SurfaceCard, SurfaceDark))
                            )
                            .border(1.dp, if (live) Color.White.copy(alpha = 0.25f) else GlassBorder, CircleShape)
                            .clickable { viewModel.toggleSession() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (live) Icons.Default.GraphicEq else Icons.Default.Mic,
                            contentDescription = if (live) "End conversation" else "Start talking",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                RoundIconButton(
                    Icons.Default.StopCircle, "Stop speaking", size = 52.dp,
                    tint = if (speaking) RedStop else TextSecondary
                ) { viewModel.interruptAssistant() }
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

                    // "Hi Nik" wake word: applies right away (not on Save)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "\"Hi Nik\" wake word",
                                color = TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (isWakeWordEnabled)
                                    "ON: listens on the phone (offline) for \"Hi Nik\" even when the app is closed; sleeps after each conversation."
                                else
                                    "OFF (mic stays closed): use the assistant gesture below, or open the app and tap the mic.",
                                color = TextSecondary,
                                fontSize = 11.sp,
                                lineHeight = 14.sp
                            )
                        }
                        Switch(
                            checked = isWakeWordEnabled,
                            onCheckedChange = { viewModel.setWakeWordEnabled(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = BgDark,
                                checkedTrackColor = GreenActive,
                                uncheckedThumbColor = TextMuted,
                                uncheckedTrackColor = SurfaceCard
                            )
                        )
                    }

                    // Assistant gesture: Nik as the phone's digital assistant app (long-press power / corner swipe)
                    val isAssistant = remember {
                        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q &&
                            context.getSystemService(android.app.role.RoleManager::class.java)
                                ?.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT) == true
                    }
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Phone assistant (like Google Assistant)",
                            color = TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (isAssistant)
                                "✓ Nik's voice is your assistant: long-press the power button (or swipe up from a bottom corner) to talk."
                            else
                                "Make Nik's voice the digital assistant app, then long-press the power button (or swipe up from a bottom corner) to talk. The mic opens only then.",
                            color = TextSecondary,
                            fontSize = 11.sp,
                            lineHeight = 14.sp
                        )
                        if (!isAssistant) {
                            TextButton(onClick = {
                                context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }) {
                                Text("Choose Nik's voice as assistant →", color = CyanAccent, fontSize = 12.sp)
                            }
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
                        // Hidden like a password; it stays on this phone only
                        visualTransformation = PasswordVisualTransformation(),
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
        "user" -> Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(
                text = item.text,
                color = TextPrimary,
                fontSize = 15.sp,
                lineHeight = 21.sp,
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp))
                    .background(Brush.linearGradient(listOf(BrandCyan.copy(alpha = 0.22f), BrandViolet.copy(alpha = 0.22f))))
                    .border(1.dp, GlassBorder, RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            )
        }

        "assistant" -> Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(BrandCyan, BrandViolet, BrandPink)))
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = item.text,
                color = TextPrimary.copy(alpha = 0.92f),
                fontSize = 15.sp,
                lineHeight = 22.sp,
                modifier = Modifier
                    .widthIn(max = 310.dp)
                    .clip(RoundedCornerShape(6.dp, 20.dp, 20.dp, 20.dp))
                    .background(Glass)
                    .border(1.dp, GlassBorder, RoundedCornerShape(6.dp, 20.dp, 20.dp, 20.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            )
        }

        // "Executed open_app {app_name=youtube}: com.google.android.youtube" -> ⚡ Open app · com.google…
        "tool" -> {
            val body = item.text.removePrefix("Executed ")
            val name = body.substringBefore(' ').substringBefore(':').replace('_', ' ').replaceFirstChar { it.uppercase() }
            val detail = body.substringAfter(": ", "").take(80)
            Row(
                modifier = Modifier
                    .padding(start = 32.dp)
                    .clip(RoundedCornerShape(50))
                    .background(BrandViolet.copy(alpha = 0.10f))
                    .border(1.dp, BrandViolet.copy(alpha = 0.25f), RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Bolt, contentDescription = null, tint = BrandPink, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(5.dp))
                Text(name, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                if (detail.isNotBlank()) {
                    Text(" · $detail", color = TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        else -> Text(
            text = item.text,
            color = TextMuted,
            fontSize = 11.5.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
        )
    }
}

@Composable
private fun RoundIconButton(
    icon: ImageVector,
    description: String,
    size: Dp = 40.dp,
    tint: Color = TextSecondary,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(Glass)
            .border(1.dp, GlassBorder, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(size * 0.46f))
    }
}

@Composable
private fun SuggestionChip(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        color = TextPrimary,
        fontSize = 13.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Glass)
            .border(1.dp, GlassBorder, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp)
    )
}
