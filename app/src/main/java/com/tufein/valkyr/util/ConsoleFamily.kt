package com.tufein.valkyr.util

/**
 * The family a console belongs to, and the colour 5.0 gives it (console chips, cover placeholders,
 * statistics). Matched on words in the console id (`nintendo_gameboy_advance`, `sony_psp`,
 * `super_nintendo_entertainment_system`), so imported sources with their own ids still get a
 * sensible colour. Pure JVM for the tests.
 */
enum class ConsoleFamily(val argb: Long, val accessibleArgb: Long) {
    NINTENDO(0xFFFF5A5F, 0xFFD55E00),
    NINTENDO_HANDHELD(0xFF8FCF3C, 0xFF009E73),
    PLAYSTATION(0xFF5B8CFF, 0xFF0072B2),
    SEGA(0xFF2FA8FF, 0xFF56B4E9),
    XBOX(0xFF5CC93B, 0xFF6A3D9A),
    ATARI(0xFFFF8A3D, 0xFFE69F00),
    NEC(0xFFFFB547, 0xFFF0E442),
    SNK(0xFFE7C24A, 0xFFCC79A7),
    BANDAI(0xFFE86BD6, 0xFF8C564B),
    ARCADE(0xFFA57BFF, 0xFF9467BD),
    COMPUTER(0xFF2ED6B8, 0xFF17BECF),
    OTHER(0xFF9AA0AE, 0xFF888888);

    /** The colour used by the chips and cover placeholders; distinct hues for colour-blind players. */
    fun color(accessiblePalette: Boolean): Long = if (accessiblePalette) accessibleArgb else argb

    companion object {
        private val handheld = listOf("gameboy", "game_boy", "gba", "gbc", "nintendo_ds", "ds", "dsi", "3ds", "pokemon_mini", "virtual_boy", "nds")
        private val nintendo = listOf("nintendo", "nes", "famicom", "snes", "n64", "gamecube", "wii", "switch")
        private val playstation = listOf("playstation", "psp", "ps1", "ps2", "ps3", "psx", "vita", "sony")
        private val sega = listOf("sega", "genesis", "mega_drive", "megadrive", "master_system", "game_gear", "saturn", "dreamcast", "32x", "sg_1000", "mega_cd", "sega_cd")
        private val xbox = listOf("xbox", "microsoft")
        private val atari = listOf("atari", "lynx", "jaguar")
        private val nec = listOf("nec", "pc_engine", "pce", "turbografx", "pc_fx", "supergrafx")
        private val snk = listOf("snk", "neo_geo", "neogeo", "ngp")
        private val bandai = listOf("bandai", "wonderswan")
        private val arcade = listOf("arcade", "mame", "fbneo", "fba", "cps1", "cps2", "cps3", "naomi", "atomiswave")
        private val computer = listOf("amiga", "commodore", "c64", "msx", "msx2", "dos", "zx", "spectrum", "amstrad", "cpc", "apple", "pc98", "x68000", "atari_st", "scummvm", "computer")

        fun of(consoleId: String): ConsoleFamily {
            val id = "_" + consoleId.lowercase().replace('-', '_').replace(' ', '_') + "_"
            // Whole words only: "nes" must not match "genesis", "ds" not "dreamcast".
            fun has(words: List<String>) = words.any { w -> id.contains("_${w}_") }
            return when {
                has(arcade) -> ARCADE
                has(computer) -> COMPUTER
                has(handheld) -> NINTENDO_HANDHELD
                has(nintendo) -> NINTENDO
                has(playstation) -> PLAYSTATION
                has(sega) -> SEGA
                has(xbox) -> XBOX
                has(atari) -> ATARI
                has(nec) -> NEC
                has(snk) -> SNK
                has(bandai) -> BANDAI
                else -> OTHER
            }
        }
    }
}
