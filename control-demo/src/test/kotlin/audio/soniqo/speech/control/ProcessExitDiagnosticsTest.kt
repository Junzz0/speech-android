package audio.soniqo.speech.control

import org.junit.Assert.assertEquals
import org.junit.Test

class ProcessExitDiagnosticsTest {

    @Test
    fun `native crash summary includes signal phase and sampled memory`() {
        val exit = PreviousProcessExit(
            kind = ProcessExitKind.NATIVE_CRASH,
            status = 11,
            timestampMs = 1L,
            phase = "thinking",
            pssKb = 903L * 1024L,
            rssKb = 0L,
            description = null,
        )

        assertEquals(
            "Previous run ended unexpectedly: native crash (signal 11) during thinking" +
                " · last PSS 903 MB",
            exit.summaryLine(),
        )
    }

    @Test
    fun `java crash summary omits meaningless exit status and unavailable fields`() {
        val exit = PreviousProcessExit(
            kind = ProcessExitKind.JAVA_CRASH,
            status = 1,
            timestampMs = 1L,
            phase = null,
            pssKb = 0L,
            rssKb = 0L,
            description = "java.lang.IllegalStateException",
        )

        assertEquals(
            "Previous run ended unexpectedly: Java/Kotlin crash",
            exit.summaryLine(),
        )
    }
}
