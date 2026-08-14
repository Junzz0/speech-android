package audio.soniqo.speech.control

import audio.soniqo.speech.llm.FunctionGemma
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LoraConfig
import com.google.ai.edge.litertlm.SamplerConfig

/**
 * [FunctionGemma.Runtime] over the litertlm-android Engine/Conversation API.
 *
 * Each generate() runs in a fresh one-shot Conversation: FunctionGemma tool
 * emission is single-turn, and a fresh conversation keeps the KV cache from
 * growing across commands.
 */
class LiteRtLmRuntime(
    private val modelPath: String,
    private val loraPath: String? = null,
) : FunctionGemma.Runtime, AutoCloseable {

    private var engine: Engine? = null

    // Verified on-emulator against 0.16.0: without SDK-side templating the
    // model emits degraded, non-canonical call syntax (`name:...` instead of
    // `call:NAME{...}`), so the SDK applies the Gemma chat template.
    override val appliesChatTemplate: Boolean get() = false

    /** Blocking, up to ~10 s — call from a background thread. */
    fun initialize() {
        val e = Engine(EngineConfig(modelPath = modelPath, backend = Backend.CPU()))
        e.initialize()
        engine = e
    }

    override fun generate(prompt: String, maxNewTokens: Int): String {
        val e = checkNotNull(engine) { "LiteRtLmRuntime not initialized" }
        // Greedy decoding: sampled decoding makes the 270M model's call
        // syntax fall apart every few turns (verified on-emulator), while
        // tool emission wants the single strongest path anyway. maxNewTokens
        // is also enforced by LiteRT-LM so malformed output cannot run until
        // the model-wide context limit. Valid FunctionGemma calls stop at
        // <end_function_call> on their own.
        val config = ConversationConfig(
            samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
            loraConfig = loraPath?.let { LoraConfig(loraPath = it) },
            maxOutputToken = maxNewTokens,
        )
        return e.createConversation(config).use { conversation ->
            conversation.sendMessage(prompt).toString()
        }
    }

    override fun cancel() {
        // The synchronous Conversation API has no mid-generation cancel; a
        // 270M tool call completes in well under a second on CPU, so the
        // demo simply lets in-flight generations finish.
    }

    override fun close() {
        engine?.close()
        engine = null
    }
}
