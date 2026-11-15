package com.tufein.valkyr.util

/**
 * 7.6: typing should not wait for the library. A query is started once the typing pauses; an
 * emptied box answers at once, because showing the whole shelf again is the cheap case.
 */
object SearchDebounce {
    const val PAUSE_MS = 250L

    fun waitMs(query: String): Long = if (query.isBlank()) 0L else PAUSE_MS
}
