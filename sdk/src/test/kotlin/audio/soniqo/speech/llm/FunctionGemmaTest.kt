package audio.soniqo.speech.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chat-template contract of [FunctionGemma.Runtime]: raw runtimes get the
 * fully templated prompt, runtimes that template internally (litertlm-android
 * Conversation API) get the bare user turn — double-templating degrades
 * tool-call accuracy.
 */
class FunctionGemmaTest {

    private class CapturingRuntime(
        override val appliesChatTemplate: Boolean,
        private val response: String = "",
    ) : FunctionGemma.Runtime {
        var lastPrompt: String? = null
        override fun generate(prompt: String, maxNewTokens: Int): String {
            lastPrompt = prompt
            return response
        }
        override fun cancel() {}
    }

    private val tools = listOf(
        FunctionDeclaration(
            name = "set_temperature",
            description = "Set cabin temperature",
            parameters = mapOf(
                "type" to "OBJECT",
                "properties" to mapOf(
                    "celsius" to mapOf(
                        "type" to "INTEGER",
                        "description" to "Target temperature",
                    ),
                ),
                "required" to listOf("celsius"),
            ),
        ),
    )

    @Test
    fun `raw runtime receives canonical developer-turn prompt`() {
        val runtime = CapturingRuntime(appliesChatTemplate = false)
        FunctionGemma(runtime).generateToolCall("set temp to 21", tools)

        val prompt = runtime.lastPrompt!!
        assertTrue(prompt.startsWith(
            "<start_of_turn>developer\n${FunctionGemmaPrompt.DEVELOPER_PREAMBLE}"))
        assertTrue(prompt.contains(
            "<start_function_declaration>declaration:set_temperature{description:" +
            "<escape>Set cabin temperature<escape>,parameters:{properties:{celsius:" +
            "{description:<escape>Target temperature<escape>,type:<escape>INTEGER<escape>}}," +
            "required:[<escape>celsius<escape>],type:<escape>OBJECT<escape>}}" +
            "<end_function_declaration>"))
        assertTrue(prompt.contains("<start_of_turn>user\nset temp to 21<end_of_turn>"))
        assertTrue(prompt.endsWith("<start_of_turn>model\n"))
    }

    @Test
    fun `templating runtime receives bare declarations and text`() {
        val runtime = CapturingRuntime(appliesChatTemplate = true)
        FunctionGemma(runtime).generateToolCall("set temp to 21", tools)

        val prompt = runtime.lastPrompt!!
        assertFalse(prompt.contains("<start_of_turn>"))
        assertTrue(prompt.startsWith(FunctionGemmaPrompt.DEVELOPER_PREAMBLE))
        assertTrue(prompt.endsWith("set temp to 21"))
    }

    @Test
    fun `parse accepts bare function name without call prefix`() {
        // The released FunctionGemma-270M weights emit `NAME {args}` without
        // the `call:` prefix from the training-format spec (seen on-device).
        val response = "<start_function_call>call_contact {name:<escape>Anna<escape>," +
            "say:<escape>Calling Anna.<escape>}<end_function_call>"
        val calls = FunctionGemmaParser.parseFunctionCalls(response)

        assertEquals(1, calls.size)
        assertEquals("call_contact", calls[0].name)
        assertEquals(ArgumentValue.Str("Anna"), calls[0].arguments["name"])
    }

    @Test
    fun `parse tolerates newlines around call body`() {
        // litertlm-android's Conversation output puts the call body on its
        // own line after the marker (seen on-device with 0.14.0).
        val response = "<start_function_call>\n" +
            "call:find_contact{name:<escape>anna<escape>}\n<end_function_call>"
        val calls = FunctionGemmaParser.parseFunctionCalls(response)

        assertEquals(1, calls.size)
        assertEquals("find_contact", calls[0].name)
        assertEquals(ArgumentValue.Str("anna"), calls[0].arguments["name"])
    }

    @Test
    fun `generate and parse round-trips a tool call`() {
        val response = "<start_function_call>call:set_temperature{celsius:21," +
            "say:<escape>Temperature set to 21 degrees.<escape>}<end_function_call>"
        val gemma = FunctionGemma(CapturingRuntime(appliesChatTemplate = true, response = response))

        val calls = gemma.parseToolCalls(gemma.generateToolCall("set temp to 21", tools))

        assertEquals(1, calls.size)
        assertEquals("set_temperature", calls[0].name)
        assertEquals(ArgumentValue.Int(21), calls[0].arguments["celsius"])
        assertEquals(
            ArgumentValue.Str("Temperature set to 21 degrees."),
            calls[0].arguments["say"],
        )
    }
}
