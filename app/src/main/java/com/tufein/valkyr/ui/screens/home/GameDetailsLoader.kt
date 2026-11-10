package com.tufein.valkyr.ui.screens.home

import com.tufein.valkyr.data.local.dao.GameMetadataDao
import com.tufein.valkyr.data.local.entity.DownloadableFileEntity
import com.tufein.valkyr.data.model.DownloadableFileWithTags
import com.tufein.valkyr.data.model.GameDetails
import com.tufein.valkyr.data.repository.CollectionsRepository
import com.tufein.valkyr.data.repository.DownloadableFileRepository
import com.tufein.valkyr.data.service.GameMetadataService
import com.tufein.valkyr.data.service.LibraryIndexService
import com.tufein.valkyr.data.service.RetroAchievementsService
import com.tufein.valkyr.util.LibraryDiscovery
import com.tufein.valkyr.util.LibraryKeys
import com.tufein.valkyr.util.SimilarGames
import com.tufein.valkyr.util.SwitchTitles
import com.tufein.valkyr.util.VersionPicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import com.tufein.valkyr.data.local.VersionPreferenceSettings
import com.tufein.valkyr.data.service.ProfileService
import com.tufein.valkyr.data.service.VersionPreferenceService
import com.tufein.valkyr.util.VersionPreference

/**
 * 8.0: fills a [DetailsState] step by step (versions, collections, RetroAchievements, Switch
 * status, "More like this", the online lookup). Shared by the library's details card and the game
 * page so both show the same facts. Each step hands a change to [load]'s `update`, which applies it
 * to whatever state is current then (so a collection ticked meanwhile is not overwritten).
 */
@Singleton
class GameDetailsLoader @Inject constructor(
    private val repository: DownloadableFileRepository,
    private val collectionsRepository: CollectionsRepository,
    private val retroAchievements: RetroAchievementsService,
    private val metadataService: GameMetadataService,
    private val libraryIndex: LibraryIndexService,
    private val metadataDao: GameMetadataDao,
    private val versionPreference: VersionPreferenceService,
    private val versionSettings: VersionPreferenceSettings,
    private val profiles: ProfileService
) {
    /**
     * Runs every step for [item]. [index] is the cached genre / year index ("More like this");
     * null builds one from the cache (the game page has no index of its own).
     */
    suspend fun load(
        item: DownloadableFileWithTags,
        index: LibraryDiscovery.Index?,
        update: ((DetailsState) -> DetailsState) -> Unit
    ) {
        // Which version of this game suits the user best (region, language, no demos).
        val restrictions = profiles.current()
        val versions = runCatching { repository.versionsOf(item.file) }.getOrDefault(emptyList()).filter { restrictions.allows(it.file.consoleId, it.tags) }
        val bestId = if (versions.size > 1) VersionPreference.pick(
            versions.map { VersionPicker.Candidate(it.file.fileName, it.file.fileName, it.tags, it.file.fileSize) },
            versionSettings.effective(item.file.consoleId), versionPreference.preferred(item.file.consoleId, item.file.fileName)
        )?.id else null
        val best = versions.firstOrNull { it.file.fileName == bestId }
        update { it.copy(versionCount = versions.size, best = best, versions = versions, bestFileName = bestId) }
        val collections = collectionsRepository.collectionsOf(item.file)
        update { it.copy(collectionIds = collections) }
        achievementsFor(item.file)?.let { ra -> update { it.copy(achievements = ra) } }
        // A Switch game: its updates and DLC in the library, against what is on disk.
        SwitchTitles.parse(item.file.fileName)?.let { title ->
            val rows = runCatching { repository.filesOf(item.file.consoleId) }.getOrDefault(emptyList())
            val status = SwitchTitles.analyse(rows, { it.fileName }, ownedNamesFor(item.file.consoleId), onlyOwned = false)
                .firstOrNull { it.baseId == title.baseId }
            update { it.copy(switchTitle = title, switch = status) }
        }
        val discover = index ?: runCatching {
            withContext(Dispatchers.Default) {
                metadataDao.observeKnown()
                    .map { rows -> LibraryDiscovery.buildIndex(rows.map { LibraryDiscovery.RawRow(it.lookupKey, it.genres, it.released, it.developer) }) }
                    .first()
            }
        }.getOrDefault(LibraryDiscovery.Index.EMPTY)
        // "More like this" from what is cached: shown before the (slower) online lookup returns.
        val similar = similarTo(item, null, discover)
        if (similar.isNotEmpty()) update { it.copy(similar = similar) }
        val found = metadataService.lookup(item.file.name, item.file.consoleId, item.file.fileName)
        update { it.copy(loading = false, details = found) }
        // The lookup may have brought genres / developer the first pass did not have.
        if (found != null && (found.genres.isNotEmpty() || found.developer.isNotBlank())) {
            val better = similarTo(item, found, discover)
            if (better.isNotEmpty()) update { it.copy(similar = better) }
        }
    }

    /** RA game of a row: (game, true) by hash, (game, false) by title only. */
    suspend fun achievementsFor(file: DownloadableFileEntity) =
        runCatching { retroAchievements.resolveGame(file.consoleId, file.fileName, file.name) }.getOrNull()

    /** File names on disk for [consoleId] (from the library index keys of its folders). */
    private fun ownedNamesFor(consoleId: String): List<String> {
        val scopes = LibraryKeys.scopesFor(consoleId)
        return libraryIndex.ownedKeys.value.mapNotNull { key ->
            val bar = key.indexOf('|')
            if (bar < 0 || key.substring(0, bar) !in scopes) null else key.substring(bar + 1)
        }
    }

    /** "More like this": local data only, off the main thread. */
    private suspend fun similarTo(
        item: DownloadableFileWithTags,
        extra: GameDetails?,
        index: LibraryDiscovery.Index
    ): List<DownloadableFileWithTags> = runCatching {
        withContext(Dispatchers.Default) {
            val file = item.file
            val own = index.metaFor(file.consoleId, file.name)
            val genres = own?.genres ?: LibraryDiscovery.parseGenres(extra?.genres?.joinToString("|").orEmpty())
            val developer = own?.developer?.takeIf { it.isNotBlank() } ?: extra?.developer.orEmpty()
            val target = SimilarGames.Candidate(file.id, file.consoleId, file.name, genres, developer)
            val family = SimilarGames.familyQuery(file.name)
            val pool = HashMap<Long, DownloadableFileWithTags>()
            if (family.isNotBlank()) {
                repository.searchFilesWithTags(query = family, limit = 80, offset = 0).forEach { pool[it.file.id] = it }
            }
            // Same console and a shared genre: cheap thanks to the cached index (no network).
            if (genres.isNotEmpty()) {
                val sameGenre = repository.fileRefsOf(file.consoleId).asSequence()
                    .filter { f -> index.metaFor(f.consoleId, f.name)?.genres?.any { it in genres } == true }
                    .take(400)
                    .map { it.id }
                    .filter { it !in pool }
                    .toList()
                repository.filesByIds(sameGenre).forEach { pool.putIfAbsent(it.id, DownloadableFileWithTags(it, emptyList())) }
            }
            val candidates = pool.values.map {
                val meta = index.metaFor(it.file.consoleId, it.file.name)
                SimilarGames.Candidate(it.file.id, it.file.consoleId, it.file.name, meta?.genres.orEmpty(), meta?.developer.orEmpty())
            }
            val restrictions = profiles.current()
            SimilarGames.rank(target, candidates, SIMILAR_LIMIT).mapNotNull { pool[it.id] }.filter { restrictions.allows(it.file.consoleId, it.tags) }
        }
    }.getOrDefault(emptyList())

    private companion object {
        const val SIMILAR_LIMIT = 6
    }
}
