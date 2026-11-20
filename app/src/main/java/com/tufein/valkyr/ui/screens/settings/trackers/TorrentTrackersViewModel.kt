package com.tufein.valkyr.ui.screens.settings.trackers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tufein.valkyr.data.local.TorrentTrackerSettings
import com.tufein.valkyr.data.service.TorrentTrackerCheck
import com.tufein.valkyr.util.TrackerEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TorrentTrackersViewModel @Inject constructor(
    private val settings: TorrentTrackerSettings
) : ViewModel() {

    val entries: StateFlow<List<TrackerEntry>> = settings.entries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _testing = MutableStateFlow<String?>(null)
    val testing: StateFlow<String?> = _testing.asStateFlow()

    private val _results = MutableStateFlow<Map<String, TorrentTrackerCheck.Result>>(emptyMap())
    val results: StateFlow<Map<String, TorrentTrackerCheck.Result>> = _results.asStateFlow()

    fun add(url: String) { viewModelScope.launch { settings.add(url) } }
    fun remove(url: String) { viewModelScope.launch { settings.remove(url) } }
    fun setEnabled(url: String, on: Boolean) { viewModelScope.launch { settings.setEnabled(url, on) } }

    fun test(url: String) = viewModelScope.launch {
        _testing.value = url
        val result = TorrentTrackerCheck.check(url)
        _results.value = _results.value + (url to result)
        _testing.value = null
    }
}
