package audio.soniqo.speech.control

import org.junit.Assert.assertEquals
import org.junit.Test

class CompactPromptTest {

    @Test
    fun developerTurn_matchesTrainingSerializationForIdleState() {
        val tools = ControlTools.availableTools(musicPlaying = false)

        assertEquals(
            "You are a model that can do function calling with the following functions\n" +
                "Available functions: call_contact, dial_number, find_contact, find_music, " +
                "play_music, set_volume, list_capabilities.\n" +
                "Music state: idle.",
            CompactPrompt.developerTurn(tools, musicPlaying = false),
        )
    }

    @Test
    fun fullPrompt_matchesTrainingSerializationForPlayingState() {
        val tools = ControlTools.availableTools(musicPlaying = true)

        assertEquals(
            "<start_of_turn>developer\n" +
                "You are a model that can do function calling with the following functions\n" +
                "Available functions: call_contact, dial_number, find_contact, find_music, " +
                "play_music, stop_music, set_volume, list_capabilities.\n" +
                "Music state: playing.<end_of_turn>\n" +
                "<start_of_turn>user\nstop the music<end_of_turn>\n" +
                "<start_of_turn>model\n",
            CompactPrompt.format(tools, musicPlaying = true, userText = "stop the music"),
        )
    }
}
