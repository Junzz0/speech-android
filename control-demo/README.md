# Full-pipeline control demo

This separate application runs a complete on-device voice agent:

`Silero VAD → Parakeet-EOU STT → FunctionGemma 270M → Android action → Pocket TTS`

It links directly to this checkout's `:sdk` module, so SDK and Pocket changes
are exercised without publishing an intermediate artifact. On first launch it
downloads the public speech models plus two separate FunctionGemma artifacts:
the reusable 327.4 MB Android LoRA base and the 9.5 MB Control adapter. It then
operates without a network connection.

The app always pairs the adapter with its compact prompt serialization. The
exact base and adapter files are published under
[`soniqo/FunctionGemma-270M-LiteRT-LM`](https://huggingface.co/soniqo/FunctionGemma-270M-LiteRT-LM).

## Install

Download the latest signed
[Control Demo APK](https://github.com/soniqo/speech-android/releases/latest/download/control-demo-release.apk),
or install a development build from source as described below.

## Run

Use an arm64 Android device with at least 4 GB RAM:

```bash
./setup.sh
./gradlew :control-demo:installDebug
```

Grant microphone access in the app, tap the orb, and try commands such as
"set volume to three" or "what can you do?" Contact and media commands also
need their corresponding Android permissions.

Call commands speak their confirmation before opening Android's system dialer;
the demo never places a call directly.

The footer shows STT, LLM, Pocket first-audio, round-trip, and memory metrics.
The same data is available for device benchmarks with:

```bash
adb logcat -s SpeechControl | grep 'TURN'
```

## Crash diagnostics

On Android 11 and newer, the demo records only its coarse execution phase
(`loading_llm`, `thinking`, `speaking`, and so on) in Android's process-exit
metadata. It never puts an utterance, contact, or media title there. After an
unexpected Java/native crash, ANR, signal, or low-memory kill, the next launch
shows the previous exit reason, phase, signal where available, and Android's
last sampled PSS. Open **ⓘ → Share diagnostics** to copy the full device/app
summary into a bug report.

For the stack trace, reproduce once while collecting logcat:

```bash
adb logcat -c
adb logcat -v threadtime > soniqo-control-crash.txt
# Reproduce the crash, then stop logcat with Ctrl+C.
```

Immediately after a crash, Android's dedicated crash buffer is a shorter
alternative:

```bash
adb logcat -b crash -d -v threadtime > soniqo-control-crash.txt
```

Review or redact logs before sharing them; system logs can contain unrelated
device information.

## Validate

```bash
./gradlew :control-demo:testDebugUnitTest
./gradlew :control-demo:lintDebug
./gradlew :control-demo:assembleDebug
```

The adapter instrumentation suite reuses the base and adapter downloaded by
the demo. Push only the compact held-out JSONL to `files/compact-test.jsonl`;
see `LiteRtLmLoraTest` for details. It reports route accuracy, argument
accuracy, engine load, mean, p50, p95, and maximum generation latency.

For representative latency, benchmark a release build on the target phone.
Emulator CPU timings are useful for functional checks but do not represent a
Snapdragon device or its accelerators.

### Apple Silicon emulator workaround

If an arm64 emulator crashes in `liblitertlm_jni.so` on `RDSVL`, add the
following line to that AVD's `config.ini` and cold-boot it:

```ini
kernel.parameters=arm64.nosme arm64.nosve
```

Some Apple Silicon/Android emulator combinations advertise SME/SVE to the
guest even when those instructions are not available through virtualization.
The kernel flags keep LiteRT-LM's XNNPack backend on supported NEON kernels.
