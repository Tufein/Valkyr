package com.tufein.valkyr.util

/**
 * How a console's sources are doing, for the 5.0 Sources cards: one state from the last scan of
 * each enabled source. Pure JVM for the tests.
 */
object SourceHealth {

    /** 7.8: one remembered scan of one source: when, and why it failed (null = it answered). */
    data class Outcome(val at: Long, val failure: FailureKind?)

    /**
     * What a source's recent history says, for one extra line: [failures] of the last [scans] in the
     * past [WINDOW_DAYS] days failed, [streak] failed in a row up to now, the newest failure was [last].
     */
    data class Trend(val failures: Int, val scans: Int, val streak: Int, val last: FailureKind)

    const val KEEP = 12
    const val WINDOW_DAYS = 30L

    /** [history] oldest first. Null while the source looks healthy: one failure that was followed by an answer is not news. */
    fun trend(history: List<Outcome>, now: Long): Trend? {
        val recent = history.filter { now - it.at in 0..WINDOW_DAYS * 86_400_000L }
        val failed = recent.filter { it.failure != null }
        val last = failed.lastOrNull()?.failure ?: return null
        val streak = recent.takeLastWhile { it.failure != null }.size
        if (streak < 2 && failed.size < 2) return null
        return Trend(failed.size, recent.size, streak, last)
    }

    /** [history] with [next] appended, bounded to the last [KEEP]. */
    fun append(history: List<Outcome>, next: Outcome): List<Outcome> = (history + next).takeLast(KEEP)

    fun encode(history: List<Outcome>): String = history.joinToString(",") { "${it.at}:${it.failure?.name ?: ""}" }

    fun decode(text: String?): List<Outcome> = text.orEmpty().split(',').mapNotNull { part ->
        val at = part.substringBefore(':').toLongOrNull() ?: return@mapNotNull null
        val name = part.substringAfter(':', "")
        Outcome(at, if (name.isEmpty()) null else FailureKind.entries.firstOrNull { it.name == name } ?: FailureKind.OTHER)
    }.takeLast(KEEP)

    enum class State {
        /** The console has no enabled source. */
        NO_SOURCES,
        /** None of its sources has been scanned yet. */
        NOT_SCANNED,
        /** Every scanned source answered (some may not be scanned yet). */
        OK,
        /** Some sources failed, others answered. */
        PARTIAL,
        /** Every scanned source failed. */
        FAILED
    }

    data class Health(val state: State, val failed: Int = 0, val scanned: Int = 0)

    /**
     * @param outcomes one entry per enabled source: true = its last scan worked, false = it failed,
     *   null = never scanned.
     */
    fun of(outcomes: List<Boolean?>): Health {
        if (outcomes.isEmpty()) return Health(State.NO_SOURCES)
        val scanned = outcomes.count { it != null }
        val failed = outcomes.count { it == false }
        val state = when {
            scanned == 0 -> State.NOT_SCANNED
            failed == 0 -> State.OK
            failed == scanned -> State.FAILED
            else -> State.PARTIAL
        }
        return Health(state, failed, scanned)
    }
}

/** What kind of address a source is, for its icon and label in Sources. */
enum class SourceKind {
    ROMM, TORRENT, WEB, OTHER;

    companion object {
        /**
         * RomM platforms (`romm://`), torrents (magnets, `.torrent` links, picked or stored torrent
         * files), web directories (http/https); anything else is [OTHER].
         */
        fun of(url: String): SourceKind {
            val u = url.trim()
            val path = u.substringBefore('?').substringBefore('#')
            return when {
                RommSource.isSource(u) -> ROMM
                u.startsWith("magnet:", ignoreCase = true) -> TORRENT
                path.endsWith(".torrent", ignoreCase = true) -> TORRENT
                u.startsWith("/") || u.startsWith("content://", ignoreCase = true) -> TORRENT
                u.startsWith("http://", ignoreCase = true) || u.startsWith("https://", ignoreCase = true) -> WEB
                else -> OTHER
            }
        }
    }
}
