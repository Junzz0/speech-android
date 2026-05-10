#include "kokoro_tts.h"
#include "onnx_engine.h"
#include <cstring>
#include <cstdlib>
#include <fstream>

static constexpr int MAX_PHONEMES = 128;

KokoroTts::KokoroTts(
    const std::string& model_path,
    const std::string& voices_dir,
    const std::string& data_dir,
    bool nnapi)
    : voices_dir_(voices_dir)
{
    auto& engine = OnnxEngine::get();
    api_ = engine.api();
    session_ = engine.load(model_path, nnapi);

    // Load phonemizer vocabulary and dictionaries
    phonemizer_.load_vocab(data_dir + "/vocab_index.json");
    phonemizer_.load_dictionaries(data_dir);

    // Load optional non-English pronunciation dictionaries
    for (const char* lang : {"fr", "es", "it", "pt", "hi"}) {
        phonemizer_.load_language_dict(lang,
            data_dir + "/dict_" + lang + ".json");
    }

    // Load default voice
    set_voice("af_heart");
}

KokoroTts::~KokoroTts() {
    if (session_) api_->ReleaseSession(session_);
}

void KokoroTts::set_voice(const std::string& name) {
    voice_embedding_ = load_voice_embedding(name);
}

std::vector<float> KokoroTts::load_voice_embedding(const std::string& name) {
    std::string path = voices_dir_ + "/" + name + ".bin";
    std::ifstream file(path, std::ios::binary);
    if (!file.is_open()) {
        LOGE("Voice file not found: %s", path.c_str());
        return std::vector<float>(256, 0.0f);
    }

    std::vector<float> embedding(256);
    file.read(reinterpret_cast<char*>(embedding.data()), 256 * sizeof(float));
    return embedding;
}

void KokoroTts::auto_switch_voice(const std::string& lang) {
    if (lang == current_lang_) return;
    current_lang_ = lang;

    // Map language to default voice
    struct LangVoice { const char* lang; const char* voice; };
    static const LangVoice map[] = {
        {"en", "af_heart"},
        {"fr", "ff_siwis"},
        {"es", "ef_dora"},
        {"it", "if_sara"},
        {"pt", "pf_dora"},
        {"hi", "hf_alpha"},
        {"ja", "jf_alpha"},
        {"zh", "zf_xiaobei"},
        {"ko", "kf_somi"},
    };

    for (auto& entry : map) {
        if (lang == entry.lang) {
            auto emb = load_voice_embedding(entry.voice);
            if (emb[0] != 0.0f || emb[1] != 0.0f) {  // check not zeroed (missing file)
                voice_embedding_ = std::move(emb);
                LOGI("TTS: auto-switched voice to %s for language %s", entry.voice, entry.lang);
            }
            return;
        }
    }
    // Unknown language — keep current voice
}

void KokoroTts::synthesize(
    const char* text, const char* language,
    ChunkCallback on_chunk, void* ctx)
{
    cancelled_ = false;

    // Set language and auto-switch voice if language changed
    std::string lang = (language && language[0]) ? language : "en";
    phonemizer_.set_language(lang);
    auto_switch_voice(lang);
    auto* mem = OnnxEngine::get().cpu_memory();

    // Text → phoneme token IDs
    auto raw_tokens = phonemizer_.tokenize(text, MAX_PHONEMES);
    if (raw_tokens.empty() || cancelled_) return;

    size_t token_count = raw_tokens.size();

    LOGI("TTS: text='%.60s' tokens=%zu", text, token_count);

    // Pad to fixed MAX_PHONEMES with attention mask
    std::vector<int64_t> input_ids(MAX_PHONEMES, 0);
    std::vector<int64_t> attention_mask(MAX_PHONEMES, 0);
    for (size_t i = 0; i < token_count && i < MAX_PHONEMES; i++) {
        input_ids[i] = raw_tokens[i];
        attention_mask[i] = 1;
    }

    // --- input tensors ---

    const int64_t ids_shape[] = {1, MAX_PHONEMES};

    // input_ids [1, 128]
    OrtValue* t_ids = nullptr;
    ort_check(api_, api_->CreateTensorWithDataAsOrtValue(
        mem, input_ids.data(), input_ids.size() * sizeof(int64_t),
        ids_shape, 2, ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64, &t_ids));

    // attention_mask [1, 128]
    OrtValue* t_mask = nullptr;
    ort_check(api_, api_->CreateTensorWithDataAsOrtValue(
        mem, attention_mask.data(), attention_mask.size() * sizeof(int64_t),
        ids_shape, 2, ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64, &t_mask));

    // ref_s / voice embedding [1, 256]
    const int64_t style_shape[] = {1, 256};
    OrtValue* t_style = nullptr;
    ort_check(api_, api_->CreateTensorWithDataAsOrtValue(
        mem, voice_embedding_.data(), voice_embedding_.size() * sizeof(float),
        style_shape, 2, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &t_style));

    // speed [1]
    float speed = 0.85f;
    const int64_t speed_shape[] = {1};
    OrtValue* t_speed = nullptr;
    ort_check(api_, api_->CreateTensorWithDataAsOrtValue(
        mem, &speed, sizeof(float),
        speed_shape, 1, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &t_speed));

    // random_phases [1, 9]
    float phases[9];
    for (int i = 0; i < 9; i++)
        phases[i] = static_cast<float>(rand()) / static_cast<float>(RAND_MAX);
    const int64_t phases_shape[] = {1, 9};
    OrtValue* t_phases = nullptr;
    ort_check(api_, api_->CreateTensorWithDataAsOrtValue(
        mem, phases, sizeof(phases),
        phases_shape, 2, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &t_phases));

    // --- run ---

    const char* in_names[]  = {"input_ids", "attention_mask", "ref_s", "speed", "random_phases"};
    const char* out_names[] = {"audio", "audio_length_samples", "pred_dur"};
    OrtValue* inputs[]  = {t_ids, t_mask, t_style, t_speed, t_phases};
    OrtValue* outputs[] = {nullptr, nullptr, nullptr};

    ort_check(api_, api_->Run(
        session_, nullptr,
        in_names, inputs, 5,
        out_names, 3, outputs));

    if (!cancelled_) {
        float* audio = nullptr;
        ort_check(api_, api_->GetTensorMutableData(outputs[0], (void**)&audio));

        // Get valid sample count from model
        int64_t* len_ptr = nullptr;
        ort_check(api_, api_->GetTensorMutableData(outputs[1], (void**)&len_ptr));
        size_t valid_samples = static_cast<size_t>(len_ptr[0]);

        // Inspect peak before any processing — short prompts (≤5 tokens) can
        // make the E2E ONNX export numerically explode (peak in the hundreds).
        // Treat that as a synthesis failure rather than amplifying garbage.
        float peak = 0.0f;
        for (size_t i = 0; i < valid_samples; i++) {
            float a = std::abs(audio[i]);
            if (a > peak) peak = a;
        }
        if (peak > 2.0f) {
            LOGI("TTS: dropping output, peak=%.2f indicates numerical instability "
                 "(short prompt? text='%.40s')", peak, text);
            // Cleanup outputs and return without emitting audio
            for (int i = 2; i >= 0; i--) api_->ReleaseValue(outputs[i]);
            api_->ReleaseValue(t_phases);
            api_->ReleaseValue(t_speed);
            api_->ReleaseValue(t_style);
            api_->ReleaseValue(t_mask);
            api_->ReleaseValue(t_ids);
            return;
        }

        // Trim trailing artifacts — Kokoro's E2E model often emits 100-300 ms
        // of low-energy noise + occasional loud spike past the real speech.
        // Walk backwards through 50 ms windows; the last window above the
        // silence floor is where speech ended. Sustained-energy threshold
        // (50 ms window) avoids mistaking isolated artifact spikes for
        // speech. Mirrors KokoroTTSModel.synthesize() in speech-swift.
        constexpr int sample_rate = 24000;
        constexpr float silence_rms = 0.030f;
        const size_t win = std::max<size_t>(1, sample_rate / 20);  // 50 ms
        size_t speech_end = valid_samples;
        if (valid_samples > win) {
            for (size_t i = valid_samples - win; i > 0; i -= win / 2) {
                float sum_sq = 0.0f;
                for (size_t j = 0; j < win; j++) {
                    float v = audio[i + j];
                    sum_sq += v * v;
                }
                float rms = std::sqrt(sum_sq / static_cast<float>(win));
                if (rms > silence_rms) {
                    speech_end = i + win;
                    break;
                }
                if (i < win / 2) break;
            }
        }
        if (speech_end < valid_samples) {
            for (size_t k = speech_end; k < valid_samples; k++) audio[k] = 0.0f;
        }
        // ~10 ms linear fade-out at the new tail boundary so the seam is smooth.
        const size_t fade_out = std::min<size_t>(speech_end, sample_rate / 100);
        if (fade_out >= 2) {
            const size_t start = speech_end - fade_out;
            const float denom = static_cast<float>(fade_out - 1);
            for (size_t k = 0; k < fade_out; k++) {
                float gain = static_cast<float>(fade_out - 1 - k) / denom;
                audio[start + k] *= gain;
            }
        }
        // 5 ms fade-in to prevent click at start.
        const size_t fade_in = std::min<size_t>(120, speech_end);
        for (size_t i = 0; i < fade_in; i++) {
            audio[i] *= static_cast<float>(i) / static_cast<float>(fade_in);
        }

        LOGI("TTS: valid=%zu speech_end=%zu peak=%.4f", valid_samples, speech_end, peak);

        on_chunk(audio, speech_end, true, ctx);
    }

    // --- cleanup ---

    for (int i = 2; i >= 0; i--) api_->ReleaseValue(outputs[i]);
    api_->ReleaseValue(t_phases);
    api_->ReleaseValue(t_speed);
    api_->ReleaseValue(t_style);
    api_->ReleaseValue(t_mask);
    api_->ReleaseValue(t_ids);
}

void KokoroTts::cancel() {
    cancelled_ = true;
}
