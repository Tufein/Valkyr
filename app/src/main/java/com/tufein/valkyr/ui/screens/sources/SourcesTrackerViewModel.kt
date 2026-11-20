package com.tufein.valkyr.ui.screens.sources

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tufein.valkyr.data.repository.SourcesRepository
import com.tufein.valkyr.data.service.SourceScanService
import com.tufein.valkyr.data.service.SourceTrackService
import com.tufein.valkyr.data.state.SourceScanResults
import com.tufein.valkyr.util.SourceTracker
import com.tufein.valkyr.util.SourceTrackerRow
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/** Sources tracker: one row per configured source across all consoles, with scan and download history. */
@HiltViewModel
class SourcesTrackerViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val sources: SourcesRepository,
    scanResults: SourceScanResults,
    private val sourceTrack: SourceTrackService,
    private val scanService: SourceScanService
) : ViewModel() {

    val rows: StateFlow<List<SourceTrackerRow>> = combine(
        sources.manufacturers, scanResults.results, sourceTrack.records
    ) { manufacturers, results, records ->
        SourceTracker.rows(manufacturers, results, records, System.currentTimeMillis())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _rescanning = MutableStateFlow(false)
    val rescanning: StateFlow<Boolean> = _rescanning

    /** Rescans just this one address of this one console; other sources are untouched. */
    fun rescan(row: SourceTrackerRow) {
        if (_rescanning.value || !row.enabled) return
        _rescanning.value = true
        scanService.scanSelectedUrls(mapOf(row.consoleId to setOf(row.url))).invokeOnCompletion { _rescanning.value = false }
    }

    /** Switches one source on or off; the list updates itself from the repository. */
    fun setEnabled(row: SourceTrackerRow, enabled: Boolean) {
        viewModelScope.launch { sources.setUrlEnabled(row.consoleId, row.index, enabled) }
    }

    /** The sources that would be paused: failing ones, never a console's last working source. */
    fun pausable(): List<SourceTrackerRow> = SourceTracker.pausable(rows.value, System.currentTimeMillis())

    /** Switches those sources off (they stay in the list and can be switched on again). Returns how many. */
    fun pause(targets: List<SourceTrackerRow>, onDone: (Int) -> Unit) {
        viewModelScope.launch {
            targets.forEach { sources.setUrlEnabled(it.consoleId, it.index, false) }
            onDone(targets.size)
        }
    }

    /** Writes a redacted track record into the exports folder and hands back a shareable URI. */
    fun exportTrackRecord(onReady: (Uri) -> Unit) {
        runCatching {
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            val file = File(dir, "valkyr-source-track.json")
            file.writeText(sourceTrack.exportTrackRecord())
            FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        }.getOrNull()?.let(onReady)
    }
}
