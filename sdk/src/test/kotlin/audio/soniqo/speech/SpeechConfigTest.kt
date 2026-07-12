package audio.soniqo.speech

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechConfigTest {

    @Test
    fun endOfSpeechSilence_usesSnappyCommandDefault() {
        assertEquals(0.5f, SpeechConfig().endOfSpeechSilenceSec, 0f)
    }

    @Test
    fun endOfSpeechSilence_acceptsLongerPauseForDictation() {
        val config = SpeechConfig(endOfSpeechSilenceSec = 0.8f)

        assertEquals(0.8f, config.endOfSpeechSilenceSec, 0f)
    }
}
