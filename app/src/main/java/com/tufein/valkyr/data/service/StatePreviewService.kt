package com.tufein.valkyr.data.service

import android.content.Context
import android.net.Uri
import com.tufein.valkyr.data.local.AppSettings
import com.tufein.valkyr.data.repository.SettingsRepository
import com.tufein.valkyr.util.DiskScanner
import com.tufein.valkyr.util.SaveFolderLayout
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
    private val settings: SettingsRepository,
    private val appSettings: AppSettings
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

    /** One picture in the gallery: where it is, its file name and when it changed (0 when unknown). */
    data class Picture(val uri: Uri, val name: String, val modified: Long)

    /** 9.6: every state picture of a game in the same folder, newest first; empty without a folder or pictures. */
    suspend fun findAll(fileName: String): List<Picture> = withContext(Dispatchers.IO) {
        runCatching {
            val tree = settings.saveSyncStatesDir.first().takeIf { it.isNotBlank() } ?: return@runCatching emptyList()
            val dir = DiskScanner.rootOf(tree) ?: return@runCatching emptyList()
            val files = DiskScanner.list(context, dir).filter { !it.isDirectory }
            StatePreviews.pictures(files.map { StatePreviews.Candidate(it.name, it.lastModified) }, fileName).mapNotNull { c ->
                files.firstOrNull { it.name == c.name }?.let { Picture(it.uri, it.name, c.modified) }
            }
        }.getOrDefault(emptyList())
    }

    /**
     * 7.3: the newest save or state of a game in the folders picked in Save sync: the saves and states
     * folders with the emulator / core folders inside them, and the folders of emulators that keep one
     * file per game. Memory cards and save folders are not named after a game and are not looked at.
     * Only names and times are read.
     */
    suspend fun newestSave(fileName: String): StatePreviews.SaveLine? = withContext(Dispatchers.IO) {
        runCatching {
            val roots = listOf(settings.saveSyncSavesDir.first(), settings.saveSyncStatesDir.first()) +
                appSettings.saveSyncEmulatorFolders.first().filter { it.layout == SaveFolderLayout.FILES }.map { it.uri }
            val candidates = mutableListOf<StatePreviews.Candidate>()
            roots.filter { it.isNotBlank() }.distinct().forEach { tree ->
                val dir = DiskScanner.rootOf(tree) ?: return@forEach
                val top = DiskScanner.list(context, dir)
                top.filter { !it.isDirectory }.forEach { candidates += StatePreviews.Candidate(it.name, it.lastModified) }
                top.filter { it.isDirectory }.take(MAX_SUBFOLDERS).forEach { sub ->
                    DiskScanner.list(context, DiskScanner.dirOf(dir, sub)).filter { !it.isDirectory }
                        .forEach { candidates += StatePreviews.Candidate(it.name, it.lastModified) }
                }
            }
            StatePreviews.newestSave(candidates, fileName)
        }.getOrNull()
    }

    private companion object {
        /** Emulator / core folders looked into per picked folder. */
        const val MAX_SUBFOLDERS = 40
    }
}
