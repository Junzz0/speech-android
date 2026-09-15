package audio.soniqo.speech

/** Configuration for [SpeakerEmbedder]. */
data class SpeakerEmbedderConfig(
    /** Directory returned by [ModelManager.ensureSpeakerEmbeddingModels]. */
    val modelDir: String,

    /** Try NNAPI. CPU is the default. */
    val hardwareAcceleration: Boolean = false,
)

/**
 * ReDimNet2-B6 speaker embeddings: one unit-length 192-dimensional vector per
 * stretch of one person's speech, for comparing voices by cosine similarity.
 *
 * It is not a diarizer and its vectors are not authentication. Deciding which
 * audio belongs to one speaker, and how close two vectors must be to count as
 * the same voice, is left to the caller.
 *
 * The graph takes a fixed 6 s window: longer audio contributes its centre 6 s,
 * shorter audio is repeated to fill the window. Audio at another rate is
 * resampled to 16 kHz first.
 *
 * Calls are serialized; [close] releases the model.
 */
interface SpeakerEmbedder : AutoCloseable {

    /** Length of every vector [embed] returns: 192. */
    val dimension: Int

    /** Fewest 16 kHz samples [embed] accepts: 32 000, two seconds. */
    val minimumSamples: Int

    /**
     * Embed [count] mono float32 samples at [sampleRate]. Throws
     * [IllegalArgumentException] when the audio holds fewer than
     * [minimumSamples] once at 16 kHz, or a non-finite sample.
     */
    fun embed(samples: FloatArray, count: Int = samples.size, sampleRate: Int = 16000): FloatArray

    companion object {
        /** Loads the model. Throws [RuntimeException] naming the cause when
         *  it cannot be loaded. */
        operator fun invoke(config: SpeakerEmbedderConfig): SpeakerEmbedder {
            config.requireValidConfiguration()
            return SpeakerEmbedderImpl(config)
        }
    }
}

internal fun SpeakerEmbedderConfig.requireValidConfiguration() {
    require(modelDir.isNotBlank()) {
        "SpeakerEmbedderConfig.modelDir must be the directory returned by " +
            "ModelManager.ensureSpeakerEmbeddingModels()"
    }
}

internal class SpeakerEmbedderImpl(config: SpeakerEmbedderConfig) : SpeakerEmbedder {

    private var handle: Long = NativeBridge.nativeCreateEmbedder(
        config.modelDir,
        config.hardwareAcceleration,
    ).also { h ->
        if (h == 0L) throw IllegalStateException("Failed to create the native speaker embedder")
    }

    override val dimension: Int = NativeBridge.nativeEmbedderDimension(handle)
    override val minimumSamples: Int = NativeBridge.nativeEmbedderMinimumSamples()

    @Synchronized
    override fun embed(samples: FloatArray, count: Int, sampleRate: Int): FloatArray {
        requireSampleCount(samples, count)
        require(sampleRate > 0) { "sampleRate must be positive, was $sampleRate" }
        return NativeBridge.nativeEmbed(open(), samples, count, sampleRate)
            ?: throw IllegalStateException("Speaker embedding returned no vector")
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) {
            NativeBridge.nativeDestroyEmbedder(handle)
            handle = 0L
        }
    }

    private fun open(): Long {
        check(handle != 0L) { "SpeakerEmbedder is closed" }
        return handle
    }
}
