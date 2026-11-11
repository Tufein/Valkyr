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
}
