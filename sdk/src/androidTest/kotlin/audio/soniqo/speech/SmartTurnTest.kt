package audio.soniqo.speech

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Android integration coverage for speech-core's Smart Turn ONNX backend. */
@RunWith(AndroidJUnit4::class)
class SmartTurnTest {

    private lateinit var modelDir: String

    @Before
    fun setup() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        modelDir = ModelManager.ensureModels(context, enableSmartTurn = true)
    }

    @Test
    fun smartTurnModelIsReadyAndLoadsThroughThePipeline() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(ModelManager.areModelsReady(context, enableSmartTurn = true))

        SpeechPipeline(
            SpeechConfig(
                modelDir = modelDir,
                useNnapi = false,
                enableSmartTurn = true,
            )
        ).use { pipeline ->
            assertEquals(PipelineState.Idle, pipeline.state)
            pipeline.start()
            pipeline.stop()
        }
    }

    @Test
    fun vetoedPauseWaitsForTheConfiguredSilenceCap() = runBlocking {
        val audio = synthesize("Could you tell me the weather today?")
        val pipeline = SpeechPipeline(
            SpeechConfig(
                modelDir = modelDir,
                useNnapi = false,
                enableSmartTurn = true,
                // A sigmoid probability is below 1, so this deliberately makes
                // every model decision exercise the hold path.
                turnCompletionThreshold = 1f,
                turnCompletionMaxSilenceSec = 2f,
            )
        )

        try {
            pipeline.start()
            val ended = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(60_000) {
                    pipeline.events.first { it is SpeechEvent.SpeechEnded }
                }
            }

            pushFrames(pipeline, audio)
            repeat(31) { pipeline.pushAudio(FloatArray(512)) } // ~1 s
            delay(100)
            assertFalse("Smart Turn should hold the first pause", ended.isCompleted)

            repeat(47) { pipeline.pushAudio(FloatArray(512)) } // total silence ~2.5 s
            assertNotNull("the maximum-silence cap must settle the turn", ended.await())
        } finally {
            pipeline.stop()
            pipeline.close()
        }
    }

    private fun synthesize(text: String): FloatArray =
        SpeechSynthesizer(SpeechSynthesizerConfig(modelDir = modelDir, useNnapi = false)).use {
            val result = it.synthesize(text, "en")
            val shorts = ShortArray(result.pcm16.size / 2)
            ByteBuffer.wrap(result.pcm16)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
                .get(shorts)
            val source = FloatArray(shorts.size) { index -> shorts[index] / 32768f }
            if (result.sampleRate == 16000) return@use source

            val outputSize = ((source.size.toLong() * 16000L) / result.sampleRate).toInt()
            FloatArray(outputSize) { index ->
                val sourceIndex = index.toDouble() * result.sampleRate / 16000.0
                val lower = sourceIndex.toInt().coerceIn(0, source.lastIndex)
                val upper = minOf(lower + 1, source.lastIndex)
                val fraction = (sourceIndex - lower).toFloat()
                source[lower] * (1f - fraction) + source[upper] * fraction
            }
        }

    private fun pushFrames(pipeline: SpeechPipeline, audio: FloatArray) {
        for (offset in audio.indices step 512) {
            val end = minOf(offset + 512, audio.size)
            if (end - offset == 512) {
                pipeline.pushAudio(audio.sliceArray(offset until end))
            }
        }
    }
}
