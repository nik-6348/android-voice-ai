# 🎙️ Nik Jarvis — Native Android Voice Assistant

Native Android Voice Assistant built with **Kotlin**, **OkHttp WebSockets**, and **Jetpack Compose**. Connects to Gemini Live directly, or through the gateway server (Settings > Connection; default `BuildConfig.DEFAULT_WS_URL`), with hardware tool execution and barge-in capability.

---

## ⚡ Tech Stack & Architecture

- **Platform:** Android (API 26+ / Android 8.0 to Android 14+)
- **Audio Capture:** `AudioRecord` (16kHz, 16-bit Mono PCM, 1024-byte chunks with AEC)
- **Audio Playback:** `AudioTrack` (24kHz, 16-bit Mono PCM, Low Latency Stream Mode)
- **Network Pipeline:** OkHttp `WebSocket` with full-duplex binary audio and JSON telemetry
- **Hardware & App Actions:** `PackageManager` (App Launcher), `CameraManager` (Torch/Flashlight), `BluetoothAdapter`
- **UI & Animations:** Jetpack Compose with real-time `SiriWaveVisualizer` and Material 3 Dark theme
- **Background Execution:** Android `ForegroundService` with `microphone` type

---

## 📂 Project Structure

```
android-voice/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── java/com/nik/voiceassistant/
│           ├── MainActivity.kt                     # Permissions & Compose host
│           ├── VoiceAssistantViewModel.kt          # State & Session Orchestrator
│           ├── audio/
│           │   ├── AudioRecordManager.kt           # 16kHz PCM Mic input & RMS
│           │   └── AudioTrackManager.kt            # 24kHz PCM Playback & Barge-in flush
│           ├── network/
│           │   └── LiveAudioWebSocketClient.kt     # Gemini Live duplex WebSocket client
│           ├── tools/
│           │   ├── DeviceToolExecutor.kt           # App launching, flashlight, bluetooth
│           │   └── ToolModels.kt                   # Tool JSON payloads
│           ├── service/
│           │   └── VoiceAssistantService.kt        # Microphone Foreground Service
│           └── ui/
│               ├── VoiceAssistantScreen.kt         # Futuristic Jarvis UI
│               ├── components/
│               │   └── SiriWaveVisualizer.kt       # Dynamic Siri-like sine wave canvas
│               └── theme/
│                   ├── Color.kt
│                   └── Theme.kt
├── build.gradle.kts
├── settings.gradle.kts
└── gradle/
    └── libs.versions.toml
```

---

## 🚀 How to Run in Android Studio

1. Open **Android Studio** -> Select **Open** -> Choose `/Users/sama/Desktop/projects/ai-service/android-voice`.
2. Allow Gradle to sync dependencies automatically.
3. Connect a physical Android device or launch an Emulator with microphone support enabled.
4. Click **Run** (`Shift + F10`).
5. Grant **Microphone**, **Camera**, and **Notification** permissions when prompted.
6. Tap the glowing blue Mic button to initiate the live session!

---

## 🛠️ Supported Voice Actions (Phase 2 Tools)

The backend Gemini Live model can trigger JSON tool calls that Android intercepts and executes locally:

| Action | Example Prompt | Android Implementation |
| :--- | :--- | :--- |
| **Open Installed App** | *"Open YouTube"* / *"Launch WhatsApp"* | `DeviceToolExecutor.openAppByName()` |
| **Flashlight Toggle** | *"Turn on the flashlight"* | `CameraManager.setTorchMode(id, true)` |
| **Bluetooth Toggle** | *"Turn on bluetooth"* | `BluetoothAdapter` / System intent |

---

## 🌊 Barge-In Handling

When the assistant is speaking and the user begins talking (or when the backend sends `{"type": "interrupted"}`):
1. `AudioTrackManager.stopAndFlush()` is called immediately.
2. The internal buffer and hardware queues are flushed without playing stale audio.
3. The visualizer and speech state cleanly reset.

---

## 🔐 Safety & Privacy (Settings)

The agent reads the screen, and everything on it (messages, web pages, notifications) reaches the model. So the risky checks run on the phone, outside the model (`security/ActionGuard.kt`), and only the user's own words count as asking:

| Setting | Default | What it does |
| :--- | :--- | :--- |
| **Ask before actions** | Balanced | *Relaxed*: asks only before payments, wipes and dial codes (`*21*…#`). *Balanced*: also before calls, sending and deleting, unless the user asked for that in their own words. *Strict*: before every call, send, delete and payment. When an action needs an OK, the phone shows a yes / no; the model retries after a yes. |
| **Hide OTPs & card numbers from the AI** | Off | Masks codes and card numbers in what the agent reads (it can't read an OTP for you then). Password fields are never read. |
| **Auto-unlock** | Off | Types the saved PIN only on the real lock screen. Anyone who can talk to the locked phone can use it while on; two wrong PINs turn it off. The PIN is never logged or sent. |
| **Save conversations** / keep for | On / 30 days | History on the phone; codes and card numbers are masked before saving. |
| **Save activity logs** / screen contents / keep for | On / Off / 7 days | The Activity tab's logs and traces. |
| **Start listening when the app opens** | On | |

Nothing is included in cloud backups or device transfers (`allowBackup=false`, `res/xml/data_extraction_rules.xml`). Release builds are minified with R8 and drop `Log.v/d/i` (`app/proguard-rules.pro`).

## 🧪 Tests

```
./gradlew testDebugUnitTest
```
