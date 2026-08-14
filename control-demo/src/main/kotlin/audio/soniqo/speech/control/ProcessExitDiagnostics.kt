package audio.soniqo.speech.control

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import java.time.Instant

/** Actionable reason for an unexpected death of the previous app process. */
internal enum class ProcessExitKind(val label: String) {
    JAVA_CRASH("Java/Kotlin crash"),
    NATIVE_CRASH("native crash"),
    ANR("app not responding"),
    LOW_MEMORY("system low-memory kill"),
    SIGNAL("signal termination"),
    EXCESSIVE_RESOURCES("excessive-resource kill"),
    INITIALIZATION_FAILURE("initialization failure"),
    DEPENDENCY_DIED("dependency process died"),
}

internal data class PreviousProcessExit(
    val kind: ProcessExitKind,
    val status: Int,
    val timestampMs: Long,
    val phase: String?,
    val pssKb: Long,
    val rssKb: Long,
    val description: String?,
) {
    fun summaryLine(): String = buildString {
        append("Previous run ended unexpectedly: ")
        append(kind.label)
        if (status != 0 && kind in setOf(ProcessExitKind.NATIVE_CRASH, ProcessExitKind.SIGNAL)) {
            append(" (signal ").append(status).append(')')
        }
        phase?.let { append(" during ").append(it) }
        if (pssKb > 0) append(" · last PSS ").append(kbToMb(pssKb)).append(" MB")
    }
}

internal fun buildDiagnosticsReport(
    appVersion: String,
    previousExit: PreviousProcessExit?,
): String = buildString {
    appendLine("Soniqo Control diagnostics")
    appendLine("App: $appVersion")
    appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
    appendLine(
        "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}; ${Build.DISPLAY})"
    )
    if (previousExit == null) {
        append("Previous unexpected exit: none recorded")
    } else {
        appendLine("Previous exit: ${previousExit.kind.label}")
        appendLine("Timestamp: ${Instant.ofEpochMilli(previousExit.timestampMs)}")
        appendLine("Status/signal: ${previousExit.status}")
        appendLine("Recorded phase: ${previousExit.phase ?: "unknown"}")
        appendLine("Last sampled PSS: ${formatMemory(previousExit.pssKb)}")
        appendLine("Last sampled RSS: ${formatMemory(previousExit.rssKb)}")
        append("System description: ${previousExit.description ?: "unavailable"}")
    }
}

private fun kbToMb(kb: Long): Long = (kb + 512L) / 1024L

private fun formatMemory(kb: Long): String =
    if (kb > 0) "${kbToMb(kb)} MB" else "unavailable"

/**
 * Records the current coarse pipeline phase and consumes Android's most recent
 * process-exit record on the next launch. No utterance, contact, or media data
 * is stored in the 128-byte process summary.
 */
internal class ProcessExitDiagnostics(context: Context) {
    private val appContext = context.applicationContext
    private val activityManager = appContext.getSystemService(ActivityManager::class.java)
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Volatile private var lastPhase: String? = null

    fun markPhase(phase: String) {
        if (Build.VERSION.SDK_INT < 30) return
        val safePhase = phase
            .lowercase()
            .replace(Regex("[^a-z0-9_-]+"), "_")
            .trim('_')
            .take(64)
            .ifEmpty { "unknown" }
        if (lastPhase == safePhase) return
        lastPhase = safePhase
        runCatching {
            activityManager.setProcessStateSummary("phase=$safePhase".toByteArray(Charsets.UTF_8))
        }.onFailure { Log.w(TAG, "Unable to record process phase", it) }
    }

    fun consumePreviousExit(): PreviousProcessExit? {
        if (Build.VERSION.SDK_INT < 30) return null
        val info = runCatching {
            activityManager.getHistoricalProcessExitReasons(null, 0, 1).firstOrNull()
        }.onFailure { Log.w(TAG, "Unable to read previous process exit", it) }
            .getOrNull() ?: return null

        val lastSeen = preferences.getLong(KEY_LAST_SEEN_EXIT, 0L)
        if (info.timestamp <= lastSeen) return null
        preferences.edit().putLong(KEY_LAST_SEEN_EXIT, info.timestamp).apply()

        val kind = unexpectedKind(info.reason) ?: return null
        return PreviousProcessExit(
            kind = kind,
            status = info.status,
            timestampMs = info.timestamp,
            phase = info.processStateSummary
                ?.toString(Charsets.UTF_8)
                ?.substringAfter("phase=", missingDelimiterValue = "")
                ?.takeIf { it.isNotBlank() },
            pssKb = info.pss,
            rssKb = info.rss,
            description = info.description
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                ?.take(240)
                ?.takeIf { it.isNotEmpty() },
        )
    }

    private fun unexpectedKind(reason: Int): ProcessExitKind? = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> ProcessExitKind.JAVA_CRASH
        ApplicationExitInfo.REASON_CRASH_NATIVE -> ProcessExitKind.NATIVE_CRASH
        ApplicationExitInfo.REASON_ANR -> ProcessExitKind.ANR
        ApplicationExitInfo.REASON_LOW_MEMORY -> ProcessExitKind.LOW_MEMORY
        ApplicationExitInfo.REASON_SIGNALED -> ProcessExitKind.SIGNAL
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> ProcessExitKind.EXCESSIVE_RESOURCES
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE ->
            ProcessExitKind.INITIALIZATION_FAILURE
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> ProcessExitKind.DEPENDENCY_DIED
        else -> null
    }

    private companion object {
        const val TAG = "SpeechControl"
        const val PREFERENCES = "process_exit_diagnostics"
        const val KEY_LAST_SEEN_EXIT = "last_seen_exit_timestamp"
    }
}
