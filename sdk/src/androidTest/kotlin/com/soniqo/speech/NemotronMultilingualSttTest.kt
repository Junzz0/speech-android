package audio.soniqo.speech

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device E2E test for the Nemotron-3.5 ASR streaming multilingual STT
 * (ONNX backend). Unlike ParakeetSttTest this does NOT download from HuggingFace
 * — the bundle is expected to be pre-pushed (via adb) into the app's external
 * files dir under `nemo-models/`, so the test runs offline:
 *
 *   adb push onnx-320-fp16/.  /sdcard/Android/data/audio.soniqo.speech.test/files/nemo-models/
 *   adb push nemo_en.raw      /sdcard/Android/data/audio.soniqo.speech.test/files/nemo-models/
 *
 * Each test skips gracefully (returns) if the bundle isn't present, so the
 * suite stays green on CI runners without the model.
 */
@RunWith(AndroidJUnit4::class)
class NemotronMultilingualSttTest {

    private fun modelDir(): File {
        // Internal filesDir: provisioned via `adb shell run-as … cp` from
        // /data/local/tmp (shell-pushed files in the app's *external* dir are
        // unreadable by the app uid on sdcardfs). App-owned → readable here.
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.filesDir, "nemo-models")
        android.util.Log.i("NemoTest", "modelDir=$dir exists=${dir.exists()} bundle=${hasBundle(dir)}")
        return dir
    }

    private fun hasBundle(dir: File): Boolean =
        File(dir, "encoder.onnx").exists() &&
        File(dir, "decoder.onnx").exists() &&
        File(dir, "joint.onnx").exists() &&
        File(dir, "vocab.json").exists() &&
        File(dir, "languages.json").exists()

    private fun nemotronConfig(dir: File) = SpeechConfig(
        modelDir = dir.absolutePath,
        useNnapi = false,
        sttModel = SttModel.NEMOTRON_MULTILINGUAL,
        sttBackend = SttBackend.ONNX,
        language = "en-US",
    )

    /** The pipeline (VAD + Nemotron STT + TTS) constructs and tears down. */
    @Test
    fun pipelineConstructsWithNemotron() {
        val dir = modelDir()
        if (!hasBundle(dir)) return
        val pipeline = SpeechPipeline(nemotronConfig(dir))
        assertEquals(PipelineState.Idle, pipeline.state)
        pipeline.start()
        assertTrue(
            pipeline.state == PipelineState.Idle ||
            pipeline.state == PipelineState.Listening
        )
        pipeline.stop()
        pipeline.close()
    }

    /** Real English audio → a non-empty transcription mentioning expected words. */
    @Test
    fun transcribesEnglishAudio() = runBlocking {
        val dir = modelDir()
        if (!hasBundle(dir)) return@runBlocking
        val raw = File(dir, "nemo_en.raw")
        if (!raw.exists()) return@runBlocking

        val pipeline = SpeechPipeline(nemotronConfig(dir))
        pipeline.start()

        val bytes = raw.readBytes()
        val samples = FloatArray(bytes.size / 4)
        java.nio.ByteBuffer.wrap(bytes)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .asFloatBuffer().get(samples)

        for (offset in samples.indices step 512) {
            val end = minOf(offset + 512, samples.size)
            pipeline.pushAudio(samples.sliceArray(offset until end))
            delay(8)
        }
        // Trailing silence to trigger end-of-speech.
        val silence = FloatArray(16000)
        for (offset in silence.indices step 512) {
            pipeline.pushAudio(silence.sliceArray(offset until minOf(offset + 512, silence.size)))
            delay(8)
        }

        val event = withTimeout(60_000) {
            pipeline.events.first { it is SpeechEvent.TranscriptionCompleted }
        }
        val tc = event as SpeechEvent.TranscriptionCompleted
        assertTrue("transcription should not be empty", tc.text.isNotBlank())
        val text = tc.text.lowercase()
        val expected = listOf("alloy", "metal", "mixture", "element")
        val matched = expected.count { it in text }
        assertTrue("expected >=1 of $expected in '$text'", matched >= 1)

        pipeline.stop()
        pipeline.close()
    }
}
