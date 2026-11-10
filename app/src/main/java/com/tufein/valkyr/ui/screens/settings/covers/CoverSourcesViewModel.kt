package com.tufein.valkyr.ui.screens.settings.covers

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tufein.valkyr.R
import com.tufein.valkyr.data.local.CoverSourceSettings
import com.tufein.valkyr.data.service.CoverRepository
import com.tufein.valkyr.data.service.CoverSourceStats
import com.tufein.valkyr.data.service.IgdbService
import com.tufein.valkyr.data.service.ScreenScraperService
import com.tufein.valkyr.data.service.SteamGridDbService
import com.tufein.valkyr.data.service.TheGamesDbService
import com.tufein.valkyr.util.CoverApis
import com.tufein.valkyr.util.CoverArtwork
import com.tufein.valkyr.util.CoverSources
import com.tufein.valkyr.util.ToastUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

/** What the Cover sources screen shows of the ScreenScraper login (passwords only as "set" / "not set" in rows). */
data class ScreenScraperLogin(
    val devId: String = "",
    val devPassword: String = "",
    val softName: String = "",
    val user: String = "",
    val password: String = ""
) {
    val ready: Boolean get() = devId.isNotBlank() && devPassword.isNotBlank()
}

/** Settings → Cover sources (4.3.0): the source chain, the ScreenScraper login and today's counts. 4.4.0: the keys of TheGamesDB, SteamGridDB and IGDB with a test each. */
@HiltViewModel
class CoverSourcesViewModel @Inject constructor(
    private val settings: CoverSourceSettings,
    private val screenScraper: ScreenScraperService,
    private val theGamesDb: TheGamesDbService,
    private val steamGridDb: SteamGridDbService,
    private val igdb: IgdbService,
    private val covers: CoverRepository,
    stats: CoverSourceStats
) : ViewModel() {

    val chain: StateFlow<CoverSources.Chain?> = settings.chain.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 5.6.0: the order and switches of screenshots and title screens, apart from the covers. */
    val artwork: StateFlow<CoverArtwork.Chain> = settings.artworkChain.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CoverArtwork.Chain())

    val login: StateFlow<ScreenScraperLogin> = combine(settings.credentials, settings.softNameTyped) { c, soft ->
        ScreenScraperLogin(c.devId, c.devPassword, soft, c.user, c.password)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScreenScraperLogin())

    /** The API keys (the screen shows only whether each is set). */
    val keys: StateFlow<CoverApis.Keys> = settings.apiKeys.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CoverApis.Keys())

    val stats: StateFlow<CoverSourceStats.State> = stats.state

    private val _testingKey = MutableStateFlow<CoverSources.Source?>(null)
    /** The source whose key is being tested right now. */
    val testingKey: StateFlow<CoverSources.Source?> = _testingKey.asStateFlow()

    private val _keyTests = MutableStateFlow<Map<CoverSources.Source, CoverApis.KeyTest>>(emptyMap())
    /** The answer of the last "Test key" per source (cleared when one of its keys changes). */
    val keyTests: StateFlow<Map<CoverSources.Source, CoverApis.KeyTest>> = _keyTests.asStateFlow()

    private val _testing = MutableStateFlow(false)
    val testing: StateFlow<Boolean> = _testing.asStateFlow()

    private val _testResult = MutableStateFlow<ScreenScraperService.LoginResult?>(null)
    /** The answer of the last "Test login" (cleared when a credential changes). */
    val testResult: StateFlow<ScreenScraperService.LoginResult?> = _testResult.asStateFlow()

    private val _retried = MutableStateFlow<Int?>(null)
    /** How many games without a cover the last "look again" sent back to the lookup. */
    val retried: StateFlow<Int?> = _retried.asStateFlow()

    fun move(source: CoverSources.Source, delta: Int) = viewModelScope.launch { runCatching { settings.move(source, delta) } }

    fun setEnabled(source: CoverSources.Source, on: Boolean) = viewModelScope.launch { runCatching { settings.setEnabled(source, on) } }

    fun moveArtwork(source: CoverSources.Source, delta: Int) = viewModelScope.launch { runCatching { settings.moveArtwork(source, delta) } }

    fun setArtworkEnabled(source: CoverSources.Source, on: Boolean) = viewModelScope.launch { runCatching { settings.setArtworkEnabled(source, on) } }

    fun setNonBoxArt(on: Boolean) = viewModelScope.launch { runCatching { settings.setNonBoxArt(on) } }

    fun set(field: CoverSourceSettings.Field, value: String) = viewModelScope.launch {
        runCatching { settings.set(field, value) }
        _testResult.value = null
        sourceOf(field)?.let { _keyTests.value = _keyTests.value - it }
    }

    private fun sourceOf(field: CoverSourceSettings.Field): CoverSources.Source? = when (field) {
        CoverSourceSettings.Field.TGDB_KEY -> CoverSources.Source.THEGAMESDB
        CoverSourceSettings.Field.SGDB_KEY -> CoverSources.Source.STEAMGRIDDB
        CoverSourceSettings.Field.IGDB_CLIENT_ID, CoverSourceSettings.Field.IGDB_CLIENT_SECRET -> CoverSources.Source.IGDB
        else -> null
    }

    /** Checks the key(s) of [source] with the service and keeps the answer for the screen. */
    fun testKey(source: CoverSources.Source) {
        if (_testingKey.value != null) return
        _testingKey.value = source
        viewModelScope.launch {
            val result = try {
                when (source) {
                    CoverSources.Source.THEGAMESDB -> theGamesDb.testKey()
                    CoverSources.Source.STEAMGRIDDB -> steamGridDb.testKey()
                    CoverSources.Source.IGDB -> igdb.testKey()
                    else -> CoverApis.KeyTest.Missing
                }
            } catch (e: CancellationException) {
                _testingKey.value = null
                throw e
            } catch (e: Exception) {
                CoverApis.KeyTest.Offline
            }
            _keyTests.value = _keyTests.value + (source to result)
            _testingKey.value = null
        }
    }

    fun testLogin() {
        if (_testing.value) return
        _testing.value = true
        viewModelScope.launch {
            _testResult.value = runCatching { screenScraper.testLogin() }.getOrDefault(ScreenScraperService.LoginResult.Offline)
            _testing.value = false
        }
    }

    /** Forgets the covers that were not found, so every source is asked again; says how many. */
    fun retryMisses(context: Context) {
        val app = context.applicationContext
        val texts = context.resources
        viewModelScope.launch {
            val count = runCatching { covers.retryMisses() }.getOrNull()
            if (count == null) {
                ToastUtil.showError(app, texts.getString(R.string.settings_v5_covers_retry_failed))
            } else {
                _retried.value = count
                ToastUtil.showSuccess(app, texts.getQuantityString(R.plurals.settings_v5_covers_reset_done, count, count))
            }
        }
    }
}
