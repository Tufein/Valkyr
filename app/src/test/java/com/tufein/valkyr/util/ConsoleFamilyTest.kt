package com.tufein.valkyr.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ConsoleFamilyTest {
    @Test fun `the accessible palette has one distinct hue per family`() {
        val hues = ConsoleFamily.entries.map { it.accessibleArgb }
        assertEquals(ConsoleFamily.entries.size, hues.toSet().size)
        ConsoleFamily.entries.forEach { assertEquals(it.accessibleArgb, it.color(true)) }
        ConsoleFamily.entries.forEach { assertEquals(it.argb, it.color(false)) }
    }


    @Test fun `nintendo home consoles and handhelds get their own colours`() {
        assertEquals(ConsoleFamily.NINTENDO, ConsoleFamily.of("nintendo_entertainment_system"))
        assertEquals(ConsoleFamily.NINTENDO, ConsoleFamily.of("super_nintendo_entertainment_system"))
        assertEquals(ConsoleFamily.NINTENDO, ConsoleFamily.of("nintendo_gamecube"))
        assertEquals(ConsoleFamily.NINTENDO_HANDHELD, ConsoleFamily.of("nintendo_gameboy_advance"))
        assertEquals(ConsoleFamily.NINTENDO_HANDHELD, ConsoleFamily.of("nintendo_ds"))
        assertEquals(ConsoleFamily.NINTENDO_HANDHELD, ConsoleFamily.of("nintendo_3ds"))
    }

    @Test fun `words are matched whole`() {
        // "nes" inside "genesis", "ds" inside "dreamcast"
        assertEquals(ConsoleFamily.SEGA, ConsoleFamily.of("sega_genesis"))
        assertEquals(ConsoleFamily.SEGA, ConsoleFamily.of("sega_dreamcast"))
        assertEquals(ConsoleFamily.SEGA, ConsoleFamily.of("Sega Master System"))
    }

    @Test fun `other makers`() {
        assertEquals(ConsoleFamily.PLAYSTATION, ConsoleFamily.of("sony_psp"))
        assertEquals(ConsoleFamily.PLAYSTATION, ConsoleFamily.of("sony_playstation_2"))
        assertEquals(ConsoleFamily.XBOX, ConsoleFamily.of("microsoft_xbox_360"))
        assertEquals(ConsoleFamily.ATARI, ConsoleFamily.of("atari_2600"))
        assertEquals(ConsoleFamily.NEC, ConsoleFamily.of("nec_pc_engine"))
        assertEquals(ConsoleFamily.SNK, ConsoleFamily.of("snk_neo_geo_pocket_color"))
        assertEquals(ConsoleFamily.BANDAI, ConsoleFamily.of("bandai_wonderswan_color"))
        assertEquals(ConsoleFamily.ARCADE, ConsoleFamily.of("arcade"))
        assertEquals(ConsoleFamily.COMPUTER, ConsoleFamily.of("commodore_amiga"))
        assertEquals(ConsoleFamily.COMPUTER, ConsoleFamily.of("atari_st"))
    }

    @Test fun `unknown ids fall back to grey`() {
        assertEquals(ConsoleFamily.OTHER, ConsoleFamily.of("proper_romsets"))
        assertEquals(ConsoleFamily.OTHER, ConsoleFamily.of(""))
    }
}
