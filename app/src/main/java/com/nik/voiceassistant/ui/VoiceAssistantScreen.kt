package com.nik.voiceassistant.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nik.voiceassistant.VoiceAssistantViewModel
import com.nik.voiceassistant.ui.screens.AssistantLiveTab
import com.nik.voiceassistant.ui.screens.AutomationsTab
import com.nik.voiceassistant.ui.screens.HistoryTab
import com.nik.voiceassistant.ui.screens.SettingsTab
import com.nik.voiceassistant.ui.theme.*

enum class NavTab(val title: String, val icon: ImageVector) {
    ASSISTANT("Assistant", Icons.Default.Mic),
    AUTOMATIONS("Automations", Icons.Default.Bolt),
    HISTORY("History", Icons.Default.History),
    SETTINGS("Settings", Icons.Default.Settings)
}

@Composable
fun VoiceAssistantScreen(viewModel: VoiceAssistantViewModel) {
    val currentApiKey by viewModel.apiKey.collectAsState()
    
    // Default to Assistant tab, or Settings if API key is not yet set
    var selectedTab by remember {
        mutableStateOf(if (currentApiKey.isBlank()) NavTab.SETTINGS else NavTab.ASSISTANT)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            // Ambient luminous glow
            .background(
                Brush.radialGradient(
                    colors = listOf(BrandViolet.copy(alpha = 0.16f), Color.Transparent),
                    center = Offset(0f, 0f),
                    radius = 1200f
                )
            )
            .background(
                Brush.radialGradient(
                    colors = listOf(BrandCyan.copy(alpha = 0.12f), Color.Transparent),
                    center = Offset(1400f, 2600f),
                    radius = 1400f
                )
            )
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                GlassBottomNavigationBar(
                    selectedTab = selectedTab,
                    onTabSelected = { selectedTab = it }
                )
            },
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = paddingValues.calculateBottomPadding())
            ) {
                AnimatedContent(
                    targetState = selectedTab,
                    transitionSpec = {
                        fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(180))
                    },
                    label = "TabTransition"
                ) { target ->
                    when (target) {
                        NavTab.ASSISTANT -> AssistantLiveTab(
                            viewModel = viewModel,
                            onNavigateToSettings = { selectedTab = NavTab.SETTINGS }
                        )
                        NavTab.AUTOMATIONS -> AutomationsTab(
                            viewModel = viewModel
                        )
                        NavTab.HISTORY -> HistoryTab(
                            viewModel = viewModel,
                            onNavigateToAssistant = { selectedTab = NavTab.ASSISTANT }
                        )
                        NavTab.SETTINGS -> SettingsTab(
                            viewModel = viewModel
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GlassBottomNavigationBar(
    selectedTab: NavTab,
    onTabSelected: (NavTab) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(SurfaceDark.copy(alpha = 0.88f))
            .border(1.dp, GlassBorder, RoundedCornerShape(26.dp))
            .padding(horizontal = 6.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            NavTab.values().forEach { tab ->
                val isSelected = tab == selectedTab
                val interactionSource = remember { MutableInteractionSource() }

                val itemModifier = if (isSelected) {
                    Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    BrandCyan.copy(alpha = 0.22f),
                                    BrandViolet.copy(alpha = 0.22f)
                                )
                            )
                        )
                        .border(
                            1.dp,
                            BrandCyan.copy(alpha = 0.45f),
                            RoundedCornerShape(18.dp)
                        )
                } else {
                    Modifier.clip(RoundedCornerShape(18.dp))
                }

                Box(
                    modifier = itemModifier
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = { onTabSelected(tab) }
                        )
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = tab.title,
                            tint = if (isSelected) BrandCyan else TextMuted,
                            modifier = Modifier.size(19.dp)
                        )
                        if (isSelected) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = tab.title,
                                color = TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}
