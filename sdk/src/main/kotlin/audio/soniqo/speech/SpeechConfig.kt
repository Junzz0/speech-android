package audio.soniqo.speech

enum class ModelPrecision { FP32, INT8 }

/** On-device STT model. PARAKEET auto-detects language; NEMOTRON_MULTILINGUAL
 *  is prompt-conditioned and uses [SpeechConfig.language]. */
enum class SttModel { PARAKEET, NEMOTRON_MULTILINGUAL }

/** Native inference backend for the STT model. Only Nemotron multilingual
 *  ships both; Parakeet is ONNX-only. */
enum class SttBackend { ONNX, LITERT }

data class SpeechConfig(
    /** Path to directory containing ONNX model files. */
    val modelDir: String = "",

    /** Enable NNAPI acceleration (Qualcomm Hexagon NPU / Samsung NPU). */
    val useNnapi: Boolean = true,

    /** Which STT model to load. */
    val sttModel: SttModel = SttModel.PARAKEET,

    /** STT inference backend (Nemotron multilingual supports both). */
    val sttBackend: SttBackend = SttBackend.ONNX,

    /** Language/locale prompt for prompt-conditioned models (Nemotron):
     *  a key from languages.json, e.g. "en-US", "fr", "ja-JP". "auto" lets
     *  the model decide. Ignored by auto-detecting models (Parakeet). */
    val language: String = "auto",

    /** Enable noise cancellation (DeepFilterNet3). */
    val enableEnhancer: Boolean = true,

    /** Model quantization — INT8 recommended for mobile. */
    val precision: ModelPrecision = ModelPrecision.INT8,

    /** Emit partial transcription events during speech (words appear as you speak). */
    val emitPartialTranscriptions: Boolean = false,

    /** Interval between partial transcriptions in seconds. */
    val partialTranscriptionInterval: Float = 0.5f,
)
