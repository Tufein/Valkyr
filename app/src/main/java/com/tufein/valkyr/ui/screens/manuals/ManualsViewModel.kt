package com.tufein.valkyr.ui.screens.manuals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tufein.valkyr.data.local.ManualSettings
import com.tufein.valkyr.data.service.ManualResult
import com.tufein.valkyr.data.service.ManualService
import com.tufein.valkyr.util.ManualHit
import com.tufein.valkyr.util.ManualSource
import com.tufein.valkyr.util.Manuals
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class ManualsUi(
    val loaded: Boolean = false,
    val docs: List<Manuals.Doc> = emptyList(),
    /** Manuals the enabled sources offer, best first. */
    val hits: List<ManualHit> = emptyList(),
    /** Which sources were asked, for "no manual, RomM and ScreenScraper were asked". */
    val sources: List<ManualSource> = emptyList(),
    /** Where each stored manual came from, by file name. */
    val attribution: Map<String, ManualSource> = emptyMap(),
    val busy: Boolean = false,
    val result: ManualResult? = null
)

/** The manuals panel of one game page: what is on the device, what the sources offer, and the actions. */
@HiltViewModel
class ManualsViewModel @Inject constructor(
    private val service: ManualService,
    private val settings: ManualSettings
) : ViewModel() {

    private val _ui = MutableStateFlow(ManualsUi())
    val ui: StateFlow<ManualsUi> = _ui.asStateFlow()

    private var key: String? = null
    private var changesJob: Job? = null

    fun load(consoleId: String, fileName: String, title: String = "", force: Boolean = false) {
        val k = "$consoleId|$fileName"
        if (!force && k == key) return
        key = k
        viewModelScope.launch {
            val docs = service.docs(consoleId, fileName)
            val attribution = service.attribution(consoleId, fileName)
            _ui.update { it.copy(loaded = true, docs = docs, attribution = attribution) }
            // Only look online while no manual is on the device (definite misses are cached for a month).
            if (docs.isEmpty()) {
                val sources = settings.sources.first().let { enabled -> ManualSource.entries.filter { it in enabled && it != ManualSource.LINK } }
                val hits = service.find(consoleId, fileName, title.ifBlank { Manuals.gameSegment(fileName) })
                _ui.update { it.copy(hits = hits, sources = sources) }
            } else _ui.update { it.copy(hits = emptyList(), sources = emptyList()) }
        }
        changesJob?.cancel()
        changesJob = viewModelScope.launch {
            service.changes.collect { change ->
                if (key == k) {
                    val docs = service.docs(consoleId, fileName)
                    _ui.update { u -> u.copy(docs = docs, attribution = service.attribution(consoleId, fileName)) }
                }
            }
        }
    }

    fun download(consoleId: String, fileName: String, title: String, hit: ManualHit) {
        if (_ui.value.busy) return
        _ui.update { it.copy(busy = true, result = null) }
        viewModelScope.launch {
            val result = service.download(consoleId, fileName, title, hit)
            val docs = service.docs(consoleId, fileName)
            _ui.update {
                it.copy(
                    busy = false, result = result, docs = docs,
                    attribution = service.attribution(consoleId, fileName),
                    hits = if (docs.isEmpty()) it.hits.filter { h -> h !== hit } else emptyList()
                )
            }
        }
    }

    fun downloadLink(consoleId: String, fileName: String, title: String, url: String) {
        if (_ui.value.busy) return
        _ui.update { it.copy(busy = true, result = null) }
        viewModelScope.launch {
            val result = service.downloadLink(consoleId, fileName, title, url)
            val docs = service.docs(consoleId, fileName)
            _ui.update {
                it.copy(
                    busy = false, result = result, docs = docs,
                    attribution = service.attribution(consoleId, fileName)
                )
            }
        }
    }

    fun deleteAll(consoleId: String, fileName: String, title: String) {
        viewModelScope.launch {
            service.delete(consoleId, fileName)
            _ui.update { it.copy(docs = emptyList(), result = null, attribution = emptyMap()) }
            load(consoleId, fileName, title, force = true)
        }
    }

    fun clearResult() = _ui.update { it.copy(result = null) }

    fun fileOf(consoleId: String, fileName: String, name: String): File = service.fileOf(consoleId, fileName, name)

    suspend fun lastPage(consoleId: String, fileName: String, doc: Manuals.Doc): Int = settings.lastPage(Manuals.docKey(consoleId, fileName, doc))

    fun saveLastPage(consoleId: String, fileName: String, doc: Manuals.Doc, page: Int) {
        viewModelScope.launch { settings.setLastPage(Manuals.docKey(consoleId, fileName, doc), page) }
    }
}
