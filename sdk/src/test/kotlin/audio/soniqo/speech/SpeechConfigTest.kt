package audio.soniqo.speech

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechConfigTest {

    @Test
    fun `end-of-speech silence defaults to snappy command value`() {
        assertEquals(0.5f, SpeechConfig().endOfSpeechSilenceSec, 0f)
    }
}
