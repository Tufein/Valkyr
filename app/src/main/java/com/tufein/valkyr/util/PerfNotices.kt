package com.tufein.valkyr.util

/**
 * 6.3 performance notices: a short hint while the app is open when the device struggles (hot, short of
 * memory, almost out of room for games). This object holds the thresholds and the rate limit; the
 * readings come from [com.tufein.valkyr.data.service.PerfNoticeService]. Pure JVM for the tests.
 */
object PerfNotices {

    /** Persisted switch (default on), its backup type is in [SETTING_TYPES]. */
    const val KEY = "perf63_notices"
    const val DEFAULT_ON = true
    val SETTING_TYPES: Map<String, String> = mapOf(KEY to "b")

    /** One kind of notice is shown at most once per this long. */
    const val GAP_MS = 30L * 60_000L

    /** Notices kept for the list in Tools, newest first. */
    const val CAPACITY = 10

    /** PowerManager.THERMAL_STATUS_SEVERE, the same line the heat rule of the download schedule uses. */
    const val THERMAL_SEVERE = ThermalLatch.SEVERE

    /** Less room than this on the games volume counts as very low (the same line as the health check's problem level). */
    const val LOW_STORAGE_BYTES = HealthRules.LOW_FREE_PROBLEM

    enum class Kind { HOT, LOW_MEMORY, LOW_STORAGE }

    /** What the device says right now; [freeBytes] is null when there is no games folder or it cannot be read. */
    data class Reading(val thermalStatus: Int = 0, val lowMemory: Boolean = false, val freeBytes: Long? = null)

    data class Notice(val kind: Kind, val at: Long)

    /** Which problems [reading] shows, in a fixed order. */
    fun conditions(reading: Reading): List<Kind> = buildList {
        if (reading.thermalStatus >= THERMAL_SEVERE) add(Kind.HOT)
        if (reading.lowMemory) add(Kind.LOW_MEMORY)
        val free = reading.freeBytes
        if (free != null && free in 0 until LOW_STORAGE_BYTES) add(Kind.LOW_STORAGE)
    }

    /** Decides what to show and remembers what was shown; not thread-safe, the caller serialises. */
    class Tracker(private val gapMs: Long = GAP_MS, private val capacity: Int = CAPACITY) {
        private val lastShown = HashMap<Kind, Long>()
        private val log = ArrayDeque<Notice>()

        /** The kinds to show now: present in [reading] and not shown within the gap. They are recorded as shown. */
        fun due(reading: Reading, nowMs: Long): List<Kind> {
            val out = conditions(reading).filter { kind ->
                val last = lastShown[kind]
                last == null || nowMs < last || nowMs - last >= gapMs
            }
            out.forEach { kind ->
                lastShown[kind] = nowMs
                log.addFirst(Notice(kind, nowMs))
                while (log.size > capacity) log.removeLast()
            }
            return out
        }

        /** The notices shown, newest first. */
        fun history(): List<Notice> = log.toList()
    }
}
