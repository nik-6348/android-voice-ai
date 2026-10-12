package com.nik.voiceassistant.ui.screens

import android.widget.Toast
import androidx.compose.animation.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.nik.voiceassistant.VoiceAssistantViewModel
import com.nik.voiceassistant.data.automation.AutomationStep
import com.nik.voiceassistant.data.automation.AutomationTask
import com.nik.voiceassistant.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun AutomationsTab(
    viewModel: VoiceAssistantViewModel
) {
    val context = LocalContext.current
    val automations by viewModel.automations.collectAsState()
    var selectedFilter by remember { mutableStateOf("All") }
    var isRunningTask by remember { mutableStateOf<AutomationTask?>(null) }
    var runningProgress by remember { mutableStateOf("") }
    var showCreateDialog by remember { mutableStateOf(false) }

    val filteredTasks = remember(automations, selectedFilter) {
        when (selectedFilter) {
            "Favorites" -> automations.filter { it.isFavorite }
            else -> automations
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
                    text = "Task Automations",
                    color = TextPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Execute multi-step tasks in 1 tap or voice",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }

            Button(
                onClick = { showCreateDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                shape = RoundedCornerShape(20.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, tint = BgDark, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("New", color = BgDark, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        // ---- Filters ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("All", "Favorites").forEach { filter ->
                val selected = selectedFilter == filter
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (selected) CyanAccent.copy(alpha = 0.2f) else SurfaceCard)
                        .border(1.dp, if (selected) CyanAccent else GlassBorder, RoundedCornerShape(16.dp))
                        .clickable { selectedFilter = filter }
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = filter,
                        color = if (selected) CyanAccent else TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }

        // ---- Automations List ----
        if (filteredTasks.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Bolt,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(54.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "No automations found",
                        color = TextSecondary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Create a custom task to automate repeated steps.",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                items(filteredTasks, key = { it.id }) { task ->
                    AutomationCard(
                        task = task,
                        isCurrentlyRunning = isRunningTask?.id == task.id,
                        onRun = {
                            isRunningTask = task
                            runningProgress = "Starting ${task.title}…"
                            viewModel.executeAutomation(
                                task = task,
                                onProgress = { step, total, desc ->
                                    runningProgress = "Step $step/$total: $desc"
                                },
                                onComplete = { success, msg ->
                                    isRunningTask = null
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                }
                            )
                        },
                        onToggleFavorite = { viewModel.toggleFavoriteAutomation(task.id) },
                        onDelete = { viewModel.deleteAutomation(task.id) }
                    )
                }
            }
        }
    }

    // ---- Running Progress Dialog ----
    isRunningTask?.let { task ->
        Dialog(onDismissRequest = { /* Don't dismiss while running */ }) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = SurfaceDark,
                border = androidx.compose.foundation.BorderStroke(1.dp, CyanAccent.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth(0.9f)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(color = CyanAccent, modifier = Modifier.size(44.dp))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Running Automation", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(task.title, color = CyanAccent, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(runningProgress, color = TextSecondary, fontSize = 12.sp, maxLines = 2)
                }
            }
        }
    }

    // ---- Create Custom Automation Dialog ----
    if (showCreateDialog) {
        CreateAutomationDialog(
            onDismiss = { showCreateDialog = false },
            onSave = { newTask ->
                viewModel.saveAutomation(newTask)
                showCreateDialog = false
                Toast.makeText(context, "Automation saved!", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

@Composable
fun AutomationCard(
    task: AutomationTask,
    isCurrentlyRunning: Boolean,
    onRun: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, GlassBorder, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Icon
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            when (task.iconName) {
                                "alarm" -> Color(0xFFEF4444).copy(alpha = 0.2f)
                                "music" -> BrandViolet.copy(alpha = 0.2f)
                                else -> CyanAccent.copy(alpha = 0.2f)
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when (task.iconName) {
                            "alarm" -> Icons.Default.Alarm
                            "music" -> Icons.Default.MusicNote
                            else -> Icons.Default.Bolt
                        },
                        contentDescription = null,
                        tint = when (task.iconName) {
                            "alarm" -> Color(0xFFEF4444)
                            "music" -> BrandViolet
                            else -> CyanAccent
                        },
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.title,
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = task.description,
                        color = TextSecondary,
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Favorite
                IconButton(onClick = onToggleFavorite, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = if (task.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = "Favorite",
                        tint = if (task.isFavorite) Color(0xFFFBBF24) else TextMuted,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Step details & trigger info
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .background(Glass, RoundedCornerShape(6.dp))
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "${task.steps.size} ${if (task.steps.size == 1) "step" else "steps"}",
                            color = TextMuted,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    if (task.triggerPhrase.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .background(BrandCyan.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "“${task.triggerPhrase}”",
                                color = BrandCyan,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                // Action buttons
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "Delete", tint = TextMuted, modifier = Modifier.size(16.dp))
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    Button(
                        onClick = onRun,
                        enabled = !isCurrentlyRunning,
                        colors = ButtonDefaults.buttonColors(containerColor = GreenActive),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = BgDark, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(2.dp))
                        Text("RUN", color = BgDark, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun CreateAutomationDialog(
    onDismiss: () -> Unit,
    onSave: (AutomationTask) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var trigger by remember { mutableStateOf("") }
    var selectedActionType by remember { mutableStateOf("alarm") } // alarm, youtube, status, app
    var customAppName by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = SurfaceDark,
            border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text("New Task Automation", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Task Title", color = TextSecondary) },
                    placeholder = { Text("e.g. Set Night Alarm", color = TextMuted) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                        focusedBorderColor = CyanAccent, unfocusedBorderColor = GlassBorder,
                        focusedContainerColor = SurfaceCard, unfocusedContainerColor = SurfaceCard
                    ),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description", color = TextSecondary) },
                    placeholder = { Text("What does this do?", color = TextMuted) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                        focusedBorderColor = CyanAccent, unfocusedBorderColor = GlassBorder,
                        focusedContainerColor = SurfaceCard, unfocusedContainerColor = SurfaceCard
                    ),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = trigger,
                    onValueChange = { trigger = it },
                    label = { Text("Voice Trigger (Optional)", color = TextSecondary) },
                    placeholder = { Text("e.g. Raat ka alarm", color = TextMuted) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                        focusedBorderColor = CyanAccent, unfocusedBorderColor = GlassBorder,
                        focusedContainerColor = SurfaceCard, unfocusedContainerColor = SurfaceCard
                    ),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text("Action Type", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("alarm" to "Alarm", "youtube" to "YouTube", "app" to "Open App").forEach { (type, label) ->
                        val selected = selectedActionType == type
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selected) CyanAccent.copy(alpha = 0.2f) else SurfaceCard)
                                .border(1.dp, if (selected) CyanAccent else GlassBorder, RoundedCornerShape(10.dp))
                                .clickable { selectedActionType = type }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(label, color = if (selected) CyanAccent else TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                if (selectedActionType == "app") {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customAppName,
                        onValueChange = { customAppName = it },
                        label = { Text("App Name", color = TextSecondary) },
                        placeholder = { Text("e.g. WhatsApp, Camera", color = TextMuted) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                            focusedBorderColor = CyanAccent, unfocusedBorderColor = GlassBorder,
                            focusedContainerColor = SurfaceCard, unfocusedContainerColor = SurfaceCard
                        ),
                        singleLine = true
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = TextSecondary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (title.isBlank()) return@Button
                            val steps = when (selectedActionType) {
                                "alarm" -> listOf(
                                    AutomationStep(
                                        toolName = "launch_intent",
                                        args = mapOf(
                                            "action" to "android.intent.action.SET_ALARM",
                                            "extras" to mapOf("android.intent.extra.alarm.HOUR" to 6, "android.intent.extra.alarm.MINUTES" to 0, "android.intent.extra.alarm.SKIP_UI" to true)
                                        ),
                                        description = "Set 6:00 AM Alarm"
                                    )
                                )
                                "youtube" -> listOf(
                                    AutomationStep(
                                        toolName = "open_app",
                                        args = mapOf("app_name" to "YouTube"),
                                        description = "Open YouTube"
                                    )
                                )
                                else -> listOf(
                                    AutomationStep(
                                        toolName = "open_app",
                                        args = mapOf("app_name" to customAppName.ifBlank { "Chrome" }),
                                        description = "Open ${customAppName.ifBlank { "Chrome" }}"
                                    )
                                )
                            }
                            onSave(
                                AutomationTask(
                                    title = title,
                                    description = description.ifBlank { "Automated step sequence" },
                                    triggerPhrase = trigger,
                                    iconName = if (selectedActionType == "alarm") "alarm" else "bolt",
                                    steps = steps
                                )
                            )
                        },
                        enabled = title.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Save Automation", color = BgDark, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
