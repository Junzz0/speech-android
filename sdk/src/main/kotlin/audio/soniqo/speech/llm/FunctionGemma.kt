package audio.soniqo.speech.llm

/**
 * On-device FunctionGemma 270M tool-calling LLM.
 *
 * Wraps the `.litertlm` bundle published as
 * `soniqo/FunctionGemma-270M-LiteRT-LM` and exposes a single
 * [generateToolCall] entry point that returns either the raw model text
 * (still containing `<start_function_call>...<end_function_call>` markers)
 * or a structured list of [FunctionCall]s via [parseToolCalls].
 *
 * The actual LiteRT-LM runtime binding is supplied via [Runtime] so this
 * file stays neutral about which Gradle dependency you wire in
 * (`com.google.ai.edge.litert:litert-lm-runtime`,
 * `com.google.mediapipe:tasks-genai`, etc.). The default constructor
 * expects the caller to pass an already-loaded [Runtime] instance — see
 * the README for the recommended Gradle dependency and Runtime
 * implementation.
 *
 * Thread safety: [generateToolCall] is **not** safe to call concurrently
 * on the same instance.
 */
class FunctionGemma(
    private val runtime: Runtime,
    private val maxNewTokens: Int = 64,
) {

    /**
     * Adapter the SDK calls to drive the LiteRT-LM engine. Implementations
     * live in app code so the SDK can ship without taking on the entire
     * litert-lm transitive dependency tree.
     */
    interface Runtime {
        /** Process a full prompt and return raw model text up to a stop token. */
        fun generate(prompt: String, maxNewTokens: Int): String

        /** Cancel in-flight generation. Thread-safe. */
        fun cancel()
    }

    /**
     * Generate the model's response to a tool-call prompt.
     *
     * Caller passes the user's text plus the registered tool list; the
     * model emits a `<start_function_call>...<end_function_call>` block
     * that you parse with [parseToolCalls].
     */
    fun generateToolCall(userText: String, tools: List<FunctionDeclaration>): String {
        val prompt = FunctionGemmaPrompt.formatUserTurn(tools, userText)
        val wrapped = FunctionGemmaPrompt.applyChatTemplate(prompt)
        return runtime.generate(wrapped, maxNewTokens)
    }

    /** Extract structured calls from a raw model response. */
    fun parseToolCalls(text: String): List<FunctionCall> =
        FunctionGemmaParser.parseFunctionCalls(text)

    /** Cancel an in-flight [generateToolCall]. */
    fun cancel() { runtime.cancel() }

    companion object {
        /** Repo id of the published .litertlm bundle. */
        const val DEFAULT_MODEL_ID = "soniqo/FunctionGemma-270M-LiteRT-LM"
    }
}
