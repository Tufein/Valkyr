package com.tufein.valkyr.util

/**
 * 10.0: power search. Words of the form `console:snes`, `c:"game boy"`, `tag:hack` and `is:fav` in the
 * search box become filters; everything else stays the text that is searched. Several `console:` words
 * add up (SNES or Genesis), several `tag:` words narrow. A word that is not an operator, or an operator
 * with nothing after the colon, stays ordinary text, so searching for a title with a colon in it works as before.
 * Pure JVM.
 */
object QueryOperators {
    data class Parsed(
        /** What is left to search for. */
        val text: String,
        val consoleWords: List<String> = emptyList(),
        val tags: List<String> = emptyList(),
        val favouritesOnly: Boolean = false
    ) {
        val hasOperators: Boolean get() = consoleWords.isNotEmpty() || tags.isNotEmpty() || favouritesOnly
    }

    private val CONSOLE = setOf("console", "c", "system")
    private val TAG = setOf("tag", "t")
    private val FAVOURITE_VALUES = setOf("fav", "favs", "favorite", "favorites", "favourite", "favourites")

    /** Splits on spaces, keeping `"quoted words"` together (the quotes themselves are kept for [parse] to read). */
    internal fun tokens(text: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quoted = false
        for (ch in text) {
            when {
                ch == '"' -> { quoted = !quoted; cur.append(ch) }
                ch.isWhitespace() && !quoted -> { if (cur.isNotEmpty()) { out += cur.toString(); cur.clear() } }
                else -> cur.append(ch)
            }
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    fun parse(query: String): Parsed {
        if (':' !in query) return Parsed(query)
        val rest = ArrayList<String>()
        val consoles = ArrayList<String>()
        val tags = ArrayList<String>()
        var fav = false
        for (token in tokens(query)) {
            val colon = token.indexOf(':')
            val op = if (colon > 0) token.substring(0, colon).lowercase() else ""
            val value = if (colon > 0) token.substring(colon + 1).trim('"').trim() else ""
            when {
                value.isEmpty() -> rest += token
                op in CONSOLE -> consoles += value
                op in TAG -> tags += value
                op == "is" && value.lowercase() in FAVOURITE_VALUES -> fav = true
                else -> rest += token
            }
        }
        return Parsed(rest.joinToString(" "), consoles, tags, fav)
    }

    private fun norm(text: String): String = text.lowercase().filter { it.isLetterOrDigit() }

    /**
     * The consoles a typed word means: the console id or display name (exactly, else as a start), or
     * any word of the display name starting with it (`snes`, `super nintendo`, `mega` -> Mega Drive).
     * Case and punctuation are ignored; a word of fewer than three letters only matches whole names.
     */
    fun matchConsoles(word: String, ids: List<String>, nameOf: (String) -> String): Set<String> {
        val k = norm(word)
        if (k.isEmpty()) return emptySet()
        val exact = ids.filter { norm(it) == k || norm(nameOf(it)) == k }
        if (exact.isNotEmpty() || k.length < 3) return exact.toSet()
        return ids.filter { id ->
            val name = nameOf(id)
            norm(name).startsWith(k) || norm(id).startsWith(k) ||
                name.split(Regex("[^\\p{L}\\p{N}]+")).any { it.lowercase().startsWith(k) }
        }.toSet()
    }
}
