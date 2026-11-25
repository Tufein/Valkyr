package com.tufein.valkyr.util

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** 9.4: one finished play session as it travels: the game, the span, and the handheld it was played on. */
data class SyncSession(val game: String, val startMs: Long, val endMs: Long, val device: String)

/**
 * 9.4: play history between your own handhelds (`<root>/sync/plays_<profile>.json`). Finished sessions
 * only, each named after the handheld it was measured on. Merging is a union: a session is the same
 * session on both sides when game, start and end match, so nothing is counted twice and nothing is
 * lost, whatever the order the handhelds sync in. A running session never travels. The document is
 * bounded like the local file (newest sessions win). Pure JVM.
 */
object PlayHistorySync {
    const val FORMAT = "valkyr-play-history"
    const val VERSION = 1
    const val MAX_DEVICE = 40

    fun fileName(profileName: String): String = DocFiles.name("plays", profileName)

    private fun key(s: SyncSession) = "${s.game}|${s.startMs}|${s.endMs}"

    /** Newest first for the bound, then in one fixed order so equal documents compare equal. */
    private fun canonical(all: Collection<SyncSession>): List<SyncSession> =
        all.sortedWith(compareByDescending<SyncSession> { it.startMs }.thenBy { it.game }).take(PlaySessions.MAX_SESSIONS)
            .sortedWith(compareBy<SyncSession> { it.startMs }.thenBy { it.game }.thenBy { it.endMs })

    /** This handheld's finished sessions of [profileId]; sessions not named yet get [deviceName]. */
    fun snapshot(sessions: List<PlaySessions.Session>, profileId: String, deviceName: String): List<SyncSession> {
        val name = deviceName.trim().take(MAX_DEVICE)
        return canonical(PlaySessions.finished(sessions).filter { it.profileId == profileId && it.lengthMs > 0L }.map {
            SyncSession(it.gameId, it.startMs, it.endMs, it.device.ifBlank { name })
        })
    }

    fun merge(mine: List<SyncSession>, remote: List<SyncSession>?): List<SyncSession> {
        if (remote == null) return mine
        val out = LinkedHashMap<String, SyncSession>()
        for (s in mine + remote) {
            val known = out[key(s)]
            if (known == null || (known.device.isBlank() && s.device.isNotBlank())) out[key(s)] = s
        }
        return canonical(out.values)
    }

    /** What the merged document says as sessions of [profileId] on this handheld. */
    fun toSessions(doc: List<SyncSession>, profileId: String): List<PlaySessions.Session> =
        doc.map { PlaySessions.Session(it.game, profileId, it.startMs, it.endMs, it.device) }

    fun encode(doc: List<SyncSession>, rev: String, now: Long): String = JsonObject().apply {
        addProperty("format", FORMAT); addProperty("version", VERSION); addProperty("updatedAt", now); addProperty("rev", rev)
        add("sessions", JsonArray().also { arr ->
            doc.forEach { s ->
                arr.add(JsonObject().apply {
                    addProperty("g", s.game); addProperty("s", s.startMs); addProperty("e", s.endMs)
                    if (s.device.isNotBlank()) addProperty("d", s.device)
                })
            }
        })
    }.toString()

    /** A broken or implausible row is skipped, never the whole history. */
    fun decode(text: String): DocParsed<List<SyncSession>> = runCatching {
        val o = JsonParser.parseString(text).asJsonObject
        if (o.get("format")?.asString != FORMAT) return@runCatching DocParsed.Invalid
        val version = o.get("version")?.asInt ?: return@runCatching DocParsed.Invalid
        if (version > VERSION) return@runCatching DocParsed.Newer(version)
        val rows = ArrayList<SyncSession>()
        for (el in o.getAsJsonArray("sessions")) {
            val e = runCatching { el.asJsonObject }.getOrNull() ?: continue
            val game = runCatching { e.get("g").asString }.getOrNull()?.takeIf { it.length in 3..1_200 && '|' in it } ?: continue
            val start = runCatching { e.get("s").asLong }.getOrNull()?.takeIf { it > 0L } ?: continue
            val end = runCatching { e.get("e").asLong }.getOrNull() ?: continue
            if (end <= start || end - start > PlaySessions.MAX_SESSION_MS) continue
            val device = runCatching { e.get("d").asString }.getOrNull().orEmpty().take(MAX_DEVICE)
            rows += SyncSession(game, start, end, device)
        }
        DocParsed.Ok(canonical(rows), o.get("rev")?.asString.orEmpty())
    }.getOrDefault(DocParsed.Invalid)
}

object PlayHistoryCodec : DocSyncEngine.Codec<List<SyncSession>> {
    override fun decode(text: String) = PlayHistorySync.decode(text)
    override fun encode(doc: List<SyncSession>, rev: String, now: Long) = PlayHistorySync.encode(doc, rev, now)
    override fun isEmpty(doc: List<SyncSession>) = doc.isEmpty()
}
