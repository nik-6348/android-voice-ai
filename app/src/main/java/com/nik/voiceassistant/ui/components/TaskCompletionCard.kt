package com.nik.voiceassistant.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nik.voiceassistant.R
import com.nik.voiceassistant.tools.TaskPlan
import com.nik.voiceassistant.ui.theme.NikTheme
import com.nik.voiceassistant.ui.theme.Spacing
import kotlin.math.cos
import kotlin.math.sin

/**
 * Summary card displayed when a multi-step task completes successfully.
 */
@Composable
fun TaskCompletionCard(
    plan: TaskPlan,
    onViewDetails: () -> Unit,
    onNewTask: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val ext = NikTheme.extended
    val cyanAccent = Color(0xFF00E5FF)
    val goldAccent = Color(0xFFFFD700)
    val cardBorder = Brush.horizontalGradient(
        listOf(cyanAccent.copy(alpha = 0.35f), goldAccent.copy(alpha = 0.2f))
    )

    val infiniteTransition = rememberInfiniteTransition(label = "sparkle")
    val sparkleProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sparkle_progress"
    )

    val steps = safeList<String>(plan.steps)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, cardBorder, MaterialTheme.shapes.large)
            .animateContentSize(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerHigh)
    ) {
        Column(Modifier.padding(Spacing.m)) {
            // Header Row: Emoji + Title + Sparkle + Done Badge
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = "🎉", fontSize = 24.sp)
                Spacer(Modifier.width(Spacing.s))
                Text(
                    text = stringResource(R.string.task_done_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = scheme.onSurface
                )
                Spacer(Modifier.width(Spacing.xs))
                Canvas(modifier = Modifier.size(24.dp)) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val alpha = (1f - sparkleProgress).coerceIn(0f, 1f)
                    val dist = sparkleProgress * (size.width / 2.2f)
                    val dotRadius = ((1f - sparkleProgress * 0.4f) * 2.5.dp.toPx()).coerceAtLeast(1f)
                    for (i in 0 until 5) {
                        val rad = Math.toRadians((72.0 * i)).toFloat()
                        val pos = Offset(
                            x = center.x + cos(rad) * dist,
                            y = center.y + sin(rad) * dist
                        )
                        drawCircle(
                            color = goldAccent.copy(alpha = alpha),
                            radius = dotRadius,
                            center = pos
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Badge(
                    text = stringResource(R.string.plan_status_done),
                    container = ext.successContainer,
                    content = ext.onSuccessContainer
                )
            }

            // Goal subtitle
            Spacer(Modifier.size(Spacing.xs))
            Text(
                text = safeString(plan.goal),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            // Divider
            HorizontalDivider(
                modifier = Modifier.padding(vertical = Spacing.s),
                color = scheme.outlineVariant.copy(alpha = 0.5f)
            )

            // Completed steps checklist
            steps.forEachIndexed { index, stepText ->
                val n = index + 1
                val detail = plan.stepDetails?.get(n)?.takeIf { it.isNotBlank() }
                AnimatedVisibility(
                    visible = true,
                    enter = fadeIn(tween(delayMillis = index * 50)) + slideInVertically(
                        animationSpec = tween(delayMillis = index * 50),
                        initialOffsetY = { it / 2 }
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = ext.success,
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .size(16.dp)
                        )
                        Spacer(Modifier.width(Spacing.s))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stepText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = scheme.onSurface
                            )
                            if (detail != null) {
                                Text(
                                    text = detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 1.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Action Buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.m),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s)
            ) {
                OutlinedButton(
                    onClick = onViewDetails,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = stringResource(R.string.task_done_view_details),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                Button(
                    onClick = onNewTask,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = stringResource(R.string.task_done_new_task),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }
}
