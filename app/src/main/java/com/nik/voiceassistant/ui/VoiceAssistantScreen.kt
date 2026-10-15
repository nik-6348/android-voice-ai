package com.nik.voiceassistant.ui

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.nik.voiceassistant.R
import com.nik.voiceassistant.VoiceAssistantViewModel
import com.nik.voiceassistant.ui.screens.ActivityTab
import com.nik.voiceassistant.ui.screens.AssistantLiveTab
import com.nik.voiceassistant.ui.screens.AutomationsTab
import com.nik.voiceassistant.ui.screens.HistoryTab
import com.nik.voiceassistant.ui.screens.SettingsTab

enum class NavTab(@StringRes val title: Int, val icon: ImageVector, val selectedIcon: ImageVector) {
    ASSISTANT(R.string.tab_assistant, Icons.Outlined.Mic, Icons.Filled.Mic),
    AUTOMATIONS(R.string.tab_automations, Icons.Outlined.Bolt, Icons.Filled.Bolt),
    HISTORY(R.string.tab_history, Icons.Outlined.History, Icons.Filled.History),
    ACTIVITY(R.string.tab_activity, Icons.Outlined.Timeline, Icons.Filled.Timeline),
    SETTINGS(R.string.tab_settings, Icons.Outlined.Settings, Icons.Filled.Settings)
}

@Composable
fun VoiceAssistantScreen(viewModel: VoiceAssistantViewModel) {
    // Starts on Settings until a key is set; kept across rotation
    var selectedTab by rememberSaveable {
        mutableStateOf(if (!viewModel.hasCredentials()) NavTab.SETTINGS else NavTab.ASSISTANT)
    }
    // Each tab keeps its own saved state (scroll, search, unsaved settings) while another is shown
    val tabStates = rememberSaveableStateHolder()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            NavigationBar(windowInsets = WindowInsets(0)) {
                NavTab.values().forEach { tab ->
                    val selected = tab == selectedTab
                    NavigationBarItem(
                        selected = selected,
                        onClick = { selectedTab = tab },
                        icon = { Icon(if (selected) tab.selectedIcon else tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.title), maxLines = 1) }
                    )
                }
            }
        },
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                label = "tab"
            ) { target ->
                tabStates.SaveableStateProvider(target.name) {
                    when (target) {
                        NavTab.ASSISTANT -> AssistantLiveTab(viewModel, onNavigateToSettings = { selectedTab = NavTab.SETTINGS })
                        NavTab.AUTOMATIONS -> AutomationsTab(viewModel)
                        NavTab.HISTORY -> HistoryTab(viewModel, onNavigateToAssistant = { selectedTab = NavTab.ASSISTANT })
                        NavTab.ACTIVITY -> ActivityTab(viewModel)
                        NavTab.SETTINGS -> SettingsTab(viewModel)
                    }
                }
            }
        }
    }
}
