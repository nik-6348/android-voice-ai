# Shiv Verify & Plan Engine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the fake spinner/plan with a real two-tier screen-verify loop and a proper step state machine so Shiv actually confirms each action worked before moving on.

**Architecture:** Tier-1 local element-match (~100ms) answers "did it work?" for most actions; Tier-2 sends a JPEG to the gateway `/v1/screen/verify` route only when Tier-1 is inconclusive. `PlanEngine` owns step state (`PENDING→RUNNING→VERIFYING→DONE/FAILED`) and emits it as a `StateFlow`; `complete_step` is the only way to mark a step done.

**Tech Stack:** Kotlin/Android (minSdk 26, targetSdk 34), Jetpack Compose/Material3, OkHttp, Gson, Node/Express (gateway), JUnit4 (Android unit tests).

**Spec:** `/private/tmp/claude-501/-Users-sama-Desktop-projects-ai-service-android-voice/80e80a06-579c-4b26-b7aa-c977b272a6ac/scratchpad/shiv-real-agent-plan.md` §§ A0–A5, B1–B4.

## Global Constraints

- minSdk 26; screenshot APIs require SDK ≥ 30 (`Build.VERSION_CODES.R`); `takeScreenshotOfWindow` requires SDK ≥ 34. Text-only path for SDK < 30.
- Gateway model pinned to `gemini-pro` on `/v1/screen/verify`; `FALLBACK_MODEL` disabled on that route.
- Rate-limit screenshots to 1/sec (AccessibilityService limit).
- Resize JPEG longest side to 1024px, quality 70, before sending.
- FLAG_SECURE black images → fall back to text-only verify.
- Banking/UPI app denylist (off by default), toggled by "Banking" sub-toggle under Screen understanding setting.
- `complete_step` is the **only** trigger for DONE on UI steps (single-step auto-plans complete on verify=yes).
- Background steps (DND, calendar, SMS, alarm) use API read-back, never a screenshot verify.
- `steps: List<String>` Gson format must still deserialize (backward compat).
- All UI-facing strings in English (not Hinglish); spoken replies follow user's language.
- Commit attribution: `Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>`

## Review Focus

1. **Rate-limit race**: two tool calls firing within 1s could both capture screenshots simultaneously and hit the 1/sec AccessibilityService cap — `ScreenVerifier` must serialize captures with a monotonic timestamp gate.
2. **FLAG_SECURE false negative**: some apps render a partial black JPEG (not all-black) — the black-image detector must threshold at ≥90% black pixels, not 100%.
3. **Gson backward compat**: old `steps: List<String>` plans stored in prefs will deserialize into the new `List<PlanStep>` — the fallback must handle both JSON shapes without crashing.
4. **`complete_step` on a non-running step**: model might call `complete_step` on a step that's already DONE or FAILED — `PlanEngine` must return an error string and leave state unchanged.
5. **Gateway timeout on Tier-2**: if the gateway takes >7s the `ScreenVerifier` coroutine hangs the tool thread — hard-cap the OkHttp call at 7s and return `verified=unsure` on timeout.

---

## M1 — Two-Tier Screen Verify

### Task 1: Gateway `/v1/screen/verify` route

**Files:**
- Modify: `ai-service-rpa/server.js` (add route ~line 210)

**Interfaces:**
- Consumes: existing `withAttachments(body, prompt)`, `resolveModel(model)`, `authMiddleware`
- Produces: `POST /v1/screen/verify` → strict JSON `{screen_summary, app, verified, evidence, blocker, step_done, next_action, element_refs}`

- [ ] **Step 1: Write a curl fixture test**

```bash
# Save as ai-service-rpa/test-verify.sh
# Checks that a non-image request returns the expected JSON shape
curl -s -X POST http://localhost:3000/v1/screen/verify \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer test" \
  -d '{"elements":[],"app":"com.test","goal":"open settings","step":{"index":0,"title":"Tap Settings","expect":"Settings screen visible"},"last_action":{"tool":"tap","args":{"text":"Settings"}},"question":"Did Settings open?"}' \
  | jq '{verified,app,blocker,step_done}'
```

Expected shape: `{"verified": "yes"|"no"|"unsure", "app": "...", "blocker": null|"...", "step_done": true|false}`

- [ ] **Step 2: Add `/v1/screen/verify` route to `server.js`**

Insert after the `/health` route (~line 209). The route:
1. Calls `withAttachments(body, systemPrompt)` where `systemPrompt` instructs Gemini to return strict JSON with fields: `screen_summary`, `app`, `verified` (yes|no|unsure), `evidence`, `blocker` (permission_dialog|login|popup|error_toast|loading|null), `step_done` (bool), `next_action` (`{tool,args,why}`|null), `element_refs` (array of strings).
2. Pins model: uses `"gemini-pro"` via `resolveModel`; ignores `FALLBACK_MODEL`.
3. Parses JSON from Gemini response; on parse failure retries once; on second failure returns `{verified:"unsure", evidence:"parse_error"}`.
4. Sets `Content-Type: application/json`; timeout 6s on the Gemini call (use `AbortSignal.timeout(6000)`).
5. On image not received: returns `{verified:"unsure", image:"not_seen"}`.

Signature inside the route handler:
```js
app.post("/v1/screen/verify", authMiddleware, async (req, res) => { ... })
```

- [ ] **Step 3: Run the fixture test**

```bash
cd ai-service-rpa && node server.js &
sleep 2
bash test-verify.sh
kill %1
```
Expected: JSON with `verified` key present, no 500 error.

- [ ] **Step 4: Commit**

```bash
git add ai-service-rpa/server.js ai-service-rpa/test-verify.sh
git commit -m "feat(gateway): add POST /v1/screen/verify route with two-retry JSON parse

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

### Task 2: `VerifyResult` model + `ElementVerifier` (Tier-1)

**Files:**
- Create: `app/src/main/java/com/nik/voiceassistant/tools/VerifyResult.kt`
- Create: `app/src/main/java/com/nik/voiceassistant/tools/ElementVerifier.kt`
- Create: `app/src/test/java/com/nik/voiceassistant/tools/ElementVerifierTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  data class VerifyResult(
      val verified: String,       // "yes" | "no" | "unsure"
      val via: String,            // "elements" | "vision" | "timeout" | "text_only"
      val evidence: String,
      val blocker: String?,       // "permission_dialog" | "login" | "popup" | "error_toast" | "loading" | null
      val stepDone: Boolean,
      val nextAction: Map<String, Any?>?,
      val elementRefs: List<String>
  )
  
  object ElementVerifier {
      // Returns VerifyResult or null if inconclusive
      fun check(expect: String, elements: List<String>, app: String): VerifyResult?
  }
  ```

- [ ] **Step 1: Write failing tests**

```kotlin
// ElementVerifierTest.kt
class ElementVerifierTest {
    @Test fun `exact element text match returns yes`() {
        val result = ElementVerifier.check(
            expect = "Settings screen visible",
            elements = listOf("[1] TextView \"Settings\"", "[2] Button \"Back\""),
            app = "com.android.settings"
        )
        assertNotNull(result)
        assertEquals("yes", result!!.verified)
        assertEquals("elements", result.via)
    }

    @Test fun `no matching elements returns null`() {
        val result = ElementVerifier.check(
            expect = "WhatsApp chat open",
            elements = listOf("[1] Button \"Home\""),
            app = "com.android.launcher"
        )
        assertNull(result)
    }

    @Test fun `empty elements returns null`() {
        val result = ElementVerifier.check("anything", emptyList(), "com.test")
        assertNull(result)
    }

    @Test fun `error toast detection returns no`() {
        val result = ElementVerifier.check(
            expect = "Message sent",
            elements = listOf("[1] TextView \"Failed to send\""),
            app = "com.whatsapp"
        )
        assertNotNull(result)
        assertEquals("no", result!!.verified)
        assertEquals("error_toast", result.blocker)
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
cd app && ../gradlew test --tests "*.ElementVerifierTest" 2>&1 | tail -10
```
Expected: compilation error (class not found).

- [ ] **Step 3: Implement `VerifyResult.kt`**

Data class as shown in Interfaces above. No logic.

- [ ] **Step 4: Implement `ElementVerifier.kt`**

Logic: 
1. If `elements` is empty → return null.
2. Lowercase the `expect` string; extract key nouns (split on spaces, filter words >3 chars).
3. Score: count how many key nouns appear in the joined elements string (lowercase).
4. If score ≥ 2 or the join contains an exact phrase from expect (3+ consecutive words) → `verified="yes"`.
5. Check for error markers in elements ("failed", "error", "couldn't", "not sent") → `verified="no"`, `blocker="error_toast"`.
6. If score == 0 → return null (inconclusive).
7. If score == 1 → return null (inconclusive, needs vision).

- [ ] **Step 5: Run tests to confirm they pass**

```bash
cd app && ../gradlew test --tests "*.ElementVerifierTest" 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`, all 4 tests pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nik/voiceassistant/tools/VerifyResult.kt \
        app/src/main/java/com/nik/voiceassistant/tools/ElementVerifier.kt \
        app/src/test/java/com/nik/voiceassistant/tools/ElementVerifierTest.kt
git commit -m "feat(verify): add VerifyResult model and ElementVerifier tier-1 matcher

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

### Task 3: `ScreenVerifier.kt` (capture, settle, resize, gateway call)

**Files:**
- Create: `app/src/main/java/com/nik/voiceassistant/tools/ScreenVerifier.kt`
- Create: `app/src/test/java/com/nik/voiceassistant/tools/ScreenVerifierTest.kt`

**Interfaces:**
- Consumes: `ElementVerifier.check(...)`, `VerifyResult`, `PhoneAgentService.screenshotJpeg()`, `PhoneAgentService.appWindowJpeg()`
- Produces:
  ```kotlin
  class ScreenVerifier(
      private val context: Context,
      private val gatewayBase: () -> String,
      private val apiKey: () -> String,
      private val agent: () -> PhoneAgentService?,
      private val deniedApps: Set<String> = emptySet()   // banking denylist
  ) {
      // Main entry: run tier-1, fall back to tier-2 if needed
      // expect: what the model expects to be true after its action
      // elements: current accessibility elements
      // app: current foreground app package
      // goal/step for gateway context
      // Returns VerifyResult
      suspend fun verify(
          expect: String,
          elements: List<String>,
          app: String,
          goal: String,
          stepIndex: Int,
          stepTitle: String,
          lastTool: String,
          lastArgs: Map<String, Any?>,
          forceVision: Boolean = false
      ): VerifyResult
      
      // Returns a JPEG of the app window (not overlay), resized to 1024px, q70
      // Rate-limited to 1/sec; returns null on FLAG_SECURE or SDK < 30
      suspend fun captureJpeg(): ByteArray?
  }
  ```

- [ ] **Step 1: Write failing tests (pure logic, no Android)**

```kotlin
// ScreenVerifierTest.kt — tests the black-image detection and resize helpers
class ScreenVerifierTest {
    @Test fun `isBlackImage returns true for all-zero bytes`() {
        val black = ByteArray(1000) { 0 }
        assertTrue(ScreenVerifier.isBlackImage(black))
    }

    @Test fun `isBlackImage returns false when less than 90pct black`() {
        val mixed = ByteArray(1000) { if (it < 200) 0 else 1 }
        assertFalse(ScreenVerifier.isBlackImage(mixed))
    }

    @Test fun `isBlackImage returns true when 90pct black`() {
        val mostly = ByteArray(1000) { if (it < 900) 0 else 1 }
        assertTrue(ScreenVerifier.isBlackImage(mostly))
    }
}
```

- [ ] **Step 2: Run to confirm fail**

```bash
cd app && ../gradlew test --tests "*.ScreenVerifierTest" 2>&1 | tail -5
```

- [ ] **Step 3: Implement `ScreenVerifier.kt`**

Key details:
- `isBlackImage(jpeg: ByteArray): Boolean` companion fun — decode JPEG, count pixels where R+G+B < 15; return true if ≥ 90%.
- `captureJpeg()`: check `System.currentTimeMillis() - lastCaptureAt < 1000` → wait remainder. Try `agent().appWindowJpeg()`, fallback `agent().screenshotJpeg()`. If null or `isBlackImage` → return null. Resize longest side to 1024px (use `Bitmap.createScaledBitmap`), compress JPEG q70 into `ByteArrayOutputStream`. Record `lastCaptureAt`.
- `verify()` flow:
  1. If not `forceVision` and `elements.isNotEmpty()`: run `ElementVerifier.check(expect, elements, app)`. If non-null → return it.
  2. If SDK < 30 or app in `deniedApps` → return `VerifyResult(verified="unsure", via="text_only", ...)`.
  3. Capture JPEG (`captureJpeg()`). If null → return `VerifyResult(verified="unsure", via="timeout", ...)`.
  4. Call gateway `POST /v1/screen/verify` via OkHttp (timeout 7s). Body: JSON with `image_base64`, `image_mime="image/jpeg"`, `elements`, `app`, `goal`, `step{index,title,expect}`, `last_action{tool,args}`, `question`.
  5. Parse response JSON into `VerifyResult(via="vision", ...)`. On failure → `verified="unsure"`.

- [ ] **Step 4: Run tests**

```bash
cd app && ../gradlew test --tests "*.ScreenVerifierTest" 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`, 3 tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nik/voiceassistant/tools/ScreenVerifier.kt \
        app/src/test/java/com/nik/voiceassistant/tools/ScreenVerifierTest.kt
git commit -m "feat(verify): add ScreenVerifier with tier-1/tier-2 fallback and 1/sec rate-limit

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

### Task 4: Wire `ScreenVerifier` into `DeviceToolExecutor`

**Files:**
- Modify: `app/src/main/java/com/nik/voiceassistant/tools/DeviceToolExecutor.kt`
- Modify: `app/src/main/java/com/nik/voiceassistant/VoiceSession.kt`

**Interfaces:**
- Consumes: `ScreenVerifier.verify(...)`, `VerifyResult`
- Produces:
  - `expect` optional param added to: `tap`, `long_press`, `type_text`, `scroll`, `swipe`, `navigate`, `open_app`, `launch_intent`, `open_website`
  - Tool results gain a `"verify"` key when `expect` is set: `{verified, via, evidence, blocker}`
  - `look_at_screen` uses `ScreenVerifier.verify(forceVision=true)` instead of `describeImage`
  - `DeviceToolExecutor` constructor gains `screenVerifier: ScreenVerifier?` replacing `describeImage`

- [ ] **Step 1: Add `expect` param to acting-tool declarations**

In `DeviceToolExecutor.declarations`, add to each of `tap`, `type_text`, `scroll`, `swipe`, `navigate`, `open_app`, `launch_intent`, `open_website`:
```kotlin
"expect" to param("string", "What you expect to be true on screen after this action, e.g. \"Settings screen is open\"")
```
(Add to `optional` list.)

- [ ] **Step 2: Add `screenVerifier` constructor param**

Replace:
```kotlin
private val describeImage: ((ByteArray, String) -> String?)? = null,
```
With:
```kotlin
private val screenVerifier: ScreenVerifier? = null,
```
Update all internal usages (`augmentWithScreenshot`, `lookAtScreen`, `researchViaBrowser`).

- [ ] **Step 3: Update `augmentWithScreenshot` to use `ScreenVerifier`**

Replace the `describeImage.invoke(jpeg, prompt)` call with:
```kotlin
runBlocking { screenVerifier?.captureJpeg() }
```
Then use the JPEG with a simple describe call (keep existing text-only fallback for null).
Actually: `augmentWithScreenshot` is only for when elements are empty — keep using `screenVerifier?.captureJpeg()` + the existing gateway `chat()` for description, since `verify()` needs `expect`.

- [ ] **Step 4: Add verify block to acting-tool results**

In `execute()` after `val result = run(...)`, check if `arguments["expect"]` is set. If so, run:
```kotlin
val expect = arguments["expect"] as? String ?: ""
if (expect.isNotBlank() && screenVerifier != null) {
    // Re-read screen elements after action (withProgressCheck already has them)
    val vr = runBlocking { screenVerifier.verify(expect, currentElements(), app, ...) }
    return (withProgressCheck(toolName, arguments, result) + mapOf("verify" to mapOf(
        "verified" to vr.verified,
        "via" to vr.via,
        "evidence" to vr.evidence,
        "blocker" to vr.blocker
    )))
}
```

- [ ] **Step 5: Rewrite `lookAtScreen` to use `ScreenVerifier`**

```kotlin
private fun lookAtScreen(agent: PhoneAgentService, question: String?): Map<String, Any?> {
    val screen = agent.readScreen()
    @Suppress("UNCHECKED_CAST")
    val elements = (screen["elements"] as? List<String>).orEmpty()
    val vr = runBlocking {
        screenVerifier?.verify(
            expect = question ?: "Describe what is on screen",
            elements = elements,
            app = screen["app"] as? String ?: "",
            goal = plan?.goal ?: "",
            stepIndex = 0,
            stepTitle = question ?: "look_at_screen",
            lastTool = "look_at_screen",
            lastArgs = emptyMap(),
            forceVision = true
        )
    } ?: return mapOf("error" to "Screen description not available", "screen" to screen)
    return mapOf("description" to vr.evidence, "screen" to screen,
        "verified" to vr.verified, "via" to vr.via)
}
```

- [ ] **Step 6: Update `VoiceSession` to construct `ScreenVerifier` and pass it**

In `VoiceSession.kt`, after `PhoneAgentService` is available, construct:
```kotlin
private val screenVerifier = ScreenVerifier(
    context = context,
    gatewayBase = { gatewayBase() },
    apiKey = { _apiKey.value },
    agent = { PhoneAgentService.instance }
)
```
Pass it to `DeviceToolExecutor` constructor as `screenVerifier = screenVerifier`.
Remove the `describeImage = { jpeg, prompt -> describeImage(jpeg, prompt) }` lambda.

- [ ] **Step 7: Build check**

```bash
cd /Users/sama/Desktop/projects/ai-service/android-voice && ./gradlew assembleDebug 2>&1 | tail -20
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/nik/voiceassistant/tools/DeviceToolExecutor.kt \
        app/src/main/java/com/nik/voiceassistant/VoiceSession.kt
git commit -m "feat(verify): wire ScreenVerifier into DeviceToolExecutor; add expect param to acting tools

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

### Task 5: "Screen understanding" setting + system prompt rules

**Files:**
- Modify: `app/src/main/java/com/nik/voiceassistant/VoiceSession.kt` (prompt text, setting read)
- Modify: `app/src/main/res/xml/preferences.xml` (or equivalent settings file)

**Interfaces:**
- Produces: pref key `"screen_understanding"` with values `"text_only"` | `"smart"` (default) | `"every_action"`; banking sub-pref `"screen_understanding_banking"` (boolean, default false).

- [ ] **Step 1: Add setting to preferences XML**

Find the existing preferences XML file (likely `res/xml/preferences.xml` or similar). Add:
```xml
<ListPreference
    android:key="screen_understanding"
    android:title="Screen understanding"
    android:summary="How Shiv verifies its actions"
    android:entries="@array/screen_understanding_entries"
    android:entryValues="@array/screen_understanding_values"
    android:defaultValue="smart" />
<SwitchPreferenceCompat
    android:key="screen_understanding_banking"
    android:title="Include banking apps"
    android:summary="Send banking app screenshots to AI (off = text only for those apps)"
    android:defaultValue="false"
    android:dependency="screen_understanding" />
```
Add corresponding string arrays to `strings.xml`:
```xml
<string-array name="screen_understanding_entries">
    <item>Text only</item>
    <item>Smart (recommended)</item>
    <item>Every action</item>
</string-array>
<string-array name="screen_understanding_values">
    <item>text_only</item>
    <item>smart</item>
    <item>every_action</item>
</string-array>
```

- [ ] **Step 2: Read setting in `VoiceSession` and pass to `ScreenVerifier`**

Add `_screenUnderstanding: StateFlow<String>` backed by pref key `"screen_understanding"` defaulting to `"smart"`. Pass value to `ScreenVerifier` so it can skip Tier-2 when `"text_only"` or force Tier-2 when `"every_action"`.

- [ ] **Step 3: Add system prompt rules for `expect`**

In `VoiceSession.kt` system prompt block, append rules (search for the line ending with `...Reply in the user's language...`):
```
Screen verify rules:
- Every acting tool call (tap, type_text, scroll, swipe, navigate, open_app, launch_intent, open_website) MUST include an "expect" field describing what you expect to be true on screen after it.
- When a tool result contains "verify": {"verified": "no"}, do NOT proceed to the next step. Retry the action or replan (max 2 retries per step).
- When "verified" is "yes", proceed to complete_step.
- Use the "next_action" hint from the verify result when retrying.
- "verified": "unsure" means the check was inconclusive — try look_at_screen or proceed with caution.
```

- [ ] **Step 4: Build check**

```bash
./gradlew assembleDebug 2>&1 | tail -10
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nik/voiceassistant/VoiceSession.kt \
        app/src/main/res/xml/ app/src/main/res/values/strings.xml
git commit -m "feat(verify): add Screen understanding setting and system prompt verify rules

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## M2 — Plan Engine

### Task 6: New `PlanStep` / `TaskPlan` / `PlanEvent` models + Gson backward compat

**Files:**
- Modify: `app/src/main/java/com/nik/voiceassistant/tools/ToolModels.kt`
- Create: `app/src/test/java/com/nik/voiceassistant/tools/TaskPlanModelTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  enum class StepStatus { PENDING, RUNNING, VERIFYING, DONE, FAILED, SKIPPED }
  
  data class PlanStep(
      val id: String,            // e.g. "s1", "s2"
      val title: String,
      val detail: String = "",
      val expect: String = "",
      val status: StepStatus = StepStatus.PENDING,
      val startedAt: Long? = null,
      val endedAt: Long? = null,
      val attempts: Int = 0,
      val evidence: String? = null,
      val thumbId: String? = null,    // filename under filesDir/evidence/<taskId>/<step>-<n>.jpg
      val background: Boolean = false
  )
  
  data class PlanEvent(
      val at: Long,
      val kind: String,   // "TOOL" | "VERIFY" | "REPLAN" | "BLOCKER" | "APPROVAL" | "NOTE"
      val text: String
  )
  
  data class TaskPlan(
      val goal: String,
      val steps: List<PlanStep>,
      val status: String,          // "working" | "blocked" | "done" | "failed"
      val revision: Int = 0,
      val note: String? = null,
      val events: List<PlanEvent> = emptyList(),
      val startedAt: Long = System.currentTimeMillis(),
      // compat fields kept nullable so old code reading them doesn't crash
      val tried: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf()),
      val blockedCount: Int = 0
  )
  ```
  
  Gson backward compat: a `TaskPlanDeserializer : JsonDeserializer<TaskPlan>` that handles both `steps: ["step text", ...]` (old) and `steps: [{id, title, ...}]` (new).

- [ ] **Step 1: Write failing model tests**

```kotlin
class TaskPlanModelTest {
    private val gson = GsonBuilder()
        .registerTypeAdapter(TaskPlan::class.java, TaskPlanDeserializer())
        .create()

    @Test fun `new PlanStep format deserializes correctly`() {
        val json = """{"goal":"g","steps":[{"id":"s1","title":"Open Settings","status":"PENDING"}],"status":"working"}"""
        val plan = gson.fromJson(json, TaskPlan::class.java)
        assertEquals("s1", plan.steps[0].id)
        assertEquals(StepStatus.PENDING, plan.steps[0].status)
    }

    @Test fun `old string steps format deserializes with auto ids`() {
        val json = """{"goal":"g","steps":["Open Settings","Tap Wi-Fi"],"status":"working","currentStep":1}"""
        val plan = gson.fromJson(json, TaskPlan::class.java)
        assertEquals(2, plan.steps.size)
        assertEquals("s1", plan.steps[0].id)
        assertEquals("Open Settings", plan.steps[0].title)
        assertEquals(StepStatus.RUNNING, plan.steps[0].status)  // currentStep=1 → first is RUNNING
    }

    @Test fun `complete_step does not exist in old TaskPlan`() {
        // The old TaskPlan had steps:List<String> — verify new one is additive
        val plan = TaskPlan(goal = "g", steps = listOf(PlanStep("s1", "Step 1")), status = "working")
        assertEquals(1, plan.steps.size)
        assertNull(plan.steps[0].evidence)
    }
}
```

- [ ] **Step 2: Run to confirm fail**

```bash
cd app && ../gradlew test --tests "*.TaskPlanModelTest" 2>&1 | tail -5
```

- [ ] **Step 3: Replace `ToolModels.kt` with new model definitions**

Keep `ToolCallRequest` and `ToolCallResponse` unchanged. Replace the `TaskPlan` data class with `StepStatus`, `PlanStep`, `PlanEvent`, new `TaskPlan`, and `TaskPlanDeserializer`.

- [ ] **Step 4: Run tests**

```bash
cd app && ../gradlew test --tests "*.TaskPlanModelTest" 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`, 3 tests pass.

- [ ] **Step 5: Fix any compilation breaks from old `TaskPlan` references**

Search: `grep -rn "\.steps\b\|currentStep\|stepTimings\|stepDetails\|reasoningLines" --include=*.kt app/src/main/` then fix each usage.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nik/voiceassistant/tools/ToolModels.kt \
        app/src/test/java/com/nik/voiceassistant/tools/TaskPlanModelTest.kt
git commit -m "feat(plan): new PlanStep/TaskPlan/PlanEvent models with Gson backward compat

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

### Task 7: `PlanEngine.kt` state machine

**Files:**
- Create: `app/src/main/java/com/nik/voiceassistant/tools/PlanEngine.kt`
- Create: `app/src/test/java/com/nik/voiceassistant/tools/PlanEngineTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  class PlanEngine {
      val plan: StateFlow<TaskPlan?>
      
      fun start(goal: String, steps: List<PlanStep>): TaskPlan
      // Replan: done/failed steps are immutable; replaces PENDING/RUNNING steps
      fun replan(newSteps: List<PlanStep>, note: String? = null): TaskPlan
      fun onToolStart(stepId: String, toolName: String)
      fun onToolResult(stepId: String, toolName: String, result: Map<String, Any?>)
      // Gate: checks verify evidence before marking done
      // Returns error string if step can't be completed, null on success
      fun requestComplete(stepId: String, summary: String, verifyResult: VerifyResult?): String?
      fun fail(stepId: String, reason: String)
      fun finish(status: String)   // "done" | "failed"
      fun reset()
      
      companion object {
          const val MAX_EVENTS = 12
      }
  }
  ```

- [ ] **Step 1: Write failing tests**

```kotlin
class PlanEngineTest {
    private val engine = PlanEngine()

    @Test fun `start creates plan with first step RUNNING`() {
        engine.start("Open Settings", listOf(PlanStep("s1", "Tap Settings")))
        val plan = engine.plan.value!!
        assertEquals(StepStatus.RUNNING, plan.steps[0].status)
    }

    @Test fun `requestComplete on running step with yes verify marks DONE`() {
        engine.start("g", listOf(PlanStep("s1", "Step 1"), PlanStep("s2", "Step 2")))
        val vr = VerifyResult("yes", "elements", "Settings open", null, true, null, emptyList())
        val err = engine.requestComplete("s1", "Done", vr)
        assertNull(err)
        val plan = engine.plan.value!!
        assertEquals(StepStatus.DONE, plan.steps[0].status)
        assertEquals(StepStatus.RUNNING, plan.steps[1].status)
    }

    @Test fun `requestComplete on already done step returns error`() {
        engine.start("g", listOf(PlanStep("s1", "Step 1")))
        val vr = VerifyResult("yes", "elements", "ok", null, true, null, emptyList())
        engine.requestComplete("s1", "done", vr)
        val err = engine.requestComplete("s1", "done again", vr)
        assertNotNull(err)
        assertTrue(err!!.contains("already"))
    }

    @Test fun `replan preserves done steps`() {
        engine.start("g", listOf(PlanStep("s1", "Step 1"), PlanStep("s2", "Step 2")))
        val vr = VerifyResult("yes", "elements", "ok", null, true, null, emptyList())
        engine.requestComplete("s1", "done", vr)
        engine.replan(listOf(PlanStep("s3", "New step")))
        val plan = engine.plan.value!!
        assertEquals(StepStatus.DONE, plan.steps.first { it.id == "s1" }.status)
        assertTrue(plan.steps.any { it.id == "s3" })
    }

    @Test fun `events ring buffer keeps max 12`() {
        engine.start("g", listOf(PlanStep("s1", "Step 1")))
        repeat(20) { engine.onToolStart("s1", "tap") }
        assertTrue(engine.plan.value!!.events.size <= PlanEngine.MAX_EVENTS)
    }
}
```

- [ ] **Step 2: Run to confirm fail**

```bash
cd app && ../gradlew test --tests "*.PlanEngineTest" 2>&1 | tail -5
```

- [ ] **Step 3: Implement `PlanEngine.kt`**

Use `MutableStateFlow<TaskPlan?>` internally. Operations mutate a copy and emit. `requestComplete` checks: if step is not RUNNING → return error; if `verifyResult?.verified == "no"` → return error "verify failed"; otherwise mark DONE, advance next PENDING step to RUNNING. Events ring buffer: `ArrayDeque<PlanEvent>(MAX_EVENTS)`.

- [ ] **Step 4: Run tests**

```bash
cd app && ../gradlew test --tests "*.PlanEngineTest" 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`, 5 tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nik/voiceassistant/tools/PlanEngine.kt \
        app/src/test/java/com/nik/voiceassistant/tools/PlanEngineTest.kt
git commit -m "feat(plan): PlanEngine state machine with verify-gated complete_step

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

### Task 8: `update_plan` / `complete_step` tools + remove fake plan

**Files:**
- Modify: `app/src/main/java/com/nik/voiceassistant/tools/DeviceToolExecutor.kt`

**Interfaces:**
- Consumes: `PlanEngine` (replaces the `plan: TaskPlan?` field and `updatePlan()` method)
- Produces:
  - `update_plan` tool schema updated: `steps` is `array of {title, detail, expect}` objects
  - New `complete_step(step_id: String, summary: String)` tool
  - `ensurePlanForAction` removed; replaced by a real 1-step plan via `PlanEngine.start()`
  - `DeviceToolExecutor` constructor gains `planEngine: PlanEngine`
  - `onPlan` callback removed; callers read `planEngine.plan` StateFlow directly

- [ ] **Step 1: Update `update_plan` declaration**

Replace current `update_plan` optional `steps` definition with:
```kotlin
"steps" to mapOf(
    "type" to "array",
    "items" to mapOf(
        "type" to "object",
        "properties" to mapOf(
            "title" to mapOf("type" to "string", "description" to "Short step label, in English"),
            "detail" to mapOf("type" to "string", "description" to "What to do in this step"),
            "expect" to mapOf("type" to "string", "description" to "What you expect to be true after completing it")
        ),
        "required" to listOf("title")
    ),
    "description" to "Ordered steps to reach the goal, in English"
)
```

- [ ] **Step 2: Add `complete_step` declaration**

```kotlin
function(
    "complete_step",
    "Marks the current step as done. Only call this after confirming the step succeeded " +
        "(verify block showed verified=yes, or for background steps, the API confirmed it). " +
        "The next step starts automatically.",
    required = listOf(
        "step_id" to param("string", "The id of the step to mark done, e.g. \"s1\""),
        "summary" to param("string", "One line: what you did and the outcome")
    )
)
```

- [ ] **Step 3: Replace `plan` field with `PlanEngine`**

Remove `@Volatile private var plan: TaskPlan?`. Add `private val planEngine: PlanEngine` constructor param. Remove `onPlan: (TaskPlan?) -> Unit` constructor param. Replace all `plan` accesses with `planEngine.plan.value`.

- [ ] **Step 4: Replace `updatePlan()` with `planEngine.start()` / `planEngine.replan()`**

In the `run()` dispatch for `"update_plan"`:
```kotlin
val goal = str("goal")
val rawSteps = args["steps"]
val steps: List<PlanStep> = when {
    rawSteps is List<*> && rawSteps.firstOrNull() is Map<*, *> ->
        (rawSteps as List<Map<*, *>>).mapIndexed { i, m ->
            PlanStep("s${i+1}", m["title"] as? String ?: "", m["detail"] as? String ?: "", m["expect"] as? String ?: "")
        }
    rawSteps is List<*> ->
        (rawSteps as List<*>).mapIndexed { i, s -> PlanStep("s${i+1}", s.toString()) }
    else -> emptyList()
}
val existing = planEngine.plan.value
return if (existing == null || existing.status in setOf("done", "failed") || goal != existing.goal) {
    planEngine.start(goal, steps.ifEmpty { listOf(PlanStep("s1", goal)) })
    mapOf("ok" to true)
} else {
    planEngine.replan(steps, str("note"))
    mapOf("ok" to true)
}
```

- [ ] **Step 5: Add `complete_step` handler**

```kotlin
"complete_step" -> {
    val stepId = str("step_id")
    val summary = str("summary")
    val vr = pendingVerifyResult.also { pendingVerifyResult = null }
    val err = planEngine.requestComplete(stepId, summary, vr)
    if (err != null) mapOf("error" to err)
    else mapOf("ok" to true, "next_step" to planEngine.plan.value?.steps?.firstOrNull { it.status == StepStatus.RUNNING }?.title)
}
```
(Add `@Volatile private var pendingVerifyResult: VerifyResult? = null` field; set it from `execute()` after a verify block.)

- [ ] **Step 6: Replace `ensurePlanForAction` with real 1-step plan**

Remove `ensurePlanForAction`. In `execute()`, before `run()`, if `planEngine.plan.value == null`:
```kotlin
val userGoal = userText().lines().lastOrNull { it.isNotBlank() }?.trim() ?: toolName
planEngine.start(userGoal, listOf(PlanStep("s1", userGoal, expect = "")))
```

- [ ] **Step 7: Wire `planEngine.plan` to `onPlan` callers in `VoiceSession`**

In `VoiceSession.kt`, collect `toolExecutor.planEngine.plan` StateFlow and forward to UI via the existing `onPlan` flow. Remove the `onPlan` lambda from `DeviceToolExecutor` construction.

- [ ] **Step 8: Build check**

```bash
./gradlew assembleDebug 2>&1 | tail -20
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/nik/voiceassistant/tools/DeviceToolExecutor.kt \
        app/src/main/java/com/nik/voiceassistant/VoiceSession.kt
git commit -m "feat(plan): wire PlanEngine; add complete_step tool; remove fake ensurePlanForAction

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

### Task 9: System prompt rules for PlanEngine + minimal UI wiring

**Files:**
- Modify: `app/src/main/java/com/nik/voiceassistant/VoiceSession.kt` (prompt)
- Modify: `app/src/main/java/com/nik/voiceassistant/ui/screens/AssistantLiveTab.kt` (PlanCard reads new model)

**Interfaces:**
- Consumes: `PlanEngine.plan: StateFlow<TaskPlan?>`, `PlanStep.status`, `PlanStep.id`

- [ ] **Step 1: Add PlanEngine rules to system prompt**

In `VoiceSession.kt` system prompt, add:
```
Plan rules:
- Call update_plan at the START of every multi-step task with goal and steps (each step must have title + expect).
- After each acting tool confirms verified=yes, call complete_step(step_id, summary) to advance.
- Never skip complete_step — it is the only way to mark a step done.
- On verify=no: retry the same action (max 2 retries), then replan via update_plan.
- Single-step tasks: still call update_plan + complete_step.
```

- [ ] **Step 2: Update `AssistantLiveTab` PlanCard to read `PlanStep` fields**

In `AssistantLiveTab.kt`, find where `plan.steps` is used (currently `List<String>`). Update to read `PlanStep.title` and `PlanStep.status` for the step label and spinner/checkmark state:
```kotlin
plan.steps.forEachIndexed { i, step ->
    val isActive = step.status == StepStatus.RUNNING || step.status == StepStatus.VERIFYING
    val isDone = step.status == StepStatus.DONE
    // ... existing UI rendering with step.title instead of step (the String)
}
```

- [ ] **Step 3: Build + run lint**

```bash
./gradlew assembleDebug lint 2>&1 | grep -E "error:|warning:|BUILD" | tail -20
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nik/voiceassistant/VoiceSession.kt \
        app/src/main/java/com/nik/voiceassistant/ui/screens/AssistantLiveTab.kt
git commit -m "feat(plan): add PlanEngine system prompt rules; update PlanCard to read PlanStep.status

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```
