package com.tufein.valkyr.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VariantGroupsTest {

    private data class Row(val console: String, val fileName: String) {
        // As the scanners store it: the name without its tags and extension.
        val name: String = FileParsingUtils.extractNameAndTags(fileName.substringBeforeLast('.')).first
        val key: String = SearchNormalizer.key(name)
    }

    private fun group(rows: List<Row>) = VariantGroups.group(rows, { it.console }, { it.name }, { it.key }, { it.fileName }) { it.first() }

    private fun names(rows: List<Row>) = group(rows).map { it.item.fileName to it.versions }

    @Test fun `regional copies of one game become one entry with a version count`() {
        val rows = listOf(
            Row("snes", "Super Metroid (Europe) (En,Fr,De).sfc"),
            Row("snes", "Super Metroid (Japan, USA) (En,Ja).sfc"),
            Row("snes", "Super Metroid (Japan) (Rev 1).sfc")
        )
        assertEquals(listOf("Super Metroid (Europe) (En,Fr,De).sfc" to 3), names(rows))
    }

    @Test fun `the same game on another console is never merged`() {
        val rows = listOf(
            Row("gb", "Tetris (World) (Rev 1).gb"),
            Row("gb", "Tetris (USA, Europe).gb"),
            Row("nes", "Tetris (USA).nes"),
            Row("nes", "Tetris (Europe).nes"),
            Row("megadrive", "Tetris (Japan).md")
        )
        assertEquals(listOf("Tetris (World) (Rev 1).gb" to 2, "Tetris (USA).nes" to 2, "Tetris (Japan).md" to 1), names(rows))
    }

    @Test fun `different games with similar titles stay apart`() {
        val rows = listOf(
            Row("snes", "Super Mario World (USA).sfc"),
            Row("snes", "Super Mario World 2 - Yoshi's Island (USA).sfc"),
            Row("snes", "Super Mario World 2 - Yoshi's Island (Europe).sfc"),
            Row("snes", "Super Mario Kart (USA).sfc")
        )
        assertEquals(
            listOf("Super Mario World (USA).sfc" to 1, "Super Mario World 2 - Yoshi's Island (USA).sfc" to 2, "Super Mario Kart (USA).sfc" to 1),
            names(rows)
        )
    }

    @Test fun `discs of one game are not folded into each other`() {
        val rows = listOf(
            Row("psx", "Final Fantasy VII (USA) (Disc 1).chd"),
            Row("psx", "Final Fantasy VII (USA) (Disc 2).chd"),
            Row("psx", "Final Fantasy VII (Europe) (Disc 1).chd"),
            Row("psx", "Final Fantasy VII (Europe) (Disc 2).chd")
        )
        assertEquals(listOf("Final Fantasy VII (USA) (Disc 1).chd" to 2, "Final Fantasy VII (USA) (Disc 2).chd" to 2), names(rows))
    }

    @Test fun `the same file from several sources is not a group`() {
        val rows = listOf(Row("gba", "Metroid Fusion (USA).gba"), Row("gba", "Metroid Fusion (USA).gba"))
        assertEquals(listOf("Metroid Fusion (USA).gba" to 1, "Metroid Fusion (USA).gba" to 1), names(rows))
        val more = rows + Row("gba", "Metroid Fusion (Europe).gba")
        assertEquals(listOf("Metroid Fusion (USA).gba" to 2), names(more))
    }

    @Test fun `updates, DLC and hacks are never folded into the game`() {
        val rows = listOf(
            Row("switch", "Some Game (USA).nsp"),
            Row("switch", "Some Game (Update v1.0.1).nsp"),
            Row("switch", "Some Game (DLC).nsp"),
            Row("snes", "Zelda no Densetsu (Japan).sfc"),
            Row("snes", "Zelda no Densetsu (Hack).sfc"),
            Row("snes", "Zelda no Densetsu (Rev 1).sfc")
        )
        assertEquals(
            listOf(
                "Some Game (USA).nsp" to 1, "Some Game (Update v1.0.1).nsp" to 1, "Some Game (DLC).nsp" to 1,
                "Zelda no Densetsu (Japan).sfc" to 2, "Zelda no Densetsu (Hack).sfc" to 1
            ),
            names(rows)
        )
    }

    @Test fun `the group keeps the place of its first member and the picked version leads it`() {
        val rows = listOf(
            Row("nes", "Contra (Europe).nes"),
            Row("nes", "Contra (Japan) (Rev 1).nes"),
            Row("nes", "Mega Man 2 (USA).nes"),
            Row("nes", "Contra (USA).nes")
        )
        val grouped = VariantGroups.group(rows, { it.console }, { it.name }, { it.key }, { it.fileName }) { members -> members.first { "USA" in it.fileName } }
        assertEquals(listOf("Contra (USA).nes" to 3, "Mega Man 2 (USA).nes" to 1), grouped.map { it.item.fileName to it.versions })
    }

    @Test fun `a list without variants comes back unchanged`() {
        val rows = listOf(Row("gb", "Pokemon Red (USA).gb"), Row("gb", "Pokemon Blue (USA).gb"))
        assertEquals(rows, group(rows).map { it.item })
        assertTrue(group(rows).all { it.versions == 1 })
        assertEquals(emptyList<Row>(), group(emptyList()).map { it.item })
    }

    @Test fun `url encoded and plain file names count as the same file`() {
        // The scanners store the decoded title as name whatever the listing's spelling of the file name.
        val rows = listOf(Row("gba", "Wario Land 4 (USA).gba"), Row("gba", "Wario Land 4 (USA).gba"), Row("gba", "Wario Land 4 (Europe).gba"))
        val encoded = rows.map { it.copy(fileName = it.fileName.replace(" ", "%20").replace("(", "%28").replace(")", "%29")) to it }
        val grouped = VariantGroups.group(encoded, { it.second.console }, { it.second.name }, { it.second.key }, { it.first.fileName }) { it.first() }
        assertEquals(2, grouped.single().versions)
    }

    @Test fun `key rules`() {
        assertNull(VariantGroups.key("snes", "Game", SearchNormalizer.key("Game"), "Game (Update v1.1).nsp"))
        assertNotNull(VariantGroups.key("snes", "Game", "", "Game (USA).sfc"))
        assertFalse(VariantGroups.key("snes", "Game", "game", "Game (USA).sfc") == VariantGroups.key("gb", "Game", "game", "Game (USA).sfc"))
        assertEquals("b", SettingSchema.types[VariantGroups.GROUP_KEY])
    }
}
