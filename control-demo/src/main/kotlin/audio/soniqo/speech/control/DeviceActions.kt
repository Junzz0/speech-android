package audio.soniqo.speech.control

/** A contact hit from the address book. */
data class Contact(val name: String, val number: String)

/** A playable local audio track. */
data class Track(val title: String, val artist: String?)

/** An Android action that must run after the spoken reply has drained. */
sealed interface DeferredDeviceAction {
    /** Opening the dialer backgrounds the demo Activity, so it cannot overlap TTS playback. */
    data class Dial(val number: String) : DeferredDeviceAction
}

/** Result of executing one tool call. */
data class ToolOutcome(
    /** What the agent says — model-authored `say` or a result template. */
    val spoken: String,
    /** Compact log label, e.g. `call_contact {name: anna}`. */
    val label: String,
    /** Optional external action to run once [spoken] has finished playing. */
    val deferredAction: DeferredDeviceAction? = null,
)

/**
 * Device operations the voice tools bind to. The activity implements these
 * with real Android APIs (ContactsContract, MediaStore/MediaPlayer,
 * AudioManager, ACTION_DIAL); unit tests substitute a fake.
 */
interface DeviceActions {
    /** True while a track is actively playing — gates the stop_music tool. */
    val isMusicPlaying: Boolean

    /** Best fuzzy match from the address book, or null. */
    fun lookupContact(name: String): Contact?

    /** Open the dialer with [number] (ACTION_DIAL — user confirms the call). */
    fun dial(number: String)

    /** Start playing a local track matching [query] (null = any). */
    fun playMusic(query: String?): Track?

    /** Tracks matching [query] (null = list the library), without playing. */
    fun listMusic(query: String?): List<Track>

    fun stopMusic()

    /** Media volume, 0..10. */
    fun setVolume(level: Int)
}
