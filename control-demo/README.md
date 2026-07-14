# Full-pipeline control demo

This separate application runs a complete on-device voice agent:

`Silero VAD → Parakeet-EOU STT → FunctionGemma 270M → Android action → Kokoro TTS`

It links directly to this checkout's `:sdk` module, so SDK and Kokoro changes
are exercised without publishing an intermediate artifact. On first launch it
downloads the public speech models plus two separate FunctionGemma artifacts:
the reusable 327.4 MB Android LoRA base and the 9.5 MB Control adapter. It then
operates without a network connection.

The Control-specific FunctionGemma datasets, LoRA runner, evaluator, and
experiment notes live in
[`speech-models/models/functiongemma/training`](https://github.com/soniqo/speech-models/tree/main/models/functiongemma/training).
The app always pairs the adapter with the compact prompt serialization used for
training. The exact base and adapter files are published under
[`soniqo/FunctionGemma-270M-LiteRT-LM`](https://huggingface.co/soniqo/FunctionGemma-270M-LiteRT-LM).

## Run

Use an arm64 Android device with at least 4 GB RAM:

```bash
./setup.sh
./gradlew :control-demo:installDebug
```

Grant microphone access in the app, tap the orb, and try commands such as
"set volume to three" or "what can you do?" Contact and media commands also
need their corresponding Android permissions.

The footer shows STT, LLM, Kokoro first-audio, round-trip, and memory metrics.
The same data is available for device benchmarks with:

```bash
adb logcat -s SpeechControl | grep 'TURN'
```

## Validate

```bash
./gradlew :control-demo:testDebugUnitTest
./gradlew :control-demo:lintDebug
./gradlew :control-demo:assembleDebug
```

The adapter instrumentation suite expects the published base and adapter plus
the compact held-out JSONL under the test app's internal `files/` directory;
see `LiteRtLmLoraTest` for the exact filenames. It reports route accuracy,
argument accuracy, engine load, mean, p50, p95, and maximum generation latency.

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
