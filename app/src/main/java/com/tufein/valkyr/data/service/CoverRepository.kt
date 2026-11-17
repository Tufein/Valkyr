package com.tufein.valkyr.data.service

import android.util.LruCache
import com.tufein.valkyr.data.local.CoverSourceSettings
import com.tufein.valkyr.data.local.VersionPreferenceSettings
import com.tufein.valkyr.data.local.dao.CoverDao
import com.tufein.valkyr.data.local.dao.GameMetadataDao
import com.tufein.valkyr.data.local.entity.CoverEntity
import com.tufein.valkyr.util.BoundedMap
import com.tufein.valkyr.util.CoverPlanner
import com.tufein.valkyr.util.CoverSources
import com.tufein.valkyr.util.CoverSources.Answer
import com.tufein.valkyr.util.CoverSources.Source
import com.tufein.valkyr.util.GameTitleCleaner
import com.tufein.valkyr.util.RommMarks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Finds the cover of a library game for the 5.0 screens. 4.3.0: a chain of sources the user orders
 * and switches in Settings → Cover sources ([CoverSources]): the RomM server's own cover when the
 * game is on the server, then libretro-thumbnails (box art, other region names, optionally title
 * screens and snaps), ScreenScraper (with the user's credentials), 4.4.0: TheGamesDB, SteamGridDB
 * and IGDB (each with a key of the user's own) and the image a metadata lookup already cached, in
 * the user's order. Results are kept in Room and in memory, so a game costs at
 * most one lookup per source and scrolling back is instant; a source that could not answer
 * (offline, rate limited, paused for the day) is never remembered as a miss.
 */
@Singleton
class CoverRepository @Inject constructor(
    private val dao: CoverDao,
    private val metadataDao: GameMetadataDao,
    private val thumbnails: ThumbnailService,
    private val rommLibrary: RommLibraryService,
    private val rommClient: RommClient,
    private val sourceSettings: CoverSourceSettings,
    private val screenScraper: ScreenScraperService,
    theGamesDb: TheGamesDbService,
    steamGridDb: SteamGridDbService,
    igdb: IgdbService,
    private val stats: CoverSourceStats,
    private val versionSettings: VersionPreferenceSettings,
    private val overrides: GameOverrideService
) {
    /** The sources that need a key, by source: asked only while set up and not resting. */
    private val keyed: Map<Source, CoverLookupSource> =
        listOf<CoverLookupSource>(screenScraper, theGamesDb, steamGridDb, igdb).associateBy { it.source }

    private val memory = LruCache<String, String>(4_000)
    /** At most a few lookups at once: a fast scroll should not open dozens of connections. */
    private val lookups = Semaphore(4)
    /** libretro-thumbnails on its own limit (ScreenScraper keeps the account's thread limit itself). */
    private val libretroPermits = Semaphore(3)
    /** Games whose lookup met a source that could not answer: not asked again before this moment. */
    private val retryAfter = BoundedMap<String, Long>()

    init {
        // A changed order, switch or login can find covers that were missing: look again.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            combine(sourceSettings.chain, sourceSettings.credentials, sourceSettings.apiKeys) { chain, login, keys -> Triple(chain, login.fingerprint, keys.fingerprint) }
                .distinctUntilChanged()
                .drop(1)
                .collect { memory.evictAll(); retryAfter.clear() }
        }
    }

    /** The cover already known for a game (memory only, no waiting): lets a row draw it at once. */
    fun cached(consoleId: String, fileName: String): String? =
        memory.get(RommMarks.key(consoleId, fileName))?.ifEmpty { null }

    /** The best cover URL for a game, or null when none is known. */
    suspend fun coverUrl(consoleId: String, fileName: String, name: String = fileName): String? {
        val key = RommMarks.key(consoleId, fileName)
        // 9.0: a cover the user pinned is above every provider and survives a rescan. With no
        // corrections at all this is one in-memory check, so a large grid still scrolls fast.
        if (overrides.hasAny()) overrides.pinnedCover(consoleId, fileName)?.takeIf { it.isNotBlank() }?.let { return it }
        memory.get(key)?.let { return it.ifEmpty { null } }
        val chain = sourceSettings.chain.first()
        if (chain.enabled(Source.ROMM)) {
            rommLibrary.gameFor(consoleId, fileName)?.coverPath?.takeIf { it.isNotBlank() }?.let { path ->
                val base = rommClient.configuredBaseUrl()
                if (base.isNotEmpty()) return CoverPlanner.coverUrl(base, path).also { memory.put(key, it) }
            }
        }
        val now = System.currentTimeMillis()
        retryAfter[key]?.let { if (now < it) return null else retryAfter.remove(key) }
        val usable = usableSources(now)
        // No switched-on source is waiting (paused, backing off): an answer may stay in memory for the
        // whole run. A source without its credentials / key is not waiting, it is just not set up (a
        // change of them empties the memory anyway).
        val configured = keyed.filterValues { runCatching { it.configured() }.getOrDefault(false) }.keys
        val complete = keyed.keys.all { it in usable || !chain.enabled(it) || it !in configured }
        val row = dao.get(key)?.let { CoverSources.Cached(it.url, it.source, it.fetchedAt) }
        val ask = when (val plan = CoverSources.plan(chain, usable, row, now, unconfigured = keyed.keys - configured)) {
            is CoverSources.Plan.Use -> { memory.put(key, plan.url); return plan.url }
            CoverSources.Plan.KnownMiss -> { remember(key, null, complete, now); return null }
            is CoverSources.Plan.Ask -> plan
        }
        val answers = lookups.withPermit {
            // Another row asked for the same game while this one waited.
            memory.get(key)?.let { return it.ifEmpty { null } }
            val regions = runCatching { versionSettings.effectiveFlow(consoleId).first().regions }.getOrDefault(emptyList())
            val out = ArrayList<Pair<Source, Answer>>()
            for (source in ask.sources) {
                val answer = askOne(source, consoleId, fileName, name, regions, chain.nonBoxArt)
                stats.count(source, answer)
                if (source == Source.SCREENSCRAPER) screenScraper.noteActivity()
                out += source to answer
                if (answer is Answer.Found) break
            }
            out
        }
        val settled = CoverSources.settle(answers, ask, now)
        settled.record?.let { dao.upsert(CoverEntity(key, settled.url.orEmpty(), it, settled.fetchedAt)) }
        remember(key, settled.url, complete && settled.remember, now)
        return settled.url
    }

    private fun remember(key: String, url: String?, forTheRun: Boolean, now: Long) {
        when {
            url != null -> memory.put(key, url)
            forTheRun -> memory.put(key, "")
            else -> retryAfter[key] = now + RETRY_AFTER_MS
        }
    }

    private suspend fun usableSources(now: Long): Set<Source> = buildSet {
        add(Source.LIBRETRO)
        add(Source.METADATA)
        keyed.forEach { (source, service) -> if (runCatching { service.usable(now) }.getOrDefault(false)) add(source) }
    }

    private suspend fun askOne(source: Source, consoleId: String, fileName: String, name: String, regions: List<String>, nonBoxArt: Boolean): Answer = try {
        when (source) {
            Source.ROMM -> Answer.Miss
            Source.LIBRETRO -> libretroPermits.withPermit { thumbnails.find(consoleId, fileName, regions, nonBoxArt) }
            Source.SCREENSCRAPER, Source.THEGAMESDB, Source.STEAMGRIDDB, Source.IGDB ->
                keyed.getValue(source).find(consoleId, fileName, name, regions, nonBoxArt)
            Source.METADATA -> metadataImage(name, consoleId)?.let { Answer.Found(it) } ?: Answer.Miss
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Answer.Unavailable
    }

    /**
     * The address to download a cover from outside the image loader (writing covers for the
     * launchers): a ScreenScraper cover gets its credentials back, everything else is unchanged.
     */
    suspend fun downloadUrl(url: String): String = runCatching { screenScraper.signed(url) }.getOrDefault(url)

    /** Forget the games without a cover, so they are looked up again. */
    suspend fun retryMisses(): Int {
        memory.evictAll()
        retryAfter.clear()
        thumbnails.clearChainCache()
        keyed.values.forEach { it.clearPause() }
        return dao.clearMisses()
    }

    /** The image a metadata lookup (RAWG / TheGamesDB) already stored for this title, if any. */
    private suspend fun metadataImage(name: String, consoleId: String): String? {
        val title = GameTitleCleaner.clean(name)
        if (title.isBlank()) return null
        val platform = consoleId.substringAfter("_", consoleId).lowercase()
        return metadataDao.get("$platform|${title.lowercase()}")?.imageUrl?.takeIf { it.isNotBlank() }
    }

    private companion object {
        /** How long a game whose lookup met an unavailable source waits before it is asked again. */
        const val RETRY_AFTER_MS = 5 * 60_000L
    }
}
