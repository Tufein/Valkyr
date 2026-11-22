package com.tufein.valkyr.data.service

import android.content.Context
import android.util.Log
import com.tufein.valkyr.data.local.ManualSettings
import com.tufein.valkyr.data.local.VersionPreferenceSettings
import com.tufein.valkyr.util.CoverSources
import com.tufein.valkyr.util.ManualHit
import com.tufein.valkyr.util.ManualLookup
import com.tufein.valkyr.util.ManualSource
import com.tufein.valkyr.util.ManualSources
import com.tufein.valkyr.util.Manuals
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "Manuals"

/** How a manual download ended. */
enum class ManualResult { SAVED, EXISTS, NO_MANUAL, TOO_BIG, OFFLINE, FAILED, BAD_LINK }

/** The Internet Archive answer: a hit, a definite "nothing there", or a source we could not reach. */
private sealed interface ArchiveLookup {
    data class Found(val hit: ManualHit) : ArchiveLookup
    data object Miss : ArchiveLookup
    data object Unavailable : ArchiveLookup
}

/** One source's answer, keeping "nothing there" apart from "could not ask". */
private sealed interface Lookup {
    data class Hits(val hits: List<ManualHit>) : Lookup
    data object Miss : Lookup
    data object Unavailable : Lookup
}

/**
 * Game manuals in the app's own storage (`files/manuals/<console>/<game>/`, 6.0). The sources are
 * asked in the user's chosen order (7.7): their RomM server, ScreenScraper's `manual` media and the
 * public Internet Archive APIs, plus a link the user pastes explicitly. A definite "no manual" from
 * a source is remembered for a month; a source that was only unavailable is simply tried again.
 *
 * Writes go through a temporary file, an existing file is never replaced, and the total stays under
 * the size cap (oldest manuals of other games go first). ScreenScraper credentials are added only to
 * the outgoing request, never to a stored URL or a log.
 */
@Singleton
class ManualService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: ManualSettings,
    private val rommClient: RommClient,
    private val rommGames: RommGameService,
    private val screenScraper: ScreenScraperService,
    private val versionSettings: VersionPreferenceSettings,
    downloads: DownloadService
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    /** One manual download at a time: a run of finished downloads must not fetch many files at once. */
    private val slot = Semaphore(1)
    private val root get() = File(context.filesDir, Manuals.ROOT)

    private val _changes = MutableStateFlow(0)
    /** Moves whenever a manual is added or removed (screens list again). */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    init {
        // With "Download manual with the game" on, a finished download fetches its manual too.
        scope.launch {
            downloads.finished.collect { name ->
                if (!settings.withGame.first()) return@collect
                val file = downloads.entityFor(name) ?: return@collect
                launch {
                    val r = try {
                        downloadFound(file.consoleId, file.fileName, file.name)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // A manual is a nice extra: never let its lookup take the app down.
                        Log.w(TAG, "Manual with the game failed: ${e.javaClass.simpleName}")
                        ManualResult.FAILED
                    }
                    Log.i(TAG, "Manual with the game: $r")
                }
            }
        }
    }

    private fun dirOf(consoleId: String, fileName: String) = File(context.filesDir, Manuals.relativeDir(consoleId, fileName))

    /** The readable manual files of a game, by name. */
    suspend fun files(consoleId: String, fileName: String): List<String> = withContext(Dispatchers.IO) {
        dirOf(consoleId, fileName).listFiles()?.filter { it.isFile && Manuals.kindOf(it.name) != Manuals.Kind.OTHER }?.map { it.name }.orEmpty()
    }

    suspend fun docs(consoleId: String, fileName: String): List<Manuals.Doc> = Manuals.docsIn(files(consoleId, fileName))

    fun fileOf(consoleId: String, fileName: String, name: String): File = File(dirOf(consoleId, fileName), Manuals.safeSegment(name, "manual"))

    /** Where each stored manual came from, by file name. */
    suspend fun attribution(consoleId: String, fileName: String): Map<String, ManualSource> {
        val key = Manuals.relativeDir(consoleId, fileName)
        return settings.sourcesOf(files(consoleId, fileName).map { "$key/$it" })
            .mapKeys { it.key.substringAfterLast('/') }
    }

    /** The manual RomM has for the game; null when RomM is off, the game is not on it, or it has no manual. */
    private suspend fun rommManual(consoleId: String, fileName: String): ManualHit? = withContext(Dispatchers.IO) {
        runCatching {
            val id = rommGames.romIdFor(consoleId, fileName) ?: return@runCatching null
            val url = rommGames.details(id).manualUrl ?: return@runCatching null
            ManualHit(ManualSource.ROMM, url, Manuals.fileNameFor(url))
        }.getOrNull()
    }

    /** Remember a definite miss without letting a settings write failure break the whole lookup. */
    private suspend fun rememberMiss(gameKey: String, source: ManualSource) {
        try {
            settings.rememberMiss(gameKey, source, System.currentTimeMillis())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not remember the ${source.key} miss: ${e.javaClass.simpleName}")
        }
    }

    /** All manuals the enabled sources offer, best source first. Never throws. */
    suspend fun find(
        consoleId: String,
        fileName: String,
        title: String,
        firstOnly: Boolean = false
    ): ManualLookup = withContext(Dispatchers.IO) {
        val gameKey = Manuals.relativeDir(consoleId, fileName)
        val enabled = settings.sources.first()
        val misses = settings.misses()
        val order = ManualSources.askOrder(enabled, misses, gameKey, System.currentTimeMillis())
        val found = ArrayList<ManualHit>()
        val asked = ArrayList<ManualSource>()
        val unavailable = LinkedHashSet<ManualSource>()
        for (source in order) {
            asked += source
            val answer = when (source) {
                ManualSource.ROMM -> rommManual(consoleId, fileName).let {
                    if (it == null) Lookup.Miss else Lookup.Hits(listOf(it))
                }
                ManualSource.SCREENSCRAPER -> screenScraperManual(consoleId, fileName, title, gameKey)
                ManualSource.ARCHIVE -> when (val archive = archiveManual(title)) {
                    is ArchiveLookup.Found -> Lookup.Hits(listOf(archive.hit))
                    ArchiveLookup.Miss -> {
                        // Only a successful search with no usable manual is a definite miss.
                        rememberMiss(gameKey, ManualSource.ARCHIVE)
                        Lookup.Miss
                    }
                    ArchiveLookup.Unavailable -> Lookup.Unavailable
                }
                ManualSource.LINK -> Lookup.Miss // a pasted link is an explicit action, never searched for
            }
            when (answer) {
                is Lookup.Hits -> found += answer.hits
                Lookup.Miss -> Unit
                Lookup.Unavailable -> unavailable += source
            }
            if (firstOnly && found.isNotEmpty()) break
        }
        ManualLookup(ManualSources.dedupe(found), asked, unavailable)
    }

    private suspend fun screenScraperManual(
        consoleId: String, fileName: String, title: String, gameKey: String
    ): Lookup {
        val regions = runCatching { versionSettings.effectiveFlow(consoleId).first().regions.orEmpty() }.getOrDefault(emptyList())
        return when (val answer = screenScraper.findManual(consoleId, fileName, title, regions)) {
            is CoverSources.Answer.Found -> Lookup.Hits(listOf(ManualHit(
                ManualSource.SCREENSCRAPER, answer.url,
                Manuals.fileNameFor(answer.url, "pdf")
            )))
            CoverSources.Answer.Miss -> {
                rememberMiss(gameKey, ManualSource.SCREENSCRAPER)
                Lookup.Miss
            }
            else -> Lookup.Unavailable // rate limit, quota, login or offline: not a miss, try again later
        }
    }

    /** Up to two search results are checked for a downloadable PDF. */
    private suspend fun archiveManual(title: String): ArchiveLookup {
        return try {
            val body = JsonHttp.request("GET", ManualSources.archiveSearchUrl(title), readTimeoutMs = 30_000)
            if (!body.ok) return ArchiveLookup.Unavailable
            val items = ManualSources.parseArchiveSearch(body.body).take(2)
            if (items.isEmpty()) return ArchiveLookup.Miss
            var metadataAnswered = false
            for (item in items) {
                val meta = try {
                    JsonHttp.request("GET", ManualSources.archiveMetadataUrl(item.id), readTimeoutMs = 30_000)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    continue
                }
                if (!meta.ok) continue
                metadataAnswered = true
                val name = ManualSources.parseArchiveManual(meta.body, item.title.ifBlank { title }) ?: continue
                return ArchiveLookup.Found(ManualHit(
                    ManualSource.ARCHIVE, ManualSources.archiveDownloadUrl(item.id, name), name,
                    item.title.ifBlank { item.id }, "https://archive.org/details/${item.id}"
                ))
            }
            // A metadata call that failed is not a "no manual": only an answered search is.
            if (metadataAnswered) ArchiveLookup.Miss else ArchiveLookup.Unavailable
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ArchiveLookup.Unavailable
        }
    }

    /** Ask the sources and save the first one that has a manual (used when a game finishes downloading). */
    suspend fun downloadFound(consoleId: String, fileName: String, title: String): ManualResult {
        // Stop at the first hit: an automatic download should not wait for every other source.
        val hit = find(consoleId, fileName, title, firstOnly = true).hits.firstOrNull() ?: return ManualResult.NO_MANUAL
        return download(consoleId, fileName, title, hit)
    }

    /** A manual the user picked from the source list, or pasted as a link. */
    suspend fun download(consoleId: String, fileName: String, title: String, hit: ManualHit): ManualResult = withContext(Dispatchers.IO) {
        if (hit.source != ManualSource.ROMM && !ManualSources.validLink(hit.url) && hit.source != ManualSource.SCREENSCRAPER) {
            return@withContext ManualResult.BAD_LINK
        }
        val name = ManualSources.fileName(hit, title)
        val target = fileOf(consoleId, fileName, name)
        if (target.exists()) return@withContext ManualResult.EXISTS
        val cap = Manuals.capBytes(settings.capMb.first())
        slot.withPermit {
            val tmp = File(target.parentFile, target.name + ".part")
            try {
                target.parentFile?.mkdirs()
                val url = if (hit.source == ManualSource.SCREENSCRAPER) screenScraper.signed(hit.url) else hit.url
                val max = Manuals.maxFileBytes(cap)
                if (hit.source == ManualSource.ROMM) {
                    rommClient.downloadServerFileTo(url, tmp, max)
                } else {
                    JsonHttp.downloadToFile(url, emptyMap(), tmp, max, readTimeoutMs = 120_000) { candidate ->
                        ManualSources.acceptsRedirect(hit.source, url, candidate)
                    }
                }
                lock.withLock {
                    if (target.exists()) { tmp.delete(); return@withLock ManualResult.EXISTS }
                    val existing = entries()
                    when (val fit = Manuals.fit(existing, tmp.length(), cap)) {
                        Manuals.Fit.TooBig -> { tmp.delete(); ManualResult.TOO_BIG }
                        is Manuals.Fit.Ok -> {
                            if (!tmp.renameTo(target)) { tmp.delete(); ManualResult.FAILED } else {
                                // Older manuals go only after the new one is in place.
                                fit.remove.forEach { File(root, it.path).delete() }
                                settings.setSourceOf(Manuals.sourceKey(consoleId, fileName, name), hit.source)
                                settings.forgetMisses(Manuals.relativeDir(consoleId, fileName))
                                prune()
                                _changes.update { it + 1 }
                                ManualResult.SAVED
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                tmp.delete()
                throw e
            } catch (e: Exception) {
                tmp.delete()
                Log.w(TAG, "Manual download failed: ${e.javaClass.simpleName}")
                when {
                    e is IOException -> ManualResult.OFFLINE
                    rommErrorKind(e) == RommErrorKind.UNREACHABLE -> ManualResult.OFFLINE
                    else -> ManualResult.FAILED
                }
            }
        }
    }

    /** Downloads a URL the user pasted; nothing is searched and the link is never stored. */
    suspend fun downloadLink(consoleId: String, fileName: String, title: String, url: String): ManualResult {
        if (!ManualSources.validLink(url)) return ManualResult.BAD_LINK
        return download(consoleId, fileName, title, ManualHit(ManualSource.LINK, url.trim(), Manuals.fileNameFor(url.trim())))
    }

    /** Removes every manual of the game. */
    suspend fun delete(consoleId: String, fileName: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            val key = Manuals.relativeDir(consoleId, fileName)
            files(consoleId, fileName).forEach { settings.removeSourceOf("$key/$it") }
            dirOf(consoleId, fileName).deleteRecursively()
            prune()
        }
        _changes.update { it + 1 }
    }

    private fun entries(): List<Manuals.Entry> =
        root.walkTopDown().filter { it.isFile && !it.name.endsWith(".part") }.map { Manuals.Entry(it.relativeTo(root).path, it.length(), it.lastModified()) }.toList()

    /** Removes empty game and console folders. */
    private fun prune() {
        root.listFiles()?.forEach { console ->
            console.listFiles()?.forEach { game -> if (game.isDirectory && game.list().isNullOrEmpty()) game.delete() }
            if (console.isDirectory && console.list().isNullOrEmpty()) console.delete()
        }
    }

    /** Space all manuals take, for "Free up space". */
    suspend fun usedBytes(): Long = withContext(Dispatchers.IO) { entries().sumOf { it.size } }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        lock.withLock { root.deleteRecursively() }
        _changes.update { it + 1 }
    }
}
