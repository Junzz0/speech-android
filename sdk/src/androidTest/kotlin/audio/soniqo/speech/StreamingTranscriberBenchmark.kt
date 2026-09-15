package audio.soniqo.speech

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-time factor of [StreamingTranscriber] on the short sentence and a
 * ~20 s single utterance, pushed in 100 ms chunks the way a microphone
 * delivers them. Measurement only: skipped unless run with
 * `-e benchmark true`; `-e hardwareAcceleration true` selects the encoder
 * delegate.
 *
 * The mean push cost over the first and last quarter of the stream says
 * whether a window's cost grows with the audio already pushed.
 */
@RunWith(AndroidJUnit4::class)
class StreamingTranscriberBenchmark {

    private val arguments get() = InstrumentationRegistry.getArguments()
    private lateinit var modelDir: String

    @Before
    fun setup() = runBlocking {
        assumeTrue("pass -e benchmark true", arguments.getString("benchmark") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        modelDir = ModelManager.ensureTranscriberModels(context)
    }

    @Test
    fun shortSentence() = measure("transcriber_en.wav")

    @Test
    fun twentySecondUtterance() = measure("utterance_20s.wav")

    private fun measure(fixture: String) {
        val hardware = arguments.getString("hardwareAcceleration") == "true"
        val audio = MeetingFixtures.read(fixture)
        val audioSeconds = MeetingFixtures.seconds(audio.size)

        val loadStarted = SystemClock.elapsedRealtimeNanos()
        StreamingTranscriber(
            TranscriberConfig(modelDir = modelDir, hardwareAcceleration = hardware),
        ).use { transcriber ->
            val loadMs = (SystemClock.elapsedRealtimeNanos() - loadStarted) / 1_000_000

            transcriber.beginStream()
            val pushes = (audio.size + CHUNK_SAMPLES - 1) / CHUNK_SAMPLES
            val pushNanos = LongArray(pushes)
            val partials = StringBuilder()
            for (index in 0 until pushes) {
                val from = index * CHUNK_SAMPLES
                val chunk = audio.copyOfRange(from, minOf(from + CHUNK_SAMPLES, audio.size))
                val started = SystemClock.elapsedRealtimeNanos()
                val partial = transcriber.pushAudio(chunk)
                pushNanos[index] = SystemClock.elapsedRealtimeNanos() - started
                partials.append(partial.text).append('\n')
            }
            val endStarted = SystemClock.elapsedRealtimeNanos()
            val final = transcriber.endStream()
            val endNanos = SystemClock.elapsedRealtimeNanos() - endStarted

            val decodeMs = (pushNanos.sum() + endNanos) / 1e6
            val quarter = maxOf(1, pushes / 4)
            val firstQuarterMs = pushNanos.take(quarter).average() / 1e6
            val lastQuarterMs = pushNanos.takeLast(quarter).average() / 1e6
            Log.i(
                MeetingFixtures.TAG,
                "benchmark fixture=$fixture hardware=$hardware " +
                    "audioS=${"%.2f".format(audioSeconds)} loadMs=$loadMs " +
                    "decodeMs=${"%.0f".format(decodeMs)} rtf=${"%.3f".format(decodeMs / 1000.0 / audioSeconds)} " +
                    "firstQuarterPushMs=${"%.1f".format(firstQuarterMs)} " +
                    "lastQuarterPushMs=${"%.1f".format(lastQuarterMs)} " +
                    "maxPushMs=${"%.1f".format(pushNanos.max() / 1e6)} " +
                    "partialsDigest=${partials.toString().hashCode()} words=${final.words.size} " +
                    "final=[${final.text}]",
            )
        }
    }

    private companion object {
        const val CHUNK_SAMPLES = 1_600
    }
}
