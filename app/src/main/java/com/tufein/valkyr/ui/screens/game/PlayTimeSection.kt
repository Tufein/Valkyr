package com.tufein.valkyr.ui.screens.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tufein.valkyr.R
import com.tufein.valkyr.data.service.PlaySessionService
import com.tufein.valkyr.ui.components.Panel
import com.tufein.valkyr.ui.components.SectionTitle
import com.tufein.valkyr.util.PlaySessions
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlayTimeViewModel @Inject constructor(private val sessions: PlaySessionService) : ViewModel() {
    private val _totals = MutableStateFlow(PlaySessions.Totals.EMPTY)
    val totals = _totals.asStateFlow()
    val revision = sessions.revision

    fun load(consoleId: String, fileName: String) {
        viewModelScope.launch { _totals.value = sessions.totals(consoleId, fileName) }
    }
}

/** "2 h 05" in the screen's own words; no locale-dependent time formatting in the pure util. */
@Composable
private fun duration(ms: Long): String {
    // Anything under a minute still counts as one, so "0 min in 1 session" is never shown.
    val minutes = ((ms + 59_999L) / 60_000L).coerceAtLeast(1L)
    return if (minutes < 60) stringResource(R.string.v90_played_minutes, minutes)
    else stringResource(R.string.v90_played_hours, minutes / 60L, minutes % 60L)
}

/** 9.0: the time this game was open on this handheld, measured from launch to your return. */
@Composable
fun PlayTimeSection(consoleId: String, fileName: String, viewModel: PlayTimeViewModel = hiltViewModel()) {
    var totals by remember(consoleId, fileName) { mutableStateOf(PlaySessions.Totals.EMPTY) }
    val revision by viewModel.revision.collectAsStateWithLifecycle()
    LaunchedEffect(consoleId, fileName, revision) {
        viewModel.load(consoleId, fileName)
    }
    LaunchedEffect(viewModel) {
        viewModel.totals.collect { totals = it }
    }
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.v90_played_title), icon = R.drawable.ic_devices)
        Panel(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (totals.sessions == 0) stringResource(R.string.v90_played_never)
                else pluralStringResource(R.plurals.v90_played_total, totals.sessions, duration(totals.timePlayedMs), totals.sessions),
                style = MaterialTheme.typography.bodyMedium
            )
            if (totals.firstPlayedMs != null && totals.lastPlayedMs != null) {
                Text(
                    stringResource(
                        R.string.v90_played_dates,
                        java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT).format(totals.firstPlayedMs),
                        java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT).format(totals.lastPlayedMs)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
            }
            Text(stringResource(R.string.v90_played_hint), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
    }
}
