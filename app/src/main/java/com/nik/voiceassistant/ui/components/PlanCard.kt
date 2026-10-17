package com.nik.voiceassistant.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nik.voiceassistant.R
import com.nik.voiceassistant.tools.TaskPlan
import com.nik.voiceassistant.ui.theme.NikTheme
import com.nik.voiceassistant.ui.theme.Spacing

/**
 * The assistant's live plan: the goal, a progress bar, the steps (current one highlighted; the list
 * folds to the current step and can be opened), its latest thinking and what it is doing right now.
 */
@Composable
fun PlanCard(
    plan: TaskPlan,
    toolStatus: String?,
    modifier: Modifier = Modifier,
    showTried: Boolean = false,
    startExpanded: Boolean = false
) {
    val scheme = MaterialTheme.colorScheme
    val ext = NikTheme.extended
    val steps = safeList<String>(plan.steps)
    val total = steps.size
    val status = safeString(plan.status)
    val finished = status == "done"
    val current = if (total == 0) 0 else plan.currentStep.coerceIn(1, total)
    val doneCount = if (finished) total else (current - 1).coerceAtLeast(0)
    var expanded by rememberSaveable { mutableStateOf(startExpanded) }

    val (statusLabel, statusContainer, statusContent) = when (status) {
        "done" -> Triple(stringResource(R.string.plan_status_done), ext.successContainer, ext.onSuccessContainer)
        "blocked" -> Triple(stringResource(R.string.plan_status_blocked), ext.warningContainer, ext.onWarningContainer)
        "failed" -> Triple(stringResource(R.string.plan_status_failed), scheme.errorContainer, scheme.onErrorContainer)
        else -> Triple(stringResource(R.string.plan_status_working), scheme.primaryContainer, scheme.onPrimaryContainer)
    }

    Card(
        modifier = modifier.fillMaxWidth().animateContentSize(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerHigh)
    ) {
        Column(Modifier.padding(Spacing.m)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Route, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(Spacing.s))
                Text(stringResource(R.string.plan_title), style = MaterialTheme.typography.labelLarge, color = scheme.primary, modifier = Modifier.weight(1f))
                Badge(statusLabel, container = statusContainer, content = statusContent)
            }
            Spacer(Modifier.size(Spacing.s))
            Text(
                safeString(plan.goal), style = MaterialTheme.typography.titleMedium, color = scheme.onSurface,
                maxLines = 3, overflow = TextOverflow.Ellipsis
            )
            if (total > 0) {
                Spacer(Modifier.size(Spacing.s))
                val progressText = stringResource(R.string.plan_progress, doneCount, total)
                LinearProgressIndicator(
                    progress = { doneCount / total.toFloat() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.extraSmall)
                        .semantics { contentDescription = progressText },
                    color = if (status == "failed") scheme.error else scheme.primary,
                    trackColor = scheme.surfaceContainerHighest
                )
                Spacer(Modifier.size(Spacing.s))
                // Folded: only the current step. Open: every step.
                val visible = if (expanded || total <= 3) steps.indices.toList() else listOf(current - 1)
                visible.forEach { i ->
                    val n = i + 1
                    val done = finished || n < current
                    val now = !finished && n == current
                    val stepState = stringResource(
                        when {
                            done -> R.string.plan_step_done
                            now -> R.string.plan_step_current
                            else -> R.string.plan_step_pending
                        }
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .semantics(mergeDescendants = true) { stateDescription = stepState },
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            when {
                                done -> Icons.Filled.CheckCircle
                                now && status == "failed" -> Icons.Filled.ErrorOutline
                                else -> Icons.Filled.RadioButtonUnchecked
                            },
                            contentDescription = null,
                            tint = when {
                                done -> ext.success
                                now -> scheme.primary
                                else -> scheme.outline
                            },
                            modifier = Modifier.padding(top = 2.dp).size(16.dp)
                        )
                        Spacer(Modifier.width(Spacing.s))
                        Text(
                            stringResource(R.string.plan_step_line, n, steps[i]),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (now) scheme.onSurface else scheme.onSurfaceVariant,
                            fontWeight = if (now) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
                if (total > 3) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clip(MaterialTheme.shapes.small)
                            .clickable { expanded = !expanded },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, tint = scheme.primary)
                        Spacer(Modifier.width(Spacing.s))
                        Text(
                            stringResource(if (expanded) R.string.plan_show_less else R.string.plan_show_all, total),
                            style = MaterialTheme.typography.labelLarge, color = scheme.primary
                        )
                    }
                }
            }
            val note = safeString(plan.note).takeIf { it.isNotBlank() }
            if (note != null) {
                Spacer(Modifier.size(Spacing.xs))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Outlined.Lightbulb, contentDescription = null, tint = scheme.tertiary, modifier = Modifier.padding(top = 2.dp).size(16.dp))
                    Spacer(Modifier.width(Spacing.s))
                    Text(
                        note, style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic,
                        color = when (status) {
                            "blocked" -> ext.warning
                            "failed" -> scheme.error
                            else -> scheme.onSurfaceVariant
                        }
                    )
                }
            }
            toolStatus?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.size(Spacing.xs))
                Text(
                    stringResource(R.string.status_working_on, it), style = MaterialTheme.typography.bodySmall,
                    color = scheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis
                )
            }
            if (showTried) {
                val tried = runCatching { synchronized(plan.tried) { plan.tried.toList() } }.getOrDefault(emptyList())
                if (tried.isNotEmpty()) {
                    Spacer(Modifier.size(Spacing.xs))
                    Text(
                        stringResource(R.string.plan_tried, tried.joinToString(", ")),
                        style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
