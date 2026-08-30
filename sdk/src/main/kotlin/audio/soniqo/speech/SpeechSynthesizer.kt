package audio.soniqo.speech

/**
 * Configuration for direct text-to-speech synthesis.
 *
 * This is the TTS-only counterpart to [SpeechConfig]. It loads only a
 * [TtsModel] and does not create the VAD/STT voice pipeline.
 */
data class SpeechSynthesizerConfig(
    /** Path to directory containing TTS model files. */
    val modelDir: String = "",

    /** Try the deprecated NNAPI execution provider. The CPU path is default. */
    val useNnapi: Boolean = false,

    /** Which TTS model/profile to load. SUPERTONIC requires LiteRT. */
    val ttsModel: TtsModel = TtsModel.KOKORO_SHORT_TURN,
)

data class SpeechSynthesisResult(
    /** PCM sample rate in Hz. */
    val sampleRate: Int,

    /** Signed little-endian PCM16 mono audio. */
    val pcm16: ByteArray,
)

/**
 * Direct on-device text-to-speech synthesizer.
 *
 * Construct via `SpeechSynthesizer(config)` after downloading TTS assets with
 * [ModelManager.ensureTtsModels]. Tests can provide their own implementation
 * to avoid loading the native library.
 */
interface SpeechSynthesizer : AutoCloseable {
    val sampleRate: Int

    /** Synthesize [text] as PCM16 mono audio. */
    fun synthesize(text: String, language: String = "en"): SpeechSynthesisResult

    /**
     * Like [synthesize], but renders this one call with the TTS voice preset
     * [voice] (Supertonic `F1`…`F5` / `M1`…`M5`, Kokoro `af_heart`,
     * `ff_siwis`, …). The preset applies to this call only; an empty [voice]
     * keeps the engine default, an unknown id throws [RuntimeException], and
     * fixed-voice models (Pocket) ignore it. The default implementation
     * ignores [voice] so test doubles keep compiling.
     */
    fun synthesize(text: String, language: String, voice: String): SpeechSynthesisResult =
        synthesize(text, language)

    /** Cancel any in-progress synthesis. */
    fun stop()

    companion object {
        operator fun invoke(config: SpeechSynthesizerConfig): SpeechSynthesizer =
            SpeechSynthesizerImpl(config)
    }
}

internal class SpeechSynthesizerImpl(config: SpeechSynthesizerConfig) : SpeechSynthesizer {
    private var handle: Long = NativeBridge.nativeCreateSynthesizer(
        config.modelDir,
        config.useNnapi,
        config.ttsModel.nativeId,
    ).also { h ->
        if (h == 0L) throw IllegalStateException(
            "Failed to create native synthesizer. Models may be corrupt - " +
                "try clearing app data and reinstalling."
        )
    }

    override val sampleRate: Int
        get() = NativeBridge.nativeSynthesizerSampleRate(handle)

    override fun synthesize(text: String, language: String): SpeechSynthesisResult =
        synthesize(text, language, voice = "")

    override fun synthesize(text: String, language: String, voice: String): SpeechSynthesisResult {
        check(handle != 0L) { "SpeechSynthesizer is closed" }
        return SpeechSynthesisResult(
            sampleRate = sampleRate,
            pcm16 = NativeBridge.nativeSynthesize(handle, text, language, voice),
        )
    }

    override fun stop() {
        if (handle != 0L) NativeBridge.nativeStopSynthesizer(handle)
    }

    override fun close() {
        if (handle != 0L) {
            NativeBridge.nativeDestroySynthesizer(handle)
            handle = 0
        }
    }
}
