package com.nik.voiceassistant.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.AutoAwesome
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

    val infiniteTransition = rememberInfiniteTransition(label = "plan_progress_pulse")
    val inProgressAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "in_progress_alpha"
    )

    val (statusLabel, statusContainer, statusContent) = when (status) {
        "done" -> Triple(stringResource(R.string.plan_status_done), ext.successContainer, ext.onSuccessContainer)
        "blocked" -> Triple(stringResource(R.string.plan_status_blocked), ext.warningContainer, ext.onWarningContainer)
        "failed" -> Triple(stringResource(R.string.plan_status_failed), scheme.errorContainer, scheme.onErrorContainer)
        else -> Triple(stringResource(R.string.plan_status_working), scheme.primaryContainer, scheme.onPrimaryContainer)
    }

    val cyanAccent = Color(0xFF00E5FF)
    val goldAccent = Color(0xFFFFD700)
    val cardBorder = Brush.horizontalGradient(listOf(cyanAccent.copy(alpha = 0.35f), goldAccent.copy(alpha = 0.2f)))

    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, cardBorder, MaterialTheme.shapes.large)
            .animateContentSize(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerHigh)
    ) {
        Column(Modifier.padding(Spacing.m)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Route, contentDescription = null, tint = cyanAccent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(Spacing.s))
                Text(stringResource(R.string.plan_title), style = MaterialTheme.typography.labelLarge, color = cyanAccent, modifier = Modifier.weight(1f))
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
                    color = if (status == "failed") scheme.error else cyanAccent,
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
                            .padding(vertical = 4.dp)
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
                                now -> cyanAccent
                                else -> scheme.outline
                            },
                            modifier = Modifier.padding(top = 2.dp).size(16.dp)
                        )
                        Spacer(Modifier.width(Spacing.s))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    stringResource(R.string.plan_step_line, n, steps[i]),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (now) scheme.onSurface else scheme.onSurfaceVariant,
                                    fontWeight = if (now) FontWeight.SemiBold else FontWeight.Normal,
                                    modifier = Modifier.weight(1f)
                                )
                                val timing = plan.stepTimings?.get(n)
                                if (done && timing != null) {
                                    val seconds = (timing / 1000L).coerceAtLeast(1L)
                                    Text(
                                        stringResource(R.string.plan_step_timing, seconds),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = scheme.onSurfaceVariant
                                    )
                                }
                            }
                            val detail = plan.stepDetails?.get(n)?.takeIf { it.isNotBlank() }
                            if (detail != null) {
                                Text(
                                    text = detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                            if (now) {
                                Text(
                                    text = stringResource(R.string.plan_step_in_progress),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = cyanAccent.copy(alpha = inProgressAlpha),
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }
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
            val reasoning = plan.reasoningLines?.filter { it.isNotBlank() }
            AnimatedVisibility(
                visible = !reasoning.isNullOrEmpty(),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.s)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.AutoAwesome,
                            contentDescription = null,
                            tint = cyanAccent,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(Spacing.xs))
                        Text(
                            stringResource(R.string.plan_reasoning_header, stringResource(R.string.app_name)),
                            style = MaterialTheme.typography.labelLarge,
                            color = cyanAccent
                        )
                    }
                    val recent = (reasoning ?: emptyList()).takeLast(5)
                    recent.forEachIndexed { idx, line ->
                        AnimatedVisibility(
                            visible = true,
                            enter = fadeIn(tween(delayMillis = idx * 60)) + slideInVertically(
                                animationSpec = tween(delayMillis = idx * 60),
                                initialOffsetY = { it / 2 }
                            )
                        ) {
                            Text(
                                text = "•  $line",
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 2.dp)
                            )
                        }
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
