package audio.soniqo.speech

internal object NativeBridge {

    init {
        System.loadLibrary("speech_android")
    }

    external fun nativeCreate(
        modelDir: String,
        useNnapi: Boolean,
        useInt8: Boolean,
        sttModel: Int,    // SttModel.ordinal: 0=PARAKEET, 1=NEMOTRON_MULTILINGUAL
        sttBackend: Int,  // SttBackend.ordinal: 0=ONNX, 1=LITERT
        language: String, // prompt locale for Nemotron ("auto", "en-US", ...)
        callback: EventCallback,
        emitPartialTranscriptions: Boolean,
        partialTranscriptionInterval: Float,
    ): Long

    external fun nativeNnapiFallbackReason(): String?
    external fun nativeDestroy(handle: Long)
    external fun nativeStart(handle: Long)
    external fun nativeStop(handle: Long)
    external fun nativePushAudio(handle: Long, samples: FloatArray, count: Int)
    external fun nativeResumeListen(handle: Long)
    external fun nativeGetState(handle: Long): Int

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
