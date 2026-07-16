package audio.soniqo.speech.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MicGainTest {

    @Test
    fun silenceRemainsSilent() {
        val samples = FloatArray(512)

        MicGain().process(samples, samples.size)

        assertTrue(samples.all { it == 0f })
    }

    @Test
    fun quietSpeechIsRaisedGradually() {
        val gain = MicGain()
        val samples = FloatArray(512)

        repeat(12) {
            samples.fill(0.01f)
            gain.process(samples, samples.size)
        }

        assertTrue(samples[0] > 0.01f)
        assertTrue(samples[0] <= 0.98f)
    }

    @Test
    fun learnedGainNeverExceedsSafetyClip() {
        val gain = MicGain()
        val samples = FloatArray(512)
        repeat(20) {
            samples.fill(0.005f)
            gain.process(samples, samples.size)
        }
        samples.fill(1f)

        gain.process(samples, samples.size)

        assertTrue(samples.all { it == 0.98f })
    }

    @Test
    fun onlyRequestedPrefixIsModified() {
        val samples = floatArrayOf(0.01f, 0.25f)

        MicGain().process(samples, 1)

        assertTrue(samples[0] > 0.01f)
        assertEquals(0.25f, samples[1], 0f)
    }
}
