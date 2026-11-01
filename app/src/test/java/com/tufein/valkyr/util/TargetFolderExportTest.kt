package com.tufein.valkyr.util

import com.tufein.valkyr.util.TargetFolderLayouts.Layout
import com.tufein.valkyr.util.TargetFolderLayouts.System
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 6.0: the names and hints taken from the systems' public documentation (see the KDoc of [TargetFolderLayouts]). */
class TargetFolderExportTest {
    @Test fun `bios folders are only given where the documentation names them`() {
        assertEquals("bios", TargetFolderLayouts.biosFolder(Layout.BATOCERA))
        assertEquals("bios", TargetFolderLayouts.biosFolder(Layout.KNULLI))
        assertEquals("MUOS/bios", TargetFolderLayouts.biosFolder(Layout.MUOS))
        listOf(Layout.LIBRARY, Layout.ES_DE, Layout.ARKOS, Layout.ROCKNIX, Layout.ONION).forEach { assertNull("$it", TargetFolderLayouts.biosFolder(it)) }
    }

    @Test fun `gamelist update advice`() {
        assertTrue(TargetFolderLayouts.needsGamelistUpdate(Layout.BATOCERA))
        assertTrue(TargetFolderLayouts.needsGamelistUpdate(Layout.ROCKNIX))
        assertFalse(TargetFolderLayouts.needsGamelistUpdate(Layout.MUOS))
    }

    @Test fun `verified folder names per system`() {
        fun f(l: Layout, s: System) = TargetFolderLayouts.destinationFolder(l, s.keys.last().let { s.keys.first() }, "x", null)
        assertEquals("roms/snes", f(Layout.KNULLI, System.SNES))
        assertEquals("roms/segacd", f(Layout.KNULLI, System.SEGA_CD))
        assertEquals("roms/o2em", f(Layout.KNULLI, System.ODYSSEY_2))
        assertEquals("roms/megacd", f(Layout.ROCKNIX, System.SEGA_CD))
        assertEquals("roms/psx", f(Layout.ROCKNIX, System.PS1))
        assertEquals("ROMS/gba", f(Layout.MUOS, System.GBA))
        assertEquals("ROMS/psx", f(Layout.MUOS, System.PS1))
        assertEquals("roms/megacd", f(Layout.BATOCERA, System.SEGA_CD))
        assertEquals("ROMS/arcade", f(Layout.MUOS, System.ARCADE))
    }
}
