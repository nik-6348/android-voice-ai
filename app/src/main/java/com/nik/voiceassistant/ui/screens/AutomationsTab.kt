package com.nik.voiceassistant.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nik.voiceassistant.R
import com.nik.voiceassistant.VoiceAssistantViewModel
import com.nik.voiceassistant.data.macro.Action
import com.nik.voiceassistant.data.macro.Macro
import com.nik.voiceassistant.data.macro.MacroEngine
import com.nik.voiceassistant.data.macro.RecordedStep
import com.nik.voiceassistant.data.macro.Trigger
import com.nik.voiceassistant.ui.components.Badge
import com.nik.voiceassistant.ui.components.ConfirmDialog
import com.nik.voiceassistant.ui.components.EmptyState
import com.nik.voiceassistant.ui.components.ScreenHeader
import com.nik.voiceassistant.ui.theme.Spacing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * The automation running now. Kept outside the screen: the run outlives a rotation, and its
 * callbacks only touch this and the application context, never the activity.
 */
private object AutomationRun {
    data class Running(val macroId: String, val title: String, val progress: String)
    val state = MutableStateFlow<Running?>(null)
}

private val Macro.stepCount get() = (action as? Action.Replay)?.steps?.size ?: 0

@Composable
fun AutomationsTab(viewModel: VoiceAssistantViewModel) {
    val context = LocalContext.current
    val automations by viewModel.automations.collectAsState()
    val assistantName by viewModel.assistantName.collectAsState()
    val running by AutomationRun.state.collectAsState()
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }

    val shown = remember(automations, favoritesOnly) { if (favoritesOnly) automations.filter { it.isFavorite } else automations }
    val startingText = stringResource(R.string.automation_starting)

    Column(Modifier.fillMaxSize().padding(horizontal = Spacing.m)) {
        ScreenHeader(stringResource(R.string.automations_title), stringResource(R.string.automations_subtitle)) {
            FilledTonalButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.xs))
                Text(stringResource(R.string.action_new))
            }
        }

        Row(Modifier.fillMaxWidth().padding(bottom = Spacing.s), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            FilterChip(selected = !favoritesOnly, onClick = { favoritesOnly = false }, label = { Text(stringResource(R.string.filter_all)) })
            FilterChip(
                selected = favoritesOnly, onClick = { favoritesOnly = true },
                label = { Text(stringResource(R.string.automations_favorites)) },
                leadingIcon = { Icon(Icons.Filled.Star, contentDescription = null, modifier = Modifier.size(18.dp)) }
            )
        }

        if (shown.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.Bolt,
                title = stringResource(if (favoritesOnly) R.string.automations_no_favorites else R.string.automations_empty_title),
                message = stringResource(R.string.automations_empty_body),
                modifier = Modifier.weight(1f)
            ) {
                Button(onClick = { showCreateDialog = true }) { Text(stringResource(R.string.automations_create)) }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.s),
                contentPadding = PaddingValues(bottom = Spacing.l)
            ) {
                items(shown, key = { it.id }) { macro ->
                    AutomationCard(
                        macro = macro,
                        isRunning = running?.macroId == macro.id,
                        anyRunning = running != null,
                        onRun = {
                            val app = context.applicationContext
                            AutomationRun.state.value = AutomationRun.Running(macro.id, macro.title, startingText)
                            viewModel.runAutomation(
                                macro = macro,
                                onProgress = { step, total, desc ->
                                    AutomationRun.state.update { it?.copy(progress = app.getString(R.string.automation_step, step, total, desc)) }
                                },
                                onComplete = { result: MacroEngine.ReplayResult ->
                                    AutomationRun.state.value = null
                                    Toast.makeText(app, result.message, Toast.LENGTH_SHORT).show()
                                    // A failed replay means the screen moved on since this was recorded: offer to relearn it
                                    if (!result.ok) viewModel.recordAndExecuteAutomation(macro.instruction)
                                }
                            )
                        },
                        onToggleFavorite = { viewModel.toggleFavoriteAutomation(macro.id) },
                        onToggleEnabled = { viewModel.setAutomationEnabled(macro.id, it) },
                        onDelete = { deleteId = macro.id }
                    )
                }
            }
        }
    }

    running?.let { run ->
        AlertDialog(
            onDismissRequest = { /* stays while it runs */ },
            icon = { CircularProgressIndicator(Modifier.size(32.dp)) },
            title = { Text(stringResource(R.string.automation_running)) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text(run.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(Spacing.s))
                    Text(run.progress, style = MaterialTheme.typography.bodyMedium, maxLines = 3, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
            },
            confirmButton = {}
        )
    }

    deleteId?.let { id ->
        val title = automations.firstOrNull { it.id == id }?.title.orEmpty()
        ConfirmDialog(
            title = stringResource(R.string.automation_delete_title),
            message = stringResource(R.string.automation_delete_body, title),
            confirmLabel = stringResource(R.string.action_delete),
            dismissLabel = stringResource(R.string.action_cancel),
            icon = Icons.Outlined.DeleteOutline,
            onConfirm = { viewModel.deleteAutomation(id); deleteId = null },
            onDismiss = { deleteId = null }
        )
    }

    if (showCreateDialog) {
        val savedMsg = stringResource(R.string.automation_saved)
        val learningMsg = stringResource(R.string.automation_learning, assistantName)
        CreateAutomationDialog(
            assistantName = assistantName,
            onDismiss = { showCreateDialog = false },
            onSave = { macro ->
                viewModel.saveAutomation(macro)
                showCreateDialog = false
                Toast.makeText(context.applicationContext, savedMsg, Toast.LENGTH_SHORT).show()
            },
            onRecordWithAi = { instruction ->
                viewModel.recordAndExecuteAutomation(instruction)
                showCreateDialog = false
                Toast.makeText(context.applicationContext, learningMsg, Toast.LENGTH_LONG).show()
            }
        )
    }
}

@Composable
fun AutomationCard(
    macro: Macro,
    isRunning: Boolean,
    anyRunning: Boolean,
    onRun: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val (icon: ImageVector, container, content) = when {
        macro.action is Action.HandleCall -> Triple(Icons.Outlined.Call, scheme.secondaryContainer, scheme.onSecondaryContainer)
        macro.triggers.any { it is Trigger.Scheduled } -> Triple(Icons.Outlined.Alarm, scheme.errorContainer, scheme.onErrorContainer)
        macro.title.contains("music", true) || macro.title.contains("song", true) -> Triple(Icons.Outlined.MusicNote, scheme.tertiaryContainer, scheme.onTertiaryContainer)
        else -> Triple(Icons.Outlined.Bolt, scheme.primaryContainer, scheme.onPrimaryContainer)
    }
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(start = Spacing.m, top = Spacing.m, end = Spacing.s, bottom = Spacing.s)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = MaterialTheme.shapes.medium, color = container, contentColor = content, modifier = Modifier.size(48.dp)) {
                    Icon(icon, contentDescription = null, modifier = Modifier.padding(Spacing.s + 4.dp))
                }
                Spacer(Modifier.width(Spacing.m))
                Column(Modifier.weight(1f)) {
                    Text(macro.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        macro.description.ifBlank { macro.triggerSummary() }, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                }
                Switch(checked = macro.enabled, onCheckedChange = onToggleEnabled)
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        if (macro.isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = stringResource(if (macro.isFavorite) R.string.cd_unfavorite else R.string.cd_favorite, macro.title),
                        tint = if (macro.isFavorite) scheme.tertiary else scheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.size(Spacing.s))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                    if (macro.action is Action.Replay) Badge(pluralSteps(macro.stepCount))
                    if (macro.action is Action.HandleCall) Badge("📞 AI Call", container = scheme.secondaryContainer, content = scheme.onSecondaryContainer)
                    Badge(macro.triggerSummary(), container = scheme.tertiaryContainer, content = scheme.onTertiaryContainer, modifier = Modifier.weight(1f, fill = false))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = stringResource(R.string.cd_delete_automation, macro.title))
                }
                if (macro.action is Action.Replay) {
                    Button(onClick = onRun, enabled = !anyRunning) {
                        if (isRunning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(Spacing.xs))
                        Text(stringResource(R.string.action_run))
                    }
                } else if (macro.action is Action.HandleCall) {
                    Badge("Auto-answers", container = scheme.surfaceContainerHigh, content = scheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun pluralSteps(n: Int): String = pluralStringResource(R.plurals.automation_steps, n, n)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateAutomationDialog(
    assistantName: String,
    onDismiss: () -> Unit,
    onSave: (Macro) -> Unit,
    onRecordWithAi: (instruction: String) -> Unit
) {
    var aiMode by rememberSaveable { mutableStateOf(true) }
    var aiInstruction by rememberSaveable { mutableStateOf("") }
    var title by rememberSaveable { mutableStateOf("") }
    var trigger by rememberSaveable { mutableStateOf("") }
    var actionType by rememberSaveable { mutableStateOf("alarm") } // alarm, youtube, app, call
    var customAppName by rememberSaveable { mutableStateOf("") }
    var callerFilter by rememberSaveable { mutableStateOf("") }
    var callGoal by rememberSaveable { mutableStateOf("") }
    var callDiscloseAi by rememberSaveable { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.AutoAwesome, contentDescription = null) },
        title = { Text(stringResource(R.string.automation_new_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(selected = aiMode, onClick = { aiMode = true }, shape = SegmentedButtonDefaults.itemShape(0, 2)) {
                        Text(stringResource(R.string.automation_mode_ai))
                    }
                    SegmentedButton(selected = !aiMode, onClick = { aiMode = false }, shape = SegmentedButtonDefaults.itemShape(1, 2)) {
                        Text(stringResource(R.string.automation_mode_manual))
                    }
                }
                if (aiMode) {
                    Text(stringResource(R.string.automation_ai_desc, assistantName), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = aiInstruction,
                        onValueChange = { aiInstruction = it },
                        label = { Text(stringResource(R.string.automation_instruction)) },
                        placeholder = { Text("YouTube par Lofi chalao") },
                        minLines = 3, maxLines = 5,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Text(stringResource(R.string.automation_action), style = MaterialTheme.typography.labelLarge)
                    val types = listOf(
                        "alarm" to stringResource(R.string.automation_action_alarm),
                        "youtube" to stringResource(R.string.automation_action_youtube),
                        "app" to stringResource(R.string.automation_action_app),
                        "call" to stringResource(R.string.automation_action_call)
                    )
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        types.forEachIndexed { i, (type, label) ->
                            SegmentedButton(selected = actionType == type, onClick = { actionType = type }, shape = SegmentedButtonDefaults.itemShape(i, types.size)) {
                                Text(label, maxLines = 1)
                            }
                        }
                    }
                    if (actionType == "call") {
                        OutlinedTextField(
                            value = title, onValueChange = { title = it },
                            label = { Text(stringResource(R.string.automation_task_title)) },
                            placeholder = { Text("e.g. Screen unknown calls") },
                            singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = callerFilter, onValueChange = { callerFilter = it },
                            label = { Text(stringResource(R.string.automation_call_caller)) },
                            placeholder = { Text(stringResource(R.string.automation_call_caller_hint)) },
                            singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = callGoal, onValueChange = { callGoal = it },
                            label = { Text(stringResource(R.string.automation_call_goal)) },
                            placeholder = { Text(stringResource(R.string.automation_call_goal_hint)) },
                            minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth()
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.automation_call_disclose), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Switch(checked = callDiscloseAi, onCheckedChange = { callDiscloseAi = it })
                        }
                    } else {
                        OutlinedTextField(
                            value = title, onValueChange = { title = it },
                            label = { Text(stringResource(R.string.automation_task_title)) },
                            placeholder = { Text(stringResource(R.string.automation_task_title_hint)) },
                            singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = trigger, onValueChange = { trigger = it },
                            label = { Text(stringResource(R.string.automation_trigger)) },
                            placeholder = { Text("Raat ka alarm") },
                            singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                        if (actionType == "app") {
                            OutlinedTextField(
                                value = customAppName, onValueChange = { customAppName = it },
                                label = { Text(stringResource(R.string.automation_app_name)) },
                                placeholder = { Text("WhatsApp") },
                                singleLine = true, modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (aiMode) {
                Button(onClick = { onRecordWithAi(aiInstruction.trim()) }, enabled = aiInstruction.isNotBlank()) {
                    Text(stringResource(R.string.automation_do_and_record))
                }
            } else {
                val canSave = if (actionType == "call") callGoal.isNotBlank() else title.isNotBlank()
                Button(
                    enabled = canSave,
                    onClick = {
                        if (actionType == "call") {
                            val filter = callerFilter.trim().takeIf { it.isNotBlank() }
                            val goal = callGoal.trim()
                            val finalTitle = title.trim().ifBlank {
                                if (filter == null) "Screen all incoming calls" else "Call agent for $filter"
                            }
                            onSave(
                                Macro(
                                    title = finalTitle,
                                    description = "Answers matching calls and conducts AI conversation",
                                    triggers = listOf(Trigger.IncomingCall(contactFilter = filter, contactLabel = filter)),
                                    instruction = goal,
                                    action = Action.HandleCall(goal = goal, discloseAi = callDiscloseAi)
                                )
                            )
                        } else {
                            val app = customAppName.trim().ifBlank { "Chrome" }
                            val steps = when (actionType) {
                                "alarm" -> listOf(
                                    RecordedStep(
                                        toolName = "launch_intent",
                                        args = mapOf(
                                            "action" to "android.intent.action.SET_ALARM",
                                            // launch_intent takes extras as JSON text
                                            "extras" to """{"android.intent.extra.alarm.HOUR":6,"android.intent.extra.alarm.MINUTES":0,"android.intent.extra.alarm.SKIP_UI":true}"""
                                        ),
                                        description = "Set 6:00 AM Alarm"
                                    )
                                )
                                "youtube" -> listOf(RecordedStep(toolName = "open_app", args = mapOf("app_name" to "YouTube"), description = "Open YouTube"))
                                else -> listOf(RecordedStep(toolName = "open_app", args = mapOf("app_name" to app), description = "Open $app"))
                            }
                            val trig = trigger.trim().takeIf { it.isNotBlank() }
                            onSave(
                                Macro(
                                    title = title.trim(),
                                    description = "Manual automation shortcut",
                                    triggers = listOf(trig?.let { Trigger.Phrase(it) } ?: Trigger.Manual),
                                    instruction = title.trim(),
                                    action = Action.Replay(steps)
                                )
                            )
                        }
                    }
                ) { Text(stringResource(R.string.action_save)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}
