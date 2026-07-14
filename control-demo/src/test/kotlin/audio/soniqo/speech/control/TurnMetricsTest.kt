package audio.soniqo.speech.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnMetricsTest {

    private val metrics = TurnMetrics(
        sttMs = 420f, speechSec = 2.0f,
        llmMs = 500, llmChars = 120,   // ~30 tokens
        ttsMs = 510, ttsAudioSec = 1.7f,
        actionMs = 940, roundMs = 1450,
        memMb = 1432,
    )

    @Test
    fun rtf_isWallTimeOverAudioSeconds() {
        assertEquals(0.21f, metrics.sttRtf, 0.001f)
        assertEquals(0.3f, metrics.ttsRtf, 0.001f)
    }

    @Test
    fun tokPerSec_estimatesFourCharsPerToken() {
        assertEquals(60f, metrics.tokPerSec, 0.001f)
    }

    @Test
    fun format_isRunnerStyleSingleLine() {
        assertEquals(
            "stt 420ms rtf 0.21 · llm 500ms ~60 tok/s · tts 510ms→1.7s rtf 0.30" +
                " · action 940ms · round 1450ms · mem 1432 MB",
            metrics.format(),
        )
    }

    @Test
    fun format_omitsUnknownFields() {
        val line = TurnMetrics(
            sttMs = 100f, speechSec = 0f,
            llmMs = 0, llmChars = 0,
            ttsMs = 200, ttsAudioSec = 0f,
            actionMs = 300, roundMs = 400,
            memMb = 0,
        ).format()

        assertFalse(line.contains("rtf"))
        assertFalse(line.contains("tok/s"))
        assertFalse(line.contains("mem"))
        assertTrue(line.contains("action 300ms"))
    }
}
