package audio.soniqo.speech.control

import java.util.Locale

/**
 * Per-turn latency report, formatted like soniqo/runner's sidecar log lines
 * (`elapsed=..ms audio=..ms rtf=..`, `round=..ms, memory=.. MB`) and the
 * per-stage `RTF · tok/s · MB` table in the on-device-voice-agents blog post.
 */
data class TurnMetrics(
    /** STT decode wall time (from the pipeline event). */
    val sttMs: Float,
    /** Captured speech length in seconds (SpeechStarted → SpeechEnded). */
    val speechSec: Float,
    /** LLM wall time for the tool-call generation. */
    val llmMs: Long,
    /** Characters the LLM emitted — proxy for tokens (~4 chars/token). */
    val llmChars: Int,
    /** TTS synthesis wall time. */
    val ttsMs: Long,
    /** Synthesized audio length in seconds. */
    val ttsAudioSec: Float,
    /** Voice-to-action: end of speech → tool executed on the car state. */
    val actionMs: Long,
    /** Full round: end of speech → response playback started. */
    val roundMs: Long,
    /** Process PSS when the turn finished, in MB (0 = unknown). */
    val memMb: Int,
) {
    val sttRtf: Float get() = if (speechSec > 0f) sttMs / 1000f / speechSec else 0f
    val ttsRtf: Float get() = if (ttsAudioSec > 0f) ttsMs / 1000f / ttsAudioSec else 0f

    /** Estimated generation speed; chars/4 ≈ tokens for BPE English text. */
    val tokPerSec: Float get() = if (llmMs > 0) (llmChars / 4f) / (llmMs / 1000f) else 0f

    fun format(): String = buildString {
        append("stt ${sttMs.toInt()}ms")
        if (speechSec > 0f) append(" rtf ${sttRtf.f2()}")
        append(" · llm ${llmMs}ms")
        if (tokPerSec > 0f) append(" ~${tokPerSec.toInt()} tok/s")
        append(" · tts ${ttsMs}ms")
        if (ttsAudioSec > 0f) {
            append("→${"%.1f".format(Locale.US, ttsAudioSec)}s rtf ${ttsRtf.f2()}")
        }
        append(" · action ${actionMs}ms · round ${roundMs}ms")
        if (memMb > 0) append(" · mem $memMb MB")
    }

    private fun Float.f2(): String = "%.2f".format(Locale.US, this)
}
