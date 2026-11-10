package com.tufein.valkyr.util

import java.util.Locale
import java.net.URLDecoder

/**
 * Game manuals kept on the device (6.0): where they live, what kind of file they are, how they are
 * ordered for the reader, the size cap and the last page read. Pure, so the rules are unit-tested.
 * The files sit in the app's own storage under `manuals/<console>/<game>/` (never in the user's folders).
 */
object Manuals {

    const val ROOT = "manuals"

    const val KEY_WITH_GAME = "manuals60_with_game"
    const val KEY_CAP_MB = "manuals60_cap_mb"
    const val KEY_LAST_PAGES = "manuals60_last_pages"

    /** Types for the settings backup (see SettingSchema). */
    val SETTING_TYPES: Map<String, String> = mapOf(KEY_WITH_GAME to "b", KEY_CAP_MB to "i", KEY_LAST_PAGES to "s")

    /** The total space manuals may take, in MB, as the setting offers it. */
    val CAP_CHOICES_MB: List<Int> = listOf(50, 100, 250, 500, 1000, 2000)
    const val DEFAULT_CAP_MB = 500

    /** The nearest allowed cap; anything unreadable gives the default. */
    fun clampCapMb(mb: Int?): Int {
        val v = mb ?: return DEFAULT_CAP_MB
        return CAP_CHOICES_MB.minByOrNull { kotlin.math.abs(it - v) } ?: DEFAULT_CAP_MB
    }

    /** The next/previous cap choice for the stepper, staying inside the list. */
    fun stepCapMb(current: Int, delta: Int): Int {
        val i = CAP_CHOICES_MB.indexOf(clampCapMb(current))
        return CAP_CHOICES_MB[(i + delta).coerceIn(0, CAP_CHOICES_MB.lastIndex)]
    }

    fun capBytes(mb: Int): Long = clampCapMb(mb).toLong() * 1024L * 1024L

    /** One manual alone may take at most this much (a quarter of the cap, at most 100 MB, at least 20 MB). */
    fun maxFileBytes(capBytes: Long): Long = (capBytes / 4).coerceIn(20L * 1024 * 1024, 100L * 1024 * 1024).coerceAtMost(capBytes)

    /** A folder or file name part: letters, digits and a few signs; nothing that climbs out of its folder. */
    fun safeSegment(text: String, fallback: String = "game"): String {
        val cleaned = text.trim().map { c -> if (c.isLetterOrDigit() || c in " ._()-[]&'+,") c else '_' }.joinToString("")
            .replace(Regex("\\s+"), " ").trim().trim('.', ' ')
        return cleaned.take(80).trim().trimEnd('.').ifEmpty { fallback }
    }

    /** The game part of the folder: the file name without its extension. */
    fun gameSegment(fileName: String): String = safeSegment(fileName.substringBeforeLast('.', fileName))

    /** `manuals/<console>/<game>` relative to the app's files folder. */
    fun relativeDir(consoleId: String, fileName: String): String =
        "$ROOT/${safeSegment(consoleId, "console")}/${gameSegment(fileName)}"

    enum class Kind { PDF, IMAGE, TEXT, OTHER }

    private val imageExt = setOf("jpg", "jpeg", "png", "webp")
    private val textExt = setOf("md", "markdown", "txt")

    fun kindOf(fileName: String): Kind = when (fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "pdf" -> Kind.PDF
        in imageExt -> Kind.IMAGE
        in textExt -> Kind.TEXT
        else -> Kind.OTHER
    }

    /** True for markdown (rendered with headings), false for plain text. */
    fun isMarkdown(fileName: String): Boolean = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT).let { it == "md" || it == "markdown" }

    /**
     * The name a downloaded manual is saved under: the last part of its URL (without query), made safe, and
     * only with an extension the reader opens; [fallbackExt] is used when the URL has none.
     */
    fun fileNameFor(url: String, fallbackExt: String = "pdf"): String {
        val last = url.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
        val decoded = runCatching { URLDecoder.decode(last.replace("+", "%2B"), "UTF-8") }.getOrDefault(last)
        val safe = safeSegment(decoded, "manual")
        return if (kindOf(safe) == Kind.OTHER) "${safe.substringBeforeLast('.', safe)}.$fallbackExt" else safe
    }

    /** Natural order: `page2` before `page10`. */
    fun naturalOrder(names: List<String>): List<String> = names.sortedWith { a, b -> compareNatural(a, b) }

    private fun compareNatural(a: String, b: String): Int {
        val ra = Regex("(\\d+)|(\\D+)").findAll(a.lowercase(Locale.ROOT)).map { it.value }.toList()
        val rb = Regex("(\\d+)|(\\D+)").findAll(b.lowercase(Locale.ROOT)).map { it.value }.toList()
        for (i in 0 until minOf(ra.size, rb.size)) {
            val x = ra[i]; val y = rb[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) {
                val nx = x.trimStart('0'); val ny = y.trimStart('0')
                if (nx.length != ny.length) nx.length.compareTo(ny.length) else nx.compareTo(ny)
            } else x.compareTo(y)
            if (c != 0) return c
        }
        return ra.size.compareTo(rb.size)
    }

    /** What the reader opens: one PDF, one text file, or all the pictures of the folder as one sequence. */
    data class Doc(val kind: Kind, val title: String, val files: List<String>)

    /** The documents in one game's folder: each PDF and text file alone, the images together in natural order. */
    fun docsIn(fileNames: List<String>): List<Doc> {
        val docs = mutableListOf<Doc>()
        naturalOrder(fileNames.filter { kindOf(it) == Kind.PDF }).forEach { docs += Doc(Kind.PDF, it, listOf(it)) }
        val images = naturalOrder(fileNames.filter { kindOf(it) == Kind.IMAGE })
        if (images.isNotEmpty()) docs += Doc(Kind.IMAGE, images.first().substringBeforeLast('.'), images)
        naturalOrder(fileNames.filter { kindOf(it) == Kind.TEXT }).forEach { docs += Doc(Kind.TEXT, it, listOf(it)) }
        return docs
    }

    /** One manual file on disk, for the cap. [path] is relative to the manuals folder. */
    data class Entry(val path: String, val size: Long, val modified: Long)

    /** Outcome of fitting a new file under the cap. */
    sealed class Fit {
        /** Save it; [remove] are the oldest other files that make room (empty when it simply fits). */
        data class Ok(val remove: List<Entry>) : Fit()
        object TooBig : Fit()
    }

    /** Whether [incoming] bytes fit the [capBytes] cap, and which oldest files go to make room. [protect] paths are never removed. */
    fun fit(existing: List<Entry>, incoming: Long, capBytes: Long, protect: Set<String> = emptySet()): Fit {
        if (incoming > maxFileBytes(capBytes) || incoming > capBytes) return Fit.TooBig
        var total = existing.sumOf { it.size } + incoming
        if (total <= capBytes) return Fit.Ok(emptyList())
        val remove = mutableListOf<Entry>()
        for (e in existing.filter { it.path !in protect }.sortedBy { it.modified }) {
            if (total <= capBytes) break
            remove += e
            total -= e.size
        }
        return if (total <= capBytes) Fit.Ok(remove) else Fit.TooBig
    }

    /** The last page read of each document, as `page|relative path` lines, newest last, kept short. */
    object LastPages {
        const val LIMIT = 200

        fun decode(text: String?): Map<String, Int> {
            val out = LinkedHashMap<String, Int>()
            text.orEmpty().lineSequence().forEach { line ->
                val page = line.substringBefore('|', "").toIntOrNull() ?: return@forEach
                val key = line.substringAfter('|', "").takeIf { it.isNotEmpty() } ?: return@forEach
                if (page >= 0) { out.remove(key); out[key] = page }
            }
            return out
        }

        fun encode(map: Map<String, Int>): String = map.entries.toList().takeLast(LIMIT).joinToString("\n") { "${it.value}|${it.key}" }

        fun put(text: String?, key: String, page: Int): String {
            val m = LinkedHashMap(decode(text))
            m.remove(key)
            m[key] = page.coerceAtLeast(0)
            return encode(m)
        }

        fun get(text: String?, key: String): Int = decode(text)[key] ?: 0
    }

    /** The key a document's last page is stored under. */
    fun docKey(consoleId: String, fileName: String, doc: Doc): String = "${relativeDir(consoleId, fileName)}/${doc.files.first()}"
}

/** The reader's page and zoom, as plain values: page turns clamp, zoom has fixed steps. */
data class ReaderState(val count: Int, val page: Int = 0, val zoom: Float = 1f) {
    init { require(count >= 0) }

    private fun last() = (count - 1).coerceAtLeast(0)

    val current: Int get() = page.coerceIn(0, last())
    val atStart: Boolean get() = current <= 0
    val atEnd: Boolean get() = current >= last()

    fun goTo(p: Int) = copy(page = p.coerceIn(0, last()), zoom = 1f)
    fun next() = goTo(current + 1)
    fun prev() = goTo(current - 1)
    fun jump(delta: Int) = goTo(current + delta)

    /** Zoom in/out one step (1x, 1.5x, 2x, 3x, 4x); a page turn resets it. */
    fun zoomIn() = copy(zoom = ZOOMS.firstOrNull { it > zoom + 0.01f } ?: ZOOMS.last())
    fun zoomOut() = copy(zoom = ZOOMS.lastOrNull { it < zoom - 0.01f } ?: ZOOMS.first())
    /** One button: cycle through the steps, back to 1x after the last. */
    fun zoomCycle() = if (zoom >= ZOOMS.last() - 0.01f) copy(zoom = 1f) else zoomIn()
    fun withZoom(z: Float) = copy(zoom = z.coerceIn(ZOOMS.first(), ZOOMS.last()))

    /** "3 / 12" for the page indicator (one-based); empty for an empty document. */
    fun label(): String = if (count == 0) "" else "${current + 1} / $count"

    companion object {
        val ZOOMS = listOf(1f, 1.5f, 2f, 3f, 4f)
    }
}

/** A few markdown lines as blocks, so a manual written in markdown reads with headings. */
object MarkdownLite {
    data class Block(val level: Int, val text: String, val bullet: Boolean = false)

    fun parse(source: String): List<Block> = source.replace("\r\n", "\n").lines().map { raw ->
        val line = raw.trimEnd()
        val heading = Regex("^(#{1,6})\\s+(.*)$").find(line)
        when {
            heading != null -> Block(heading.groupValues[1].length, clean(heading.groupValues[2]))
            Regex("^\\s*[-*+]\\s+.*").matches(line) -> Block(0, clean(line.trimStart().drop(2)), bullet = true)
            else -> Block(0, clean(line))
        }
    }

    private fun clean(s: String) = s.replace(Regex("\\*\\*(.+?)\\*\\*"), "$1").replace(Regex("`(.+?)`"), "$1")
        .replace(Regex("!?\\[(.*?)]\\((.*?)\\)"), "$1")
}
