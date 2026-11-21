package com.tufein.valkyr.data.service

import android.util.Log
import com.tufein.valkyr.data.local.CoverSourceSettings
import com.tufein.valkyr.util.CoverSources
import com.tufein.valkyr.util.GameTitleCleaner
import com.tufein.valkyr.util.LibretroThumbnails
import com.tufein.valkyr.util.ScreenScraper
import com.tufein.valkyr.util.ScreenScraperQuota
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Box art from ScreenScraper (4.3.0), by ROM file name and, when that is unknown there, by a name
 * search. Keeps to ScreenScraper's rules: the software name and a User-Agent on every request, no
 * more threads at once than the account allows (`maxthreads`, 1 without a login), a minute's
 * pause after "too many requests", the rest of the day off once the daily quota is used up, an
 * hour off after a refused login, and a "not found" is the only answer that counts as a miss.
 *
 * Built from ScreenScraper's public API documentation; it could not be tried against the real
 * service while it was written. Credentials never reach a log: only status codes are logged.
 */
@Singleton
class ScreenScraperService @Inject constructor(
    private val settings: CoverSourceSettings,
    private val stats: CoverSourceStats
) : CoverLookupSource {
    override val source = CoverSources.Source.SCREENSCRAPER
    @Volatile private var threads = ScreenScraper.DEFAULT_THREADS
    @Volatile private var permits = Semaphore(threads)
    /** No requests before this moment (a short pause after 429, longer after a refused login or a closed API). */
    @Volatile private var backoffUntil = 0L
    /** The credentials the backoff belongs to: new credentials end it at once. */
    @Volatile private var backoffFor = 0
    /** Keeps request starts a little apart (see [ScreenScraperQuota.gapMs]); the thread limit is the semaphore above. */
    private val paceLock = Mutex()
    private var lastStart = 0L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var refreshJob: Job? = null

    sealed interface LoginResult {
        data class LoggedIn(val user: ScreenScraper.User) : LoginResult
        /** The developer credentials work; no user login was given (fewer requests a day). */
        data object DeveloperOnly : LoginResult
        data object DeveloperMissing : LoginResult
        data object DeveloperRejected : LoginResult
        data object WrongLogin : LoginResult
        data class QuotaReached(val until: Long) : LoginResult
        data object Busy : LoginResult
        data object Offline : LoginResult
    }

    /** Whether the developer credentials are filled in (without them the source is simply skipped). */
    override suspend fun configured(): Boolean = settings.credentials.first().ready

    /**
     * Called when a scrape went through ScreenScraper: a while after the last one (a batch is over)
     * the account's numbers are asked once more, so the screen shows where the day stands. With a
     * user login every answer carries the numbers already; this makes sure the last ones are in.
     */
    fun noteActivity() {
        synchronized(this) {
            refreshJob?.cancel()
            refreshJob = scope.launch {
                delay(BATCH_IDLE_MS)
                runCatching { refreshQuota() }
            }
        }
    }

    /** Asks for the account's numbers (no cover lookup); a no-op without a login, while paused or backing off. */
    suspend fun refreshQuota() {
        val c = settings.credentials.first()
        if (!c.ready || !c.hasLogin || !usable(System.currentTimeMillis())) return
        call(c, ScreenScraper.userUrl(c), counted = false)
    }

    /** Waits until the next request may start: [ScreenScraperQuota.gapMs] after the previous one. */
    private suspend fun pace() = paceLock.withLock {
        val st = stats.state.value
        val user = st.user?.takeIf { ScreenScraperQuota.sameDay(st.userAt, System.currentTimeMillis()) }
        val gap = ScreenScraperQuota.gapMs(user?.remainingToday)
        val wait = ScreenScraperQuota.waitMs(System.currentTimeMillis(), lastStart, gap)
        if (wait > 0) delay(wait)
        lastStart = System.currentTimeMillis()
    }

    /** Whether ScreenScraper can be asked right now: credentials there, not paused, not backing off. */
    override suspend fun usable(now: Long): Boolean {
        val c = settings.credentials.first()
        if (!c.ready) return false
        if (now < stats.state.value.pausedUntil) return false
        return !(now < backoffUntil && backoffFor == c.fingerprint)
    }

    /** [url] with the credentials, when it is a ScreenScraper address (stored covers are kept without them). */
    suspend fun signed(url: String): String =
        if (ScreenScraper.isApiUrl(url)) ScreenScraper.withCredentials(url, settings.credentials.first()) else url

    /** The cover of a game: [fileName] on its console, else the best match of [title]'s search. */
    override suspend fun find(consoleId: String, fileName: String, title: String, regions: List<String>, nonBoxArt: Boolean): CoverSources.Answer =
        lookup(consoleId, fileName, title) { game -> ScreenScraper.pickCover(game, regions, nonBoxArt) }

    /** 5.6.0: the title screen or screenshot of a game (the launcher covers' extra image types); answers like [find]. */
    suspend fun findArt(consoleId: String, fileName: String, title: String, regions: List<String>, art: LibretroThumbnails.Art): CoverSources.Answer =
        lookup(consoleId, fileName, title) { game -> ScreenScraper.pickArt(game, regions, art) }

    /** 7.7: a game's manual from ScreenScraper's `manual` media, with the same gates as a cover lookup. */
    suspend fun findManual(consoleId: String, fileName: String, title: String, regions: List<String>): CoverSources.Answer =
        lookup(consoleId, fileName, title) { game -> ScreenScraper.pickManual(game, regions) }

    private suspend fun lookup(consoleId: String, fileName: String, title: String, pick: (ScreenScraper.Game) -> String?): CoverSources.Answer {
        val system = ScreenScraper.systemId(consoleId) ?: return CoverSources.Answer.Miss
        val c = settings.credentials.first()
        if (!c.ready || !usable(System.currentTimeMillis())) return CoverSources.Answer.Unavailable
        val byName = call(c, ScreenScraper.gameUrl(c, system, fileName))
        when (byName.status) {
            ScreenScraper.Status.OK -> {
                val game = ScreenScraper.parseGame(byName.json) ?: return CoverSources.Answer.Miss
                return answer(pick(game))
            }
            // 400: ScreenScraper could not take this file name; the title search may still find the game.
            ScreenScraper.Status.NOT_FOUND, ScreenScraper.Status.BAD_REQUEST -> Unit
            else -> return CoverSources.Answer.Unavailable
        }
        val query = GameTitleCleaner.clean(title.ifBlank { fileName })
        if (query.isBlank()) return CoverSources.Answer.Miss
        val search = call(c, ScreenScraper.searchUrl(c, system, query))
        return when (search.status) {
            ScreenScraper.Status.OK -> {
                val game = ScreenScraper.bestMatch(ScreenScraper.parseSearch(search.json), query) ?: return CoverSources.Answer.Miss
                answer(pick(game))
            }
            // A request ScreenScraper refuses as malformed would be refused again: a miss for this title.
            ScreenScraper.Status.NOT_FOUND, ScreenScraper.Status.BAD_REQUEST -> CoverSources.Answer.Miss
            else -> CoverSources.Answer.Unavailable
        }
    }

    private fun answer(url: String?): CoverSources.Answer = url?.let { CoverSources.Answer.Found(it) } ?: CoverSources.Answer.Miss

    /** Checks the credentials in the settings and says what ScreenScraper thinks of them. */
    suspend fun testLogin(): LoginResult {
        val c = settings.credentials.first()
        if (!c.ready) return LoginResult.DeveloperMissing
        // A test is a deliberate new try: it is not held back by an earlier refusal.
        backoffUntil = 0
        if (!c.hasLogin) return infra(c)
        val r = call(c, ScreenScraper.userUrl(c), counted = false)
        return when (r.status) {
            ScreenScraper.Status.OK -> {
                val user = ScreenScraper.parseUser(r.json) ?: return LoginResult.Busy
                if (user.quotaUsedUp) return LoginResult.QuotaReached(stats.state.value.pausedUntil)
                // ScreenScraper says there is quota left: an earlier pause is over.
                stats.pauseUntil(0)
                stats.problem(null)
                LoginResult.LoggedIn(user)
            }
            ScreenScraper.Status.LOGIN_REJECTED -> when (infra(c)) {
                LoginResult.DeveloperOnly -> LoginResult.WrongLogin.also { stats.problem(CoverSourceStats.Problem.LOGIN) }
                LoginResult.Offline -> LoginResult.Offline
                LoginResult.Busy -> LoginResult.Busy
                else -> LoginResult.DeveloperRejected
            }
            else -> resultOf(r.status)
        }
    }

    private suspend fun infra(c: ScreenScraper.Credentials): LoginResult {
        val r = call(c, ScreenScraper.infraUrl(c), counted = false)
        return when (r.status) {
            ScreenScraper.Status.OK -> {
                if (!c.hasLogin) stats.problem(null)
                LoginResult.DeveloperOnly
            }
            ScreenScraper.Status.LOGIN_REJECTED -> LoginResult.DeveloperRejected
            else -> resultOf(r.status)
        }
    }

    private fun resultOf(status: ScreenScraper.Status?): LoginResult = when (status) {
        null -> LoginResult.Offline
        ScreenScraper.Status.QUOTA -> LoginResult.QuotaReached(stats.state.value.pausedUntil)
        ScreenScraper.Status.LOGIN_REJECTED -> LoginResult.DeveloperRejected
        else -> LoginResult.Busy
    }

    private class Reply(val status: ScreenScraper.Status?, val json: com.google.gson.JsonElement?)

    /**
     * One request within the account's thread limit; reacts to what the status says. A null
     * status is a network failure (offline). [counted] off for the login test: it changes no pause.
     */
    private suspend fun call(c: ScreenScraper.Credentials, url: String, counted: Boolean = true): Reply = permits.withPermit {
        withContext(Dispatchers.IO) {
            pace()
            val response = try {
                JsonHttp.request("GET", url, connectTimeoutMs = 15_000, readTimeoutMs = 30_000)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Log.w(TAG, "ScreenScraper unreachable: ${e.javaClass.simpleName}")
                if (counted) stats.problem(CoverSourceStats.Problem.OFFLINE)
                return@withContext Reply(null, null)
            }
            val status = ScreenScraper.status(response.code)
            if (status != ScreenScraper.Status.OK && status != ScreenScraper.Status.NOT_FOUND) Log.w(TAG, "ScreenScraper answered HTTP ${response.code}")
            val now = System.currentTimeMillis()
            when (status) {
                ScreenScraper.Status.OK, ScreenScraper.Status.NOT_FOUND -> {
                    if (status == ScreenScraper.Status.OK) ScreenScraper.parseUser(response.json)?.let { user(it, now) }
                    if (counted && stats.state.value.problem != CoverSourceStats.Problem.QUOTA) stats.problem(null)
                }
                ScreenScraper.Status.RATE_LIMITED -> { backoff(c, now + RATE_LIMIT_PAUSE_MS); stats.problem(CoverSourceStats.Problem.RATE_LIMITED) }
                ScreenScraper.Status.QUOTA -> { stats.pauseUntil(ScreenScraper.nextReset(now)); stats.problem(CoverSourceStats.Problem.QUOTA) }
                ScreenScraper.Status.LOGIN_REJECTED -> if (counted) { backoff(c, now + LOGIN_PAUSE_MS); stats.problem(CoverSourceStats.Problem.LOGIN) }
                ScreenScraper.Status.CLOSED, ScreenScraper.Status.SERVER_ERROR -> if (counted) { backoff(c, now + BUSY_PAUSE_MS); stats.problem(CoverSourceStats.Problem.BUSY) }
                ScreenScraper.Status.BAD_REQUEST -> Unit
            }
            Reply(status, response.json)
        }
    }

    private fun backoff(c: ScreenScraper.Credentials, until: Long) {
        backoffFor = c.fingerprint
        backoffUntil = maxOf(backoffUntil, until)
    }

    /** The account as ScreenScraper describes it: its thread limit is applied, a used-up quota pauses the source. */
    private fun user(user: ScreenScraper.User, now: Long) {
        stats.user(user, now)
        if (user.quotaUsedUp) {
            stats.pauseUntil(ScreenScraper.nextReset(now))
            stats.problem(CoverSourceStats.Problem.QUOTA)
        }
        if (user.maxThreads != threads) {
            // Requests holding the old permits finish on them; new ones use the new limit.
            threads = user.maxThreads
            permits = Semaphore(user.maxThreads)
        }
    }

    /** Ends a pause for the quota (the user asked to look again on a new day, or changed the login). */
    override fun clearPause() {
        if (stats.state.value.pausedUntil <= System.currentTimeMillis()) stats.pauseUntil(0)
    }

    private companion object {
        const val TAG = "ScreenScraper"
        const val RATE_LIMIT_PAUSE_MS = 60_000L
        const val BUSY_PAUSE_MS = 15 * 60_000L
        const val LOGIN_PAUSE_MS = 60 * 60_000L
        /** Quiet time after the last scrape before the numbers are refreshed. */
        const val BATCH_IDLE_MS = 20_000L
    }
}
