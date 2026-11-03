package com.tufein.valkyr.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemePreviewTest {
    @Test fun threeCardsInStepOrder() {
        assertEquals(listOf("LIGHT", "DARK", "TRUE_BLACK"), ThemePreview.looks.map { it.modeName })
    }

    @Test fun explicitModesResolveToThemselves() {
        assertEquals(ThemePreview.Look.LIGHT, ThemePreview.resolve("LIGHT", systemDark = true))
        assertEquals(ThemePreview.Look.TRUE_BLACK, ThemePreview.resolve("TRUE_BLACK", systemDark = false))
    }

    @Test fun systemFollowsTheDevice() {
        assertEquals(ThemePreview.Look.DARK, ThemePreview.resolve("SYSTEM", systemDark = true))
        assertEquals(ThemePreview.Look.LIGHT, ThemePreview.resolve("SYSTEM", systemDark = false))
        assertEquals(ThemePreview.Look.LIGHT, ThemePreview.resolve(null, systemDark = false))
        assertEquals(ThemePreview.Look.DARK, ThemePreview.resolve("nonsense", systemDark = true))
    }

    @Test fun onlyTheResolvedCardIsActive() {
        val active = ThemePreview.looks.filter { ThemePreview.isActive(it, "SYSTEM", systemDark = true) }
        assertEquals(listOf(ThemePreview.Look.DARK), active)
        assertFalse(ThemePreview.isActive(ThemePreview.Look.DARK, "TRUE_BLACK", systemDark = true))
    }

    @Test fun followsSystemOnlyForSystemOrUnknown() {
        assertTrue(ThemePreview.followsSystem("SYSTEM"))
        assertTrue(ThemePreview.followsSystem(null))
        assertFalse(ThemePreview.followsSystem("DARK"))
    }
}
