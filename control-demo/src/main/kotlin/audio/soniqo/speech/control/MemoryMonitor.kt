package audio.soniqo.speech.control

import java.io.File

/**
 * Background sampler for current/peak process memory — the Android analog of
 * soniqo/runner's `memoryFootprintMB()` (mach `phys_footprint`) and
 * speech-swift's `MemoryMonitor`. Reads PSS from `/proc/self/smaps_rollup`
 * (readable for our own process, no throttling — unlike
 * `ActivityManager.getProcessMemoryInfo`, which is rate-limited since O),
 * falling back to RSS from `/proc/self/statm`.
 */
class MemoryMonitor {

    @Volatile private var running = false
    @Volatile var peakMb: Int = 0
        private set

    fun currentMb(): Int = readPssKb().let { kb -> if (kb > 0) (kb / 1024).toInt() else 0 }

    /** Record [currentMb] into the peak immediately (call at load milestones). */
    fun sample(): Int {
        val mb = currentMb()
        if (mb > peakMb) peakMb = mb
        return mb
    }

    fun start() {
        if (running) return
        running = true
        Thread {
            while (running) {
                sample()
                try { Thread.sleep(SAMPLE_INTERVAL_MS) } catch (_: InterruptedException) { break }
            }
        }.apply {
            name = "MemoryMonitor"
            isDaemon = true
        }.start()
    }

    fun stop() { running = false }

    private fun readPssKb(): Long {
        // smaps_rollup: "Pss:      123456 kB"
        runCatching {
            File("/proc/self/smaps_rollup").useLines { lines ->
                lines.firstOrNull { it.startsWith("Pss:") }
                    ?.let { return parseKbLine(it) }
            }
        }
        // Fallback: statm field 2 is resident pages.
        runCatching {
            val fields = File("/proc/self/statm").readText().trim().split(' ')
            val pages = fields.getOrNull(1)?.toLongOrNull() ?: return -1
            return pages * PAGE_SIZE_BYTES / 1024
        }
        return -1
    }

    private fun parseKbLine(line: String): Long =
        line.removePrefix("Pss:").trim().removeSuffix("kB").trim().toLongOrNull() ?: -1

    companion object {
        private const val SAMPLE_INTERVAL_MS = 250L
        private const val PAGE_SIZE_BYTES = 4096L
    }
}
