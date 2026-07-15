package audio.soniqo.speech.control

import audio.soniqo.speech.llm.FunctionDeclaration

/**
 * Compact FunctionGemma prompt for the published Control adapter: the
 * developer turn carries the FunctionGemma trigger, the state-filtered
 * function names, and the music state — no full schema declarations. The
 * schemas are trained into the weights, so this prompt must stay compatible
 * with the adapter's compact training serialization and use the same Gemma
 * turn structure as the SDK's canonical path.
 *
 * This demo always pairs the compact prompt with the published Control LoRA.
 */
object CompactPrompt {

    private const val TRIGGER =
        "You are a model that can do function calling with the following functions"

    /** Developer-turn body: trigger + available function names + music state. */
    fun developerTurn(tools: List<FunctionDeclaration>, musicPlaying: Boolean): String {
        val names = tools.joinToString(", ") { it.name }
        val state = if (musicPlaying) "playing" else "idle"
        return "$TRIGGER\nAvailable functions: $names.\nMusic state: $state."
    }

    /**
     * Full prompt for a runtime that does not apply the chat template itself
     * (LiteRtLmRuntime.appliesChatTemplate == false), mirroring
     * FunctionGemmaPrompt.formatPrompt's turn structure.
     */
    fun format(
        tools: List<FunctionDeclaration>,
        musicPlaying: Boolean,
        userText: String,
    ): String =
        "<start_of_turn>developer\n${developerTurn(tools, musicPlaying)}<end_of_turn>\n" +
            "<start_of_turn>user\n$userText<end_of_turn>\n" +
            "<start_of_turn>model\n"
}
