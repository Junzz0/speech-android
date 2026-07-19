package audio.soniqo.speech

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Barge-in / interruption tests. Uses synthesized speech so the full
 * VAD -> STT -> TTS chain actually runs; one pipeline per test to avoid OOM
 * from concurrent model loads.
 */
@RunWith(AndroidJUnit4::class)
class BargeInTest {

    private lateinit var modelDir: String
    private lateinit var pipeline: SpeechPipeline

    @Before
    fun setup() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        modelDir = ModelManager.ensureModels(ctx)
        pipeline = SpeechPipeline(SpeechConfig(modelDir = modelDir, useNnapi = false))
        pipeline.start()
    }

    @After
    fun teardown() {
        pipeline.stop()
        pipeline.close()
    }

    @Test
    fun bargeInDuringSpeakingEmitsInterrupted() = runBlocking {
        // Both fixtures are synthesized before the first push: loading a second
        // TTS instance while the pipeline streams its response can starve the
        // emulator enough that the response finishes before the interruption lands.
        // Two duration constraints keep this scenario honest. The command must
        // echo back as many TTS chunks, because the pipeline is interruptible
        // only while later chunks are still synthesizing — on fast hardware a
        // short response leaves Speaking almost immediately after the first
        // delta. And the interruption must sustain speech past the turn
        // detector's min_interruption_duration (1 s), or it is discarded as
        // residual echo.
        val command = synthesize(
            "The quick brown fox jumps over the lazy dog. The slow white cat " +
                "watches the quiet garden. A small bird sings in the tall green " +
                "tree. The old dog sleeps near the warm stone wall. The red fox " +
                "runs across the wide open field. A grey mouse hides under the " +
                "wooden floor. The young child reads a long story. The tall man " +
                "walks along the sandy river bank."
        )
        val interruption = synthesize(
            "Stop talking now because I have a completely different question to ask you."
        )

        val delta = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(90_000) {
                pipeline.events.first { it is SpeechEvent.ResponseAudioDelta }
            }
        }

        pushRealtime(command)
        pushRealtime(FloatArray(16000)) // 1 s of silence ends the utterance

        delta.await() // pipeline is now speaking its echo response

        val interrupted = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(30_000) {
                pipeline.events.first { it is SpeechEvent.ResponseInterrupted }
            }
        }
        pushRealtime(interruption)

        assertNotNull(interrupted.await())
    }

    @Test
    fun pipelineStableAfterBargeIn() = runBlocking {
        // Two duration constraints keep this scenario honest. The command must
        // echo back as many TTS chunks, because the pipeline is interruptible
        // only while later chunks are still synthesizing — on fast hardware a
        // short response leaves Speaking almost immediately after the first
        // delta. And the interruption must sustain speech past the turn
        // detector's min_interruption_duration (1 s), or it is discarded as
        // residual echo.
        val command = synthesize(
            "The quick brown fox jumps over the lazy dog. The slow white cat " +
                "watches the quiet garden. A small bird sings in the tall green " +
                "tree. The old dog sleeps near the warm stone wall. The red fox " +
                "runs across the wide open field. A grey mouse hides under the " +
                "wooden floor. The young child reads a long story. The tall man " +
                "walks along the sandy river bank."
        )
        val interruption = synthesize(
            "Stop talking now because I have a completely different question to ask you."
        )

        val delta = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(90_000) {
                pipeline.events.first { it is SpeechEvent.ResponseAudioDelta }
            }
        }

        pushRealtime(command)
        pushRealtime(FloatArray(16000))

        delta.await()

        pushRealtime(interruption)
        delay(2_000)
        pipeline.resumeListening()

        assertTrue(
            "Pipeline should be in Idle or Listening after barge-in recovery, " +
                "was ${pipeline.state}",
            pipeline.state == PipelineState.Idle || pipeline.state == PipelineState.Listening
        )
    }

    @Test
    fun resumeListeningAfterInterruption() = runBlocking {
        val sr = 16000
        val tone = FloatArray(sr) { i ->
            (0.3f * Math.sin(2.0 * Math.PI * 200.0 * i / sr)).toFloat()
        }
        pushRealtime(tone)
        pipeline.resumeListening()

        assertTrue(
            "Pipeline should be in a valid state after resumeListening, was ${pipeline.state}",
            pipeline.state == PipelineState.Idle ||
            pipeline.state == PipelineState.Listening ||
            pipeline.state == PipelineState.Transcribing
        )
    }

    private suspend fun pushRealtime(audio: FloatArray) {
        for (offset in audio.indices step 512) {
            val end = minOf(offset + 512, audio.size)
            val chunk = audio.sliceArray(offset until end)
            if (chunk.size == 512) {
                pipeline.pushAudio(chunk)
                delay(32) // 512 samples @ 16 kHz
            }
        }
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
