package com.tufein.valkyr.util

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.URLEncoder
import java.util.Locale

/** Where a game manual may come from. The order is the order the app asks them. */
enum class ManualSource(val key: String, val label: String) {
    ROMM("romm", "RomM"),
    SCREENSCRAPER("screenscraper", "ScreenScraper"),
    ARCHIVE("archive", "Internet Archive"),
    LINK("link", "A link");
}

/** One manual a source offers: [url] is stored without credentials, [name] is the suggested file name. */
data class ManualHit(
    val source: ManualSource,
    val url: String,
    val name: String,
    /** What the source calls it, shown in the "another source" list. */
    val label: String? = null,
    /** A page that says where the file came from, when the source has one. */
    val detail: String? = null
)

/**
 * Pure rules for the manual sources (7.7): which providers are asked and in what order, how the
 * answers are named and de-duplicated, how a definite "no manual" is remembered, and the public
 * Internet Archive search/metadata API. No Android and no network here, so the rules are tested.
 */
object ManualSources {

    /** The sources that are asked automatically, in order. A pasted link is an explicit action. */
    val ORDER: List<ManualSource> = listOf(ManualSource.ROMM, ManualSource.SCREENSCRAPER, ManualSource.ARCHIVE)

    /** All three on by default: ScreenScraper needs credentials and Archive needs a good connection, both fail quietly. */
    val DEFAULT_ENABLED: Set<ManualSource> = ORDER.toSet()

    /** A definite miss is not asked again for a month (as with cover sources). */
    const val MISS_TTL_MS = 30L * 24 * 3_600_000

    fun encodeEnabled(enabled: Set<ManualSource>): String = ORDER.filter { it in enabled }.joinToString(",") { it.key }

    fun decodeEnabled(value: String?): Set<ManualSource> {
        if (value == null) return DEFAULT_ENABLED
        val keys = value.split(',').map { it.trim() }.toSet()
        // An empty stored value means the user switched every source off; null means "never chosen".
        return ORDER.filter { it.key in keys }.toSet()
    }

    /** The sources to ask, in order; a source already known to have no manual is left out. */
    fun askOrder(enabled: Set<ManualSource>, misses: Map<String, Long>, key: String, now: Long): List<ManualSource> =
        ORDER.filter { it in enabled && !isMissed(misses, missKey(key, it), now) }

    fun missKey(gameKey: String, source: ManualSource): String = "$gameKey|${source.key}"

    fun isMissed(misses: Map<String, Long>, key: String, now: Long): Boolean {
        val at = misses[key] ?: return false
        return at in 1..now && now - at < MISS_TTL_MS
    }

    /** Oldest first, so a fresh miss replaces the oldest when the map is trimmed. */
    fun rememberMiss(misses: Map<String, Long>, gameKey: String, source: ManualSource, now: Long, limit: Int = 200): Map<String, Long> {
        val out = LinkedHashMap(misses.filterKeys { it != missKey(gameKey, source) })
        out[missKey(gameKey, source)] = now
        return out.entries.sortedBy { it.value }.takeLast(limit).associate { it.key to it.value }
    }

    /** A file name for a hit, always with an extension the reader opens. */
    fun fileName(hit: ManualHit, title: String): String {
        val fromUrl = Manuals.fileNameFor(hit.url, "pdf")
        val fromName = Manuals.safeSegment(hit.name, "")
        val base = when {
            fromName.isNotBlank() && Manuals.kindOf(fromName) != Manuals.Kind.OTHER -> fromName
            fromName.isNotBlank() -> "$fromName.pdf"
            fromUrl != "manual.pdf" -> fromUrl
            else -> "${Manuals.safeSegment(title, "manual")}.pdf"
        }
        return if (Manuals.kindOf(base) == Manuals.Kind.OTHER) "$base.pdf" else base
    }

    /** The same file offered by two sources is only offered once; the first (best-ranked) source wins. */
    fun dedupe(hits: List<ManualHit>): List<ManualHit> {
        val seen = HashSet<String>()
        return hits.filter { hit ->
            val key = fileName(hit, "").lowercase(Locale.ROOT)
            seen.add(key)
        }
    }

    /** Whether a redirect is still on the provider's own ground (no credentials or token may travel elsewhere). */
    fun acceptsRedirect(source: ManualSource, original: String, candidate: String): Boolean {
        fun host(url: String) = runCatching { URI(url).host?.lowercase(Locale.ROOT) }.getOrNull()
        val from = host(original) ?: return false
        val to = host(candidate) ?: return false
        if (source == ManualSource.ARCHIVE) return to == from || to.endsWith(".archive.org") || to.endsWith(".us.archive.org")
        return to == from && runCatching { URI(candidate).scheme?.lowercase(Locale.ROOT) == URI(original).scheme?.lowercase(Locale.ROOT) }.getOrDefault(false)
    }

    /** A link a user pasted: http(s) and a host, nothing local or scripted. */
    fun validLink(url: String): Boolean = runCatching {
        val uri = URI(url.trim())
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        uri.scheme?.lowercase(Locale.ROOT) in setOf("https", "http") &&
            (host == "localhost" || host.endsWith(".localhost") || host == "127.0.0.1" || host == "::1").not()
    }.getOrDefault(false)

    // ---- Internet Archive: public search and metadata APIs ----

    private const val ARCHIVE_API = "https://archive.org"
    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    /**
     * A title search for game manuals. The query is kept narrow: text items whose title or subject
     * mentions the game and a manual. Five candidates at most, so one lookup stays small.
     */
    fun archiveSearchUrl(title: String, console: String = ""): String {
        val words = GameTitleCleaner.words(title).take(6).joinToString(" ")
        val q = buildString {
            append('(').append(words).append(')').append(" AND mediatype:texts")
            if (console.isNotBlank()) append(" AND (").append(GameTitleCleaner.words(console).take(3).joinToString(" ")).append(')')
        }
        return "$ARCHIVE_API/advancedsearch.php?q=${enc(q)}&fl%5B%5D=identifier&fl%5B%5D=title&rows=5&page=1&output=json"
    }

    fun archiveMetadataUrl(identifier: String): String = "$ARCHIVE_API/metadata/${enc(identifier)}"

    fun archiveDownloadUrl(identifier: String, fileName: String): String =
        "$ARCHIVE_API/download/${enc(identifier)}/${enc(fileName).replace("+", "%20")}"

    data class ArchiveItem(val id: String, val title: String)

    /** `response.docs` of advancedsearch; only items with an identifier. */
    fun parseArchiveSearch(json: String): List<ArchiveItem> = runCatching {
        val docs = JsonParser.parseString(json).asJsonObject.obj("response")?.arr("docs") ?: return emptyList()
        docs.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.text("identifier").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ArchiveItem(id, o.text("title"))
        }
    }.getOrDefault(emptyList())

    /** A PDF file of an item, preferring one named after the game or a manual; null when there is none. */
    fun parseArchiveManual(json: String, title: String): String? = runCatching {
        val files = JsonParser.parseString(json).asJsonObject.arr("files") ?: return null
        val pdfs = files.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val name = o.text("name").takeIf { it.isNotBlank() && it.endsWith(".pdf", ignoreCase = true) } ?: return@mapNotNull null
            val format = o.text("format")
            val bad = o.text("private") == "true" || format.contains("text pdf", ignoreCase = true) && name.contains("_djvu", ignoreCase = true)
            if (bad) null else name
        }
        if (pdfs.isEmpty()) return null
        val words = GameTitleCleaner.words(title).filter { it.length >= 3 }
        pdfs.maxByOrNull { name ->
            val lower = name.lowercase(Locale.ROOT)
            (if ("manual" in lower) 4 else 0) + words.count { it in lower }
        }
    }.getOrDefault(null)

    private fun JsonObject.obj(name: String): JsonObject? = get(name)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.arr(name: String): com.google.gson.JsonArray? = get(name)?.takeIf { it.isJsonArray }?.asJsonArray
    private fun JsonObject.text(name: String): String = get(name)?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
}
