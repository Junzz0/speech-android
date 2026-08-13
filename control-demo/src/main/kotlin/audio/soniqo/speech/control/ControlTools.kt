package audio.soniqo.speech.control

import audio.soniqo.speech.llm.ArgumentValue
import audio.soniqo.speech.llm.FunctionCall
import audio.soniqo.speech.llm.FunctionDeclaration

/**
 * Phone-control tool surface for FunctionGemma.
 *
 * Every tool takes a `say` string parameter — a short confirmation the model
 * writes for the driver/user, so one LLM pass authors both the action and
 * the sentence TTS speaks. `say` is honored on action tools whose outcome
 * the model can predict (dial, volume, stop). Lookup tools (find_contact,
 * call_contact, play_music) speak the actual device result instead — the
 * model cannot know what the address book or media store will return, so
 * the "function response" turn is collapsed into a result template.
 */
object ControlTools {

    private val SAY_PARAM = mapOf(
        "type" to "STRING",
        "description" to "Short spoken confirmation for the user, one sentence",
    )

    val declarations: List<FunctionDeclaration> = listOf(
        FunctionDeclaration(
            name = "call_contact",
            description = "Call a person from the address book by name",
            parameters = mapOf(
                "type" to "OBJECT",
                "properties" to mapOf(
                    "name" to mapOf(
                        "type" to "STRING",
                        "description" to "Contact name to call, e.g. Anna",
                    ),
                    "say" to SAY_PARAM,
                ),
                "required" to listOf("name", "say"),
            ),
        ),
        FunctionDeclaration(
            name = "dial_number",
            description = "Dial a phone number the user spoke digit by digit",
            parameters = mapOf(
                "type" to "OBJECT",
                "properties" to mapOf(
                    "number" to mapOf(
                        "type" to "STRING",
                        // No example number here: the 270M model copies
                        // literal examples into its arguments verbatim.
                        "description" to "The phone number to dial",
                    ),
                    "say" to SAY_PARAM,
                ),
                "required" to listOf("number", "say"),
            ),
        ),
        FunctionDeclaration(
            name = "find_contact",
            // Keep this description short and plain: longer variants (e.g.
            // "... without calling them") measurably degrade the 270M
            // model's call syntax on adjacent queries.
            description = "Look up a person's phone number in the address book",
            parameters = mapOf(
                "type" to "OBJECT",
                "properties" to mapOf(
                    "name" to mapOf(
                        "type" to "STRING",
                        "description" to "Contact name to search for",
                    ),
                    "say" to SAY_PARAM,
                ),
                "required" to listOf("name", "say"),
            ),
        ),
        FunctionDeclaration(
            name = "find_music",
            description = "List or search songs and artists in the music library without playing",
            parameters = mapOf(
                "type" to "OBJECT",
                "properties" to mapOf(
                    "query" to mapOf(
                        "type" to "STRING",
                        "description" to "Song title or artist to search for; omit to list available music",
                    ),
                    "say" to SAY_PARAM,
                ),
                "required" to listOf("say"),
            ),
        ),
        FunctionDeclaration(
            name = "play_music",
            description = "Play music from the device library, optionally matching a song or artist",
            parameters = mapOf(
                "type" to "OBJECT",
                "properties" to mapOf(
                    "query" to mapOf(
                        "type" to "STRING",
                        "description" to "Song title or artist to play; omit for any music",
                    ),
                    "say" to SAY_PARAM,
                ),
                "required" to listOf("say"),
            ),
        ),
        FunctionDeclaration(
            name = "stop_music",
            description = "Stop music playback",
            parameters = mapOf(
                "type" to "OBJECT",
                "properties" to mapOf("say" to SAY_PARAM),
                "required" to listOf("say"),
            ),
        ),
        FunctionDeclaration(
            name = "set_volume",
            description = "Set the media volume",
            parameters = mapOf(
                "type" to "OBJECT",
                "properties" to mapOf(
                    "level" to mapOf(
                        "type" to "INTEGER",
                        "description" to "Volume 0 (mute) to 10 (max)",
                    ),
                    "say" to SAY_PARAM,
                ),
                "required" to listOf("level", "say"),
            ),
        ),
        FunctionDeclaration(
            name = "list_capabilities",
            description = "Tell the user what you can do and which commands are available",
            parameters = mapOf(
                "type" to "OBJECT",
                "properties" to mapOf("say" to SAY_PARAM),
                "required" to listOf("say"),
            ),
        ),
    )

    /** Spoken by list_capabilities — the executor states the real surface,
     *  the model's `say` is ignored (it tends to under- or over-promise). */
    // Deliberately long enough to exercise multi-frame streaming on every
    // "what can you do" response.
    const val CAPABILITIES_SUMMARY =
        "I can call your contacts, dial numbers, look up phone numbers, " +
        "play or stop music, and set the volume — all offline, on this device."

    val VOLUME_RANGE = 0..10

    /** Spoken fallback when the model produced no parseable tool call. */
    const val NO_TOOL_RESPONSE = "Sorry, I can't do that. Ask me what I can do."

    /**
     * The tools offered to FunctionGemma for the current device state. Only
     * valid actions are presented, so the model can't pick something that
     * makes no sense: stop_music appears only while music is playing (you
     * can't stop what isn't playing). Calling, lookups, volume, and play are
     * always available. Everything is routed by the model — no hardcoded
     * command matching.
     */
    fun availableTools(musicPlaying: Boolean): List<FunctionDeclaration> =
        declarations.filter { it.name != "stop_music" || musicPlaying }

    /**
     * A spoken command represents one device action. Reject malformed model
     * output containing zero or multiple calls instead of guessing which
     * action the user intended.
     */
    internal fun selectSingleCall(calls: List<FunctionCall>): FunctionCall? =
        calls.singleOrNull()

    /**
     * Execute [call] against [device]. Returns null for an unknown tool or
     * missing required argument. Pure orchestration — all Android work is
     * behind [DeviceActions] — so the mapping is unit-testable.
     */
    fun execute(call: FunctionCall, device: DeviceActions): ToolOutcome? {
        val say = call.string("say")
        return when (call.name) {
            "call_contact" -> {
                val name = call.string("name") ?: return null
                val contact = device.lookupContact(name)
                if (contact == null) {
                    // Never speak the model's success line for a failed lookup.
                    ToolOutcome(
                        "I couldn't find $name in your contacts.",
                        label(call.name, "name" to name),
                    )
                } else {
                    device.stopMusic()  // a call takes over audio; stop the music
                    ToolOutcome(
                        say ?: "Calling ${contact.name}.",
                        label(call.name, "name" to name),
                        DeferredDeviceAction.Dial(contact.number),
                    )
                }
            }
            "dial_number" -> {
                val number = call.string("number") ?: return null
                device.stopMusic()  // a call takes over audio; stop the music
                ToolOutcome(
                    say ?: "Calling $number.",
                    label(call.name, "number" to number),
                    DeferredDeviceAction.Dial(number),
                )
            }
            "find_contact" -> {
                val name = call.string("name") ?: return null
                val contact = device.lookupContact(name)
                ToolOutcome(
                    contact?.let { "${it.name}'s number is ${it.number}." }
                        ?: "No contact named $name.",
                    label(call.name, "name" to name),
                )
            }
            "find_music" -> {
                val query = call.string("query")
                val tracks = device.listMusic(query)
                ToolOutcome(
                    when {
                        tracks.isEmpty() && query == null ->
                            "There is no music on this device."
                        tracks.isEmpty() -> "No music matching $query."
                        else -> {
                            val sample = tracks.take(3).joinToString(", ") {
                                if (it.artist != null) "${it.title} by ${it.artist}"
                                else it.title
                            }
                            if (tracks.size > 3)
                                "You have ${tracks.size} tracks, including $sample."
                            else "You have: $sample."
                        }
                    },
                    label(call.name, "query" to (query ?: "*")),
                )
            }
            "play_music" -> {
                val query = call.string("query")
                // The 270M model sometimes invents a query for a generic
                // "play some music" — when the requested match misses, fall
                // back to any track rather than refusing to play.
                val track = device.playMusic(query) ?: device.playMusic(null)
                ToolOutcome(
                    when {
                        track == null -> "No music found on this device."
                        track.artist != null -> "Playing ${track.title} by ${track.artist}."
                        else -> "Playing ${track.title}."
                    },
                    label(call.name, "query" to (query ?: "*")),
                )
            }
            "stop_music" -> {
                device.stopMusic()
                ToolOutcome(say ?: "Music stopped.", label(call.name))
            }
            "set_volume" -> {
                val level = (call.int("level") ?: return null).coerceIn(VOLUME_RANGE)
                device.setVolume(level)
                ToolOutcome(say ?: "Volume $level.", label(call.name, "level" to level))
            }
            "list_capabilities" -> ToolOutcome(CAPABILITIES_SUMMARY, label(call.name))
            else -> null
        }
    }

    /**
     * Run an external action after its spoken confirmation. Launching another
     * Activity sooner triggers [android.app.Activity.onStop], which tears down
     * the demo's AudioTrack while Pocket TTS is still writing to it.
     */
    fun executeDeferredAction(outcome: ToolOutcome, device: DeviceActions) {
        when (val action = outcome.deferredAction) {
            is DeferredDeviceAction.Dial -> device.dial(action.number)
            null -> Unit
        }
    }

    private fun label(name: String, vararg args: Pair<String, Any?>): String =
        "$name {${args.joinToString(", ") { "${it.first}: ${it.second}" }}}"
}

// ---------------------------------------------------------------------------
// Typed accessors over parsed FunctionGemma arguments. The 270M model
// occasionally emits an int where a string is declared (or vice versa), so
// each accessor coerces the near-miss encodings instead of failing the turn.
// ---------------------------------------------------------------------------

internal fun FunctionCall.string(key: String): String? = when (val v = arguments[key]) {
    is ArgumentValue.Str -> v.value.trim().ifEmpty { null }
    is ArgumentValue.Int -> v.value.toString()
    is ArgumentValue.Double -> v.value.toString()
    else -> null
}

internal fun FunctionCall.int(key: String): Int? = when (val v = arguments[key]) {
    is ArgumentValue.Int -> v.value.toInt()
    is ArgumentValue.Double -> v.value.toInt()
    is ArgumentValue.Str -> v.value.trim().toDoubleOrNull()?.toInt()
    else -> null
}
