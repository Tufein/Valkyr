package com.tufein.valkyr.util

import java.util.Locale

/**
 * 6.5: the picture RetroArch writes beside a save state (`Game.state.png`, `Game.state1.png`,
 * `Game.state.auto.png`) for the "resume here" card on the game page. Pure JVM for the tests.
 */
object StatePreviews {

    /** One file in the states folder: its name and last change (epoch millis, 0 when unknown). */
    data class Candidate(val name: String, val modified: Long)

    /**
     * The newest preview of [gameFileName] among [files], or null. A preview is `<game>.state<anything>.png`
     * where `<game>` is the game's file name with or without its extension.
     */
    fun pick(files: List<Candidate>, gameFileName: String): Candidate? {
        val stem = gameFileName.substringBeforeLast('.', gameFileName).lowercase(Locale.ROOT)
        val full = gameFileName.lowercase(Locale.ROOT)
        if (stem.isEmpty()) return null
        return files.filter { isPreviewOf(it.name.lowercase(Locale.ROOT), stem, full) }
            .maxWithOrNull(compareBy<Candidate> { it.modified }.thenBy { it.name })
    }

    private fun isPreviewOf(name: String, stem: String, full: String): Boolean {
        if (!name.endsWith(".png")) return false
        val body = name.removeSuffix(".png")
        return listOf(stem, full).any { base -> body.startsWith("$base.state") && body.length >= base.length + 6 }
    }

    /** The newest save or save state of a game: its file name, when it changed and whether it is a state. */
    data class SaveLine(val name: String, val modified: Long, val state: Boolean)

    /**
     * 7.3: the newest save of [gameFileName] among [files], whatever wrote it: a file named after the
     * game with a short ending (`Game.srm`, `Game.dsv`, `Game.state1`, `Game.state.auto`, `Game.gba.sav`).
     * Pictures and the game file itself are no saves; a file without a known time is used only when
     * nothing else fits. Null when the game has none.
     */
    fun newestSave(files: List<Candidate>, gameFileName: String): SaveLine? {
        val stem = gameFileName.substringBeforeLast('.', gameFileName).lowercase(Locale.ROOT)
        val full = gameFileName.lowercase(Locale.ROOT)
        if (stem.isEmpty()) return null
        return files.mapNotNull { file ->
            val name = file.name.lowercase(Locale.ROOT)
            if (name == full || name.endsWith(".png") || name.endsWith(".jpg")) return@mapNotNull null
            val ending = listOf(full, stem).firstNotNullOfOrNull { base -> name.removePrefix("$base.").takeIf { it != name && it.isNotEmpty() } } ?: return@mapNotNull null
            if (ending.length > MAX_ENDING || ending.any { it == ' ' || it == '(' } || ending.count { it == '.' } > 1) return@mapNotNull null
            SaveLine(file.name, file.modified, ending.startsWith("state"))
        }.maxWithOrNull(compareBy<SaveLine> { it.modified }.thenBy { it.name })
    }

    /** The longest ending a save has after the game's name (`state.auto`, `gba.sav`). */
    private const val MAX_ENDING = 12
}
