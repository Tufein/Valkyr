package com.tufein.valkyr.util

import com.tufein.valkyr.util.EmulatorCheck.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmulatorCheckTest {
    private val gba = "nintendo_gameboy_advance"
    private val retroarch64 = "com.retroarch.aarch64"

    @Test fun retroArchAloneCoversAConsoleWithCores() {
        val c = EmulatorCheck.coverage(gba, setOf(retroarch64))
        assertEquals(Status.RETROARCH, c.status)
        assertTrue(c.installed.any { it.startsWith("RetroArch") })
    }

    @Test fun nothingInstalledSuggestsCatalogueEmulatorsStandaloneFirst() {
        val c = EmulatorCheck.coverage(gba, emptySet())
        assertEquals(Status.MISSING, c.status)
        assertTrue(c.suggestions.isNotEmpty())
        assertTrue(c.suggestions.size <= 4)
    }

    @Test fun consolesTheCatalogueDoesNotKnowAreUnsupported() {
        assertEquals(Status.UNSUPPORTED, EmulatorCheck.coverage("nintendo_switch_2", setOf(retroarch64)).status)
        assertEquals(Status.UNSUPPORTED, EmulatorCheck.coverage("some_unknown_machine", emptySet()).status)
    }

    @Test fun installedListUsesTheFirstInstalledVariantAndVersion() {
        val list = EmulatorCheck.installed(setOf(retroarch64)) { if (it == retroarch64) "1.20.0" else null }
        val ra = list.single { it.emulator.id == "retroarch" }
        assertEquals("1.20.0", ra.version)
        assertEquals("RetroArch (64-bit)", EmulatorCheck.label(ra.emulator, ra.variant))
    }

    @Test fun problemsComeFirst() {
        val out = EmulatorCheck.coverages(listOf(gba, "nintendo_switch_2", "some_unknown"), setOf(retroarch64)) { it }
        assertEquals(Status.UNSUPPORTED, out.first().status)
        assertEquals(Status.RETROARCH, out.last().status)
    }
}
