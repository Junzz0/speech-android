package audio.soniqo.speech.llm

/**
 * Kotlin port of `function_calls.format_tool_call_prompt` from the Python
 * speech-models export repo. Output is plain text — call
 * [applyChatTemplate] to wrap it in the model's chat-template scaffolding
 * before passing to the LiteRT-LM runtime.
 */
object FunctionGemmaPrompt {

    const val FUNCTION_DECLARATIONS_START = "<start_function_declarations>"
    const val FUNCTION_DECLARATIONS_END   = "<end_function_declarations>"
    const val FUNCTION_DECLARATION_START  = "<start_function_declaration>"
    const val FUNCTION_DECLARATION_END    = "<end_function_declaration>"
    const val FUNCTION_CALL_START         = "<start_function_call>"
    const val FUNCTION_CALL_END           = "<end_function_call>"
    const val FUNCTION_RESPONSE_START     = "<start_function_response>"
    const val FUNCTION_RESPONSE_END       = "<end_function_response>"
    const val ESCAPE                      = "<escape>"

    fun formatDeclarations(tools: List<FunctionDeclaration>): String = buildString {
        append(FUNCTION_DECLARATIONS_START).append('\n')
        for (tool in tools) {
            append(FUNCTION_DECLARATION_START).append('\n')
            append("name:").append(tool.name)
                .append(",description:").append(ESCAPE).append(tool.description).append(ESCAPE)
                .append(",parameters:").append(formatValue(tool.parameters))
            append('\n').append(FUNCTION_DECLARATION_END).append('\n')
        }
        append(FUNCTION_DECLARATIONS_END)
    }

    fun formatUserTurn(tools: List<FunctionDeclaration>, userText: String): String =
        "${formatDeclarations(tools)}\n$userText"

    /** Wrap the formatted user turn in Gemma 3's chat-template scaffolding. */
    fun applyChatTemplate(userMessage: String): String =
        "<start_of_turn>user\n$userMessage<end_of_turn>\n<start_of_turn>model\n"

    fun formatResponse(name: String, response: Map<String, Any?>): String =
        "$FUNCTION_RESPONSE_START" + "response:" + name +
            formatObject(response) + FUNCTION_RESPONSE_END

    // ------------------------------------------------------------------
    // JSON-like serialiser
    // ------------------------------------------------------------------

    private fun formatValue(value: Any?): String = when (value) {
        null               -> "null"
        is Map<*, *>       -> @Suppress("UNCHECKED_CAST") formatObject(value as Map<String, Any?>)
        is List<*>         -> "[${value.joinToString(",") { formatValue(it) }}]"
        is String          -> "$ESCAPE$value$ESCAPE"
        is Boolean         -> if (value) "true" else "false"
        is Number          -> value.toString()
        else               -> "$ESCAPE${value}$ESCAPE"
    }

    private fun formatObject(dict: Map<String, Any?>): String {
        // Match the field ordering the Python tokenisation script emits — the
        // model was trained on those exact field names appearing in this order.
        val preferred = listOf("type", "description", "properties", "required", "items", "enum")
        val keys = dict.keys.toMutableList().sortedWith(compareBy(
            { preferred.indexOf(it).let { idx -> if (idx == -1) Int.MAX_VALUE else idx } },
            { it },
        ))
        val pairs = keys.joinToString(",") { "${it}:${formatValue(dict[it])}" }
        return "{$pairs}"
    }
}
