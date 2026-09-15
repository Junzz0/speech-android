package audio.soniqo.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MeetingModelConfigTest {

    @Test
    fun `the transcriber defaults to LiteRT INT8 with automatic language`() {
        val config = TranscriberConfig(modelDir = "/models")
        assertEquals(SttBackend.LITERT, config.backend)
        assertEquals(ModelPrecision.INT8, config.precision)
        assertEquals("auto", config.language)
        assertFalse(config.hardwareAcceleration)
        config.requireValidConfiguration()
    }

    @Test
    fun `a cn language tag is rejected before the model loads`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            TranscriberConfig(modelDir = "/models", language = "cn").requireValidConfiguration()
        }
        assertTrue(error.message.orEmpty().contains("zh-CN"))
    }

    @Test
    fun `a concrete locale is left for the bundle to resolve`() {
        TranscriberConfig(modelDir = "/models", language = "pt_BR").requireValidConfiguration()
        TranscriberConfig(modelDir = "/models", language = "zh-TW").requireValidConfiguration()
    }

    @Test
    fun `a blank model directory is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            TranscriberConfig(modelDir = " ").requireValidConfiguration()
        }
        assertThrows(IllegalArgumentException::class.java) {
            DiarizerConfig(modelDir = "").requireValidConfiguration()
        }
        assertThrows(IllegalArgumentException::class.java) {
            SpeakerEmbedderConfig(modelDir = "").requireValidConfiguration()
        }
    }

    @Test
    fun `hardware acceleration is opt-in for every standalone model`() {
        assertFalse(DiarizerConfig(modelDir = "/models").hardwareAcceleration)
        assertFalse(SpeakerEmbedderConfig(modelDir = "/models").hardwareAcceleration)
    }

    @Test
    fun `a sample count outside the array is rejected`() {
        val samples = FloatArray(10)
        requireSampleCount(samples, 0)
        requireSampleCount(samples, 10)
        assertThrows(IllegalArgumentException::class.java) { requireSampleCount(samples, 11) }
        assertThrows(IllegalArgumentException::class.java) { requireSampleCount(samples, -1) }
    }
}
