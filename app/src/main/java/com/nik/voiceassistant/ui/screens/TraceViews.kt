package com.nik.voiceassistant.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.nik.voiceassistant.data.trace.Tracer
import com.nik.voiceassistant.data.trace.Tracer.Kind
import com.nik.voiceassistant.data.trace.Tracer.SpanData
import com.nik.voiceassistant.data.trace.Tracer.Status
import com.nik.voiceassistant.data.trace.Tracer.TraceData
import com.nik.voiceassistant.ui.theme.*
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

private fun kindColor(kind: Kind, status: Status): Color = when {
    status == Status.ERROR -> RedStop
    kind == Kind.REQUEST -> BrandCyan
    kind == Kind.LLM -> BrandViolet
    kind == Kind.TOOL -> Color(0xFF2DD4BF)
    else -> TextSecondary
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
    val total = (trace.end ?: System.currentTimeMillis()) - trace.start
    val status = when {
        trace.end == null -> "running" to CyanAccent
        root.status == Status.ERROR || trace.errorCount > 0 -> "${trace.errorCount} error${if (trace.errorCount == 1) "" else "s"}" to RedStop
        else -> "ok" to GreenActive
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceDark)
            .border(1.dp, GlassBorder, RoundedCornerShape(14.dp))
            .clickable(onClick = onOpen)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                (if (root.kind == Kind.SESSION) "🔌 " else "🗣 ") + trace.title(),
                color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                status.first, color = status.second, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(CircleShape).background(status.second.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
        root.attributes["agent.goal"]?.let {
            Spacer(Modifier.height(2.dp))
            Text("🎯 $it", color = PurpleAccent, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "${time.format(Date(trace.start))} · ${duration(trace.end?.let { total })} · ${trace.toolCount} tool call${if (trace.toolCount == 1) "" else "s"}" +
                (root.attributes["gen_ai.usage.total_tokens"]?.let { " · $it tokens" } ?: ""),
            color = TextSecondary, fontSize = 11.sp
        )
        Spacer(Modifier.height(8.dp))
        MiniWaterfall(trace, total)
    }
}

/** The spans of a trace as thin coloured bars on one line. */
@Composable
private fun MiniWaterfall(trace: TraceData, total: Long) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.05f))) {
        val w = maxWidth
        trace.spans.filter { it.parentId != null }.forEach { s ->
            val start = (s.start - trace.start).toFloat() / total.coerceAtLeast(1)
            val len = ((s.end ?: System.currentTimeMillis()) - s.start).toFloat() / total.coerceAtLeast(1)
            Box(
                Modifier
                    .offset(x = w * start.coerceIn(0f, 1f))
                    .width((w * len.coerceIn(0f, 1f)).coerceAtLeast(2.dp))
                    .height(6.dp)
                    .background(kindColor(s.kind, s.status))
            )
        }
    }
}

/** A trace opened: header, waterfall, and each span's details. */
@Composable
fun TraceDetail(trace: TraceData, onBack: () -> Unit) {
    val context = LocalContext.current
    val root = trace.root
    val total = ((trace.end ?: System.currentTimeMillis()) - trace.start).coerceAtLeast(1)
    // Spans in time order, each with its depth under the root
    val depth = remember(trace) {
        val byId = trace.spans.associateBy { it.spanId }
        trace.spans.associate { s -> s.spanId to generateSequence(s) { byId[it.parentId ?: ""] }.count() - 1 }
    }
    val ordered = trace.spans.sortedWith(compareBy({ it.parentId != null }, { it.start }))

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 90.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextPrimary) }
                Text("Trace", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = {
                    val json = pretty.toJson(JsonParser.parseString(Tracer.otlp(trace.spans).toString()))
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_TEXT, json), "Share trace (OTLP JSON)"))
                }) { Icon(Icons.Default.Share, "Share", tint = TextSecondary) }
            }
        }
        item {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(SurfaceCard).border(1.dp, GlassBorder, RoundedCornerShape(16.dp)).padding(14.dp)
            ) {
                Text(trace.title(), color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                root.attributes["agent.goal"]?.let { Text("🎯 $it", color = PurpleAccent, fontSize = 12.sp) }
                Spacer(Modifier.height(6.dp))
                Text(
                    "${time.format(Date(trace.start))} · ${duration(trace.end?.let { it - trace.start })} · ${trace.spans.size} spans · " +
                        "${trace.toolCount} tool calls · ${trace.errorCount} errors" +
                        (root.attributes["gen_ai.usage.total_tokens"]?.let { " · $it tokens" } ?: ""),
                    color = TextSecondary, fontSize = 11.sp
                )
                Text("trace ${trace.traceId}", color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
        }
        items(ordered, key = { it.spanId }) { span ->
            SpanRow(span, depth[span.spanId] ?: 0, trace.start, total, context)
        }
    }
}

@Composable
private fun SpanRow(span: SpanData, depth: Int, traceStart: Long, total: Long, context: Context) {
    var open by remember { mutableStateOf(span.kind == Kind.TOOL && span.status == Status.ERROR) }
    val color = kindColor(span.kind, span.status)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = (depth * 12).dp)
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceDark)
            .clickable { open = !open }
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(6.dp))
            Text(span.name, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(duration(span.durationMs), color = if ((span.durationMs ?: 0) > 3000) Color(0xFFFBBF24) else TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = TextMuted, modifier = Modifier.size(18.dp))
        }
        // Where it sits in the request's time
        Spacer(Modifier.height(6.dp))
        BoxWithConstraints(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.05f))) {
            val start = (span.start - traceStart).toFloat() / total
            val len = ((span.end ?: System.currentTimeMillis()) - span.start).toFloat() / total
            Box(
                Modifier.offset(x = maxWidth * start.coerceIn(0f, 1f)).width((maxWidth * len.coerceIn(0f, 1f)).coerceAtLeast(2.dp)).height(5.dp)
                    .clip(CircleShape).background(color)
            )
        }
        Text(
            "+${duration(span.start - traceStart)} · ${span.kind.name.lowercase()} · ${span.status.name.lowercase()}" + (span.statusMessage?.let { " · $it" } ?: ""),
            color = if (span.status == Status.ERROR) RedStop else TextMuted, fontSize = 10.sp, maxLines = if (open) 6 else 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp)
        )

        if (open) {
            // Tool input and output first, as formatted JSON
            listOf("gen_ai.tool.call.arguments" to "Input", "gen_ai.tool.call.result" to "Output").forEach { (key, title) ->
                span.attributes[key]?.let { JsonBlock(title, it, context) }
            }
            span.attributes.filterKeys { it != "gen_ai.tool.call.arguments" && it != "gen_ai.tool.call.result" }.forEach { (k, v) ->
                Row(Modifier.padding(top = 6.dp)) {
                    Text(k, color = TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(0.42f))
                    SelectionContainer(Modifier.weight(0.58f)) { Text(v, color = TextPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
                }
            }
            if (span.events.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Events", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                span.events.forEach { e ->
                    Column(Modifier.padding(top = 4.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.04f)).padding(8.dp)) {
                        Row {
                            Text(e.name, color = if (e.name.contains("error") || e.name.contains("warning")) Color(0xFFFBBF24) else CyanAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Text("+${duration(e.time - traceStart)}", color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        }
                        e.attributes.forEach { (k, v) ->
                            SelectionContainer { Text(if (k == "message") v else "$k: $v", color = TextPrimary, fontSize = 11.sp) }
                        }
                    }
                }
            }
        }
    }
}

/** A JSON value, formatted, scrollable, copyable. */
@Composable
private fun JsonBlock(title: String, raw: String, context: Context) {
    val text = remember(raw) { runCatching { pretty.toJson(JsonParser.parseString(raw)) }.getOrDefault(raw) }
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text("${raw.length} chars", color = TextMuted, fontSize = 10.sp)
        IconButton(onClick = {
            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(title, text))
            Toast.makeText(context, "$title copied", Toast.LENGTH_SHORT).show()
        }, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.ContentCopy, "Copy", tint = TextMuted, modifier = Modifier.size(14.dp)) }
    }
    Box(
        Modifier.fillMaxWidth().heightIn(max = 280.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF0B0F19))
            .verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(8.dp)
    ) {
        SelectionContainer { Text(text, color = Color(0xFFB5F5EC), fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 14.sp) }
    }
}
