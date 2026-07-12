package audio.soniqo.speech

internal object NativeBridge {

    init {
        System.loadLibrary("speech_android")
    }

    external fun nativeCreate(
        modelDir: String,
        useNnapi: Boolean,
        useInt8: Boolean,
        sttModel: Int,    // SttModel.ordinal: 0=PARAKEET, 1=NEMOTRON_MULTILINGUAL, 2=PARAKEET_EOU
        sttBackend: Int,  // SttBackend.ordinal: 0=ONNX, 1=LITERT
        ttsModel: Int,    // 0=KOKORO, 1=SUPERTONIC, 2=KOKORO_SHORT_TURN
        pipelineMode: Int, // PipelineMode.ordinal: 0=ECHO, 1=TRANSCRIBE_ONLY
        language: String, // single language hint ("auto", "en-US", ...) — Nemotron prompt + TTS voice
        languageHints: Array<String>, // reserved; no current backend consumes hints
        callback: EventCallback,
        emitPartialTranscriptions: Boolean,
        partialTranscriptionInterval: Float,
        endOfSpeechSilenceSec: Float,  // seconds of silence ending an utterance
    ): Long

    external fun nativeNnapiFallbackReason(): String?
    external fun nativeDestroy(handle: Long)
    external fun nativeStart(handle: Long)
    external fun nativeStop(handle: Long)
    external fun nativePushAudio(handle: Long, samples: FloatArray, count: Int)
    external fun nativeResumeListen(handle: Long)
    external fun nativeGetState(handle: Long): Int

    // Direct synthesis with the pipeline's already-loaded TTS model — lets a
    // TRANSCRIBE_ONLY agent loop speak responses without a second TTS copy.
    external fun nativePipelineTtsSampleRate(handle: Long): Int
    external fun nativePipelineSynthesize(handle: Long, text: String, language: String): ByteArray
    external fun nativePipelineCancelSynthesis(handle: Long)

    external fun nativeCreateSynthesizer(
        modelDir: String,
        useNnapi: Boolean,
        ttsModel: Int,    // 0=KOKORO, 1=SUPERTONIC, 2=KOKORO_SHORT_TURN
    ): Long
    external fun nativeDestroySynthesizer(handle: Long)
    external fun nativeStopSynthesizer(handle: Long)
    external fun nativeSynthesizerSampleRate(handle: Long): Int
    external fun nativeSynthesize(handle: Long, text: String, language: String): ByteArray

    /** Called from native code on the pipeline worker thread. */
    interface EventCallback {
        fun onEvent(
            type: Int,
            text: String?,
            audio: ByteArray?,
            confidence: Float,
            sttMs: Float,
            ttsMs: Float,
        )
    }
}
