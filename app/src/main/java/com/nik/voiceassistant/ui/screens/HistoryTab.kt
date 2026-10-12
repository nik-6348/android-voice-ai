package com.nik.voiceassistant.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.nik.voiceassistant.VoiceAssistantViewModel
import com.nik.voiceassistant.data.history.ConversationSession
import com.nik.voiceassistant.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun HistoryTab(
    viewModel: VoiceAssistantViewModel,
    onNavigateToAssistant: () -> Unit
) {
    val historySessions by viewModel.historySessions.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var viewingSession by remember { mutableStateOf<ConversationSession?>(null) }
    var sessionToDelete by remember { mutableStateOf<ConversationSession?>(null) }

    val filteredSessions = remember(historySessions, searchQuery) {
        if (searchQuery.isBlank()) historySessions
        else {
            historySessions.filter { session ->
                session.title.contains(searchQuery, ignoreCase = true) ||
                    session.messages.any { it.text.contains(searchQuery, ignoreCase = true) }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        // ---- Header ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "Conversation History",
                    color = TextPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${historySessions.size} saved sessions",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }

            Button(
                onClick = {
                    viewModel.startNewChat()
                    onNavigateToAssistant()
                },
                colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                shape = RoundedCornerShape(20.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, tint = BgDark, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("New Chat", color = BgDark, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        // ---- Search Field ----
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search conversations…", color = TextMuted, fontSize = 13.sp) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextMuted) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear", tint = TextMuted)
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = CyanAccent,
                unfocusedBorderColor = GlassBorder,
                focusedContainerColor = SurfaceCard,
                unfocusedContainerColor = SurfaceCard,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary
            ),
            shape = RoundedCornerShape(14.dp),
            singleLine = true
        )

        // ---- History List ----
        if (filteredSessions.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Forum,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(54.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = if (searchQuery.isNotBlank()) "No conversations match '$searchQuery'" else "No conversation history yet",
                        color = TextSecondary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Talk to the assistant or start a new chat.",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            viewModel.startNewChat()
                            onNavigateToAssistant()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = BrandCyan),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Text("Start Talking", color = BgDark, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                items(filteredSessions, key = { it.id }) { session ->
                    HistorySessionCard(
                        session = session,
                        onContinue = {
                            viewModel.resumeChat(session.id)
                            onNavigateToAssistant()
                        },
                        onViewTranscript = { viewingSession = session },
                        onDelete = { sessionToDelete = session }
                    )
                }
            }
        }
    }

    // ---- Transcript Detail Dialog ----
    viewingSession?.let { session ->
        TranscriptViewerDialog(
            session = session,
            onDismiss = { viewingSession = null },
            onResumeChat = {
                viewingSession = null
                viewModel.resumeChat(session.id)
                onNavigateToAssistant()
            }
        )
    }

    // ---- Confirm Delete Dialog ----
    sessionToDelete?.let { session ->
        AlertDialog(
            onDismissRequest = { sessionToDelete = null },
            title = { Text("Delete Conversation?", color = TextPrimary) },
            text = { Text("Are you sure you want to delete '${session.title}'? This action cannot be undone.", color = TextSecondary) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteChat(session.id)
                        sessionToDelete = null
                    }
                ) {
                    Text("Delete", color = RedStop, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { sessionToDelete = null }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = SurfaceDark
        )
    }
}

@Composable
fun HistorySessionCard(
    session: ConversationSession,
    onContinue: () -> Unit,
    onViewTranscript: () -> Unit,
    onDelete: () -> Unit
) {
    val dateStr = remember(session.updatedAt) {
        val sdf = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
        sdf.format(Date(session.updatedAt))
    }
    val userMsgs = session.messages.count { it.sender == "user" }
    val lastMsg = session.messages.lastOrNull { it.sender == "user" || it.sender == "assistant" }?.text ?: "No messages"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
            .clickable { onViewTranscript() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = session.title,
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Text(
                    text = dateStr,
                    color = TextMuted,
                    fontSize = 11.sp
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = lastMsg,
                color = TextSecondary,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 16.sp
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Info badges
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .background(BrandViolet.copy(alpha = 0.2f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "$userMsgs queries",
                            color = BrandViolet,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    Text(
                        text = "${session.messages.size} total turns",
                        color = TextMuted,
                        fontSize = 10.sp
                    )
                }

                // Actions
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "Delete", tint = TextMuted, modifier = Modifier.size(16.dp))
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    Button(
                        onClick = onContinue,
                        colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = BgDark, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(2.dp))
                        Text("Continue", color = BgDark, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun TranscriptViewerDialog(
    session: ConversationSession,
    onDismiss: () -> Unit,
    onResumeChat: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(20.dp),
            color = SurfaceDark,
            border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorder)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(session.title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${session.messages.size} messages", color = TextSecondary, fontSize = 11.sp)
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                    }
                }

                HorizontalDivider(color = GlassBorder, modifier = Modifier.padding(vertical = 10.dp))

                // Transcript list
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(session.messages) { item ->
                        val isUser = item.sender == "user"
                        val isAssistant = item.sender == "assistant"
                        val isTool = item.sender == "tool"

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        when {
                                            isUser -> CyanAccent.copy(alpha = 0.15f)
                                            isAssistant -> SurfaceCard
                                            isTool -> BrandViolet.copy(alpha = 0.15f)
                                            else -> Glass
                                        }
                                    )
                                    .border(
                                        1.dp,
                                        when {
                                            isUser -> CyanAccent.copy(alpha = 0.4f)
                                            isTool -> BrandViolet.copy(alpha = 0.3f)
                                            else -> GlassBorder
                                        },
                                        RoundedCornerShape(12.dp)
                                    )
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Column {
                                    Text(
                                        text = when (item.sender) {
                                            "user" -> "You"
                                            "assistant" -> "Assistant"
                                            "tool" -> "Action / Tool"
                                            else -> "System"
                                        },
                                        color = when (item.sender) {
                                            "user" -> CyanAccent
                                            "assistant" -> GreenActive
                                            "tool" -> BrandViolet
                                            else -> TextMuted
                                        },
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = item.text,
                                        color = TextPrimary,
                                        fontSize = 13.sp,
                                        lineHeight = 17.sp
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = GlassBorder, modifier = Modifier.padding(vertical = 10.dp))

                // Dialog bottom actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = {
                            val fullText = session.messages.joinToString("\n\n") { "${it.sender.uppercase()}: ${it.text}" }
                            clipboard.setText(AnnotatedString(fullText))
                            copied = true
                        }
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (copied) "Copied!" else "Copy All", color = TextSecondary, fontSize = 12.sp)
                    }

                    Button(
                        onClick = onResumeChat,
                        colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = BgDark, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Continue in Chat", color = BgDark, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
