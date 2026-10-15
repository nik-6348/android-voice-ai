package com.nik.voiceassistant.ui.screens

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material.icons.outlined.Token
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nik.voiceassistant.R
import com.nik.voiceassistant.VoiceAssistantViewModel
import com.nik.voiceassistant.data.trace.AgentTrace
import com.nik.voiceassistant.data.trace.AgentTrace.Kind
import com.nik.voiceassistant.data.trace.Tracer
import com.nik.voiceassistant.ui.components.ConfirmDialog
import com.nik.voiceassistant.ui.components.EmptyState
import com.nik.voiceassistant.ui.components.PlanCard
import com.nik.voiceassistant.ui.components.ScreenHeader
import com.nik.voiceassistant.ui.components.SectionCard
import com.nik.voiceassistant.ui.theme.NikTheme
import com.nik.voiceassistant.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Shared text is capped: an Intent extra over ~1 MB crashes with TransactionTooLargeException. */
internal const val MAX_SHARE_CHARS = 90_000

internal fun shareText(context: Context, text: String, mime: String, title: String) {
    val capped = if (text.length > MAX_SHARE_CHARS) text.takeLast(MAX_SHARE_CHARS) else text
    runCatching {
        context.startActivity(
            Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_TEXT, capped), title)
        )
    }
}

/**
 * Live view of what the agent is doing and why: the current plan, key numbers (reply latency, tool
 * success, reconnects, tokens), the traces (one per request) and every event as it happens. Saved
 * days are read from the phone in the background; they can be shared or deleted. How long logs are
 * kept and whether they are saved at all is set in Settings > Safety & Privacy (also offered here).
 */
@Composable
fun ActivityTab(viewModel: VoiceAssistantViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val live by AgentTrace.events.collectAsState()
    val files by AgentTrace.files.collectAsState()
    val plan by viewModel.plan.collectAsState()
    val tool by viewModel.activeToolStatus.collectAsState()
    val policy by viewModel.policy.collectAsState()
    val liveTraces by Tracer.traces.collectAsState()
    val otlpEndpoint by Tracer.otlpEndpoint.collectAsState()

    // null = live; else the name of a saved day's file
    var day by rememberSaveable { mutableStateOf<String?>(null) }
    var dayEvents by remember { mutableStateOf<List<AgentTrace.Event>>(emptyList()) }
    var dayTraces by remember { mutableStateOf<List<Tracer.TraceData>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    var showTraces by rememberSaveable { mutableStateOf(true) }
    var openTraceId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDelete by rememberSaveable { mutableStateOf<String?>(null) } // a file name, or "*" for all

    // A saved day is read and parsed off the main thread
    LaunchedEffect(day) {
        val name = day
        if (name == null) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        dayEvents = AgentTrace.loadDay(name)
        dayTraces = Tracer.loadDay(name)
        loading = false
    }

    val events = if (day == null) live else dayEvents
    val traces = if (day == null) liveTraces else dayTraces
    val shown = remember(events, filter) { events.filter { filter.kinds?.contains(it.kind) ?: true }.asReversed() }
    val metrics = remember(events) { AgentTrace.metrics(events) }

    // An opened trace (stays live while it runs)
    traces.firstOrNull { it.traceId == openTraceId }?.let { trace ->
        BackHandler { openTraceId = null }
        TraceDetail(trace) { openTraceId = null }
        return
    }

    val shareTitle = stringResource(R.string.share_trace)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Spacing.m, end = Spacing.m, bottom = Spacing.l),
        verticalArrangement = Arrangement.spacedBy(Spacing.s)
    ) {
        item {
            ScreenHeader(
                title = stringResource(R.string.activity_title),
                subtitle = day?.let { stringResource(R.string.activity_saved_day, it.removeSuffix(".jsonl")) }
                    ?: stringResource(R.string.activity_live_subtitle)
            ) {
                IconButton(onClick = {
                    scope.launch {
                        val text = withContext(Dispatchers.Default) { AgentTrace.export(events) }
                        shareText(context, text, "text/plain", shareTitle)
                    }
                }) { Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.cd_share_log)) }
            }
        }

        // Which log: live, or a saved day
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                item {
                    FilterChip(selected = day == null, onClick = { day = null }, label = { Text(stringResource(R.string.activity_live)) })
                }
                items(files, key = { it.name }) { f ->
                    FilterChip(selected = day == f.name, onClick = { day = f.name }, label = { Text("${f.day} · ${size(f.bytes)}") })
                }
            }
        }

        if (loading) item {
            Box(Modifier.fillMaxWidth().padding(Spacing.l), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }

        // Numbers
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                item { Metric(Icons.Outlined.Speed, stringResource(R.string.metric_latency), metrics.avgLatencyMs?.let { "$it ms" } ?: "–", metrics.lastLatencyMs?.let { stringResource(R.string.metric_last, it) }) }
                item {
                    val success = if (metrics.toolCalls == 0) "–" else "${(100 * (metrics.toolCalls - metrics.toolFailures)) / metrics.toolCalls}%"
                    Metric(Icons.Outlined.Build, stringResource(R.string.metric_tool_success), success, pluralStringResource(R.plurals.metric_calls, metrics.toolCalls, metrics.toolCalls))
                }
                item { Metric(Icons.Outlined.PlayCircle, stringResource(R.string.metric_tool_time), metrics.avgToolMs?.let { "$it ms" } ?: "–", null) }
                item { Metric(Icons.Outlined.Wifi, stringResource(R.string.metric_reconnects), metrics.reconnects.toString(), pluralStringResource(R.plurals.metric_errors, metrics.errors, metrics.errors)) }
                item { Metric(Icons.Outlined.Token, stringResource(R.string.metric_tokens), metrics.tokens.toString(), null) }
            }
        }

        // The agent's current plan and thinking (live only)
        if (day == null) plan?.let { p ->
            item { PlanCard(p, toolStatus = tool, showTried = true, startExpanded = true) }
        }

        // Traces (one per request, OpenTelemetry style) or the flat event log
        item { TracesOrEvents(showTraces, traces.size, events.size) { showTraces = it } }

        if (showTraces) {
            if (traces.isEmpty() && !loading) item {
                EmptyState(Icons.Outlined.Timeline, stringResource(R.string.traces_empty_title), stringResource(R.string.traces_empty_body))
            }
            items(traces, key = { "t-" + it.traceId }) { t -> TraceCard(t) { openTraceId = t.traceId } }
        } else {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    items(Filter.values().toList()) { f ->
                        FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(stringResource(f.title)) })
                    }
                }
            }
            if (shown.isEmpty() && !loading) item {
                EmptyState(
                    Icons.Outlined.Timeline,
                    stringResource(R.string.events_empty_title),
                    stringResource(if (day == null) R.string.events_empty_live else R.string.events_empty_day)
                )
            }
            // seq is unique within a list (live: running number; a saved day: line number)
            items(shown, key = { "e-${it.seq}-${it.time}" }) { e -> EventRow(e) }
        }

        // Storage: what is kept, for how long, where it goes, and deleting
        item {
            StorageCard(
                saving = policy.saveActivityLogs,
                keepDays = policy.logRetentionDays,
                dayCount = files.size,
                bytes = files.sumOf { it.bytes },
                otlpEndpoint = otlpEndpoint,
                day = day,
                onKeepDays = { d -> viewModel.updatePolicy { it.copy(logRetentionDays = d) } },
                onSaveEndpoint = { Tracer.setOtlpEndpoint(it) },
                onShareDay = { name ->
                    scope.launch {
                        val text = withContext(Dispatchers.IO) { runCatching { Tracer.dayFile(name)?.readText() }.getOrNull() }
                        if (text == null) Toast.makeText(context.applicationContext, R.string.share_nothing, Toast.LENGTH_SHORT).show()
                        else shareText(context, text, "application/json", context.getString(R.string.share_day_otlp))
                    }
                },
                onDelete = { confirmDelete = it }
            )
        }
    }

    confirmDelete?.let { target ->
        ConfirmDialog(
            title = if (target == "*") stringResource(R.string.delete_all_logs_title) else stringResource(R.string.delete_day_title, target.removeSuffix(".jsonl")),
            message = stringResource(R.string.cannot_undo),
            confirmLabel = stringResource(R.string.action_delete),
            dismissLabel = stringResource(R.string.action_cancel),
            icon = Icons.Outlined.Delete,
            onConfirm = {
                if (target == "*") AgentTrace.clearAll() else AgentTrace.deleteDay(target)
                if (target == "*" || target == day) { day = null; dayEvents = emptyList(); dayTraces = emptyList() }
                confirmDelete = null
            },
            onDismiss = { confirmDelete = null }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TracesOrEvents(showTraces: Boolean, traces: Int, events: Int, onChange: (Boolean) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = showTraces, onClick = { onChange(true) },
            shape = SegmentedButtonDefaults.itemShape(0, 2)
        ) { Text(stringResource(R.string.activity_traces, traces)) }
        SegmentedButton(
            selected = !showTraces, onClick = { onChange(false) },
            shape = SegmentedButtonDefaults.itemShape(1, 2)
        ) { Text(stringResource(R.string.activity_events, events)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StorageCard(
    saving: Boolean,
    keepDays: Int,
    dayCount: Int,
    bytes: Long,
    otlpEndpoint: String,
    day: String?,
    onKeepDays: (Int) -> Unit,
    onSaveEndpoint: (String) -> Unit,
    onShareDay: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    var endpointInput by rememberSaveable(otlpEndpoint) { mutableStateOf(otlpEndpoint) }
    SectionCard(title = stringResource(R.string.storage_title), icon = Icons.Outlined.Storage) {
        Text(
            if (saving) stringResource(R.string.storage_saved_on_phone, dayCount, size(bytes))
            else stringResource(R.string.storage_memory_only),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (otlpEndpoint.isNotBlank()) {
            Spacer(Modifier.size(Spacing.xs))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CloudUpload, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(Spacing.s))
                Text(
                    stringResource(R.string.storage_exported_to, otlpEndpoint),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary
                )
            }
        }
        Spacer(Modifier.size(Spacing.m))
        Text(stringResource(R.string.logs_keep_for), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.size(Spacing.s))
        RetentionPicker(
            options = listOf(1, 7, 30, 0),
            selected = keepDays,
            onSelect = onKeepDays
        )
        Spacer(Modifier.size(Spacing.m))
        Text(stringResource(R.string.otlp_title), style = MaterialTheme.typography.labelLarge)
        Text(stringResource(R.string.otlp_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(Spacing.s))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = endpointInput, onValueChange = { endpointInput = it },
                placeholder = { Text(stringResource(R.string.otlp_off)) },
                label = { Text(stringResource(R.string.otlp_label)) },
                singleLine = true, modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { onSaveEndpoint(endpointInput) }, enabled = endpointInput.trim() != otlpEndpoint) {
                Text(stringResource(R.string.action_save))
            }
        }
        Spacer(Modifier.size(Spacing.s))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (day != null) {
                TextButton(onClick = { onShareDay(day) }) { Text(stringResource(R.string.share_day_otlp)) }
                TextButton(onClick = { onDelete(day) }) { Text(stringResource(R.string.delete_this_day), color = MaterialTheme.colorScheme.error) }
            }
            TextButton(onClick = { onDelete("*") }) { Text(stringResource(R.string.delete_all_logs), color = MaterialTheme.colorScheme.error) }
        }
    }
}

/** Days to keep (0 = forever) as one segmented row. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RetentionPicker(options: List<Int>, selected: Int, onSelect: (Int) -> Unit, enabled: Boolean = true) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, d ->
            SegmentedButton(
                selected = selected == d,
                onClick = { onSelect(d) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(i, options.size)
            ) {
                Text(
                    when (d) {
                        0 -> stringResource(R.string.keep_forever)
                        1 -> stringResource(R.string.keep_one_day)
                        else -> stringResource(R.string.keep_days, d)
                    },
                    maxLines = 1
                )
            }
        }
    }
}

private enum class Filter(val title: Int, val kinds: Set<Kind>?) {
    ALL(R.string.filter_all, null),
    DECISIONS(R.string.filter_decisions, setOf(Kind.PLAN, Kind.WARNING)),
    TOOLS(R.string.filter_tools, setOf(Kind.TOOL)),
    TALK(R.string.filter_conversation, setOf(Kind.USER, Kind.REPLY, Kind.LATENCY)),
    SYSTEM(R.string.filter_system, setOf(Kind.NETWORK, Kind.ERROR, Kind.SESSION, Kind.USAGE))
}

internal fun size(bytes: Long) = if (bytes < 1024) "$bytes B" else if (bytes < 1024 * 1024) "${bytes / 1024} KB" else "%.1f MB".format(bytes / 1048576.0)

private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

@Composable
private fun style(kind: Kind): Pair<ImageVector, Color> {
    val scheme = MaterialTheme.colorScheme
    val ext = NikTheme.extended
    return when (kind) {
        Kind.SESSION -> Icons.Outlined.PlayCircle to scheme.primary
        Kind.NETWORK -> Icons.Outlined.Wifi to scheme.onSurfaceVariant
        Kind.USER -> Icons.Outlined.Person to scheme.secondary
        Kind.REPLY -> Icons.Outlined.RecordVoiceOver to scheme.tertiary
        Kind.LATENCY -> Icons.Outlined.Speed to scheme.tertiary
        Kind.TOOL -> Icons.Outlined.Build to scheme.primary
        Kind.PLAN -> Icons.Outlined.AccountTree to scheme.secondary
        Kind.WARNING -> Icons.Outlined.WarningAmber to ext.warning
        Kind.ERROR -> Icons.Outlined.ErrorOutline to scheme.error
        Kind.USAGE -> Icons.Outlined.Token to scheme.outline
    }
}

@Composable
private fun EventRow(e: AgentTrace.Event) {
    var open by rememberSaveable(e.seq, e.time) { mutableStateOf(false) }
    val (icon, tint) = style(e.kind)
    val scheme = MaterialTheme.colorScheme
    val color = if (e.ok == false) scheme.error else tint
    val failed = stringResource(R.string.event_failed)
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow),
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClickLabel = stringResource(if (open) R.string.action_collapse else R.string.action_expand)) { open = !open }
            .semantics { if (e.ok == false) stateDescription = failed }
    ) {
        Row(Modifier.padding(horizontal = Spacing.m, vertical = Spacing.s + 2.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.padding(top = 2.dp).size(18.dp))
            Spacer(Modifier.width(Spacing.s + 4.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(e.kind.name.lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelMedium, color = color)
                    if (e.ok == false) Text("  $failed", style = MaterialTheme.typography.labelMedium, color = scheme.error)
                    Spacer(Modifier.weight(1f))
                    e.ms?.takeIf { e.kind != Kind.USAGE }?.let {
                        Text("$it ms", style = MaterialTheme.typography.labelSmall, color = if (it > 3000) NikTheme.extended.warning else scheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.width(Spacing.s))
                    }
                    if (e.kind == Kind.USAGE) e.ms?.let {
                        Text("$it tok", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.width(Spacing.s))
                    }
                    Text(clock.format(Date(e.time)), style = MaterialTheme.typography.labelSmall, color = scheme.outline, fontFamily = FontFamily.Monospace)
                }
                Text(
                    e.text, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface,
                    maxLines = if (open) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
                    fontFamily = if (e.kind == Kind.TOOL) FontFamily.Monospace else FontFamily.Default
                )
            }
        }
    }
}

@Composable
private fun Metric(icon: ImageVector, title: String, value: String, sub: String?) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.width(136.dp).semantics(mergeDescendants = true) {}
    ) {
        Column(Modifier.padding(Spacing.m - 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(Spacing.xs))
                Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.size(Spacing.xs))
            Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            sub?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
