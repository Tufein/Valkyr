package com.tufein.valkyr.data.service

import android.content.Context
import android.util.Log
import com.tufein.valkyr.data.local.SourcePickSettings
import com.tufein.valkyr.data.local.dao.ConsoleDao
import com.tufein.valkyr.data.local.dao.DownloadableFileDao
import com.tufein.valkyr.data.local.entity.DownloadableFileEntity
import com.tufein.valkyr.data.repository.SettingsRepository
import com.tufein.valkyr.util.RommSource
import com.tufein.valkyr.util.FailureClass
import com.tufein.valkyr.util.SourceRanking
import com.tufein.valkyr.util.DownloadSourcePolicy
import com.tufein.valkyr.util.SourceRecord
import com.tufein.valkyr.util.SourcesJson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SourceTrack"

/**
 * 7.5: the track record of every source (speed, how many downloads worked or failed and why), kept
 * in `files/source_track.json`, and the choice of the best copy when several sources list a game
 * (see [SourceRanking]). The download pipeline reports each finished, failed or retried download here.
 */
@Singleton
class SourceTrackService @Inject constructor(
    @param:ApplicationContext context: Context,
    private val fileDao: DownloadableFileDao,
    private val consoleDao: ConsoleDao,
    private val settings: SourcePickSettings,
    private val history: OperationHistoryService,
    private val profiles: ProfileService,
    private val settingsRepository: SettingsRepository
) {
    private val file = File(context.filesDir, "source_track.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _records = MutableStateFlow<Map<String, SourceRecord>>(emptyMap())
    /** Track record per source, by its address as configured in Sources. */
    val records: StateFlow<Map<String, SourceRecord>> = _records.asStateFlow()

    /** Whether "Pick the best source" is on. */
    val enabled get() = settings.pickBest

    init {
        scope.launch {
            val stored = runCatching { if (file.exists()) SourceRanking.fromJson(file.readText()) else emptyMap() }.getOrDefault(emptyMap())
            // Anything recorded before the file was read stays on top of it.
            _records.update { stored + it }
        }
    }

    /** A download of [entity] finished; [bytes] moved in [millis] (0 when unknown, e.g. a torrent). */
    fun recordSuccess(entity: DownloadableFileEntity, bytes: Long, millis: Long) = record(entity) { r, now ->
        history.event("download", entity.fileName)
        SourceRanking.withSuccess(r, bytes, millis, now)
    }

    /** A download (or one of its automatic retries) of [entity] failed with [error] (null = no detail). */
    fun recordFailure(entity: DownloadableFileEntity, error: Throwable?) = record(entity) { r, now ->
        history.event("download", entity.fileName, "failed")
        val code = (error as? HttpStatusException)?.code
        SourceRanking.withFailure(r, FailureClass.of(code, error is IOException), now)
    }

    private fun record(entity: DownloadableFileEntity, change: (SourceRecord, Long) -> SourceRecord) {
        scope.launch {
            val source = runCatching { sourceOf(entity) }.getOrNull() ?: return@launch
            _records.update { it + (source to change(it[source] ?: SourceRecord(), System.currentTimeMillis())) }
            save()
        }
    }

    @Synchronized
    private fun save() {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        runCatching {
            tmp.writeText(SourceRanking.toJson(_records.value))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }.onFailure {
            tmp.delete()
            Log.w(TAG, "Could not save the source track record: ${it.message}")
        }
    }

    /** A shareable, redacted track record: labels and numbers, never addresses, keys or file names. */
    fun exportTrackRecord(): String = SourceRanking.exportJson(_records.value, System.currentTimeMillis())

    /**
     * The source [entity] came from. Downloads restored from the history of an earlier run carry
     * no source: it is looked up by the download address.
     */
    suspend fun sourceOf(entity: DownloadableFileEntity): String? =
        entity.sourceUrl.ifEmpty {
            fileDao.filesByFileNames(listOf(entity.fileName))
                .firstOrNull { it.consoleId == entity.consoleId && it.downloadUrl == entity.downloadUrl }?.sourceUrl.orEmpty()
        }.ifEmpty { null }

    /**
     * Copies of [entity] in the enabled sources of its console (itself included), best first. With
     * [sameNameOnly] only copies under the same file name count (the Downloads list knows a
     * download by its file name); otherwise also the same cleaned title with the same tags.
     */
    suspend fun ranked(entity: DownloadableFileEntity, sameNameOnly: Boolean = true): List<DownloadableFileEntity> {
        val order = sourceOrder(entity.consoleId)
        val self = entity.copy(sourceUrl = sourceOf(entity).orEmpty())
        val rows = fileDao.filesByFileNames(listOf(entity.fileName)).filter { it.consoleId == entity.consoleId } +
            (if (sameNameOnly) emptyList() else fileDao.versionsOf(entity.consoleId, entity.searchKey))
        val others = rows.filter { it.downloadUrl != self.downloadUrl && it.sourceUrl in order }.distinctBy { it.downloadUrl }
        if (others.isEmpty()) return listOf(self)
        // Automatic ranking follows the same profile visibility rule as manual choices/imports.
        val tags = (others.map { it.id } + self.id).distinct().chunked(400)
            .flatMap { fileDao.tagsOfFiles(it) }.groupBy({ it.fileId }, { it.tag })
        val restrictions = profiles.current()
        fun copyOf(f: DownloadableFileEntity) = SourceRanking.Copy(f.consoleId, f.fileName, f.name, tags[f.id].orEmpty(), f.fileSize)
        val target = copyOf(self)
        val same = others.filter {
            restrictions.allows(it.consoleId, tags[it.id].orEmpty()) && SourceRanking.sameGame(target, copyOf(it))
        }
        return SourceRanking.rank(listOf(self) + same, { it.sourceUrl }, _records.value, order, System.currentTimeMillis())
    }

    /** The best copy of [entity] when "Pick the best source" is on; [entity] itself otherwise or on any trouble. */
    suspend fun pickBest(entity: DownloadableFileEntity, sameNameOnly: Boolean = true): DownloadableFileEntity {
        if (!settings.pickBest.first()) return entity
        return runCatching { ranked(entity, sameNameOnly).firstOrNull() }
            .onFailure { Log.w(TAG, "Could not rank the sources of ${entity.fileName}: ${it.message}") }
            .getOrNull() ?: entity
    }

    /**
     * After [entity] failed for good: the best copy of it in another source (same file name), or
     * null when there is none or "Pick the best source" is off.
     */
    suspend fun nextBest(entity: DownloadableFileEntity): DownloadableFileEntity? {
        if (!settings.pickBest.first()) return null
        return runCatching { ranked(entity, sameNameOnly = true).firstOrNull { it.downloadUrl != entity.downloadUrl } }.getOrNull()
    }

    /** Indexed, enabled copies of this exact file that the current profile allows. Ranking is advisory. */
    suspend fun manualCandidates(entity: DownloadableFileEntity): List<DownloadableFileEntity> {
        val order = sourceOrder(entity.consoleId)
        val rows = fileDao.filesByFileNames(listOf(entity.fileName)).filter {
            DownloadSourcePolicy.sameFile(entity, it) && it.sourceUrl in order
        }.distinctBy { it.downloadUrl }
        val tags = rows.map { it.id }.chunked(400).flatMap { fileDao.tagsOfFiles(it) }
            .groupBy({ it.fileId }, { it.tag })
        // Read after all index awaits: a profile switch during a database query wins.
        val restrictions = profiles.current()
        if (entity.consoleId in restrictions.hiddenConsoles) return emptyList()
        // A disappeared history row has no reliable tags. With tag restrictions, fail closed.
        val current = rows.firstOrNull { it.downloadUrl == entity.downloadUrl }
        if (restrictions.hiddenTags.isNotEmpty() && (current == null || !restrictions.allows(current.consoleId, tags[current.id].orEmpty()))) return emptyList()
        return SourceRanking.rank(
            rows.filter { restrictions.allows(it.consoleId, tags[it.id].orEmpty()) },
            { it.sourceUrl }, _records.value, order, System.currentTimeMillis()
        )
    }

    /** Used to explain a confirmation rejected after switching profile while the dialog was open. */
    suspend fun permitsManualChoice(entity: DownloadableFileEntity): Boolean {
        val row = fileDao.filesByFileNames(listOf(entity.fileName)).firstOrNull {
            it.consoleId == entity.consoleId && it.downloadUrl == entity.downloadUrl
        }
        val tags = row?.let { fileDao.getTagsForFile(it.id) }.orEmpty()
        val restrictions = profiles.current()
        return restrictions.allows(entity.consoleId, tags) && (restrictions.hiddenTags.isEmpty() || row != null)
    }

    /** The enabled sources of [consoleId] in the order of Sources. */
    suspend fun sourceOrder(consoleId: String): List<String> {
        val rommOn = settingsRepository.rommEnabled.first()
        // "Use RomM" off: a RomM platform is never a candidate for the best source.
        return consoleDao.getConsoleById(consoleId)?.urls?.let { SourcesJson.parseUrlEntries(it) }
            ?.filter { it.enabled && (rommOn || !RommSource.isSource(it.url)) }?.map { it.url }.orEmpty()
    }
}
