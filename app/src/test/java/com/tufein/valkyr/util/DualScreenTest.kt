package com.tufein.valkyr.util

import com.tufein.valkyr.util.DualScreen.DisplayInfo
import com.tufein.valkyr.util.DualScreen.Layout
import com.tufein.valkyr.util.DualScreen.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DualScreenTest {

    private fun d(id: Int, valid: Boolean = true, presentation: Boolean = true) = DisplayInfo(id, valid, presentation)

    @Test
    fun `single screen has no target`() {
        assertNull(DualScreen.presentationTarget(emptyList(), 0))
        assertNull(DualScreen.presentationTarget(listOf(d(0, presentation = false)), 0))
    }

    @Test
    fun `second display is chosen, the activity's own display and invalid ones are not`() {
        assertEquals(4, DualScreen.presentationTarget(listOf(d(0, presentation = false), d(4)), 0))
        assertNull(DualScreen.presentationTarget(listOf(d(4)), 4))
        assertNull(DualScreen.presentationTarget(listOf(d(4, valid = false)), 0))
        assertEquals(2, DualScreen.presentationTarget(listOf(d(7), d(2)), 0))
        assertEquals(2, DualScreen.launchTarget(listOf(d(7), d(2)), 0))
    }

    @Test
    fun `a display the hub runs on is not a presentation target`() {
        assertNull(DualScreen.presentationTarget(listOf(d(0, presentation = false), d(4)), 0, setOf(4)))
        assertEquals(7, DualScreen.presentationTarget(listOf(d(4), d(7)), 0, setOf(4)))
        DualScreen.HubDisplays.add(4)
        assertEquals(setOf(4), DualScreen.HubDisplays.snapshot())
        DualScreen.HubDisplays.remove(4)
        assertEquals(emptySet<Int>(), DualScreen.HubDisplays.snapshot())
    }

    @Test
    fun `layout follows size and shape`() {
        assertEquals(Layout.WIDE, DualScreen.layout(900, 500))
        assertEquals(Layout.STACKED, DualScreen.layout(450, 392))
        assertEquals(Layout.STACKED, DualScreen.layout(600, 600))
        assertEquals(Layout.STACKED, DualScreen.layout(400, 800))
        assertEquals(Layout.WIDE, DualScreen.layout(720, 600))
        assertTrue(DualScreen.compact(392))
        assertFalse(DualScreen.compact(440))
    }

    @Test
    fun `chips count consoles and need two`() {
        assertTrue(DualScreen.chips(listOf("snes", "snes")).isEmpty())
        val chips = DualScreen.chips(listOf("snes", "gba", "gba", "", "nes"))
        assertEquals(listOf("gba", "nes", "snes"), chips.map { it.consoleId })
        assertEquals(2, chips.first().count)
        assertEquals(2, DualScreen.chips(listOf("a", "a", "b", "c"), limit = 2).size)
    }

    @Test
    fun `selection drops when its chip is gone and filter keeps order`() {
        val chips = DualScreen.chips(listOf("gba", "snes"))
        assertEquals("gba", DualScreen.resolveSelection("gba", chips))
        assertNull(DualScreen.resolveSelection("nes", chips))
        assertNull(DualScreen.resolveSelection(null, chips))
        val games = listOf("gba:a", "snes:b", "gba:c")
        assertEquals(listOf("gba:a", "gba:c"), DualScreen.filter(games, "gba") { it.substringBefore(':') })
        assertEquals(games, DualScreen.filter(games, null) { it.substringBefore(':') })
        assertEquals(listOf("snes:b", "gba:c"), DualScreen.railItems(games).also { assertEquals(2, it.size) })
    }

    @Test
    fun `sections follow history setting and queue`() {
        val idle = SecondScreenControls.Mode.IDLE
        val active = SecondScreenControls.Mode.ACTIVE
        val none = DualScreen.sections(0, 0, true, 0, idle)
        assertTrue(none.empty)
        assertFalse(none.queue)
        val full = DualScreen.sections(5, 5, true, 3, active)
        assertTrue(full.continueCard && full.rail && full.chips && full.queue)
        assertFalse(full.empty)
        val off = DualScreen.sections(5, 5, false, 3, idle)
        assertFalse(off.continueCard || off.rail || off.chips)
        assertTrue(off.empty)
        val one = DualScreen.sections(1, 1, true, 0, idle)
        assertTrue(one.continueCard)
        assertFalse(one.rail)
        // A filter that matches nothing keeps the chips so it can be undone.
        val filtered = DualScreen.sections(0, 4, true, 3, idle)
        assertTrue(filtered.chips)
        assertFalse(filtered.continueCard)
        assertFalse(filtered.empty)
    }

    @Test
    fun `battery and heat levels`() {
        assertEquals(Level.CRITICAL, DualScreen.deviceState(10, false, 300, 0).battery)
        assertEquals(Level.OK, DualScreen.deviceState(10, true, 300, 0).battery)
        assertEquals(Level.WARN, DualScreen.deviceState(25, false, null, 0).battery)
        assertEquals(Level.OK, DualScreen.deviceState(null, false, null, 0).battery)
        assertEquals(Level.CRITICAL, DualScreen.deviceState(80, false, 450, 0).heat)
        assertEquals(Level.CRITICAL, DualScreen.deviceState(80, false, 300, 3).heat)
        assertEquals(Level.WARN, DualScreen.deviceState(80, false, 410, 0).heat)
        assertEquals(Level.WARN, DualScreen.deviceState(80, false, null, 2).heat)
        assertEquals(Level.OK, DualScreen.deviceState(80, false, 350, 1).heat)
        assertEquals(35.0f, DualScreen.deviceState(80, false, 350, 1).tempCelsius!!, 0.01f)
    }
}
