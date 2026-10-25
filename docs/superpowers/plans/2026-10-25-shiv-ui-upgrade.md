# Shiv UI Upgrade — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Upgrade Shiv's UI across 7 feature areas to match the demo images and exceed them in polish and information density.

**Architecture:** Extend `TaskPlan` with optional runtime fields (stepTimings, stepDetails, reasoningLines); add two new composables (`ResearchCard`, `TaskCompletionCard`); enhance PlanCard, HistoryTab, AutomationsTab, and AssistantLiveTab to wire all new components.

**Tech Stack:** Kotlin, Jetpack Compose, Material3, Kotlin StateFlow, Gson (already in project)

**Spec:** `docs/superpowers/specs/2026-10-25-shiv-ui-upgrade-design.md`

## Global Constraints

- Android API 26+ / Android 8.0 minimum
- All new strings go in `app/src/main/res/values/strings.xml` — no hardcoded English
- Design tokens: CyanAccent `#00E5FF`, GoldAccent `#FFD700` — use `NikTheme.extended` for success/warning; never hardcode colors that already exist in the theme
- No new library dependencies — Compose animation APIs already on classpath
- All new `@Composable` functions must have a `modifier: Modifier = Modifier` parameter
- Maintain all existing `semantics {}` / `contentDescription` / `liveRegion` annotations
- New fields on `TaskPlan` must be nullable with defaults so Gson deserialization of old data doesn't break
- Unit tests go in `app/src/test/java/com/nik/voiceassistant/`; run with `./gradlew testDebugUnitTest`

## Review Focus

1. **PlanCard with 0 steps** — `TaskPlan(goal="x", steps=emptyList(), currentStep=1, status="working", note=null)` should render without crash; `stepTimings`/`stepDetails`/`reasoningLines` are null-safe
2. **TaskCompletionCard with a done plan that has no stepDetails** — should show plain step text, not crash
3. **ResearchCard with empty sources list** — should show a generic "Searching…" state rather than blank
4. **HistoryTab with zero sessions** — analytics tiles must not appear; existing EmptyState still shows
5. **Timeline tab with sessions that have no tool messages** — "No actions recorded" empty state rather than crash

---

## Task 1: Extend TaskPlan with runtime fields

**Files:**
- Modify: `app/src/main/java/com/nik/voiceassistant/tools/ToolModels.kt`

**Interfaces:**
- Produces: `TaskPlan` with new optional fields:
  - `stepTimings: Map<Int, Long>?` — key = 1-based step index, value = elapsed ms
  - `stepDetails: Map<Int, String>?` — key = 1-based step index, value = sub-description shown under step title
  - `reasoningLines: List<String>?` — live reasoning bullets (ring buffer of last 8), newest last

- [x] **Step 1: Write the failing test** in `app/src/test/java/com/nik/voiceassistant/tools/TaskPlanTest.kt`

```kotlin
// TaskPlanTest.kt
class TaskPlanTest {
    @Test fun `new fields default to null`() {
        val plan = TaskPlan(goal = "g", steps = listOf("s1"), currentStep = 1, status = "working", note = null)
        assertNull(plan.stepTimings)
        assertNull(plan.stepDetails)
        assertNull(plan.reasoningLines)
    }

    @Test fun `stepTimings survives round-trip Gson`() {
        val plan = TaskPlan(
            goal = "g", steps = listOf("s1", "s2"), currentStep = 2, status = "working", note = null,
            stepTimings = mapOf(1 to 5000L)
        )
        val json = com.google.gson.Gson().toJson(plan)
        val back = com.google.gson.Gson().fromJson(json, TaskPlan::class.java)
        assertEquals(5000L, back.stepTimings?.get(1))
        assertNull(back.stepTimings?.get(2))
    }
}
```

- [x] **Step 2: Run test to confirm it fails**

  Run: `./gradlew testDebugUnitTest --tests "*.TaskPlanTest" 2>&1 | tail -20`
  Expected: FAIL — `TaskPlan` does not have `stepTimings`

- [x] **Step 3: Add three nullable fields to `TaskPlan` in `ToolModels.kt`**

  Add below `blockedCount: Int = 0`:
  ```kotlin
  val stepTimings: Map<Int, Long>? = null,
  val stepDetails: Map<Int, String>? = null,
  val reasoningLines: List<String>? = null,
  ```
  Also update `DeviceToolExecutor.kt:updatePlan()` — in the `TaskPlan(...)` constructor call, carry forward the three new fields from `old` when `sameGoal`:
  ```kotlin
  stepTimings = if (sameGoal) old?.stepTimings else null,
  stepDetails = if (sameGoal) old?.stepDetails else null,
  reasoningLines = if (sameGoal) old?.reasoningLines else null,
  ```

- [x] **Step 4: Run tests to verify both pass**

  Run: `./gradlew testDebugUnitTest --tests "*.TaskPlanTest" 2>&1 | tail -20`
  Expected: PASS (2 tests)

- [x] **Step 5: Commit**

  ```bash
  git add app/src/main/java/com/nik/voiceassistant/tools/ToolModels.kt \
          app/src/main/java/com/nik/voiceassistant/tools/DeviceToolExecutor.kt \
          app/src/test/java/com/nik/voiceassistant/tools/TaskPlanTest.kt
  git commit -m "feat: extend TaskPlan with stepTimings, stepDetails, reasoningLines

  Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
  ```

---

## Task 2: PlanCard v2 — timestamps, sub-descriptions, reasoning feed

**Files:**
- Modify: `app/src/main/java/com/nik/voiceassistant/ui/components/PlanCard.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `TaskPlan` with new fields from Task 1
- Produces: enhanced `PlanCard(plan: TaskPlan, toolStatus: String?, modifier, showTried, startExpanded)` — same signature, richer output

No unit tests for pure Compose UI — visual verification only.

- [x] **Step 1: Add new strings to `strings.xml`**

  Add inside `<resources>`:
  ```xml
  <string name="plan_step_timing">Completed • %1$ss</string>
  <string name="plan_reasoning_header">%1$s is reasoning…</string>
  <string name="plan_step_in_progress">In progress…</string>
  ```

- [x] **Step 2: Enhance step rows in `PlanCard.kt`**

  Inside the `visible.forEach { i -> ... }` block, after the existing `Row` with icon + step text:
  - Below the step title text, add `stepDetails[i+1]` as `bodySmall`, `onSurfaceVariant`, only when non-null and non-blank
  - For the current (`now`) step, add the `plan_step_in_progress` string as an animated `bodySmall` in cyanAccent below the detail (use `InfiniteTransition` alpha 0.4→1.0→0.4 with 1200ms period)
  - For done steps, add `stepTimings[i+1]` formatted as `plan_step_timing` string (divide ms by 1000) right-aligned on the same Row as the step number — use `Text(..., modifier = Modifier.weight(1f))` on the step text and `Text(timingStr)` after it

- [x] **Step 3: Add the Reasoning Feed section below the steps/expand button**

  After the existing expand-toggle Row, add:
  ```
  if (!reasoningLines.isNullOrEmpty()) {
      // "✦ Shiv is reasoning…" header row
      // AnimatedContent / stagger: each line slides in from bottom, 60ms stagger
      // Ring-buffer: show at most 5 lines (last 5 of reasoningLines)
      // Each line: bullet dot + bodySmall text in onSurfaceVariant
  }
  ```
  Use `AnimatedVisibility` wrapping the whole section (visible when reasoningLines non-empty).  
  Each bullet uses `Text("•  $line")` with 4dp start indent.  
  Header uses `Icons.Filled.AutoAwesome` (already imported in other files) in cyanAccent + `labelLarge` text using `plan_reasoning_header` string substituting `assistantName`. Since `PlanCard` doesn't receive `assistantName`, use the string resource `R.string.app_name` as fallback — this is the implementer's call.

- [x] **Step 4: Verify no crash with 0 steps**

  Trace through code manually: `steps.indices.toList()` on emptyList → emptyList, `forEach` is no-op. `reasoningLines` guard covers null. Confirm.

- [x] **Step 5: Build to catch compile errors**

  Run: `./gradlew assembleDebug 2>&1 | grep -E "error:|warning:" | head -30`
  Expected: 0 errors

- [x] **Step 6: Commit**

  ```bash
  git add app/src/main/java/com/nik/voiceassistant/ui/components/PlanCard.kt \
          app/src/main/res/values/strings.xml
  git commit -m "feat: PlanCard v2 — step timings, sub-descriptions, reasoning feed

  Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
  ```

---

## Task 3: TaskCompletionCard

**Files:**
- Create: `app/src/main/java/com/nik/voiceassistant/ui/components/TaskCompletionCard.kt`
- Modify: `app/src/main/java/com/nik/voiceassistant/ui/screens/AssistantLiveTab.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `TaskPlan` (from Task 1), `onViewDetails: () -> Unit`, `onNewTask: () -> Unit`
- Produces: `TaskCompletionCard(plan: TaskPlan, onViewDetails: () -> Unit, onNewTask: () -> Unit, modifier: Modifier = Modifier)`

- [x] **Step 1: Add strings to `strings.xml`**

  ```xml
  <string name="task_done_title">Task Completed!</string>
  <string name="task_done_view_details">View Full Details</string>
  <string name="task_done_new_task">Start New Task</string>
  ```

- [x] **Step 2: Create `TaskCompletionCard.kt`**

  Signature:
  ```kotlin
  @Composable
  fun TaskCompletionCard(
      plan: TaskPlan,
      onViewDetails: () -> Unit,
      onNewTask: () -> Unit,
      modifier: Modifier = Modifier
  )
  ```

  Layout:
  - `Card` with `surfaceContainerHigh` + cyan→gold horizontal border (same as PlanCard)
  - Header row: `🎉` emoji as `Text` (24sp) + `task_done_title` in `titleMedium` + success green badge
  - Goal subtitle: `plan.goal` in `bodyMedium`, `onSurfaceVariant`, max 2 lines
  - Divider (`HorizontalDivider`)
  - Steps: `plan.steps` iterated — each shows `Icons.Filled.CheckCircle` in `ext.success` + step text in `bodyMedium`. Animate each row entering with `slideInVertically { it } + fadeIn()`, staggered 50ms per item using `LaunchedEffect` + `Animatable`
  - Sparkle: 5 `Canvas`-drawn dots in a Row near the header, animating outward with `InfiniteTransition` (scale 0→1, alpha 1→0, 800ms, each offset 72° * i). Keep this subtle — max 6dp dot radius
  - Bottom row: `OutlinedButton("View Full Details", onClick = onViewDetails)` + `Button("Start New Task", onClick = onNewTask)`

- [x] **Step 3: Wire into `AssistantLiveTab.kt`**

  In the `Column` where `PlanCard` is shown:
  ```kotlin
  plan?.let { p ->
      if (p.status == "done") {
          TaskCompletionCard(
              plan = p,
              onViewDetails = { /* TODO Task 5: open trace/timeline dialog */ },
              onNewTask = { viewModel.startNewChat() },
              modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.xs)
          )
      } else {
          PlanCard(p, toolStatus = null, modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.xs))
      }
  }
  ```

- [x] **Step 4: Build to confirm no errors**

  Run: `./gradlew assembleDebug 2>&1 | grep -E "error:" | head -20`
  Expected: 0 errors

- [x] **Step 5: Commit**

  ```bash
  git add app/src/main/java/com/nik/voiceassistant/ui/components/TaskCompletionCard.kt \
          app/src/main/java/com/nik/voiceassistant/ui/screens/AssistantLiveTab.kt \
          app/src/main/res/values/strings.xml
  git commit -m "feat: add TaskCompletionCard shown when plan status=done

  Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
  ```

---

## Task 4: History Analytics Tiles

**Files:**
- Modify: `app/src/main/java/com/nik/voiceassistant/ui/screens/HistoryTab.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `sessions: List<ConversationSession>` (already computed in `HistoryTab`)
- Produces: `AnalyticsTileRow(sessions: List<ConversationSession>, modifier: Modifier = Modifier)` private composable inside `HistoryTab.kt`

- [x] **Step 1: Write unit test for analytics computation** in `app/src/test/java/com/nik/voiceassistant/ui/screens/HistoryAnalyticsTest.kt`

  ```kotlin
  class HistoryAnalyticsTest {
      @Test fun `action count from tool messages`() {
          val msg1 = TranscriptItem(id="1", sender="tool", text="Executed open_app: WhatsApp")
          val msg2 = TranscriptItem(id="2", sender="tool", text="Executed tap: confirm")
          val msg3 = TranscriptItem(id="3", sender="user", text="hello")
          val count = countActions(listOf(msg1, msg2, msg3))
          assertEquals(2, count)
      }

      @Test fun `app open count distinct apps`() {
          val msg1 = TranscriptItem(id="1", sender="tool", text="Executed open_app: WhatsApp")
          val msg2 = TranscriptItem(id="2", sender="tool", text="Executed open_app: WhatsApp")
          val msg3 = TranscriptItem(id="3", sender="tool", text="Executed launch_app: YouTube")
          val count = countDistinctApps(listOf(msg1, msg2, msg3))
          assertEquals(2, count)
      }
  }
  ```

  Add top-level pure functions `countActions(messages: List<TranscriptItem>): Int` and `countDistinctApps(messages: List<TranscriptItem>): Int` in a new file `app/src/main/java/com/nik/voiceassistant/ui/screens/HistoryAnalytics.kt`.

- [x] **Step 2: Run test to confirm failure**

  Run: `./gradlew testDebugUnitTest --tests "*.HistoryAnalyticsTest" 2>&1 | tail -15`
  Expected: FAIL

- [x] **Step 3: Create `HistoryAnalytics.kt`**

  ```kotlin
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
  ```

- [x] **Step 4: Run tests to confirm pass**

  Run: `./gradlew testDebugUnitTest --tests "*.HistoryAnalyticsTest" 2>&1 | tail -15`
  Expected: PASS (2 tests)

- [x] **Step 5: Add strings to `strings.xml`**

  ```xml
  <string name="analytics_tasks">Tasks\nDone</string>
  <string name="analytics_apps">Apps\nUsed</string>
  <string name="analytics_actions">Actions\nTaken</string>
  <string name="analytics_success">Success\nRate</string>
  ```

- [x] **Step 6: Add `AnalyticsTileRow` composable in `HistoryTab.kt`**

  Place it above the search bar inside `HistoryTab`, wrapped in `AnimatedVisibility(visible = sessions.isNotEmpty())`.

  Compute values from all sessions' messages:
  ```kotlin
  val allMessages = sessions.flatMap { it.safeMessages() }
  val totalActions = countActions(allMessages)
  val totalApps = countDistinctApps(allMessages)
  ```

  Layout: `Row(Modifier.fillMaxWidth().padding(bottom = Spacing.s))` containing 4 `AnalyticsTile` items equally weighted via `Modifier.weight(1f)`.

  Each `AnalyticsTile(value: String, label: String, modifier)`:
  - `Card(containerColor = surfaceContainerLow)` with 8dp padding
  - `Text(value, style = titleLarge, color = cyanAccent, textAlign = Center)` where `cyanAccent = Color(0xFF00E5FF)`
  - `Text(label, style = labelSmall, color = onSurfaceVariant, textAlign = Center)`

  Values: `"${sessions.size}"`, `"$totalApps"`, `"$totalActions"`, `"100%"` (always 100 for now per spec)

- [x] **Step 7: Build + run all tests**

  Run: `./gradlew testDebugUnitTest assembleDebug 2>&1 | grep -E "error:|FAIL|PASS" | head -20`
  Expected: 0 errors, no FAIL

- [x] **Step 8: Commit**

  ```bash
  git add app/src/main/java/com/nik/voiceassistant/ui/screens/HistoryTab.kt \
          app/src/main/java/com/nik/voiceassistant/ui/screens/HistoryAnalytics.kt \
          app/src/test/java/com/nik/voiceassistant/ui/screens/HistoryAnalyticsTest.kt \
          app/src/main/res/values/strings.xml
  git commit -m "feat: add analytics tiles to HistoryTab (tasks, apps, actions, success)

  Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
  ```

---

## Task 5: Action Timeline tab in History detail dialog

**Files:**
- Modify: `app/src/main/java/com/nik/voiceassistant/ui/screens/HistoryTab.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `ConversationSession.safeMessages(): List<TranscriptItem>` (already exists)
- Produces: `TimelineTab` private composable inside the `TranscriptViewerDialog`

- [x] **Step 1: Add strings**

  ```xml
  <string name="history_tab_chat">Chat</string>
  <string name="history_tab_timeline">Timeline</string>
  <string name="timeline_empty">No actions recorded for this session</string>
  <string name="timeline_started">Started task</string>
  <string name="timeline_action_prefix">T+%1$s</string>
  ```

- [x] **Step 2: Add tab state to `TranscriptViewerDialog`**

  Add `var selectedTab by rememberSaveable { mutableStateOf(0) }` inside the dialog.

  Replace the `HorizontalDivider` below the header row with:
  ```kotlin
  TabRow(selectedTabIndex = selectedTab) {
      Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 },
          text = { Text(stringResource(R.string.history_tab_chat)) })
      Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 },
          text = { Text(stringResource(R.string.history_tab_timeline)) })
  }
  ```

  Wrap the existing `LazyColumn` of `TranscriptBubble` in `if (selectedTab == 0)`.

  Add `else { TimelineTab(messages = messages) }`.

- [x] **Step 3: Implement `TimelineTab` private composable**

  Signature: `@Composable private fun TimelineTab(messages: List<TranscriptItem>)`

  Filter `tool` messages from the list; they form the timeline entries.

  For each entry, compute a relative time offset: `"T+${(index * 30).let { s -> "${s/60}:${(s%60).toString().padStart(2, '0')}" }}"` (approximation — 30 seconds per step, since real timestamps are not stored per spec builder note).

  Each row: `Row` with 8dp vertical padding:
  - A 10dp colored dot: `Box(Modifier.size(10.dp).clip(CircleShape).background(cyanAccent))`
  - 2dp vertical line connecting dots (drawn with `Canvas` — a vertical line from bottom of dot to top of next dot in `onSurfaceVariant.copy(0.3f)`)
  - Time label `labelSmall` in `onSurfaceVariant`
  - Action name extracted from tool text: `text.removePrefix("Executed ").substringBefore(":").replace('_', ' ').replaceFirstChar { it.uppercase() }`

  Show `EmptyState` (already available) when no tool messages exist with `timeline_empty` string.

- [x] **Step 4: Build to catch errors**

  Run: `./gradlew assembleDebug 2>&1 | grep "error:" | head -20`
  Expected: 0 errors

- [x] **Step 5: Commit**

  ```bash
  git add app/src/main/java/com/nik/voiceassistant/ui/screens/HistoryTab.kt \
          app/src/main/res/values/strings.xml
  git commit -m "feat: add Timeline tab to History detail dialog

  Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
  ```

---

## Task 6: ResearchCard — multi-source chips during web search

**Files:**
- Create: `app/src/main/java/com/nik/voiceassistant/ui/components/ResearchCard.kt`
- Modify: `app/src/main/java/com/nik/voiceassistant/ui/screens/AssistantLiveTab.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `toolStatus: String?` from ViewModel (already flows to `AssistantLiveTab`)
- Produces: `ResearchCard(query: String, sources: List<String>, modifier: Modifier = Modifier)`

- [x] **Step 1: Write unit test for source extraction** in `app/src/test/java/com/nik/voiceassistant/ui/components/ResearchCardTest.kt`

  ```kotlin
  class ResearchCardTest {
      @Test fun `known search domains produce labeled sources`() {
          val sources = defaultResearchSources("flight booking")
          assertTrue(sources.any { it.contains("Google") })
      }

      @Test fun `empty query returns non-empty source list`() {
          val sources = defaultResearchSources("")
          assertTrue(sources.isNotEmpty())
      }
  }
  ```

  Add pure function `defaultResearchSources(query: String): List<String>` in `ResearchCard.kt`.

- [x] **Step 2: Run test to confirm failure**

  Run: `./gradlew testDebugUnitTest --tests "*.ResearchCardTest" 2>&1 | tail -15`
  Expected: FAIL

- [x] **Step 3: Create `ResearchCard.kt`**

  Top-level pure function:
  ```kotlin
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
  ```

  `ResearchCard` composable:
  - `Card` with `surfaceContainerHigh` + globe icon in cyanAccent + "Live Research" label + `RUNNING` badge (same `Badge` composable already in project)
  - Query text in `bodyMedium`
  - `LazyRow` of `FilterChip` for each source; first chip is "selected" (cyan tint), others are un-selected; chips animate in with `slideInHorizontally` staggered 80ms each
  - Bottom: `"✦ Searching across ${sources.size} sources…"` in `bodySmall` animated alpha

- [x] **Step 4: Run test to confirm pass**

  Run: `./gradlew testDebugUnitTest --tests "*.ResearchCardTest" 2>&1 | tail -15`
  Expected: PASS

- [x] **Step 5: Wire into `AssistantLiveTab.kt`**

  In the `Column` body (in the `conversationStarted` branch), above `PlanCard`/`TaskCompletionCard`:
  ```kotlin
  val isSearching = toolStatus?.let { t ->
      t.contains("research", ignoreCase = true) || t.contains("search", ignoreCase = true)
          || t.contains("browse", ignoreCase = true) || t.contains("web", ignoreCase = true)
  } ?: false

  AnimatedVisibility(visible = isSearching, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
      plan?.let { p ->
          ResearchCard(
              query = p.goal,
              sources = defaultResearchSources(p.goal),
              modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.xs)
          )
      }
  }
  ```

- [x] **Step 6: Build + run all tests**

  Run: `./gradlew testDebugUnitTest assembleDebug 2>&1 | grep -E "error:|FAIL" | head -20`
  Expected: 0

- [x] **Step 7: Commit**

  ```bash
  git add app/src/main/java/com/nik/voiceassistant/ui/components/ResearchCard.kt \
          app/src/main/java/com/nik/voiceassistant/ui/screens/AssistantLiveTab.kt \
          app/src/test/java/com/nik/voiceassistant/ui/components/ResearchCardTest.kt \
          app/src/main/res/values/strings.xml
  git commit -m "feat: add ResearchCard with animated multi-source chips

  Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
  ```

---

## Task 7: Automation Run Progress card

**Files:**
- Modify: `app/src/main/java/com/nik/voiceassistant/ui/screens/AutomationsTab.kt`
- Modify: `app/src/main/java/com/nik/voiceassistant/VoiceAssistantViewModel.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `MacroEngine.runningMacroId: String?` (check if exposed) and current step from macro execution
- Produces: `AutomationRunCard` private composable inside `AutomationsTab.kt`

- [x] **Step 1: Check ViewModel for macro run state**

  Run: `grep -n "runningMacro\|macroRunning\|currentMacro" app/src/main/java/com/nik/voiceassistant/VoiceAssistantViewModel.kt app/src/main/java/com/nik/voiceassistant/data/macro/MacroEngine.kt | head -20`

  If `MacroEngine` does not expose `runningMacroId` as a `StateFlow`, add:
  ```kotlin
  // MacroEngine.kt
  private val _runningMacroId = MutableStateFlow<String?>(null)
  val runningMacroId: StateFlow<String?> = _runningMacroId.asStateFlow()
  ```
  And set `_runningMacroId.value = macro.id` when starting, `_runningMacroId.value = null` on finish.

  Expose in ViewModel:
  ```kotlin
  val runningMacroId: StateFlow<String?> = macroEngine.runningMacroId
  ```

- [x] **Step 2: Add strings**

  ```xml
  <string name="automation_running_title">Automation Running</string>
  <string name="automation_run_in_bg">Run in Background</string>
  <string name="automation_live_badge">LIVE</string>
  ```

- [x] **Step 3: Add `AutomationRunCard` in `AutomationsTab.kt`**

  Fetch from ViewModel:
  ```kotlin
  val runningMacroId by viewModel.runningMacroId.collectAsState()
  val macros by viewModel.macros.collectAsState()
  val runningMacro = runningMacroId?.let { id -> macros.find { it.id == id } }
  ```

  At the top of the `LazyColumn` content (before existing items), show the card when `runningMacro != null`:
  ```kotlin
  runningMacro?.let { macro ->
      item(key = "running") {
          AutomationRunCard(
              macro = macro,
              onDismiss = { /* sets local dismissed flag — does NOT stop the automation */ }
          )
      }
  }
  ```

  `AutomationRunCard(macro: Macro, onDismiss: () -> Unit)`:
  - Same card style as PlanCard (same border gradient)
  - Header: refresh icon + `automation_running_title` + `LIVE` badge in error/red color
  - `macro.title` subtitle
  - `macro.actions` listed like PlanCard steps (no dynamic currentStep since we don't track it at this level — all shown as pending with a spinning `CircularProgressIndicator` on the first one)
  - "Run in Background" `TextButton` on the right which calls `onDismiss`

- [x] **Step 4: Build to confirm no errors**

  Run: `./gradlew assembleDebug 2>&1 | grep "error:" | head -20`
  Expected: 0 errors

- [x] **Step 5: Commit**

  ```bash
  git add app/src/main/java/com/nik/voiceassistant/ui/screens/AutomationsTab.kt \
          app/src/main/java/com/nik/voiceassistant/VoiceAssistantViewModel.kt \
          app/src/main/res/values/strings.xml
  git commit -m "feat: show live AutomationRunCard in AutomationsTab when macro is running

  Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
  ```

---

## Task 8: Full test sweep + code review

**Files:**
- Read-only pass across all modified files

- [x] **Step 1: Run full unit test suite**

  Run: `./gradlew testDebugUnitTest 2>&1 | tail -30`
  Expected: BUILD SUCCESSFUL, 0 FAIL

- [x] **Step 2: Build release variant**

  Run: `./gradlew assembleRelease 2>&1 | grep -E "error:|warning: " | head -30`
  Expected: 0 errors

- [x] **Step 3: Review Focus checks**
  - Trace through `PlanCard` with `steps = emptyList()` mentally — confirm no `coerceIn` crash
  - Trace through `TaskCompletionCard` with `stepDetails = null` — confirm plain step text renders
  - Trace through `ResearchCard` with `sources = emptyList()` — confirm empty state shows
  - Trace through `HistoryTab` with `sessions = emptyList()` — confirm `AnimatedVisibility(visible = false)` hides tiles, `EmptyState` still renders
  - Trace through `TimelineTab` with no tool messages — confirm `EmptyState` renders with `timeline_empty`

- [x] **Step 4: Commit final sweep**

  ```bash
  git add -p   # only if any fixes were needed
  git commit -m "fix: review focus checks — null/empty guard pass

  Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
  ```

---

## Self-Review Notes

**Spec coverage:**
- Feature 1 (PlanCard v2) → Task 2 ✅
- Feature 2 (Reasoning Feed) → Task 2 (in PlanCard) ✅
- Feature 3 (Task Completion Card) → Task 3 ✅
- Feature 4 (History Analytics) → Task 4 ✅
- Feature 5 (Action Timeline) → Task 5 ✅
- Feature 6 (Research Source Chips) → Task 6 ✅
- Feature 7 (Automation Run Progress) → Task 7 ✅
- Feature 7b (Overlay Enhancement) → **NOT covered** — overlay changes are risky (SystemUI overlay layer) and the spec ranked it last. Deferred to a follow-up. The implementer notes this omission and may add it after Task 8 if time allows.

**Type consistency:** All references to `TaskPlan.stepTimings`, `stepDetails`, `reasoningLines` match the definition in Task 1. `countActions`/`countDistinctApps` functions defined in Task 4 Step 3 and used in Task 4 Step 6. `defaultResearchSources` defined and used in Task 6.

**Proportion:** Plan is ~200 lines longer than the spec — acceptable given exact code signatures needed for Kotlin/Compose. No full function bodies except for the two pure utility functions where the body *is* the decision.
