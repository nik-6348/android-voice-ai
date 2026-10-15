# Call Agent — implementation plan

**Audience:** a Claude Code agent picking up this work with no prior conversation history. Read this whole document before touching any file. It is written to be self-contained: every API you need already exists and is quoted or referenced here with its exact current signature.

**Repo:** `/Users/sama/Desktop/projects/ai-service/android-voice`
**Branch:** `production-hardening` — check out with `git branch --show-current` to confirm, work on it, **never commit to `main`**.
**Build/test commands** (project builds offline; a device may or may not be connected — check `~/Library/Android/sdk/platform-tools/adb devices -l` before any install step):
```
./gradlew compileDebugKotlin --offline -q 2>&1 | grep -E '^e:'
./gradlew testDebugUnitTest -q
./gradlew lintDebug --offline -q
./gradlew assembleRelease -q --offline        # release, not debug — see note below
```
**Install note:** the app on the test device (a Motorola Edge 50 Fusion, if still connected) is signed with the release keystore (`keystore.properties` at repo root, gitignored). A `debug`-signed APK will fail to install over it (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`). Always build and install **release**:
```
~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/release/app-release.apk
```
Installing over the existing app keeps its data (settings, saved macros, keys, PIN). Only `adb uninstall` loses it — never do that without telling the user first, it's a destructive step.

---

## 1. What this feature is

The user asked, in their own words: *"agent khud call lagaye, receive kare, aur use handle kare"* — the agent should be able to both **place** a call and **answer an incoming** one, and actually **hold a conversation** on it, not just dial and hang up.

Decisions the user already made (don't re-ask):
- **Incoming calls:** the agent should both *screen* (recognize/reject spam, per existing `CallTriggerMonitor`) and be able to **answer and talk** when a matching macro says so.
- **Disclosure:** the agent must always say it's an AI at the start of a call it's driving. This isn't just a prompt suggestion — see §4 Task 7.

**Hard technical constraint, make sure you understand this before writing any audio code:** no third-party Android app (without root) can inject audio into a live cellular call's stream, or read the call's raw audio directly. There is no public API for it. The only way for the AI to "talk on the call" is **acoustic coupling**: put the call on speakerphone, let the AI's TTS play out of the speaker, and have the phone's mic pick up both the AI's own voice (echo-cancelled by hardware, same mechanism `CallAudio` already uses for the normal assistant conversation) and the other party's voice. Expect some latency and occasional echo; that's an accepted, understood limitation — don't try to "fix" it with anything exotic.

**Never place a real test call to a real contact autonomously.** Before any live-call test, stop and ask the user what number is safe to use (their own second number, voicemail, a number they control) and get them present for it. See §4 Task 8.

---

## 2. What already exists (read these files; do not blindly rewrite them)

### 2.1 The macro engine (built in commits `56e0bb3`, `c4ac39c`)

`app/src/main/java/com/nik/voiceassistant/data/macro/Macro.kt`:
```kotlin
sealed class Trigger {
    data class IncomingCall(val contactFilter: String?, val contactLabel: String?) : Trigger()
    data class MissedCall(val contactFilter: String?, val contactLabel: String?) : Trigger()
    // ...Phrase, Manual, Scheduled, AppOpened, NotificationReceived, ChargerConnected/Disconnected, WifiConnected, BootCompleted
}

sealed class Action {
    data class Replay(val steps: List<RecordedStep>) : Action()
    object AlwaysAsk : Action()
    data class HandleCall(val goal: String, val discloseAi: Boolean = true, val maxDurationMs: Long = 5 * 60_000L) : Action()
}
```
`Macro` itself: `id, title, description, triggers: List<Trigger>, constraints, instruction: String, action: Action, enabled, createdAt, lastRunAt, runCount, lastRunOk, isFavorite`. This is the piece you're building toward: a macro with `triggers = [Trigger.IncomingCall(...)]` and `action = Action.HandleCall(goal, discloseAi)` should make the agent answer a matching call and carry out `goal`. A macro with no `IncomingCall`/`MissedCall` trigger at all (e.g. `Trigger.Scheduled` + `Action.HandleCall`) means "place this call at this time" — an outgoing call macro.

`MacroGson.kt` already (de)serializes `Action.HandleCall` — nothing to change there.

`MacroStore.kt` / `MacroEngine.kt` — persistence and literal-replay; **`Action.HandleCall` never goes through `MacroEngine.replay`** (that's only for `Action.Replay`). You are building the thing that runs a `HandleCall` action.

`MacroDispatch.kt` — the shared "a sensor trigger matched a macro, now run it" helper, used by the notification/charger/Wi-Fi listeners and by `CallTriggerMonitor` for non-call macros. Its relevant branch today:
```kotlin
// MacroDispatch.run(context, macro, executor):
is Action.HandleCall -> Log.i(TAG, "HandleCall macro matched outside a call context; ignoring")
```
This is intentionally a no-op today. **Decide, as part of this work, whether an outgoing `HandleCall` macro (e.g. a `Trigger.Scheduled` "call Mom every day at 7pm") should be dispatched through here too.** Recommendation: give `MacroDispatch.run` an optional callback (parallel to `onAgentGoalRequested`) e.g. `var onCallGoalRequested: ((Macro) -> Unit)? = null`, call it from this branch, and have `VoiceSession.init{}` wire it to your new call-starting function. Keep `CallTriggerMonitor`'s own direct `onIncomingCallMacroMatched` path separate (that one is specifically "a call is ringing right now and this macro matches the caller").

### 2.2 Call detection (built in commit `c4ac39c`)

`app/src/main/java/com/nik/voiceassistant/service/CallTriggerMonitor.kt`:
```kotlin
object CallTriggerMonitor {
    @Volatile var onIncomingCallMacroMatched: ((macro: Macro, number: String?, contactName: String?) -> Unit)? = null
    fun start(context: Context)   // already called from VoiceSession.init{}
    fun stop()
    // ...
}
```
It already detects a ringing call, looks up the contact, matches `Trigger.IncomingCall` macros, and — **only** when `macro.action is Action.HandleCall` — invokes `onIncomingCallMacroMatched(macro, number, contactName)`. Right now **nothing sets this callback**, so matched calls just ring normally. Wiring it up is Task 5 below. It does *not* answer the call itself — that's your job (via `TelecomManager`, see Task 3).

It also detects missed calls via a `CallLog` `ContentObserver` and dispatches `Trigger.MissedCall` matches through `MacroDispatch.run` already (for `Replay`/`AlwaysAsk` actions) — that part needs no changes.

### 2.3 Dialing (existing, before this feature; don't break it)

`app/src/main/java/com/nik/voiceassistant/tools/DeviceToolExecutor.kt`, function `makeCall` (around line 946):
```kotlin
private fun makeCall(number: String?, speaker: Boolean): Map<String, Any?> {
    if (number.isNullOrBlank()) return mapOf("error" to "Give a phone number (use find_contact for a name)")
    val tel = Uri.fromParts("tel", number.replace(Regex("[\\s()-]"), ""), null)
    lastDialed = null
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
        context.startActivity(Intent(Intent.ACTION_DIAL, tel).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return withScreen(mapOf("success" to false, "error" to "Phone call permission is off, ..."))
    }
    context.startActivity(Intent(Intent.ACTION_CALL, tel).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    var onSpeaker = false
    if (speaker) {
        Thread.sleep(2500)
        onSpeaker = PhoneAgentService.instance?.switchOn(listOf("Speaker", "Speakerphone", "स्पीकर")) == true
        if (!onSpeaker) { /* AudioManager.isSpeakerphoneOn fallback */ }
    }
    return withScreen(mapOf("success" to true, "calling" to number, "speaker" to onSpeaker,
        "next" to "The call is on (a SIM choice may be showing: pick one if so). Say one short line, then call end_conversation."))
}
```
This is the existing `make_call` tool: it dials, turns on speaker, and tells the model to say one line and end the **voice-assistant** conversation (not hang up the phone call — there is currently no hang-up tool at all; the call stays connected with nobody AI-side listening). **Keep this tool and this behaviour exactly as is** for the common "call Rahul" case where the user just wants the number dialed. You are adding a *new, separate* capability for "place the call and have an actual conversation on it" — see Task 4 for why this should be a distinct tool, not a change to `make_call`.

This function already goes through the `ActionGuard` confirmation gate one level up, in `run()`:
```kotlin
"make_call" -> {
    val number = str("number")
    val risk = if (ActionGuard.isDialCode(number)) ActionGuard.Risk.DIAL_CODE else ActionGuard.Risk.CALL
    number?.let { gate(risk, "call:${digits(it)}", "Call $it", trusted) } ?: makeCall(number, bool("speaker") != false)
}
```
Reuse this exact `gate(...)` pattern for your new tool (§4 Task 4).

### 2.4 Audio building blocks (already generic, reuse as-is — don't fork/copy them)

`app/src/main/java/com/nik/voiceassistant/audio/CallAudio.kt`:
```kotlin
class CallAudio(
    context: Context,
    private val onFocusLost: () -> Unit = {}   // called on main thread
) {
    fun enter(): Boolean   // idempotent; false if focus refused or a call is already active elsewhere
    fun exit()              // idempotent; restores previous audio mode
}
```
This already does everything "make this feel like a call: speakerphone, MODE_IN_COMMUNICATION, max call volume, audio focus" — it's not specific to the Gemini-assistant conversation. **Reuse it directly**, don't copy its logic.

`app/src/main/java/com/nik/voiceassistant/audio/AudioRecordManager.kt`:
```kotlin
class AudioRecordManager(
    private val onAudioChunkReady: (ByteArray) -> Unit,
    private val onAmplitudeChanged: ((Float) -> Unit)? = null,
    private val onError: (String) -> Unit = {}   // start failures: caller's thread; read errors: IO thread
) {
    fun startRecording(coroutineScope: CoroutineScope): Boolean  // false means onError already fired
    fun stopRecording()
}
```

`app/src/main/java/com/nik/voiceassistant/audio/AudioTrackManager.kt`:
```kotlin
class AudioTrackManager(
    private val onPlaybackAmplitudeChanged: ((Float) -> Unit)? = null,
    private val onPlaybackStateChanged: ((Boolean) -> Unit)? = null
) {
    fun isAudible(): Boolean
    fun startPlaybackWorker(coroutineScope: CoroutineScope)
    fun enqueueAudioChunk(chunk: ByteArray)
    fun stopAndFlush()
    fun release()
}
```
**Construct your own, separate instances of both** for the call agent — do not share `VoiceSession`'s `audioRecordManager`/`audioTrackManager`. The main assistant conversation and a call conversation must never run at the same time (see Task 3's mutual-exclusion note), and giving the call agent its own instances makes that trivially true instead of something you have to carefully interleave.

`app/src/main/java/com/nik/voiceassistant/network/LiveClient.kt` — the interface; `GeminiLiveClient` implements it:
```kotlin
class GeminiLiveClient(
    private val audioTrackManager: AudioTrackManager,
    private val tools: ToolCallRunner,
    private val declarations: () -> List<Map<String, Any>>,
    private val listener: LiveClient.Callback
) : LiveClient
```
`LiveClient.Config(serverUrl, apiKey, model, voice, search, instructions: (Boolean) -> String, resumeHandle)` is what you pass to `connect()`. Use a **fresh `GeminiLiveClient` instance** dedicated to the call agent (cheap to construct; don't reuse `VoiceSession`'s `directClient`, for the same isolation reason as the audio managers). The Gemini API key comes from `SecurePrefs.secret(context, SecurePrefs.GEMINI_API_KEY)` exactly like `VoiceSession.start()` reads it. You do **not** need the server/relay transport (`LiveAudioWebSocketClient`) for this — direct-to-Gemini is simplest and this feature doesn't need the relay's extra Hindi STT.

`app/src/main/java/com/nik/voiceassistant/tools/ToolCallRunner.kt`:
```kotlin
class ToolCallRunner(
    private val executor: DeviceToolExecutor,   // see Task 3 — do NOT reuse the main DeviceToolExecutor
    private val listener: LiveClient.Callback,
    private val beforeRespond: (String, Map<String, Any?>) -> Unit = { _, _ -> }
)
```
This class isn't tied to `DeviceToolExecutor` by type beyond calling `.execute(name, args)` — check its exact signature when you read the file; if it's typed to the concrete `DeviceToolExecutor` class rather than an interface, you have two choices: (a) give `DeviceToolExecutor` an optional constrained mode (not recommended — it's already a big class with screen-control tools that make no sense on a call), or (b) write a small separate tool-runner for the call agent rather than reusing `ToolCallRunner` (recommended — the call agent's tool set is tiny, see Task 3, a dedicated minimal runner is clearer and keeps the "no screen tools on a call" guarantee structurally obvious rather than relying on a filtered declarations list).

### 2.5 Security / policy pieces to reuse, not reinvent

- `app/src/main/java/com/nik/voiceassistant/security/ActionGuard.kt` — `Risk.CALL`, `Risk.DIAL_CODE`, `needsYes(risk, level, userText, trusted)`. Reuse for gating when the new outgoing-call tool is invoked (same as `make_call` already does).
- `app/src/main/java/com/nik/voiceassistant/security/Redactor.kt` — `forModel`, `forStorage`, `mask`. Anything from the call (a summary, a transcript line) that gets logged or stored must go through this, same as everywhere else in the app.
- `app/src/main/java/com/nik/voiceassistant/security/AgentPolicy.kt` — `AgentPolicy.current.confirmLevel` etc. Nothing new needed here unless you decide call macros need their own policy knob (see §6 Open Questions).

### 2.6 Where `VoiceSession` already wires things (your integration point)

`app/src/main/java/com/nik/voiceassistant/VoiceSession.kt`, `init {}` block, current content:
```kotlin
init {
    com.nik.voiceassistant.service.PaymentGuard.restore(context)
    VoiceAssistantService.onConversationForegroundFailed = { ... }
    audioTrackManager.startPlaybackWorker(scope)
    AssistantOverlay(this).bind(scope, appVisible)

    com.nik.voiceassistant.data.macro.MacroDispatch.onAgentGoalRequested = { instruction ->
        scope.launch { sendText(instruction) }
    }
    com.nik.voiceassistant.service.CallTriggerMonitor.start(context)
    com.nik.voiceassistant.service.ChargerAndWifiTriggerMonitor.start(context)

    scope.launch { loadHistory() }
}
```
This is where you'll add `CallTriggerMonitor.onIncomingCallMacroMatched = { macro, number, name -> ... }` and (if you implement the outgoing-macro path from §2.1) `MacroDispatch.onCallGoalRequested = { macro -> ... }`.

`VoiceSession` also has, for context/reuse when building the call agent's instructions: `_assistantName.value` (the configured name, default "Shiv"), `DeviceToolExecutor.deviceProfile()` (phone make/model/Android version, one line), and `ownerLine()`/`ownNumbers()` (the user's own numbers, for "message yourself" — not directly relevant to calls but read it for the pattern if the call goal ever needs "the user's own number").

---

## 3. What you're building

### New class: `CallAgentSession`
Suggested location: `app/src/main/java/com/nik/voiceassistant/call/CallAgentSession.kt` (new package; keeps it visually separate from the screen-control world in `tools/`).

Composition (mirror `VoiceSession`'s pattern, trimmed down — read `VoiceSession.kt`'s top section for the exact style of wiring `GeminiLiveClient`/`ToolCallRunner`/`AudioRecordManager`/`AudioTrackManager`/`CallAudio` together, then build a smaller version):

```
CallAgentSession(context: Context, assistantName: () -> String)
  - its own AudioRecordManager, AudioTrackManager, CallAudio (CallAudio(context, onFocusLost = { /* end the call session */ }))
  - its own minimal tool executor exposing ONLY:
      - end_call: hangs up (see Task 3a) and ends this session
      - note_for_user(text): leaves a one-line note the user sees afterward (stored via addTranscript-equivalent,
        through Redactor.forStorage — you'll need VoiceSession to expose a way to post this into its own
        transcript, e.g. a small callback passed into CallAgentSession's constructor:
        onNote: (String) -> Unit, wired in VoiceSession.init{} to a new private fun that calls addTranscript("system", "📞 " + it))
    Do NOT give it tap/type/launch_intent/read_screen/make_call or anything from the main DeviceToolExecutor's
    declarations. This is the single most important constraint in this whole feature.
  - its own GeminiLiveClient, built fresh each call (no caching/sharing across calls — simplest, avoids stale state)
  - fun isActive(): Boolean
  - fun start(number: String?, goal: String, discloseAi: Boolean, maxDurationMs: Long, isIncoming: Boolean): returns
    quickly (launch a coroutine internally); report success/failure/ongoing status via a callback or StateFlow,
    your choice — keep it simple, VoiceSession just needs to know when it starts and ends to show something
    in the transcript and to re-enable its own mic afterward.
  - fun stop() — force-end (e.g. the app is closing)
```

### 3a. Actually answering / hanging up the call

Use `android.telecom.TelecomManager`:
```kotlin
val tm = context.getSystemService(TelecomManager::class.java)
tm.acceptRingingCall()   // API 26+, needs ANSWER_PHONE_CALLS
tm.endCall()             // API 28+ requires ANSWER_PHONE_CALLS too (deprecated signature pre-28 needs MODIFY_PHONE_STATE, a system-only permission — don't go there)
```
If `ANSWER_PHONE_CALLS` isn't granted, **don't auto-answer** — just don't act (let it ring normally; log once). For hanging up without that permission (e.g. ending a call *you* placed), fall back to the accessibility-gesture approach already used elsewhere in this codebase: `PhoneAgentService.instance?.switchOn(listOf("End call", "End", "Hang up", "कॉल समाप्त करें"))` — same pattern as the existing `switchOn(listOf("Speaker", ...))` call in `makeCall()`. Read `PhoneAgentService.switchOn` before relying on it; it finds a clickable node by label and taps it, which works on the in-call screen's own End-call button across most dialers.

### 3b. Waiting for the call to actually connect

You need to know when a placed call goes from dialing to `OFFHOOK` (connected) before starting the audio loop — otherwise the AI starts talking into a ringing tone. `CallTriggerMonitor` already has the telephony-state plumbing (`TelephonyCallback`/`PhoneStateListener`) but it's structured around *trigger matching*, not around "tell me the moment this specific outgoing call connects." Either:
- (a) add a small, separate, focused listener inside `CallAgentSession` itself (don't entangle it with `CallTriggerMonitor`'s trigger-matching logic — a plain `TelephonyCallback`/`PhoneStateListener` that just resolves a `CompletableDeferred<Boolean>` on reaching `OFFHOOK` or `IDLE`, with a timeout), or
- (b) factor a tiny reusable "wait for call state" helper out that both `CallTriggerMonitor` and `CallAgentSession` can use.
(a) is simpler and lower-risk for this pass — don't over-refactor `CallTriggerMonitor` while you're not sure yet how much the two need to share.

### 3c. Mutual exclusion with the main assistant conversation

`VoiceSession` and `CallAgentSession` must never both be driving the mic/speaker at once. Before `CallAgentSession.start()` begins, check `voiceSession.isActive` — if the main conversation is running, either queue the call-agent start until it ends, or (simpler, and reasonable for v1) just don't start and report "assistant is busy" back through whatever triggered it. Conversely, `VoiceSession.start()` should check `callAgentSession.isActive()` and refuse (with a clear transcript message) if a call is in progress. Wire this check wherever you hold both references — likely `VoiceSession` ends up owning the `CallAgentSession` instance (`private val callAgent = CallAgentSession(context) { _assistantName.value }`), so the check in `VoiceSession.start()` is a simple `if (callAgent.isActive()) { ...; return@launch }` near the top, parallel to the existing `if (_isRecording.value) return@launch` check.

### 3d. Instructions (the call-agent's system prompt)

Do **not** reuse `VoiceSession.DEFAULT_INSTRUCTIONS` (it's full of screen-control guidance that's irrelevant and actively confusing here). Write a dedicated, short prompt, something like:

```
You are {NAME}, an AI assistant {placing/answering} a phone call for {owner-description, or just "your user" if unknown}.
Your goal for this call: {goal}

{IF discloseAi}
Your very first sentence, before anything else, must be exactly this kind of line (translate/adapt naturally
to the conversation's language): "Namaste, main {NAME} hoon, [user]'s AI assistant." Never skip this.
{END IF}

Speak naturally and listen. Work toward the goal. If the other person asks to speak to the real person, or
the call isn't something you can usefully continue, say so honestly and call end_call. When the goal is
reached, or there's nothing more to do, say a short goodbye and call end_call.
You have no other abilities on this call: you cannot access the phone's screen, make payments, or take any
action beyond talking. If asked to do something like that, say you can't and that the user will need to
handle it themselves.
Call note_for_user once, near the end, with one short sentence summarizing the outcome for your user to read later.
```
Keep the **disclosure instruction structurally forced**, not just a suggestion the model could skip under pressure: the safest approach is to literally prepend the disclosure sentence yourself (not via the model) as the very first thing spoken — e.g. have `CallAgentSession` call `sendText` or (better, since this is a live audio client, not a text relay) whatever `GeminiLiveClient` offers for an initial scripted utterance, OR simply make the first model turn mandatory by having the connect-time instructions state it as the literal first required action and verify (log a warning, don't block the call) if the model's first transcript doesn't contain the expected opening — full enforcement isn't critical for v1, a strongly worded instruction plus a log-and-flag-if-skipped is acceptable, but note this as a known gap if you don't fully enforce it.

### 3e. Reporting back to the user

After the call ends (goal done, other party hung up, timeout, or error), call the `onNote`/transcript callback with a one-line summary (through `Redactor.forStorage`). If it was a macro-triggered call, consider updating `MacroStore` (`lastRunAt`, `runCount`, `lastRunOk`) the same way `MacroEngine.replay` does for replay macros — reuse that exact pattern (`MacroStore.update(context, macro.id) { it.copy(lastRunAt = ..., runCount = it.runCount + 1, lastRunOk = ok) }`).

---

## 4. Ordered task list

1. **Permission**: add `<uses-permission android:name="android.permission.ANSWER_PHONE_CALLS" />` to `AndroidManifest.xml` (API 26+, no special protection level beyond normal runtime request). Request it at runtime in `MainActivity.checkAndRequestPermissions()`'s permission list, same pattern as the existing entries there. **Do not** request `MODIFY_PHONE_STATE` or anything system-only — it isn't grantable to a normal app and you don't need it (see 3a).

2. **`CallPlacer` helper** (optional but recommended): extract the dial+speaker-on logic currently inline in `DeviceToolExecutor.makeCall()` into a small shared function (e.g. `tools/CallPlacer.kt`, `fun place(context, number, speaker): Map<String, Any?>` or similar) so both the existing `make_call` tool and your new outgoing-call path can use it without duplicating the `ACTION_CALL`/`ACTION_DIAL` fallback and the speaker-on gesture. Keep `make_call`'s existing behavior and its "next" text byte-for-byte the same — this is a pure extraction, not a behavior change, and there's no test coverage for `makeCall()` today, so be careful and manually re-verify "call Rahul" still works exactly as before.

3. **`CallAgentSession`**: build per §3 above. This is the bulk of the work. Build and test it in isolation first — e.g. a temporary debug trigger (a button, or a dev-only voice phrase) to call `callAgent.start(number = <your own second number>, goal = "say hello and ask how they are, then end the call", ...)` before wiring it into macros at all. Verify manually on-device (with the user) that: the call connects, the AI's voice comes through the speaker, the AI hears the other side, `end_call` actually hangs up, and the main assistant's mic/wake-word works normally again afterward.

4. **Outgoing tool**: add a new tool declaration, e.g. `call_and_talk` (required: `number` or a contact lookup via the existing `find_contact` tool first, `goal`; optional: `max_minutes`). Its description must make clear this is for when the user wants the AI to actually hold a conversation on the call ("order 2 pizzas from Domino's", "call the plumber and ask if tomorrow works"), as distinct from `make_call` (just connect me to this person). Gate it through `ActionGuard` exactly like `make_call` does (`gate(ActionGuard.Risk.CALL, "call_and_talk:${digits(number)}", "Call $number to: $goal", trusted)`), then delegate to `callAgent.start(...)`.

5. **Incoming wiring**: in `VoiceSession.init{}`, set `CallTriggerMonitor.onIncomingCallMacroMatched = { macro, number, name -> val action = macro.action as Action.HandleCall; callAgent.start(number, action.goal, action.discloseAi, action.maxDurationMs, isIncoming = true) }`. Guard for `ANSWER_PHONE_CALLS` not granted (see 3a) — just don't answer, don't crash.

6. **(Optional for this pass, note if deferred) Outgoing macros**: wire `MacroDispatch.onCallGoalRequested` (the callback you'd add per §2.1) to the same `callAgent.start(..., isIncoming = false)`, so a `Trigger.Scheduled` + `Action.HandleCall` macro can place a call on a timer. If you run low on scope, it's fine to leave this specific wiring for a follow-up — just make sure `MacroDispatch.run`'s `HandleCall` branch logs clearly that it's unhandled rather than silently swallowing it, so it's easy to pick up later.

7. **(Optional for this pass) UI**: `AutomationsTab.kt`'s `CreateAutomationDialog` only builds `Replay`/`AlwaysAsk` macros today. Adding a "When a call comes in from ___, do ___" creation flow is real but lower-priority UI work — fine to defer; note it clearly if you do.

8. **Testing — read this before dialing anything:**
   - Compile/test/lint must stay green (commands at the top of this doc) before any device step.
   - **Stop and ask the user** what number is safe for a live test (their own second device, a number they control, voicemail) before placing any real call. Never dial a contact from their address book autonomously.
   - For incoming-call testing, ask the user to place the test call themselves, from a device they control, while present and aware it's a test.
   - After a test call, verify: `CallAudio` fully restored normal audio mode (check `AudioManager.mode == MODE_NORMAL` or whatever it was before), the mic foreground service didn't get killed, and the normal assistant ("Hi Nik") still works right after.

---

## 5. Commit plan

The user wants **grouped commits**, not one giant one. Suggested grouping, each a separate `git commit` on `production-hardening`:
1. `ANSWER_PHONE_CALLS` permission + `CallPlacer` extraction (if done) — mechanical, low-risk, easy to review alone.
2. `CallAgentSession` itself (the new audio/live-client/minimal-tool composition) — the core of the feature.
3. The outgoing `call_and_talk` tool + its `ActionGuard` wiring.
4. Incoming-call wiring (`CallTriggerMonitor.onIncomingCallMacroMatched` hookup) + the answer/hangup `TelecomManager`/accessibility-fallback logic.
5. UI / outgoing-macro wiring, if you get to them.

Check the system reminder in your own session for the exact `Co-Authored-By:` attribution line currently in effect (it is **not** fixed — it has changed partway through this project's history already, e.g. between Claude Opus 5.5 and Claude Sonnet 5) and use whichever one your session states, not a value copied from an old commit.

---

## 6. Open questions — ask the user, don't guess

- Should a macro-triggered outgoing call (e.g. a scheduled "call Mom every evening") be allowed to dial **without a fresh confirmation each time**, relying on the macro itself having been something the user set up (same `trusted = true` reasoning `MacroEngine.replay` already uses for taps/sends)? Or should every `call_and_talk`, even from a saved macro, always need a fresh yes first, given it's a bigger action than a tap? Recommend asking before deciding; the rest of the system (see `ActionGuard.needsYes`, `Balanced` level) treats a macro the user created as "asking," but calls are a step up in consequence from taps/sends.
- If `ANSWER_PHONE_CALLS` is denied, is "incoming calls just ring normally forever, never auto-answered, no nag" the right fallback? (Recommended — avoid pestering.) Or should Settings show a clear "this macro needs a permission" indicator?
- Confirm safe test numbers before Task 8.

---

## 7. What NOT to do

- Don't try to access or inject into the call's raw audio via any `AudioRecord`/`AudioTrack` source constant, undocumented API, or reflection trick "to get better quality." It doesn't exist for a non-system app; acoustic coupling via `CallAudio` is correct and sufficient.
- Don't give the call agent's tool executor anything beyond `end_call` / `note_for_user`. No screen tools, no `launch_intent`, no `make_call` (that would let it place *another* call mid-call), nothing finance-shaped.
- Don't skip the disclosure line "because the model usually says it anyway." Make it as forced as reasonably possible (§3d).
- Don't call a real, unaware person to test this.
- Don't touch `main`; don't force-push; don't `git reset --hard`/`clean` without checking `git status` first and stashing/committing anything present.
