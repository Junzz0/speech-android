package audio.soniqo.speech

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Gated physical-device coverage for the bounded Kokoro graph and JNI model ID.
 *
 * Run with `-e kokoroModelDir <app-readable-model-directory>`. The directory
 * must contain `kokoro-e2e-realtime.onnx`, the shared
 * `kokoro-e2e.onnx.data`, dictionaries, vocabulary, and voices.
 */
@RunWith(AndroidJUnit4::class)
class KokoroShortTurnProfileTest {

    @Test
    fun directSynthesizerLoadsShortTurnGraphAndRetriesSafely() {
        val args = InstrumentationRegistry.getArguments()
        val modelDir = args.getString("kokoroModelDir")
        assumeTrue("set -e kokoroModelDir to run the Kokoro device test", !modelDir.isNullOrBlank())
        assumeTrue(File(modelDir!!, "kokoro-e2e-realtime.onnx").isFile)
        assumeTrue(File(modelDir, "kokoro-e2e.onnx.data").isFile)

        SpeechSynthesizer(
            SpeechSynthesizerConfig(
                modelDir = modelDir,
                useNnapi = false,
                ttsModel = TtsModel.KOKORO_SHORT_TURN,
            )
        ).use { synthesizer ->
            assertEquals(24_000, synthesizer.sampleRate)

            val shortStart = SystemClock.elapsedRealtimeNanos()
            val short = synthesizer.synthesize(
                "The quick brown fox jumps over the lazy dog",
                "en",
            )
            val shortMs = (SystemClock.elapsedRealtimeNanos() - shortStart) / 1_000_000.0
            assertEquals(24_000, short.sampleRate)
            assertTrue("short-turn PCM must not be empty", short.pcm16.isNotEmpty())
            assertTrue("short-turn PCM must contain audio", short.pcm16.any { it != 0.toByte() })

            val retry = synthesizer.synthesize(
                "The package arrives tomorrow morning safely today.",
                "en",
            )
            assertEquals(24_000, retry.sampleRate)
            assertTrue("retry PCM must not be empty", retry.pcm16.isNotEmpty())
            assertTrue("retry PCM must contain audio", retry.pcm16.any { it != 0.toByte() })

            val audioSeconds = short.pcm16.size / 2.0 / short.sampleRate
            val rtf = (shortMs / 1_000.0) / audioSeconds
            println("KOKORO_SHORT_TURN short_ms=$shortMs audio_s=$audioSeconds rtf=$rtf")
        }
    }
}
