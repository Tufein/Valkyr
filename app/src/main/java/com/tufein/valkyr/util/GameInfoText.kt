package com.tufein.valkyr.util

/**
 * 6.3: the "Copy game info" text of the game page: what the page knows about one game as a few plain
 * lines for a chat, a forum post or a note. Lines whose value is unknown are left out. Pure JVM for the tests.
 */
object GameInfoText {

    /** The words in front of the values, already in the app's language. */
    data class Labels(
        val console: String,
        val released: String,
        val developer: String,
        val genres: String,
        val file: String,
        val tags: String
    )

    /** What the page knows; blank or empty means unknown. [sizeText] is the size as the user reads it ("1.2 GB"). */
    data class Facts(
        val title: String,
        val console: String,
        val released: String = "",
        val developer: String = "",
        val genres: List<String> = emptyList(),
        val fileName: String = "",
        val sizeText: String = "",
        val tags: List<String> = emptyList()
    )

    /** Most tags written out, so a heavily tagged file does not bury the other lines. */
    const val MAX_TAGS = 12

    fun build(facts: Facts, labels: Labels, link: String? = null): String {
        val lines = mutableListOf<String>()
        lines += facts.title.trim()
        line(lines, labels.console, facts.console)
        line(lines, labels.released, facts.released)
        line(lines, labels.developer, facts.developer)
        line(lines, labels.genres, facts.genres.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(", "))
        val file = facts.fileName.trim()
        val size = facts.sizeText.trim()
        line(lines, labels.file, if (file.isNotEmpty() && size.isNotEmpty()) "$file ($size)" else file.ifEmpty { size })
        line(lines, labels.tags, facts.tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_TAGS).joinToString(", "))
        if (!link.isNullOrBlank()) lines += link
        return lines.joinToString("\n")
    }

    private fun line(out: MutableList<String>, label: String, value: String) {
        val v = value.trim()
        if (v.isNotEmpty()) out += "$label: $v"
    }
}
