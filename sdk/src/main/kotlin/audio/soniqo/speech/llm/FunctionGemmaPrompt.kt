package audio.soniqo.speech.llm

/**
 * FunctionGemma prompt formatter, matching the canonical template from
 * Google's "FunctionGemma formatting and best practices" guide (verified
 * against the released 270M weights on the LiteRT-LM runtime):
 *
 * ```
 * <start_of_turn>developer
 * You are a model that can do function calling with the following functions
 * <start_function_declaration>declaration:NAME{description:<escape>..<escape>,
 * parameters:{properties:{..},required:[..],type:<escape>OBJECT<escape>}}<end_function_declaration><end_of_turn>
 * <start_of_turn>user
 * ..<end_of_turn>
 * <start_of_turn>model
 * ```
 *
 * Object keys serialize in alphabetical order and type names must be the
 * UPPERCASE variants (`OBJECT`, `STRING`, `INTEGER`, `NUMBER`, `BOOLEAN`,
 * `ARRAY`) — both taken from the canonical examples; deviations measurably
 * degrade the 270M model's call syntax.
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

    const val DEVELOPER_PREAMBLE =
        "You are a model that can do function calling with the following functions"

    /** Developer-turn body: preamble + one declaration block per tool. */
    fun formatDeclarations(tools: List<FunctionDeclaration>): String = buildString {
        append(DEVELOPER_PREAMBLE)
        for (tool in tools) {
            append(FUNCTION_DECLARATION_START)
            append("declaration:").append(tool.name)
            append("{description:").append(ESCAPE).append(tool.description).append(ESCAPE)
            append(",parameters:").append(formatValue(tool.parameters))
            append('}')
            append(FUNCTION_DECLARATION_END)
        }
    }

    /** Full chat-templated prompt: developer turn + user turn + model cue. */
    fun formatPrompt(tools: List<FunctionDeclaration>, userText: String): String =
        "<start_of_turn>developer\n${formatDeclarations(tools)}<end_of_turn>\n" +
            "<start_of_turn>user\n$userText<end_of_turn>\n" +
            "<start_of_turn>model\n"

    /** Un-templated variant for runtimes that add chat scaffolding themselves. */
    fun formatUserTurn(tools: List<FunctionDeclaration>, userText: String): String =
        "${formatDeclarations(tools)}\n$userText"

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
        // Canonical serialization orders object keys alphabetically (e.g.
        // parameters:{properties:..,required:..,type:..}) — the model was
        // trained on that exact ordering.
        val pairs = dict.keys.sorted().joinToString(",") { "${it}:${formatValue(dict[it])}" }
        return "{$pairs}"
    }
}
