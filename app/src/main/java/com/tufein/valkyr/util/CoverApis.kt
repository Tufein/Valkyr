package com.tufein.valkyr.util

import com.tufein.valkyr.util.CoverSources.Source
import java.net.URI
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneOffset

/**
 * What the three 4.4.0 cover sources share (TheGamesDB, SteamGridDB, IGDB): where their keys are
 * kept, which of them never travel in a backup, the HTTP status classification, the hosts a key may
 * be sent to and the careful title matching. Pure JVM for the tests; the requests are in
 * `TheGamesDbService`, `SteamGridDbService` and `IgdbService`.
 *
 * Everything here is written from the public API documentation of the three services. None of the
 * services could be tried from the machine this was written on.
 */
object CoverApis {

    const val TGDB_KEY = "covers44_tgdb_key"
    const val SGDB_KEY = "covers44_sgdb_key"
    const val IGDB_CLIENT_ID_KEY = "covers44_igdb_client_id"
    const val IGDB_CLIENT_SECRET_KEY = "covers44_igdb_client_secret"

    val SETTING_TYPES: Map<String, String> =
        listOf(TGDB_KEY, SGDB_KEY, IGDB_CLIENT_ID_KEY, IGDB_CLIENT_SECRET_KEY).associateWith { "s" }

    /** Keys and secrets never travel in a backup file or a problem report; the Twitch client id is not a secret. */
    val SECRETS: Set<String> = setOf(TGDB_KEY, SGDB_KEY, IGDB_CLIENT_SECRET_KEY)

    /**
     * The keys the user filled in. [tgdbBuiltIn] is the TheGamesDB key the app was built with for
     * the game details (`BuildConfig.THEGAMESDB_API_KEY`, empty in a build without one): when the
     * user has no key of their own, that same key is used, so nobody is asked twice.
     */
    class Keys(
        val tgdb: String = "",
        val sgdb: String = "",
        val igdbId: String = "",
        val igdbSecret: String = "",
        val tgdbBuiltIn: String = ""
    ) {
        /** The TheGamesDB key in use: the user's own, else the one built into the app. */
        val tgdbKey: String get() = plausible(tgdb) ?: plausible(tgdbBuiltIn).orEmpty()
        /** The user has no TheGamesDB key but the app has one for the game details. */
        val tgdbShared: Boolean get() = plausible(tgdb) == null && plausible(tgdbBuiltIn) != null
        val sgdbKey: String get() = plausible(sgdb).orEmpty()
        val igdbClientId: String get() = plausible(igdbId).orEmpty()
        val igdbClientSecret: String get() = plausible(igdbSecret).orEmpty()

        /** Set up with a key of the user's own (the app's built-in TheGamesDB key does not count). */
        fun ownConfigured(source: Source): Boolean = when (source) {
            Source.THEGAMESDB -> plausible(tgdb) != null
            else -> configured(source)
        }

        fun configured(source: Source): Boolean = when (source) {
            Source.THEGAMESDB -> tgdbKey.isNotEmpty()
            Source.STEAMGRIDDB -> sgdbKey.isNotEmpty()
            Source.IGDB -> igdbClientId.isNotEmpty() && igdbClientSecret.isNotEmpty()
            else -> true
        }

        /** The sources that this set of keys sets up and [before] did not (the first key switches a source on). */
        fun newlyConfigured(before: Keys): Set<Source> =
            OWN.filter { configured(it) && !before.configured(it) }.toSet()

        /** Changes whenever a key changes (a refused key is forgotten then). */
        val fingerprint: Int get() = listOf(tgdbKey, sgdbKey, igdbClientId, igdbClientSecret).hashCode()

        /** The fingerprint of one source's keys. */
        fun fingerprint(source: Source): Int = when (source) {
            Source.THEGAMESDB -> tgdbKey.hashCode()
            Source.STEAMGRIDDB -> sgdbKey.hashCode()
            Source.IGDB -> listOf(igdbClientId, igdbClientSecret).hashCode()
            else -> 0
        }

        override fun equals(other: Any?): Boolean = other is Keys && other.tgdb == tgdb && other.sgdb == sgdb &&
            other.igdbId == igdbId && other.igdbSecret == igdbSecret && other.tgdbBuiltIn == tgdbBuiltIn
        override fun hashCode(): Int = listOf(tgdb, sgdb, igdbId, igdbSecret, tgdbBuiltIn).hashCode()
        /** Never the values: this may end up in a log. */
        override fun toString(): String = "Keys(tgdb=${tgdbKey.isNotEmpty()}, sgdb=${sgdbKey.isNotEmpty()}, igdb=${configured(Source.IGDB)})"
    }

    /** The three sources of 4.4.0 that need a key of this kind. */
    val OWN: List<Source> = listOf(Source.THEGAMESDB, Source.STEAMGRIDDB, Source.IGDB)

    /**
     * A key as it may be sent: trimmed, not empty, no spaces or control characters (a pasted key
     * with a line break would be a malformed header), not absurdly long. Null for anything else.
     */
    fun plausible(value: String?): String? {
        val v = value.orEmpty().trim()
        if (v.isEmpty() || v.length > 200) return null
        if (v.any { it.isWhitespace() || it.isISOControl() }) return null
        return v
    }

    /**
     * After a restore (which never carries a key): the keys of [current] that still belong. A
     * TheGamesDB / SteamGridDB key has no name to compare, so it simply stays. The IGDB secret stays
     * while the restored client id is the same one (or the backup has none); another client id
     * means another application, whose secret this one is not.
     */
    fun keptSecrets(current: Map<String, String>, restored: Map<String, String>, backupHasIgdbId: Boolean): Map<String, String> = buildMap {
        current[TGDB_KEY]?.takeIf { it.isNotEmpty() }?.let { put(TGDB_KEY, it) }
        current[SGDB_KEY]?.takeIf { it.isNotEmpty() }?.let { put(SGDB_KEY, it) }
        val sameId = !backupHasIgdbId || current[IGDB_CLIENT_ID_KEY].orEmpty().trim() == restored[IGDB_CLIENT_ID_KEY].orEmpty().trim()
        current[IGDB_CLIENT_SECRET_KEY]?.takeIf { it.isNotEmpty() && current[IGDB_CLIENT_ID_KEY].orEmpty().isNotBlank() && sameId }
            ?.let { put(IGDB_CLIENT_SECRET_KEY, it) }
    }

    // ---- Answers -------------------------------------------------------------------------------------

    enum class Status {
        OK,
        /** The service has no such thing: a real "none". */
        NOT_FOUND,
        /** Too many requests: wait a minute. */
        RATE_LIMITED,
        /** The key (or client id / secret) is refused; also what a used-up allowance can look like. */
        KEY_REJECTED,
        /** The service refused the request itself; it would be refused again: a miss for this title. */
        BAD_REQUEST,
        /** Server error, closed, anything unexpected: try later, never a miss. */
        BUSY
    }

    fun status(code: Int): Status = when (code) {
        in 200..299 -> Status.OK
        404 -> Status.NOT_FOUND
        429 -> Status.RATE_LIMITED
        401, 403 -> Status.KEY_REJECTED
        408 -> Status.BUSY
        in 400..499 -> Status.BAD_REQUEST
        else -> Status.BUSY
    }

    const val RATE_LIMIT_PAUSE_MS = 60_000L
    const val BUSY_PAUSE_MS = 15 * 60_000L
    const val KEY_PAUSE_MS = 60 * 60_000L
    /** A refused key that may also be a used-up allowance (TheGamesDB's 403) rests only this long. */
    const val SOFT_KEY_PAUSE_MS = 5 * 60_000L
    /** One request may take this long in total, however slowly the service trickles its answer. */
    const val DEADLINE_MS = 20_000L

    /** How long a source rests after [status] (0 = not at all). */
    fun pauseFor(status: Status): Long = when (status) {
        Status.RATE_LIMITED -> RATE_LIMIT_PAUSE_MS
        Status.KEY_REJECTED -> KEY_PAUSE_MS
        Status.BUSY -> BUSY_PAUSE_MS
        else -> 0L
    }

    /** What the Test key button found out. */
    sealed interface KeyTest {
        /** The key works; [remaining] requests are left this month, when the service says. */
        data class Ok(val remaining: Int? = null) : KeyTest
        data object Missing : KeyTest
        data object Rejected : KeyTest
        /** Twitch: the client id is unknown. */
        data object IdRejected : KeyTest
        /** Twitch: the client id exists, the secret is wrong. */
        data object SecretRejected : KeyTest
        data object RateLimited : KeyTest
        data object Busy : KeyTest
        data object Offline : KeyTest
        /** The service answered something that is not what its documentation describes. */
        data object Unreadable : KeyTest
    }

    // ---- Where a key may go --------------------------------------------------------------------------

    private val KEY_HOSTS: Map<Source, Set<String>> = mapOf(
        Source.THEGAMESDB to setOf("api.thegamesdb.net"),
        Source.STEAMGRIDDB to setOf("www.steamgriddb.com"),
        Source.IGDB to setOf("api.igdb.com", "id.twitch.tv")
    )

    /** Whether [url] is an https address on a host that belongs to [source]: the only place its key is ever sent. */
    fun sendsKeyTo(source: Source, url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme.equals("https", ignoreCase = true) && uri.userInfo == null && uri.port in setOf(-1, 443) &&
            uri.host?.lowercase() in (KEY_HOSTS[source] ?: emptySet())
    }.getOrDefault(false)

    /** [url] as an https address on [host] or a sub-domain of it (image addresses from a response), else null. */
    fun httpsOn(url: String, host: String): String? = runCatching {
        val uri = URI(url.trim())
        val h = uri.host?.lowercase() ?: return null
        val ok = uri.scheme.equals("https", ignoreCase = true) && uri.userInfo == null && (h == host || h.endsWith(".$host"))
        if (ok) uri.toString() else null
    }.getOrNull()

    /** Percent-encoding for a path segment (spaces as %20, not +). */
    fun pathSegment(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /** The first day of the next month (UTC), when a monthly allowance starts again. */
    fun nextMonthStart(now: Long): Long {
        val d = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate()
        return d.withDayOfMonth(1).plusMonths(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    }
}

/**
 * Careful title matching for the three 4.4.0 sources. A database search is fuzzy: its first hit is
 * often another game of the same series. A hit is only taken when its title is the same title
 * (the same words once tags, "the" and accents are left out) or the same plus or minus one plain
 * word (a subtitle), never when the difference is a number or a Roman numeral, and never from a
 * short title. Among the hits that qualify, an identical title beats a close one, then the better
 * [rank] (for example "on the right console"), then the service's own order.
 */
object CoverTitles {

    private val ROMAN = Regex("(?i)^[ivxlcdm]+$")

    private fun numberLike(word: String) = word.all { it.isDigit() } || ROMAN.matches(word)

    /** 2 = the same title, 1 = the same title with one extra plain word on either side, 0 = not the same game. */
    fun closeness(title: String, candidate: String): Int {
        val wanted = GameTitleCleaner.words(title)
        val found = GameTitleCleaner.words(candidate)
        if (wanted.isEmpty() || found.isEmpty()) return 0
        if (wanted == found) return 2
        val (small, large) = if (wanted.size <= found.size) wanted to found else found to wanted
        if (small.size < 3 || !large.containsAll(small)) return 0
        val extra = large - small
        return if (extra.size == 1 && !numberLike(extra.first())) 1 else 0
    }

    /** The best hit of [items] for [title] by any of its [names], or null when none qualifies ([minLevel] 2: only identical titles). */
    fun <T> pick(title: String, items: List<T>, names: (T) -> List<String>, rank: (T) -> Int = { 0 }, minLevel: Int = 1): T? {
        var best: T? = null
        var bestScore = -1L
        items.forEachIndexed { index, item ->
            val level = names(item).maxOfOrNull { closeness(title, it) } ?: 0
            if (level < minLevel) return@forEachIndexed
            val score = level * 1_000_000L + rank(item).coerceIn(0, 999) * 1_000L - index.coerceAtMost(999)
            if (score > bestScore) { best = item; bestScore = score }
        }
        return best
    }
}
