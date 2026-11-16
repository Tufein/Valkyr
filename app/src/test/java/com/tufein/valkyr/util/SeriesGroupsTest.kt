package com.tufein.valkyr.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesGroupsTest {

    private data class Row(val console: String, val name: String)

    private fun group(vararg rows: Row) = SeriesGroups.group(rows.toList(), { it.console }, { it.name })

    @Test fun `known franchise meets across title styles and spellings`() {
        assertEquals("megaman", SeriesKey.of("Mega Man X (USA)")?.key)
        assertEquals("megaman", SeriesKey.of("Rockman 3 (Japan)")?.key)
        assertEquals("pokemon", SeriesKey.of("Pokémon - Red Version")?.key)
        assertEquals("pokemon", SeriesKey.of("Pocket Monsters Gold (Japan)")?.key)
        assertEquals("mario", SeriesKey.of("Super Mario Kart (USA)")?.key)
        assertEquals("mario", SeriesKey.of("Yoshi's Island")?.key)
        assertEquals("residentevil", SeriesKey.of("Biohazard 2 (Japan)")?.key)
    }

    @Test fun `unknown titles use their first two words and nothing else`() {
        val s = SeriesKey.of("Space Harrier II (Europe)")
        assertEquals("w:space harrier", s?.key)
        assertEquals(false, s?.known)
        assertNull(SeriesKey.of("(USA)"))
    }

    @Test fun `key keeps consoles apart`() {
        assertEquals("snes|megaman", SeriesKey.key("snes", "Mega Man X"))
        assertEquals("nes|megaman", SeriesKey.key("nes", "Mega Man 2"))
    }

    @Test fun `two games of a known franchise fold into one entry with a badge`() {
        val out = group(Row("snes", "Mega Man X"), Row("snes", "Super Metroid"), Row("snes", "Mega Man X2"))
        assertEquals(listOf("Mega Man X", "Super Metroid"), out.map { it.item.name })
        assertEquals(SeriesGroups.Badge("Mega Man", 2), out[0].badge)
        assertNull(out[1].badge)
    }

    @Test fun `a lone game of a franchise stays alone`() {
        val out = group(Row("snes", "Mega Man X"), Row("snes", "Super Metroid"))
        assertTrue(out.all { it.badge == null })
        assertEquals(2, out.size)
    }

    @Test fun `two heuristic matches stay apart but three fold`() {
        assertTrue(group(Row("md", "Space Harrier"), Row("md", "Space Harrier II")).all { it.badge == null })
        val three = group(Row("md", "Space Harrier"), Row("md", "Space Harrier II"), Row("md", "Space Harrier 3D"))
        assertEquals(1, three.size)
        assertEquals(3, three[0].badge?.games)
    }

    @Test fun `other consoles never fold together and order is kept`() {
        val out = group(Row("nes", "Mega Man"), Row("snes", "Mega Man X"), Row("nes", "Mega Man 2"))
        assertEquals(listOf("Mega Man", "Mega Man X"), out.map { it.item.name })
        assertNotNull(out[0].badge)
        assertNull(out[1].badge)
    }

    @Test fun `regional copies count as one game`() {
        val out = group(Row("snes", "Mega Man X"), Row("snes", "Mega Man X"))
        assertTrue(out.all { it.badge == null })
    }

    @Test fun `search text names the franchise or the lead words`() {
        assertEquals("mega man", SeriesKey.query("Rockman X"))
        assertEquals("space harrier", SeriesKey.query("Space Harrier II"))
    }

    @Test fun `the switch is off by default and travels in backups`() {
        assertEquals(false, SeriesGroups.DEFAULT_ON)
        assertEquals("b", SettingSchema.types[SeriesGroups.GROUP_KEY])
    }
}
