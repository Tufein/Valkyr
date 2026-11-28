package com.tufein.valkyr.util

import android.view.KeyEvent
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 10.0: the arithmetic behind the Handheld lab (controller, screen and battery tests). Nothing here
 * touches the device; the screen feeds it readings. Pure JVM.
 */
object HandheldLab {

    // ---- Controller --------------------------------------------------------------------------

    /** The name of a gamepad key as printed on most handhelds, or null for a key that is not a button. */
    fun buttonName(keyCode: Int): String? = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> "A"
        KeyEvent.KEYCODE_BUTTON_B -> "B"
        KeyEvent.KEYCODE_BUTTON_X -> "X"
        KeyEvent.KEYCODE_BUTTON_Y -> "Y"
        KeyEvent.KEYCODE_BUTTON_L1 -> "L1"
        KeyEvent.KEYCODE_BUTTON_R1 -> "R1"
        KeyEvent.KEYCODE_BUTTON_L2 -> "L2"
        KeyEvent.KEYCODE_BUTTON_R2 -> "R2"
        KeyEvent.KEYCODE_BUTTON_THUMBL -> "L3"
        KeyEvent.KEYCODE_BUTTON_THUMBR -> "R3"
        KeyEvent.KEYCODE_BUTTON_SELECT -> "Select"
        KeyEvent.KEYCODE_BUTTON_START -> "Start"
        KeyEvent.KEYCODE_BUTTON_MODE -> "Mode"
        KeyEvent.KEYCODE_DPAD_UP -> "Up"
        KeyEvent.KEYCODE_DPAD_DOWN -> "Down"
        KeyEvent.KEYCODE_DPAD_LEFT -> "Left"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "Right"
        KeyEvent.KEYCODE_DPAD_CENTER -> "Centre"
        KeyEvent.KEYCODE_BUTTON_C -> "C"
        KeyEvent.KEYCODE_BUTTON_Z -> "Z"
        KeyEvent.KEYCODE_MENU -> "Menu"
        else -> null
    }

    private val KONAMI = listOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_A
    )

    /** The last ten key presses after [key], and whether they are the Konami code (up up down down left right left right B A). */
    fun konami(history: List<Int>, key: Int): Pair<List<Int>, Boolean> {
        val next = (history + key).takeLast(KONAMI.size)
        return next to (next == KONAMI)
    }

    /** Some pads report the right stick on Z/RZ, others on RX/RY: the axis that is pushed further wins. */
    fun pick(primary: Float, fallback: Float): Float = if (abs(primary) >= abs(fallback)) primary else fallback

    /** How far a stick is pushed, 0 (centre) to 1 (rim); a corner reading above 1 is clamped. */
    fun magnitude(x: Float, y: Float): Float = hypot(x, y).coerceAtMost(1f)

    enum class DriftVerdict { OK, SLIGHT, DRIFTING }

    data class Drift(val samples: Int, val meanPercent: Int, val maxPercent: Int, val verdict: DriftVerdict)

    /** Slight drift from this mean, and drift that games will feel. */
    const val SLIGHT_FROM_PERCENT = 4
    const val DRIFTING_FROM_PERCENT = 10
    const val DRIFT_MIN_SAMPLES = 20

    /**
     * Collects readings of a stick the player has let go of. The verdict follows the mean (one bump when
     * letting go does not count as drift) and the largest value is reported beside it.
     */
    class DriftMeter {
        private var count = 0
        private var sum = 0f
        private var max = 0f

        val samples: Int get() = count

        fun add(x: Float, y: Float) {
            val m = magnitude(x, y)
            count++; sum += m; if (m > max) max = m
        }

        /** Null until there are enough readings to say anything. */
        fun result(): Drift? {
            if (count < DRIFT_MIN_SAMPLES) return null
            val mean = (sum / count * 100f).roundToInt()
            return Drift(count, mean, (max * 100f).roundToInt(), verdictFor(mean))
        }

        fun reset() { count = 0; sum = 0f; max = 0f }
    }

    fun verdictFor(meanPercent: Int): DriftVerdict = when {
        meanPercent >= DRIFTING_FROM_PERCENT -> DriftVerdict.DRIFTING
        meanPercent >= SLIGHT_FROM_PERCENT -> DriftVerdict.SLIGHT
        else -> DriftVerdict.OK
    }

    // ---- Screen ------------------------------------------------------------------------------

    enum class Pattern { BLACK, WHITE, RED, GREEN, BLUE, GREY_RAMP, GRID }

    fun next(p: Pattern): Pattern = Pattern.entries[(p.ordinal + 1) % Pattern.entries.size]

    fun previous(p: Pattern): Pattern = Pattern.entries[(p.ordinal + Pattern.entries.size - 1) % Pattern.entries.size]

    /** The solid colour of a pattern (opaque ARGB), or null for the ones that are drawn. */
    fun solidColor(p: Pattern): Long? = when (p) {
        Pattern.BLACK -> 0xFF000000
        Pattern.WHITE -> 0xFFFFFFFF
        Pattern.RED -> 0xFFFF0000
        Pattern.GREEN -> 0xFF00FF00
        Pattern.BLUE -> 0xFF0000FF
        else -> null
    }

    // ---- Touch and sound ---------------------------------------------------------------------

    /** The screen cut into squares; a touch (or a swipe) turns the squares it passes over. */
    class TouchGrid(val cols: Int, val rows: Int) {
        private val hit = BooleanArray((cols * rows).coerceAtLeast(1))
        var touched: Int = 0
            private set

        val total: Int get() = hit.size
        val percent: Int get() = touched * 100 / total
        val complete: Boolean get() = touched == total

        fun isHit(col: Int, row: Int): Boolean = col in 0 until cols && row in 0 until rows && hit[row * cols + col]

        /** A touch at a fraction (0..1) of the width and height; true when it turned a new square. */
        fun mark(fx: Float, fy: Float): Boolean {
            if (cols <= 0 || rows <= 0) return false
            val c = (fx * cols).toInt().coerceIn(0, cols - 1)
            val r = (fy * rows).toInt().coerceIn(0, rows - 1)
            val i = r * cols + c
            if (hit[i]) return false
            hit[i] = true; touched++
            return true
        }

        /** A swipe from one point to the next turns every square on the way (a fast finger skips whole squares between events). */
        fun markLine(x0: Float, y0: Float, x1: Float, y1: Float): Boolean {
            val steps = (maxOf(abs(x1 - x0) * cols, abs(y1 - y0) * rows) * 2f).toInt().coerceIn(1, 400)
            var any = false
            for (s in 0..steps) {
                val t = s / steps.toFloat()
                if (mark(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t)) any = true
            }
            return any
        }

        fun reset() { hit.fill(false); touched = 0 }
    }

    /** 16-bit stereo PCM (interleaved left, right) of a sine tone on the chosen channels, with a short fade at both ends so it does not click. */
    fun tone(freqHz: Int, millis: Int, sampleRate: Int, left: Boolean, right: Boolean, amplitude: Float = 0.3f): ShortArray {
        val frames = (sampleRate.toLong() * millis / 1000L).toInt().coerceAtLeast(0)
        val out = ShortArray(frames * 2)
        val fade = (sampleRate / 100).coerceAtLeast(1)
        for (i in 0 until frames) {
            val envelope = minOf(1f, minOf(i, frames - 1 - i).toFloat() / fade)
            val v = (kotlin.math.sin(2.0 * Math.PI * freqHz * i / sampleRate) * amplitude * envelope * Short.MAX_VALUE).toInt().toShort()
            if (left) out[i * 2] = v
            if (right) out[i * 2 + 1] = v
        }
        return out
    }

    // ---- Battery and heat --------------------------------------------------------------------

    enum class Charge { CHARGING, DISCHARGING, FULL, UNKNOWN }

    enum class Heat { COOL, WARM, HOT }

    data class Battery(val percent: Int?, val celsius: Float?, val charge: Charge, val millivolts: Int?)

    /** BatteryManager reports temperature in tenths of a degree; absent or absurd values become null. */
    fun battery(level: Int, scale: Int, tempTenths: Int, status: Int, voltageMv: Int): Battery {
        val percent = if (level >= 0 && scale > 0) (level * 100f / scale).roundToInt().coerceIn(0, 100) else null
        val celsius = if (tempTenths in -300..1_500 && tempTenths != 0) tempTenths / 10f else null
        val charge = when (status) {
            2 -> Charge.CHARGING          // BatteryManager.BATTERY_STATUS_CHARGING
            3, 4 -> Charge.DISCHARGING    // DISCHARGING, NOT_CHARGING
            5 -> Charge.FULL
            else -> Charge.UNKNOWN
        }
        return Battery(percent, celsius, charge, voltageMv.takeIf { it in 2_000..6_000 })
    }

    const val WARM_FROM_C = 40f
    const val HOT_FROM_C = 45f

    fun heat(celsius: Float): Heat = when {
        celsius >= HOT_FROM_C -> Heat.HOT
        celsius >= WARM_FROM_C -> Heat.WARM
        else -> Heat.COOL
    }
}
