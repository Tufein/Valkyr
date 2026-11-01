package com.tufein.valkyr.ui.screens.cloud.sections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tufein.valkyr.data.service.NetworkMonitor
import com.tufein.valkyr.data.service.RommErrorKind
import com.tufein.valkyr.data.service.RommGameService
import com.tufein.valkyr.data.service.rommErrorKind
import com.tufein.valkyr.util.RommGameInfo
import com.tufein.valkyr.util.RommProps
import com.tufein.valkyr.util.RommUserProps
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Where the RomM section of one game stands. */
enum class RommGamePhase {
    /** Finding out whether the game is on the server. */
    RESOLVING,
    /** Not on RomM, or RomM is not set up: the section draws nothing. */
    ABSENT,
    LOADING,
    READY,
    FAILED
}

data class RommGameUiState(
    val phase: RommGamePhase = RommGamePhase.RESOLVING,
    val info: RommGameInfo? = null,
    /** The play data as shown (may run ahead of the server while a change is being saved). */
    val props: RommUserProps = RommUserProps(),
    val saving: Boolean = false,
    val errorKind: RommErrorKind? = null
)

/**
 * The RomM section of the details dialog for one game ([RommGameSection] keys it per game).
 * Changes show at once and are written to RomM; a failed write puts the old values back and
 * sends an [events] message for a toast. Rating steps are gathered for a moment, so ▶▶▶ is one write.
 */
@HiltViewModel
class RommGameViewModel @Inject constructor(
    private val service: RommGameService,
    private val network: NetworkMonitor
) : ViewModel() {

    private val _ui = MutableStateFlow(RommGameUiState())
    val ui: StateFlow<RommGameUiState> = _ui.asStateFlow()

    private val _events = Channel<RommErrorKind>(Channel.BUFFERED)
    /** A write failed (and was undone): show a toast. */
    val events: Flow<RommErrorKind> = _events.receiveAsFlow()

    private val _queued = Channel<Unit>(Channel.BUFFERED)
    /** A change was kept for later because there is no connection: show a toast. */
    val queued: Flow<Unit> = _queued.receiveAsFlow()

    private var key: String? = null
    private var romId: Int? = null
    /** What the server holds, as far as we know. */
    private var confirmed = RommUserProps()
    private var loadedAt = 0L
    private var writeJob: Job? = null
    private val writeLock = Mutex()

    /** Called by the section for its game; cheap when nothing changed. */
    fun load(consoleId: String, fileName: String, force: Boolean = false) {
        val k = "$consoleId|$fileName"
        if (!force && k == key && _ui.value.phase != RommGamePhase.FAILED && System.currentTimeMillis() - loadedAt < RELOAD_MS) return
        if (k != key) romId = null
        key = k
        viewModelScope.launch {
            val id = romId ?: runCatching { service.romIdFor(consoleId, fileName) }.getOrNull()
            if (id == null) {
                _ui.value = RommGameUiState(phase = RommGamePhase.ABSENT)
                return@launch
            }
            romId = id
            val cached = service.cached(id)
            if (cached != null && _ui.value.info == null) show(cached)
            else if (_ui.value.info == null) _ui.update { it.copy(phase = RommGamePhase.LOADING) }
            try {
                val info = service.details(id, refresh = force)
                // A change made while it loaded wins over the older answer.
                if (writeJob?.isActive != true && !_ui.value.saving) show(info)
                else _ui.update { it.copy(info = info, phase = RommGamePhase.READY) }
                loadedAt = System.currentTimeMillis()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_ui.value.info == null) _ui.update { it.copy(phase = RommGamePhase.FAILED, errorKind = rommErrorKind(e)) }
            }
        }
    }

    fun retry() {
        val k = key ?: return
        load(k.substringBefore('|'), k.substringAfter('|'), force = true)
    }

    private fun show(info: RommGameInfo) {
        confirmed = info.props
        _ui.value = RommGameUiState(phase = RommGamePhase.READY, info = info, props = info.props)
    }

    fun toggle(pill: RommProps.Pill) = edit(RommProps.toggle(_ui.value.props, pill), delayMs = 0)

    fun stepRating(delta: Int) {
        val next = RommProps.stepRating(_ui.value.props, delta)
        if (next != _ui.value.props) edit(next, delayMs = RATING_DELAY_MS)
    }

    private fun edit(next: RommUserProps, delayMs: Long) {
        if (_ui.value.phase != RommGamePhase.READY || romId == null) return
        _ui.update { it.copy(props = next) }
        writeJob?.cancel()
        writeJob = viewModelScope.launch {
            if (delayMs > 0) delay(delayMs)
            // Once a write has started it finishes, even if another change comes in meanwhile.
            withContext(NonCancellable) { flush() }
        }
    }

    private suspend fun flush() {
        writeLock.withLock {
            val id = romId ?: return
            val target = _ui.value.props
            val changes = RommProps.changes(confirmed, target)
            if (changes.isEmpty()) return
            if (!network.online.value) {
                // No connection: keep the change for later instead of failing; the screen already shows it.
                service.queueProps(id, changes, target)
                confirmed = target
                _queued.trySend(Unit)
                return
            }
            _ui.update { it.copy(saving = true) }
            try {
                val saved = service.updateProps(id, changes, target)
                confirmed = saved
                // Only take the server's answer when nothing changed on screen meanwhile.
                _ui.update { if (it.props == target) it.copy(props = saved, saving = false) else it.copy(saving = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val kind = rommErrorKind(e)
                if (kind == RommErrorKind.UNREACHABLE) {
                    service.queueProps(id, changes, target)
                    confirmed = target
                    _ui.update { it.copy(saving = false) }
                    _queued.trySend(Unit)
                    return
                }
                _ui.update { it.copy(props = confirmed, saving = false) }
                _events.trySend(kind)
            }
        }
    }

    override fun onCleared() {
        // A rating step still waiting for its write goes out anyway.
        val id = romId
        val pending = RommProps.changes(confirmed, _ui.value.props)
        if (id != null && pending.isNotEmpty() && !_ui.value.saving) service.updatePropsLater(id, pending, _ui.value.props)
        super.onCleared()
    }

    private companion object {
        const val RATING_DELAY_MS = 700L
        const val RELOAD_MS = 2L * 60 * 1000
    }
}
