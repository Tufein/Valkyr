package com.tufein.valkyr.ui.screens.manuals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tufein.valkyr.data.local.ManualSettings
import com.tufein.valkyr.data.service.ManualResult
import com.tufein.valkyr.data.service.ManualService
import com.tufein.valkyr.data.service.RemoteManual
import com.tufein.valkyr.util.Manuals
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class ManualsUi(
    val loaded: Boolean = false,
    val docs: List<Manuals.Doc> = emptyList(),
    /** RomM has a manual that is not on the device yet. */
    val remote: RemoteManual? = null,
    val busy: Boolean = false,
    val result: ManualResult? = null
)

/** The manuals panel of one game page: what is on the device, what RomM could add, and the actions. */
@HiltViewModel
class ManualsViewModel @Inject constructor(
    private val service: ManualService,
    private val settings: ManualSettings
) : ViewModel() {

    private val _ui = MutableStateFlow(ManualsUi())
    val ui: StateFlow<ManualsUi> = _ui.asStateFlow()

    private var key: String? = null

    fun load(consoleId: String, fileName: String, force: Boolean = false) {
        val k = "$consoleId|$fileName"
        if (!force && k == key) return
        key = k
        viewModelScope.launch {
            val docs = service.docs(consoleId, fileName)
            _ui.update { it.copy(loaded = true, docs = docs) }
            // Look at RomM only while no manual is on the device (a quick cached read; failure just hides the button).
            val remote = if (docs.isEmpty()) service.remote(consoleId, fileName) else null
            _ui.update { it.copy(remote = remote) }
        }
        viewModelScope.launch { service.changes.collect { if (key == k) _ui.update { u -> u.copy(docs = service.docs(consoleId, fileName)) } } }
    }

    fun download(consoleId: String, fileName: String) {
        val remote = _ui.value.remote ?: return
        if (_ui.value.busy) return
        _ui.update { it.copy(busy = true, result = null) }
        viewModelScope.launch {
            val result = service.download(consoleId, fileName, remote)
            val docs = service.docs(consoleId, fileName)
            _ui.update { it.copy(busy = false, result = result, docs = docs, remote = if (docs.isEmpty()) it.remote else null) }
        }
    }

    fun deleteAll(consoleId: String, fileName: String) {
        viewModelScope.launch {
            service.delete(consoleId, fileName)
            _ui.update { it.copy(docs = emptyList(), result = null) }
            load(consoleId, fileName, force = true)
        }
    }

    fun clearResult() = _ui.update { it.copy(result = null) }

    fun fileOf(consoleId: String, fileName: String, name: String): File = service.fileOf(consoleId, fileName, name)

    suspend fun lastPage(consoleId: String, fileName: String, doc: Manuals.Doc): Int = settings.lastPage(Manuals.docKey(consoleId, fileName, doc))

    fun saveLastPage(consoleId: String, fileName: String, doc: Manuals.Doc, page: Int) {
        viewModelScope.launch { settings.setLastPage(Manuals.docKey(consoleId, fileName, doc), page) }
    }
}
