package com.tufein.valkyr.util

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 9.0: play sessions measured around a launch and the app returning to the foreground.
 *
 * Pure JVM: merging, clamping a backwards clock, and totalling per game and profile. The service
 * (PlaySessionService) gathers start/stop events; this object decides what they mean. A clock that
 * jumps backwards is clamped so a session can never be negative, a crash mid-session loses at most
 * the running session, and a session is never counted twice.
 */
object PlaySessions {
    const val FORMAT = "valkyr-play-sessions"
    const val VERSION = 1

    /** A device left with the emulator open is not twelve hours of play. */
    const val MAX_SESSION_MS = 12 * 60 * 60 * 1000L

    /** 9.7: a start that is over this fast did not run: it is not counted as play time, and the game page says so. */
    const val QUICK_STOP_MS = 15_000L

    /** A bounded file: 20 games played twice a day for three months still fits. */
    const val MAX_SESSIONS = 4_000

    /** One measured session. [endMs] of 0 means "still running" (lost on a crash). */
    data class Session(
        val gameId: String,
        val profileId: String,
        val startMs: Long,
        val endMs: Long,
        /** 9.4: the handheld the session was played on; empty until a sync names it. */
        val device: String = ""
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
        val lastPlayedMs: Long?,
        /** 9.4: the handhelds this game was played on, as far as a sync named them. */
        val devices: List<String> = emptyList()
    ) {
        companion object { val EMPTY = Totals(0L, 0, null, null) }
    }

    /** 9.4: everything one profile has played, for the Cloud hub. */
    data class Summary(val timePlayedMs: Long, val sessions: Int, val devices: Int)

    fun summary(sessions: List<Session>, profileId: String): Summary {
        val mine = finished(sessions).filter { it.profileId == profileId }
        return Summary(mine.sumOf { it.lengthMs }, mine.size, mine.map { it.device }.filter { it.isNotBlank() }.toSet().size)
    }

    /** The key two copies of one session share, whoever named the handheld. */
    fun key(s: Session): String = "${s.gameId}|${s.profileId}|${s.startMs}|${s.endMs}"

    /** Clamp a session whose clock jumped backwards: it becomes a zero-length session. */
    fun clamp(session: Session): Session =
        if (session.endMs in 1 until session.startMs) session.copy(endMs = session.startMs) else session

    /** Drop sessions that are still running (endMs 0) — a crash loses at most the running one. */
    fun finished(sessions: List<Session>): List<Session> = sessions.filter { it.endMs > 0L }

    /** Merge two session lists, dropping exact duplicates (same game, profile, start, end); a copy that names its handheld wins. */
    fun merge(a: List<Session>, b: List<Session>): List<Session> {
        val out = LinkedHashMap<String, Session>()
        for (s in a + b) {
            val key = key(s)
            val known = out[key]
            if (known == null || (known.device.isBlank() && s.device.isNotBlank())) out[key] = s
        }
        return out.values.toList()
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
            lastPlayedMs = mine.maxOf { it.endMs },
            devices = mine.map { it.device }.filter { it.isNotBlank() }.distinct().sorted()
        )
    }

    /** Total time played across all games for a profile. */
    fun totalTime(sessions: List<Session>, profileId: String): Long =
        finished(sessions).filter { it.profileId == profileId }.sumOf { it.lengthMs }

    /** Sessions in a single calendar day (by epoch ms range, inclusive start, exclusive end). */
    fun inDay(sessions: List<Session>, dayStartMs: Long, dayEndMs: Long): List<Session> =
        sessions.filter { it.startMs in dayStartMs until dayEndMs }

    /** Keeps the newest sessions, so the file cannot grow without bound. */
    fun trim(sessions: List<Session>): List<Session> =
        sessions.sortedByDescending { it.startMs }.take(MAX_SESSIONS)

    /** True for a closed session shorter than [QUICK_STOP_MS]. */
    fun quickStop(session: Session): Boolean = session.endMs > session.startMs && session.endMs - session.startMs < QUICK_STOP_MS

    /** Closes a running session, bounded by [MAX_SESSION_MS]. */
    fun close(session: Session, now: Long): Session {
        val end = minOf(now, session.startMs + MAX_SESSION_MS)
        return clamp(session.copy(endMs = if (end > session.startMs) end else 0L))
    }

    /** Fixed field names: this file is written on the device and may be copied with a backup. */
    fun toJson(sessions: List<Session>): String {
        val root = JsonObject()
        root.addProperty("format", FORMAT)
        root.addProperty("version", VERSION)
        val array = JsonArray()
        trim(sessions).forEach { s ->
            val o = JsonObject()
            o.addProperty("gameId", s.gameId)
            o.addProperty("profileId", s.profileId)
            o.addProperty("startMs", s.startMs)
            o.addProperty("endMs", s.endMs)
            if (s.device.isNotBlank()) o.addProperty("device", s.device)
            array.add(o)
        }
        root.add("sessions", array)
        return root.toString()
    }

    /** An unknown version or a broken row is ignored rather than losing the whole history. */
    fun fromJson(json: String?): List<Session> {
        if (json.isNullOrBlank()) return emptyList()
        val root = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull() ?: return emptyList()
        if (root.field("format") != FORMAT || root.fieldLong("version")?.toInt() != VERSION) return emptyList()
        val rows = runCatching { root.getAsJsonArray("sessions") }.getOrNull() ?: return emptyList()
        return rows.mapNotNull { row ->
            val o = runCatching { row.asJsonObject }.getOrNull() ?: return@mapNotNull null
            val gameId = o.field("gameId") ?: return@mapNotNull null
            val profileId = o.field("profileId") ?: return@mapNotNull null
            val start = o.fieldLong("startMs") ?: return@mapNotNull null
            val end = o.fieldLong("endMs") ?: return@mapNotNull null
            if (gameId.isBlank() || start <= 0L) null else Session(gameId, profileId, start, end, o.field("device").orEmpty().take(PlayHistorySync.MAX_DEVICE))
        }.let(::trim)
    }

    private fun JsonObject.field(name: String): String? = runCatching { get(name)?.asString }.getOrNull()
    private fun JsonObject.fieldLong(name: String): Long? = runCatching { get(name)?.asLong }.getOrNull()
}