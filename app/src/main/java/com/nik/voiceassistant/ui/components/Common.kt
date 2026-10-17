package com.nik.voiceassistant.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.nik.voiceassistant.TranscriptItem
import com.nik.voiceassistant.ui.theme.Spacing

// ---- Null safety for data loaded with Gson (fields declared non-null can be null at runtime) ----

/** A String field from Gson-loaded data that may be null at runtime. */
fun safeString(value: Any?): String = (value as? String).orEmpty()

/** The item's text, never null (Gson can leave it null in saved history). */
val TranscriptItem.safeText: String get() = safeString(text)
val TranscriptItem.safeSender: String get() = safeString(sender)
val TranscriptItem.safeId: String get() = safeString(id)
val TranscriptItem.safeTime: String get() = safeString(timeFormatted)

/** A list from a Gson-loaded field that may be null, without null entries. */
inline fun <reified T> safeList(value: Any?): List<T> = (value as? List<*>)?.filterIsInstance<T>().orEmpty()

/**
 * Unique keys for a lazy list of transcript items: the item id, or the position when it is missing
 * or repeated (duplicate keys crash a LazyColumn).
 */
fun transcriptKeys(items: List<TranscriptItem>): List<String> {
    val seen = HashSet<String>(items.size * 2)
    return items.mapIndexed { i, item ->
        val id = item.safeId
        if (id.isNotEmpty() && seen.add(id)) id else "pos-$i"
    }
}

// ---- Lifecycle ----

/** Goes up every time the screen resumes (e.g. back from system settings): use it as a remember key. */
@Composable
fun rememberResumeCount(): Int {
    val owner = LocalLifecycleOwner.current
    var count by remember { mutableIntStateOf(0) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) count++ }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return count
}

// ---- Building blocks ----

/** Screen title with an optional subtitle and actions on the right. */
@Composable
fun ScreenHeader(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier.fillMaxWidth().padding(top = Spacing.m, bottom = Spacing.s),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() }
            )
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        actions()
    }
}

/** A titled group of settings or details. */
@Composable
fun SectionCard(
    title: String,
    icon: ImageVector?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(Spacing.m)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) { heading() }) {
                icon?.let {
                    Icon(it, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(Spacing.s))
                }
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            }
            subtitle?.let {
                Spacer(Modifier.size(Spacing.xs))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.size(Spacing.s))
            content()
        }
    }
}

/** A setting with a switch: the whole row toggles it (one TalkBack stop, 48 dp+ target). */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title, style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            )
            description?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(Spacing.m))
        // The row handles the click; the switch only shows the state
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** One option of a single choice: the whole row selects it. */
@Composable
fun RadioRow(
    title: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    badge: String? = null
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = Spacing.s),
        verticalAlignment = Alignment.Top
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(top = 2.dp, end = Spacing.m))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f, fill = false))
                badge?.let {
                    Spacer(Modifier.width(Spacing.s))
                    Badge(it)
                }
            }
            description?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A small tonal label. */
@Composable
fun Badge(
    text: String,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: Color = MaterialTheme.colorScheme.onSecondaryContainer
) {
    Surface(color = container, contentColor = content, shape = CircleShape, modifier = modifier) {
        Text(text, style = MaterialTheme.typography.labelSmall, maxLines = 1, modifier = Modifier.padding(horizontal = Spacing.s, vertical = 2.dp))
    }
}

/** Centered message for an empty list, with an optional action. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    Box(modifier.fillMaxWidth().padding(Spacing.l), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(72.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(32.dp))
                }
            }
            Spacer(Modifier.size(Spacing.xs))
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            action?.let {
                Spacer(Modifier.size(Spacing.s))
                it()
            }
        }
    }
}

/** "Are you sure?" for anything that can't be undone. */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = true,
    icon: ImageVector? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = icon?.let { { Icon(it, contentDescription = null) } },
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } }
    )
}

/** Content padding for a screen's scrolling list (8 dp grid, room at the bottom). */
val ListPadding = PaddingValues(start = Spacing.m, end = Spacing.m, bottom = Spacing.l)
