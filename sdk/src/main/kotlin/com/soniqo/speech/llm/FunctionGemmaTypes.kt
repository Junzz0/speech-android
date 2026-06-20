package audio.soniqo.speech.llm

/** A function the LLM can invoke. */
data class FunctionDeclaration(
    val name: String,
    val description: String,
    /** JSON-Schema parameter spec, e.g. `mapOf("type" to "object", "properties" to ...)`. */
    val parameters: Map<String, Any?>,
)

/** A single parsed `<start_function_call>...<end_function_call>` block. */
data class FunctionCall(
    val name: String,
    val arguments: Map<String, ArgumentValue>,
)

/** Type-tagged values inside a parsed function call. */
sealed class ArgumentValue {
    data class Str(val value: String) : ArgumentValue()
    data class Int(val value: Long) : ArgumentValue()
    data class Double(val value: kotlin.Double) : ArgumentValue()
    data class Bool(val value: Boolean) : ArgumentValue()
    data class Array(val items: List<ArgumentValue>) : ArgumentValue()
    data class Object(val fields: Map<String, ArgumentValue>) : ArgumentValue()
    object Null : ArgumentValue()
}
