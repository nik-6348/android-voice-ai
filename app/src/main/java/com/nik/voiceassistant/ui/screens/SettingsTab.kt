package com.nik.voiceassistant.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.nik.voiceassistant.R
import com.nik.voiceassistant.SecurePrefs
import com.nik.voiceassistant.VoiceAssistantViewModel
import com.nik.voiceassistant.VoiceSession
import com.nik.voiceassistant.data.trace.AgentTrace
import com.nik.voiceassistant.data.trace.Tracer
import com.nik.voiceassistant.security.AgentPolicy
import com.nik.voiceassistant.service.PaymentGuard
import com.nik.voiceassistant.service.WebhookServer
import androidx.compose.material.icons.outlined.Webhook
import androidx.compose.material.icons.outlined.ContentCopy
import com.nik.voiceassistant.ui.components.Badge
import com.nik.voiceassistant.ui.components.ConfirmDialog
import com.nik.voiceassistant.ui.components.RadioRow
import com.nik.voiceassistant.ui.components.ScreenHeader
import com.nik.voiceassistant.ui.components.SectionCard
import com.nik.voiceassistant.ui.components.SwitchRow
import com.nik.voiceassistant.ui.components.rememberResumeCount
import com.nik.voiceassistant.ui.theme.NikTheme
import com.nik.voiceassistant.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings, in sections: the assistant itself, the AI model, connection & keys, safety & privacy,
 * screen unlock, payment apps and system permissions. Switches apply at once; the model, voice and
 * connection form is applied with "Save" (a bar appears when something changed) and keeps the
 * user's edits when saving fails, with a message saying what to fix.
 */
@Composable
fun SettingsTab(viewModel: VoiceAssistantViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val currentModel by viewModel.selectedModel.collectAsState()
    val currentVoice by viewModel.selectedVoice.collectAsState()
    val currentSearch by viewModel.isSearchEnabled.collectAsState()
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
    val policy by viewModel.policy.collectAsState()
    val isAgentEnabled by viewModel.isAgentEnabled.collectAsState()
    val otlpEndpoint by Tracer.otlpEndpoint.collectAsState()

    // The form applied with Save (kept across rotation and tab switches; keys are never saved)
    var model by rememberSaveable { mutableStateOf(currentModel) }
    var voice by rememberSaveable { mutableStateOf(currentVoice) }
    var searchEnabled by rememberSaveable { mutableStateOf(currentSearch) }
    var serverUrl by rememberSaveable { mutableStateOf(currentServerUrl) }
    var mode by rememberSaveable { mutableStateOf(currentMode) }
    var llmBase by rememberSaveable { mutableStateOf(currentLlmBase) }
    var vision by rememberSaveable { mutableStateOf(currentVision) }
    // Key fields start empty: a saved key is never shown again; typing a new one replaces it
    var geminiKey by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var showKeys by remember { mutableStateOf(false) }
    var saveError by rememberSaveable { mutableStateOf<VoiceSession.SaveResult?>(null) }

    val dirty = model != currentModel || voice != currentVoice || searchEnabled != currentSearch ||
        serverUrl != currentServerUrl || mode != currentMode || llmBase != currentLlmBase || vision != currentVision ||
        geminiKey.isNotBlank() || apiKey.isNotBlank()

    // Dialogs (kept across rotation)
    var confirmClearHistory by rememberSaveable { mutableStateOf(false) }
    var confirmClearLogs by rememberSaveable { mutableStateOf(false) }
    var showAdbHelp by rememberSaveable { mutableStateOf(false) }

    val msgSaved = stringResource(R.string.settings_saved)
    val msgKeyNotStored = stringResource(R.string.save_key_not_stored)
    val msgBadServer = stringResource(R.string.save_invalid_server_url)
    val msgBadLlm = stringResource(R.string.save_invalid_llm_url)
    val msgBadKey = stringResource(R.string.save_invalid_key)
    val msgModels = stringResource(R.string.models_updated)
    val msgModelsFailed = stringResource(R.string.models_failed)

    fun save() {
        val result = viewModel.saveSettings(
            model, voice, searchEnabled, serverUrl, mode,
            geminiKey.ifBlank { null }, llmBase, apiKey.ifBlank { null }, vision
        )
        saveError = result.takeUnless { it == VoiceSession.SaveResult.OK }
        if (result == VoiceSession.SaveResult.OK) {
            geminiKey = ""
            apiKey = ""
        }
        val message = when (result) {
            VoiceSession.SaveResult.OK -> msgSaved
            VoiceSession.SaveResult.KEY_NOT_STORED -> msgKeyNotStored
            VoiceSession.SaveResult.INVALID_SERVER_URL -> msgBadServer
            VoiceSession.SaveResult.INVALID_LLM_URL -> msgBadLlm
            VoiceSession.SaveResult.INVALID_KEY -> msgBadKey
        }
        scope.launch { snackbar.showSnackbar(message) }
    }

    fun discard() {
        model = currentModel; voice = currentVoice; searchEnabled = currentSearch; serverUrl = currentServerUrl
        mode = currentMode; llmBase = currentLlmBase; vision = currentVision; geminiKey = ""; apiKey = ""; saveError = null
    }

    // Permission rows are read again whenever the user comes back (e.g. from system settings)
    val resumes = rememberResumeCount()
    LaunchedEffect(resumes) { viewModel.refreshAgentStatus() }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Spacing.m, end = Spacing.m, bottom = if (dirty) 120.dp else Spacing.l),
            verticalArrangement = Arrangement.spacedBy(Spacing.m)
        ) {
            item { ScreenHeader(stringResource(R.string.settings_title), stringResource(R.string.settings_subtitle)) }

            // ---- Assistant ----
            item {
                SectionCard(stringResource(R.string.section_assistant), Icons.Outlined.RecordVoiceOver) {
                    var nameInput by rememberSaveable { mutableStateOf(assistantName) }
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = {
                            nameInput = it.take(24)
                            if (it.isNotBlank()) viewModel.setAssistantName(it.trim().take(24))
                        },
                        label = { Text(stringResource(R.string.assistant_name)) },
                        supportingText = { Text(stringResource(R.string.assistant_name_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.size(Spacing.s))
                    Text(stringResource(R.string.assistant_voice), style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.size(Spacing.xs))
                    viewModel.availableVoices.chunked(3).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                            row.forEach { v ->
                                FilterChip(
                                    selected = voice == v,
                                    onClick = { voice = v },
                                    label = { Text(v) },
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                )
                            }
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = Spacing.s))
                    SwitchRow(
                        stringResource(R.string.wake_word_title, assistantName), isWakeWordEnabled, { viewModel.setWakeWordEnabled(it) },
                        description = stringResource(R.string.wake_word_desc)
                    )
                    SwitchRow(
                        stringResource(R.string.auto_start_title), policy.autoStartOnOpen,
                        { on -> viewModel.updatePolicy { it.copy(autoStartOnOpen = on) } },
                        description = stringResource(R.string.auto_start_desc)
                    )
                    SwitchRow(
                        stringResource(R.string.auto_sleep_title), autoSleep, { viewModel.setAutoSleep(it) },
                        description = stringResource(R.string.auto_sleep_desc)
                    )
                    SwitchRow(
                        stringResource(R.string.auto_mute_title), autoMute, { viewModel.setAutoMute(it) },
                        description = stringResource(R.string.auto_mute_desc, assistantName)
                    )
                }
            }

            // ---- AI model & search ----
            item {
                SectionCard(stringResource(R.string.section_model), Icons.Outlined.Psychology) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.model_live), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        if (hasGeminiKey) {
                            if (modelsLoading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else TextButton(onClick = {
                                viewModel.refreshLiveModels { ok -> scope.launch { snackbar.showSnackbar(if (ok) msgModels else msgModelsFailed) } }
                            }) {
                                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(Spacing.xs))
                                Text(stringResource(R.string.models_refresh))
                            }
                        }
                    }
                    models.forEach { opt ->
                        RadioRow(
                            title = opt.displayName,
                            selected = model == opt.id,
                            onSelect = { model = opt.id },
                            description = opt.description,
                            badge = opt.badge
                        )
                    }
                    HorizontalDivider(Modifier.padding(vertical = Spacing.s))
                    SwitchRow(
                        stringResource(R.string.search_title), searchEnabled, { searchEnabled = it },
                        description = stringResource(R.string.search_desc)
                    )
                    if (mode == VoiceSession.MODE_DIRECT) {
                        SwitchRow(
                            stringResource(R.string.vision_title), vision, { vision = it },
                            description = stringResource(R.string.vision_desc)
                        )
                    }
                }
            }

            // ---- Connection & keys ----
            item {
                SectionCard(stringResource(R.string.section_connection), Icons.Outlined.Cloud) {
                    Text(stringResource(R.string.connection_how), style = MaterialTheme.typography.labelLarge)
                    RadioRow(
                        stringResource(R.string.mode_direct), mode == VoiceSession.MODE_DIRECT, { mode = VoiceSession.MODE_DIRECT },
                        description = stringResource(R.string.mode_direct_desc)
                    )
                    RadioRow(
                        stringResource(R.string.mode_server), mode == VoiceSession.MODE_SERVER, { mode = VoiceSession.MODE_SERVER },
                        description = stringResource(R.string.mode_server_desc)
                    )
                    Spacer(Modifier.size(Spacing.s))
                    if (mode == VoiceSession.MODE_DIRECT) {
                        SecretField(
                            value = geminiKey, onValueChange = { geminiKey = it; saveError = null }, label = stringResource(R.string.gemini_key),
                            saved = hasGeminiKey, visible = showKeys, onToggleVisible = { showKeys = !showKeys },
                            onClear = { viewModel.clearKey(SecurePrefs.GEMINI_API_KEY) },
                            error = saveError == VoiceSession.SaveResult.INVALID_KEY || saveError == VoiceSession.SaveResult.KEY_NOT_STORED
                        )
                    } else {
                        OutlinedTextField(
                            value = serverUrl, onValueChange = { serverUrl = it; saveError = null },
                            label = { Text(stringResource(R.string.server_url)) },
                            isError = saveError == VoiceSession.SaveResult.INVALID_SERVER_URL,
                            supportingText = if (saveError == VoiceSession.SaveResult.INVALID_SERVER_URL) {
                                { Text(msgBadServer) }
                            } else null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                    }
                    HorizontalDivider(Modifier.padding(vertical = Spacing.m))
                    Text(stringResource(R.string.llm_gateway), style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.size(Spacing.s))
                    OutlinedTextField(
                        value = llmBase, onValueChange = { llmBase = it; saveError = null },
                        label = { Text(stringResource(R.string.llm_base_url)) },
                        isError = saveError == VoiceSession.SaveResult.INVALID_LLM_URL,
                        supportingText = if (saveError == VoiceSession.SaveResult.INVALID_LLM_URL) {
                            { Text(msgBadLlm) }
                        } else null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.size(Spacing.s))
                    SecretField(
                        value = apiKey, onValueChange = { apiKey = it; saveError = null }, label = stringResource(R.string.llm_key),
                        saved = currentApiKey.isNotBlank(), visible = showKeys, onToggleVisible = { showKeys = !showKeys },
                        onClear = { viewModel.clearKey(SecurePrefs.LLM_API_KEY) },
                        error = mode == VoiceSession.MODE_SERVER &&
                            (saveError == VoiceSession.SaveResult.INVALID_KEY || saveError == VoiceSession.SaveResult.KEY_NOT_STORED)
                    )
                    Spacer(Modifier.size(Spacing.s))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Lock, contentDescription = null, tint = NikTheme.extended.success, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(Spacing.s))
                        Text(stringResource(R.string.keys_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // ---- Safety & privacy ----
            item {
                SectionCard(stringResource(R.string.section_safety), Icons.Outlined.Shield, subtitle = stringResource(R.string.safety_subtitle)) {
                    Text(stringResource(R.string.confirm_title), style = MaterialTheme.typography.labelLarge)
                    RadioRow(
                        stringResource(R.string.confirm_relaxed), policy.confirmLevel == AgentPolicy.ConfirmLevel.RELAXED,
                        { viewModel.updatePolicy { it.copy(confirmLevel = AgentPolicy.ConfirmLevel.RELAXED) } },
                        description = stringResource(R.string.confirm_relaxed_desc)
                    )
                    RadioRow(
                        stringResource(R.string.confirm_balanced), policy.confirmLevel == AgentPolicy.ConfirmLevel.BALANCED,
                        { viewModel.updatePolicy { it.copy(confirmLevel = AgentPolicy.ConfirmLevel.BALANCED) } },
                        description = stringResource(R.string.confirm_balanced_desc),
                        badge = stringResource(R.string.recommended)
                    )
                    RadioRow(
                        stringResource(R.string.confirm_strict), policy.confirmLevel == AgentPolicy.ConfirmLevel.STRICT,
                        { viewModel.updatePolicy { it.copy(confirmLevel = AgentPolicy.ConfirmLevel.STRICT) } },
                        description = stringResource(R.string.confirm_strict_desc)
                    )
                    HorizontalDivider(Modifier.padding(vertical = Spacing.s))
                    SwitchRow(
                        stringResource(R.string.mask_title), policy.maskSensitiveScreen,
                        { on -> viewModel.updatePolicy { it.copy(maskSensitiveScreen = on) } },
                        description = stringResource(R.string.mask_desc)
                    )
                    HorizontalDivider(Modifier.padding(vertical = Spacing.s))
                    SwitchRow(
                        stringResource(R.string.save_history_title), policy.saveHistory,
                        { on -> viewModel.updatePolicy { it.copy(saveHistory = on) } },
                        description = stringResource(R.string.save_history_desc)
                    )
                    Text(stringResource(R.string.history_keep_for), style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.size(Spacing.s))
                    RetentionPicker(listOf(7, 30, 90, 0), policy.historyDays, { d -> viewModel.updatePolicy { it.copy(historyDays = d) } }, enabled = policy.saveHistory)
                    Spacer(Modifier.size(Spacing.s))
                    DangerButton(stringResource(R.string.delete_all_conversations), Icons.Outlined.DeleteForever) { confirmClearHistory = true }
                    HorizontalDivider(Modifier.padding(vertical = Spacing.s))
                    SwitchRow(
                        stringResource(R.string.save_logs_title), policy.saveActivityLogs,
                        { on -> viewModel.updatePolicy { it.copy(saveActivityLogs = on) } },
                        description = stringResource(R.string.save_logs_desc)
                    )
                    SwitchRow(
                        stringResource(R.string.log_screen_title), policy.logScreenContents,
                        { on -> viewModel.updatePolicy { it.copy(logScreenContents = on) } },
                        description = stringResource(R.string.log_screen_desc)
                    )
                    Text(stringResource(R.string.logs_keep_for), style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.size(Spacing.s))
                    RetentionPicker(listOf(1, 7, 30, 0), policy.logRetentionDays, { d -> viewModel.updatePolicy { it.copy(logRetentionDays = d) } }, enabled = policy.saveActivityLogs)
                    if (otlpEndpoint.isNotBlank()) {
                        Spacer(Modifier.size(Spacing.s))
                        Text(
                            stringResource(R.string.storage_exported_to, otlpEndpoint),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                    Spacer(Modifier.size(Spacing.s))
                    DangerButton(stringResource(R.string.delete_activity_logs), Icons.Outlined.Delete) { confirmClearLogs = true }
                }
            }

            // ---- Screen unlock ----
            item { UnlockSection(viewModel, resumes) { msg -> scope.launch { snackbar.showSnackbar(msg) } } }

            // ---- Payment apps ----
            item { PaymentSection(context, resumes) { showAdbHelp = true } }

            // ---- Webhook server ----
            item { WebhookSection(context) }

            // ---- System permissions ----
            item {
                val overlayOk = remember(resumes) { Settings.canDrawOverlays(context) }
                val micOk = remember(resumes) { granted(context, Manifest.permission.RECORD_AUDIO) }
                val notifyOk = remember(resumes) {
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted(context, Manifest.permission.POST_NOTIFICATIONS)
                }
                SectionCard(stringResource(R.string.section_permissions), Icons.Outlined.Security) {
                    PermissionRow(stringResource(R.string.perm_accessibility), stringResource(R.string.perm_accessibility_desc), isAgentEnabled) {
                        open(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                    PermissionRow(stringResource(R.string.perm_overlay), stringResource(R.string.perm_overlay_desc), overlayOk) {
                        open(context, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                    }
                    PermissionRow(stringResource(R.string.perm_mic), stringResource(R.string.perm_mic_desc), micOk) {
                        open(context, appDetails(context))
                    }
                    PermissionRow(stringResource(R.string.perm_notifications), stringResource(R.string.perm_notifications_desc), notifyOk) {
                        open(context, appDetails(context))
                    }
                }
            }
        }

        // Unsaved changes: Save / Discard, above the snackbar
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            SnackbarHost(snackbar, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            AnimatedVisibility(dirty, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shadowElevation = 6.dp,
                    tonalElevation = 3.dp,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth().padding(Spacing.s)
                ) {
                    Row(Modifier.padding(start = Spacing.m, end = Spacing.s, top = Spacing.s, bottom = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.unsaved_changes), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = ::discard) { Text(stringResource(R.string.action_discard)) }
                        Spacer(Modifier.width(Spacing.xs))
                        Button(onClick = ::save) { Text(stringResource(R.string.action_save)) }
                    }
                }
            }
        }
    }

    if (confirmClearHistory) {
        ConfirmDialog(
            title = stringResource(R.string.delete_all_conversations_title),
            message = stringResource(R.string.delete_all_conversations_body),
            confirmLabel = stringResource(R.string.action_delete_all),
            dismissLabel = stringResource(R.string.action_cancel),
            icon = Icons.Outlined.DeleteForever,
            onConfirm = {
                confirmClearHistory = false
                viewModel.clearAllHistory()
                scope.launch { snackbar.showSnackbar(context.getString(R.string.conversations_deleted)) }
            },
            onDismiss = { confirmClearHistory = false }
        )
    }
    if (confirmClearLogs) {
        ConfirmDialog(
            title = stringResource(R.string.delete_activity_logs_title),
            message = stringResource(R.string.delete_activity_logs_body),
            confirmLabel = stringResource(R.string.action_delete_all),
            dismissLabel = stringResource(R.string.action_cancel),
            icon = Icons.Outlined.Delete,
            onConfirm = {
                confirmClearLogs = false
                AgentTrace.clearAll()
                scope.launch { snackbar.showSnackbar(context.getString(R.string.logs_deleted)) }
            },
            onDismiss = { confirmClearLogs = false }
        )
    }
    if (showAdbHelp) {
        val command = "adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"
        AlertDialog(
            onDismissRequest = { showAdbHelp = false },
            title = { Text(stringResource(R.string.adb_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.adb_body))
                    Spacer(Modifier.size(Spacing.s))
                    Surface(color = NikTheme.extended.codeBackground, shape = MaterialTheme.shapes.small) {
                        SelectionContainer {
                            Text(command, color = NikTheme.extended.codeText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(Spacing.s))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("adb", command))
                    showAdbHelp = false
                }) { Text(stringResource(R.string.action_copy)) }
            },
            dismissButton = { TextButton(onClick = { showAdbHelp = false }) { Text(stringResource(R.string.action_close)) } }
        )
    }
}

@Composable
private fun UnlockSection(viewModel: VoiceAssistantViewModel, resumes: Int, onMessage: (String) -> Unit) {
    // Read again on resume: the app turns auto-unlock off by itself after failed unlocks
    var lockType by remember(resumes) { mutableStateOf(viewModel.lockType()) }
    var credentialSaved by remember(resumes, lockType) {
        mutableStateOf(if (lockType == SecurePrefs.LockType.PATTERN) viewModel.hasPattern() else viewModel.hasPin())
    }
    var autoUnlock by remember(resumes) { mutableStateOf(viewModel.isAutoUnlockEnabled()) }
    // Nothing saved yet: show the picker straight away. Already set up: show only the matching type,
    // a "Change" link reveals the other two instead of all three always competing for attention.
    var showTypePicker by remember(resumes) { mutableStateOf(!credentialSaved) }
    var pinInput by remember(lockType) { mutableStateOf("") }
    val pinSavedMsg = stringResource(R.string.pin_saved)
    val pinFailedMsg = stringResource(R.string.pin_failed)
    val passwordSavedMsg = stringResource(R.string.password_saved)
    val passwordFailedMsg = stringResource(R.string.password_failed)
    val patternSavedMsg = stringResource(R.string.pattern_saved)
    val patternFailedMsg = stringResource(R.string.pattern_failed)

    fun selectLockType(type: SecurePrefs.LockType) {
        lockType = type
        viewModel.setLockType(type)
    }

    SectionCard(stringResource(R.string.section_unlock), Icons.Outlined.LockOpen) {
        SwitchRow(
            stringResource(R.string.unlock_title), autoUnlock,
            { autoUnlock = it; viewModel.setAutoUnlock(it) },
            description = stringResource(if (credentialSaved) R.string.unlock_desc else R.string.unlock_needs_pin),
            enabled = credentialSaved
        )
        if (autoUnlock) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                ),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.s)
            ) {
                Row(Modifier.padding(Spacing.m - 4.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Outlined.WarningAmber, contentDescription = null)
                    Spacer(Modifier.width(Spacing.s))
                    Text(stringResource(R.string.unlock_warning), style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            Text(stringResource(R.string.unlock_off_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(Spacing.s))
        }

        if (showTypePicker) {
            Text(stringResource(R.string.lock_type_title), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.size(Spacing.xs))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                FilterChip(
                    selected = lockType == SecurePrefs.LockType.PIN, onClick = { selectLockType(SecurePrefs.LockType.PIN) },
                    label = { Text(stringResource(R.string.lock_type_pin)) }, modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = lockType == SecurePrefs.LockType.PASSWORD, onClick = { selectLockType(SecurePrefs.LockType.PASSWORD) },
                    label = { Text(stringResource(R.string.lock_type_password)) }, modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = lockType == SecurePrefs.LockType.PATTERN, onClick = { selectLockType(SecurePrefs.LockType.PATTERN) },
                    label = { Text(stringResource(R.string.lock_type_pattern)) }, modifier = Modifier.weight(1f)
                )
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.lock_type_title) + ": " + stringResource(
                        when (lockType) {
                            SecurePrefs.LockType.PIN -> R.string.lock_type_pin
                            SecurePrefs.LockType.PASSWORD -> R.string.lock_type_password
                            SecurePrefs.LockType.PATTERN -> R.string.lock_type_pattern
                        }
                    ),
                    style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { showTypePicker = true }) { Text(stringResource(R.string.action_change)) }
            }
        }
        Spacer(Modifier.size(Spacing.s))

        when (lockType) {
            SecurePrefs.LockType.PIN -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.device_pin), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    if (credentialSaved) Badge(stringResource(R.string.pin_saved_badge), container = NikTheme.extended.successContainer, content = NikTheme.extended.onSuccessContainer)
                }
                Spacer(Modifier.size(Spacing.xs))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = { if (it.all(Char::isDigit) && it.length <= 12) pinInput = it },
                        label = { Text(stringResource(if (credentialSaved) R.string.pin_replace else R.string.pin_enter)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(Spacing.s))
                    Button(
                        onClick = {
                            val ok = viewModel.savePin(pinInput)
                            pinInput = ""
                            credentialSaved = viewModel.hasPin()
                            if (ok) showTypePicker = false
                            onMessage(if (ok) pinSavedMsg else pinFailedMsg)
                        },
                        enabled = pinInput.length >= 4
                    ) { Text(stringResource(R.string.action_save)) }
                }
                Text(stringResource(R.string.pin_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.xs))
            }

            SecurePrefs.LockType.PASSWORD -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.device_password), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    if (credentialSaved) Badge(stringResource(R.string.pin_saved_badge), container = NikTheme.extended.successContainer, content = NikTheme.extended.onSuccessContainer)
                }
                Spacer(Modifier.size(Spacing.xs))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = { if (it.length <= 64) pinInput = it },
                        label = { Text(stringResource(if (credentialSaved) R.string.password_replace else R.string.password_enter)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(Spacing.s))
                    Button(
                        onClick = {
                            val ok = viewModel.savePin(pinInput)
                            pinInput = ""
                            credentialSaved = viewModel.hasPin()
                            if (ok) showTypePicker = false
                            onMessage(if (ok) passwordSavedMsg else passwordFailedMsg)
                        },
                        enabled = pinInput.isNotBlank()
                    ) { Text(stringResource(R.string.action_save)) }
                }
                Text(stringResource(R.string.password_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.xs))
            }

            SecurePrefs.LockType.PATTERN -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.device_pattern), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    if (credentialSaved) Badge(stringResource(R.string.pin_saved_badge), container = NikTheme.extended.successContainer, content = NikTheme.extended.onSuccessContainer)
                }
                Spacer(Modifier.size(Spacing.xs))
                Text(stringResource(R.string.pattern_draw_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(Spacing.s))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    com.nik.voiceassistant.ui.components.PatternPad(
                        onPatternComplete = { cells ->
                            val ok = viewModel.savePattern(cells)
                            credentialSaved = viewModel.hasPattern()
                            if (ok) showTypePicker = false
                            onMessage(if (ok) patternSavedMsg else patternFailedMsg)
                        }
                    )
                }
                Spacer(Modifier.size(Spacing.s))
                OutlinedButton(
                    onClick = {
                        viewModel.savePattern(emptyList())
                        credentialSaved = viewModel.hasPattern()
                    },
                    enabled = credentialSaved,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.pattern_clear)) }
                Text(stringResource(R.string.pattern_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.xs))
            }
        }
    }
}

@Composable
private fun PaymentSection(context: Context, resumes: Int, onAdbHelp: () -> Unit) {
    var guardOn by remember(resumes) { mutableStateOf(PaymentGuard.isEnabled(context)) }
    val canToggle = remember(resumes) { PaymentGuard.canToggle(context) }
    val canWatch = remember(resumes) { PaymentGuard.canWatch(context) }
    // Looks through installed apps: off the main thread
    val apps by produceState<List<String>?>(null, resumes) {
        value = withContext(Dispatchers.IO) { runCatching { PaymentGuard.protectedAppNames(context) }.getOrDefault(emptyList()) }
    }
    SectionCard(stringResource(R.string.section_payments), Icons.Outlined.Payments) {
        SwitchRow(
            stringResource(R.string.payments_title), guardOn,
            { guardOn = it; PaymentGuard.setEnabled(context, it) },
            description = stringResource(R.string.payments_desc)
        )
        Text(
            apps?.let { stringResource(R.string.payments_found, it.joinToString(", ").ifEmpty { stringResource(R.string.none) }) }
                ?: stringResource(R.string.loading),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.size(Spacing.s))
        PermissionRow(stringResource(R.string.perm_usage), stringResource(R.string.perm_usage_desc), canWatch) {
            open(context, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        PermissionRow(stringResource(R.string.perm_secure_settings), stringResource(R.string.perm_secure_settings_desc), canToggle, onFix = onAdbHelp)
    }
}

/** A permission and whether it is granted; "Fix" opens the place to grant it. */
@Composable
private fun PermissionRow(name: String, description: String, granted: Boolean, onFix: () -> Unit) {
    val ext = NikTheme.extended
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = Spacing.xs).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (granted) Icons.Outlined.CheckCircle else Icons.Outlined.WarningAmber,
            contentDescription = null,
            tint = if (granted) ext.success else ext.warning,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(Spacing.m))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (granted) stringResource(R.string.perm_granted) else description,
                style = MaterialTheme.typography.bodySmall,
                color = if (granted) ext.success else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!granted) TextButton(onClick = onFix) { Text(stringResource(R.string.action_fix)) }
    }
}

/** A key field: empty means "keep the saved key"; shows that one is saved without ever showing it. */
@Composable
private fun SecretField(
    value: String, onValueChange: (String) -> Unit, label: String, saved: Boolean,
    visible: Boolean, onToggleVisible: () -> Unit, onClear: () -> Unit, error: Boolean
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(stringResource(if (saved) R.string.key_saved_placeholder else R.string.key_paste)) },
        supportingText = if (saved && value.isEmpty()) {
            { Text(stringResource(R.string.key_saved_note)) }
        } else null,
        isError = error,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            Row {
                IconButton(onClick = onToggleVisible) {
                    Icon(
                        if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = stringResource(if (visible) R.string.cd_hide_key else R.string.cd_show_key)
                    )
                }
                if (saved) IconButton(onClick = onClear) {
                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.cd_remove_key))
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
}

@Composable
private fun DangerButton(text: String, icon: ImageVector, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(Spacing.s))
        Text(text)
    }
}

@Composable
private fun WebhookSection(context: Context) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    val ip = remember { WebhookServer.localAddress(context) }
    val port = WebhookServer.PORT

    SectionCard("Webhook Server", Icons.Outlined.Webhook) {
        if (ip == null) {
            Text(
                "Webhook server is running on port $port. Connect to Wi-Fi to see the URL.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            val baseUrl = "http://$ip:$port/webhook/"
            Text(
                "Local server ready. Ask Shiv to register a webhook, or POST to:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.padding(top = 4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                SelectionContainer(Modifier.weight(1f)) {
                    Text(baseUrl, style = MaterialTheme.typography.bodyMedium)
                }
                IconButton(onClick = {
                    clipboard?.setPrimaryClip(ClipData.newPlainText("Webhook URL", baseUrl))
                }) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy URL")
                }
            }
            Text(
                "Only reachable on same Wi-Fi / Tailscale network.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun granted(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun appDetails(context: Context) =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

private fun open(context: Context, intent: Intent) {
    runCatching { context.startActivity(intent) }
        .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) } }
}
