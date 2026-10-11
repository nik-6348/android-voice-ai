# 🎙️ Nik Jarvis — Native Android Voice Assistant

Production-ready Native Android Voice Assistant built with **Kotlin**, **OkHttp WebSockets**, and **Jetpack Compose**. Directly connects to Gemini Live Real-time Audio WebSocket stream (`wss://voice.ai.nik6348.in/ws/live`) with hardware tool execution and barge-in capability.

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
