package audio.soniqo.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VadConfigTest {

    @Test
    fun `defaults match the pipeline's turn detection`() {
        val config = VadConfig()
        assertEquals(0.5f, config.onsetThreshold, 0f)
        assertEquals(0.35f, config.offsetThreshold, 0f)
        assertEquals(0.25f, config.minSpeechDurationSec, 0f)
        // Same value SpeechConfig uses, so a caller moving between the two
        // does not silently get different segment boundaries.
        assertEquals(
            SpeechConfig().endOfSpeechSilenceSec,
            config.endOfSpeechSilenceSec,
            0f,
        )
    }

    @Test
    fun `utterance audio is opt-in`() {
        assertEquals(false, VadConfig().emitUtteranceAudio)
    }

    @Test
    fun `an offset above the onset is rejected instead of chattering`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            VadConfig(onsetThreshold = 0.4f, offsetThreshold = 0.6f)
                .requireValidThresholds()
        }

        assertTrue(error.message.orEmpty().contains("offsetThreshold"))
        assertTrue(error.message.orEmpty().contains("hysteresis"))
    }

    @Test
    fun `zero end-of-speech silence is rejected`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            VadConfig(endOfSpeechSilenceSec = 0f).requireValidThresholds()
        }

        assertTrue(error.message.orEmpty().contains("endOfSpeechSilenceSec"))
    }

    @Test
    fun `an onset above one can never fire`() {
        assertThrows(IllegalArgumentException::class.java) {
            VadConfig(onsetThreshold = 1.5f).requireValidThresholds()
        }
    }

    @Test
    fun `negative durations are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            VadConfig(minSpeechDurationSec = -1f).requireValidThresholds()
        }
        assertThrows(IllegalArgumentException::class.java) {
            VadConfig(preSpeechBufferSec = -1f).requireValidThresholds()
        }
        assertThrows(IllegalArgumentException::class.java) {
            VadConfig(maxUtteranceDurationSec = -1f).requireValidThresholds()
        }
    }

    @Test
    fun `equal onset and offset is allowed`() {
        VadConfig(onsetThreshold = 0.5f, offsetThreshold = 0.5f).requireValidThresholds()
    }
}
