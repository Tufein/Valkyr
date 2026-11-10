package com.tufein.valkyr.data.service

import android.content.Context
import android.util.Log
import com.tufein.valkyr.data.local.BetterVersionsSettings
import com.tufein.valkyr.data.local.dao.DownloadableFileDao
import com.tufein.valkyr.data.local.entity.DownloadableFileEntity
import com.tufein.valkyr.data.model.DownloadStatus
import com.tufein.valkyr.data.repository.SettingsRepository
import com.tufein.valkyr.util.BetterVersions
import com.tufein.valkyr.util.CollectionGoals
import com.tufein.valkyr.util.DatStatus
import com.tufein.valkyr.util.DuplicateFinder
import com.tufein.valkyr.util.FileParsingUtils
import com.tufein.valkyr.util.GameEntry
import com.tufein.valkyr.util.LibraryKeys
import com.tufein.valkyr.util.ToastUtil
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import android.net.Uri
import android.provider.DocumentsContract
import com.tufein.valkyr.data.local.AppSettings
import com.tufein.valkyr.data.local.VersionPreferenceSettings
import com.tufein.valkyr.util.ActionReason
import com.tufein.valkyr.util.GameTitleCleaner
import com.tufein.valkyr.util.OfflineCollections
import com.tufein.valkyr.util.SpaceMath
import com.tufein.valkyr.util.StorageHelper
import com.tufein.valkyr.util.StorageInsights
import com.tufein.valkyr.util.VersionCompare

private const val TAG = "BetterVersions"

/** A better file for a game on the device: [owned] is what you have, [candidate] the row of a source to download. */
data class BetterSuggestion(
    val id: String,
    val consoleId: String,
    val owned: GameEntry,
    val candidate: DownloadableFileEntity,
    val upgrade: BetterVersions.Upgrade
) {
    /** The game's title as it reads without tags. */
    val title: String get() = GameTitleCleaner.clean(owned.baseName + ".x").ifBlank { owned.baseName }

    /** The candidate's name as a person reads it (URL escapes undone). */
    val candidateName: String get() = FileParsingUtils.decodeUrlEncodedFileName(candidate.fileName)
}

/** What a scan found: the games checked and the suggestions. */
data class BetterScan(val folderSet: Boolean, val gamesChecked: Int, val suggestions: List<BetterSuggestion>)

/** The messages of a removal, resolved by the screen (it carries the in-app language) before the work starts. */
data class UpgradePreview(val bytes: Long, val shortfall: Long, val unknownSizes: Int, val unknownVolumes: Int, val unavailable: Boolean) {
    val fits get() = shortfall == 0L && !unavailable
}

data class ReplaceMessages(val removed: String, val kept: String, val failed: String)

/** How the "download, then remove the old file" of one suggestion stands. */
enum class ReplaceState { WAITING, REMOVED, KEPT, FAILED }

/**
 * Better versions (7.0): works out which games on the device have a clearly better copy in the
 * sources (the decision is [BetterVersions]), starts the download, and, when asked, removes the
 * old file afterwards. The removal is deliberately timid: it only runs for a download that finished
 * and passed its check (or has nothing to be checked against), when the new file is on the device
 * and is not the old one; anything else leaves the old file alone. A pending removal lives in
 * memory only, so closing the app before the download is done removes nothing.
 */
@Singleton
class BetterVersionsService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val scanService: LibraryScanService,
    private val fileDao: DownloadableFileDao,
    private val libraryIndex: LibraryIndexService,
    private val downloadService: DownloadService,
    private val datService: DatService,
    private val settingsRepository: SettingsRepository,
    private val settings: BetterVersionsSettings,
    private val versionPreference: VersionPreferenceService,
    private val preferences: VersionPreferenceSettings,
    private val profiles: ProfileService,
    private val appSettings: AppSettings
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class Pending(val suggestion: BetterSuggestion, val messages: ReplaceMessages, val profile: String)

    /** Pending removals by the file name of the download they wait for. */
    private val pending = ConcurrentHashMap<String, Pending>()

    private val _replacing = MutableStateFlow<Map<String, ReplaceState>>(emptyMap())
    /** Suggestion id → state of its removal, for the suggestions that were started with "remove the old one". */
    val replacing: StateFlow<Map<String, ReplaceState>> = _replacing.asStateFlow()

    init {
        scope.launch {
            downloadService.finished.collect { name ->
                val p = pending.remove(name) ?: return@collect
                launch { finishReplace(p) }
            }
        }
        // A download the user stopped, that failed, or that is gone from the list is no longer going to
        // replace anything: a later manual download of the same name must not delete without asking.
        scope.launch {
            val seen = HashSet<String>()
            downloadService.downloads.collect { list ->
                val byName = list.associateBy { it.fileName }
                seen.addAll(byName.keys)
                for (name in pending.keys.toList()) {
                    val status = byName[name]?.status
                    val gone = byName[name] == null && name in seen
                    if (gone || status == DownloadStatus.STOPPED || status == DownloadStatus.FAILED) {
                        pending.remove(name)?.let { setState(it.suggestion.id, null) }
                        seen.remove(name)
                    }
                }
                seen.retainAll(byName.keys + pending.keys)
            }
        }
    }

    // ---- Finding -----------------------------------------------------------------------------

    suspend fun find(): BetterScan = withContext(Dispatchers.IO) {
        val folderSet = settingsRepository.downloadDirectory.first().isNotBlank() ||
            settingsRepository.consoleDownloadDirectories.first().isNotEmpty()
        val snapshot = scanService.scan()
        val games = DuplicateFinder.entries(snapshot.files).filter { eligible(it) && versionPreference.preferred(it.consoleId.orEmpty(), it.baseName) == null }
        val restrictions = profiles.current()
        val pref = preferences.snapshot()
        val reports = datService.reports.value
        val ownedKeys = libraryIndex.ownedKeys.value
        val ignored = settings.ignored.first()
        val out = ArrayList<BetterSuggestion>()
        for ((consoleId, group) in games.groupBy { it.consoleId!! }) {
            ensureActive()
            val rows = fileDao.filesOf(consoleId).filter { restrictions.allows(consoleId, fileDao.tagsOf(it.id)) }
            if (rows.isEmpty()) continue
            val rowById = rows.associateBy { it.id.toString() }
            val offers = rows.map { BetterVersions.Offer(it.id.toString(), consoleId, FileParsingUtils.decodeUrlEncodedFileName(it.fileName)) }
            val scopes = LibraryKeys.scopesFor(consoleId)
            val statuses = datStatuses(reports[consoleId])
            val owned = group.map { BetterVersions.OwnedGame(it.id, consoleId, it.baseName, datStatus(it, statuses)) }
            val byId = group.associateBy { it.id }
            val matches = BetterVersions.suggest(owned, offers, ignored) { offer ->
                val row = rowById[offer.id]!!
                CollectionGoals.isOwned(scopes, row.fileName, ownedKeys) || downloadService.isActive(row.fileName)
            }
            for (m in matches) {
                val candidate = rowById.getValue(m.offer.id)
                val ranking = VersionCompare.rank(listOf(
                    VersionCompare.Version("old", m.owned.name),
                    VersionCompare.Version("new", FileParsingUtils.decodeUrlEncodedFileName(candidate.fileName), fileDao.tagsOf(candidate.id), candidate.fileSize)
                ), pref.of(consoleId))
                if (ranking.firstOrNull()?.version?.id != "new") continue
                out += BetterSuggestion(m.owned.id + "::" + m.offer.id, consoleId, byId.getValue(m.owned.id), rowById.getValue(m.offer.id), m.upgrade)
            }
        }
        val unambiguous = out.groupBy { it.candidate.fileName }.values.filter { group -> group.map { it.consoleId }.distinct().size == 1 }.map { it.first() }
        BetterScan(folderSet, games.size, unambiguous)
    }

    /** A game worth comparing: known console, a real game (not a playlist, not a BIOS). */
    private fun eligible(entry: GameEntry): Boolean =
        entry.comparable && entry.consoleId != null && entry.baseName.isNotBlank() &&
            !entry.files.all { it.name.substringAfterLast('.', "").equals("m3u", ignoreCase = true) } &&
            entry.folder.split('/').none { it.trim().lowercase() in biosFolders }

    private fun datStatuses(report: DatReport?): Map<String, DatStatus> =
        report?.checks?.associate { (disk, check) -> disk.name.lowercase() to check.status }.orEmpty()

    /** The DAT's verdict on one game of the device, when a DAT check ran this session and covered it. */
    private fun datStatus(entry: GameEntry, byName: Map<String, DatStatus>): DatStatus? {
        val seen = entry.files.mapNotNull { byName[it.name.lowercase()] }.filter { it != DatStatus.SKIPPED }
        return when {
            seen.isEmpty() -> null
            DatStatus.VERIFIED in seen -> DatStatus.VERIFIED
            DatStatus.MISNAMED in seen -> DatStatus.MISNAMED
            else -> DatStatus.UNKNOWN
        }
    }

    // ---- Downloading -------------------------------------------------------------------------

    /** Starts the downloads of [items]; nothing is removed. */
    suspend fun download(items: List<BetterSuggestion>) {
        if (items.isNotEmpty() && allowed(items)) downloadService.startDownloads(items.map { it.candidate })
    }

    /**
     * Starts the downloads of [items] and, for each, removes the old file once the new one is
     * complete and checked. [messages] belong to each suggestion by id.
     */
    suspend fun downloadAndReplace(items: List<BetterSuggestion>, messages: Map<String, ReplaceMessages>) {
        if (items.isEmpty() || !allowed(items)) return
        for (item in items) {
            val m = messages[item.id] ?: continue
            pending[item.candidate.fileName] = Pending(item, m, profiles.currentIdNow())
            setState(item.id, ReplaceState.WAITING)
        }
        downloadService.startDownloads(items.map { it.candidate })
    }

    private suspend fun allowed(items: List<BetterSuggestion>): Boolean {
        val restrictions = profiles.current()
        return items.all { s -> restrictions.allows(s.consoleId, fileDao.tagsOf(s.candidate.id)) &&
            versionPreference.preferred(s.consoleId, s.owned.baseName) == null }
    }

    suspend fun preview(items: List<BetterSuggestion>, replacing: Boolean): UpgradePreview = withContext(Dispatchers.IO) {
        val root = settingsRepository.downloadDirectory.first()
        val folders = settingsRepository.consoleDownloadDirectories.first()
        val reserves = appSettings.minFreeGb.first().toLong() * 1024 * 1024 * 1024
        fun folder(console: String) = folders[console]?.takeIf { it.isNotBlank() } ?: root
        fun volume(uri: String): String = runCatching {
            val parsed = Uri.parse(uri)
            parsed.authority + ":" + DocumentsContract.getTreeDocumentId(parsed).substringBefore(':')
        }.getOrDefault(uri)
        val queue = downloadService.getDownloads().filter { it.status in setOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.COPYING, DownloadStatus.UNZIPPING, DownloadStatus.PAUSED) }
            .groupBy { volume(folder(downloadService.entityFor(it.fileName)?.consoleId.orEmpty())) }.mapValues { (_, rows) -> SpaceMath.sum(rows.map { item ->
                SpaceMath.add(
                    (item.fileSize - item.downloadedBytes).coerceAtLeast(0),
                    if (StorageInsights.isExtractable(item.fileName.substringAfterLast('.'))) item.fileSize else 0L)
            }) }
        var unknownVolumes = 0
        var short = 0L
        var needed = 0L
        val missingFolder = items.any { folder(it.consoleId).isBlank() }
        for ((_, group) in items.groupBy { volume(folder(it.consoleId)) }) {
            val uri = folder(group.first().consoleId)
            val required = SpaceMath.sum(group.map { s -> OfflineCollections.need(
                OfflineCollections.Offer(s.candidate.fileSize,
                    StorageInsights.isExtractable(s.candidate.fileExtension))) .let { need ->
                    SpaceMath.add(need, if (replacing) s.owned.size else 0L)
                } })
            needed = SpaceMath.add(needed, required)
            val free = StorageHelper.getFreeBytes(context, uri)
            if (free == null) unknownVolumes++
            else {
                val reserve = SpaceMath.add(reserves, queue[volume(uri)] ?: 0)
                val available = SpaceMath.available(free, reserve, OfflineCollections.SPACE_MARGIN).coerceAtLeast(0)
                short = SpaceMath.add(short, (required - available).coerceAtLeast(0))
            }
        }
        UpgradePreview(needed, short, items.count { it.candidate.fileSize <= 0 }, unknownVolumes, missingFolder || !allowed(items))
    }

    // ---- Removing the old file ---------------------------------------------------------------

    private suspend fun finishReplace(p: Pending) {
        val s = p.suggestion
        val outcome = try {
            if (profiles.currentIdNow() != p.profile || !allowed(listOf(s))) ReplaceState.KEPT else decide(s)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Old file not removed: ${e.message}")
            ReplaceState.KEPT
        }
        val state = if (outcome == ReplaceState.REMOVED) {
            val removed = runCatching { scanService.delete(s.owned, ActionReason.BETTER_VERSION) }.getOrDefault(0)
            if (removed > 0) ReplaceState.REMOVED else ReplaceState.FAILED
        } else outcome
        setState(s.id, state)
        val text = when (state) {
            ReplaceState.REMOVED -> p.messages.removed
            ReplaceState.FAILED -> p.messages.failed
            else -> p.messages.kept
        }
        withContext(Dispatchers.Main) {
            if (state == ReplaceState.REMOVED) ToastUtil.showSuccess(context, text) else ToastUtil.showInfo(context, text)
        }
    }

    /** [ReplaceState.REMOVED] when it is safe to remove the old file now (the caller does it), else [ReplaceState.KEPT]. */
    private suspend fun decide(s: BetterSuggestion): ReplaceState {
        val file = s.candidate
        // The new file may be copied and unpacked a moment after "finished", and its check starts then.
        delay(GRACE_MS)
        val expectsCheck = file.expectedHash != null || datService.sets.first().any { it.consoleId == file.consoleId }
        val verdict = withTimeoutOrNull(CHECK_WAIT_MS) {
            downloadService.verification.map { BetterVersions.checkVerdict(it[file.fileName], expectsCheck) }
                .first { it != BetterVersions.CheckVerdict.WAIT }
        }
        if (verdict != BetterVersions.CheckVerdict.REMOVE) return ReplaceState.KEPT
        // Never trust the path alone: the new file must be on the device and must not be one of the old files.
        if (BetterVersions.overlaps(s.owned.files.map { it.name }, file.fileName)) return ReplaceState.KEPT
        if (!onDevice(file)) return ReplaceState.KEPT
        return ReplaceState.REMOVED
    }

    private suspend fun onDevice(file: DownloadableFileEntity): Boolean {
        if (LibraryKeys.isOwned(file.consoleId, file.fileName, libraryIndex.ownedKeys.value)) return true
        libraryIndex.refresh()
        return LibraryKeys.isOwned(file.consoleId, file.fileName, libraryIndex.ownedKeys.value)
    }

    private fun setState(id: String, state: ReplaceState?) {
        _replacing.update { if (state == null) it - id else it + (id to state) }
    }

    private companion object {
        val biosFolders = setOf("bios", "system", "firmware")
        const val GRACE_MS = 3_000L
        /** How long a check may take (hashing a big file) before the old file is simply left alone. */
        const val CHECK_WAIT_MS = 5 * 60_000L
    }
}
