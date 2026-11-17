package com.tufein.valkyr.util

import kotlin.math.abs

/** The text size setting: a percentage on top of Android's own font size, in a few fixed steps. */
object TextSize {
    val STEPS = listOf(85, 100, 115, 130, 150, 175, 200)
    const val DEFAULT = 100

    /** The step [delta] places from [current]; a value between steps counts as the nearest step. */
    fun shift(current: Int, delta: Int): Int {
        val index = STEPS.indices.minBy { abs(STEPS[it] - current) }
        return STEPS[(index + delta).coerceIn(0, STEPS.lastIndex)]
    }

    /** Factor for the font scale; a value that is not a step (an old or broken setting) is 100 %. */
    fun factor(percent: Int): Float = (if (percent in STEPS) percent else DEFAULT) / 100f
}
