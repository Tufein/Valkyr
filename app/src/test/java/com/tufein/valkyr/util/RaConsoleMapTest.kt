package com.tufein.valkyr.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RaConsoleMapTest {

    private fun raId(id: String, name: String = "") = RaConsoleMap.forConsole(id, name)?.id

    @Test
    fun `the table has unique ids and every console has a spelling`() {
        val ids = RaConsoleMap.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(RaConsoleMap.all.all { it.keys.isNotEmpty() && it.name.isNotBlank() })
        assertEquals("Game Boy Advance", RaConsoleMap.byId(5)?.name)
        assertNull(RaConsoleMap.byId(9999))
    }

    @Test
    fun `RA's own ids of the big systems`() {
        assertEquals(1, raId("sega_genesis"))
        assertEquals(2, raId("nintendo_64"))
        assertEquals(3, raId("nintendo_snes"))
        assertEquals(4, raId("nintendo_gameboy"))
        assertEquals(5, raId("nintendo_gameboy_advance"))
        assertEquals(6, raId("nintendo_gameboy_color"))
        assertEquals(7, raId("nintendo_nes"))
        assertEquals(12, raId("sony_playstation"))
        assertEquals(21, raId("sony_playstation_2"))
        assertEquals(41, raId("sony_psp"))
        assertEquals(39, raId("sega_saturn"))
        assertEquals(40, raId("sega_dreamcast"))
        assertEquals(16, raId("nintendo_gamecube"))
        assertEquals(18, raId("nintendo_ds"))
        assertEquals(62, raId("nintendo_3ds"))
    }

    @Test
    fun `ids come in many spellings`() {
        assertEquals(3, raId("super_nintendo_entertainment_system"))
        assertEquals(3, raId("nintendo_super_nintendo_entertainment_system"))
        assertEquals(7, raId("nintendo_entertainment_system"))
        assertEquals(5, raId("nintendo_gba"))
        assertEquals(5, raId("gameboyadvance"))
        assertEquals(1, raId("sega_md"))
        assertEquals(1, raId("sega_mega_drive"))
        assertEquals(1, raId("megadrive"))
        assertEquals(8, raId("nec_turbografx_16"))
        assertEquals(8, raId("nec_pc_engine"))
        assertEquals(11, raId("sega_master_system"))
        assertEquals(15, raId("sega_game_gear"))
        assertEquals(53, raId("bandai_wonderswan_color"))
        assertEquals(14, raId("snk_neo_geo_pocket_color"))
        assertEquals(12, raId("sony_psx"))
    }

    @Test
    fun `the most specific console wins`() {
        // Game Boy, Game Boy Color and Game Boy Advance share a word.
        assertEquals(6, raId("gameboy_color"))
        assertEquals(5, raId("game_boy_advance"))
        assertEquals(4, raId("game_boy"))
        // PlayStation, 2 and Portable.
        assertEquals(21, raId("playstation_2"))
        assertEquals(21, raId("ps2"))
        assertEquals(41, raId("playstation_portable"))
        // Super Nintendo is not the NES, Wii U is not the Wii.
        assertEquals(3, raId("super_nes"))
        assertEquals(20, raId("nintendo_wii_u"))
        assertEquals(19, raId("nintendo_wii"))
        // Nintendo 3DS is not the DS.
        assertEquals(62, raId("3ds"))
        assertEquals(18, raId("nds"))
    }

    @Test
    fun `disc add-ons are their own systems`() {
        assertEquals(9, raId("sega_cd"))
        assertEquals(9, raId("mega_cd"))
        assertEquals(10, raId("sega_32x"))
        assertEquals(76, raId("nec_pc_engine_cd"))
        assertEquals(76, raId("turbografx_cd"))
        assertEquals(56, raId("snk_neo_geo_cd"))
        assertEquals(1, raId("sega_genesis"))
    }

    @Test
    fun `systems RA does not have map to nothing`() {
        assertNull(raId("atari_jaguar_cd"))
        assertNull(raId("nintendo_famicom_disk_system"))
        assertNull(raId("nintendo_dsi"))
        assertNull(raId("sony_playstation_vita"))
        assertNull(raId("sony_playstation_3"))
        assertNull(raId("microsoft_xbox_360"))
        assertNull(raId("snk_neo_geo"))
        assertEquals(30, raId("commodore_64"))
        assertNull(raId(""))
        assertNull(raId("proper_romsets"))
    }

    @Test
    fun `the console's display name can decide when the id does not`() {
        assertEquals(41, raId("sony_handheld", "PlayStation Portable"))
        assertEquals(5, raId("custom_1", "Game Boy Advance"))
        // The id is not misread because of a longer name: the best of both counts.
        assertEquals(12, raId("sony_ps1", "Sony PlayStation"))
        assertNull(raId("custom_2", "Atari Jaguar CD"))
    }

    @Test
    fun `mapAll keeps only the consoles RA has`() {
        val map = RaConsoleMap.mapAll(listOf("nintendo_gba" to "GBA", "snk_neo_geo" to "Neo Geo", "sony_psp" to "PSP"))
        assertEquals(setOf("nintendo_gba", "sony_psp"), map.keys)
        assertEquals(41, map["sony_psp"]?.id)
    }

    @Test
    fun `agrees with the cartridge table the RA tool already uses`() {
        // Every system the older table knows must come out with the same RA id here.
        for (console in RetroAchievements.consoles) {
            val key = console.keys.first()
            val mine = RaConsoleMap.forConsole(key)
            assertEquals("${console.name} via $key", console.id, mine?.id)
        }
    }
}
