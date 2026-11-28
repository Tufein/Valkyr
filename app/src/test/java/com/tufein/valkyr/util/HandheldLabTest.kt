package com.tufein.valkyr.util

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HandheldLabTest {

    @Test fun buttonsHaveNamesAndOtherKeysDoNot() {
        assertEquals("A", HandheldLab.buttonName(KeyEvent.KEYCODE_BUTTON_A))
        assertEquals("Select", HandheldLab.buttonName(KeyEvent.KEYCODE_BUTTON_SELECT))
        assertEquals("Up", HandheldLab.buttonName(KeyEvent.KEYCODE_DPAD_UP))
        assertNull(HandheldLab.buttonName(KeyEvent.KEYCODE_A))
    }

    @Test fun theFartherAxisWins() {
        assertEquals(0.5f, HandheldLab.pick(0.5f, 0.1f), 0f)
        assertEquals(-0.8f, HandheldLab.pick(0.1f, -0.8f), 0f)
    }

    @Test fun magnitudeIsClampedAtTheRim() {
        assertEquals(0f, HandheldLab.magnitude(0f, 0f), 0f)
        assertEquals(1f, HandheldLab.magnitude(1f, 1f), 0f)
        assertEquals(0.5f, HandheldLab.magnitude(0.3f, 0.4f), 0.0001f)
    }

    @Test fun driftNeedsEnoughReadings() {
        val meter = HandheldLab.DriftMeter()
        repeat(HandheldLab.DRIFT_MIN_SAMPLES - 1) { meter.add(0f, 0f) }
        assertNull(meter.result())
        meter.add(0f, 0f)
        assertEquals(HandheldLab.DriftVerdict.OK, meter.result()?.verdict)
    }

    @Test fun driftVerdictFollowsTheMeanNotOneBump() {
        val meter = HandheldLab.DriftMeter()
        repeat(99) { meter.add(0.01f, 0f) }
        meter.add(0.9f, 0f)
        val result = meter.result()
        assertNotNull(result)
        assertEquals(90, result!!.maxPercent)
        assertEquals(HandheldLab.DriftVerdict.OK, result.verdict)
        meter.reset()
        repeat(50) { meter.add(0.06f, 0.0f) }
        assertEquals(HandheldLab.DriftVerdict.SLIGHT, meter.result()?.verdict)
        meter.reset()
        repeat(50) { meter.add(0.2f, 0.1f) }
        assertEquals(HandheldLab.DriftVerdict.DRIFTING, meter.result()?.verdict)
    }

    @Test fun theKonamiCodeIsRecognisedAfterNoise() {
        val code = listOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_A
        )
        var history = listOf(KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y)
        var done = false
        for (k in code) { val r = HandheldLab.konami(history, k); history = r.first; done = r.second }
        assertEquals(true, done)
        assertEquals(false, HandheldLab.konami(history, KeyEvent.KEYCODE_BUTTON_A).second)
        assertEquals(10, history.size)
    }

    @Test fun touchGridCountsSquaresOnce() {
        val g = HandheldLab.TouchGrid(4, 2)
        assertEquals(8, g.total)
        assertEquals(true, g.mark(0.1f, 0.1f))
        assertEquals(false, g.mark(0.12f, 0.2f))
        assertEquals(1, g.touched)
        assertEquals(true, g.isHit(0, 0))
        assertEquals(false, g.isHit(3, 1))
        assertEquals(true, g.mark(1f, 1f))
        assertEquals(true, g.isHit(3, 1))
    }

    @Test fun aSwipeTurnsEverySquareOnTheWay() {
        val g = HandheldLab.TouchGrid(10, 10)
        g.markLine(0f, 0.05f, 1f, 0.05f)
        assertEquals((0 until 10).all { g.isHit(it, 0) }, true)
        assertEquals(10, g.touched)
        assertEquals(10, g.percent)
        for (row in 0 until 10) g.markLine(0f, (row + 0.5f) / 10f, 1f, (row + 0.5f) / 10f)
        assertEquals(true, g.complete)
        g.reset()
        assertEquals(0, g.touched)
    }

    @Test fun toneFillsTheChosenChannelOnly() {
        val pcm = HandheldLab.tone(440, 100, 8_000, left = true, right = false)
        assertEquals(800 * 2, pcm.size)
        assertEquals(true, (0 until 800).any { pcm[it * 2].toInt() != 0 })
        assertEquals(true, (0 until 800).all { pcm[it * 2 + 1].toInt() == 0 })
        assertEquals(0, pcm[0].toInt())
        val both = HandheldLab.tone(440, 50, 8_000, left = true, right = true)
        assertEquals(true, (0 until 400).all { both[it * 2] == both[it * 2 + 1] })
        assertEquals(true, both.maxOf { kotlin.math.abs(it.toInt()) } <= (0.31f * Short.MAX_VALUE))
    }

    @Test fun patternsCycleBothWays() {
        var p = HandheldLab.Pattern.BLACK
        repeat(HandheldLab.Pattern.entries.size) { p = HandheldLab.next(p) }
        assertEquals(HandheldLab.Pattern.BLACK, p)
        assertEquals(HandheldLab.Pattern.GRID, HandheldLab.previous(HandheldLab.Pattern.BLACK))
        assertEquals(0xFF000000, HandheldLab.solidColor(HandheldLab.Pattern.BLACK))
        assertNull(HandheldLab.solidColor(HandheldLab.Pattern.GRID))
    }

    @Test fun batteryReadingsAreSanitised() {
        val b = HandheldLab.battery(level = 73, scale = 100, tempTenths = 382, status = 2, voltageMv = 4012)
        assertEquals(73, b.percent)
        assertEquals(38.2f, b.celsius!!, 0.001f)
        assertEquals(HandheldLab.Charge.CHARGING, b.charge)
        assertEquals(4012, b.millivolts)
        val odd = HandheldLab.battery(level = -1, scale = 0, tempTenths = 0, status = 1, voltageMv = 0)
        assertNull(odd.percent); assertNull(odd.celsius); assertNull(odd.millivolts)
        assertEquals(HandheldLab.Charge.UNKNOWN, odd.charge)
    }

    @Test fun heatBands() {
        assertEquals(HandheldLab.Heat.COOL, HandheldLab.heat(35f))
        assertEquals(HandheldLab.Heat.WARM, HandheldLab.heat(41f))
        assertEquals(HandheldLab.Heat.HOT, HandheldLab.heat(46f))
    }
}
