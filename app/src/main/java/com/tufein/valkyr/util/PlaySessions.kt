package com.tufein.valkyr.util

/**
 * 9.0: play sessions measured around a launch and the app returning to the foreground.
 *
 * Pure JVM: merging, clamping a backwards clock, and totalling per game and profile. The service
 * (PlaySessionService) gathers start/stop events; this object decides what they mean. A clock that
 * jumps backwards is clamped so a session can never be negative, a crash mid-session loses at most
 * the running session, and a session is never counted twice.
 */
object PlaySessions {

    /** One measured session. [endMs] of 0 means "still running" (lost on a crash). */
    data class Session(
        val gameId: String,
        val profileId: String,
        val startMs: Long,
        val endMs: Long
    ) {
        /** Length in ms, never negative. A running session (endMs 0) counts 0 until it stops. */
        val lengthMs: Long get() = when {
            endMs <= 0L -> 0L
            endMs < startMs -> 0L
            else -> endMs - startMs
        }
    }

    data class Totals(
        val timePlayedMs: Long,
        val sessions: Int,
        val firstPlayedMs: Long?,
        val lastPlayedMs: Long?
    ) {
        companion object { val EMPTY = Totals(0L, 0, null, null) }
    }

    /** Clamp a session whose clock jumped backwards: it becomes a zero-length session. */
    fun clamp(session: Session): Session =
        if (session.endMs in 1 until session.startMs) session.copy(endMs = session.startMs) else session

    /** Drop sessions that are still running (endMs 0) — a crash loses at most the running one. */
    fun finished(sessions: List<Session>): List<Session> = sessions.filter { it.endMs > 0L }

    /** Merge two session lists, dropping exact duplicates (same game, profile, start, end). */
    fun merge(a: List<Session>, b: List<Session>): List<Session> {
        val seen = HashSet<String>()
        val out = ArrayList<Session>()
        for (s in a + b) {
            val key = "${s.gameId}|${s.profileId}|${s.startMs}|${s.endMs}"
            if (seen.add(key)) out += s
        }
        return out
    }

    /** Sessions for one game and profile. */
    fun forGame(sessions: List<Session>, gameId: String, profileId: String): List<Session> =
        sessions.filter { it.gameId == gameId && it.profileId == profileId }

    /** Totals for one game and profile (finished sessions only). */
    fun totals(sessions: List<Session>, gameId: String, profileId: String): Totals {
        val mine = finished(forGame(sessions, gameId, profileId))
        if (mine.isEmpty()) return Totals.EMPTY
        return Totals(
            timePlayedMs = mine.sumOf { it.lengthMs },
            sessions = mine.size,
            firstPlayedMs = mine.minOf { it.startMs },
            lastPlayedMs = mine.maxOf { it.endMs }
        )
    }

    /** Total time played across all games for a profile. */
    fun totalTime(sessions: List<Session>, profileId: String): Long =
        finished(sessions).filter { it.profileId == profileId }.sumOf { it.lengthMs }

    /** Sessions in a single calendar day (by epoch ms range, inclusive start, exclusive end). */
    fun inDay(sessions: List<Session>, dayStartMs: Long, dayEndMs: Long): List<Session> =
        sessions.filter { it.startMs in dayStartMs until dayEndMs }
}