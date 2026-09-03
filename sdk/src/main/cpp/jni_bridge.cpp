#include <jni.h>
#include <android/log.h>

#include <speech_core/models/deepfilter.h>
#include <speech_core/models/kokoro_tts.h>
#include <speech_core/models/onnx_engine.h>
#include <speech_core/models/onnx_canary_stt.h>
#include <speech_core/models/onnx_nemotron_streaming_stt.h>
#include <speech_core/models/onnx_pocket_tts.h>
#include <speech_core/models/onnx_smart_turn.h>
#include <speech_core/models/parakeet_stt.h>
#include <speech_core/models/nemotron_multilingual_stt.h>
#include <speech_core/models/silero_vad.h>
#ifdef SPEECH_ANDROID_WITH_LITERT
#include <speech_core/models/litert_nemotron_multilingual_stt.h>
#include <speech_core/models/litert_supertonic_tts.h>
#endif
#include <speech_core/interfaces.h>
#include <speech_core/pipeline/agent_config.h>
#include <speech_core/pipeline/turn_detector.h>
#include <speech_core/pipeline/voice_pipeline.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <mutex>
#include <memory>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

#define LOG_TAG "Speech"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ---------------------------------------------------------------------------
// Pipeline handle
//
// speech_core::* model wrappers directly implement the speech_core interfaces
// (VADInterface / STTInterface / TTSInterface / EnhancerInterface), so the
// JNI bridge constructs them and hands references to VoicePipeline. No
// C-vtable adapters needed — the entire vtable boilerplate that used to live
// here was deleted in this change.
// ---------------------------------------------------------------------------

struct PipelineHandle {
    std::unique_ptr<speech_core::SileroVad> vad;
    std::unique_ptr<speech_core::STTInterface> stt;  // Parakeet-EOU, Parakeet TDT, or Nemotron
    std::unique_ptr<speech_core::TTSInterface> tts;  // Kokoro/Pocket (ONNX) or Supertonic (LiteRT)
    std::unique_ptr<speech_core::DeepFilterEnhancer> enhancer;
    std::unique_ptr<speech_core::OnnxSmartTurn> smart_turn;
    std::unique_ptr<speech_core::VoicePipeline> pipeline;

    // Non-owning typed view of `stt` when the Parakeet-EOU streaming model is
    // loaded. set_context_phrases() (contextual biasing) is model-specific, not
    // on STTInterface, so nativeSetContextPhrases needs the concrete type.
    speech_core::OnnxNemotronStreamingStt* eou_stt = nullptr;

    // Serializes nativePipelineSynthesize calls on the shared TTS instance.
    // In TranscribeOnly mode the pipeline itself never touches TTS, so this
    // only guards against concurrent Kotlin-side synthesize() calls.
    std::mutex tts_mutex;

    JavaVM* jvm = nullptr;
    jobject callback = nullptr;
    jmethodID on_event_mid = nullptr;
};

struct SynthesizerHandle {
    std::unique_ptr<speech_core::TTSInterface> tts;
    std::mutex mutex;
};

// VAD-only handle: Silero + speech_core::TurnDetector, no VoicePipeline and
// therefore no STT/TTS model. The counterpart of SynthesizerHandle at the
// other end of the pipeline — an app that only needs to know when someone is
// talking loads 2 MB instead of the ~500 MB model set.
struct VadHandle {
    std::unique_ptr<speech_core::SileroVad> vad;
    std::unique_ptr<speech_core::TurnDetector> detector;

    // TurnDetector mutates VAD, hysteresis and utterance state on every push
    // and is not thread-safe; VoicePipeline guards its own instance the same
    // way. Turn callbacks run inside push_audio with this held, so the Kotlin
    // callback must not re-enter the detector.
    std::mutex mutex;

    // Copying the utterance out costs a JNI array per turn, so callers that
    // only want the speech boundaries opt out.
    bool emit_audio = false;

    JavaVM* jvm = nullptr;
    jobject callback = nullptr;
    jmethodID on_turn_mid = nullptr;
};

static constexpr int STT_PARAKEET = 0;
static constexpr int STT_NEMOTRON_MULTILINGUAL = 1;
static constexpr int STT_PARAKEET_EOU = 2;
static constexpr int STT_CANARY = 3;
static constexpr int BACKEND_ONNX = 0;
static constexpr int BACKEND_LITERT = 1;
static constexpr int TTS_KOKORO = 0;
static constexpr int TTS_SUPERTONIC = 1;
static constexpr int MODE_ECHO = 0;
static constexpr int MODE_TRANSCRIBE_ONLY = 1;
static constexpr int TTS_KOKORO_SHORT_TURN = 2;
static constexpr int TTS_POCKET = 3;

static std::unique_ptr<speech_core::TTSInterface> create_tts(
    const std::string& dir, bool nnapi, int ttsModel)
{
    if (ttsModel == TTS_SUPERTONIC) {
#ifdef SPEECH_ANDROID_WITH_LITERT
        // Assets from soniqo/Supertonic-3-LiteRT: the four graphs + the G2P-free tokenizer
        // (unicode_indexer.json + tts.json in modelDir) + voice_styles/.
        return std::make_unique<speech_core::LiteRTSupertonicTts>(
            dir + "/duration_predictor.tflite",
            dir + "/text_encoder.tflite",
            dir + "/vector_estimator.tflite",
            dir + "/vocoder.tflite",
            dir,
            dir + "/voice_styles",
            nnapi);
#else
        throw std::runtime_error("Supertonic TTS requires the LiteRT backend (not built into this SDK)");
#endif
    }

    if (ttsModel == TTS_KOKORO_SHORT_TURN) {
        return std::make_unique<speech_core::KokoroTts>(
            dir + "/kokoro-e2e-realtime.onnx",
            dir + "/voices",
            dir,
            nnapi);
    }

    if (ttsModel == TTS_POCKET) {
        // The public recurrent graphs are CPU-oriented. Two ORT threads and
        // four flow steps are the quality/latency profile validated on the
        // Galaxy S23 Ultra. Pocket assets are namespaced because both the STT
        // and TTS bundles publish a file named vocab.json.
        speech_core::PocketTtsConfig config;
        config.intra_threads = 2;
        config.flow_steps = 4;
        config.hardware_acceleration = false;
        return std::make_unique<speech_core::OnnxPocketTts>(
            dir + "/pocket_tts", config);
    }

    return std::make_unique<speech_core::KokoroTts>(
        dir + "/kokoro-e2e.onnx", dir + "/voices", dir, nnapi);
}

// ---------------------------------------------------------------------------
// JNI thread helper
// ---------------------------------------------------------------------------

static JNIEnv* get_env(JavaVM* jvm) {
    JNIEnv* env = nullptr;
    if (jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
        jvm->AttachCurrentThread(&env, nullptr);
    }
    return env;
}

// ---------------------------------------------------------------------------
// Pipeline event → Kotlin onEvent
//
// Kotlin signature unchanged:
//   void onEvent(int type, String text, byte[] audio,
//                float confidence, float sttMs, float ttsMs)
// ---------------------------------------------------------------------------

// Map speech_core::EventType → the int values the Kotlin side expects.
//
// Kotlin's SpeechPipeline.kt switches on raw ints inherited from the original
// C ABI (sc_event_t.type), whose ordering differs from speech_core::EventType:
// the C ABI had ResponseAudioDelta=7 / ResponseDone=8, the enum has them
// swapped. Map explicitly so renumbering speech_core::EventType in the future
// can't silently break the Kotlin event stream.
static jint to_kotlin_event(speech_core::EventType t) {
    using ET = speech_core::EventType;
    switch (t) {
        case ET::SessionCreated:         return 0;
        case ET::SpeechStarted:          return 1;
        case ET::SpeechEnded:            return 2;
        case ET::PartialTranscription:   return 3;
        case ET::TranscriptionCompleted: return 4;
        case ET::ResponseCreated:        return 5;
        case ET::ResponseInterrupted:    return 6;
        case ET::ResponseAudioDelta:     return 7;
        case ET::ResponseDone:           return 8;
        case ET::ToolCallStarted:        return 9;
        case ET::ToolCallCompleted:      return 10;
        case ET::Error:                  return 11;
    }
    return -1;
}

static void dispatch_event(PipelineHandle* h,
                           const speech_core::PipelineEvent& event) {
    LOGI("event type=%d text='%.60s' audio=%zu stt=%.0fms tts=%.0fms",
         static_cast<int>(event.type), event.text.c_str(),
         event.audio_data.size(), event.stt_duration_ms,
         event.tts_duration_ms);

    if (!h->callback) return;

    JNIEnv* env = get_env(h->jvm);
    if (!env) return;

    jstring text = !event.text.empty()
        ? env->NewStringUTF(event.text.c_str()) : nullptr;

    jbyteArray audio = nullptr;
    if (!event.audio_data.empty()) {
        audio = env->NewByteArray(static_cast<jsize>(event.audio_data.size()));
        env->SetByteArrayRegion(audio, 0,
            static_cast<jsize>(event.audio_data.size()),
            reinterpret_cast<const jbyte*>(event.audio_data.data()));
    }

    env->CallVoidMethod(h->callback, h->on_event_mid,
        to_kotlin_event(event.type),
        text, audio,
        event.confidence,
        event.stt_duration_ms,
        event.tts_duration_ms);

    if (audio) env->DeleteLocalRef(audio);
    if (text) env->DeleteLocalRef(text);
}

// ---------------------------------------------------------------------------
// TurnEvent -> Kotlin onTurn
//
//   void onTurn(int type, float timeSec, float[] audio)
//
// Only the two speech-boundary events can reach Kotlin. Interruption and
// InterruptionRecovered need set_agent_speaking(), which nothing calls on a
// standalone detector — there is no agent playback to barge into.
// ---------------------------------------------------------------------------
static void dispatch_turn(VadHandle* h, const speech_core::TurnEvent& event) {
    jint type;
    switch (event.type) {
        case speech_core::TurnEvent::UserSpeechStarted: type = 0; break;
        case speech_core::TurnEvent::UserSpeechEnded:   type = 1; break;
        default: return;
    }

    if (!h->callback) return;

    JNIEnv* env = get_env(h->jvm);
    if (!env) return;

    jfloatArray audio = nullptr;
    if (h->emit_audio && !event.audio.empty()) {
        audio = env->NewFloatArray(static_cast<jsize>(event.audio.size()));
        if (audio) {
            env->SetFloatArrayRegion(audio, 0,
                static_cast<jsize>(event.audio.size()), event.audio.data());
        }
    }

    env->CallVoidMethod(h->callback, h->on_turn_mid, type, event.time, audio);

    if (audio) env->DeleteLocalRef(audio);
}

// ---------------------------------------------------------------------------
// JNI exports
// ---------------------------------------------------------------------------

static std::string jstring_to_string(JNIEnv* env, jstring js) {
    if (!js) return "";
    const char* chars = env->GetStringUTFChars(js, nullptr);
    std::string s(chars);
    env->ReleaseStringUTFChars(js, chars);
    return s;
}

static std::vector<std::string> jstring_array_to_vector(JNIEnv* env, jobjectArray array) {
    std::vector<std::string> out;
    if (!array) return out;
    const jsize n = env->GetArrayLength(array);
    out.reserve(static_cast<size_t>(n));
    for (jsize i = 0; i < n; ++i) {
        auto item = static_cast<jstring>(env->GetObjectArrayElement(array, i));
        std::string value = jstring_to_string(env, item);
        if (!value.empty()) out.push_back(std::move(value));
        if (item) env->DeleteLocalRef(item);
    }
    return out;
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_audio_soniqo_speech_NativeBridge_nativeCreate(
    JNIEnv* env, jobject /*thiz*/,
    jstring modelDir, jboolean useNnapi, jboolean useInt8,
    jint sttModel, jint sttBackend, jint ttsModel, jint pipelineMode,
    jstring language,
    jobjectArray languageHints,
    jobject callback,
    jboolean emitPartialTranscriptions, jfloat partialTranscriptionInterval,
    jfloat endOfSpeechSilenceSec, jint beamSize,
    jboolean enableSmartTurn, jfloat turnCompletionThreshold,
    jfloat turnCompletionMaxSilenceSec)
{
    auto dir = jstring_to_string(env, modelDir);
    bool nnapi = useNnapi;
    std::string suffix = useInt8 ? "-int8" : "";
    std::string lang = jstring_to_string(env, language);
    std::vector<std::string> lang_hints = jstring_array_to_vector(env, languageHints);
    (void)lang_hints;  // reserved for prompt-conditioned backends; Parakeet autodetects

    auto h = std::make_unique<PipelineHandle>();
    env->GetJavaVM(&h->jvm);
    h->callback = env->NewGlobalRef(callback);

    // Cache event method ID
    jclass cls = env->GetObjectClass(callback);
    h->on_event_mid = env->GetMethodID(cls, "onEvent",
        "(ILjava/lang/String;[BFFF)V");

    try {
        // Load models
        h->vad = std::make_unique<speech_core::SileroVad>(
            dir + "/silero-vad.onnx", /*hw_accel=*/false);
        // STT — Parakeet-EOU low-memory streaming, Parakeet TDT v3, or
        // Nemotron-3.5 multilingual (prompt-conditioned) on ONNX/LiteRT.
        if (sttModel == STT_NEMOTRON_MULTILINGUAL) {
            if (sttBackend == BACKEND_LITERT) {
#ifdef SPEECH_ANDROID_WITH_LITERT
                auto m = std::make_unique<speech_core::LiteRTNemotronMultilingualStt>(
                    dir + "/nemotron-multilingual-encoder.tflite",
                    dir + "/nemotron-multilingual-decoder.tflite",
                    dir + "/nemotron-multilingual-joint.tflite",
                    dir + "/vocab.json", dir + "/languages.json", nnapi);
                // The core setter returns false for the special "auto" value
                // after selecting autoSlot; false means an error only for a
                // concrete locale.
                const bool language_found = lang.empty() || m->set_language(lang);
                if (lang != "auto" && !language_found) {
                    throw std::invalid_argument(
                        "Nemotron has no language prompt for '" + lang +
                        "'; use a locale from languages.json "
                        "(Chinese: zh-CN or zh-TW)");
                }
                h->stt = std::move(m);
#else
                throw std::runtime_error("LiteRT STT backend not built into this SDK");
#endif
            } else {
                auto m = std::make_unique<speech_core::NemotronMultilingualStt>(
                    dir + "/encoder.onnx", dir + "/decoder.onnx", dir + "/joint.onnx",
                    dir + "/vocab.json", dir + "/languages.json", nnapi);
                // The core setter returns false for the special "auto" value
                // after selecting autoSlot; false means an error only for a
                // concrete locale.
                const bool language_found = lang.empty() || m->set_language(lang);
                if (lang != "auto" && !language_found) {
                    throw std::invalid_argument(
                        "Nemotron has no language prompt for '" + lang +
                        "'; use a locale from languages.json "
                        "(Chinese: zh-CN or zh-TW)");
                }
                h->stt = std::move(m);
            }
        } else if (sttModel == STT_CANARY) {
            // Canary 180M Flash is offline per utterance: the encoder runs on
            // the whole VAD segment, then tokens decode one at a time. The
            // wrapper reads its prompt, cache shape and end-of-text from the
            // bundle's graph metadata, so nothing is configured here beyond
            // the language. English, German, Spanish and French; an unknown
            // code leaves the bundle's default in place rather than failing
            // the whole pipeline.
            auto m = std::make_unique<speech_core::OnnxCanaryStt>(
                dir + "/canary-encoder-int8.onnx",
                dir + "/canary-decoder-int8.onnx",
                dir + "/vocab.json",
                nnapi);
            if (lang != "auto" && !lang.empty() && !m->set_language(lang)) {
                LOGI("Canary has no prompt token for '%s', keeping the bundle default",
                     lang.c_str());
            }
            h->stt = std::move(m);
        } else if (sttModel == STT_PARAKEET_EOU) {
            // beamSize > 1 enables modified beam search, which contextual
            // biasing (nativeSetContextPhrases) rides on; <= 1 stays greedy.
            // The wrapper self-configures the rest from config.json.
            speech_core::OnnxNemotronStreamingStt::Config eou_cfg;
            eou_cfg.beam_size = beamSize;
            auto m = std::make_unique<speech_core::OnnxNemotronStreamingStt>(
                dir + "/parakeet-eou-encoder.onnx",
                dir + "/parakeet-eou-decoder.onnx",
                dir + "/parakeet-eou-joint.onnx",
                dir + "/vocab.json",
                eou_cfg, nnapi);
            h->eou_stt = m.get();
            h->stt = std::move(m);
        } else {
            // Parakeet TDT autodetects its language — there is no forcing
            // mechanism (the transducer has no decoder prompt, and the
            // published exports emit no language tokens to steer), matching
            // every other Parakeet runtime. `language`/`languageHints`
            // apply to Nemotron's prompt slot and TTS voice selection only.
            auto m = std::make_unique<speech_core::ParakeetStt>(
                dir + "/parakeet-encoder" + suffix + ".onnx",
                dir + "/parakeet-decoder-joint" + suffix + ".onnx",
                dir + "/vocab.json",
                nnapi);
            h->stt = std::move(m);
        }
        // TTS — Kokoro (ONNX, 24 kHz) or Supertonic-3 (LiteRT flow-matching, 44.1 kHz, G2P-free).
        h->tts = create_tts(dir, nnapi, ttsModel);
        if (enableSmartTurn) {
            // Smart Turn runs once per confirmed VAD pause. Keep it on CPU:
            // the mobile build's dynamic-int8 graph is small, while NNAPI
            // partitioning and fallback would add device-specific variance.
            h->smart_turn = std::make_unique<speech_core::OnnxSmartTurn>(
                dir + "/smart-turn-v3.2-int8.onnx",
                /*hardware_acceleration=*/false);
        }

        speech_core::AgentConfig cfg;
        // App-tunable end-of-utterance silence; <=0 falls back to the
        // snappy-command default.
        cfg.vad.min_silence_duration =
            endOfSpeechSilenceSec > 0.0f ? endOfSpeechSilenceSec : 0.5f;
        cfg.vad.min_speech_duration = 0.15f;
        cfg.eager_stt = false;
        cfg.post_playback_guard = 0.15f;
        cfg.emit_partial_transcriptions = emitPartialTranscriptions;
        cfg.partial_transcription_interval = partialTranscriptionInterval;
        cfg.turn_completion_threshold = turnCompletionThreshold;
        cfg.turn_completion_max_silence = turnCompletionMaxSilenceSec;
        cfg.mode = (pipelineMode == MODE_TRANSCRIBE_ONLY)
            ? speech_core::AgentConfig::Mode::TranscribeOnly
            : speech_core::AgentConfig::Mode::Echo;

        // Note: DeepFilterNet3 noise cancellation is disabled in the pipeline.
        // DFN operates at 48 kHz but the pipeline pushes 16 kHz audio —
        // running DFN without resampling produces artifacts. Needs a
        // 16k→48k→DFN→48k→16k resample chain before it can be re-enabled.
        // See issue #12. The model is still downloaded for future use.

        PipelineHandle* raw = h.get();
        h->pipeline = std::make_unique<speech_core::VoicePipeline>(
            *h->stt, *h->tts, /*llm=*/nullptr, *h->vad, cfg,
            [raw](const speech_core::PipelineEvent& e) { dispatch_event(raw, e); });
        if (h->smart_turn) {
            h->pipeline->set_turn_completion(h->smart_turn.get());
        }

        auto& engine = OnnxEngine::get();
        if (engine.had_nnapi_fallback()) {
            LOGI("Pipeline created with NNAPI fallback to CPU: %s",
                 engine.nnapi_fallback_reason().c_str());
        } else {
            LOGI("Pipeline created (NNAPI=%d)", nnapi);
        }
    } catch (const std::exception& e) {
        LOGE("Pipeline creation failed: %s", e.what());
        if (h->callback) env->DeleteGlobalRef(h->callback);
        jclass ex_cls = env->FindClass("java/lang/RuntimeException");
        if (ex_cls) {
            std::string msg = std::string("Native pipeline failed: ") + e.what();
            env->ThrowNew(ex_cls, msg.c_str());
        }
        return 0;
    }

    return reinterpret_cast<jlong>(h.release());
}

JNIEXPORT jstring JNICALL
Java_audio_soniqo_speech_NativeBridge_nativeNnapiFallbackReason(
    JNIEnv* env, jobject /*thiz*/)
{
    auto& engine = OnnxEngine::get();
    if (engine.had_nnapi_fallback()) {
        return env->NewStringUTF(engine.nnapi_fallback_reason().c_str());
    }
    return nullptr;
}

JNIEXPORT void JNICALL
Java_audio_soniqo_speech_NativeBridge_nativeDestroy(
    JNIEnv* env, jobject /*thiz*/, jlong handle)
{
    auto* h = reinterpret_cast<PipelineHandle*>(handle);
    if (h) {
        if (h->callback) env->DeleteGlobalRef(h->callback);
        delete h;
    }
}

JNIEXPORT void JNICALL
Java_audio_soniqo_speech_NativeBridge_nativeStart(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle)
{
    auto* h = reinterpret_cast<PipelineHandle*>(handle);
    if (h && h->pipeline) h->pipeline->start();
}

JNIEXPORT void JNICALL
Java_audio_soniqo_speech_NativeBridge_nativeStop(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle)
{
    auto* h = reinterpret_cast<PipelineHandle*>(handle);
    if (h && h->pipeline) h->pipeline->stop();
}

JNIEXPORT void JNICALL
Java_audio_soniqo_speech_NativeBridge_nativeCancelTurn(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle)
{
    auto* h = reinterpret_cast<PipelineHandle*>(handle);
    if (h && h->pipeline) h->pipeline->cancel_current_turn();
}

JNIEXPORT void JNICALL
Java_audio_soniqo_speech_NativeBridge_nativePushAudio(
    JNIEnv* env, jobject /*thiz*/, jlong handle,
    jfloatArray samples, jint count)
{
    auto* h = reinterpret_cast<PipelineHandle*>(handle);
    if (!h || !h->pipeline) return;

    float* data = env->GetFloatArrayElements(samples, nullptr);
    h->pipeline->push_audio(data, static_cast<size_t>(count));
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_audio_soniqo_speech_NativeBridge_nativeResumeListen(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle)
{
    auto* h = reinterpret_cast<PipelineHandle*>(handle);
    if (h && h->pipeline) h->pipeline->resume_listening();
}

JNIEXPORT jint JNICALL
Java_audio_soniqo_speech_NativeBridge_nativeGetState(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle)
{
    auto* h = reinterpret_cast<PipelineHandle*>(handle);
    if (!h || !h->pipeline) return 0;
    return static_cast<jint>(h->pipeline->state());
}

// Install contextual-biasing phrases on the Parakeet-EOU streaming STT. No-op
// unless EOU is the active model and it was created with beamSize > 1. Call
// between utterances (not mid-decode); rebuild per turn to inject the entities
// currently on the device. An empty array clears biasing.
JNIEXPORT void JNICALL
Java_audio_soniqo_speech_NativeBridge_nativeSetContextPhrases(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jobjectArray phrases, jfloat maxBonus)
{
    auto* h = reinterpret_cast<PipelineHandle*>(handle);
    if (!h || !h->eou_stt) return;
    std::vector<std::string> ph = jstring_array_to_vector(env, phrases);
    h->eou_stt->set_context_phrases(ph, /*per_char=*/1.5f, /*completion=*/3.0f,
                                    /*max_bonus=*/maxBonus);
}

JNIEXPORT jlong JNICALL
Java_audio_soniqo_speech_NativeBridge_nativeCreateSynthesizer(
    JNIEnv* env, jobject /*thiz*/,
    jstring modelDir, jboolean useNnapi, jint ttsModel)
{
    auto dir = jstring_to_string(env, modelDir);
    auto h = std::make_unique<SynthesizerHandle>();

    try {
        h->tts = create_tts(dir, useNnapi, ttsModel);
        auto& engine = OnnxEngine::get();
        if (engine.had_nnapi_fallback()) {
            LOGI("Synthesizer created with NNAPI fallback to CPU: %s",
                 engine.nnapi_fallback_reason().c_str());
        } else {
            LOGI("Synthesizer created (NNAPI=%d)", static_cast<int>(useNnapi));
        }
    } catch (const std::exception& e) {
        LOGE("Synthesizer creation failed: %s", e.what());
        jclass ex_cls = env->FindClass("java/lang/RuntimeException");
        if (ex_cls) {
            std::string msg = std::string("Native synthesizer failed: ") + e.what();
            env->ThrowNew(ex_cls, msg.c_str());
        }
        return 0;
    }

    return reinterpret_cast<jlong>(h.release());
}

JNIEXPORT void JNICALL
Java_audio_soniqo_speech_NativeBridge_nativeDestroySynthesizer(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle)
{
    auto* h = reinterpret_cast<SynthesizerHandle*>(handle);
    delete h;
}

JNIEXPORT void JNICALL
Java_audio_soniqo_speech_NativeBridge_nativeStopSynthesizer(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle)
{
    auto* h = reinterpret_cast<SynthesizerHandle*>(handle);
    if (h && h->tts) h->tts->cancel();
}

JNIEXPORT jint JNICALL
Java_audio_soniqo_speech_NativeBridge_nativeSynthesizerSampleRate(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle)
{
    auto* h = reinterpret_cast<SynthesizerHandle*>(handle);
    if (!h || !h->tts) return 0;
    return static_cast<jint>(h->tts->output_sample_rate());
}

// Applies a per-call voice preset for one synthesis and restores the engine
// default afterwards — also when synthesis throws — so the preset never leaks
// into later calls or into the pipeline's own ECHO responses. Construct only
// while holding the TTS mutex; set_voice() is not synchronized on its own.
// An unknown id throws std::invalid_argument from the constructor, before any
// state changes.
class ScopedVoice {
public:
    ScopedVoice(speech_core::TTSInterface& tts, const std::string& voice_id)
        : tts_(tts), active_(!voice_id.empty())
    {
        if (active_) tts_.set_voice(voice_id);
    }
    ~ScopedVoice()
    {
        if (!active_) return;
        try {
            tts_.set_voice("");
        } catch (const std::exception& e) {
            LOGE("Failed to restore the default TTS voice: %s", e.what());
        }
    }
    ScopedVoice(const ScopedVoice&) = delete;
    ScopedVoice& operator=(const ScopedVoice&) = delete;

private:
    speech_core::TTSInterface& tts_;
    bool active_;
};

// Synthesize with [tts] under [mutex], returning PCM16 mono little-endian
// bytes. [voice] selects a preset for this call only ("" or null keeps the
// engine default). Throws a Java RuntimeException and returns nullptr on
// failure.
static jbyteArray synthesize_pcm16(JNIEnv* env, speech_core::TTSInterface& tts,
                                   std::mutex& mutex, jstring text, jstring language,
                                   jstring voice)
{
    std::string input = jstring_to_string(env, text);
    std::string lang = jstring_to_string(env, language);
    std::string voice_id = jstring_to_string(env, voice);
    std::vector<int16_t> pcm;

    try {
        std::lock_guard<std::mutex> lock(mutex);
        ScopedVoice scoped_voice(tts, voice_id);
        tts.synthesize(input, lang, [&pcm](const float* samples, size_t length, bool /*is_final*/) {
            pcm.reserve(pcm.size() + length);
            for (size_t i = 0; i < length; ++i) {
                const float clamped = std::max(-1.0f, std::min(1.0f, samples[i]));
                pcm.push_back(static_cast<int16_t>(std::lrintf(clamped * 32767.0f)));
            }
        });
    } catch (const std::exception& e) {
        LOGE("Synthesis failed: %s", e.what());
        jclass ex_cls = env->FindClass("java/lang/RuntimeException");
        if (ex_cls) {
            std::string msg = std::string("Native synthesis failed: ") + e.what();
            env->ThrowNew(ex_cls, msg.c_str());
        }
        return nullptr;
    }

    const jsize byte_count = static_cast<jsize>(pcm.size() * sizeof(int16_t));
    jbyteArray out = env->NewByteArray(byte_count);
    if (byte_count > 0) {
        env->SetByteArrayRegion(
            out, 0, byte_count,
            reinterpret_cast<const jbyte*>(pcm.data()));
    }
    return out;
}

// Synthesize synchronously while forwarding each safe native model chunk to
// Kotlin. speech-core invokes the callback before starting the next chunk, so
// callers can begin playback while the following inference is still running.
static void synthesize_streaming_pcm16(
    JNIEnv* env, speech_core::TTSInterface& tts, std::mutex& mutex,
    jstring text, jstring language, jstring voice, jobject callback)
{
    if (!callback) {
        jclass ex_cls = env->FindClass("java/lang/IllegalArgumentException");
        if (ex_cls) env->ThrowNew(ex_cls, "Synthesis callback must not be null");
        return;
    }

    jclass callback_cls = env->GetObjectClass(callback);
    if (!callback_cls) return;
    jmethodID on_chunk = env->GetMethodID(callback_cls, "onChunk", "([BZ)V");
    if (!on_chunk) {
        env->DeleteLocalRef(callback_cls);
        return;
    }

    std::string input = jstring_to_string(env, text);
    std::string lang = jstring_to_string(env, language);
    std::string voice_id = jstring_to_string(env, voice);
    bool callback_failed = false;
    try {
        std::lock_guard<std::mutex> lock(mutex);
        ScopedVoice scoped_voice(tts, voice_id);
        tts.synthesize(
            input, lang,
            [&](const float* samples, size_t length, bool is_final) {
                if (callback_failed) return;
                std::vector<int16_t> pcm(length);
                for (size_t i = 0; i < length; ++i) {
                    const float clamped = std::max(-1.0f, std::min(1.0f, samples[i]));
                    pcm[i] = static_cast<int16_t>(std::lrintf(clamped * 32767.0f));
                }

                const jsize byte_count =
                    static_cast<jsize>(pcm.size() * sizeof(int16_t));
                jbyteArray audio = env->NewByteArray(byte_count);
                if (!audio) {
                    callback_failed = true;
                    tts.cancel();
                    return;
                }
                if (byte_count > 0) {
                    env->SetByteArrayRegion(
                        audio, 0, byte_count,
                        reinterpret_cast<const jbyte*>(pcm.data()));
                }
                if (env->ExceptionCheck()) {
                    env->DeleteLocalRef(audio);
                    callback_failed = true;
                    tts.cancel();
                    return;
                }
                env->CallVoidMethod(
                    callback, on_chunk, audio,
                    is_final ? JNI_TRUE : JNI_FALSE);
                env->DeleteLocalRef(audio);
                if (env->ExceptionCheck()) {
                    callback_failed = true;
                    tts.cancel();
                }
            });
    } catch (const std::exception& e) {
        LOGE("Streaming synthesis failed: %s", e.what());
        if (!env->ExceptionCheck()) {
            jclass ex_cls = env->FindClass("java/lang/RuntimeException");
            if (ex_cls) {
                std::string msg = std::string("Native streaming synthesis failed: ") + e.what();
                env->ThrowNew(ex_cls, msg.c_str());
            }
        }
    }
    env->DeleteLocalRef(callback_cls);
}

JNIEXPORT jbyteArray JNICALL
Java_audio_soniqo_speech_NativeBridge_nativeSynthesize(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jstring text, jstring language,
    jstring voice)
{
    auto* h = reinterpret_cast<SynthesizerHandle*>(handle);
    if (!h || !h->tts) {
        jclass ex_cls = env->FindClass("java/lang/IllegalStateException");
        if (ex_cls) env->ThrowNew(ex_cls, "Native synthesizer is closed");
        return nullptr;
    }
    return synthesize_pcm16(env, *h->tts, h->mutex, text, language, voice);
}

JNIEXPORT jint JNICALL
Java_audio_soniqo_speech_NativeBridge_nativePipelineTtsSampleRate(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle)
{
    auto* h = reinterpret_cast<PipelineHandle*>(handle);
    if (!h || !h->tts) return 0;
    return static_cast<jint>(h->tts->output_sample_rate());
}

JNIEXPORT jbyteArray JNICALL
Java_audio_soniqo_speech_NativeBridge_nativePipelineSynthesize(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jstring text, jstring language,
    jstring voice)
{
    auto* h = reinterpret_cast<PipelineHandle*>(handle);
    if (!h || !h->tts) {
        jclass ex_cls = env->FindClass("java/lang/IllegalStateException");
        if (ex_cls) env->ThrowNew(ex_cls, "Native pipeline is closed");
        return nullptr;
    }
    return synthesize_pcm16(env, *h->tts, h->tts_mutex, text, language, voice);
}

JNIEXPORT void JNICALL
Java_audio_soniqo_speech_NativeBridge_nativePipelineSynthesizeStreaming(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jstring text, jstring language,
    jstring voice, jobject callback)
{
    auto* h = reinterpret_cast<PipelineHandle*>(handle);
    if (!h || !h->tts) {
        jclass ex_cls = env->FindClass("java/lang/IllegalStateException");
        if (ex_cls) env->ThrowNew(ex_cls, "Native pipeline is closed");
        return;
    }
    synthesize_streaming_pcm16(
        env, *h->tts, h->tts_mutex, text, language, voice, callback);
}

JNIEXPORT void JNICALL
Java_audio_soniqo_speech_NativeBridge_nativePipelineCancelSynthesis(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle)
{
    auto* h = reinterpret_cast<PipelineHandle*>(handle);
    if (h && h->tts) h->tts->cancel();
}

// ---------------------------------------------------------------------------
// VAD-only detector
// ---------------------------------------------------------------------------

JNIEXPORT jlong JNICALL
Java_audio_soniqo_speech_NativeBridge_nativeCreateVad(
    JNIEnv* env, jobject /*thiz*/,
    jstring modelDir,
    jfloat onsetThreshold, jfloat offsetThreshold,
    jfloat minSpeechDurationSec, jfloat endOfSpeechSilenceSec,
    jfloat preSpeechBufferSec, jfloat maxUtteranceDurationSec,
    jboolean emitUtteranceAudio, jobject callback)
{
    auto dir = jstring_to_string(env, modelDir);

    auto h = std::make_unique<VadHandle>();
    env->GetJavaVM(&h->jvm);
    h->emit_audio = emitUtteranceAudio;
    h->callback = env->NewGlobalRef(callback);

    jclass cls = env->GetObjectClass(callback);
    h->on_turn_mid = env->GetMethodID(cls, "onTurn", "(IF[F)V");

    try {
        // hw_accel=false to match the pipeline: Silero is 2 MB and runs a
        // 32 ms chunk in under a millisecond on CPU, so a hardware provider
        // only adds a conversion path that can fail.
        h->vad = std::make_unique<speech_core::SileroVad>(
            dir + "/silero-vad.onnx", /*hw_accel=*/false);

        speech_core::AgentConfig cfg;
        cfg.vad.onset = onsetThreshold;
        cfg.vad.offset = offsetThreshold;
        cfg.vad.min_speech_duration = minSpeechDurationSec;
        cfg.vad.min_silence_duration = endOfSpeechSilenceSec;
        cfg.vad.pre_speech_buffer_duration = preSpeechBufferSec;
        cfg.max_utterance_duration = maxUtteranceDurationSec;
        // No STT to run early and no agent playback to interrupt: both would
        // only split turns the caller never asked to have split.
        cfg.eager_stt = false;
        cfg.allow_interruptions = false;

        VadHandle* raw = h.get();
        h->detector = std::make_unique<speech_core::TurnDetector>(
            *h->vad, cfg,
            [raw](const speech_core::TurnEvent& e) { dispatch_turn(raw, e); });

        LOGI("VAD detector created");
    } catch (const std::exception& e) {
        LOGE("VAD detector creation failed: %s", e.what());
        if (h->callback) env->DeleteGlobalRef(h->callback);
        jclass ex_cls = env->FindClass("java/lang/RuntimeException");
        if (ex_cls) {
            std::string msg = std::string("Native VAD detector failed: ") + e.what();
            env->ThrowNew(ex_cls, msg.c_str());
        }
        return 0;
    }

    return reinterpret_cast<jlong>(h.release());
}

JNIEXPORT void JNICALL
Java_audio_soniqo_speech_NativeBridge_nativeDestroyVad(
    JNIEnv* env, jobject /*thiz*/, jlong handle)
{
    auto* h = reinterpret_cast<VadHandle*>(handle);
    if (h) {
        if (h->callback) env->DeleteGlobalRef(h->callback);
        delete h;
    }
}

JNIEXPORT void JNICALL
Java_audio_soniqo_speech_NativeBridge_nativePushVadAudio(
    JNIEnv* env, jobject /*thiz*/, jlong handle,
    jfloatArray samples, jint count)
{
    auto* h = reinterpret_cast<VadHandle*>(handle);
    if (!h || !h->detector) return;

    float* data = env->GetFloatArrayElements(samples, nullptr);
    {
        std::lock_guard<std::mutex> lock(h->mutex);
        h->detector->push_audio(data, static_cast<size_t>(count));
    }
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_audio_soniqo_speech_NativeBridge_nativeFlushVad(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle)
{
    auto* h = reinterpret_cast<VadHandle*>(handle);
    if (!h || !h->detector) return;
    std::lock_guard<std::mutex> lock(h->mutex);
    h->detector->flush();
}

JNIEXPORT void JNICALL
Java_audio_soniqo_speech_NativeBridge_nativeResetVad(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle)
{
    auto* h = reinterpret_cast<VadHandle*>(handle);
    if (!h || !h->detector) return;
    std::lock_guard<std::mutex> lock(h->mutex);
    // Independent audio session: also drops the pre-speech ring and the
    // model's recurrent state so the next stream starts clean.
    h->detector->reset_for_new_stream();
}

JNIEXPORT jboolean JNICALL
Java_audio_soniqo_speech_NativeBridge_nativeVadInSpeech(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle)
{
    auto* h = reinterpret_cast<VadHandle*>(handle);
    if (!h || !h->detector) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(h->mutex);
    return h->detector->in_speech() ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
