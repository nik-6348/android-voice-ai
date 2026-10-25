package com.nik.voiceassistant.ui.screens

import com.nik.voiceassistant.TranscriptItem
import com.nik.voiceassistant.ui.components.safeSender
import com.nik.voiceassistant.ui.components.safeText

fun countActions(messages: List<TranscriptItem>): Int =
    messages.count { it.safeSender == "tool" }

fun countDistinctApps(messages: List<TranscriptItem>): Int =
    messages.filter { it.safeSender == "tool" && (it.safeText.contains("open_app") || it.safeText.contains("launch_app")) }
        .map { it.safeText.substringAfter(": ").substringBefore(" ").trim().lowercase() }
        .filter { it.isNotBlank() }
        .distinct()
        .size
