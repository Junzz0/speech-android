package audio.soniqo.speech

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Standalone VAD-only detection: the same speech-boundary behavior
 * [SileroVadTest] covers through the full pipeline, with no STT or TTS model
 * loaded and only Silero on disk.
 */
@RunWith(AndroidJUnit4::class)
class VadDetectorTest {

    private lateinit var vadModelDir: String
    private lateinit var fixtureModelDir: String

    @Before
    fun setup() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        // The detector under test loads from the VAD-only cache — 2 MB, its own
        // directory, no STT/TTS files. Fixture speech comes from the shared
        // pipeline cache the rest of the suite already downloads; asking for a
        // TTS-only copy here would duplicate ~330 MB of Kokoro on every device.
        vadModelDir = ModelManager.ensureVadModels(ctx)
        fixtureModelDir = ModelManager.ensureModels(ctx)
    }

    @Test
    fun vadOnlyCacheHoldsSileroAndNothingElse() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(ModelManager.areVadModelsReady(ctx))

        val files = File(vadModelDir).listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".onnx") }
            .map { it.name }

        assertEquals(listOf("silero-vad.onnx"), files)
    }

    @Test
    fun silenceDoesNotTriggerSpeechStarted() = runBlocking {
        VadDetector(VadConfig(modelDir = vadModelDir)).use { detector ->
            val started = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeoutOrNull(10_000) {
                    detector.events.first { it is VadEvent.SpeechStarted }
                }
            }

            repeat(94) { detector.pushAudio(FloatArray(512)) } // ~3 s of silence

            assertNull("silence must not trigger SpeechStarted", started.await())
            assertFalse(detector.isSpeaking)
        }
    }

    @Test
    fun synthesizedSpeechTriggersSpeechStartedAndEnded() = runBlocking {
        // Fixture generation is deliberately outside the event timeouts: the
        // detector has received no audio yet and has nothing to detect.
        val audio = synthesize("The quick brown fox jumps over the lazy dog.")

        VadDetector(
            VadConfig(modelDir = vadModelDir, emitUtteranceAudio = true),
        ).use { detector ->
            val started = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(30_000) {
                    detector.events.first { it is VadEvent.SpeechStarted }
                }
            }
            val ended = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(60_000) {
                    detector.events.first { it is VadEvent.SpeechEnded }
                }
            }

            pushRealtime(detector, audio)
            repeat(47) { // ~1.5 s of trailing silence closes the segment
                detector.pushAudio(FloatArray(512))
                delay(32)
            }

            assertNotNull(started.await())
            val end = ended.await() as VadEvent.SpeechEnded
            assertNotNull("emitUtteranceAudio was on", end.audio)
            assertTrue(
                "captured utterance should carry the speech",
                end.audio!!.size > 16000 / 2,
            )
            assertFalse(detector.isSpeaking)
        }
    }

    @Test
    fun utteranceAudioIsOmittedByDefault() = runBlocking {
        val audio = synthesize("Hello there.")

        VadDetector(VadConfig(modelDir = vadModelDir)).use { detector ->
            val ended = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(60_000) {
                    detector.events.first { it is VadEvent.SpeechEnded }
                }
            }

            pushRealtime(detector, audio)
            repeat(47) {
                detector.pushAudio(FloatArray(512))
                delay(32)
            }

            assertNull((ended.await() as VadEvent.SpeechEnded).audio)
        }
    }

    @Test
    fun flushClosesAnOpenSegmentAtEndOfStream() = runBlocking {
        val audio = synthesize("Testing one two three.")

        VadDetector(VadConfig(modelDir = vadModelDir)).use { detector ->
            val ended = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(60_000) {
                    detector.events.first { it is VadEvent.SpeechEnded }
                }
            }

            // No trailing silence: without flush() the segment stays open and
            // a recording that stops mid-utterance loses its final event.
            pushRealtime(detector, audio)
            detector.flush()

            assertNotNull(ended.await())
        }
    }

    @Test
    fun detectorCreatesAndDestroys() {
        val detector = VadDetector(VadConfig(modelDir = vadModelDir))
        assertFalse(detector.isSpeaking)
        detector.reset()
        detector.close()
    }

    private suspend fun pushRealtime(detector: VadDetector, audio: FloatArray) {
        for (offset in audio.indices step 512) {
            val end = minOf(offset + 512, audio.size)
            val chunk = audio.sliceArray(offset until end)
            if (chunk.size == 512) {
                detector.pushAudio(chunk)
                delay(32) // real-time pace: 512 samples @ 16 kHz
            }
        }
    }

    private fun synthesize(text: String): FloatArray =
        SpeechSynthesizer(SpeechSynthesizerConfig(modelDir = fixtureModelDir, useNnapi = false)).use {
            val spoken = it.synthesize(text, "en")
            assertTrue("synthesized fixture should not be empty", spoken.pcm16.isNotEmpty())
            pcm16ToFloat16k(spoken.pcm16, spoken.sampleRate)
        }

    private fun pcm16ToFloat16k(pcm16: ByteArray, sourceSampleRate: Int): FloatArray {
        val shorts = ShortArray(pcm16.size / 2)
        ByteBuffer.wrap(pcm16)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(shorts)
        val source = FloatArray(shorts.size) { i -> shorts[i] / 32768.0f }
        if (sourceSampleRate == 16000) return source

        val outputSize = ((source.size.toLong() * 16000L) / sourceSampleRate).toInt()
        return FloatArray(outputSize) { i ->
            val src = i.toDouble() * sourceSampleRate.toDouble() / 16000.0
            val lo = src.toInt().coerceIn(0, source.lastIndex)
            val hi = minOf(lo + 1, source.lastIndex)
            val frac = (src - lo).toFloat()
            source[lo] * (1.0f - frac) + source[hi] * frac
        }
    }
}
