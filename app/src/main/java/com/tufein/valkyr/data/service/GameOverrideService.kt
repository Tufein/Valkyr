package com.tufein.valkyr.data.service

import com.tufein.valkyr.data.local.dao.GameOverrideDao
import com.tufein.valkyr.data.local.entity.GameOverrideEntity
import com.tufein.valkyr.data.model.GameDetails
import com.tufein.valkyr.util.MetadataOverrides
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 9.0: per-game corrections ("Fix the match"), keyed `consoleId|stem` like covers. Only fields the
 * user changed are stored; the rest stay automatic. A pinned cover short-circuits the provider
 * chain, in [CoverRepository].
 */
@Singleton
class GameOverrideService @Inject constructor(private val dao: GameOverrideDao) {
    /** Cached so the cover path costs one Room read only once a correction actually exists. */
    @Volatile
    private var cachedHasAny: Boolean? = null

    suspend fun hasAny(): Boolean = withContext(Dispatchers.IO) {
        cachedHasAny ?: (dao.count() > 0).also { cachedHasAny = it }
    }

    suspend fun get(consoleId: String, fileName: String): GameOverrideEntity? = withContext(Dispatchers.IO) {
        dao.get(MetadataOverrides.key(consoleId, fileName))
    }

    suspend fun pinnedCover(consoleId: String, fileName: String): String? = withContext(Dispatchers.IO) {
        dao.get(MetadataOverrides.key(consoleId, fileName))?.pinnedCoverUrl
    }

    suspend fun save(consoleId: String, fileName: String, fields: GameOverrideEntity): Boolean = withContext(Dispatchers.IO) {
        if (fields.title == null && fields.year == null && fields.genre == null && fields.pinnedCoverUrl == null) {
            dao.delete(MetadataOverrides.key(consoleId, fileName))
        } else {
            dao.upsert(fields.copy(key = MetadataOverrides.key(consoleId, fileName), updatedAt = System.currentTimeMillis()))
        }
        cachedHasAny = null
        true
    }

    suspend fun clear(consoleId: String, fileName: String) = withContext(Dispatchers.IO) {
        dao.delete(MetadataOverrides.key(consoleId, fileName))
        cachedHasAny = null
    }

    /** Applies a correction to the details a provider returned. */
    suspend fun apply(consoleId: String, fileName: String, details: GameDetails): GameDetails {
        val override = get(consoleId, fileName) ?: return details
        return MetadataOverrides.apply(details, override)
    }

    /** Everything the backup needs; fixed field names are handled by BackupJson. */
    suspend fun all(): List<GameOverrideEntity> = withContext(Dispatchers.IO) { dao.all() }

    suspend fun restore(entries: List<GameOverrideEntity>) = withContext(Dispatchers.IO) {
        dao.restore(entries)
        cachedHasAny = null
    }
}
