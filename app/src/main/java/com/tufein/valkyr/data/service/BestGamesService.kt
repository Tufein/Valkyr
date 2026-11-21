package com.tufein.valkyr.data.service

import android.content.Context
import com.tufein.valkyr.data.local.AppSettings
import com.tufein.valkyr.util.BestGame
import com.tufein.valkyr.util.BestGames
import com.tufein.valkyr.util.GameDetail
import com.tufein.valkyr.util.PlayerCount
import com.tufein.valkyr.util.PlayerStats
import com.tufein.valkyr.util.RaApi
import com.tufein.valkyr.util.RaErrorKind
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** A console's RA game list as the service has it: [stale] when it is over a week old and could not be renewed. */
data class BestGameList(val games: List<BestGame>, val fetchedAt: Long, val stale: Boolean)

/**
 * The data behind "Best games per console" (7.0), from RetroAchievements' web API with the user's own
 * name and key. Gentle by design:
 *  - one call per console for the list of games with achievement sets, kept for a week in
 *    `files/bestgames/list_<console>.json` (only the biggest sets, a few KB);
 *  - player counts need one call per game, so only the top candidates by achievement points are asked
 *    ([BestGames.CANDIDATES]), one at a time, [THROTTLE_MS] apart, with progress; each count is kept for
 *    a week in `files/bestgames/players_<console>.json` and saved as it arrives, so a cancelled or
 *    failed run is continued, not repeated;
 *  - a rate limit, a refused key or being offline stops the run at once instead of retrying.
 * Failures are [RetroAchievementsService.RaApiException]s (a kind and a status, never the URL, which
 * holds the key) and [NoCredentialsException].
 */
@Singleton
class BestGamesService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val appSettings: AppSettings
) {
    private val dir = File(context.filesDir, "bestgames")

    /** Only one RA call runs at a time, whatever screen asked. */
    private val netMutex = Mutex()

    class NoCredentialsException : Exception()

    /** True while an RA name and web API key are set. */
    val accountReady: Flow<Boolean> =
        combine(appSettings.raUser, appSettings.raKey) { user, key -> user.isNotBlank() && key.isNotBlank() }.distinctUntilChanged()

    private fun listFile(raConsoleId: Int) = File(dir, "list_$raConsoleId.json")
    private fun playersFile(raConsoleId: Int) = File(dir, "players_$raConsoleId.json")

    /** The cached list without any network (any age), or null when there is none. */
    suspend fun cachedGames(raConsoleId: Int): BestGameList? = withContext(Dispatchers.IO) {
        val file = listFile(raConsoleId)
        if (!file.exists()) return@withContext null
        val games = runCatching { BestGames.decodeGames(file.readText()) }.getOrDefault(emptyList())
        if (games.isEmpty()) null else BestGameList(games, file.lastModified(), stale = !BestGames.isFresh(file.lastModified(), System.currentTimeMillis()))
    }

    /**
     * The games of one RA console: from the cache when it is under a week old (and [refresh] is off),
     * else fetched. A failed refresh falls back to an older cache (marked stale); with no cache it throws
     * [NoCredentialsException] or [RetroAchievementsService.RaApiException].
     */
    suspend fun games(raConsoleId: Int, refresh: Boolean = false): BestGameList = withContext(Dispatchers.IO) {
        val cached = cachedGames(raConsoleId)
        if (!refresh && cached != null && !cached.stale) return@withContext cached
        netMutex.withLock {
            // Another caller may have fetched it while this one waited.
            if (!refresh) cachedGames(raConsoleId)?.takeIf { !it.stale }?.let { return@withLock it }
            try {
                val (user, key) = credentials()
                val body = fetch(BestGames.gameListUrl(raConsoleId, user, key), readTimeoutMs = 120_000)
                val games = BestGames.parseGameList(body)
                // A list that is really empty is fine ("[]"); an error object or an HTML page is not.
                if (games.isEmpty() && !body.trim().startsWith("[")) throw RetroAchievementsService.RaApiException(RaApi.unreadableKind(body))
                // What is kept (the biggest official sets) is also what is returned, so a fresh and a cached list read alike.
                val kept = BestGames.encodeGames(games)
                dir.mkdirs()
                listFile(raConsoleId).writeText(kept)
                BestGameList(BestGames.decodeGames(kept), System.currentTimeMillis(), stale = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The old list is better than none when the network or the server is the problem.
                if (cached != null && e !is NoCredentialsException && !(e is RetroAchievementsService.RaApiException && e.kind == RaErrorKind.BAD_KEY)) cached.copy(stale = true) else throw e
            }
        }
    }

    /** The player counts still good (under a week old) of one console's games, from the cache only. */
    suspend fun cachedPlayers(raConsoleId: Int): Map<Int, Int> = withContext(Dispatchers.IO) {
        BestGames.freshPlayers(loadPlayers(raConsoleId), System.currentTimeMillis())
    }

    /** The fresh player, hardcore and detail facts of one console, from the cache only. */
    suspend fun cachedStats(raConsoleId: Int): PlayerStats = withContext(Dispatchers.IO) {
        BestGames.freshStats(loadPlayers(raConsoleId), System.currentTimeMillis())
    }

    /**
     * Asks RA for the player count of every game in [candidates] that has no fresh count, one at a time,
     * [THROTTLE_MS] apart, calling [onProgress] with (games with a count, games in all) after each. Counts
     * are saved as they arrive. Returns all the fresh counts of the console. Throws
     * [RetroAchievementsService.RaApiException] when RA refuses (key, rate limit) or cannot be reached
     * (what was fetched so far stays saved), and [CancellationException] when the caller goes away.
     */
    suspend fun fetchPlayers(raConsoleId: Int, candidates: List<BestGame>, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): Map<Int, Int> =
        fetchStats(raConsoleId, candidates, onProgress).players

    /**
     * As above, but also keeps the hardcore counts, release date and genre from the very same answer.
     * One call per game serves all four facts; nothing extra is asked of RetroAchievements.
     */
    suspend fun fetchStats(raConsoleId: Int, candidates: List<BestGame>, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): PlayerStats =
        withContext(Dispatchers.IO) {
            netMutex.withLock {
                val (user, key) = credentials()
                val counts = LinkedHashMap(loadPlayers(raConsoleId))
                val fresh = { BestGames.freshPlayers(counts, System.currentTimeMillis()) }
                val todo = BestGames.pending(candidates, fresh())
                var done = candidates.size - todo.size
                onProgress(done, candidates.size)
                var lastStart = 0L
                var sinceSave = 0
                var failures = 0
                try {
                    for (game in todo) {
                        ensureActive()
                        val wait = lastStart + THROTTLE_MS - System.currentTimeMillis()
                        if (lastStart != 0L && wait > 0) delay(wait)
                        ensureActive()
                        lastStart = System.currentTimeMillis()
                        val detail = try {
                            val body = fetch(BestGames.gameExtendedUrl(game.id, user, key), readTimeoutMs = 30_000)
                            BestGames.parseDetails(body) ?: throw RetroAchievementsService.RaApiException(RaApi.unreadableKind(body))
                        } catch (e: RetroAchievementsService.RaApiException) {
                            when (e.kind) {
                                // RA does not know this game: nothing to wait for, rank it last.
                                RaErrorKind.NOT_FOUND -> GameDetail(0, 0, null, null)
                                // Odd answers for a game or two are skipped; a run of them is a broken server.
                                RaErrorKind.BAD_RESPONSE -> if (++failures >= MAX_ODD_ANSWERS) throw e else continue
                                else -> throw e
                            }
                        }
                        failures = 0
                        counts[game.id] = PlayerCount(detail.players, System.currentTimeMillis(), detail.hardcore, detail.released, detail.genre)
                        done++
                        onProgress(done, candidates.size)
                        if (++sinceSave >= SAVE_EVERY) { savePlayers(raConsoleId, counts); sinceSave = 0 }
                    }
                } finally {
                    // Cancelled or failed: keep what was fetched, the next run continues from there.
                    withContext(NonCancellable) { savePlayers(raConsoleId, counts) }
                }
                BestGames.freshStats(counts, System.currentTimeMillis())
            }
        }

    private suspend fun credentials(): Pair<String, String> {
        val user = appSettings.raUser.first().trim()
        val key = appSettings.raKey.first().trim()
        if (user.isEmpty() || key.isEmpty()) throw NoCredentialsException()
        return user to key
    }

    private fun loadPlayers(raConsoleId: Int): Map<Int, PlayerCount> {
        val file = playersFile(raConsoleId)
        return if (file.exists()) runCatching { BestGames.decodePlayers(file.readText()) }.getOrDefault(emptyMap()) else emptyMap()
    }

    private fun savePlayers(raConsoleId: Int, counts: Map<Int, PlayerCount>) {
        runCatching {
            dir.mkdirs()
            playersFile(raConsoleId).writeText(BestGames.encodePlayers(counts))
        }
    }

    /** One GET to the RA API. Failures become exceptions that say nothing about the URL. */
    private fun fetch(url: String, readTimeoutMs: Int): String {
        val response = try {
            JsonHttp.request("GET", url, connectTimeoutMs = 15_000, readTimeoutMs = readTimeoutMs)
        } catch (e: IOException) {
            throw RetroAchievementsService.RaApiException(RaErrorKind.OFFLINE)
        } catch (e: RuntimeException) {
            throw RetroAchievementsService.RaApiException(RaErrorKind.BAD_RESPONSE)
        }
        if (!response.ok) throw RetroAchievementsService.RaApiException(RaApi.errorKindFor(response.code, response.body), response.code)
        return response.body
    }

    private companion object {
        /** Gap between two player-count calls: a minute for the whole top 40, far under what RA tolerates. */
        const val THROTTLE_MS = 1_500L
        const val SAVE_EVERY = 5
        const val MAX_ODD_ANSWERS = 3
    }
}
