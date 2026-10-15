package com.nik.voiceassistant.ui.screens

import android.content.Intent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Token
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nik.voiceassistant.VoiceAssistantViewModel
import com.nik.voiceassistant.data.trace.AgentTrace
import com.nik.voiceassistant.data.trace.AgentTrace.Kind
import com.nik.voiceassistant.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Live view of what the agent is doing and why: the current plan with its steps and thinking, key
 * numbers (reply latency, tool success, reconnects, tokens), and every event as it happens — the
 * user's words, plans and decisions, each tool call with its input, result and time, warnings,
 * errors and network changes. Past days are read from the trace files on the phone; they can be
 * shared, deleted one day at a time or all at once, and deleted automatically after N days.
 */
@Composable
fun ActivityTab(viewModel: VoiceAssistantViewModel) {
    val context = LocalContext.current
    val live by AgentTrace.events.collectAsState()
    val files by AgentTrace.files.collectAsState()
    val retention by AgentTrace.retention.collectAsState()
    val plan by viewModel.plan.collectAsState()
    val tool by viewModel.activeToolStatus.collectAsState()

    // null = live; else the name of a saved day's file
    var day by remember { mutableStateOf<String?>(null) }
    var dayEvents by remember { mutableStateOf<List<AgentTrace.Event>>(emptyList()) }
    var filter by remember { mutableStateOf(Filter.ALL) }
    var confirmDelete by remember { mutableStateOf<String?>(null) } // a file name, or "*" for all

    val events = if (day == null) live else dayEvents
    val shown = events.filter { filter.kinds == null || it.kind in filter.kinds!! }.asReversed()
    val metrics = AgentTrace.metrics(events)

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 90.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Agent Activity", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        if (day == null) {
                            Spacer(Modifier.width(8.dp))
                            LiveDot()
                        }
                    }
                    Text(
                        if (day == null) "Live: what the agent does and decides, as it happens" else "Saved trace of ${day!!.removeSuffix(".jsonl")}",
                        color = TextSecondary, fontSize = 12.sp
                    )
                }
                IconButton(onClick = {
                    val text = AgentTrace.export(events)
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text.takeLast(90_000)), "Share trace"))
                }) { Icon(Icons.Default.Share, "Share", tint = TextSecondary) }
            }
        }

        // Which trace: live, or a saved day
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("Live", day == null) { day = null }
                files.forEach { f ->
                    Chip("${f.day} · ${size(f.bytes)}", day == f.name) {
                        day = f.name
                        dayEvents = AgentTrace.readDay(f.name)
                    }
                }
            }
        }

        // Numbers
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Metric(Icons.Default.Speed, "Reply latency", metrics.avgLatencyMs?.let { "$it ms" } ?: "–", metrics.lastLatencyMs?.let { "last $it ms" })
                val success = if (metrics.toolCalls == 0) "–" else "${(100 * (metrics.toolCalls - metrics.toolFailures)) / metrics.toolCalls}%"
                Metric(Icons.Default.Build, "Tool success", success, "${metrics.toolCalls} calls")
                Metric(Icons.Default.PlayCircle, "Avg tool time", metrics.avgToolMs?.let { "$it ms" } ?: "–", null)
                Metric(Icons.Default.Wifi, "Reconnects", metrics.reconnects.toString(), "${metrics.errors} errors")
                Metric(Icons.Default.Token, "Tokens", metrics.tokens.toString(), null)
            }
        }

        // The agent's current plan and thinking (live only)
        if (day == null) plan?.let { p ->
            item {
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AccountTree, null, tint = PurpleAccent, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Current plan · ${p.status}", color = PurpleAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(p.goal, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    p.steps.forEachIndexed { i, step ->
                        val n = i + 1
                        val done = n < p.currentStep || p.status == "done"
                        val now = n == p.currentStep && p.status != "done"
                        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (done) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, null,
                                tint = if (done) GreenActive else if (now) CyanAccent else TextMuted, modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(step, color = if (now) TextPrimary else TextSecondary, fontSize = 12.sp, fontWeight = if (now) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                    p.note?.let {
                        Spacer(Modifier.height(6.dp))
                        Text("💭 $it", color = if (p.status == "blocked") Color(0xFFFCD34D) else Color(0xFFC9B8F5), fontSize = 12.sp)
                    }
                    tool?.let {
                        Spacer(Modifier.height(4.dp))
                        Text("⚙ $it", color = CyanAccent, fontSize = 12.sp)
                    }
                    if (p.tried.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text("Tried: ${p.tried.toList().joinToString(", ")}", color = TextMuted, fontSize = 11.sp)
                    }
                }
            }
        }

        // Filters
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Filter.values().forEach { f -> Chip(f.title, filter == f) { filter = f } }
            }
        }

        if (shown.isEmpty()) item {
            Text(
                if (day == null) "Nothing yet. Start a conversation and every step will show up here." else "No events in this file.",
                color = TextMuted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 24.dp)
            )
        }
        items(shown, key = { "${it.time}-${it.kind}-${it.text.hashCode()}" }) { e -> EventRow(e) }

        // Storage: automatic deletion and deleting by hand
        item {
            Card {
                Text("Trace storage", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("Saved on this phone only (app storage). ${files.size} day(s), ${size(files.sumOf { it.bytes })}.", color = TextSecondary, fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Delete old traces automatically", color = TextPrimary, fontSize = 13.sp)
                        Text(if (retention.autoDelete) "Keeps the last ${retention.keepDays} days" else "Keeps everything until you delete it", color = TextMuted, fontSize = 11.sp)
                    }
                    Switch(
                        checked = retention.autoDelete,
                        onCheckedChange = { AgentTrace.setRetention(it, retention.keepDays) },
                        colors = SwitchDefaults.colors(checkedThumbColor = BgDark, checkedTrackColor = GreenActive)
                    )
                }
                if (retention.autoDelete) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Keep for", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { AgentTrace.setRetention(true, retention.keepDays - 1) }) { Text("−", color = CyanAccent, fontSize = 18.sp) }
                    Text("${retention.keepDays} days", color = TextPrimary, fontSize = 13.sp)
                    TextButton(onClick = { AgentTrace.setRetention(true, retention.keepDays + 1) }) { Text("+", color = CyanAccent, fontSize = 18.sp) }
                }
                Spacer(Modifier.height(4.dp))
                Row {
                    if (day != null) TextButton(onClick = { confirmDelete = day }) {
                        Icon(Icons.Default.Delete, null, tint = RedStop, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Delete this day", color = RedStop, fontSize = 12.sp)
                    }
                    TextButton(onClick = { confirmDelete = "*" }) {
                        Icon(Icons.Default.Delete, null, tint = RedStop, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Delete all traces", color = RedStop, fontSize = 12.sp)
                    }
                }
            }
        }
    }

    confirmDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(if (target == "*") "Delete all traces?" else "Delete ${target.removeSuffix(".jsonl")}?") },
            text = { Text("This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    if (target == "*") AgentTrace.deleteAll() else AgentTrace.deleteDay(target)
                    if (target == "*" || target == day) { day = null; dayEvents = emptyList() }
                    confirmDelete = null
                }) { Text("Delete", color = RedStop) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
            containerColor = SurfaceCard, titleContentColor = TextPrimary, textContentColor = TextSecondary
        )
    }
}

private enum class Filter(val title: String, val kinds: Set<Kind>?) {
    ALL("All", null),
    DECISIONS("Decisions", setOf(Kind.PLAN, Kind.WARNING)),
    TOOLS("Tools", setOf(Kind.TOOL)),
    TALK("Conversation", setOf(Kind.USER, Kind.REPLY, Kind.LATENCY)),
    SYSTEM("Network & errors", setOf(Kind.NETWORK, Kind.ERROR, Kind.SESSION, Kind.USAGE))
}

private fun size(bytes: Long) = if (bytes < 1024) "$bytes B" else "${bytes / 1024} KB"

private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

private fun style(kind: Kind): Pair<ImageVector, Color> = when (kind) {
    Kind.SESSION -> Icons.Default.PlayCircle to BrandCyan
    Kind.NETWORK -> Icons.Default.Wifi to TextSecondary
    Kind.USER -> Icons.Default.Person to Color(0xFF93C5FD)
    Kind.REPLY -> Icons.Default.RecordVoiceOver to BrandViolet
    Kind.LATENCY -> Icons.Default.Speed to Color(0xFF5EEAD4)
    Kind.TOOL -> Icons.Default.Build to CyanAccent
    Kind.PLAN -> Icons.Default.AccountTree to PurpleAccent
    Kind.WARNING -> Icons.Default.WarningAmber to Color(0xFFFBBF24)
    Kind.ERROR -> Icons.Default.ErrorOutline to RedStop
    Kind.USAGE -> Icons.Default.Token to TextMuted
}

@Composable
private fun EventRow(e: AgentTrace.Event) {
    var open by remember { mutableStateOf(false) }
    val (icon, tint) = style(e.kind)
    val color = if (e.ok == false) RedStop else tint
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceDark)
            .clickable { open = !open }
            .padding(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(16.dp).padding(top = 1.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.kind.name.lowercase().replaceFirstChar { it.uppercase() }, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                if (e.ok == false) Text("  failed", color = RedStop, fontSize = 11.sp)
                Spacer(Modifier.weight(1f))
                e.ms?.takeIf { e.kind != Kind.USAGE }?.let {
                    Text("$it ms", color = if (it > 3000) Color(0xFFFBBF24) else TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(8.dp))
                }
                if (e.kind == Kind.USAGE) e.ms?.let {
                    Text("$it tok", color = TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(8.dp))
                }
                Text(clock.format(Date(e.time)), color = TextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            Text(
                e.text, color = TextPrimary, fontSize = 12.sp, lineHeight = 16.sp,
                maxLines = if (open) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
                fontFamily = if (e.kind == Kind.TOOL) FontFamily.Monospace else FontFamily.Default
            )
        }
    }
}

@Composable
private fun Metric(icon: ImageVector, title: String, value: String, sub: String?) {
    Column(
        Modifier
            .width(128.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceCard)
            .border(1.dp, GlassBorder, RoundedCornerShape(14.dp))
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = CyanAccent, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(title, color = TextSecondary, fontSize = 11.sp, maxLines = 1)
        }
        Spacer(Modifier.height(4.dp))
        Text(value, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        sub?.let { Text(it, color = TextMuted, fontSize = 10.sp) }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text, color = if (selected) BgDark else TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) CyanAccent else SurfaceCard)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceCard)
            .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) { content() }
}

@Composable
private fun LiveDot() {
    val pulse = rememberInfiniteTransition(label = "live")
    val a by pulse.animateFloat(0.3f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "a")
    Box(Modifier.size(8.dp).alpha(a).clip(CircleShape).background(GreenActive))
}
