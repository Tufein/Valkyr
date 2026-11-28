package com.tufein.valkyr.data.service

import android.content.Context
import android.net.Uri
import com.tufein.valkyr.data.local.entity.DownloadableFileEntity
import com.tufein.valkyr.util.Fingerprints
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 10.0: reads the local files of a game (the same files the launch uses) and returns their CRC32, MD5
 * and SHA-1. Read only; nothing is written or sent anywhere.
 */
@Singleton
class FingerprintService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val library: LibraryIndexService
) {
    /** The game's files and their sums; [onProgress] gets (files done, bytes read in the current file, name of the current file). */
    suspend fun compute(file: DownloadableFileEntity, onProgress: (Int, Long, String) -> Unit): List<Fingerprints.Fingerprint> = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()
        val parts = library.launchPlan(file)
        val out = ArrayList<Fingerprints.Fingerprint>()
        for ((index, part) in parts.withIndex()) {
            job.ensureActive()
            val stream = context.contentResolver.openInputStream(Uri.parse(part.uri)) ?: continue
            stream.use {
                out += Fingerprints.compute(part.name, it) { read ->
                    job.ensureActive()
                    onProgress(index, read, part.name)
                }
            }
        }
        out
    }
}
