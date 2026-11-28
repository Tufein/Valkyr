package com.tufein.valkyr.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LaunchWithTest {
    @Test fun nameAndVersion() = assertEquals("RetroArch (Snes9x) 1.19.1", LaunchWith.describe("RetroArch (Snes9x)", "1.19.1"))
    @Test fun noVersionIsJustTheName() = assertEquals("PPSSPP", LaunchWith.describe(" PPSSPP ", null))
    @Test fun versionAlreadyInTheNameIsNotRepeated() = assertEquals("DuckStation 0.1-7", LaunchWith.describe("DuckStation 0.1-7", "0.1-7"))
    @Test fun noNameIsNothing() { assertNull(LaunchWith.describe(null, "1.0")); assertNull(LaunchWith.describe("  ", "1.0")) }
    @Test fun oddVersionsAreLeftOut() {
        assertEquals("Dolphin", LaunchWith.describe("Dolphin", "x".repeat(40)))
        assertEquals("Dolphin", LaunchWith.describe("Dolphin", "1\n2"))
    }
    @Test fun longNamesAreCut() = assertEquals(LaunchWith.MAX_LENGTH, LaunchWith.describe("x".repeat(200), "1")!!.length)
}
