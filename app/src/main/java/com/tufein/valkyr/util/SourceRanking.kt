package com.tufein.valkyr.util

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.URLDecoder
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/** Why the last download from a source failed, roughly; enough to tell "busy" from "gone". */
enum class FailureClass {
    /** The connection dropped, timed out or was refused. */
    NETWORK,
    /** A 5xx, a timeout or rate limit answered by the server. */
    SERVER,
    /** 404 / 410: the file is not (or no longer) there. */
    NOT_FOUND,
    /** 401 / 403: the server refused it. */
    FORBIDDEN,
    OTHER;

    companion object {
        /** [httpCode] when the server answered with an error, [network] for an I/O error without one. */
        fun of(httpCode: Int?, network: Boolean): FailureClass = when {
            httpCode == 404 || httpCode == 410 -> NOT_FOUND
            httpCode == 401 || httpCode == 403 -> FORBIDDEN
            httpCode != null && AutoRetry.temporaryStatus(httpCode) -> SERVER
            httpCode != null -> OTHER
            network -> NETWORK
            else -> OTHER
        }
    }
}

/** One remembered download from a source: when, whether it worked, how much moved and why it failed. */
data class SourceOutcome(
    val at: Long,
    val ok: Boolean,
    val bytes: Long = 0L,
    val failure: FailureClass? = null
)

/** What the last [SourceRanking.WINDOW_DAYS] days of one source's downloads say, for a status line. */
data class DownloadTrend(
    val failures: Int,
    val downloads: Int,
    val streak: Int,
    val last: FailureClass,
    val newestAt: Long
)

/**
 * How downloads from one source (the address as configured in Sources) went lately. Kept small:
 * a speed average and two counts that are halved now and then, plus a bounded list of individual
 * outcomes so the trend covers weeks, not only "now" ([SourceRanking.trend]).
 */
data class SourceRecord(
    /** Exponentially weighted average speed of finished transfers, bytes/s; 0 = not known yet. */
    val bytesPerSec: Double = 0.0,
    val successes: Int = 0,
    val failures: Int = 0,
    val lastSuccessAt: Long = 0L,
    val lastFailureAt: Long = 0L,
    /** Failures in a row since the last success. */
    val failureStreak: Int = 0,
    val lastFailure: FailureClass? = null,
    /** Oldest first, at most [SourceRanking.KEEP_OUTCOMES] entries; added after the counters so old
     * positional constructors and old JSON files keep working. */
    val outcomes: List<SourceOutcome> = emptyList()
) {
    val total: Int get() = successes + failures
    /** Share of downloads that worked (0..100), or null before the first one. */
    val okPercent: Int? get() = if (total == 0) null else (successes * 100.0 / total).roundToInt()
}

/**
 * Picks the best source for a game several sources list (7.5 "Pick the best source"). Pure JVM
 * for the tests: reliability first, then speed, then the order of the sources in Sources; a source
 * that failed several times in a row lately goes last for a while. Only ever between copies of the
 * same game: same console and file name, or the same cleaned title with the same tags.
 */
object SourceRanking {

    /** Weight of a new speed sample in the average. */
    const val SPEED_WEIGHT = 0.3
    /** Transfers shorter than this tell little about a server's speed (handshakes, tiny files). */
    const val MIN_SAMPLE_BYTES = 1L * 1024 * 1024
    const val MIN_SAMPLE_MS = 1_000L
    /** Failures in a row that put a source last... */
    const val DEMOTE_STREAK = 2
    /** ...for this long after its last failure. */
    const val DEMOTE_MS = 6 * 60 * 60 * 1000L
    /** Above this many downloads both counts are halved, so the record follows recent behaviour. */
    const val MAX_COUNT = 100
    /** Sizes of two copies may differ this much (listings round them) and still be the same file. */
    const val SIZE_TOLERANCE = 0.05
    /** Remember this many individual downloads per source (the scan history keeps the same number). */
    const val KEEP_OUTCOMES = 12
    /** The window [trend] and [exportJson] look back over. */
    const val WINDOW_DAYS = 30L

    /** [history] oldest first, with [next] appended and bounded to [KEEP_OUTCOMES]. */
    fun appendOutcome(history: List<SourceOutcome>, next: SourceOutcome): List<SourceOutcome> =
        (history + next).takeLast(KEEP_OUTCOMES)

    fun withSuccess(r: SourceRecord, bytes: Long, millis: Long, now: Long): SourceRecord {
        val speed = if (bytes >= MIN_SAMPLE_BYTES && millis >= MIN_SAMPLE_MS) bytes * 1000.0 / millis else null
        val avg = when {
            speed == null -> r.bytesPerSec
            r.bytesPerSec <= 0.0 -> speed
            else -> r.bytesPerSec + SPEED_WEIGHT * (speed - r.bytesPerSec)
        }
        return aged(r.copy(bytesPerSec = avg, successes = r.successes + 1, lastSuccessAt = now, failureStreak = 0,
            outcomes = appendOutcome(r.outcomes, SourceOutcome(now, true, bytes.coerceAtLeast(0L)))))
    }

    fun withFailure(r: SourceRecord, cls: FailureClass, now: Long): SourceRecord =
        aged(r.copy(failures = r.failures + 1, lastFailureAt = now, failureStreak = r.failureStreak + 1, lastFailure = cls,
            outcomes = appendOutcome(r.outcomes, SourceOutcome(now, false, failure = cls))))

    private fun aged(r: SourceRecord): SourceRecord =
        if (r.total <= MAX_COUNT) r else r.copy(successes = (r.successes + 1) / 2, failures = (r.failures + 1) / 2)

    /** Failed [DEMOTE_STREAK] times or more in a row, the last time less than [DEMOTE_MS] ago. */
    fun isDemoted(r: SourceRecord?, now: Long): Boolean =
        r != null && r.failureStreak >= DEMOTE_STREAK && now - r.lastFailureAt < DEMOTE_MS

    /**
     * What [WINDOW_DAYS] days of downloads say about a source. Null while it looks healthy: one
     * failure that was followed by a success is not news. Future timestamps are ignored (a clock
     * change must not invent failures).
     */
    fun trend(r: SourceRecord?, now: Long): DownloadTrend? {
        if (r == null) return null
        val recent = r.outcomes.filter { now - it.at in 0..WINDOW_DAYS * 86_400_000L }
        val failed = recent.filter { !it.ok && it.failure != null }
        val last = failed.lastOrNull()?.failure ?: return null
        val streak = recent.takeLastWhile { !it.ok }.size
        if (streak < 2 && failed.size < 2) return null
        return DownloadTrend(failed.size, recent.size, streak, last, recent.lastOrNull()?.at ?: 0L)
    }

    /** A 0..100 score for the tracker overview: reliability, halved while the source is set aside. */
    fun score(r: SourceRecord?, now: Long): Int {
        if (r == null) return 50
        val base = (reliability(r) * 100).roundToInt()
        return if (isDemoted(r, now)) base / 2 else base
    }

    /**
     * Success rate with one imagined success and failure added, so an unknown source counts as
     * 0.5, one good download as 0.67 and one bad one as 0.33.
     */
    fun reliability(r: SourceRecord?): Double = if (r == null) 0.5 else (r.successes + 1.0) / (r.total + 2.0)

    /** Reliability in steps of 10 %: within a step the faster source wins. */
    private fun reliabilityStep(r: SourceRecord?): Int = floor(reliability(r) * 10).toInt()

    /** One indexed copy of a game, as the ranking sees it. */
    data class Copy(
        val consoleId: String,
        val fileName: String,
        /** Cleaned title (tags stripped), any case. */
        val title: String,
        val tags: Collection<String>,
        val size: Long
    )

    private val whitespace = Regex("\\s+")
    fun titleKey(title: String): String = title.lowercase().replace(whitespace, " ").trim()

    /**
     * Whether [a] and [b] are the same file of the same game: same console, close sizes (when both
     * are known) and the same file name, or the same cleaned title with the same region/version
     * tags and the same file type. Never a different game, version or region.
     */
    fun sameGame(a: Copy, b: Copy): Boolean {
        if (a.consoleId != b.consoleId) return false
        if (a.size > 0 && b.size > 0 && abs(a.size - b.size) > maxOf(a.size, b.size) * SIZE_TOLERANCE) return false
        if (a.fileName.equals(b.fileName, ignoreCase = true)) return true
        val ext = { n: String -> n.substringAfterLast('.', "").lowercase() }
        return ext(a.fileName) == ext(b.fileName) && titleKey(a.title) == titleKey(b.title) && titleKey(a.title).isNotEmpty() &&
            a.tags.map { it.lowercase() }.toSet() == b.tags.map { it.lowercase() }.toSet()
    }

    /**
     * [rows] best first. [sourceOf] gives a row's source (as configured), [sourceOrder] the sources
     * in the order of Sources (unknown sources after them); rows that tie keep their order.
     */
    fun <T> rank(
        rows: List<T>,
        sourceOf: (T) -> String,
        records: Map<String, SourceRecord>,
        sourceOrder: List<String>,
        now: Long
    ): List<T> {
        fun orderOf(s: String) = sourceOrder.indexOf(s).let { if (it < 0) Int.MAX_VALUE else it }
        return rows.sortedWith(
            compareBy<T> { isDemoted(records[sourceOf(it)], now) }
                .thenByDescending { reliabilityStep(records[sourceOf(it)]) }
                .thenByDescending { records[sourceOf(it)]?.bytesPerSec ?: 0.0 }
                .thenBy { orderOf(sourceOf(it)) }
        )
    }

    fun <T> best(rows: List<T>, sourceOf: (T) -> String, records: Map<String, SourceRecord>, sourceOrder: List<String>, now: Long): T? =
        rank(rows, sourceOf, records, sourceOrder, now).firstOrNull()

    /**
     * A short name for a source in a status line: the host of a web address, "RomM" for a RomM
     * platform, the name of a torrent, or the last part of a path.
     */
    fun label(sourceUrl: String): String {
        val u = sourceUrl.trim()
        return when (SourceKind.of(u)) {
            SourceKind.ROMM -> "RomM"
            SourceKind.WEB -> runCatching { URI(u).host }.getOrNull()?.removePrefix("www.") ?: u
            SourceKind.TORRENT -> if (u.startsWith("magnet:", ignoreCase = true)) {
                Regex("[?&]dn=([^&]+)").find(u)?.groupValues?.get(1)
                    ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) } ?: "magnet"
            } else u.substringBefore('?').trimEnd('/').substringAfterLast('/')
            SourceKind.OTHER -> u
        }.ifEmpty { u }
    }

    /** "12 MB/s" style speed, null when unknown. */
    fun speedText(bytesPerSec: Double): String? = when {
        bytesPerSec <= 0.0 -> null
        bytesPerSec >= 1024.0 * 1024 -> String.format(java.util.Locale.ROOT, if (bytesPerSec >= 10.0 * 1024 * 1024) "%.0f MB/s" else "%.1f MB/s", bytesPerSec / (1024 * 1024))
        else -> String.format(java.util.Locale.ROOT, "%.0f KB/s", maxOf(1.0, bytesPerSec / 1024))
    }

    /**
     * An export label: only the kind of source and a short, non-reversible tag to tell several
     * sources of the same kind apart. Not a host, not an address, not a torrent name, not a key.
     */
    private fun exportLabel(url: String): String {
        val kind = when (SourceKind.of(url)) {
            SourceKind.ROMM -> "RomM"
            SourceKind.WEB -> "Web"
            SourceKind.TORRENT -> "Torrent"
            SourceKind.OTHER -> "Source"
        }
        val tag = (url.hashCode().toUInt() % 10_000u).toString().padStart(4, '0')
        return "$kind $tag"
    }

    /**
     * A shareable record of how the sources did. Neither the addresses (which can carry keys or
     * tokens), nor paths, nor file names are written — only short labels and the numbers.
     * [labelOf] exists so a test can prove that no address leaks.
     */
    fun exportJson(
        records: Map<String, SourceRecord>,
        now: Long,
        labelOf: (String) -> String = { exportLabel(it) }
    ): String {
        val root = JsonObject().apply { addProperty("generatedAt", now) }
        val byLabel = LinkedHashMap<String, SourceRecord>()
        records.entries.sortedByDescending { it.value.total }.forEach { (url, record) ->
            val name = labelOf(url).ifBlank { "Source" }.take(120)
            var key = name
            var suffix = 2
            while (byLabel.containsKey(key)) { key = "$name ($suffix)"; suffix++ }
            byLabel[key] = record
        }
        val array = JsonArray()
        byLabel.forEach { (name, r) ->
            array.add(JsonObject().apply {
                addProperty("source", name)
                addProperty("ok", r.successes)
                addProperty("failed", r.failures)
                r.okPercent?.let { addProperty("okPercent", it) }
                addProperty("speed", r.bytesPerSec.roundToInt())
                addProperty("lastOkAt", r.lastSuccessAt)
                addProperty("lastFailedAt", r.lastFailureAt)
                trend(r, now)?.let { t ->
                    addProperty("failures30d", t.failures)
                    addProperty("downloads30d", t.downloads)
                    addProperty("streak", t.streak)
                    addProperty("lastFailure", t.last.name)
                }
                val events = JsonArray()
                r.outcomes.filter { now - it.at in 0..WINDOW_DAYS * 86_400_000L }.forEach { o ->
                    events.add(JsonObject().apply {
                        addProperty("at", o.at)
                        addProperty("ok", o.ok)
                        o.failure?.let { addProperty("why", it.name) }
                    })
                }
                add("events", events)
            })
        }
        root.add("sources", array)
        return root.toString()
    }

    // Stored as one small JSON object per source in files/source_track.json.

    fun toJson(records: Map<String, SourceRecord>): String {
        val o = JsonObject()
        records.forEach { (url, r) ->
            o.add(url, JsonObject().apply {
                addProperty("speed", r.bytesPerSec)
                addProperty("ok", r.successes)
                addProperty("failed", r.failures)
                addProperty("okAt", r.lastSuccessAt)
                addProperty("failedAt", r.lastFailureAt)
                addProperty("streak", r.failureStreak)
                r.lastFailure?.let { addProperty("why", it.name) }
                if (r.outcomes.isNotEmpty()) {
                    val events = JsonArray()
                    r.outcomes.forEach { outcome ->
                        events.add(JsonObject().apply {
                            addProperty("t", outcome.at)
                            addProperty("ok", outcome.ok)
                            addProperty("b", outcome.bytes)
                            outcome.failure?.let { addProperty("why", it.name) }
                        })
                    }
                    add("events", events)
                }
            })
        }
        return o.toString()
    }

    fun fromJson(json: String): Map<String, SourceRecord> = runCatching {
        JsonParser.parseString(json).asJsonObject.entrySet().associate { (url, v) ->
            val r = v.asJsonObject
            fun long(k: String) = r.get(k)?.takeUnless { it.isJsonNull }?.asLong ?: 0L
            fun int(k: String) = r.get(k)?.takeUnless { it.isJsonNull }?.asInt ?: 0
            val events = runCatching { r.get("events")?.takeUnless { it.isJsonNull }?.asJsonArray }.getOrNull()?.mapNotNull { e ->
                runCatching {
                    val o = e.asJsonObject
                    SourceOutcome(
                        at = o.get("t")?.takeUnless { it.isJsonNull }?.asLong ?: return@runCatching null,
                        ok = o.get("ok")?.takeUnless { it.isJsonNull }?.asBoolean ?: false,
                        bytes = o.get("b")?.takeUnless { it.isJsonNull }?.asLong ?: 0L,
                        failure = o.get("why")?.takeUnless { it.isJsonNull }?.asString?.let { n -> FailureClass.entries.firstOrNull { it.name == n } }
                    )
                }.getOrNull()
            }.orEmpty().takeLast(KEEP_OUTCOMES)
            url to SourceRecord(
                bytesPerSec = r.get("speed")?.takeUnless { it.isJsonNull }?.asDouble ?: 0.0,
                successes = int("ok"), failures = int("failed"),
                lastSuccessAt = long("okAt"), lastFailureAt = long("failedAt"),
                failureStreak = int("streak"),
                lastFailure = r.get("why")?.takeUnless { it.isJsonNull }?.asString?.let { n -> FailureClass.entries.firstOrNull { it.name == n } },
                outcomes = events
            )
        }
    }.getOrDefault(emptyMap())
}
