package com.tufein.valkyr.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArcadeSetsTest {

    @Test fun `ROM set consoles keep their zips`() {
        listOf("arcade_mame", "arcade_fbneo", "mame", "fbneo", "snk_neo_geo", "neogeo", "sega_naomi", "atomiswave", "capcom_cps2", "Arcade - MAME")
            .forEach { assertTrue(it, ArchiveUtils.keepsArchives(it)) }
    }

    @Test fun `other consoles and the neo geo family that is not a set still unpack`() {
        listOf("nintendo_gameboy", "sony_playstation", "genesis", "snk_neo_geo_pocket", "snk_neo_geo_pocket_color", "neo_geo_cd", "pc_dos")
            .forEach { assertFalse(it, ArchiveUtils.keepsArchives(it)) }
    }

    @Test fun `auto extraction needs an archive and a console that does not keep it`() {
        assertTrue(ArchiveUtils.autoExtracts("nintendo_gameboy", "zip"))
        assertFalse(ArchiveUtils.autoExtracts("arcade_mame", "zip"))
        assertFalse(ArchiveUtils.autoExtracts("nintendo_gameboy", "gb"))
    }

    @Test fun `the arcade consoles have a name and folder aliases`() {
        assertEquals("MAME", ConsoleFormatter.getDefaultShortName("arcade_mame"))
        assertEquals("FBNeo", ConsoleFormatter.getDefaultShortName("arcade_fbneo"))
        assertTrue(ConsoleFolderAliases.matches("arcade_mame", "mame2003-plus"))
        assertTrue(ConsoleFolderAliases.matches("arcade_fbneo", "FBA"))
        assertFalse(ConsoleFolderAliases.matches("arcade_fbneo", "mame"))
    }

    @Test fun `the arcade BIOS sets are listed for both consoles`() {
        val system = BiosCatalog.systems.filter { "arcade_mame" in it.keys || "fbneo" in it.keys }
        assertEquals(1, system.size)
        assertTrue(system[0].files.any { it.path == "neogeo.zip" } && system[0].files.any { it.path == "pgm.zip" })
    }
}
