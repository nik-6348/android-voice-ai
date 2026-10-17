package com.nik.voiceassistant.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nik.voiceassistant.R
import com.nik.voiceassistant.TranscriptItem
import com.nik.voiceassistant.VoiceAssistantViewModel
import com.nik.voiceassistant.data.history.ConversationSession
import com.nik.voiceassistant.ui.components.Badge
import com.nik.voiceassistant.ui.components.ConfirmDialog
import com.nik.voiceassistant.ui.components.EmptyState
import com.nik.voiceassistant.ui.components.ScreenHeader
import com.nik.voiceassistant.ui.components.safeList
import com.nik.voiceassistant.ui.components.safeSender
import com.nik.voiceassistant.ui.components.safeString
import com.nik.voiceassistant.ui.components.safeText
import com.nik.voiceassistant.ui.components.transcriptKeys
import com.nik.voiceassistant.ui.theme.Spacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Sessions come from Gson: any field can be null at runtime, so they are read through these
private fun ConversationSession.safeTitle(fallback: String) = safeString(title).ifBlank { fallback }
private fun ConversationSession.safeMessages(): List<TranscriptItem> = safeList(messages)
private fun ConversationSession.safeId() = safeString(id)

@Composable
fun HistoryTab(
    viewModel: VoiceAssistantViewModel,
    onNavigateToAssistant: () -> Unit
) {
    val historySessions by viewModel.historySessions.collectAsState()
    val currentSessionId by viewModel.currentSessionId.collectAsState()
    val assistantName by viewModel.assistantName.collectAsState()
    var searchQuery by rememberSaveable { mutableStateOf("") }
    // Dialogs by session id, so they survive rotation
    var viewingId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDeleteAll by rememberSaveable { mutableStateOf(false) }
    val untitled = stringResource(R.string.history_untitled)

    val sessions = remember(historySessions) { historySessions.filter { it.safeId().isNotEmpty() }.distinctBy { it.safeId() } }
    val filtered = remember(sessions, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) sessions
        else sessions.filter { s ->
            s.safeTitle("").contains(q, ignoreCase = true) || s.safeMessages().any { it.safeText.contains(q, ignoreCase = true) }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = Spacing.m)) {
        ScreenHeader(
            title = stringResource(R.string.history_title),
            subtitle = pluralStringResource(R.plurals.history_count, sessions.size, sessions.size)
        ) {
            if (sessions.isNotEmpty()) {
                IconButton(onClick = { confirmDeleteAll = true }) {
                    Icon(Icons.Outlined.DeleteSweep, contentDescription = stringResource(R.string.delete_all_conversations))
                }
            }
            FilledTonalButton(onClick = { viewModel.startNewChat(); onNavigateToAssistant() }) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.xs))
                Text(stringResource(R.string.new_chat))
            }
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text(stringResource(R.string.history_search)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) IconButton(onClick = { searchQuery = "" }) {
                    Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.cd_clear_search))
                }
            },
            shape = CircleShape,
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.s)
        )

        if (filtered.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.Forum,
                title = if (searchQuery.isNotBlank()) stringResource(R.string.history_no_match, searchQuery) else stringResource(R.string.history_empty_title),
                message = stringResource(R.string.history_empty_body),
                modifier = Modifier.weight(1f)
            ) {
                Button(onClick = { viewModel.startNewChat(); onNavigateToAssistant() }) { Text(stringResource(R.string.action_start_talking)) }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.s),
                contentPadding = PaddingValues(bottom = Spacing.l)
            ) {
                items(filtered, key = { it.safeId() }) { session ->
                    HistorySessionCard(
                        session = session,
                        title = session.safeTitle(untitled),
                        isActive = session.safeId() == currentSessionId,
                        onContinue = { viewModel.resumeChat(session.safeId()); onNavigateToAssistant() },
                        onViewTranscript = { viewingId = session.safeId() },
                        onDelete = { deleteId = session.safeId() }
                    )
                }
            }
        }
    }

    viewingId?.let { id ->
        val session = sessions.firstOrNull { it.safeId() == id }
        if (session != null) TranscriptViewerDialog(
            session = session,
            title = session.safeTitle(untitled),
            assistantName = assistantName,
            onDismiss = { viewingId = null },
            onResumeChat = {
                viewingId = null
                viewModel.resumeChat(id)
                onNavigateToAssistant()
            }
        )
    }

    deleteId?.let { id ->
        val title = sessions.firstOrNull { it.safeId() == id }?.safeTitle(untitled) ?: untitled
        ConfirmDialog(
            title = stringResource(R.string.history_delete_title),
            message = stringResource(R.string.history_delete_body, title),
            confirmLabel = stringResource(R.string.action_delete),
            dismissLabel = stringResource(R.string.action_cancel),
            icon = Icons.Outlined.DeleteOutline,
            onConfirm = { viewModel.deleteChat(id); deleteId = null },
            onDismiss = { deleteId = null }
        )
    }

    if (confirmDeleteAll) {
        ConfirmDialog(
            title = stringResource(R.string.delete_all_conversations_title),
            message = stringResource(R.string.delete_all_conversations_body),
            confirmLabel = stringResource(R.string.action_delete_all),
            dismissLabel = stringResource(R.string.action_cancel),
            icon = Icons.Outlined.DeleteSweep,
            onConfirm = { viewModel.clearAllHistory(); confirmDeleteAll = false },
            onDismiss = { confirmDeleteAll = false }
        )
    }
}

@Composable
fun HistorySessionCard(
    session: ConversationSession,
    title: String,
    isActive: Boolean,
    onContinue: () -> Unit,
    onViewTranscript: () -> Unit,
    onDelete: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val messages = session.safeMessages()
    val dateStr = remember(session.updatedAt) { SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(session.updatedAt)) }
    val userMsgs = messages.count { it.safeSender == "user" }
    val lastMsg = messages.lastOrNull { it.safeSender == "user" || it.safeSender == "assistant" }?.safeText
        ?: stringResource(R.string.history_no_messages)

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = if (isActive) scheme.secondaryContainer else scheme.surfaceContainerLow),
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .clickable(onClickLabel = stringResource(R.string.action_view_transcript), onClick = onViewTranscript)
    ) {
        Column(Modifier.padding(start = Spacing.m, end = Spacing.s, top = Spacing.m, bottom = Spacing.s)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = Spacing.s)) {
                if (isActive) {
                    Badge(stringResource(R.string.history_current), container = scheme.primary, content = scheme.onPrimary)
                    Spacer(Modifier.width(Spacing.s))
                }
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(Spacing.s))
                Text(dateStr, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
            Spacer(Modifier.size(Spacing.xs))
            Text(
                lastMsg, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = Spacing.s)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    pluralStringResource(R.plurals.history_requests, userMsgs, userMsgs) + " · " + pluralStringResource(R.plurals.history_messages, messages.size, messages.size),
                    style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = stringResource(R.string.cd_delete_conversation, title))
                }
                TextButton(onClick = onContinue) {
                    Icon(if (isActive) Icons.AutoMirrored.Filled.Chat else Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Spacing.xs))
                    Text(stringResource(if (isActive) R.string.history_open else R.string.history_resume))
                }
            }
        }
    }
}

@Composable
fun TranscriptViewerDialog(
    session: ConversationSession,
    title: String,
    assistantName: String,
    onDismiss: () -> Unit,
    onResumeChat: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    val messages = session.safeMessages()
    val keys = remember(messages) { transcriptKeys(messages) }
    val scheme = MaterialTheme.colorScheme

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.88f),
            shape = MaterialTheme.shapes.extraLarge,
            color = scheme.surfaceContainerHigh
        ) {
            Column(Modifier.padding(Spacing.m)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(pluralStringResource(R.plurals.history_messages, messages.size, messages.size), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.action_close)) }
                }
                HorizontalDivider(Modifier.padding(vertical = Spacing.s))
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    itemsIndexed(messages, key = { i, _ -> keys.getOrElse(i) { "pos-$i" } }) { _, item ->
                        TranscriptBubble(item, assistantName)
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = Spacing.s))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        val fullText = messages.joinToString("\n\n") { "${it.safeSender.uppercase()}: ${it.safeText}" }
                        clipboard.setText(AnnotatedString(fullText))
                        copied = true
                    }) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(Spacing.xs))
                        Text(stringResource(if (copied) R.string.copied_all else R.string.action_copy_all))
                    }
                    Box(Modifier.weight(1f))
                    Button(onClick = onResumeChat) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(Spacing.xs))
                        Text(stringResource(R.string.history_continue))
                    }
                }
            }
        }
    }
}
