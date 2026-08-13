package audio.soniqo.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechConfigTest {

    @Test
    fun `end-of-speech silence defaults to snappy command value`() {
        assertEquals(0.5f, SpeechConfig().endOfSpeechSilenceSec, 0f)
    }

    @Test
    fun `cn country code is rejected with Chinese language tags`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            SpeechConfig(
                sttModel = SttModel.NEMOTRON_MULTILINGUAL,
                language = "cn",
            ).requireValidLanguageConfiguration()
        }

        assertTrue(error.message.orEmpty().contains("zh-CN"))
        assertTrue(error.message.orEmpty().contains("zh-TW"))
    }

    @Test
    fun `Parakeet rejects fixed language instead of silently ignoring it`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            SpeechConfig(
                sttModel = SttModel.PARAKEET,
                language = "zh-CN",
            ).requireValidLanguageConfiguration()
        }

        assertTrue(error.message.orEmpty().contains("always auto-detects"))
        assertTrue(error.message.orEmpty().contains("NEMOTRON_MULTILINGUAL"))
    }

    @Test
    fun `Parakeet EOU rejects fixed language`() {
        assertThrows(IllegalArgumentException::class.java) {
            SpeechConfig(
                sttModel = SttModel.PARAKEET_EOU,
                language = "en",
            ).requireValidLanguageConfiguration()
        }
    }

    @Test
    fun `language shortlist is rejected instead of silently ignored`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            SpeechConfig(
                sttModel = SttModel.PARAKEET,
                languageHints = listOf("en", "zh-CN"),
            ).requireValidLanguageConfiguration()
        }

        assertTrue(error.message.orEmpty().contains("languageHints"))
    }

    @Test
    fun `Nemotron accepts fixed Chinese locale`() {
        SpeechConfig(
            sttModel = SttModel.NEMOTRON_MULTILINGUAL,
            language = "zh-CN",
        ).requireValidLanguageConfiguration()
    }

    @Test
    fun `automatic language remains valid for both Parakeet models`() {
        SpeechConfig(sttModel = SttModel.PARAKEET)
            .requireValidLanguageConfiguration()
        SpeechConfig(sttModel = SttModel.PARAKEET_EOU)
            .requireValidLanguageConfiguration()
    }
}
