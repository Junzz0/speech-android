# Speech Android

📖 Read in: [English](README.md) · [中文](README_zh.md) · [日本語](README_ja.md) · [한국어](README_ko.md) · [Español](README_es.md) · [Deutsch](README_de.md) · [Français](README_fr.md) · [हिन्दी](README_hi.md) · [Português](README_pt.md) · [Русский](README_ru.md)

On-device speech SDK for Android and embedded Linux, powered by [ONNX Runtime](https://onnxruntime.ai) and [speech-core](https://github.com/soniqo/speech-core).

Speech recognition (114 languages), text-to-speech (8 languages), voice activity detection, and noise cancellation — all running locally. No cloud APIs, no data leaves the device.

**[Demo APK](https://github.com/soniqo/speech-android/releases/latest/download/app-release.apk)** · **[Models](https://huggingface.co/collections/aufklarer/speech-android-models-69bb8a156cac0b96a2247f26)** · **[speech-swift](https://github.com/soniqo/speech-swift)** (Apple counterpart) · **[speech-core](https://github.com/soniqo/speech-core)** (pipeline engine)

## Platforms

| Platform | API | Acceleration | Directory |
| --- | --- | --- | --- |
| Android | Kotlin (`SpeechPipeline`) | NNAPI (Snapdragon, Exynos, Tensor) | `sdk/` |
| Embedded Linux | C (`speech.h`) | QNN (Hexagon DSP) | `linux/` |

## Models

| Model | Task | INT8 Size | Languages |
| --- | --- | --- | --- |
| [Parakeet TDT v3](https://huggingface.co/aufklarer/Parakeet-TDT-v3-ONNX) | Speech recognition | 891 MB | 114 |
| [Kokoro 82M](https://huggingface.co/aufklarer/Kokoro-82M-ONNX) | Text-to-speech | 330 MB | 8 (en, fr, es, it, pt, hi, ja, zh) |
| [Silero VAD v5](https://huggingface.co/aufklarer/Silero-VAD-v5-ONNX) | Voice activity detection | 2 MB | Any |
| [DeepFilterNet3](https://huggingface.co/aufklarer/DeepFilterNet3-ONNX) | Noise cancellation | ~8 MB | Any |

Models are downloaded automatically on first launch (Android) or placed manually (Linux).

## Android

### Try the demo

Download the [signed APK](https://github.com/soniqo/speech-android/releases/latest/download/app-release.apk) and install on any arm64 Android device (8+). Models (~1.2 GB) download automatically on first launch.

### Add dependency

```kotlin
dependencies {
    implementation("audio.soniqo:speech:0.0.9")
}
```

### Kotlin usage

```kotlin
val modelDir = ModelManager.ensureModels(context)

val pipeline = SpeechPipeline(
    SpeechConfig(modelDir = modelDir, useNnapi = true)
)

pipeline.events.collect { event ->
    when (event) {
        is SpeechEvent.TranscriptionCompleted -> println(event.text)
        is SpeechEvent.ResponseDone -> pipeline.resumeListening()
        else -> {}
    }
}

pipeline.start()

// Feed 16kHz mono float32 PCM from microphone
pipeline.pushAudio(samples)
```

### Build from source

```bash
git clone --recursive https://github.com/soniqo/speech-android.git
cd speech-android
./setup.sh
./gradlew :app:assembleDebug
./gradlew :sdk:connectedAndroidTest   # 34 e2e tests
```

### Demo app

The [`app/`](app/) module is a minimal voice assistant demo with:

- Real-time VAD waveform visualization
- Echo mode: transcribes speech and synthesizes it back (no LLM)
- Dictation mode: streaming partial results
- `SpeechRecognizer` test screen — exercises the system-wide voice input path
- Chat bubble UI with STT/TTS latency display

```bash
./gradlew :app:installDebug
```

### System voice input (`RecognitionService`)

The SDK ships a ready-made `audio.soniqo.speech.service.SpeechRecognitionService`
that plugs into Android's framework `SpeechRecognizer` API — no code to write.
Once your app is selected as the default voice recognizer, any third-party app
calling `SpeechRecognizer.createSpeechRecognizer(context)` (with no
`ComponentName`) gets fully on-device STT through your pipeline.

**1. Declare `RECORD_AUDIO` and the service in `AndroidManifest.xml`:**

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />

<application>
    <service
        android:name="audio.soniqo.speech.service.SpeechRecognitionService"
        android:exported="true"
        android:permission="android.permission.RECORD_AUDIO">
        <intent-filter>
            <action android:name="android.speech.RecognitionService" />
        </intent-filter>
        <meta-data
            android:name="android.speech"
            android:resource="@xml/recognition_service" />
    </service>
</application>
```

**2. Add `app/src/main/res/xml/recognition_service.xml`:**

```xml
<?xml version="1.0" encoding="utf-8"?>
<recognition-service xmlns:android="http://schemas.android.com/apk/res/android" />
```

(Optionally add `android:settingsActivity="..."` to expose a gear icon in the
system Voice-input picker.)

**3. Set the service as the system default** (Settings → System → Languages
& input → Voice input picker on stock Android, or via adb):

```bash
adb shell settings put secure voice_recognition_service \
  your.package/audio.soniqo.speech.service.SpeechRecognitionService
```

**4. Verify** by running the demo app's *Recognizer test* screen, which calls
`SpeechRecognizer.createSpeechRecognizer(ctx)` (no component) and logs every
framework callback — useful for confirming the binder round-trip without
needing logcat.

The service implements `onCheckRecognitionSupport` (API 33+) returning the
27 BCP-47 languages Parakeet TDT v3 covers, marked
`installedOnDeviceLanguage` once models are present (or
`pendingOnDeviceLanguage` while they're downloading). Audio focus is
acquired with `AUDIOFOCUS_GAIN_TRANSIENT` for the duration of a session.

**Caveat:** Gboard, Samsung Keyboard, and Google Assistant bundle their own
recognizers and skip the system default. Apps that explicitly call the
framework `SpeechRecognizer` API (or build their own UI on top of it) are
the ones that flow through your service.

## Performance

Measured on Android emulator (arm64-v8a, no NNAPI). Real hardware is significantly faster.

| Model | Task | Audio | Inference | RTF |
| --- | --- | --- | --- | --- |
| Parakeet TDT v3 | STT | 1.5s | 175ms | 0.12 |
| Kokoro 82M | TTS | 1.9s output | 1,075ms | 0.58 |
| Silero VAD v5 | VAD | 32ms chunk | <1ms | <0.01 |

## Embedded Linux

Minimal C API for automotive and embedded platforms. See [`linux/README.md`](linux/README.md) for full documentation.

### C API usage

```c
#include <speech.h>

void on_event(const speech_event_t* event, void* ctx) {
    if (event->type == SPEECH_EVENT_TRANSCRIPTION)
        printf("%s\n", event->text);
}

speech_config_t cfg = speech_config_default();
cfg.model_dir = "/opt/speech/models";
cfg.use_qnn = true;  // Hexagon DSP acceleration

speech_pipeline_t p = speech_create(cfg, on_event, NULL);
speech_start(p);
speech_push_audio(p, pcm_samples, 512);
```

### Build

```bash
cd linux && ./setup_linux.sh
cmake -B build -DORT_DIR=../ort-linux
cmake --build build
./build/speech_demo --model-dir /path/to/models
```

### Test

```bash
linux/tests/download_models.sh              # download ONNX models
SPEECH_MODEL_DIR=tests/models ./build/speech_test   # 12 tests
```

### Cross-compile for Yocto

```bash
source /opt/poky/environment-setup-aarch64-poky-linux
cmake -B build -DCMAKE_TOOLCHAIN_FILE=toolchain-aarch64.cmake -DORT_DIR=...
cmake --build build
```

## Pipeline

```text
Idle → Listening → Transcribing → Speaking → Idle
              ↑                         |
              └─── resumeListening() ───┘
```

Barge-in supported: speaking during TTS playback interrupts and starts a new transcription.

## Architecture

```text
┌──────────────────────────────────────────────┐
│   Android: SpeechPipeline (Kotlin/JNI)       │
│   Linux:   speech.h (C API)                  │
└──────────────────┬───────────────────────────┘
                   │
┌──────────────────┴───────────────────────────┐
│            speech-core (C++ submodule)        │
│   Turn detection · Interruptions · Context   │
└──┬────────┬────────┬────────┬────────────────┘
   │        │        │        │  vtables
┌──┴──┐  ┌──┴──┐  ┌──┴──┐  ┌─┴────────┐
│ VAD │  │ STT │  │ TTS │  │ Enhancer │
│Silero│  │Para-│  │Koko-│  │DeepFilter│
│     │  │keet │  │ro   │  │Net3      │
└──┬──┘  └──┬──┘  └──┬──┘  └─┬────────┘
   └────────┴────────┴────────┘
       ONNX Runtime (CPU / NNAPI / QNN)
```

## Hardware Acceleration

| Platform | Chipset | Acceleration |
| --- | --- | --- |
| Android | Snapdragon 8 Gen 1+ | NNAPI → Hexagon NPU |
| Android | Samsung Exynos 2200+ | NNAPI → Samsung NPU |
| Android | Google Tensor G2+ | NNAPI → Google TPU |
| Automotive | SA8295P / SA8255P | QNN → Hexagon DSP |
| Any | CPU fallback | XNNPACK |

## Related

| Repository | Platform |
| --- | --- |
| [speech-swift](https://github.com/soniqo/speech-swift) | Apple (macOS, iOS) — MLX + CoreML |
| [speech-core](https://github.com/soniqo/speech-core) | Cross-platform C++ pipeline engine |
| **speech-android** | Android + embedded Linux — ONNX Runtime |

## License

Apache 2.0
