package com.tufein.valkyr.util

/**
 * 7.5: when a daily cloud run that failed is tried again within the same window instead of waiting
 * for tomorrow. Only a passing problem (no network, a slow server) is retried; a wrong login, a
 * certificate or an address problem is final, so asking again would only repeat it. The waits grow
 * and the number of attempts is small: the job's own conditions (charging, unmetered network)
 * already stop a run on a flat battery or a mobile connection. Pure JVM for the tests.
 */
object CloudRetryPolicy {
    /** Waits before the second, third… attempt, in milliseconds. */
    val WAITS_MS: List<Long> = listOf(30_000L, 2 * 60_000L, 10 * 60_000L)

    /** The attempts a run makes in all (the first one included). */
    val MAX_ATTEMPTS: Int get() = WAITS_MS.size + 1

    /** Whether a failure with [problem] is worth another attempt. */
    fun retryable(problem: DavProblem): Boolean = problem == DavProblem.NETWORK || problem == DavProblem.TIMEOUT

    /** Whether [error] is worth another attempt. */
    fun retryable(error: Throwable): Boolean = error is DavException && retryable(error.problem)

    /**
     * How long to wait before attempt number [attempt] (the first retry is attempt 2), or null when
     * there is no further attempt, the problem is final or [stop] says the device should rest
     * (a low battery, a hot device, a lost network) .
     */
    fun waitBefore(attempt: Int, error: Throwable, stop: Boolean = false): Long? {
        if (stop || !retryable(error)) return null
        return WAITS_MS.getOrNull(attempt - 2)
    }
}
