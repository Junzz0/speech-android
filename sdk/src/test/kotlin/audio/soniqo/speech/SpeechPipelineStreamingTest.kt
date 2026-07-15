package audio.soniqo.speech

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechPipelineStreamingTest {

    @Test
    fun defaultAdapterEmitsOneFinalBufferedChunk() {
        val expected = byteArrayOf(1, 2, 3, 4)
        val pipeline = BufferedFakePipeline(expected)
        var callbackCount = 0

        pipeline.synthesizeStreaming("hello") { result, isFinal ->
            callbackCount++
            assertArrayEquals(expected, result.pcm16)
            assertTrue(isFinal)
        }

        assertTrue(callbackCount == 1)
    }

    private class BufferedFakePipeline(
        private val audio: ByteArray,
    ) : SpeechPipeline {
        override val events: SharedFlow<SpeechEvent> = MutableSharedFlow()
        override val state: PipelineState = PipelineState.Idle
        override val nnapiFallbackReason: String? = null

        override fun start() = Unit
        override fun stop() = Unit
        override fun pushAudio(samples: FloatArray) = Unit
        override fun resumeListening() = Unit
        override fun synthesize(text: String, language: String) =
            SpeechSynthesisResult(sampleRate = 24_000, pcm16 = audio)
        override fun close() = Unit
    }
}
