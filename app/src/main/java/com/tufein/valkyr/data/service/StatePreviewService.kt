package com.tufein.valkyr.data.service

import android.content.Context
import android.net.Uri
import com.tufein.valkyr.data.repository.SettingsRepository
import com.tufein.valkyr.util.DiskScanner
import com.tufein.valkyr.util.StatePreviews
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 6.5: the newest save-state picture of a game, looked up in the states folder picked in Save sync
 * (nothing is read or written elsewhere). Null when no folder is picked or the game has none.
 */
@Singleton
class StatePreviewService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: SettingsRepository
) {
    suspend fun find(fileName: String): Uri? = withContext(Dispatchers.IO) {
        runCatching {
            val tree = settings.saveSyncStatesDir.first().takeIf { it.isNotBlank() } ?: return@runCatching null
            val dir = DiskScanner.rootOf(tree) ?: return@runCatching null
            val files = DiskScanner.list(context, dir).filter { !it.isDirectory }
            val best = StatePreviews.pick(files.map { StatePreviews.Candidate(it.name, it.lastModified) }, fileName) ?: return@runCatching null
            files.firstOrNull { it.name == best.name }?.uri
        }.getOrNull()
    }
}
