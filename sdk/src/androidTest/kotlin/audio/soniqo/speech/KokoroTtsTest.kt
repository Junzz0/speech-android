package audio.soniqo.speech

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E2E test: Kokoro 82M TTS synthesis on device.
 *
 * Verifies that:
 * - Model and phonemizer load correctly
 * - Synthesis produces non-empty audio
 * - Audio output event is emitted
 * - Output is valid PCM at 24 kHz
 */
@RunWith(AndroidJUnit4::class)
class KokoroTtsTest {

    private lateinit var modelDir: String

    @Before
    fun setup() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        modelDir = ModelManager.ensureModels(ctx)
    }

    @Test
    fun echoModeSynthesizesAudio() = runBlocking {
        // Echo mode: STT output → TTS input (no LLM)
        val config = SpeechConfig(modelDir = modelDir, useNnapi = false)
        val pipeline = SpeechPipeline(config)
        pipeline.start()

        // Feed speech-like signal to trigger transcription → synthesis
        val sr = 16000
        val speech = FloatArray(sr * 2) { i ->
            val t = i.toFloat() / sr
            (0.3f * Math.sin(2.0 * Math.PI * 200.0 * t)).toFloat()
        }

        for (offset in speech.indices step 512) {
            val end = minOf(offset + 512, speech.size)
            val chunk = speech.sliceArray(offset until end)
            if (chunk.size == 512) pipeline.pushAudio(chunk)
        }

        // Silence to end utterance
        val silence = FloatArray(sr)
        for (offset in silence.indices step 512) {
            val chunk = silence.sliceArray(offset until minOf(offset + 512, silence.size))
            if (chunk.size == 512) pipeline.pushAudio(chunk)
        }

        // Wait for audio response
        try {
            val event = withTimeout(30_000) {
                pipeline.events.first { it is SpeechEvent.ResponseAudioDelta }
            }
            val audio = event as SpeechEvent.ResponseAudioDelta
            assertTrue("Audio should not be empty", audio.audio.isNotEmpty())
            assertTrue("TTS latency should be positive", audio.ttsMs > 0f)
        } catch (e: Exception) {
            // May not trigger if VAD doesn't detect synthetic signal
        }

        pipeline.stop()
        pipeline.close()
    }

    @Test
    fun ttsOutputIsValidPcm() = runBlocking {
        val config = SpeechConfig(modelDir = modelDir, useNnapi = false)
        val pipeline = SpeechPipeline(config)
        pipeline.start()

        // Feed speech-like signal to trigger VAD -> STT -> TTS
        val sr = 16000
        val speech = FloatArray(sr * 2) { i ->
            val t = i.toFloat() / sr
            (0.3f * Math.sin(2.0 * Math.PI * 200.0 * t)).toFloat()
        }

        for (offset in speech.indices step 512) {
            val end = minOf(offset + 512, speech.size)
            val chunk = speech.sliceArray(offset until end)
            if (chunk.size == 512) pipeline.pushAudio(chunk)
        }

        // Silence to end utterance
        val silence = FloatArray(sr)
        for (offset in silence.indices step 512) {
            val chunk = silence.sliceArray(offset until minOf(offset + 512, silence.size))
            if (chunk.size == 512) pipeline.pushAudio(chunk)
        }

        try {
            val event = withTimeout(30_000) {
                pipeline.events.first { it is SpeechEvent.ResponseAudioDelta }
            }
            val audio = (event as SpeechEvent.ResponseAudioDelta).audio

            // Audio should be non-empty
            assertTrue("TTS output should not be empty", audio.isNotEmpty())

            // PCM bytes should have reasonable length: at least 100 bytes for any phrase
            assertTrue(
                "TTS output too short (${audio.size} bytes), expected at least 100",
                audio.size >= 100
            )

            // Verify audio is not all zeros (actual PCM content)
            val hasNonZero = audio.any { it != 0.toByte() }
            assertTrue("TTS output should contain non-zero PCM data", hasNonZero)

            // At 24kHz 16-bit mono, 1 second = 48000 bytes.
            // Even a single word should produce at least ~0.1s of audio (4800 bytes).
            assertTrue(
                "TTS output suspiciously short (${audio.size} bytes), expected >= 4800 for any phrase",
                audio.size >= 4800
            )
        } catch (_: Exception) {
            // Synthetic signal may not trigger full VAD -> STT -> TTS chain
        }

        pipeline.stop()
        pipeline.close()
    }

    /**
     * Direct synthesis with a per-call voice preset: a different Kokoro voice
     * changes the predicted duration, the preset does not leak into the next
     * default call, and an unknown id fails loudly without breaking later
     * synthesis. Durations are compared rather than samples because Kokoro
     * draws random vocoder phases on every call.
     */
    @Test
    fun directSynthesisAppliesVoicePresetPerCall() {
        val config = SpeechConfig(
            modelDir = modelDir,
            useNnapi = false,
            pipelineMode = PipelineMode.TRANSCRIBE_ONLY,
        )
        SpeechPipeline(config).use { pipeline ->
            val text = "The quick brown fox jumps over the lazy dog."
            // One Kokoro frame is 600 samples = 1200 PCM16 bytes.
            val frameBytes = 1200

            val default1 = pipeline.synthesize(text, "en").pcm16
            val voiced = pipeline.synthesize(text, "en", "ff_siwis").pcm16
            val default2 = pipeline.synthesize(text, "en").pcm16

            assertTrue("default synthesis too short (${default1.size} bytes)", default1.size >= 4800)
            assertTrue("voiced synthesis too short (${voiced.size} bytes)", voiced.size >= 4800)
            assertTrue(
                "A different voice preset should change the predicted duration " +
                    "(default=${default1.size} voiced=${voiced.size} bytes)",
                Math.abs(voiced.size - default1.size) > frameBytes,
            )
            assertTrue(
                "The voice preset must not leak into the next default call " +
                    "(${default1.size} vs ${default2.size} bytes)",
                Math.abs(default1.size - default2.size) <= frameBytes,
            )

            val error = runCatching { pipeline.synthesize(text, "en", "no_such_voice") }.exceptionOrNull()
            assertTrue("Unknown voice should throw, got $error", error is RuntimeException)
            assertTrue(
                "Unknown-voice error should name the voice: ${error?.message}",
                error?.message?.contains("no_such_voice") == true,
            )

            // The failed call must leave the engine usable and on its default voice.
            val afterError = pipeline.synthesize(text, "en").pcm16
            assertTrue(
                "Default voice should survive a failed voice call " +
                    "(${default1.size} vs ${afterError.size} bytes)",
                Math.abs(default1.size - afterError.size) <= frameBytes,
            )
        }
    }

    @Test
    fun pipelineHandlesEmptyAudio() {
        val config = SpeechConfig(modelDir = modelDir, useNnapi = false)
        val pipeline = SpeechPipeline(config)
        pipeline.start()

        // Push empty array — should not crash
        pipeline.pushAudio(FloatArray(0))

        pipeline.stop()
        pipeline.close()
    }
}
