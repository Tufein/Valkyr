package com.tufein.valkyr.util

/**
 * 9.0: cheats, honestly.
 *
 * A `.cht` is imported per game, kept in a `cheats/` folder the user grants for RetroArch's own
 * discovery, never written into another app's private files. This object only decides: parse the
 * file, name the cheats, match a file to a game, and say whether a cheat may run. Nothing is ever
 * downloaded from a cheat site.
 */
object Cheats {
    const val MAX_FILE_BYTES = 4L * 1024 * 1024

    fun isCheatFile(name: String): Boolean = name.endsWith(".cht", ignoreCase = true)

    /** A plain file name: no separators, no traversal, bounded length. */
    fun safeName(name: String): String =
        name.trim().replace('\\', '/').substringAfterLast('/').take(80).ifBlank { "cheats.cht" }

    data class Cheat(val name: String, val code: String, val enabled: Boolean = false)

    enum class Verdict { ALLOWED, REFUSED_HARDCORE }

    /** Parse a RetroArch-style `.cht` body. A broken or empty file yields an empty list. */
    fun parse(text: String): List<Cheat> {
        if (text.isBlank()) return emptyList()
        val byIndex = LinkedHashMap<Int, MutableMap<String, String>>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue
            val eq = line.indexOf('=')
            if (eq < 0) continue
            val key = line.substring(0, eq).trim().lowercase()
            val value = line.substring(eq + 1).trim().trim('"')
            // keys look like cheat0_desc, cheat0_code, cheat0_enable
            val match = Regex("""cheat(\d+)_(desc|code|enable)""").matchEntire(key) ?: continue
            val idx = match.groupValues[1].toInt()
            val field = match.groupValues[2]
            byIndex.getOrPut(idx) { LinkedHashMap() }[field] = value
        }
        val out = ArrayList<Cheat>()
        for ((_, fields) in byIndex.toSortedMap()) {
            val name = fields["desc"]?.takeIf { it.isNotBlank() } ?: continue
            val code = fields["code"]?.takeIf { it.isNotBlank() } ?: continue
            val enabled = fields["enable"]?.equals("true", ignoreCase = true) == true
            out += Cheat(name, code, enabled)
        }
        return out
    }

    /** Whether [cheatFile] (a file name) belongs to [gameFile] (a game file name). */
    fun matchesFile(cheatFile: String, gameFile: String): Boolean {
        val cheatStem = cheatFile.substringBeforeLast('.').lowercase()
        val gameStem = gameFile.substringBeforeLast('.').lowercase()
        return cheatStem == gameStem || cheatStem.startsWith("$gameStem.")
    }

    /** Verdict for running cheats given the RetroAchievements hardcore state. */
    fun verdict(raHardcore: Boolean, anyEnabled: Boolean): Verdict =
        if (raHardcore && anyEnabled) Verdict.REFUSED_HARDCORE else Verdict.ALLOWED

    /** A sentence-worthy refusal reason when [verdict] is refused. */
    fun refusalReason(verdict: Verdict): String? = when (verdict) {
        Verdict.ALLOWED -> null
        Verdict.REFUSED_HARDCORE -> "Cheats are disabled while RetroAchievements runs in hardcore mode."
    }
}