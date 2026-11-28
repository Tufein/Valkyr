package com.tufein.valkyr.util

/**
 * 10.0: what a launch was started with, as one short line for the "It started and stopped again"
 * card and for a problem report: the emulator as the app knows it (core included) and its version
 * as the system reports it. Pure JVM.
 */
object LaunchWith {
    const val MAX_LENGTH = 80

    /** `RetroArch (Snes9x) 1.19.1`, `PPSSPP`, or null when not even a name is known. */
    fun describe(label: String?, version: String?): String? {
        val name = label?.trim().orEmpty().replace(Regex("\\s+"), " ")
        if (name.isEmpty()) return null
        val v = version?.trim().orEmpty().takeIf { it.isNotEmpty() && it.length <= 24 && it.none { c -> c.isISOControl() } }
        val line = if (v != null && !name.contains(v)) "$name $v" else name
        return line.take(MAX_LENGTH)
    }
}
