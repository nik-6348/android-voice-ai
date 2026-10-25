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
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nik.voiceassistant.R
import com.nik.voiceassistant.ui.theme.Spacing

/**
 * Returns a list of domain sources relevant to the search query.
 */
fun defaultResearchSources(query: String): List<String> {
    val q = query.lowercase()
    return when {
        q.contains("flight") || q.contains("travel") || q.contains("train") ->
            listOf("Google Flights", "MakeMyTrip", "InDigo", "IRCTC", "RedBus", "Skyscanner")
        q.contains("product") || q.contains("buy") || q.contains("price") ->
            listOf("Google Shopping", "Amazon", "Flipkart", "Snapdeal")
        q.contains("news") || q.contains("latest") ->
            listOf("Google News", "Times of India", "NDTV", "The Hindu")
        else -> listOf("Google Search", "Wikipedia", "Web Results")
    }
}

/**
 * Displays live research progress with animated multi-source chips during web search tool calls.
 */
@Composable
fun ResearchCard(
    query: String,
    sources: List<String>,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val cyanAccent = Color(0xFF00E5FF)
    val goldAccent = Color(0xFFFFD700)
    val cardBorder = Brush.horizontalGradient(
        listOf(cyanAccent.copy(alpha = 0.35f), goldAccent.copy(alpha = 0.2f))
    )

    val infiniteTransition = rememberInfiniteTransition(label = "research_pulse")
    val alphaAnim by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "research_alpha"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, cardBorder, MaterialTheme.shapes.large)
            .animateContentSize(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerHigh)
    ) {
        Column(Modifier.padding(Spacing.m)) {
            // Header Row: Globe Icon + "Live Research" + RUNNING badge
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Outlined.Public,
                    contentDescription = null,
                    tint = cyanAccent,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(Spacing.s))
                Text(
                    text = stringResource(R.string.research_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = cyanAccent,
                    modifier = Modifier.weight(1f)
                )
                Badge(
                    text = stringResource(R.string.research_status_running),
                    container = scheme.primaryContainer,
                    content = scheme.onPrimaryContainer
                )
            }

            // Query text
            if (query.isNotBlank()) {
                Spacer(Modifier.size(Spacing.s))
                Text(
                    text = query,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = scheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Chips row (if sources available)
            if (sources.isNotEmpty()) {
                Spacer(Modifier.size(Spacing.s))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    itemsIndexed(sources) { idx, source ->
                        AnimatedVisibility(
                            visible = true,
                            enter = fadeIn(tween(delayMillis = idx * 80)) + slideInHorizontally(
                                animationSpec = tween(delayMillis = idx * 80),
                                initialOffsetX = { it }
                            )
                        ) {
                            val isSelected = idx == 0
                            FilterChip(
                                selected = isSelected,
                                onClick = { /* Visual display chip */ },
                                label = {
                                    Text(
                                        text = source,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = cyanAccent.copy(alpha = 0.2f),
                                    selectedLabelColor = cyanAccent,
                                    containerColor = scheme.surfaceContainerLow,
                                    labelColor = scheme.onSurfaceVariant
                                ),
                                border = if (isSelected) {
                                    FilterChipDefaults.filterChipBorder(
                                        enabled = true,
                                        selected = true,
                                        borderColor = cyanAccent.copy(alpha = 0.6f)
                                    )
                                } else null
                            )
                        }
                    }
                }
            }

            // Bottom searching text with pulsing alpha
            Spacer(Modifier.size(Spacing.s))
            val footerText = if (sources.isNotEmpty()) {
                stringResource(R.string.research_searching_sources, sources.size)
            } else {
                stringResource(R.string.research_searching_generic)
            }
            Text(
                text = footerText,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant.copy(alpha = alphaAnim)
            )
        }
    }
}
