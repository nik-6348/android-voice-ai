package com.nik.voiceassistant.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nik.voiceassistant.ConnectionStatus
import com.nik.voiceassistant.R
import com.nik.voiceassistant.TranscriptItem
import com.nik.voiceassistant.VoiceAssistantViewModel
import com.nik.voiceassistant.ui.components.OrbMode
import com.nik.voiceassistant.ui.components.PlanCard
import com.nik.voiceassistant.ui.components.VoiceBars
import com.nik.voiceassistant.ui.components.VoiceOrb
import com.nik.voiceassistant.ui.components.activeWithHold
import com.nik.voiceassistant.ui.components.rememberFrameClock
import com.nik.voiceassistant.ui.components.rememberVoiceLevel
import com.nik.voiceassistant.ui.components.safeList
import com.nik.voiceassistant.ui.components.safeSender
import com.nik.voiceassistant.ui.components.safeString
import com.nik.voiceassistant.ui.components.safeText
import com.nik.voiceassistant.ui.components.transcriptKeys
import com.nik.voiceassistant.ui.theme.Spacing
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter

/** What the assistant is doing, for the status line and the orb. */
private enum class Phase(val orb: OrbMode) {
    IDLE(OrbMode.IDLE), CONNECTING(OrbMode.CONNECTING), ERROR(OrbMode.ERROR), LISTENING(OrbMode.LISTENING),
    MUTED(OrbMode.MUTED), THINKING(OrbMode.THINKING), WORKING(OrbMode.THINKING), SPEAKING(OrbMode.SPEAKING)
}

@Composable
fun AssistantLiveTab(
    viewModel: VoiceAssistantViewModel,
    onNavigateToSettings: () -> Unit
) {
    val context = LocalContext.current
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val transcripts by viewModel.transcripts.collectAsState()
    val isAgentEnabled by viewModel.isAgentEnabled.collectAsState()
    val isWakeWordEnabled by viewModel.isWakeWordEnabled.collectAsState()
    val choices by viewModel.choices.collectAsState()
    val assistantName by viewModel.assistantName.collectAsState()
    val sessionTitle by viewModel.currentSessionTitle.collectAsState()
    val sessionId by viewModel.currentSessionId.collectAsState()
    val isMicMuted by viewModel.isMicMuted.collectAsState()
    val toolStatus by viewModel.activeToolStatus.collectAsState()
    val plan by viewModel.plan.collectAsState()

    // Voice levels are only read while drawing (orb, bars): the screen doesn't recompose with them
    val levelSource = remember(viewModel) {
        combine(viewModel.micAmplitude, viewModel.aiAmplitude, viewModel.isMicMuted) { mic, ai, muted -> maxOf(ai, if (muted) 0f else mic) }
    }
    val level = rememberVoiceLevel(levelSource)
    val speaking by remember(viewModel) { viewModel.aiAmplitude.activeWithHold() }.collectAsState(initial = false)
    val userTalking by remember(viewModel) { viewModel.micAmplitude.activeWithHold(0.03f, 600) }.collectAsState(initial = false)

    val conversation = remember(transcripts) { transcripts.filter { it.safeSender == "user" || it.safeSender == "assistant" } }
    val conversationStarted = conversation.isNotEmpty()
    val lastSpeaker = conversation.lastOrNull()?.safeSender
    val lastSystem = remember(transcripts) { transcripts.lastOrNull { it.safeSender == "system" }?.safeText }

    val phase = when {
        connectionStatus == ConnectionStatus.CONNECTING -> Phase.CONNECTING
        connectionStatus == ConnectionStatus.ERROR -> Phase.ERROR
        toolStatus != null -> Phase.WORKING
        speaking -> Phase.SPEAKING
        isRecording && !userTalking && (plan?.status == "working" || lastSpeaker == "user") -> Phase.THINKING
        isRecording && isMicMuted -> Phase.MUTED
        isRecording -> Phase.LISTENING
        else -> Phase.IDLE
    }
    val statusText = when (phase) {
        Phase.CONNECTING -> stringResource(R.string.status_connecting)
        Phase.ERROR -> stringResource(R.string.status_error)
        Phase.WORKING -> stringResource(R.string.status_working_on, toolStatus.orEmpty())
        Phase.SPEAKING -> stringResource(R.string.status_speaking)
        Phase.THINKING -> stringResource(R.string.status_thinking)
        Phase.MUTED -> stringResource(R.string.status_muted)
        Phase.LISTENING -> stringResource(R.string.status_listening)
        Phase.IDLE -> if (isWakeWordEnabled) stringResource(R.string.status_idle_wake, assistantName) else stringResource(R.string.status_idle)
    }
    val clock = rememberFrameClock(phase.orb.animates)

    // Muted: turn the mic back on (starting a conversation if none); else start / end the conversation
    val onMainButton: () -> Unit = {
        if (isMicMuted) {
            viewModel.setMicMuted(false)
            if (!isRecording) viewModel.startSession()
        } else viewModel.toggleSession()
    }
    val mainLabel = stringResource(
        when {
            isMicMuted && isRecording -> R.string.cd_unmute
            isRecording -> R.string.cd_end_conversation
            else -> R.string.cd_start_conversation
        }
    )

    Column(Modifier.fillMaxSize()) {
        TopBar(
            name = assistantName,
            subtitle = if (sessionId == null) stringResource(R.string.new_chat) else sessionTitle,
            onNewChat = { viewModel.startNewChat() }
        )

        AnimatedVisibility(visible = !isAgentEnabled, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            InfoBanner(
                icon = Icons.Outlined.TouchApp,
                title = stringResource(R.string.screen_control_off_title),
                message = stringResource(R.string.screen_control_off_body, assistantName),
                action = stringResource(R.string.action_turn_on),
                onAction = {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (!conversationStarted) {
                Hero(
                    phase = phase,
                    statusText = statusText,
                    orbLabel = mainLabel,
                    level = level,
                    clock = clock,
                    lastSystem = lastSystem,
                    onOrbClick = onMainButton,
                    onSuggestion = { viewModel.sendText(it) }
                )
            } else {
                Column(Modifier.fillMaxSize()) {
                    CompactStatus(phase, statusText, mainLabel, level, clock, onMainButton)
                    plan?.takeIf { it.status != "done" }?.let { p ->
                        PlanCard(p, toolStatus = null, modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.xs))
                    }
                    Transcript(transcripts, assistantName, Modifier.weight(1f))
                }
            }
        }

        AnimatedVisibility(visible = phase == Phase.ERROR, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            ErrorBanner(
                detail = lastSystem,
                needsKey = !viewModel.hasCredentials(),
                onRetry = { viewModel.startSession() },
                onSettings = onNavigateToSettings
            )
        }

        AnimatedVisibility(visible = choices != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            choices?.let { open ->
                ChoicesCard(safeString(open.question), safeList<String>(open.options)) { viewModel.pickChoice(it) }
            }
        }

        Composer(
            name = assistantName,
            isRecording = isRecording,
            isMicMuted = isMicMuted,
            speaking = speaking,
            onSend = { viewModel.sendText(it) },
            onToggleMute = { viewModel.toggleMicMute() },
            onMain = onMainButton,
            onStopReply = { viewModel.interruptAssistant() }
        )
    }
}

@Composable
private fun TopBar(name: String, subtitle: String, onNewChat: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = Spacing.m, end = Spacing.xs, top = Spacing.s, bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onNewChat) {
            Icon(Icons.Outlined.AddComment, contentDescription = stringResource(R.string.new_chat))
        }
    }
}

@Composable
private fun InfoBanner(icon: ImageVector, title: String, message: String, action: String, onAction: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.m, vertical = Spacing.xs),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        )
    ) {
        Row(Modifier.padding(start = Spacing.m, top = Spacing.s, bottom = Spacing.s, end = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(Spacing.m))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(message, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun ErrorBanner(detail: String?, needsKey: Boolean, onRetry: () -> Unit, onSettings: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.m, vertical = Spacing.xs),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        )
    ) {
        Column(Modifier.padding(Spacing.m)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.ErrorOutline, contentDescription = null)
                Spacer(Modifier.width(Spacing.s))
                Text(stringResource(R.string.error_connect_title), style = MaterialTheme.typography.titleSmall)
            }
            Text(
                if (needsKey) stringResource(R.string.error_needs_key) else detail ?: stringResource(R.string.error_connect_body),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = Spacing.xs)
            )
            Row(Modifier.fillMaxWidth().padding(top = Spacing.s), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onSettings) { Text(stringResource(R.string.action_open_settings)) }
                Spacer(Modifier.width(Spacing.s))
                Button(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
            }
        }
    }
}

/** Before the first words: a big calm orb, the status, and a few ideas to try. */
@Composable
private fun Hero(
    phase: Phase,
    statusText: String,
    orbLabel: String,
    level: State<Float>,
    clock: State<Float>,
    lastSystem: String?,
    onOrbClick: () -> Unit,
    onSuggestion: (String) -> Unit
) = BoxWithConstraints(Modifier.fillMaxSize()) {
    // Smaller orb on short screens; scrolls rather than clipping when it still doesn't fit
    val orbSize = (maxHeight * 0.38f).coerceIn(120.dp, 208.dp)
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(vertical = Spacing.s),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        VoiceOrb(
            level = level,
            mode = phase.orb,
            clock = clock,
            modifier = Modifier
                .size(orbSize)
                .clip(CircleShape)
                .clickable(onClickLabel = orbLabel, role = Role.Button, onClick = onOrbClick)
                .semantics { contentDescription = orbLabel }
        )
        Spacer(Modifier.size(Spacing.m))
        Text(
            statusText,
            style = MaterialTheme.typography.titleMedium,
            color = if (phase == Phase.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = Spacing.l).semantics { liveRegion = LiveRegionMode.Polite }
        )
        Spacer(Modifier.size(Spacing.l))
        Text(stringResource(R.string.hero_greeting), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.size(Spacing.xs))
        Text(
            stringResource(R.string.hero_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = Spacing.xl)
        )
        Spacer(Modifier.size(Spacing.m))
        val suggestions = listOf(
            "5:30 ka alarm lagao", "YouTube par Lofi chalao", "Rahul ko call lagao",
            "WiFi band karo", "Screen pe kya hai?", "WhatsApp kholo"
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = Spacing.m),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s)
        ) {
            items(suggestions) { s ->
                SuggestionChip(onClick = { onSuggestion(s) }, label = { Text(s) })
            }
        }
        if (!lastSystem.isNullOrBlank() && phase != Phase.ERROR) {
            Spacer(Modifier.size(Spacing.s))
            Text(
                lastSystem, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = Spacing.l)
            )
        }
    }
}

/** During a conversation: a small orb and the status, above the plan and the chat. */
@Composable
private fun CompactStatus(
    phase: Phase,
    statusText: String,
    orbLabel: String,
    level: State<Float>,
    clock: State<Float>,
    onOrbClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.m, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        VoiceOrb(
            level = level,
            mode = phase.orb,
            clock = clock,
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .clickable(onClickLabel = orbLabel, role = Role.Button, onClick = onOrbClick)
                .semantics { contentDescription = orbLabel }
        )
        Spacer(Modifier.width(Spacing.s))
        Text(
            statusText,
            style = MaterialTheme.typography.titleSmall,
            color = if (phase == Phase.ERROR) scheme.error else scheme.onSurface,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }
        )
        VoiceBars(
            level = level,
            active = phase == Phase.SPEAKING || phase == Phase.LISTENING,
            clock = clock,
            colors = listOf(scheme.primary, scheme.tertiary, scheme.secondary, scheme.primary),
            idleColor = scheme.outlineVariant,
            modifier = Modifier.clearAndSetSemantics {}
        )
    }
}

/** The chat: bubbles for the user and the assistant, small rows for actions and system notes. */
@Composable
private fun Transcript(transcripts: List<TranscriptItem>, assistantName: String, modifier: Modifier) {
    val listState = rememberLazyListState()
    val keys = remember(transcripts) { transcriptKeys(transcripts) }
    val lastKey = keys.lastOrNull()
    val lastLength = transcripts.lastOrNull()?.safeText?.length ?: 0

    // Follow the newest words unless the user scrolled up to read
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.filter { !it }.collect { follow = !listState.canScrollForward }
    }
    val following by rememberUpdatedState(follow)
    // Keyed on the last item (the list is capped, so its size stops changing) ...
    LaunchedEffect(lastKey) {
        if (keys.isNotEmpty()) {
            listState.animateScrollToItem(keys.size - 1)
            listState.scrollBy(100_000f)
        }
    }
    // ... and on a reply that grows in place
    LaunchedEffect(lastLength) {
        if (following && keys.isNotEmpty()) listState.scrollBy(100_000f)
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = Spacing.m, vertical = Spacing.s),
        verticalArrangement = Arrangement.spacedBy(Spacing.s)
    ) {
        itemsIndexed(transcripts, key = { i, _ -> keys.getOrElse(i) { "pos-$i" } }) { _, item ->
            TranscriptBubble(item, assistantName)
        }
    }
}

@Composable
fun TranscriptBubble(item: TranscriptItem, assistantName: String) {
    val scheme = MaterialTheme.colorScheme
    val text = item.safeText
    when (item.safeSender) {
        "user" -> {
            val label = stringResource(R.string.cd_you_said, text)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Surface(
                    color = scheme.primaryContainer,
                    contentColor = scheme.onPrimaryContainer,
                    shape = RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp),
                    modifier = Modifier.widthIn(max = 300.dp).semantics(mergeDescendants = true) { contentDescription = label }
                ) {
                    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = Spacing.m, vertical = 10.dp))
                }
            }
        }

        "assistant" -> {
            val label = stringResource(R.string.cd_assistant_said, assistantName, text)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                Surface(
                    color = scheme.surfaceContainerHigh,
                    contentColor = scheme.onSurface,
                    shape = RoundedCornerShape(4.dp, 20.dp, 20.dp, 20.dp),
                    modifier = Modifier.widthIn(max = 320.dp).semantics(mergeDescendants = true) { contentDescription = label }
                ) {
                    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = Spacing.m, vertical = 10.dp))
                }
            }
        }

        "tool" -> {
            var open by rememberSaveable(item.id) { mutableStateOf(false) }
            val body = text.removePrefix("Executed ")
            val name = body.substringBefore(' ').substringBefore(':').replace('_', ' ').replaceFirstChar { it.uppercase() }
            val detail = body.substringAfter(": ", "")
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 32.dp)
                    .clip(MaterialTheme.shapes.small)
                    .clickable(onClickLabel = stringResource(if (open) R.string.action_collapse else R.string.action_expand)) { open = !open }
                    .padding(horizontal = Spacing.s, vertical = Spacing.xs),
                verticalAlignment = Alignment.Top
            ) {
                Icon(Icons.Outlined.Bolt, contentDescription = null, tint = scheme.tertiary, modifier = Modifier.padding(top = 1.dp).size(16.dp))
                Spacer(Modifier.width(Spacing.s))
                Text(
                    if (detail.isBlank()) name else "$name · $detail",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                    maxLines = if (open) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        else -> Row(
            Modifier.fillMaxWidth().padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.Info, contentDescription = null, tint = scheme.outline, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(Spacing.xs))
            Text(text, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun ChoicesCard(question: String, options: List<String>, onPick: (String) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.m, vertical = Spacing.xs),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(Modifier.padding(Spacing.m).animateContentSize(), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(question, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.choices_hint), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            options.forEachIndexed { i, option ->
                OutlinedButton(
                    onClick = { onPick(option) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = Spacing.m, vertical = Spacing.s)
                ) {
                    Text("${i + 1}.  $option", modifier = Modifier.fillMaxWidth(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Text box with send, then the mic controls: mute, start / end, stop the reply. */
@Composable
private fun Composer(
    name: String,
    isRecording: Boolean,
    isMicMuted: Boolean,
    speaking: Boolean,
    onSend: (String) -> Unit,
    onToggleMute: () -> Unit,
    onMain: () -> Unit,
    onStopReply: () -> Unit
) {
    var draft by rememberSaveable { mutableStateOf("") }
    val send = {
        val text = draft.trim()
        if (text.isNotEmpty()) {
            onSend(text)
            draft = ""
        }
    }
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = scheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs)
    ) {
        Column(Modifier.padding(start = Spacing.m, end = Spacing.m, top = Spacing.m, bottom = Spacing.s)) {
            TextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.composer_hint, name)) },
                maxLines = 4,
                shape = CircleShape,
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    focusedContainerColor = scheme.surfaceContainerHighest,
                    unfocusedContainerColor = scheme.surfaceContainerHighest
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                trailingIcon = {
                    FilledIconButton(onClick = send, enabled = draft.isNotBlank()) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.cd_send))
                    }
                }
            )
            Spacer(Modifier.size(Spacing.s))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Top) {
                ControlButton(
                    icon = if (isMicMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                    label = stringResource(if (isMicMuted) R.string.control_unmute else R.string.control_mute),
                    description = stringResource(if (isMicMuted) R.string.cd_unmute else R.string.cd_mute),
                    highlighted = isMicMuted,
                    onClick = onToggleMute
                )
                MainMicButton(isRecording, isMicMuted, onMain)
                ControlButton(
                    icon = Icons.Filled.StopCircle,
                    label = stringResource(R.string.control_stop_reply),
                    description = stringResource(R.string.cd_stop_reply),
                    highlighted = speaking,
                    enabled = isRecording,
                    onClick = onStopReply
                )
            }
        }
    }
}

@Composable
private fun ControlButton(
    icon: ImageVector,
    label: String,
    description: String,
    highlighted: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    val scheme = MaterialTheme.colorScheme
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(88.dp)) {
        FilledTonalIconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(56.dp),
            colors = if (highlighted) IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = scheme.tertiaryContainer, contentColor = scheme.onTertiaryContainer
            ) else IconButtonDefaults.filledTonalIconButtonColors()
        ) {
            Icon(icon, contentDescription = description)
        }
        Text(
            label, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.xs).clearAndSetSemantics {}
        )
    }
}

@Composable
private fun MainMicButton(isRecording: Boolean, isMicMuted: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val (icon, label, description) = when {
        !isRecording -> Triple(Icons.Filled.Mic, stringResource(R.string.control_start), stringResource(R.string.cd_start_conversation))
        isMicMuted -> Triple(Icons.Filled.Mic, stringResource(R.string.control_talk), stringResource(R.string.cd_unmute))
        else -> Triple(Icons.Filled.Close, stringResource(R.string.control_end), stringResource(R.string.cd_end_conversation))
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(96.dp)) {
        FilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(72.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = if (isRecording && !isMicMuted) scheme.primary else scheme.primaryContainer,
                contentColor = if (isRecording && !isMicMuted) scheme.onPrimary else scheme.onPrimaryContainer
            )
        ) {
            Icon(icon, contentDescription = description, modifier = Modifier.size(32.dp))
        }
        Text(
            label, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.xs).clearAndSetSemantics {}
        )
    }
}
