package com.tufein.valkyr.data.service

import android.util.Log
import com.tufein.valkyr.data.model.DownloadStatus
import com.tufein.valkyr.data.state.RescanStateHolder
import com.tufein.valkyr.util.RetroArchPlaylists
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PlaylistsAutoRefresh"

/**
 * 6.2: "Keep RetroArch playlists up to date" (Tools -> RetroArch playlists). Waits for a finished
 * library rescan and for the last of a batch of downloads to finish, then lets
 * [RetroArchPlaylistsService.refreshExisting] add the new games to the playlists that exist. The
 * service itself checks the setting, so building this class (at app start) costs nothing while the
 * setting is off. Failures are logged without paths and ignored.
 */
@OptIn(FlowPreview::class)
@Singleton
class RetroArchPlaylistsAutoRefresh @Inject constructor(
    rescan: RescanStateHolder,
    downloads: DownloadService,
    private val playlists: RetroArchPlaylistsService
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            var finished = downloads.downloads.value.count { it.status == DownloadStatus.COMPLETED }
            val afterDownloads = downloads.downloads.map { list ->
                val now = list.count { it.status == DownloadStatus.COMPLETED }
                val active = list.any { it.status in RUNNING }
                val due = RetroArchPlaylists.refreshAfterDownloads(finished, now, active)
                // A count that dropped (the list was cleared) is the new baseline; so is one that was acted on.
                if (due || now < finished) finished = now
                due
            }.filter { it }
            merge(
                rescan.lastRescanTime.drop(1).map { true },
                afterDownloads
            ).debounce(DEBOUNCE_MS).collect {
                try {
                    val changed = playlists.refreshExisting()
                    if (changed > 0) Log.i(TAG, "Added games to $changed playlist(s)")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Refresh failed: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    private companion object {
        /** Lets a scan and the files of a download settle, and merges a burst into one refresh. */
        const val DEBOUNCE_MS = 5_000L
        val RUNNING = setOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.COPYING, DownloadStatus.UNZIPPING)
    }
}
