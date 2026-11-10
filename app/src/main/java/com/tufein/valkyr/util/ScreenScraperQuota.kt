package com.tufein.valkyr.util

import java.time.ZoneId

/**
 * What the Cover sources screen shows of the ScreenScraper account's limits (5.5.0), and how
 * requests are paced. Pure JVM for the tests. The numbers come from the `ssuser` block that
 * ScreenScraper sends with `ssuserInfos.php` and with every request made with a user login
 * (`maxthreads`, `requeststoday` / `maxrequestsperday`, `requestskotoday` / `maxrequestskoperday`,
 * `niveau`); built from its public documentation, not tried against the real service.
 */
object ScreenScraperQuota {

    sealed interface State {
        /** No numbers for today: no login, never asked, or the last numbers are from an earlier day (the counters restart then). */
        data object Unknown : State
        /** [used] / [max] are null when ScreenScraper did not say them. */
        data class Open(
            val used: Int?,
            val max: Int?,
            val koUsed: Int?,
            val koMax: Int?,
            val threads: Int,
            val level: String,
            val asOf: Long
        ) : State {
            val remaining: Int? get() = if (used != null && max != null) (max - used).coerceAtLeast(0) else null
        }
        /** The daily quota is used up: nothing is sent before [resumeAt]. */
        data class Exhausted(val resumeAt: Long) : State
    }

    /**
     * The quota as it stands at [now]. [pausedUntil] is the source's pause for a used-up quota;
     * [user] / [userAt] the account as last reported. Numbers from an earlier day (France, where
     * ScreenScraper counts its days) do not count: they restart at midnight.
     */
    fun state(user: ScreenScraper.User?, userAt: Long, pausedUntil: Long, now: Long): State {
        if (now < pausedUntil) return State.Exhausted(pausedUntil)
        if (user == null || !sameDay(userAt, now)) return State.Unknown
        if (user.quotaUsedUp) return State.Exhausted(ScreenScraper.nextReset(now))
        return State.Open(user.requestsToday, user.maxRequestsPerDay, user.requestsKoToday, user.maxRequestsKoPerDay, user.maxThreads, user.level, userAt)
    }

    fun sameDay(a: Long, b: Long): Boolean {
        val zone = ZoneId.of("Europe/Paris")
        return ScreenScraper.dayOf(a, zone) == ScreenScraper.dayOf(b, zone)
    }

    /** Pause between two request starts (all threads together): a politeness choice of this app, ScreenScraper documents only the thread limit. */
    const val BASE_GAP_MS = 250L
    /** With this few requests left in the day (or fewer) the pace slows down, so the rest lasts. */
    const val LOW_QUOTA = 100
    const val LOW_QUOTA_GAP_MS = 1_000L

    /** The minimum time between two request starts, slower when the daily quota is nearly gone. */
    fun gapMs(remainingToday: Int?): Long =
        if (remainingToday != null && remainingToday <= LOW_QUOTA) LOW_QUOTA_GAP_MS else BASE_GAP_MS

    /**
     * How long a request wanting to start at [now] must still wait, when the last one started at
     * [lastStart] (0 = none yet); never more than [gap].
     */
    fun waitMs(now: Long, lastStart: Long, gap: Long): Long =
        if (lastStart <= 0L) 0L else (lastStart + gap - now).coerceIn(0L, gap)
}
