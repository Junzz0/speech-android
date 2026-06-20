package audio.soniqo.speech.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FunctionGemmaParserTest {

    @Test fun `parses single call with escaped string`() {
        val text = "${FunctionGemmaPrompt.FUNCTION_CALL_START}call:get_weather{location:<escape>Tokyo<escape>}${FunctionGemmaPrompt.FUNCTION_CALL_END}"
        val calls = FunctionGemmaParser.parseFunctionCalls(text)
        assertEquals(1, calls.size)
        assertEquals("get_weather", calls[0].name)
        assertEquals(ArgumentValue.Str("Tokyo"), calls[0].arguments["location"])
    }

    @Test fun `parses scalars`() {
        val text = "${FunctionGemmaPrompt.FUNCTION_CALL_START}call:set_timer{seconds:300,label:<escape>tea<escape>,enabled:true}${FunctionGemmaPrompt.FUNCTION_CALL_END}"
        val calls = FunctionGemmaParser.parseFunctionCalls(text)
        assertEquals("set_timer", calls[0].name)
        assertEquals(ArgumentValue.Int(300L), calls[0].arguments["seconds"])
        assertEquals(ArgumentValue.Str("tea"), calls[0].arguments["label"])
        assertEquals(ArgumentValue.Bool(true), calls[0].arguments["enabled"])
    }

    @Test fun `parses canonical exchange rate output`() {
        val text = "<start_function_call>call:get_exchange_rate{amount:23,from_currency:<escape>USD<escape>,to_currency:<escape>EUR<escape>}<end_function_call>"
        val calls = FunctionGemmaParser.parseFunctionCalls(text)
        assertEquals(1, calls.size)
        assertEquals("get_exchange_rate", calls[0].name)
        assertEquals(ArgumentValue.Int(23L), calls[0].arguments["amount"])
        assertEquals(ArgumentValue.Str("USD"), calls[0].arguments["from_currency"])
        assertEquals(ArgumentValue.Str("EUR"), calls[0].arguments["to_currency"])
    }

    @Test fun `parses double and null`() {
        val text = "<start_function_call>call:fn{x:0.5,y:null}<end_function_call>"
        val calls = FunctionGemmaParser.parseFunctionCalls(text)
        assertEquals(ArgumentValue.Double(0.5), calls[0].arguments["x"])
        assertEquals(ArgumentValue.Null, calls[0].arguments["y"])
    }

    @Test fun `parses parallel calls`() {
        val text = "<start_function_call>call:turn_on{device:<escape>flashlight<escape>}<end_function_call>" +
            "<start_function_call>call:create_note{title:<escape>Groceries<escape>}<end_function_call>"
        val calls = FunctionGemmaParser.parseFunctionCalls(text)
        assertEquals(listOf("turn_on", "create_note"), calls.map { it.name })
        assertEquals(ArgumentValue.Str("Groceries"), calls[1].arguments["title"])
    }

    @Test fun `no call returns empty list`() {
        assertTrue(FunctionGemmaParser.parseFunctionCalls("hello world").isEmpty())
    }
}
