package audio.soniqo.speech.control

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import audio.soniqo.speech.ModelManager
import audio.soniqo.speech.PipelineMode
import audio.soniqo.speech.SpeechConfig
import audio.soniqo.speech.SpeechPipeline
import audio.soniqo.speech.TtsModel
import java.io.File
import kotlin.math.ceil
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in physical-device gate for Pocket's real 80 ms streaming callbacks. */
@RunWith(AndroidJUnit4::class)
class PocketTtsLatencyTest {

    @Test
    fun streamsFramesBelowInteractiveLatencyGate() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "set -e runPocketLatency true to run the Pocket latency benchmark",
            args.getString("runPocketLatency") == "true",
        )

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val modelDir = File(ModelManager.modelDir(context, ttsModel = TtsModel.POCKET))
        val pocketDir = File(modelDir, "pocket_tts")
        for (name in REQUIRED_FILES) {
            require(File(pocketDir, name).isFile) {
                "Launch the Pocket control demo to download ${pocketDir.absolutePath}/$name"
            }
        }

        val loadStart = SystemClock.elapsedRealtimeNanos()
        SpeechPipeline(
            SpeechConfig(
                modelDir = modelDir.absolutePath,
                useNnapi = false,
                ttsModel = TtsModel.POCKET,
                pipelineMode = PipelineMode.TRANSCRIBE_ONLY,
                language = "en",
            ),
        ).use { pipeline ->
            val loadMs = elapsedMs(loadStart)
            assertEquals(24_000, pipeline.ttsSampleRate)

            // Exclude session/page-fault warm-up from the warm interaction gate.
            synthesizeAndMeasure(pipeline, "Ready when you are.")

            val results = buildList {
                repeat(10) {
                    add(synthesizeAndMeasure(pipeline, "I can call your contacts,"))
                }
            }
            val firstAudio = results.map { it.firstAudioMs }.sorted()
            val totals = results.map { it.totalMs }.sorted()
            val mean = firstAudio.average()
            val p50 = percentile(firstAudio, 0.50)
            val p95 = percentile(firstAudio, 0.95)
            val max = firstAudio.last()
            val medianRtf = results.map { it.rtf }.sorted()[results.size / 2]

            println(
                "POCKET_LATENCY backend=cpu threads=2 flow_steps=4 " +
                    "load_ms=$loadMs samples=${results.size} mean_ms=$mean " +
                    "p50_ms=$p50 p95_ms=$p95 max_ms=$max " +
                    "total_p50_ms=${percentile(totals, 0.50)} " +
                    "total_p95_ms=${percentile(totals, 0.95)} " +
                    "median_rtf=$medianRtf",
            )
            assertTrue("Pocket warm TTFA p95 must stay below 300 ms, was $p95 ms", p95 < 300)
        }
    }

    @Test
    fun playsFramesThroughOneContinuousAudioTrack() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "set -e runPocketPlayback true to run audible Pocket playback",
            args.getString("runPocketPlayback") == "true",
        )

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val modelDir = File(ModelManager.modelDir(context, ttsModel = TtsModel.POCKET))
        require(File(modelDir, "pocket_tts/lm_main.int8.onnx").isFile)
        SpeechPipeline(
            SpeechConfig(
                modelDir = modelDir.absolutePath,
                useNnapi = false,
                ttsModel = TtsModel.POCKET,
                pipelineMode = PipelineMode.TRANSCRIBE_ONLY,
                language = "en",
            ),
        ).use { pipeline ->
            synthesizeAndMeasure(pipeline, "Ready when you are.")

            val prepareStarted = SystemClock.elapsedRealtimeNanos()
            val player = StreamingPcmPlayer(pipeline.ttsSampleRate)
            val prepareMs = elapsedMs(prepareStarted)
            try {
                repeat(2) { turnIndex ->
                    val started = SystemClock.elapsedRealtimeNanos()
                    var callbackMs = 0L
                    var playbackStartMs = 0L
                    var nativeFrames = 0
                    var pcmBytes = 0L
                    pipeline.synthesizeStreaming(
                        if (turnIndex == 0) {
                            "Pocket TTS is now running on your phone."
                        } else {
                            "The warm audio path is ready for another turn."
                        },
                        "en",
                    ) { chunk, _ ->
                        if (chunk.pcm16.isEmpty()) return@synthesizeStreaming
                        if (callbackMs == 0L) callbackMs = elapsedMs(started)
                        nativeFrames++
                        pcmBytes += chunk.pcm16.size
                        if (playbackStartMs == 0L) {
                            assertEquals(player.sampleRate, chunk.sampleRate)
                            player.start(chunk.pcm16)
                            playbackStartMs = elapsedMs(started)
                        } else {
                            player.write(chunk.pcm16)
                        }
                    }
                    val firstPresentationNanos = player.awaitFirstPresentationNanos()
                    player.awaitDrained()
                    val presentationMs = firstPresentationNanos?.let {
                        ((it - started) / 1_000_000).coerceAtLeast(playbackStartMs)
                    }
                    val perf = if (
                        player.performanceMode == android.media.AudioTrack.PERFORMANCE_MODE_LOW_LATENCY
                    ) "low-latency" else "normal"
                    val audioSeconds = pcmBytes / 2.0 / pipeline.ttsSampleRate
                    println(
                        "POCKET_PLAYBACK turn=${turnIndex + 1} prepare_ms=$prepareMs " +
                            "callback_ms=$callbackMs playback_start_ms=$playbackStartMs " +
                            "first_presentation_ms=${presentationMs ?: "unavailable"} " +
                            "frames=$nativeFrames audio_s=$audioSeconds " +
                            "output=$perf buffer_bytes=${player.bufferSizeBytes} " +
                            "underruns=${player.underrunCount}",
                    )
                    assertTrue("Pocket callback exceeded 300 ms: $callbackMs", callbackMs < 300)
                    assertTrue(
                        "AudioTrack start exceeded 350 ms: $playbackStartMs",
                        playbackStartMs < 350,
                    )
                    assertTrue(
                        "Audio presentation timestamp unavailable or too slow: $presentationMs",
                        presentationMs != null && presentationMs < 450,
                    )
                    assertEquals("continuous playback must not underrun", 0, player.underrunCount)
                    player.resetForNextUtterance()
                }
            } finally {
                player.close()
            }
        }
    }

    private fun synthesizeAndMeasure(pipeline: SpeechPipeline, text: String): Result {
        val start = SystemClock.elapsedRealtimeNanos()
        var firstAudioMs = 0L
        var frames = 0
        var pcmBytes = 0L
        var sawFinal = false
        pipeline.synthesizeStreaming(text, "en") { chunk, isFinal ->
            if (chunk.pcm16.isNotEmpty()) {
                if (firstAudioMs == 0L) firstAudioMs = elapsedMs(start)
                assertEquals("Pocket frames must contain 80 ms at 24 kHz", 3_840, chunk.pcm16.size)
                frames++
                pcmBytes += chunk.pcm16.size
            }
            if (isFinal) sawFinal = true
        }
        val totalMs = elapsedMs(start)
        assertTrue("Pocket emitted no audio", frames > 0)
        assertTrue("Pocket final callback missing", sawFinal)
        val audioSeconds = pcmBytes / 2.0 / pipeline.ttsSampleRate
        return Result(
            firstAudioMs = firstAudioMs,
            totalMs = totalMs,
            rtf = totalMs / 1_000.0 / audioSeconds,
        )
    }

    private fun elapsedMs(startNanos: Long): Long =
        (SystemClock.elapsedRealtimeNanos() - startNanos) / 1_000_000

    private fun percentile(sorted: List<Long>, quantile: Double): Long {
        val index = (ceil(sorted.size * quantile).toInt() - 1).coerceIn(sorted.indices)
        return sorted[index]
    }

    private data class Result(
        val firstAudioMs: Long,
        val totalMs: Long,
        val rtf: Double,
    )

    private companion object {
        val REQUIRED_FILES = listOf(
            "decoder.int8.onnx",
            "encoder.onnx",
            "lm_flow.int8.onnx",
            "lm_main.int8.onnx",
            "text_conditioner.onnx",
            "token_scores.json",
            "vocab.json",
        )
    }
}
