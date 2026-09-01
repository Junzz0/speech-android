package audio.soniqo.speech

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Configuration for standalone voice activity detection.
 *
 * This is the VAD-only counterpart to [SpeechConfig]. It loads Silero VAD and
 * nothing else — no STT, no TTS, no pipeline — so the download is ~2 MB
 * instead of the ~500 MB default model set.
 */
data class VadConfig(
    /** Path to the directory containing `silero-vad.onnx`. */
    val modelDir: String = "",

    /** Speech starts once the frame probability rises above this. */
    val onsetThreshold: Float = 0.5f,

    /** Speech ends once the frame probability falls below this. Kept under
     *  [onsetThreshold] on purpose: the gap is the hysteresis that stops a
     *  probability hovering at the threshold from chattering. */
    val offsetThreshold: Float = 0.35f,

    /** Speech shorter than this never opens a segment — filters coughs, door
     *  clicks and mic bumps. */
    val minSpeechDurationSec: Float = 0.25f,

    /** Seconds of detected silence that end a segment. The 0.5 s default
     *  matches [SpeechConfig.endOfSpeechSilenceSec]; raise it (0.8–1.2 s) when
     *  speakers pause mid-utterance and get cut off early. */
    val endOfSpeechSilenceSec: Float = 0.5f,

    /** Seconds of audio kept before onset and prepended to the segment. Speech
     *  is already under way by the time the probability crosses the threshold,
     *  so without this the first syllable is missing from
     *  [VadEvent.SpeechEnded]. 0 disables the ring. */
    val preSpeechBufferSec: Float = 0.6f,

    /** A segment this long is closed and a new one opened, so a speaker who
     *  never pauses still produces bounded events. 0 = unbounded. */
    val maxUtteranceDurationSec: Float = 15f,

    /** Deliver the captured samples with [VadEvent.SpeechEnded]. Off by
     *  default: a listener that only needs the speech boundaries should not
     *  pay for a copy of every utterance. */
    val emitUtteranceAudio: Boolean = false,
)

/** Speech-boundary events from [VadDetector]. Times are seconds since the
 *  detector was created, counted over the audio pushed into it. */
sealed class VadEvent {
    data class SpeechStarted(val timeSec: Float) : VadEvent()

    /**
     * A segment closed. [audio] holds the utterance at 16 kHz including its
     * pre-speech head, or null when [VadConfig.emitUtteranceAudio] is off.
     */
    data class SpeechEnded(val timeSec: Float, val audio: FloatArray?) : VadEvent()
}

/**
 * On-device voice activity detection — Silero VAD v5 with turn detection, and
 * nothing else loaded.
 *
 * The VAD-only counterpart to [SpeechSynthesizer]: where that one speaks
 * without a pipeline, this one listens without one. Use it when the app needs
 * to know that someone is talking — push-to-talk gating, recording
 * segmentation, wake-on-voice — and never needs a transcript.
 *
 * Construct via `VadDetector(config)` after downloading the model with
 * [ModelManager.ensureVadModels]. Tests can supply their own implementation
 * of this interface to avoid loading the native library.
 *
 * Usage:
 * ```
 * val detector = VadDetector(VadConfig(modelDir = ModelManager.ensureVadModels(context)))
 * detector.events.collect { event -> ... }
 * detector.pushAudio(micSamples)
 * detector.close()
 * ```
 */
interface VadDetector : AutoCloseable {

    /** Stream of speech-boundary events. */
    val events: SharedFlow<VadEvent>

    /** True between [VadEvent.SpeechStarted] and [VadEvent.SpeechEnded]. */
    val isSpeaking: Boolean

    /**
     * Feed PCM Float32 microphone samples at 16 kHz. Events are emitted from
     * the calling thread, so do not push audio from the main thread.
     *
     * Samples are consumed in 512-sample chunks; a trailing partial chunk is
     * dropped rather than buffered, so push whole 512-sample frames.
     */
    fun pushAudio(samples: FloatArray)

    /**
     * Close an open segment at end of stream. Without this a recording that
     * stops mid-utterance never emits its final [VadEvent.SpeechEnded].
     */
    fun flush() {}

    /**
     * Reset for an independent audio stream: drops the pre-speech ring and the
     * model's recurrent state so audio cannot cross the boundary. Call it
     * between recordings, not between utterances.
     */
    fun reset() {}

    companion object {
        operator fun invoke(config: VadConfig): VadDetector {
            config.requireValidThresholds()
            return VadDetectorImpl(config)
        }
    }
}

/** Validate the hysteresis before JNI loads the model or starts native work. */
internal fun VadConfig.requireValidThresholds() {
    require(onsetThreshold > 0f && onsetThreshold <= 1f) {
        "VadConfig.onsetThreshold must be in (0, 1], was $onsetThreshold"
    }
    require(offsetThreshold > 0f && offsetThreshold <= onsetThreshold) {
        "VadConfig.offsetThreshold must be in (0, onsetThreshold], was " +
            "$offsetThreshold with onsetThreshold=$onsetThreshold. An offset " +
            "above the onset inverts the hysteresis and chatters on every frame."
    }
    require(minSpeechDurationSec >= 0f) {
        "VadConfig.minSpeechDurationSec must be >= 0, was $minSpeechDurationSec"
    }
    require(endOfSpeechSilenceSec > 0f) {
        "VadConfig.endOfSpeechSilenceSec must be > 0, was $endOfSpeechSilenceSec. " +
            "Zero silence ends a segment on the first non-speech frame."
    }
    require(preSpeechBufferSec >= 0f) {
        "VadConfig.preSpeechBufferSec must be >= 0, was $preSpeechBufferSec"
    }
    require(maxUtteranceDurationSec >= 0f) {
        "VadConfig.maxUtteranceDurationSec must be >= 0, was $maxUtteranceDurationSec"
    }
}

internal class VadDetectorImpl(config: VadConfig) : VadDetector {

    private val _events = MutableSharedFlow<VadEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val events: SharedFlow<VadEvent> = _events.asSharedFlow()

    private val nativeCallback = NativeBridge.VadCallback { type, timeSec, audio ->
        val event = when (type) {
            0 -> VadEvent.SpeechStarted(timeSec)
            1 -> VadEvent.SpeechEnded(timeSec, audio)
            else -> null
        }
        if (event != null) _events.tryEmit(event)
    }

    private var handle: Long = NativeBridge.nativeCreateVad(
        config.modelDir,
        config.onsetThreshold,
        config.offsetThreshold,
        config.minSpeechDurationSec,
        config.endOfSpeechSilenceSec,
        config.preSpeechBufferSec,
        config.maxUtteranceDurationSec,
        config.emitUtteranceAudio,
        nativeCallback,
    ).also { h ->
        if (h == 0L) throw IllegalStateException(
            "Failed to create native VAD detector. The model may be corrupt — " +
                "try clearing app data and reinstalling."
        )
    }

    override val isSpeaking: Boolean
        get() = handle != 0L && NativeBridge.nativeVadInSpeech(handle)

    override fun pushAudio(samples: FloatArray) {
        check(handle != 0L) { "VadDetector is closed" }
        NativeBridge.nativePushVadAudio(handle, samples, samples.size)
    }

    override fun flush() {
        if (handle != 0L) NativeBridge.nativeFlushVad(handle)
    }

    override fun reset() {
        if (handle != 0L) NativeBridge.nativeResetVad(handle)
    }

    override fun close() {
        if (handle != 0L) {
            NativeBridge.nativeDestroyVad(handle)
            handle = 0
        }
    }
}
