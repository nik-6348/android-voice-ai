package com.nik.voiceassistant.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Power
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.nik.voiceassistant.R
import com.nik.voiceassistant.data.trace.Tracer
import com.nik.voiceassistant.data.trace.Tracer.Kind
import com.nik.voiceassistant.data.trace.Tracer.SpanData
import com.nik.voiceassistant.data.trace.Tracer.Status
import com.nik.voiceassistant.data.trace.Tracer.TraceData
import com.nik.voiceassistant.ui.components.Badge
import com.nik.voiceassistant.ui.theme.NikTheme
import com.nik.voiceassistant.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * Trace views for the Activity tab, OpenTelemetry style: a card per request (what was asked, the
 * goal, status, time, tool calls, a mini waterfall), and a trace's detail: the waterfall of its
 * spans (the model's turns and every tool call, placed in time), each span's attributes — a tool's
 * full input and output as formatted JSON, copyable — and its events (plans, decisions, warnings).
 */

private val time = SimpleDateFormat("HH:mm:ss", Locale.US)
private val pretty = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

@Composable
private fun kindColor(kind: Kind, status: Status): Color {
    val scheme = MaterialTheme.colorScheme
    return when {
        status == Status.ERROR -> scheme.error
        kind == Kind.REQUEST -> scheme.primary
        kind == Kind.LLM -> scheme.secondary
        kind == Kind.TOOL -> scheme.tertiary
        else -> scheme.outline
    }
}

private fun duration(ms: Long?): String = when {
    ms == null -> "running…"
    ms < 1000 -> "$ms ms"
    ms < 60_000 -> "%.1f s".format(ms / 1000.0)
    else -> "${ms / 60_000} m ${(ms / 1000) % 60} s"
}

private fun TraceData.title(): String = when (root.kind) {
    Kind.SESSION -> "Conversation"
    else -> root.attributes["user.input"] ?: root.name.removePrefix("request: ")
}

/** One request in the list. */
@Composable
fun TraceCard(trace: TraceData, onOpen: () -> Unit) {
    val root = trace.root
    val scheme = MaterialTheme.colorScheme
    val ext = NikTheme.extended
    val total = (trace.end ?: System.currentTimeMillis()) - trace.start
    val (statusText, statusBg, statusFg) = when {
        trace.end == null -> Triple(stringResource(R.string.trace_running), scheme.primaryContainer, scheme.onPrimaryContainer)
        root.status == Status.ERROR || trace.errorCount > 0 ->
            Triple(pluralStringResource(R.plurals.trace_errors, trace.errorCount, trace.errorCount), scheme.errorContainer, scheme.onErrorContainer)
        else -> Triple(stringResource(R.string.trace_ok), ext.successContainer, ext.onSuccessContainer)
    }
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow),
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClickLabel = stringResource(R.string.action_open_trace), onClick = onOpen)
    ) {
        Column(Modifier.padding(Spacing.m - 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (root.kind == Kind.SESSION) Icons.Outlined.Power else Icons.Outlined.RecordVoiceOver,
                    contentDescription = null, tint = scheme.primary, modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(Spacing.s))
                Text(
                    trace.title(), style = MaterialTheme.typography.titleSmall, color = scheme.onSurface,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(Spacing.s))
                Badge(statusText, container = statusBg, content = statusFg)
            }
            root.attributes["agent.goal"]?.let {
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Flag, contentDescription = null, tint = scheme.secondary, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(Spacing.xs))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(Spacing.xs))
            Text(
                "${time.format(Date(trace.start))} · ${duration(trace.end?.let { total })} · " +
                    pluralStringResource(R.plurals.trace_tool_calls, trace.toolCount, trace.toolCount) +
                    (root.attributes["gen_ai.usage.total_tokens"]?.let { " · $it tokens" } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.s))
            MiniWaterfall(trace, total)
        }
    }
}

/** The spans of a trace as thin coloured bars on one line, drawn in one pass. */
@Composable
private fun MiniWaterfall(trace: TraceData, total: Long) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val now = System.currentTimeMillis()
    val bars = trace.spans.filter { it.parentId != null }.map { s ->
        Triple(
            ((s.start - trace.start).toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f),
            (((s.end ?: now) - s.start).toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f),
            kindColor(s.kind, s.status)
        )
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(CircleShape)
            .drawBehind {
                drawRect(track)
                val minW = 2.dp.toPx()
                bars.forEach { (start, len, color) ->
                    drawRect(color, topLeft = Offset(size.width * start, 0f), size = Size((size.width * len).coerceAtLeast(minW), size.height))
                }
            }
    )
}

/** A trace opened: header, waterfall, and each span's details. */
@Composable
fun TraceDetail(trace: TraceData, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    val root = trace.root
    val total = ((trace.end ?: System.currentTimeMillis()) - trace.start).coerceAtLeast(1)
    // Spans in time order, each with its depth under the root
    val depth = remember(trace) {
        val byId = trace.spans.associateBy { it.spanId }
        trace.spans.associate { s -> s.spanId to generateSequence(s) { byId[it.parentId ?: ""] }.count() - 1 }
    }
    val ordered = remember(trace) { trace.spans.sortedWith(compareBy({ it.parentId != null }, { it.start })) }
    val shareTitle = stringResource(R.string.share_trace_otlp)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Spacing.m, end = Spacing.m, top = Spacing.s, bottom = Spacing.l),
        verticalArrangement = Arrangement.spacedBy(Spacing.s)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back)) }
                Text(
                    stringResource(R.string.trace_title), style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).semantics { heading() }
                )
                IconButton(onClick = {
                    // Redacted and capped; built off the main thread
                    scope.launch {
                        val json = Tracer.exportJson(trace.spans)
                        shareText(context, json, "application/json", shareTitle)
                    }
                }) { Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.cd_share_trace)) }
            }
        }
        item {
            Card(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(Spacing.m)) {
                    Text(trace.title(), style = MaterialTheme.typography.titleMedium)
                    root.attributes["agent.goal"]?.let {
                        Text(stringResource(R.string.trace_goal, it), style = MaterialTheme.typography.bodySmall, color = scheme.secondary)
                    }
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        "${time.format(Date(trace.start))} · ${duration(trace.end?.let { it - trace.start })} · ${trace.spans.size} spans · " +
                            "${trace.toolCount} tool calls · ${trace.errorCount} errors" +
                            (root.attributes["gen_ai.usage.total_tokens"]?.let { " · $it tokens" } ?: ""),
                        style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant
                    )
                    Text("trace ${trace.traceId}", style = MaterialTheme.typography.labelSmall, color = scheme.outline, fontFamily = FontFamily.Monospace)
                }
            }
        }
        items(ordered, key = { it.spanId }) { span ->
            SpanRow(span, depth[span.spanId] ?: 0, trace.start, total, context)
        }
    }
}

@Composable
private fun SpanRow(span: SpanData, depth: Int, traceStart: Long, total: Long, context: Context) {
    var open by rememberSaveable(span.spanId) { mutableStateOf(span.kind == Kind.TOOL && span.status == Status.ERROR) }
    val scheme = MaterialTheme.colorScheme
    val color = kindColor(span.kind, span.status)
    val track = scheme.surfaceContainerHighest
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth * 12).dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClickLabel = stringResource(if (open) R.string.action_collapse else R.string.action_expand)) { open = !open }
    ) {
        Column(Modifier.padding(Spacing.m - 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).drawBehind { drawRect(color) })
                Spacer(Modifier.width(Spacing.s))
                Text(
                    span.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    duration(span.durationMs), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                    color = if ((span.durationMs ?: 0) > 3000) NikTheme.extended.warning else scheme.onSurfaceVariant
                )
                Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null, tint = scheme.outline, modifier = Modifier.size(20.dp))
            }
            // Where it sits in the request's time
            Spacer(Modifier.height(Spacing.s))
            val start = ((span.start - traceStart).toFloat() / total).coerceIn(0f, 1f)
            val len = (((span.end ?: System.currentTimeMillis()) - span.start).toFloat() / total).coerceIn(0f, 1f)
            Box(
                Modifier.fillMaxWidth().height(5.dp).drawBehind {
                    drawRoundRect(track, cornerRadius = CornerRadius(size.height / 2))
                    drawRoundRect(
                        color, topLeft = Offset(size.width * start, 0f),
                        size = Size((size.width * len).coerceAtLeast(2.dp.toPx()), size.height),
                        cornerRadius = CornerRadius(size.height / 2)
                    )
                }
            )
            Text(
                "+${duration(span.start - traceStart)} · ${span.kind.name.lowercase()} · ${span.status.name.lowercase()}" + (span.statusMessage?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = if (span.status == Status.ERROR) scheme.error else scheme.onSurfaceVariant,
                maxLines = if (open) 6 else 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = Spacing.xs)
            )

            if (open) {
                // Tool input and output first, as formatted JSON
                listOf("gen_ai.tool.call.arguments" to stringResource(R.string.trace_input), "gen_ai.tool.call.result" to stringResource(R.string.trace_output)).forEach { (key, title) ->
                    span.attributes[key]?.let { JsonBlock(title, it, context) }
                }
                span.attributes.filterKeys { it != "gen_ai.tool.call.arguments" && it != "gen_ai.tool.call.result" }.forEach { (k, v) ->
                    Row(Modifier.padding(top = Spacing.xs + 2.dp)) {
                        Text(k, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(0.42f))
                        SelectionContainer(Modifier.weight(0.58f)) {
                            Text(v, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
                if (span.events.isNotEmpty()) {
                    Spacer(Modifier.height(Spacing.s))
                    Text(stringResource(R.string.trace_events), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                    span.events.forEach { e ->
                        Surface(
                            color = scheme.surfaceContainerHigh, shape = MaterialTheme.shapes.small,
                            modifier = Modifier.padding(top = Spacing.xs).fillMaxWidth()
                        ) {
                            Column(Modifier.padding(Spacing.s)) {
                                Row {
                                    Text(
                                        e.name, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f),
                                        color = if (e.name.contains("error") || e.name.contains("warning")) NikTheme.extended.warning else scheme.primary
                                    )
                                    Text("+${duration(e.time - traceStart)}", style = MaterialTheme.typography.labelSmall, color = scheme.outline, fontFamily = FontFamily.Monospace)
                                }
                                e.attributes.forEach { (k, v) ->
                                    SelectionContainer { Text(if (k == "message") v else "$k: $v", style = MaterialTheme.typography.bodySmall) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A JSON value, formatted (off the main thread), scrollable, copyable. */
@Composable
private fun JsonBlock(title: String, raw: String, context: Context) {
    val text by produceState(initialValue = raw, raw) {
        value = withContext(Dispatchers.Default) { runCatching { pretty.toJson(JsonParser.parseString(raw)) }.getOrDefault(raw) }
    }
    val ext = NikTheme.extended
    val copied = stringResource(R.string.copied, title)
    Spacer(Modifier.height(Spacing.s))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(stringResource(R.string.trace_chars, raw.length), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        IconButton(onClick = {
            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(title, text))
            Toast.makeText(context.applicationContext, copied, Toast.LENGTH_SHORT).show()
        }) { Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.cd_copy, title), modifier = Modifier.size(18.dp)) }
    }
    Surface(color = ext.codeBackground, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
        Box(
            Modifier.heightIn(max = 280.dp)
                .verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(Spacing.s)
        ) {
            SelectionContainer {
                Text(text, color = ext.codeText, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
