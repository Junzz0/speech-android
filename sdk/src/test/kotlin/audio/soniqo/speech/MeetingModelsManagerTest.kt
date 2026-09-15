package audio.soniqo.speech

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile

/**
 * The standalone meeting-transcription sets — [StreamingTranscriber],
 * [SpeakerDiarizer], [SpeakerEmbedder] — and the download origin override
 * every set shares.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MeetingModelsManagerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ModelManager.endpoint = ModelManager.DEFAULT_ENDPOINT
        listOf(
            ModelManager.transcriberModelDir(context),
            ModelManager.diarizerModelDir(context),
            ModelManager.speakerEmbeddingModelDir(context),
            ModelManager.vadModelDir(context),
        ).forEach { File(it).deleteRecursively() }
    }

    @After
    fun tearDown() {
        ModelManager.endpoint = ModelManager.DEFAULT_ENDPOINT
    }

    @Test
    fun `the default endpoint keeps the Hugging Face URL`() {
        assertEquals(
            "https://huggingface.co/soniqo/Silero-VAD-v5-ONNX/resolve/main/silero-vad.onnx",
            ModelManager.modelUrl(ModelManager.vadModels().single()),
        )
    }

    @Test
    fun `a mirror endpoint replaces only the origin`() {
        ModelManager.endpoint = "https://hf-mirror.com/"

        assertEquals("https://hf-mirror.com", ModelManager.endpoint)
        assertEquals(
            "https://hf-mirror.com/soniqo/Sortformer-Diarization-4spk-ONNX/resolve/" +
                "a7176b247fb7df5588414c20632f584d4f8562c8/sortformer-default.onnx",
            ModelManager.modelUrl(ModelManager.diarizerModels().first()),
        )
    }

    @Test
    fun `an endpoint that is not an https URL is rejected and the old one kept`() {
        val rejected = listOf(
            "",
            "hf-mirror.com",
            "ftp://hf-mirror.com",
            "http://hf-mirror.com",
            "https://hf-mirror.com/?token=1",
            "https://hf-mirror.com/#models",
            "https://user@hf-mirror.com",
        )
        for (value in rejected) {
            assertThrows("'$value' should be rejected", IllegalArgumentException::class.java) {
                ModelManager.endpoint = value
            }
        }
        assertEquals(ModelManager.DEFAULT_ENDPOINT, ModelManager.endpoint)
    }

    @Test
    fun `plain http is accepted only for a loopback server`() {
        ModelManager.endpoint = "http://127.0.0.1:8080/mirror"
        assertEquals("http://127.0.0.1:8080/mirror", ModelManager.endpoint)
        ModelManager.endpoint = "http://localhost:9000"
        assertEquals("http://localhost:9000", ModelManager.endpoint)
    }

    @Test
    fun `downloads come from the configured endpoint`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            // Silero's floor is 500 KB and its header must carry ONNX magic.
            val body = ByteArray(600_000).also { it[0] = 0x08 }
            server.enqueue(MockResponse().setBody(Buffer().write(body)))
            ModelManager.endpoint = server.url("/mirror").toString()

            ModelManager.ensureVadModels(context)

            assertEquals(
                "/mirror/soniqo/Silero-VAD-v5-ONNX/resolve/main/silero-vad.onnx",
                server.takeRequest().path,
            )
            assertTrue(ModelManager.areVadModelsReady(context))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `transcriber manifests name each Nemotron bundle`() {
        val base = "Nemotron-3.5-ASR-Streaming-Multilingual-0.6B"
        val liteRtFiles = listOf(
            "nemotron-multilingual-encoder.tflite",
            "nemotron-multilingual-decoder.tflite",
            "nemotron-multilingual-joint.tflite",
            "vocab.json", "languages.json", "io_map.json", "config.json",
        )

        assertEquals(
            liteRtFiles.map { ModelManager.ModelFile("$base-LiteRT-INT8", it, "v1.0.0") },
            ModelManager.transcriberModels(SttBackend.LITERT, ModelPrecision.INT8),
        )
        assertEquals(
            liteRtFiles.map {
                ModelManager.ModelFile(
                    "$base-LiteRT-FP16", it, "1503a9a1eb75b813b83ba65bf5e9fecea4a46091",
                )
            },
            ModelManager.transcriberModels(SttBackend.LITERT, ModelPrecision.FP32),
        )
        val onnx = ModelManager.transcriberModels(SttBackend.ONNX, ModelPrecision.INT8)
        assertTrue(onnx.all { it.repo == "$base-ONNX-FP16" })
        assertEquals(9, onnx.size)
        assertEquals(onnx, ModelManager.transcriberModels(SttBackend.ONNX, ModelPrecision.FP32))
    }

    @Test
    fun `the pipeline and the standalone transcriber share one Nemotron manifest`() {
        for (backend in SttBackend.entries) {
            for (precision in ModelPrecision.entries) {
                val pipeline = ModelManager.models(
                    precision,
                    sttModel = SttModel.NEMOTRON_MULTILINGUAL,
                    sttBackend = backend,
                ).filter { it.repo.startsWith("Nemotron-") }
                assertEquals(ModelManager.transcriberModels(backend, precision), pipeline)
            }
        }
    }

    @Test
    fun `diarizer and speaker embedding manifests are pinned`() {
        assertEquals(
            listOf(
                ModelManager.ModelFile(
                    "Sortformer-Diarization-4spk-ONNX", "sortformer-default.onnx",
                    "a7176b247fb7df5588414c20632f584d4f8562c8",
                ),
                ModelManager.ModelFile(
                    "Sortformer-Diarization-4spk-ONNX", "config.json",
                    "a7176b247fb7df5588414c20632f584d4f8562c8",
                ),
            ),
            ModelManager.diarizerModels(),
        )
        assertEquals(
            listOf(
                ModelManager.ModelFile(
                    "ReDimNet2-B6-ONNX-FP32", "ReDimNet2B6.onnx",
                    "e911e3f063899805f3d94ee5d1db53fff8e9f3e8",
                ),
                ModelManager.ModelFile(
                    "ReDimNet2-B6-ONNX-FP32", "LICENSE",
                    "e911e3f063899805f3d94ee5d1db53fff8e9f3e8",
                ),
            ),
            ModelManager.speakerEmbeddingModels(),
        )
    }

    @Test
    fun `each standalone set has a directory of its own`() {
        val dirs = listOf(
            ModelManager.transcriberModelDir(context, SttBackend.LITERT, ModelPrecision.INT8),
            ModelManager.transcriberModelDir(context, SttBackend.LITERT, ModelPrecision.FP32),
            ModelManager.transcriberModelDir(context, SttBackend.ONNX, ModelPrecision.INT8),
            ModelManager.diarizerModelDir(context),
            ModelManager.speakerEmbeddingModelDir(context),
            ModelManager.vadModelDir(context),
            ModelManager.ttsModelDir(context),
            ModelManager.modelDir(context),
        )
        assertEquals(dirs.size, dirs.toSet().size)
        // The ONNX export has one precision, so both requests share its cache.
        assertEquals(
            ModelManager.transcriberModelDir(context, SttBackend.ONNX, ModelPrecision.INT8),
            ModelManager.transcriberModelDir(context, SttBackend.ONNX, ModelPrecision.FP32),
        )
    }

    @Test
    fun `set keys tell the transcriber bundles apart`() {
        val keys = setOf(
            ModelManager.transcriberModelSetKey(SttBackend.LITERT, ModelPrecision.INT8),
            ModelManager.transcriberModelSetKey(SttBackend.LITERT, ModelPrecision.FP32),
            ModelManager.transcriberModelSetKey(SttBackend.ONNX, ModelPrecision.INT8),
            ModelManager.diarizerModelSetKey(),
            ModelManager.speakerEmbeddingModelSetKey(),
        )
        assertEquals(5, keys.size)
    }

    @Test
    fun `a standalone set is ready only when every file is complete`() {
        val dir = File(ModelManager.diarizerModelDir(context))
        assertFalse(ModelManager.areDiarizerModelsReady(context))

        writeMarkers(dir, ModelManager.diarizerModelSetKey())
        writeValid(dir, "sortformer-default.onnx", 474_630_246L)
        assertFalse("config.json is still missing", ModelManager.areDiarizerModelsReady(context))

        File(dir, "config.json").writeText("{\"chunk_len\": 340}")
        assertTrue(ModelManager.areDiarizerModelsReady(context))

        writeValid(dir, "sortformer-default.onnx", 1_000_000L)
        assertFalse("a truncated graph is not ready", ModelManager.areDiarizerModelsReady(context))
    }

    @Test
    fun `a set cached under another key is not ready`() {
        val dir = File(ModelManager.speakerEmbeddingModelDir(context))
        writeMarkers(dir, ModelManager.diarizerModelSetKey())
        writeValid(dir, "ReDimNet2B6.onnx", 51_223_453L)
        File(dir, "LICENSE").writeText("MIT")

        assertFalse(ModelManager.areSpeakerEmbeddingModelsReady(context))

        writeMarkers(dir, ModelManager.speakerEmbeddingModelSetKey())
        assertTrue(ModelManager.areSpeakerEmbeddingModelsReady(context))
    }

    @Test
    fun `a fresh install plans each standalone download`() {
        val transcriber = ModelManager.plannedTranscriberBytes(context)
        assertTrue("expected ~721 MB, got $transcriber", transcriber in 715_000_000L..730_000_000L)

        val diarizer = ModelManager.plannedDiarizerBytes(context)
        assertTrue("expected ~475 MB, got $diarizer", diarizer in 474_000_000L..476_000_000L)

        val embedding = ModelManager.plannedSpeakerEmbeddingBytes(context)
        assertTrue("expected ~51 MB, got $embedding", embedding in 51_000_000L..52_000_000L)

        val vad = ModelManager.plannedVadBytes(context)
        assertTrue("expected ~2 MB, got $vad", vad in 2_000_000L..2_500_000L)
    }

    @Test
    fun `a partial standalone download keeps its planned bytes`() {
        // Markers are written before the transfer, so a directory holding them
        // and one finished file is an interrupted download, not a stale cache.
        val dir = File(ModelManager.speakerEmbeddingModelDir(context))
        writeMarkers(dir, ModelManager.speakerEmbeddingModelSetKey())
        File(dir, "LICENSE").writeText("MIT")

        assertEquals(51_223_453L, ModelManager.plannedSpeakerEmbeddingBytes(context))
    }

    @Test
    fun `complete FP16 LiteRT files pass their bundle's floors`() {
        val dir = context.cacheDir
        val fp16 = ModelManager.transcriberModels(SttBackend.LITERT, ModelPrecision.FP32)
        val int8 = ModelManager.transcriberModels(SttBackend.LITERT, ModelPrecision.INT8)
        for ((name, fp16Size) in listOf(
            "nemotron-multilingual-decoder.tflite" to 29_915_736L,
            "nemotron-multilingual-joint.tflite" to 18_943_768L,
        )) {
            val file = writeValid(dir, name, fp16Size)
            assertTrue(
                "a complete FP16 $name is valid",
                ModelManager.isValidModel(file, name, fp16.first { it.filename == name }.repo),
            )
            // The same size is a truncated INT8 file and keeps failing.
            assertFalse(
                "an FP16-sized INT8 $name is truncated",
                ModelManager.isValidModel(file, name, int8.first { it.filename == name }.repo),
            )
        }
    }

    @Test
    fun `transcriber directories are named after their bundle`() {
        assertEquals(
            "models_transcriber-litert-int8",
            ModelManager.transcriberModelDirName(SttBackend.LITERT, ModelPrecision.INT8),
        )
        assertNotEquals(
            ModelManager.transcriberModelDirName(SttBackend.LITERT, ModelPrecision.INT8),
            ModelManager.transcriberModelDirName(SttBackend.LITERT, ModelPrecision.FP32),
        )
    }

    private fun writeMarkers(dir: File, modelSetKey: String) {
        dir.mkdirs()
        File(dir, "model-set.txt").writeText(modelSetKey)
        File(dir, "version.txt").writeText(modelSetKey.substringBefore('|').removePrefix("v"))
    }

    /** ONNX magic up front, then a sparse extent to clear the size floor. */
    private fun writeValid(dir: File, name: String, size: Long): File {
        val file = File(dir, name)
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            raf.write(byteArrayOf(0x08, 0x00))
            if (size > 2) raf.setLength(size)
        }
        return file
    }
}
