package com.nik.voiceassistant.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nik.voiceassistant.ConnectionStatus
import com.nik.voiceassistant.TranscriptItem
import com.nik.voiceassistant.VoiceAssistantViewModel
import com.nik.voiceassistant.ui.components.SiriWaveVisualizer
import com.nik.voiceassistant.ui.components.VoiceOrb
import com.nik.voiceassistant.ui.theme.*

@Composable
fun AssistantLiveTab(
    viewModel: VoiceAssistantViewModel,
    onNavigateToSettings: () -> Unit
) {
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val micAmp by viewModel.micAmplitude.collectAsState()
    val aiAmp by viewModel.aiAmplitude.collectAsState()
    val transcripts by viewModel.transcripts.collectAsState()

    val currentModel by viewModel.selectedModel.collectAsState()
    val currentVoice by viewModel.selectedVoice.collectAsState()
    val isSearchEnabled by viewModel.isSearchEnabled.collectAsState()
    val isAgentEnabled by viewModel.isAgentEnabled.collectAsState()
    val isWakeWordEnabled by viewModel.isWakeWordEnabled.collectAsState()
    val choices by viewModel.choices.collectAsState()
    val assistantName by viewModel.assistantName.collectAsState()
    val context = LocalContext.current

    val listState = rememberLazyListState()

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
    val orbSize by animateDpAsState(if (conversationStarted) 110.dp else 180.dp, tween(450), label = "orbSize")

    val statusLine = when {
        connectionStatus == ConnectionStatus.CONNECTING -> "Connecting…"
        connectionStatus == ConnectionStatus.ERROR -> "Couldn't connect"
        speaking -> "Speaking…"
        live && micAmp > 0.03f -> "Listening to you…"
        live -> "Listening…"
        isWakeWordEnabled -> "Sleeping · say \"Hi $assistantName\""
        else -> "Tap the orb or mic to talk"
    }

    val modelName = when {
        currentModel.contains("2.5") -> "2.5 Flash"
        currentModel.contains("3.1") -> "3.1 Preview"
        currentModel.contains("3.8") -> "3.8 Live"
        else -> currentModel.take(10)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        // ---- Top Bar ----
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
                Text(assistantName, color = TextPrimary, fontSize = 19.sp, fontWeight = FontWeight.Bold)
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
                        color = TextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // New chat
            RoundIconButton(Icons.Default.Add, "New chat") { viewModel.startNewChat() }
            Spacer(modifier = Modifier.width(6.dp))
            // Clear current conversation
            RoundIconButton(Icons.Default.DeleteOutline, "Clear conversation") { viewModel.clearTranscripts() }
        }

        // ---- Screen control notice if accessibility is off ----
        AnimatedVisibility(visible = !isAgentEnabled) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, bottom = 4.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Brush.horizontalGradient(listOf(BrandViolet.copy(alpha = 0.22f), BrandPink.copy(alpha = 0.14f))))
                    .border(1.dp, BrandViolet.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                    .clickable {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.TouchApp, contentDescription = null, tint = BrandPink, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Turn on screen control", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text("Allows Shiv to tap, type and run automations", color = TextSecondary, fontSize = 10.5.sp)
                }
                Text("Enable →", color = BrandCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        // ---- Voice Orb & Wave Visualizer ----
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = if (conversationStarted) 4.dp else 16.dp),
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

            Spacer(modifier = Modifier.height(4.dp))

            if (live || speaking) {
                SiriWaveVisualizer(
                    amplitude = displayAmp,
                    modifier = Modifier
                        .fillMaxWidth(0.6f)
                        .height(26.dp),
                    activeColor1 = if (speaking) BrandPink else BrandCyan,
                    activeColor2 = BrandViolet
                )
            } else {
                Text(statusLine, color = TextSecondary, fontSize = 12.sp, letterSpacing = 0.3.sp)
            }
        }

        // ---- Chat Conversation or Suggestions ----
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (!conversationStarted) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(top = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Namaste 🙏", color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Bolo ya likho — main phone operate kar sakta hoon,\napps khol sakta hoon, aur sabhi tasks autonomously karta hoon.",
                        color = TextSecondary, fontSize = 12.5.sp, textAlign = TextAlign.Center, lineHeight = 17.sp
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            "⏰ 5:30 ka alarm lagao",
                            "🎵 YouTube par Lofi chalao",
                            "📞 Rahul ko call lagao",
                            "📶 WiFi band karo",
                            "👀 Screen pe kya hai?",
                            "💬 WhatsApp kholo"
                        ).forEach { chip ->
                            SuggestionChip(chip) { viewModel.sendText(chip.substringAfter(' ')) }
                        }
                    }

                    transcripts.lastOrNull { it.sender == "system" }?.let {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(it.text, color = TextMuted, fontSize = 11.sp, textAlign = TextAlign.Center)
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(transcripts, key = { it.id }) { item ->
                        TranscriptBubble(item)
                    }
                }
            }
        }

        // ---- Choices Prompt (show_choices) ----
        AnimatedVisibility(visible = choices != null) {
            val open = choices ?: return@AnimatedVisibility
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(SurfaceDark.copy(alpha = 0.95f))
                    .border(1.dp, BrandViolet.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(open.question, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("tap or say", color = TextMuted, fontSize = 11.sp)
                }
                open.options.forEachIndexed { i, option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Glass)
                            .border(1.dp, GlassBorder, RoundedCornerShape(12.dp))
                            .clickable { viewModel.pickChoice(option) }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(Brush.linearGradient(listOf(BrandCyan, BrandViolet))),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("${i + 1}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(option, color = TextPrimary, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        // ---- Text Input Box ----
        AnimatedVisibility(visible = typing) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Glass)
                    .border(1.dp, GlassBorder, RoundedCornerShape(24.dp))
                    .padding(start = 16.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    textStyle = TextStyle(color = TextPrimary, fontSize = 14.sp),
                    cursorBrush = SolidColor(BrandCyan),
                    modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                    decorationBox = { field ->
                        if (draft.isEmpty()) Text("Ask or command anything…", color = TextMuted, fontSize = 14.sp)
                        field()
                    }
                )
                IconButton(
                    onClick = { viewModel.sendText(draft); draft = "" },
                    enabled = draft.isNotBlank(),
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(if (draft.isNotBlank()) Brush.linearGradient(listOf(BrandCyan, BrandViolet)) else Brush.linearGradient(listOf(SurfaceCard, SurfaceCard)))
                ) {
                    Icon(Icons.Default.Send, contentDescription = "Send", tint = Color.White, modifier = Modifier.size(17.dp))
                }
            }
        }

        // ---- Bottom Voice Controls ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            RoundIconButton(if (typing) Icons.Default.Close else Icons.Default.Keyboard, "Type", size = 46.dp) { typing = !typing }

            // Big glowing mic button
            val pulse = rememberInfiniteTransition(label = "micPulse")
            val ring by pulse.animateFloat(1f, 1.35f, infiniteRepeatable(tween(1400), RepeatMode.Restart), label = "ring")
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(86.dp)) {
                if (live) {
                    Box(
                        modifier = Modifier
                            .size(68.dp)
                            .scale(ring)
                            .clip(CircleShape)
                            .background(BrandCyan.copy(alpha = (1.35f - ring) * 0.45f))
                    )
                }
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .shadow(if (live) 16.dp else 4.dp, CircleShape, ambientColor = BrandViolet, spotColor = BrandCyan)
                        .clip(CircleShape)
                        .background(
                            if (live) Brush.linearGradient(listOf(BrandCyan, BrandViolet, BrandPink))
                            else Brush.linearGradient(listOf(SurfaceCard, SurfaceDark))
                        )
                        .border(1.dp, if (live) Color.White.copy(alpha = 0.3f) else GlassBorder, CircleShape)
                        .clickable { viewModel.toggleSession() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (live) Icons.Default.GraphicEq else Icons.Default.Mic,
                        contentDescription = if (live) "Mute" else "Speak",
                        tint = Color.White,
                        modifier = Modifier.size(30.dp)
                    )
                }
            }

            RoundIconButton(
                Icons.Default.StopCircle, "Stop reply", size = 46.dp,
                tint = if (speaking) RedStop else TextMuted
            ) { viewModel.interruptAssistant() }
        }
    }
}

@Composable
fun TranscriptBubble(item: TranscriptItem) {
    when (item.sender) {
        "user" -> Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(
                text = item.text,
                color = TextPrimary,
                fontSize = 14.5.sp,
                lineHeight = 20.sp,
                modifier = Modifier
                    .widthIn(max = 290.dp)
                    .clip(RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                    .background(Brush.linearGradient(listOf(BrandCyan.copy(alpha = 0.22f), BrandViolet.copy(alpha = 0.22f))))
                    .border(1.dp, GlassBorder, RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                    .padding(horizontal = 14.dp, vertical = 9.dp)
            )
        }

        "assistant" -> Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(BrandCyan, BrandViolet, BrandPink)))
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = item.text,
                color = TextPrimary.copy(alpha = 0.94f),
                fontSize = 14.5.sp,
                lineHeight = 21.sp,
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp))
                    .background(Glass)
                    .border(1.dp, GlassBorder, RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp))
                    .padding(horizontal = 14.dp, vertical = 9.dp)
            )
        }

        "tool" -> {
            val body = item.text.removePrefix("Executed ")
            val name = body.substringBefore(' ').substringBefore(':').replace('_', ' ').replaceFirstChar { it.uppercase() }
            val detail = body.substringAfter(": ", "").take(80)
            Row(
                modifier = Modifier
                    .padding(start = 28.dp)
                    .clip(RoundedCornerShape(50))
                    .background(BrandViolet.copy(alpha = 0.12f))
                    .border(1.dp, BrandViolet.copy(alpha = 0.3f), RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Bolt, contentDescription = null, tint = BrandPink, modifier = Modifier.size(13.dp))
                Spacer(modifier = Modifier.width(5.dp))
                Text(name, color = TextPrimary, fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
                if (detail.isNotBlank()) {
                    Text(" · $detail", color = TextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        else -> Text(
            text = item.text,
            color = TextMuted,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
        )
    }
}

@Composable
fun RoundIconButton(
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
fun SuggestionChip(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        color = TextPrimary,
        fontSize = 12.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Glass)
            .border(1.dp, GlassBorder, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    )
}
