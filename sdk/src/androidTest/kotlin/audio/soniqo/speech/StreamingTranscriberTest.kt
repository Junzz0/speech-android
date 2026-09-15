package audio.soniqo.speech

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [StreamingTranscriber] on the LiteRT INT8 Nemotron bundle, downloaded
 * through [ModelManager.ensureTranscriberModels] (~721 MB on first run).
 */
@RunWith(AndroidJUnit4::class)
class StreamingTranscriberTest {

    private lateinit var modelDir: String

    @Before
    fun setup() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        modelDir = ModelManager.ensureTranscriberModels(context)
        assertTrue(ModelManager.areTranscriberModelsReady(context))
    }

    @Test
    fun automaticLanguageTranscribesEnglish() = assertTranscribesEnglish("auto")

    @Test
    fun explicitEnglishTranscribesEnglish() = assertTranscribesEnglish("en-US")

    @Test
    fun anUnknownLanguageIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            StreamingTranscriber(TranscriberConfig(modelDir = modelDir, language = "xx-XX")).close()
        }
    }

    @Test
    fun languagesResolveLikeSpeechSwift() {
        StreamingTranscriber(TranscriberConfig(modelDir = modelDir)).use { transcriber ->
            assertTrue(transcriber.setLanguage("auto"))
            assertTrue(transcriber.setLanguage("pt-BR"))
            assertTrue("underscore reads as a dash", transcriber.setLanguage("pt_BR"))
            assertTrue("falls back to the bare language", transcriber.setLanguage("fr-XX"))
            assertFalse(transcriber.setLanguage("klingon"))
        }
    }

    @Test
    fun aCancelledStreamLeavesNothingToEnd() {
        val audio = MeetingFixtures.read("transcriber_en.wav")
        StreamingTranscriber(TranscriberConfig(modelDir = modelDir)).use { transcriber ->
            transcriber.pushAudio(audio)
            transcriber.cancelStream()
            assertEquals("", transcriber.endStream().text)

            // The next stream starts from nothing, not after the cancelled one.
            val half = audio.size / 2
            val partial = transcriber.pushAudio(audio, half).text
            val final = transcriber.endStream().text
            assertTrue("'$final' should begin with '$partial'", final.startsWith(partial))
        }
    }

    private fun assertTranscribesEnglish(language: String) {
        val audio = MeetingFixtures.read("transcriber_en.wav")
        val loadStarted = SystemClock.elapsedRealtime()
        StreamingTranscriber(TranscriberConfig(modelDir = modelDir, language = language)).use { transcriber ->
            val loadMs = SystemClock.elapsedRealtime() - loadStarted
            assertEquals(16_000, transcriber.sampleRate)

            transcriber.beginStream()
            val decodeStarted = SystemClock.elapsedRealtime()
            var partial = ""
            var growths = 0
            for (offset in audio.indices step CHUNK_SAMPLES) {
                val end = minOf(offset + CHUNK_SAMPLES, audio.size)
                val pushed = transcriber.pushAudio(audio.copyOfRange(offset, end))
                assertWordsMatch(pushed, MeetingFixtures.seconds(end))
                if (pushed.text.length > partial.length) growths++
                partial = pushed.text
            }
            val final = transcriber.endStream()
            val decodeMs = SystemClock.elapsedRealtime() - decodeStarted
            val rtf = decodeMs / 1000.0 / MeetingFixtures.seconds(audio.size)

            Log.i(
                MeetingFixtures.TAG,
                "transcriber language=$language loadMs=$loadMs decodeMs=$decodeMs " +
                    "rtf=${"%.3f".format(rtf)} partialGrowths=$growths " +
                    "partial=[$partial] final=[${final.text}] words=${final.words}",
            )

            val expected = MeetingFixtures.words(MeetingFixtures.TRANSCRIBER_SENTENCE)
            val heard = MeetingFixtures.words(final.text).toSet()
            val matched = expected.count { it in heard }
            assertTrue(
                "expected most of $expected in '${final.text}' ($matched matched)",
                matched >= expected.size * 0.6,
            )
            assertTrue("partial text should grow while audio arrives", growths >= 2)
            assertTrue("a transcript has words", final.words.isNotEmpty())
            assertWordsMatch(final, MeetingFixtures.seconds(audio.size))
            assertEquals("a closed stream has nothing left", "", transcriber.endStream().text)
        }
    }

    /** Words read as the text, run forward in time, and end inside the audio pushed. */
    private fun assertWordsMatch(transcript: StreamingTranscript, pushedSeconds: Double) {
        assertEquals(transcript.text, transcript.words.joinToString(" ") { it.text })
        var previous: TimedToken? = null
        for (word in transcript.words) {
            assertTrue("$word starts before it ends", word.startSec < word.endSec)
            assertTrue("$word ends inside $pushedSeconds s", word.endSec <= pushedSeconds + 1e-4)
            previous?.let {
                assertTrue("$word starts no earlier than $it", word.startSec >= it.startSec)
                assertTrue("$word ends no earlier than $it", word.endSec >= it.endSec)
            }
            previous = word
        }
    }

    private companion object {
        // 100 ms, the size of an ordinary microphone callback.
        const val CHUNK_SAMPLES = 1_600
    }
}
