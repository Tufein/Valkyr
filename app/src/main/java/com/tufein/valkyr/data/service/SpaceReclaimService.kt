package com.tufein.valkyr.data.service

import android.util.Log
import com.tufein.valkyr.data.local.dao.CollectionDao
import com.tufein.valkyr.data.local.dao.DownloadHistoryDao
import com.tufein.valkyr.data.local.dao.FavouriteDao
import com.tufein.valkyr.data.local.dao.WishlistDao
import com.tufein.valkyr.data.local.entity.WishlistEntity
import com.tufein.valkyr.data.model.DownloadStatus
import com.tufein.valkyr.data.repository.SettingsRepository
import com.tufein.valkyr.data.repository.WishlistRepository
import com.tufein.valkyr.util.DuplicateFinder
import com.tufein.valkyr.util.EsdePlay
import com.tufein.valkyr.util.SaveKind
import com.tufein.valkyr.util.SpaceCandidate
import com.tufein.valkyr.util.SpaceFacts
import com.tufein.valkyr.util.SpaceReclaim
import com.tufein.valkyr.util.SpaceReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import com.tufein.valkyr.util.ActionReason

private const val TAG = "SpaceReclaim"

/** The RomM save listing is read again when older than this (one request per kind). */
private const val SAVES_TTL_MS = 5 * 60 * 1000L

/** What the "Free up space" tool found, with which of the protections could be looked at. */
data class SpaceScan(
    val report: SpaceReport,
    /** A download folder is set; without one there is nothing to scan. */
    val folderSet: Boolean,
    val freeBytes: Long?,
    /** RomM saves were read, so a game with one is protected; false when RomM is not set up or its game list is not loaded. */
    val savesChecked: Boolean,
    /** Favourites and collections were read; false when either failed (nothing may be removed then: protection fails closed). */
    val protectionsChecked: Boolean,
    /** RetroAchievements is set up, so a game with an earned achievement the app knows of is protected. */
    val achievementsChecked: Boolean
)

/** The outcome of a removal. [failed] holds the games that lost no file or only some of them. */
data class SpaceRemoval(
    val removed: List<SpaceCandidate>,
    val failed: List<SpaceCandidate>,
    val bytes: Long,
    /** How many games were put on the wishlist (already wished ones do not count). */
    val wished: Int,
    /** Games left alone because a favourite or collection protects them now (they count as not removed). */
    val newlyProtected: List<SpaceCandidate> = emptyList()
)

/**
 * The "Free up space" tool (7.0): reads the library folders through [LibraryScanService], adds what
 * the app knows about each game (ES-DE plays, favourites, collections, RomM saves, achievements,
 * download dates) and lets [SpaceReclaim] rank it; removes the games the user picked, through the
 * same delete the duplicate finder uses, and optionally puts them on the wishlist.
 *
 * Only games the last [scan] listed are ever removed, and those come from the library folders'
 * own walk: no path or file the user did not see in the confirmation can be touched. Everything
 * extra (RomM saves, ES-DE) is read cheaply: one listing request per kind, cached for minutes,
 * and a failure only means "not checked", never an error.
 */
@Singleton
class SpaceReclaimService @Inject constructor(
    private val scanService: LibraryScanService,
    private val esdePlays: EsdePlayService,
    private val settings: SettingsRepository,
    private val favouriteDao: FavouriteDao,
    private val collectionDao: CollectionDao,
    private val downloadHistory: DownloadHistoryDao,
    private val rommClient: RommClient,
    private val rommLibrary: RommLibraryService,
    private val retroAchievements: RetroAchievementsService,
    private val wishlist: WishlistRepository,
    private val wishlistDao: WishlistDao
) {
    /** File ids of the games the last scan offered: the only files [remove] touches. */
    @Volatile private var offered: Set<String> = emptySet()

    /** RomM ids with a save or state, and when they were read. */
    @Volatile private var savedRoms: Pair<Long, Set<Int>>? = null

    suspend fun scan(): SpaceScan = withContext(Dispatchers.IO) {
        val folderSet = settings.downloadDirectory.first().isNotBlank() || settings.consoleDownloadDirectories.first().isNotEmpty()
        val snapshot = scanService.scan()
        val entries = DuplicateFinder.entries(snapshot.files)
        val (saves, savesChecked) = rommSaveKeys()
        val (achievements, achievementsChecked) = achievementKeys()
        val favourites = favouriteKeys()
        val collections = collectionKeys()
        val facts = SpaceFacts(
            plays = plays(),
            favourites = favourites.orEmpty(),
            collections = collections.orEmpty(),
            saves = saves,
            achievements = achievements,
            downloadedAt = downloadDates()
        )
        val report = SpaceReclaim.build(entries, facts)
        offered = report.candidates.flatMapTo(HashSet()) { c -> c.entry.files.map { it.fileId } }
        SpaceScan(report, folderSet, snapshot.freeBytes, savesChecked, favourites != null && collections != null, achievementsChecked)
    }

    /**
     * Deletes [candidates] (and puts them on the wishlist when [addToWishlist]). A game counts as
     * removed only when all its files went; the owned-index is refreshed once, like the duplicate
     * finder does. Runs to the end even when the screen is left, so a game is never left half deleted.
     */
    suspend fun remove(candidates: List<SpaceCandidate>, addToWishlist: Boolean): SpaceRemoval =
        withContext(NonCancellable + Dispatchers.IO) {
            val allowed = offered
            // Protection is read again right before deleting: a favourite or collection added since the scan
            // keeps its game. When it cannot be read, nothing is removed.
            val favourites = favouriteKeys()
            val collections = collectionKeys()
            val readable = favourites != null && collections != null
            val newlyProtected = if (!readable) emptyList() else candidates.filter { c ->
                SpaceReclaim.keysOf(c.entry).any { it in favourites!! || it in collections!! }
            }
            val blocked = if (readable) newlyProtected.mapTo(HashSet()) { it.id } else candidates.mapTo(HashSet()) { it.id }
            val targets = candidates.filter { c -> c.id !in blocked && c.entry.files.isNotEmpty() && c.entry.files.all { it.fileId in allowed } }
            val targetIds = targets.mapTo(HashSet()) { it.id }
            val counts = scanService.deleteAll(targets.map { it.entry }, ActionReason.FREE_SPACE)
            val removed = ArrayList<SpaceCandidate>()
            val partial = ArrayList<SpaceCandidate>()
            val failed = ArrayList<SpaceCandidate>()
            failed += candidates.filter { it.id !in targetIds }
            targets.forEachIndexed { i, c ->
                when {
                    counts[i] >= c.fileCount -> removed += c
                    counts[i] > 0 -> partial += c
                    else -> failed += c
                }
            }
            offered = allowed - removed.flatMapTo(HashSet()) { c -> c.entry.files.map { it.fileId } }
            // A game that lost only some of its files is broken: it is still worth fetching again.
            var wished = 0
            if (addToWishlist) (removed + partial).forEach { if (wish(it)) wished++ }
            SpaceRemoval(removed, failed + partial, removed.sumOf { it.bytes }, wished, newlyProtected)
        }

    // ---- The wishlist ----------------------------------------------------------------------

    /**
     * Puts the game on the wishlist under its clean title. A wish a source already lists is marked
     * as seen right away: the game was just removed on purpose, so the next source scan must not
     * announce it or (with the automatic download on) fetch it again; it stays on the list, ready
     * to be fetched from the wishlist screen.
     */
    private suspend fun wish(candidate: SpaceCandidate): Boolean {
        val title = SpaceReclaim.wishTitle(candidate)
        val added = try {
            wishlist.add(title, candidate.consoleId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Not put on the wishlist: ${e.message}")
            false
        }
        if (!added) return false
        safe {
            val key = WishlistEntity(title = title, consoleId = candidate.consoleId).key
            val row = wishlistDao.getAll().firstOrNull { it.key == key && it.consoleId == candidate.consoleId }
            if (row != null && row.notifiedAt == null && wishlist.matches(row) > 0) wishlistDao.markNotified(row.id, System.currentTimeMillis())
        }
        return true
    }

    // ---- The facts -------------------------------------------------------------------------

    /** `consoleId|name` keys of the favourites; null when the table cannot be read. */
    private suspend fun favouriteKeys(): Set<String>? =
        safe { favouriteDao.getAll().mapTo(HashSet()) { SpaceReclaim.key(it.consoleId, it.fileName) } }

    /** Same for the games in any collection. */
    private suspend fun collectionKeys(): Set<String>? =
        safe { collectionDao.getAllItems().mapTo(HashSet()) { SpaceReclaim.key(it.consoleId, it.fileName) } }

    /** ES-DE's play records; null when ES-DE is not set up (or unreadable): no play data at all. */
    private suspend fun plays(): List<EsdePlay>? = safe { esdePlays.plays() }

    /** When each game was downloaded by the app (finished downloads only), for files without a modified time. */
    private suspend fun downloadDates(): Map<String, Long> = safe {
        downloadHistory.getAll()
            .filter { it.status == DownloadStatus.COMPLETED.name && (it.finishedAt ?: 0L) > 0L }
            .associate { SpaceReclaim.key(it.consoleId, it.fileName) to (it.finishedAt ?: 0L) }
    }.orEmpty()

    /**
     * `consoleId|name` keys of the games that have a save or state on the RomM server: one listing
     * per kind (cached for [SAVES_TTL_MS]), mapped through the server's game list. Not checked
     * (false) without RomM, without its game list, or when the listing fails.
     */
    private suspend fun rommSaveKeys(): Pair<Set<String>, Boolean> {
        val none = emptySet<String>() to false
        if (rommClient.configuredBaseUrl().isEmpty() || settings.rommToken.first().isBlank()) return none
        val games = rommLibrary.games.value
        if (games.isEmpty()) return none
        val ids = savedRomIds() ?: return none
        return games.filterValues { it.romId in ids }.keys to true
    }

    private suspend fun savedRomIds(): Set<Int>? {
        val now = System.currentTimeMillis()
        savedRoms?.takeIf { now - it.first < SAVES_TTL_MS }?.let { return it.second }
        val ids = HashSet<Int>()
        var read = false
        for (kind in SaveKind.entries) {
            try {
                rommClient.saves(kind).forEach { ids += it.romId }
                read = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "RomM ${kind.apiPath} not read: ${e.message}")
            }
        }
        if (!read) return null
        savedRoms = now to ids
        return ids
    }

    /**
     * Keys of the games the user earned an achievement in, as far as the app already knows: the
     * recent games and unlocks of the RetroAchievements profile and the games whose progress was
     * looked at in the details view, matched to library games by their hash marks. Nothing is
     * fetched here (RA asks API users to be gentle). Not checked (false) without an RA account.
     */
    private fun achievementKeys(): Pair<Set<String>, Boolean> {
        if (retroAchievements.accountReady.value != true) return emptySet<String>() to false
        val summary = retroAchievements.summary.value
        val earned = HashSet<Int>()
        summary?.recentlyPlayed?.forEach { if (it.achieved > 0) earned += it.gameId }
        summary?.recentUnlocks?.forEach { earned += it.gameId }
        val keys = HashSet<String>()
        retroAchievements.marks.value.byConsole.forEach { (console, games) ->
            games.forEach { (stem, game) ->
                if (game.id in earned || (retroAchievements.cachedProgress(game.id)?.earned ?: 0) > 0) keys += SpaceReclaim.stemKey(console, stem)
            }
        }
        return keys to true
    }

    /** Runs [block]; a failure (a damaged table, an unreadable file) is "no information", not an error. */
    private suspend fun <T> safe(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Fact not read: ${e.message}")
        null
    }
}
