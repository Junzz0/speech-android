package audio.soniqo.speech

import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Two-voice speech under `androidTest/assets/meeting/`, generated with macOS
 * `say` (Samantha for voice A, Daniel for voice B) and converted to 16 kHz
 * mono 16-bit WAV with `afconvert`. Synthesized, so no person's voice is in
 * the repository.
 */
internal object MeetingFixtures {
    const val SAMPLE_RATE = 16_000
    const val TAG = "MeetingModels"

    const val TRANSCRIBER_SENTENCE =
        "The meeting starts at nine tomorrow morning, and the budget review comes first."

    fun read(name: String): FloatArray {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets
            .open("meeting/$name").use { it.readBytes() }
        return decodeWav(bytes)
    }

    fun seconds(samples: Int): Double = samples.toDouble() / SAMPLE_RATE

    fun words(text: String): List<String> =
        text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    fun cosine(left: FloatArray, right: FloatArray): Float {
        var dot = 0.0
        var leftNorm = 0.0
        var rightNorm = 0.0
        for (index in left.indices) {
            dot += left[index] * right[index]
            leftNorm += left[index] * left[index]
            rightNorm += right[index] * right[index]
        }
        return (dot / (sqrt(leftNorm) * sqrt(rightNorm))).toFloat()
    }

    private fun decodeWav(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(
            String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE"
        ) { "not a WAV file" }
        var offset = 12
        var format = 0
        var channels = 0
        var rate = 0
        var bits = 0
        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            val size = buffer.getInt(offset + 4)
            val body = offset + 8
            when (id) {
                "fmt " -> {
                    format = buffer.getShort(body).toInt() and 0xFFFF
                    channels = buffer.getShort(body + 2).toInt()
                    rate = buffer.getInt(body + 4)
                    bits = buffer.getShort(body + 14).toInt()
                }
                "data" -> {
                    require(
                        (format == 1 || format == 0xFFFE) && channels == 1 &&
                            rate == SAMPLE_RATE && bits == 16
                    ) {
                        "expected 16 kHz mono 16-bit PCM, got format=$format " +
                            "channels=$channels rate=$rate bits=$bits"
                    }
                    val count = minOf(size, bytes.size - body) / 2
                    return FloatArray(count) { index -> buffer.getShort(body + index * 2) / 32768f }
                }
            }
            offset = body + size + (size and 1)
        }
        error("WAV file has no data chunk")
    }
}
