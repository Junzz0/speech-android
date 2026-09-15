package audio.soniqo.speech

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.sqrt

/**
 * [SpeakerEmbedder] on ReDimNet2-B6, downloaded through
 * [ModelManager.ensureSpeakerEmbeddingModels] (~51 MB on first run).
 */
@RunWith(AndroidJUnit4::class)
class SpeakerEmbedderTest {

    private lateinit var modelDir: String

    @Before
    fun setup() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        modelDir = ModelManager.ensureSpeakerEmbeddingModels(context)
        assertTrue(ModelManager.areSpeakerEmbeddingModelsReady(context))
    }

    @Test
    fun theSameVoiceIsCloserThanAnotherVoice() {
        val voiceA = MeetingFixtures.read("turn_a.wav")
        val voiceB = MeetingFixtures.read("turn_b.wav")
        // Different words from one voice, against the other voice.
        val firstA = voiceA.copyOfRange(0, voiceA.size / 2)
        val secondA = voiceA.copyOfRange(voiceA.size / 2, voiceA.size)
        val firstB = voiceB.copyOfRange(0, voiceB.size / 2)

        val loadStarted = SystemClock.elapsedRealtime()
        SpeakerEmbedder(SpeakerEmbedderConfig(modelDir = modelDir)).use { embedder ->
            val loadMs = SystemClock.elapsedRealtime() - loadStarted
            assertEquals(192, embedder.dimension)
            assertEquals(32_000, embedder.minimumSamples)

            val embedStarted = SystemClock.elapsedRealtime()
            val a1 = embedder.embed(firstA)
            val embedMs = SystemClock.elapsedRealtime() - embedStarted
            val a2 = embedder.embed(secondA)
            val b1 = embedder.embed(firstB)

            assertEquals(192, a1.size)
            assertEquals("vectors are unit length", 1.0, norm(a1), 1e-3)
            val sameVoice = MeetingFixtures.cosine(a1, a2)
            val otherVoice = MeetingFixtures.cosine(a1, b1)

            Log.i(
                MeetingFixtures.TAG,
                "embedder loadMs=$loadMs embedMs=$embedMs " +
                    "audioS=${"%.2f".format(MeetingFixtures.seconds(firstA.size))} " +
                    "rtf=${"%.3f".format(embedMs / 1000.0 / MeetingFixtures.seconds(firstA.size))} " +
                    "sameVoice=${"%.3f".format(sameVoice)} otherVoice=${"%.3f".format(otherVoice)}",
            )

            assertTrue(
                "same voice $sameVoice should be clearly above other voice $otherVoice",
                sameVoice > otherVoice + 0.2f,
            )
        }
    }

    @Test
    fun audioShorterThanTwoSecondsIsRejected() {
        SpeakerEmbedder(SpeakerEmbedderConfig(modelDir = modelDir)).use { embedder ->
            assertThrows(IllegalArgumentException::class.java) {
                embedder.embed(FloatArray(embedder.minimumSamples - 1) { 0.01f })
            }
        }
    }

    @Test
    fun anotherSampleRateIsResampled() {
        val voiceA = MeetingFixtures.read("turn_a.wav")
        // 8 kHz: every second sample. Four seconds of it clear the floor once
        // resampled to 16 kHz.
        val downsampled = FloatArray(voiceA.size / 2) { voiceA[it * 2] }
        SpeakerEmbedder(SpeakerEmbedderConfig(modelDir = modelDir)).use { embedder ->
            val vector = embedder.embed(downsampled, sampleRate = 8_000)
            assertEquals(192, vector.size)
            assertTrue(vector.all { it.isFinite() })
        }
    }

    private fun norm(vector: FloatArray): Double = sqrt(vector.sumOf { it.toDouble() * it })
}
