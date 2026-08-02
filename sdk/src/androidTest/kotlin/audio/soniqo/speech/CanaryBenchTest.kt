package audio.soniqo.speech

import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

/**
 * Measures Canary's decode cost and resident footprint on device. Reports
 * rather than asserts, and is the source of the figures quoted for Canary in
 * the README model table — the numbers should be reproducible from the repo
 * that publishes them.
 *
 * RTF is wall-time / audio-seconds — lower is faster, <1.0 beats real time.
 * `sttMs` from `TranscriptionCompleted` is the engine's own decode time, so
 * its RTF is the model's. The wall clock additionally carries end-of-turn
 * detection and pipeline buffering, and is reported only for contrast: it is
 * not a clean end-to-end latency measurement and should not be quoted as one.
 *
 * Three durations, not one. Every utterance pays a fixed end-of-turn cost, so
 * a single length cannot separate that constant from the part that scales with
 * audio. Decode RTF rises with utterance length on this model — an attention
 * encoder-decoder pays quadratic attention over the encoder sequence and emits
 * every token autoregressively — so one number would misrepresent it either
 * way it were rounded.
 *
 * Audio is synthesized speech, not a tone: the VAD may never open a turn for a
 * sine wave, and if it does the decoder has nothing to transcribe. The
 * transcript is asserted non-empty so a run that measured a no-op fails
 * instead of reporting a very good RTF.
 */
@RunWith(AndroidJUnit4::class)
class CanaryBenchTest {

    @Test
    fun measureCanary() {
        runBlocking {
            try {
                Log.i(TAG, "BENCH step=start")
                val ctx = InstrumentationRegistry.getInstrumentation().targetContext
                val dir = ModelManager.ensureModels(ctx, sttModel = SttModel.CANARY)
                Log.i(TAG, "BENCH step=models dir=$dir")

                val heapBefore = nativeHeapMb()
                val pssBefore = pssMb()
                val t0 = System.currentTimeMillis()
                val pipeline = SpeechPipeline(
                    SpeechConfig(modelDir = dir, useNnapi = false,
                                 sttModel = SttModel.CANARY, language = "en")
                )
                pipeline.start()
                val loadMs = System.currentTimeMillis() - t0
                val heapAfterLoad = nativeHeapMb()
                val pssAfterLoad = pssMb()
                Log.i(TAG, "BENCH step=loaded ms=$loadMs " +
                    "heap=$heapBefore->$heapAfterLoad MB pss=$pssBefore->$pssAfterLoad MB")

                // Synthesized after the pipeline is up, matching CanarySttTest.
                // An earlier revision built it first and aborted with an ORT
                // type error, but building it first in isolation survives — the
                // order is not the trigger. This only keeps both tests on the
                // same sequence.
                val clip = synthesizeClip(dir)
                Log.i(TAG, "BENCH step=fixture samples=${clip.size}")

                var peakHeap = heapAfterLoad
                var peakPss = pssAfterLoad
                val rows = mutableListOf<String>()

                try {
                    for (seconds in DURATIONS) {
                        val audio = FloatArray(SAMPLE_RATE * seconds) { i -> clip[i % clip.size] }
                        val runs = mutableListOf<Long>()
                        val sttRuns = mutableListOf<Long>()

                        // Pass 0 is warm-up: it pays for decoder buffers that
                        // every later utterance reuses.
                        repeat(WARMUP + REPEATS) { pass ->
                            val (sttMs, wallMs) = transcribeOnce(pipeline, audio)
                            if (pass >= WARMUP) {
                                runs += wallMs
                                sttRuns += sttMs
                            }
                            peakHeap = maxOf(peakHeap, nativeHeapMb())
                            peakPss = maxOf(peakPss, pssMb())
                            Log.i(TAG, "BENCH pass=$pass audio=${seconds}s stt=${sttMs}ms wall=${wallMs}ms")
                            pipeline.resumeListening()
                        }

                        val sttMedian = sttRuns.sorted()[sttRuns.size / 2]
                        val wallMedian = runs.sorted()[runs.size / 2]
                        val sttRtf = sttMedian.toDouble() / (seconds * 1000.0)
                        val wallRtf = wallMedian.toDouble() / (seconds * 1000.0)
                        rows += "audio=${seconds}s stt=${sttMedian}ms rtf=${"%.3f".format(sttRtf)} " +
                            "wall=${wallMedian}ms wallRtf=${"%.3f".format(wallRtf)} " +
                            "sttRuns=$sttRuns wallRuns=$runs"
                        Log.i(TAG, "BENCH ROW ${rows.last()}")
                    }
                } finally {
                    pipeline.stop(); pipeline.close()
                }

                Log.i(TAG, "BENCH RESULT load=${loadMs}ms " +
                    "heap=${heapBefore}->${heapAfterLoad} peak=${peakHeap}MB " +
                    "pss=${pssBefore}->${pssAfterLoad} peak=${peakPss}MB")
                rows.forEach { Log.i(TAG, "BENCH RESULT $it") }
            } catch (t: Throwable) {
                Log.e(TAG, "BENCH FAILED: ${t::class.java.name}: ${t.message}", t)
                throw t
            }
        }
    }

    /**
     * One utterance. Returns the engine's own STT milliseconds and the wall
     * milliseconds between the last speech sample entering the pipeline and the
     * transcript coming back.
     *
     * Speech is paced at real time so the VAD sees the turn the way it would
     * from a microphone. The trailing silence is pushed as fast as the pipeline
     * takes it — pacing that would add its own duration to the result, which is
     * time spent waiting, not work done.
     */
    private suspend fun transcribeOnce(
        pipeline: SpeechPipeline,
        audio: FloatArray,
    ): Pair<Long, Long> {
        val completed = CoroutineScope(coroutineContext).async {
            withTimeout(TIMEOUT_MS) {
                pipeline.events.first { it is SpeechEvent.TranscriptionCompleted }
            }
        }

        for (off in audio.indices step CHUNK) {
            pipeline.pushAudio(audio.sliceArray(off until minOf(off + CHUNK, audio.size)))
            delay(CHUNK_MS)
        }

        val start = System.currentTimeMillis()
        val silence = FloatArray(SAMPLE_RATE)
        for (off in silence.indices step CHUNK) {
            pipeline.pushAudio(silence.sliceArray(off until minOf(off + CHUNK, silence.size)))
        }

        val event = completed.await() as SpeechEvent.TranscriptionCompleted
        val elapsed = System.currentTimeMillis() - start
        assertTrue("Canary returned no transcript — nothing was measured", event.text.isNotBlank())
        return event.sttMs.toLong() to elapsed
    }

    private fun synthesizeClip(dir: String): FloatArray {
        val synthesizer = SpeechSynthesizer(
            SpeechSynthesizerConfig(modelDir = dir, useNnapi = false)
        )
        return synthesizer.use {
            val spoken = it.synthesize(
                "The quick brown fox jumps over the lazy dog while the clock strikes nine.",
                "en"
            )
            assertTrue("Synthesized fixture should not be empty", spoken.pcm16.isNotEmpty())
            pcm16ToFloat16k(spoken.pcm16, spoken.sampleRate)
        }
    }

    private fun nativeHeapMb() = (Debug.getNativeHeapAllocatedSize() / (1024 * 1024)).toInt()

    /**
     * Proportional set size — the quantity the README's peak-memory column
     * reports. Native heap alone misses mmapped weights and the Java heap.
     */
    private fun pssMb(): Int {
        val line = File("/proc/self/smaps_rollup").useLines { lines ->
            lines.firstOrNull { it.startsWith("Pss:") }
        } ?: return 0
        val kb = line.removePrefix("Pss:").trim().removeSuffix("kB").trim().toLongOrNull() ?: return 0
        return (kb / 1024).toInt()
    }

    private fun pcm16ToFloat16k(pcm16: ByteArray, sourceSampleRate: Int): FloatArray {
        val shorts = ShortArray(pcm16.size / 2)
        ByteBuffer.wrap(pcm16)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(shorts)
        val source = FloatArray(shorts.size) { i -> shorts[i] / 32768.0f }
        if (sourceSampleRate == SAMPLE_RATE) return source

        val outputSize = ((source.size.toLong() * SAMPLE_RATE) / sourceSampleRate).toInt()
        return FloatArray(outputSize) { i ->
            val src = i.toDouble() * sourceSampleRate.toDouble() / SAMPLE_RATE
            val lo = src.toInt().coerceIn(0, source.lastIndex)
            val hi = (lo + 1).coerceAtMost(source.lastIndex)
            val frac = (src - lo).toFloat()
            source[lo] * (1f - frac) + source[hi] * frac
        }
    }

    private companion object {
        const val TAG = "CanaryBench"
        const val SAMPLE_RATE = 16000
        const val CHUNK = 512
        const val CHUNK_MS = 32L // 512 samples @ 16 kHz
        val DURATIONS = listOf(3, 6, 12)
        const val WARMUP = 1
        const val REPEATS = 3
        const val TIMEOUT_MS = 180_000L
    }
}
