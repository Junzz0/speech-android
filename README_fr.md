# Speech Android

📖 Langues : [English](README.md) · [中文](README_zh.md) · [日本語](README_ja.md) · [한국어](README_ko.md) · [Español](README_es.md) · [Deutsch](README_de.md) · [Français](README_fr.md) · [हिन्दी](README_hi.md) · [Português](README_pt.md) · [Русский](README_ru.md)

SDK vocal sur appareil pour Android et Linux embarqué, propulsé par [ONNX Runtime](https://onnxruntime.ai) et [speech-core](https://github.com/soniqo/speech-core).

Reconnaissance vocale (114 langues), synthèse vocale (8 langues), détection d'activité vocale et suppression de bruit — tout fonctionne en local. Aucune API cloud, aucune donnée ne quitte l'appareil.

**[APK de démo](https://github.com/soniqo/speech-android/releases/latest/download/app-release.apk)** · **[Modèles](https://huggingface.co/collections/aufklarer/speech-android-models-69bb8a156cac0b96a2247f26)** · **[speech-swift](https://github.com/soniqo/speech-swift)** (équivalent Apple) · **[speech-core](https://github.com/soniqo/speech-core)** (moteur de pipeline)

## Plateformes

| Plateforme | API | Accélération | Répertoire |
| --- | --- | --- | --- |
| Android | Kotlin (`SpeechPipeline`) | NNAPI (Snapdragon, Exynos, Tensor) | `sdk/` |
| Linux embarqué | C (`speech.h`) | QNN (Hexagon DSP) | `linux/` |

## Modèles

| Modèle | Tâche | Taille INT8 | Langues |
| --- | --- | --- | --- |
| [Parakeet TDT v3](https://huggingface.co/aufklarer/Parakeet-TDT-v3-ONNX) | Reconnaissance vocale | 891 Mo | 114 |
| [Kokoro 82M](https://huggingface.co/aufklarer/Kokoro-82M-ONNX) | Synthèse vocale | 330 Mo | 8 (en, fr, es, it, pt, hi, ja, zh) |
| [Silero VAD v5](https://huggingface.co/aufklarer/Silero-VAD-v5-ONNX) | Détection d'activité vocale | 2 Mo | Toutes |
| [DeepFilterNet3](https://huggingface.co/aufklarer/DeepFilterNet3-ONNX) | Suppression de bruit | ~8 Mo | Toutes |

Les modèles sont téléchargés automatiquement au premier lancement (Android) ou placés manuellement (Linux).

## Android

### Essayer la démo

Téléchargez l'[APK signé](https://github.com/soniqo/speech-android/releases/latest/download/app-release.apk) et installez-le sur n'importe quel appareil Android arm64 (8+). Les modèles (~1,2 Go) sont téléchargés automatiquement au premier lancement.

### Ajouter la dépendance

```kotlin
dependencies {
    implementation("audio.soniqo:speech:0.0.8")
}
```

### Utilisation Kotlin

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

// Alimente avec du PCM float32 mono 16 kHz depuis le micro
pipeline.pushAudio(samples)
```

### Compiler depuis les sources

```bash
git clone --recursive https://github.com/soniqo/speech-android.git
cd speech-android
./setup.sh
./gradlew :app:assembleDebug
./gradlew :sdk:connectedAndroidTest   # 34 tests e2e
```

### Application de démo

Le module [`app/`](app/) est une démo minimale d'assistant vocal avec :

- Visualisation de la forme d'onde VAD en temps réel
- Mode écho : transcrit la voix et la synthétise en retour (sans LLM)
- Mode dictée : résultats partiels en streaming
- Écran de test `SpeechRecognizer` — exerce le chemin d'entrée vocale à l'échelle du système
- Interface de bulles de chat avec affichage de la latence STT/TTS

```bash
./gradlew :app:installDebug
```

### Entrée vocale système (`RecognitionService`)

Le SDK fournit un `audio.soniqo.speech.service.SpeechRecognitionService` prêt à l'emploi qui s'intègre à l'API `SpeechRecognizer` du framework Android — aucun code à écrire. Une fois votre app sélectionnée comme reconnaisseur vocal par défaut, toute application tierce appelant `SpeechRecognizer.createSpeechRecognizer(context)` (sans `ComponentName`) obtient un STT entièrement on-device via votre pipeline.

**1. Déclarez `RECORD_AUDIO` et le service dans `AndroidManifest.xml` :**

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

**2. Ajoutez `app/src/main/res/xml/recognition_service.xml` :**

```xml
<?xml version="1.0" encoding="utf-8"?>
<recognition-service xmlns:android="http://schemas.android.com/apk/res/android" />
```

(Ajoutez optionnellement `android:settingsActivity="..."` pour afficher une icône d'engrenage dans le sélecteur d'entrée vocale système.)

**3. Définissez le service comme valeur par défaut système** (Paramètres → Système → Langues et saisie → Sélecteur d'entrée vocale sur Android pur, ou via adb) :

```bash
adb shell settings put secure voice_recognition_service \
  your.package/audio.soniqo.speech.service.SpeechRecognitionService
```

**4. Vérifiez** en lançant l'écran *Recognizer test* de l'app de démo, qui appelle `SpeechRecognizer.createSpeechRecognizer(ctx)` (sans composant) et journalise chaque callback du framework — utile pour confirmer l'aller-retour binder sans avoir besoin de logcat.

Le service implémente `onCheckRecognitionSupport` (API 33+) renvoyant les 27 langues BCP-47 couvertes par Parakeet TDT v3, marquées `installedOnDeviceLanguage` lorsque les modèles sont présents (ou `pendingOnDeviceLanguage` pendant leur téléchargement). Le focus audio est acquis avec `AUDIOFOCUS_GAIN_TRANSIENT` pendant la durée d'une session.

**Limitation :** Gboard, Samsung Keyboard et Google Assistant intègrent leurs propres reconnaisseurs et contournent la valeur par défaut système. Les applications qui appellent explicitement l'API `SpeechRecognizer` du framework (ou construisent leur propre UI par-dessus) sont celles qui passent par votre service.

## Performance

Mesuré sur émulateur Android (arm64-v8a, sans NNAPI). Le matériel réel est nettement plus rapide.

| Modèle | Tâche | Audio | Inférence | RTF |
| --- | --- | --- | --- | --- |
| Parakeet TDT v3 | STT | 1,5 s | 175 ms | 0,12 |
| Kokoro 82M | TTS | 1,9 s en sortie | 1 075 ms | 0,58 |
| Silero VAD v5 | VAD | bloc 32 ms | <1 ms | <0,01 |

## Linux embarqué

API C minimale pour les plateformes automobiles et embarquées. Voir [`linux/README.md`](linux/README.md) pour la documentation complète.

### Utilisation de l'API C

```c
#include <speech.h>

void on_event(const speech_event_t* event, void* ctx) {
    if (event->type == SPEECH_EVENT_TRANSCRIPTION)
        printf("%s\n", event->text);
}

speech_config_t cfg = speech_config_default();
cfg.model_dir = "/opt/speech/models";
cfg.use_qnn = true;  // Accélération Hexagon DSP

speech_pipeline_t p = speech_create(cfg, on_event, NULL);
speech_start(p);
speech_push_audio(p, pcm_samples, 512);
```

### Compiler

```bash
cd linux && ./setup_linux.sh
cmake -B build -DORT_DIR=../ort-linux
cmake --build build
./build/speech_demo --model-dir /path/to/models
```

### Tester

```bash
linux/tests/download_models.sh              # télécharger les modèles ONNX
SPEECH_MODEL_DIR=tests/models ./build/speech_test   # 12 tests
```

### Compilation croisée pour Yocto

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

Le barge-in est pris en charge : parler pendant la lecture TTS l'interrompt et démarre une nouvelle transcription.

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

## Accélération matérielle

| Plateforme | Chipset | Accélération |
| --- | --- | --- |
| Android | Snapdragon 8 Gen 1+ | NNAPI → Hexagon NPU |
| Android | Samsung Exynos 2200+ | NNAPI → Samsung NPU |
| Android | Google Tensor G2+ | NNAPI → Google TPU |
| Automobile | SA8295P / SA8255P | QNN → Hexagon DSP |
| Toutes | Repli CPU | XNNPACK |

## Projets liés

| Dépôt | Plateforme |
| --- | --- |
| [speech-swift](https://github.com/soniqo/speech-swift) | Apple (macOS, iOS) — MLX + CoreML |
| [speech-core](https://github.com/soniqo/speech-core) | Moteur de pipeline C++ multiplateforme |
| **speech-android** | Android + Linux embarqué — ONNX Runtime |

## Licence

Apache 2.0
