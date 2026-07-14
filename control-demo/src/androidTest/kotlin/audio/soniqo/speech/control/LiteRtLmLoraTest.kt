package audio.soniqo.speech.control

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import audio.soniqo.speech.LlmModel
import audio.soniqo.speech.ModelManager
import audio.soniqo.speech.llm.ArgumentValue
import audio.soniqo.speech.llm.FunctionGemma
import java.io.File
import kotlin.math.ceil
import kotlin.system.measureTimeMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class LiteRtLmLoraTest {

    @Test
    fun compactHeldOutReportsQualityAndLatency() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profile = LlmModel.FUNCTIONGEMMA_CONTROL_LORA
        val model = File(ModelManager.llmModelFile(context, profile))
        val adapter = File(requireNotNull(ModelManager.llmAdapterFile(context, profile)))
        val dataset = File(context.filesDir, "compact-test.jsonl")
        require(model.isFile) { "Launch the demo to download ${model.absolutePath}" }
        require(adapter.isFile) { "Launch the demo to download ${adapter.absolutePath}" }
        require(dataset.isFile) { "Push compact/test.jsonl to ${dataset.absolutePath}" }

        val runtime = LiteRtLmRuntime(model.absolutePath, adapter.absolutePath)
        val loadMs = measureTimeMillis { runtime.initialize() }
        val parser = FunctionGemma(runtime, maxNewTokens = 128)
        val latencies = mutableListOf<Long>()
        var cases = 0
        var routeCorrect = 0
        var actionCases = 0
        var actionArgumentsExact = 0
        var failuresLogged = 0

        try {
            dataset.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val row = JSONObject(line)
                val messages = row.getJSONArray("messages")
                val developer = messages.getJSONObject(0).getString("content")
                val user = messages.getJSONObject(1).getString("content")
                val metadata = row.getJSONObject("metadata")
                val expectedTool = if (metadata.isNull("expected_tool")) {
                    null
                } else {
                    metadata.getString("expected_tool")
                }
                val expectedArguments = metadata.optJSONObject("expected_arguments")
                val prompt =
                    "<start_of_turn>developer\n$developer<end_of_turn>\n" +
                        "<start_of_turn>user\n$user<end_of_turn>\n" +
                        "<start_of_turn>model\n"

                lateinit var raw: String
                val elapsed = measureTimeMillis {
                    raw = runtime.generate(prompt, maxNewTokens = 128)
                }
                latencies += elapsed
                cases++

                val call = parser.parseToolCalls(raw).singleOrNull()
                val routeMatches = call?.name == expectedTool ||
                    (call == null && expectedTool == null)
                if (routeMatches) routeCorrect++

                var argumentsMatch = true
                if (expectedTool != null) {
                    actionCases++
                    if (call == null || !routeMatches) {
                        argumentsMatch = false
                    } else if (expectedArguments != null) {
                        val keys = expectedArguments.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            if (key == "say") continue
                            val expected = expectedArguments.get(key).toString().trim().lowercase()
                            val actual = argumentText(call.arguments[key])
                            if (actual != expected) argumentsMatch = false
                        }
                    }
                    if (argumentsMatch) actionArgumentsExact++
                }

                if ((!routeMatches || !argumentsMatch) && failuresLogged < 20) {
                    println(
                        "LORA_EVAL_FAILURE command='${user.replace("'", "\\'")}' " +
                            "expected=$expectedTool actual=${call?.name} " +
                            "raw='${raw.replace("\n", " ").take(300)}'",
                    )
                    failuresLogged++
                }
            }

            val sorted = latencies.sorted()
            val mean = sorted.average()
            val p50 = percentile(sorted, 0.50)
            val p95 = percentile(sorted, 0.95)
            val routeAccuracy = routeCorrect * 100.0 / cases
            val argumentAccuracy = actionArgumentsExact * 100.0 / actionCases
            println(
                "LORA_EVAL cases=$cases engine_load_ms=$loadMs " +
                    "route_correct=$routeCorrect route_accuracy=$routeAccuracy " +
                    "action_arguments_exact=$actionArgumentsExact/$actionCases " +
                    "action_argument_accuracy=$argumentAccuracy " +
                    "mean_ms=$mean p50_ms=$p50 p95_ms=$p95 max_ms=${sorted.last()}",
            )
            assertEquals("Unexpected held-out dataset size", 136, cases)
            assertTrue("Held-out route accuracy was $routeAccuracy%", routeAccuracy >= 85.0)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun compactControlAdapterGeneratesExpectedCalls() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profile = LlmModel.FUNCTIONGEMMA_CONTROL_LORA
        val model = File(ModelManager.llmModelFile(context, profile))
        val adapter = File(requireNotNull(ModelManager.llmAdapterFile(context, profile)))
        require(model.isFile) { "Launch the demo to download ${model.absolutePath}" }
        require(adapter.isFile) { "Launch the demo to download ${adapter.absolutePath}" }

        val runtime = LiteRtLmRuntime(model.absolutePath, adapter.absolutePath)
        val loadMs = measureTimeMillis { runtime.initialize() }
        val parser = FunctionGemma(runtime, maxNewTokens = 128)
        val tools = ControlTools.availableTools(musicPlaying = false)

        try {
            val cases = listOf(
                Case("set volume to five", "set_volume", "level", "5"),
                Case("call anna", "call_contact", "name", "anna"),
                Case(
                    "is there any Nina Simone on this phone",
                    "find_music",
                    "query",
                    "nina simone",
                ),
            )
            println("LORA_METRIC engine_load_ms=$loadMs")
            for (case in cases) {
                lateinit var raw: String
                val generateMs = measureTimeMillis {
                    raw = runtime.generate(
                        CompactPrompt.format(tools, musicPlaying = false, case.command),
                        maxNewTokens = 128,
                    )
                }
                val call = ControlTools.selectSingleCall(parser.parseToolCalls(raw))
                println(
                    "LORA_METRIC command='${case.command}' generate_ms=$generateMs " +
                        "raw='${raw.replace("\n", " ")}'",
                )
                assertNotNull("No single tool call for ${case.command}: $raw", call)
                assertEquals(case.tool, call!!.name)
                val actual = when (val value = call.arguments[case.argument]) {
                    is ArgumentValue.Int -> value.value.toString()
                    is ArgumentValue.Double -> value.value.toString().removeSuffix(".0")
                    is ArgumentValue.Str -> value.value.trim().lowercase()
                    else -> value.toString().trim().lowercase()
                }
                assertEquals(case.value, actual)
            }
        } finally {
            runtime.close()
        }
    }

    private data class Case(
        val command: String,
        val tool: String,
        val argument: String,
        val value: String,
    )

    private fun percentile(sorted: List<Long>, fraction: Double): Long =
        sorted[(ceil(sorted.size * fraction).toInt() - 1).coerceIn(sorted.indices)]

    private fun argumentText(value: ArgumentValue?): String = when (value) {
        is ArgumentValue.Int -> value.value.toString()
        is ArgumentValue.Double -> value.value.toString().removeSuffix(".0")
        is ArgumentValue.Str -> value.value.trim().lowercase()
        else -> value.toString().trim().lowercase()
    }
}
