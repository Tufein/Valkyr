package com.tufein.valkyr.util

object ArchiveUtils {

    private val SUPPORTED_EXTENSIONS = setOf(
        ".zip", ".7z", ".rar", ".tar", ".gz", ".tgz", ".bz2", ".xz", ".cab"
    )

    /**
     * 8.5: consoles whose games are ROM *sets*: MAME, FBNeo, Flycast's arcade boards and the Neo Geo read
     * a game as one zip (and its board BIOS as another), so the zip is the game and unpacking it would break it.
     * Matched on whole words of the console id; Neo Geo Pocket and the Neo Geo CD are not sets.
     */
    fun keepsArchives(consoleId: String): Boolean {
        val id = "_" + consoleId.lowercase().replace('-', '_').replace(' ', '_') + "_"
        if (id.contains("_pocket_") || id.contains("_ngp_") || id.contains("_ngpc_") || id.contains("_cd_") || id.contains("_neocd_")) return false
        return SET_WORDS.any { id.contains("_${it}_") }
    }

    private val SET_WORDS = listOf("arcade", "mame", "fbneo", "fba", "cps1", "cps2", "cps3", "naomi", "atomiswave", "neogeo", "neo_geo", "neo_geo_aes", "neo_geo_mvs")

    /** Whether a finished download of this console's [fileExtension] is unpacked automatically (when the user allows it). */
    fun autoExtracts(consoleId: String, fileExtension: String): Boolean = isExtractable(fileExtension) && !keepsArchives(consoleId)

    fun isExtractable(fileExtension: String): Boolean {
        if (fileExtension.isBlank()) return false
        val ext = if (fileExtension.startsWith(".")) fileExtension.lowercase() else ".${fileExtension.lowercase()}"
        return SUPPORTED_EXTENSIONS.contains(ext)
    }
}
