package audio.soniqo.speech

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [SpeakerDiarizer] on the Sortformer 4-speaker ONNX export, downloaded
 * through [ModelManager.ensureDiarizerModels] (~475 MB on first run).
 */
@RunWith(AndroidJUnit4::class)
class SpeakerDiarizerTest {

    private lateinit var modelDir: String

    @Before
    fun setup() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        modelDir = ModelManager.ensureDiarizerModels(context)
        assertTrue(ModelManager.areDiarizerModelsReady(context))
    }

    @Test
    fun twoVoicesTakingTurnsOwnDifferentColumns() {
        val voiceA = MeetingFixtures.read("turn_a.wav")
        val voiceB = MeetingFixtures.read("turn_b.wav")
        // A, B, A, B: about 38 s, past one 27.2 s call.
        val turns = listOf(voiceA, voiceB, voiceA, voiceB)
        val audio = FloatArray(turns.sumOf { it.size })
        val turnStarts = IntArray(turns.size)
        var cursor = 0
        turns.forEachIndexed { index, turn ->
            turnStarts[index] = cursor
            turn.copyInto(audio, cursor)
            cursor += turn.size
        }

        val loadStarted = SystemClock.elapsedRealtime()
        SpeakerDiarizer(DiarizerConfig(modelDir = modelDir)).use { diarizer ->
            val loadMs = SystemClock.elapsedRealtime() - loadStarted
            assertEquals(4, diarizer.speakers)
            assertEquals(0.08f, diarizer.frameSeconds, 1e-4f)

            val decodeStarted = SystemClock.elapsedRealtime()
            val collected = ArrayList<FloatArray>()
            var nonEmptyPushes = 0
            for (offset in audio.indices step MeetingFixtures.SAMPLE_RATE) {
                val chunk = audio.copyOfRange(offset, minOf(offset + MeetingFixtures.SAMPLE_RATE, audio.size))
                val frames = diarizer.pushAudio(chunk)
                if (frames.isNotEmpty()) nonEmptyPushes++
                collected += frames
            }
            val streamedFrames = collected.sumOf { it.size } / diarizer.speakers
            collected += diarizer.endStream()
            val decodeMs = SystemClock.elapsedRealtime() - decodeStarted
            val audioSeconds = MeetingFixtures.seconds(audio.size)

            val probabilities = FloatArray(collected.sumOf { it.size })
            var at = 0
            for (block in collected) {
                block.copyInto(probabilities, at)
                at += block.size
            }
            val speakers = diarizer.speakers
            assertEquals(0, probabilities.size % speakers)
            val frameCount = probabilities.size / speakers
            assertEquals(frameCount.toLong(), diarizer.framesEmitted)
            assertTrue("one 27.2 s call should finish before the end", streamedFrames >= 340)
            assertTrue(
                "timeline of ${frameCount * diarizer.frameSeconds} s should cover $audioSeconds s",
                frameCount * diarizer.frameSeconds >= audioSeconds - diarizer.frameSeconds,
            )
            assertTrue(
                "probabilities must be finite and within [0, 1]",
                probabilities.all { it.isFinite() && it >= -1e-3f && it <= 1f + 1e-3f },
            )

            // Mean probability per column over each turn, skipping half a
            // second at either edge where the voices meet.
            val margin = MeetingFixtures.SAMPLE_RATE / 2
            val turnMeans = turns.indices.map { index ->
                val first = ((turnStarts[index] + margin) / MeetingFixtures.SAMPLE_RATE.toFloat() /
                    diarizer.frameSeconds).toInt()
                val last = ((turnStarts[index] + turns[index].size - margin) /
                    MeetingFixtures.SAMPLE_RATE.toFloat() / diarizer.frameSeconds).toInt()
                    .coerceAtMost(frameCount)
                DoubleArray(speakers) { column ->
                    (first until last).sumOf { probabilities[it * speakers + column].toDouble() } /
                        (last - first).coerceAtLeast(1)
                }
            }
            val voiceAMeans = DoubleArray(speakers) { column -> (turnMeans[0][column] + turnMeans[2][column]) / 2 }
            val voiceBMeans = DoubleArray(speakers) { column -> (turnMeans[1][column] + turnMeans[3][column]) / 2 }
            val voiceAColumn = voiceAMeans.indices.maxBy { voiceAMeans[it] }
            val voiceBColumn = voiceBMeans.indices.maxBy { voiceBMeans[it] }

            Log.i(
                MeetingFixtures.TAG,
                "diarizer loadMs=$loadMs decodeMs=$decodeMs audioS=${"%.1f".format(audioSeconds)} " +
                    "rtf=${"%.3f".format(decodeMs / 1000.0 / audioSeconds)} frames=$frameCount " +
                    "nonEmptyPushes=$nonEmptyPushes voiceAColumn=$voiceAColumn voiceBColumn=$voiceBColumn " +
                    "turnDominant=${turnMeans.map { means -> means.indices.maxBy { means[it] } }} " +
                    "turnMeans=${turnMeans.map { means -> means.joinToString(prefix = "[", postfix = "]") { "%.2f".format(it) } }}",
            )

            assertNotEquals("the two voices should own different columns", voiceAColumn, voiceBColumn)
            assertTrue("voice A's column should be active in its turns", voiceAMeans[voiceAColumn] > 0.5)
            assertTrue("voice B's column should be active in its turns", voiceBMeans[voiceBColumn] > 0.5)
        }
    }

    @Test
    fun resetRestartsTheTimeline() {
        val voiceA = MeetingFixtures.read("turn_a.wav")
        val audio = FloatArray(voiceA.size * 4) { voiceA[it % voiceA.size] }
        SpeakerDiarizer(DiarizerConfig(modelDir = modelDir)).use { diarizer ->
            diarizer.pushAudio(audio)
            assertTrue(diarizer.framesEmitted > 0)

            diarizer.endStream()
            assertEquals("audio after endStream is ignored", 0, diarizer.pushAudio(audio).size)

            diarizer.reset()
            assertEquals(0L, diarizer.framesEmitted)
            assertTrue("a reset stream accepts audio again", diarizer.pushAudio(audio).isNotEmpty())
        }
    }
}
