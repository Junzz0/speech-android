package audio.soniqo.speech.control

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Software input gain for the recognizer path. The VOICE_RECOGNITION audio
 * source bypasses hardware AGC on most devices; measured speech on this
 * demo's phone sits near -31 dBFS, starving the INT8 acoustic encoder.
 * Normalizes toward a -20 dBFS speech RMS with a smoothed gain (bounded,
 * slow-moving) and a hard safety clip. Stateful per session — call [reset]
 * when the mic session restarts.
 */
class MicGain(
    private val targetRms: Float = 0.1f,     // ~-20 dBFS
    private val maxGain: Float = 12f,
    private val attack: Float = 0.2f,        // per-chunk smoothing toward target
) {
    private var smoothedRms = 0f
    private var gain = 1f

    fun reset() {
        smoothedRms = 0f
        gain = 1f
    }

    /** Apply gain in place to [buffer]'s first [count] samples. */
    fun process(buffer: FloatArray, count: Int) {
        var sum = 0f
        for (i in 0 until count) sum += buffer[i] * buffer[i]
        val rms = sqrt(sum / max(1, count))

        // Track speech level only — silence must not crank the gain.
        if (rms > 0.004f) {
            smoothedRms = if (smoothedRms == 0f) rms
            else smoothedRms + attack * (rms - smoothedRms)
            val desired = (targetRms / max(smoothedRms, 1e-4f))
                .coerceIn(1f, maxGain)
            gain += attack * (desired - gain)
        }
        for (i in 0 until count) {
            val v = buffer[i] * gain
            buffer[i] = if (abs(v) > 0.98f) (if (v > 0) 0.98f else -0.98f) else v
        }
    }
}
