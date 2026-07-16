package audio.soniqo.speech.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechChunksTest {

    @Test
    fun shortReplyStaysWhole() {
        assertEquals(listOf("Music stopped."), SpeechChunks.split("Music stopped."))
    }

    @Test
    fun sentencesSplitApart() {
        assertEquals(
            listOf("Calling Anna.", "Please wait."),
            SpeechChunks.split("Calling Anna. Please wait."),
        )
    }

    @Test
    fun longSingleSentenceSplitsAtClauseBoundary() {
        val pieces = SpeechChunks.split(ControlTools.CAPABILITIES_SUMMARY)
        assertTrue("expected 2+ pieces, got $pieces", pieces.size >= 2)
        for (piece in pieces) {
            assertTrue("piece too long: $piece", piece.length <= 30)
            assertTrue("piece too short: $piece", piece.length >= 10)
        }
        assertEquals("I can call your contacts,", pieces.first())
        assertEquals(
            ControlTools.CAPABILITIES_SUMMARY.replace(" ", ""),
            pieces.joinToString("").replace(" ", ""),
        )
    }

    @Test
    fun findMusicListingSplitsCleanly() {
        val reply = "You have 25 tracks, including Feeling Good by Nina Simone, " +
            "Hotel California, Morning Drive by The Layers."
        val pieces = SpeechChunks.split(reply)
        assertTrue(pieces.size >= 2)
        for (piece in pieces) assertTrue(piece.length <= 30)
    }

    @Test
    fun tinyTailMovesAWordFromPreviousPiece() {
        assertEquals(
            listOf("Playing Over the Horizon", "by Samsung."),
            SpeechChunks.split("Playing Over the Horizon by Samsung."),
        )
    }
}
