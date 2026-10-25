# Shiv UI Upgrade — Design Document
**Date:** 2026-10-25  
**Author:** nik6348  
**Status:** Approved → Implementation

---

## Intent & Why

Demo images (9 slides, `demo/`) show the full product vision for Shiv — an autonomous Android AI agent. The current codebase has ~70% of the structural code in place but the UI lacks the visual richness and information density shown in the demo. This document covers **all 7 feature upgrades** needed to match and exceed the demo quality.

Goal: Same features as demo, better UI — sharper visual hierarchy, richer feedback, more polish.

---

## Goals

1. **PlanCard v2** — Step timestamps, sub-descriptions, "In progress..." animation, better step icons
2. **Reasoning Feed** — Live "Shiv is thinking…" bullet feed during WORKING/THINKING phase
3. **Task Completion Card** — End-of-task summary with full checklist + "View Details" / "New Task" actions
4. **History Analytics Tiles** — Stats row in HistoryTab (tasks completed, apps used, actions, success rate)
5. **Action Timeline** — Detailed per-task timeline view (tap from history card)
6. **Research Source Chips** — Multi-source animated chips shown during web-search tool calls
7. **Automation Run Progress** — Live progress card when a macro is running (links to AutomationsTab)

---

## Non-Goals

- Backend changes — all data feeds already exist in ViewModel
- New tool additions — only UI changes
- Redesigning the entire theme — enhance within existing NikTheme/Material3 dark

---

## Anti-Goals (what would make this a failure)

- Cluttered screens — more info should feel cleaner, not noisier
- Fake/placeholder data — every piece of info shown must come from real ViewModel state
- Broken accessibility — all existing semantics/a11y must be preserved
- Performance regressions — no unnecessary recompositions on audio amplitude changes

---

## Design Tokens (keep these consistent everywhere)

```
CyanAccent   = #00E5FF   (active step, live indicators)
GoldAccent   = #FFD700   (completed, success emphasis)  
SuccessGreen = #8AD88E   (done checkmarks, dark)
WarningAmber = #F5BF48   (pending, warning)
Critical     = #FF5252   (CRITICAL badge)
CardBg       = surfaceContainerHigh
BorderGrad   = cyan 35% → gold 20% horizontal
```

---

## Feature 1: PlanCard v2

**File:** `ui/components/PlanCard.kt`

### Current state
- Steps show: icon + "1. Step text"
- No timing info
- No sub-description per step
- "In progress" not animated

### New design
```
┌─────────────────────────────────────────┐
│ ⚡ GOAL                    [RUNNING]     │
│ Plan client meeting travel…              │
│ ████░░░░░░░░░  Step 2 of 8    25%        │
│                                          │
│ ✅ 1. Understand request            5s   │
│    Client meeting, destination, date…    │
│ ⏵ 2. Check tomorrow's calendar          │  ← cyan pulse dot
│    Opening Calendar app…  In progress…   │
│ ○  3. Research travel options            │
│    Comparing flights, trains…            │
│                             Show less ▲  │
│                                          │
│ ✦ Shiv is reasoning…                     │
│ • Checking calendar for tomorrow         │
│ • Found 2 meetings. Free after 10:30 AM  │
│ • Searching Indore → Mumbai…             │
└─────────────────────────────────────────┘
```

### Changes needed
- `TaskPlan` already has `steps: List<String>` — steps need sub-description field
- Add `stepTimings: Map<Int, Long>` to `TaskPlan` (elapsed ms per completed step)  
- Add `stepDetails: Map<Int, String>` to `TaskPlan` (sub-description per step)
- Add `reasoningLines: List<String>` to `TaskPlan` (live reasoning bullets)
- PlanCard: render timing as `Completed • Xs` next to done steps
- PlanCard: render `stepDetails[i]` as body2 below step title
- PlanCard: animate current step with pulsing cyan dot
- PlanCard: render `reasoningLines` as a "Shiv is reasoning…" section at bottom
- PlanCard: stagger-animate new reasoning lines appearing (slideInVertically)

---

## Feature 2: Research Source Chips

**File:** `ui/components/ResearchCard.kt` (new)

### When to show
When ViewModel emits a tool call containing `web_search`, `search_web`, `browse`, or `research` in its name, the ResearchCard appears above the PlanCard in AssistantLiveTab.

### Design
```
┌─────────────────────────────────────────┐
│ 🌐 Live Research              RUNNING   │
│ Finding best travel options…            │
│                                          │
│  [Google Flights] [MakeMyTrip] [InDigo] │
│  [IRCTC]  [RedBus]  [Skyscanner]        │
│                                          │
│ ✦ Analyzing prices, timings and          │
│   availability for tomorrow…            │
└─────────────────────────────────────────┘
```

### Changes needed
- New `ResearchCard(sources: List<String>, query: String, status: String)` composable
- `sources` is parsed from the tool call args (URL list or source names)
- Chips use `FilterChip` with cyan border, animated shimmer on "active" ones
- Show/hide based on `toolStatus` containing search-related keywords
- Better than demo: chip icons (use first-letter avatar or search icon fallback)

---

## Feature 3: Task Completion Card

**File:** `ui/components/TaskCompletionCard.kt` (new)

### When to show
When `plan.status == "done"` and `plan.steps.isNotEmpty()`.  
Replace the PlanCard at the top with this completion summary.

### Design
```
┌─────────────────────────────────────────┐
│  🎉  Task Completed!                     │
│  Client meeting travel plan              │
│                                          │
│  ✅ Meeting details analyzed             │
│  ✅ Best travel option selected          │
│  ✅ Flight booked (InDigo 6E 722)        │
│  ✅ Itinerary sent on WhatsApp           │
│  ✅ Calendar event created               │
│  ✅ Reminder set for 8:30 AM             │
│                                          │
│ [View Full Details]  [Start New Task]   │
└─────────────────────────────────────────┘
```

### Changes needed
- New composable `TaskCompletionCard(plan: TaskPlan, onViewDetails: () -> Unit, onNewTask: () -> Unit)`
- Shows all completed steps with green checks
- "View Full Details" opens TraceViews dialog (already exists)
- "Start New Task" calls `viewModel.startNewChat()`
- Better than demo: slide-in stagger animation for each completed step (50ms delay each)
- Better than demo: subtle confetti-like sparkle at top (4-5 dots animating outward)

---

## Feature 4: History Analytics Tiles

**File:** `ui/screens/HistoryTab.kt`

### Design
```
┌──────┬──────┬──────┬──────┐
│  12  │  8   │  26  │ 100% │
│Tasks │Apps  │Actions│Success│
│done  │used  │taken │ rate │
└──────┴──────┴──────┴──────┘
```
Shown below the ScreenHeader, above the search bar. Only when `sessions.isNotEmpty()`.

### Changes needed
- Compute stats from `historySessions`:
  - `tasksCompleted = sessions.size`
  - `appsUsed` = count distinct tool names that look like app-opens across all sessions
  - `actionsTaken` = count of `tool` sender messages across all sessions
  - `successRate` = sessions with at least one message / total * 100 (or always 100% for now as placeholder)
- `AnalyticsTileRow` composable: 4 `AnalyticsTile` items in a `Row`
- Each tile: number (large, cyan) + label (small, secondary) in a Card
- Better than demo: tap on a tile filters the session list (e.g. tap "Tasks done" shows all, tap would be a future filter)

---

## Feature 5: Action Timeline (History Detail)

**File:** `ui/screens/HistoryTab.kt` — enhance `TranscriptViewerDialog`

### Design
Add tabs to the TranscriptViewerDialog: **Chat** | **Timeline**

Timeline tab shows:
```
7:15 PM  ● Started task
7:16 PM  ● Opened Calendar — reading upcoming meetings
7:17 PM  ● Searched travel options
7:18 PM  ⚠ Waiting for approval — Flight booking
7:20 PM  ● Booked flight (InDigo 6E 722)
7:21 PM  ● Sent itinerary on WhatsApp
7:22 PM  ● Set reminder
7:23 PM  ✅ Enabled Do Not Disturb
```

### Changes needed
- Extract `tool` sender items from the session messages for timeline
- `TimelineTab` composable with `LazyColumn` of `TimelineRow` items
- Each row: time dot (colored by type) + time label + action description
- Better than demo: tap row to expand and see the full tool call detail

---

## Feature 6: Automation Run Progress

**File:** `ui/screens/AutomationsTab.kt`

### When to show
When a macro is currently running (`MacroEngine.runningMacroId != null`).

### Design
Show a prominent "Running" card at the top of AutomationsTab:
```
┌─────────────────────────────────────────┐
│ 🔄 Automation Running          LIVE     │
│ Email check and WhatsApp summary        │
│                                          │
│ Step 3 of 5                      60%    │
│ ████████████░░░░░░░░                    │
│ ✅ Connecting to Gmail        5s        │
│ ✅ Fetching new emails        12s       │
│ ⏵ Analyzing important emails           │
│   Reading and analyzing 8 new emails…  │
│ ○  Generating summary                   │
│ ○  Sending WhatsApp message             │
└─────────────────────────────────────────┘
```

### Changes needed
- This already has a running state from `MacroEngine` — expose current step via ViewModel
- `AutomationRunCard` composable that mirrors PlanCard but for Macro execution
- Show/hide based on `viewModel.runningMacroId`
- Better than demo: "Run in Background" button that dismisses the card without stopping

---

## Feature 7: Overlay Enhancement (AssistantOverlay.kt)

**File:** `ui/AssistantOverlay.kt`

### Design
The overlay notification (shown on homescreen) should show:
- When idle: Trishul logo + "How can I help you?" + mic waveform
- When running a task: compact PlanCard (goal + current step + progress %)
- When task completes: "✅ Task done: [title]" with auto-dismiss

### Changes needed
- Expose `plan` state to the overlay
- Show `MiniPlanCard` (compact version of PlanCard) in the overlay when status == "working"
- Show completion banner when status == "done"

---

## Implementation Order

Priority order (most impact first):
1. **Feature 1** — PlanCard v2 (every session benefits)
2. **Feature 3** — Task Completion Card (big visual moment)
3. **Feature 4** — History Analytics (easy win, adds polish)
4. **Feature 5** — Action Timeline (transparency feature, demo highlight)
5. **Feature 2** — Research Source Chips (contextual, research tasks)
6. **Feature 6** — Automation Run Progress (power users)
7. **Feature 7** — Overlay Enhancement (last, needs care with SystemUI)

---

## Files to Create / Modify

### New files
- `ui/components/ResearchCard.kt`
- `ui/components/TaskCompletionCard.kt`

### Modified files
- `ui/components/PlanCard.kt` — v2 enhancements
- `ui/screens/AssistantLiveTab.kt` — wire ResearchCard + TaskCompletionCard
- `ui/screens/HistoryTab.kt` — analytics tiles + timeline tab
- `ui/screens/AutomationsTab.kt` — running macro card
- `tools/ToolModels.kt` — add stepTimings, stepDetails, reasoningLines to TaskPlan
- `ui/AssistantOverlay.kt` — mini plan in overlay

### String resources
- All new strings go in `res/values/strings.xml`

---

## Builder Calls (decisions made by implementer)

- `reasoningLines` ring buffer size: **8 items** (older items drop off the top)
- Completion card sparkle: use `Canvas` with 5 dots radiating at 72° intervals — no external animation library
- Analytics `appsUsed` counting: any `tool` message where text contains "launch_app" or "open_app" counts
- Timeline timestamp: use `updatedAt` from session + relative offset via message index (real timestamps not stored — show relative "T+0:01" format)
- Chip icon fallback: first letter of source name in a 24dp circle, cyan on dark surface

---

*This document approved. Next: superpowers:writing-plans → implementation.*
