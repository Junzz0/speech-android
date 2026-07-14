package audio.soniqo.speech.control

import audio.soniqo.speech.llm.ArgumentValue
import audio.soniqo.speech.llm.FunctionCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlToolsTest {

    private class FakeDevice : DeviceActions {
        val contacts = mutableMapOf(
            "anna" to Contact("Anna Miller", "+4917612345678"),
        )
        var dialed: String? = null
        var playing: Track? = Track("Morning Drive", "The Layers")
        var queryMatches = true
        var stopped = false
        var musicPlaying = false
        var lastVolume = -1

        override val isMusicPlaying: Boolean get() = musicPlaying

        override fun lookupContact(name: String): Contact? =
            contacts.entries.firstOrNull { name.lowercase().contains(it.key) }?.value

        override fun dial(number: String) { dialed = number }

        override fun playMusic(query: String?): Track? =
            if (query != null && !queryMatches) null else playing

        var library = listOf(
            Track("Feeling Good", "Nina Simone"),
            Track("Hotel California", null),
            Track("Morning Drive", "The Layers"),
            Track("Take Five", "Dave Brubeck"),
        )

        override fun listMusic(query: String?): List<Track> =
            if (query == null) library
            else library.filter {
                it.title.contains(query, ignoreCase = true) ||
                    it.artist?.contains(query, ignoreCase = true) == true
            }

        override fun stopMusic() { stopped = true; musicPlaying = false }

        override fun setVolume(level: Int) { lastVolume = level }
    }

    private val device = FakeDevice()

    private fun call(name: String, vararg args: Pair<String, ArgumentValue>) =
        FunctionCall(name, args.toMap())

    @Test
    fun selectSingleCall_acceptsExactlyOneAction() {
        val only = call("set_volume", "level" to ArgumentValue.Int(3))

        assertEquals(only, ControlTools.selectSingleCall(listOf(only)))
        assertNull(ControlTools.selectSingleCall(emptyList()))
        assertNull(ControlTools.selectSingleCall(listOf(only, only)))
    }

    // ------------------------------------------------------------------
    // say parameter: honored on actions, overridden by lookup results
    // ------------------------------------------------------------------

    @Test
    fun callContact_found_dialsAndSpeaksModelSay() {
        val outcome = ControlTools.execute(call(
            "call_contact",
            "name" to ArgumentValue.Str("anna"),
            "say" to ArgumentValue.Str("Calling Anna now."),
        ), device)!!

        assertEquals("+4917612345678", device.dialed)
        assertEquals("Calling Anna now.", outcome.spoken)
        assertEquals("call_contact {name: anna}", outcome.label)
    }

    @Test
    fun callContact_notFound_neverSpeaksSuccessSay() {
        val outcome = ControlTools.execute(call(
            "call_contact",
            "name" to ArgumentValue.Str("bob"),
            "say" to ArgumentValue.Str("Calling Bob."),
        ), device)!!

        assertNull(device.dialed)
        assertEquals("I couldn't find bob in your contacts.", outcome.spoken)
    }

    @Test
    fun callContact_missingSay_usesTemplateWithResolvedName() {
        val outcome = ControlTools.execute(call(
            "call_contact", "name" to ArgumentValue.Str("anna")), device)!!

        assertEquals("Calling Anna Miller.", outcome.spoken)
    }

    @Test
    fun findContact_speaksActualResult_ignoringSay() {
        val outcome = ControlTools.execute(call(
            "find_contact",
            "name" to ArgumentValue.Str("anna"),
            "say" to ArgumentValue.Str("Looking up Anna."),
        ), device)!!

        assertEquals("Anna Miller's number is +4917612345678.", outcome.spoken)
    }

    @Test
    fun findContact_miss_speaksNotFound() {
        val outcome = ControlTools.execute(call(
            "find_contact", "name" to ArgumentValue.Str("zoe")), device)!!

        assertEquals("No contact named zoe.", outcome.spoken)
    }

    @Test
    fun findMusic_noQuery_listsLibrary_ignoringSay() {
        val outcome = ControlTools.execute(call(
            "find_music",
            "say" to ArgumentValue.Str("Here is your music."),
        ), device)!!

        assertEquals(
            "You have 4 tracks, including Feeling Good by Nina Simone, " +
                "Hotel California, Morning Drive by The Layers.",
            outcome.spoken,
        )
        assertEquals("find_music {query: *}", outcome.label)
    }

    @Test
    fun findMusic_query_speaksMatches() {
        val outcome = ControlTools.execute(call(
            "find_music", "query" to ArgumentValue.Str("nina")), device)!!

        assertEquals("You have: Feeling Good by Nina Simone.", outcome.spoken)
    }

    @Test
    fun findMusic_miss_speaksNoMatch() {
        val outcome = ControlTools.execute(call(
            "find_music", "query" to ArgumentValue.Str("polka")), device)!!

        assertEquals("No music matching polka.", outcome.spoken)
    }

    @Test
    fun findMusic_emptyLibrary_speaksNoMusic() {
        device.library = emptyList()
        val outcome = ControlTools.execute(call("find_music"), device)!!

        assertEquals("There is no music on this device.", outcome.spoken)
    }

    @Test
    fun playMusic_speaksActualTrack() {
        val outcome = ControlTools.execute(call(
            "play_music",
            "query" to ArgumentValue.Str("morning"),
            "say" to ArgumentValue.Str("Playing music."),
        ), device)!!

        assertEquals("Playing Morning Drive by The Layers.", outcome.spoken)
        assertEquals("play_music {query: morning}", outcome.label)
    }

    @Test
    fun playMusic_queryMiss_fallsBackToAnyTrack() {
        device.queryMatches = false
        val outcome = ControlTools.execute(call(
            "play_music", "query" to ArgumentValue.Str("polka")), device)!!

        assertEquals("Playing Morning Drive by The Layers.", outcome.spoken)
    }

    @Test
    fun playMusic_emptyLibrary_speaksMiss() {
        device.playing = null
        val outcome = ControlTools.execute(call("play_music"), device)!!

        assertEquals("No music found on this device.", outcome.spoken)
    }

    @Test
    fun playMusic_unknownArtist_omitsBy() {
        device.playing = Track("Test Tone", null)
        val outcome = ControlTools.execute(call("play_music"), device)!!

        assertEquals("Playing Test Tone.", outcome.spoken)
    }

    // ------------------------------------------------------------------
    // Simple actions
    // ------------------------------------------------------------------

    @Test
    fun dialNumber_dialsAndHonorsSay() {
        val outcome = ControlTools.execute(call(
            "dial_number",
            "number" to ArgumentValue.Str("+493012345"),
            "say" to ArgumentValue.Str("Dialing."),
        ), device)!!

        assertEquals("+493012345", device.dialed)
        assertEquals("Dialing.", outcome.spoken)
    }

    @Test
    fun stopMusic_stops() {
        val outcome = ControlTools.execute(call("stop_music"), device)!!

        assertTrue(device.stopped)
        assertEquals("Music stopped.", outcome.spoken)
    }

    @Test
    fun setVolume_clampsAndTemplates() {
        val outcome = ControlTools.execute(call(
            "set_volume", "level" to ArgumentValue.Int(42)), device)!!

        assertEquals(10, device.lastVolume)
        assertEquals("Volume 10.", outcome.spoken)
    }

    @Test
    fun setVolume_coercesStringLevel() {
        ControlTools.execute(call(
            "set_volume", "level" to ArgumentValue.Str("3")), device)!!

        assertEquals(3, device.lastVolume)
    }

    // ------------------------------------------------------------------
    // Declarations + failure paths
    // ------------------------------------------------------------------

    @Test
    fun everyDeclaration_requiresSayParameter() {
        for (tool in ControlTools.declarations) {
            @Suppress("UNCHECKED_CAST")
            val properties = tool.parameters["properties"] as Map<String, Any?>
            assertTrue("${tool.name} missing say property", "say" in properties)
            @Suppress("UNCHECKED_CAST")
            val required = tool.parameters["required"] as List<String>
            assertTrue("${tool.name} does not require say", "say" in required)
        }
    }

    @Test
    fun listCapabilities_speaksTheRealSurface_ignoringSay() {
        val outcome = ControlTools.execute(call(
            "list_capabilities",
            "say" to ArgumentValue.Str("I can do everything!"),
        ), device)!!

        assertEquals(ControlTools.CAPABILITIES_SUMMARY, outcome.spoken)
        // The summary must cover every user-facing capability group.
        for (word in listOf("contacts", "dial", "music", "volume")) {
            assertTrue("summary missing '$word'", word in outcome.spoken)
        }
    }

    @Test
    fun listCapabilities_isDeclaredToTheModel() {
        assertTrue(ControlTools.declarations.any { it.name == "list_capabilities" })
    }

    @Test
    fun availableTools_offersStopOnlyWhileMusicPlays() {
        val idle = ControlTools.availableTools(musicPlaying = false).map { it.name }
        assertFalse("stop_music must not be offered when nothing plays", "stop_music" in idle)
        assertTrue("play_music always available", "play_music" in idle)
        assertTrue("call_contact always available", "call_contact" in idle)

        val playing = ControlTools.availableTools(musicPlaying = true).map { it.name }
        assertTrue("stop_music offered while playing", "stop_music" in playing)
    }

    @Test
    fun callingStopsMusicFirst() {
        device.musicPlaying = true
        ControlTools.execute(call(
            "call_contact", "name" to ArgumentValue.Str("anna")), device)!!
        assertTrue("a call must stop any playing music", device.stopped)
        assertEquals("+4917612345678", device.dialed)

        device.stopped = false
        ControlTools.execute(call(
            "dial_number", "number" to ArgumentValue.Str("123")), device)!!
        assertTrue("dialing must stop any playing music", device.stopped)
    }

    @Test
    fun unknownTool_returnsNull() {
        assertNull(ControlTools.execute(call("format_disk"), device))
    }

    @Test
    fun missingRequiredArg_returnsNull() {
        assertNull(ControlTools.execute(call("call_contact"), device))
        assertNull(ControlTools.execute(call("dial_number"), device))
        assertNull(ControlTools.execute(call("set_volume"), device))
    }
}
