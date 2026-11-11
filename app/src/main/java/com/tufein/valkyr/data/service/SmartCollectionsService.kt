package com.tufein.valkyr.data.service

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.tufein.valkyr.data.local.dataStore
import com.tufein.valkyr.data.local.dao.*
import com.tufein.valkyr.data.local.entity.CollectionItemEntity
import com.tufein.valkyr.util.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(FlowPreview::class)
@Singleton
class SmartCollectionsService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val collections: CollectionDao, private val files: DownloadableFileDao,
    private val metadata: GameMetadataDao, private val profiles: ProfileService, private val history: ActionLogService
) {
    private val key = stringPreferencesKey("smart_collection_rules")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val _failed = MutableStateFlow(false)
    val failed = _failed.asStateFlow()
    val rules = context.dataStore.data.map { prefs ->
        SmartCollectionRules.decode(prefs[key]) ?: emptyMap()
    }.stateIn(scope, SharingStarted.Eagerly, emptyMap())
    init {
        scope.launch {
            merge(rules.map { }, files.observeLibraryChanges().map { }, metadata.observeKnown().map { },
                profiles.activeId.map { }, history.entries.map { }, collections.observeAll().map { })
                .debounce(600).collect {
                    try { refresh(); _failed.value = false }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { _failed.value = true }
                }
        }
    }
    suspend fun save(id: Long, rule: SmartCollectionRule?) {
        require(rule == null || rule.valid)
        context.dataStore.edit { p ->
            val all = (SmartCollectionRules.decode(p[key]) ?: error("Rules are unreadable")).toMutableMap()
            if (rule == null) all.remove(id) else all[id] = rule
            p[key] = SmartCollectionRules.encode(all)
        }
    }
    suspend fun refresh() = lock.withLock {
        val known = metadata.observeKnown().first().associateBy { it.lookupKey }
        val restrictions = profiles.current()
        val active = profiles.activeId.value
        val lastPlayed = HashMap<String, Long>()
        history.entries.filterNotNull().first().filter { it.profileId == active && it.kind == ActionKind.PLAYED }.forEach {
            val k = VersionPreference.key(it.consoleId.orEmpty(), it.fileName.orEmpty())
            if (it.at > (lastPlayed[k] ?: 0L)) lastPlayed[k] = it.at
        }
        val now = System.currentTimeMillis()
        val current = SmartCollectionRules.decode(context.dataStore.data.first()[key]) ?: error("Rules are unreadable")
        val existing = collections.getAll().map { it.id }.toSet()
        for ((id, rule) in current.filterKeys { it in existing }) {
            val matches = ArrayList<CollectionItemEntity>()
            for (console in files.indexedSources().map { it.consoleId }.distinct().filter { rule.consoles.isEmpty() || it in rule.consoles }) {
                currentCoroutineContext().ensureActive()
                val rows = files.fileRefsOf(console)
                val tags = rows.chunked(400).flatMap { files.tagsOfFiles(it.map { f -> f.id }) }.groupBy { it.fileId }
                for (row in rows) {
                    val rowTags = tags[row.id].orEmpty().map { it.tag }
                    val title = GameTitleCleaner.clean(row.name)
                    val m = known["${console.substringAfter("_", console).lowercase()}|${title.lowercase()}"]
                    val year = m?.released?.take(4)?.toIntOrNull()
                    if (restrictions.allows(console, rowTags) && rule.matches(console, rowTags, m?.genres, year,
                            VersionPreference.key(console, row.fileName) in lastPlayed,
                            lastPlayed[VersionPreference.key(console, row.fileName)], now)) {
                        matches += CollectionItemEntity(id, console, row.fileName)
                    }
                }
            }
            collections.replaceItems(id, matches.distinctBy { it.consoleId to it.fileName })
        }
    }
}
