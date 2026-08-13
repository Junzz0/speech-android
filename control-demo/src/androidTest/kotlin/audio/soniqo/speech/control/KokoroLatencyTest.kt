package audio.soniqo.speech.control

import android.os.SystemClock
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import audio.soniqo.speech.ModelManager
import audio.soniqo.speech.PipelineMode
import audio.soniqo.speech.SpeechConfig
import audio.soniqo.speech.SpeechPipeline
import audio.soniqo.speech.SpeechSynthesizer
import audio.soniqo.speech.SpeechSynthesizerConfig
import audio.soniqo.speech.TtsModel
import java.io.File
import kotlin.math.ceil
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in physical-device benchmark for the first control-agent speech chunk.
 *
 * Run with `-e runKokoroLatency true`; `-e useNnapi true` selects NNAPI.
 * The demo must have completed its first-run model download beforehand.
 */
@RunWith(AndroidJUnit4::class)
class KokoroLatencyTest {

    @Test
    fun reportsFirstChunkLatency() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "set -e runKokoroLatency true to run the Kokoro latency benchmark",
            args.getString("runKokoroLatency") == "true",
        )

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val modelDir = File(ModelManager.modelDir(context))
        require(File(modelDir, "kokoro-e2e-realtime.onnx").isFile) {
            "Launch the demo to download ${modelDir.absolutePath}"
        }
        require(File(modelDir, "kokoro-e2e.onnx.data").isFile)

        val useNnapi = args.getString("useNnapi")?.toBoolean() == true
        val threads = args.getString("kokoroThreads")
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
        threads?.let {
            Os.setenv("SPEECH_CORE_KOKORO_ORT_THREADS", it.toString(), true)
        }
        val loadStart = SystemClock.elapsedRealtimeNanos()
        SpeechSynthesizer(
            SpeechSynthesizerConfig(
                modelDir = modelDir.absolutePath,
                useNnapi = useNnapi,
                ttsModel = TtsModel.KOKORO_SHORT_TURN,
            ),
        ).use { synthesizer ->
            val loadMs = elapsedMs(loadStart)
            // Exclude first-run allocator/page-fault effects from the warm figures.
            val warmup = synthesizer.synthesize("Ready when you are.", "en")
            assertTrue(warmup.pcm16.isNotEmpty())

            val latencies = buildList {
                repeat(10) {
                    val start = SystemClock.elapsedRealtimeNanos()
                    val result = synthesizer.synthesize("I can call your contacts,", "en")
                    add(elapsedMs(start))
                    assertTrue(result.pcm16.isNotEmpty())
                }
            }.sorted()

            val mean = latencies.average()
            val p50 = percentile(latencies, 0.50)
            val p95 = percentile(latencies, 0.95)
            println(
                "KOKORO_LATENCY backend=${if (useNnapi) "nnapi" else "cpu"} " +
                    "threads=${threads ?: "default"} " +
                    "load_ms=$loadMs samples=${latencies.size} mean_ms=$mean " +
                    "p50_ms=$p50 p95_ms=$p95 max_ms=${latencies.last()}",
            )
        }
    }

    @Test
    fun nativeStreamingDeliversBeforeTheWholeReply() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "set -e runKokoroStreaming true to run the JNI streaming check",
            args.getString("runKokoroStreaming") == "true",
        )

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val modelDir = File(ModelManager.modelDir(context))
        require(File(modelDir, "kokoro-e2e-realtime.onnx").isFile)
        val useNnapi = args.getString("useNnapi")?.toBoolean() == true
        SpeechPipeline(
            SpeechConfig(
                modelDir = modelDir.absolutePath,
                useNnapi = useNnapi,
                pipelineMode = PipelineMode.TRANSCRIBE_ONLY,
            ),
        ).use { pipeline ->
            val start = SystemClock.elapsedRealtimeNanos()
            var firstChunkMs = 0L
            var chunks = 0
            var pcmBytes = 0L
            var sawFinal = false
            pipeline.synthesizeStreaming(
                "I can call your contacts, dial numbers, look up phone numbers, " +
                    "play or stop music,",
                "en",
            ) { chunk, isFinal ->
                if (firstChunkMs == 0L) firstChunkMs = elapsedMs(start)
                chunks++
                pcmBytes += chunk.pcm16.size
                sawFinal = isFinal
            }
            val totalMs = elapsedMs(start)
            println(
                "KOKORO_STREAMING backend=${if (useNnapi) "nnapi" else "cpu"} " +
                    "first_chunk_ms=$firstChunkMs total_ms=$totalMs " +
                    "chunks=$chunks pcm_bytes=$pcmBytes",
            )
            assertTrue("expected retry output to contain multiple chunks", chunks >= 2)
            assertTrue("first chunk must precede buffered completion", firstChunkMs < totalMs)
            assertTrue("final callback missing", sawFinal)
            assertTrue("streamed PCM must not be empty", pcmBytes > 0)
        }
    }

    @Test
    fun optimizedControlReplyAvoidsNativeRetry() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "set -e runOptimizedReply true to run the control-reply check",
            args.getString("runOptimizedReply") == "true",
        )

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val modelDir = File(ModelManager.modelDir(context))
        require(File(modelDir, "kokoro-e2e-realtime.onnx").isFile)
        val useNnapi = args.getString("useNnapi")?.toBoolean() == true
        SpeechPipeline(
            SpeechConfig(
                modelDir = modelDir.absolutePath,
                useNnapi = useNnapi,
                pipelineMode = PipelineMode.TRANSCRIBE_ONLY,
            ),
        ).use { pipeline ->
            val pieces = SpeechChunks.split(ControlTools.CAPABILITIES_SUMMARY)
            val start = SystemClock.elapsedRealtimeNanos()
            var firstChunkMs = 0L
            var nativeChunks = 0
            var pcmBytes = 0L
            for (piece in pieces) {
                pipeline.synthesizeStreaming(piece, "en") { chunk, _ ->
                    if (firstChunkMs == 0L) firstChunkMs = elapsedMs(start)
                    nativeChunks++
                    pcmBytes += chunk.pcm16.size
                }
            }
            val totalMs = elapsedMs(start)
            val audioSeconds = pcmBytes / 2.0 / pipeline.ttsSampleRate
            println(
                "KOKORO_OPTIMIZED backend=${if (useNnapi) "nnapi" else "cpu"} " +
                    "first_chunk_ms=$firstChunkMs total_ms=$totalMs " +
                    "app_pieces=${pieces.size} native_chunks=$nativeChunks " +
                    "audio_s=$audioSeconds synth_rtf=${totalMs / 1_000.0 / audioSeconds}",
            )
            assertTrue("every app piece should need one native run", nativeChunks == pieces.size)
            assertTrue("optimized PCM must not be empty", pcmBytes > 0)
        }
    }

    private fun elapsedMs(startNanos: Long): Long =
        (SystemClock.elapsedRealtimeNanos() - startNanos) / 1_000_000

    private fun percentile(sorted: List<Long>, quantile: Double): Long {
        val index = (ceil(sorted.size * quantile).toInt() - 1).coerceIn(sorted.indices)
        return sorted[index]
    }
}
