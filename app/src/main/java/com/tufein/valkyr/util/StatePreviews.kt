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

    /** Most pictures the gallery of one game shows. */
    const val MAX_PICTURES = 24

    /** 9.6: every preview of [gameFileName] among [files], newest first, at most [limit]. */
    fun pictures(files: List<Candidate>, gameFileName: String, limit: Int = MAX_PICTURES): List<Candidate> {
        val stem = gameFileName.substringBeforeLast('.', gameFileName).lowercase(Locale.ROOT)
        val full = gameFileName.lowercase(Locale.ROOT)
        if (stem.isEmpty()) return emptyList()
        return files.filter { isPreviewOf(it.name.lowercase(Locale.ROOT), stem, full) }
            .sortedWith(compareByDescending<Candidate> { it.modified }.thenBy { it.name })
            .take(limit)
    }

    enum class Kind { SLOT, AUTO, STATE, OTHER }

    /** What a picture is called: a numbered slot (`Game.state3.png`), the automatic one, the plain one, or whatever the emulator wrote. */
    data class Label(val kind: Kind, val slot: Int = 0, val raw: String = "")

    fun label(fileName: String, gameFileName: String): Label {
        val stem = gameFileName.substringBeforeLast('.', gameFileName).lowercase(Locale.ROOT)
        val full = gameFileName.lowercase(Locale.ROOT)
        val body = fileName.lowercase(Locale.ROOT).removeSuffix(".png")
        val after = listOf(full, stem).firstNotNullOfOrNull { base -> body.removePrefix("$base.state").takeIf { it != body } } ?: return Label(Kind.OTHER, raw = fileName.removeSuffix(".png"))
        val tail = after.trimStart('.')
        return when {
            tail.isEmpty() -> Label(Kind.STATE)
            tail == "auto" -> Label(Kind.AUTO)
            tail.all { it.isDigit() } && tail.length <= 4 -> Label(Kind.SLOT, tail.toInt())
            else -> Label(Kind.OTHER, raw = tail.take(40))
        }
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
