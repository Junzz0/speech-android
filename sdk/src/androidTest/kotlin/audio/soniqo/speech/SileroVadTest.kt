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
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * On-device Silero VAD behavior through the public pipeline API: silence must
 * not raise SpeechStarted, synthesized speech must raise it and close the
 * segment once silence follows.
 */
@RunWith(AndroidJUnit4::class)
class SileroVadTest {

    private lateinit var modelDir: String

    @Before
    fun setup() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        modelDir = ModelManager.ensureModels(ctx)
    }

    @Test
    fun silenceDoesNotTriggerSpeechStarted() = runBlocking {
        val pipeline = SpeechPipeline(SpeechConfig(modelDir = modelDir, useNnapi = false))
        try {
            pipeline.start()
            // Subscribe before pushing — the events flow has no replay.
            val started = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeoutOrNull(10_000) {
                    pipeline.events.first { it is SpeechEvent.SpeechStarted }
                }
            }

            repeat(94) { pipeline.pushAudio(FloatArray(512)) } // ~3 s of silence

            assertNull("silence must not trigger SpeechStarted", started.await())
        } finally {
            pipeline.stop()
            pipeline.close()
        }
    }

    @Test
    fun synthesizedSpeechTriggersSpeechStartedAndEnded() = runBlocking {
        val pipeline = SpeechPipeline(SpeechConfig(modelDir = modelDir, useNnapi = false))
        try {
            pipeline.start()
            val started = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(30_000) {
                    pipeline.events.first { it is SpeechEvent.SpeechStarted }
                }
            }
            val ended = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(60_000) {
                    pipeline.events.first { it is SpeechEvent.SpeechEnded }
                }
            }

            val audio = synthesize("The quick brown fox jumps over the lazy dog.")
            for (offset in audio.indices step 512) {
                val end = minOf(offset + 512, audio.size)
                val chunk = audio.sliceArray(offset until end)
                if (chunk.size == 512) {
                    pipeline.pushAudio(chunk)
                    delay(32) // real-time pace: 512 samples @ 16 kHz
                }
            }
            repeat(47) { // ~1.5 s of trailing silence closes the segment
                pipeline.pushAudio(FloatArray(512))
                delay(32)
            }

            assertNotNull(started.await())
            assertNotNull(ended.await())
        } finally {
            pipeline.stop()
            pipeline.close()
        }
    }

    @Test
    fun pipelineCreatesAndDestroys() {
        val pipeline = SpeechPipeline(SpeechConfig(modelDir = modelDir, useNnapi = false))
        assertEquals(PipelineState.Idle, pipeline.state)
        pipeline.start()
        pipeline.stop()
        pipeline.close()
    }

    private fun synthesize(text: String): FloatArray =
        SpeechSynthesizer(SpeechSynthesizerConfig(modelDir = modelDir, useNnapi = false)).use {
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
