package audio.soniqo.speech

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The voice-preset overloads must stay source-compatible with custom
 * [SpeechPipeline] / [SpeechSynthesizer] implementations that only know the
 * two-argument `synthesize`: the interface defaults route the voice variants
 * through the voice-agnostic call.
 */
class SpeechSynthesisVoiceTest {

    @Test
    fun pipelineVoiceOverloadFallsBackToVoiceAgnosticSynthesis() {
        val expected = byteArrayOf(9, 8, 7)
        val pipeline = VoiceAgnosticPipeline(expected)

        val result = pipeline.synthesize("hello", "en", "M1")

        assertArrayEquals(expected, result.pcm16)
        assertEquals(listOf("hello" to "en"), pipeline.calls)
    }

    @Test
    fun pipelineVoiceStreamingOverloadEmitsOneFinalChunk() {
        val expected = byteArrayOf(1, 2)
        val pipeline = VoiceAgnosticPipeline(expected)
        val chunks = mutableListOf<Pair<ByteArray, Boolean>>()

        pipeline.synthesizeStreaming("hi", "fr", "ff_siwis") { result, isFinal ->
            chunks += result.pcm16 to isFinal
        }

        assertEquals(1, chunks.size)
        assertArrayEquals(expected, chunks.single().first)
        assertEquals(true, chunks.single().second)
        assertEquals(listOf("hi" to "fr"), pipeline.calls)
    }

    @Test
    fun synthesizerVoiceOverloadFallsBackToVoiceAgnosticSynthesis() {
        val expected = byteArrayOf(4, 4, 4, 4)
        val synthesizer = VoiceAgnosticSynthesizer(expected)

        val result = synthesizer.synthesize("hello", "en", "F2")

        assertArrayEquals(expected, result.pcm16)
        assertEquals(24_000, result.sampleRate)
        assertEquals(listOf("hello" to "en"), synthesizer.calls)
    }

    private class VoiceAgnosticPipeline(private val audio: ByteArray) : SpeechPipeline {
        val calls = mutableListOf<Pair<String, String>>()

        override val events: SharedFlow<SpeechEvent> = MutableSharedFlow()
        override val state: PipelineState = PipelineState.Idle
        override val nnapiFallbackReason: String? = null

        override fun start() = Unit
        override fun stop() = Unit
        override fun pushAudio(samples: FloatArray) = Unit
        override fun resumeListening() = Unit
        override fun synthesize(text: String, language: String): SpeechSynthesisResult {
            calls += text to language
            return SpeechSynthesisResult(sampleRate = 24_000, pcm16 = audio)
        }
        override fun close() = Unit
    }

    private class VoiceAgnosticSynthesizer(private val audio: ByteArray) : SpeechSynthesizer {
        val calls = mutableListOf<Pair<String, String>>()

        override val sampleRate: Int = 24_000

        override fun synthesize(text: String, language: String): SpeechSynthesisResult {
            calls += text to language
            return SpeechSynthesisResult(sampleRate = sampleRate, pcm16 = audio)
        }
        override fun stop() = Unit
        override fun close() = Unit
    }
}
