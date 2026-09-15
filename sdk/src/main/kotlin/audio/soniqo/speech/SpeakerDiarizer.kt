package audio.soniqo.speech

/** Configuration for [SpeakerDiarizer]. */
data class DiarizerConfig(
    /** Directory returned by [ModelManager.ensureDiarizerModels]. */
    val modelDir: String,

    /** Try NNAPI. CPU is the default. */
    val hardwareAcceleration: Boolean = false,
)

/**
 * Streaming speaker diarization with NVIDIA's Sortformer 4-speaker model
 * (streaming v2.1, `default` export): who spoke when, as per-frame
 * probabilities, and nothing else.
 *
 * Feed one instance the whole recording in arrival order. Its arrival-order
 * speaker cache is what keeps a column the same voice from minute one to
 * minute forty, so a new instance — or [reset] — restarts the numbering. The
 * export decodes 340 frames (27.2 s) per call and needs 40 frames (3.2 s) of
 * audio after them, so probabilities arrive about 30 s behind the audio;
 * [endStream] finalises the tail when the recording ends.
 *
 * Only probabilities come back. What counts as speech, how frames become
 * turns, and which column is which person are the caller's decisions: a
 * column is a recording-local activity track, never an identity.
 *
 * Calls are serialized; [close] releases the model.
 *
 * ```
 * val diarizer = SpeakerDiarizer(
 *     DiarizerConfig(modelDir = ModelManager.ensureDiarizerModels(context))
 * )
 * val frames = diarizer.pushAudio(samples)   // [frames x speakers], often empty
 * val tail = diarizer.endStream()            // when the recording ends
 * diarizer.close()
 * ```
 */
interface SpeakerDiarizer : AutoCloseable {

    /** Probability columns per frame: 4 for the published model. */
    val speakers: Int

    /** Audio covered by one frame, in seconds (0.08). Frame `i` of the
     *  recording covers `[i * frameSeconds, (i + 1) * frameSeconds)`. */
    val frameSeconds: Float

    /** Frames finalised since creation or [reset]: the timeline's length. */
    val framesEmitted: Long

    /**
     * Feed [count] samples of 16 kHz mono float32 audio. Returns the frames
     * this call finalised as `[frames x speakers]` probabilities in
     * chronological order, row by row — usually empty, then 340 frames at
     * once. Append them. After [endStream], audio is ignored until [reset].
     */
    fun pushAudio(samples: FloatArray, count: Int = samples.size): FloatArray

    /** Pad the remaining audio to a whole window and return the frames that
     *  padding finalised. The stream is closed afterwards. */
    fun endStream(): FloatArray

    /** Forget the recording. Column numbering restarts, so a column after
     *  this bears no relation to one before it. */
    fun reset()

    companion object {
        /**
         * Loads the model. Throws [RuntimeException] naming the cause when it
         * cannot be loaded, including when the graph and its `config.json`
         * describe different exports.
         */
        operator fun invoke(config: DiarizerConfig): SpeakerDiarizer {
            config.requireValidConfiguration()
            return SpeakerDiarizerImpl(config)
        }
    }
}

internal fun DiarizerConfig.requireValidConfiguration() {
    require(modelDir.isNotBlank()) {
        "DiarizerConfig.modelDir must be the directory returned by " +
            "ModelManager.ensureDiarizerModels()"
    }
}

internal class SpeakerDiarizerImpl(config: DiarizerConfig) : SpeakerDiarizer {

    private var handle: Long = NativeBridge.nativeCreateDiarizer(
        config.modelDir,
        config.hardwareAcceleration,
    ).also { h ->
        if (h == 0L) throw IllegalStateException("Failed to create the native speaker diarizer")
    }

    // Read from the graph once; neither changes for the life of the model.
    override val speakers: Int = NativeBridge.nativeDiarizerSpeakers(handle)
    override val frameSeconds: Float = NativeBridge.nativeDiarizerFrameSeconds(handle)

    override val framesEmitted: Long
        @Synchronized get() = NativeBridge.nativeDiarizerFramesEmitted(open())

    @Synchronized
    override fun pushAudio(samples: FloatArray, count: Int): FloatArray {
        requireSampleCount(samples, count)
        return NativeBridge.nativeDiarizerPush(open(), samples, count) ?: FloatArray(0)
    }

    @Synchronized
    override fun endStream(): FloatArray =
        NativeBridge.nativeDiarizerEnd(open()) ?: FloatArray(0)

    @Synchronized
    override fun reset() {
        NativeBridge.nativeDiarizerReset(open())
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) {
            NativeBridge.nativeDestroyDiarizer(handle)
            handle = 0L
        }
    }

    private fun open(): Long {
        check(handle != 0L) { "SpeakerDiarizer is closed" }
        return handle
    }
}
