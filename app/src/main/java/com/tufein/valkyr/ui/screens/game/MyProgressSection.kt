package com.tufein.valkyr.ui.screens.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tufein.valkyr.R
import com.tufein.valkyr.data.service.GameProgressService
import com.tufein.valkyr.ui.components.Panel
import com.tufein.valkyr.ui.components.SectionTitle
import com.tufein.valkyr.util.PlayStatus
import com.tufein.valkyr.util.ProgressEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MyProgressViewModel @Inject constructor(private val service: GameProgressService) : ViewModel() {
    suspend fun load(consoleId: String, fileName: String): ProgressEntry? = service.entry(consoleId, fileName)

    fun save(consoleId: String, fileName: String, status: PlayStatus?, rating: Int) {
        viewModelScope.launch { service.set(consoleId, fileName, status, rating) }
    }
}

/** The text of a status, shared with the sync-conflict list. */
@Composable
fun playStatusLabel(status: PlayStatus?): String = when (status) {
    PlayStatus.PLAYING -> stringResource(R.string.v83_status_playing)
    PlayStatus.FINISHED -> stringResource(R.string.v83_status_finished)
    PlayStatus.COMPLETED -> stringResource(R.string.v83_status_completed)
    PlayStatus.DROPPED -> stringResource(R.string.v83_status_dropped)
    null -> stringResource(R.string.v83_value_none)
}

/**
 * 8.3: "My progress" on the game page: a play status and one to five stars, kept per profile on this
 * handheld and shared with the others through device sync. Each chip and star is a focusable button, so
 * it works with the D-pad. Tapping the chosen status or star again clears it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MyProgressSection(consoleId: String, fileName: String, viewModel: MyProgressViewModel = hiltViewModel()) {
    var status by remember(consoleId, fileName) { mutableStateOf<PlayStatus?>(null) }
    var rating by remember(consoleId, fileName) { mutableStateOf(0) }
    LaunchedEffect(consoleId, fileName) {
        viewModel.load(consoleId, fileName)?.let { status = it.status; rating = it.rating }
    }
    fun save(s: PlayStatus?, r: Int) { status = s; rating = r; viewModel.save(consoleId, fileName, s, r) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.v83_progress_title), icon = R.drawable.ic_check_circle)
        Panel(Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PlayStatus.entries.forEach { option ->
                    FilterChip(
                        selected = status == option,
                        onClick = { save(if (status == option) null else option, rating) },
                        label = { Text(playStatusLabel(option)) },
                        modifier = Modifier.heightIn(min = 48.dp)
                    )
                }
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(stringResource(R.string.v83_rating_label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                (1..5).forEach { star ->
                    val description = stringResource(R.string.v83_star_cd, star)
                    TextButton(
                        onClick = { save(status, if (rating == star) 0 else star) },
                        modifier = Modifier.widthIn(min = 48.dp).heightIn(min = 48.dp).semantics { contentDescription = description }
                    ) {
                        Text(
                            if (star <= rating) "★" else "☆",
                            style = MaterialTheme.typography.titleLarge,
                            color = if (star <= rating) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Text(stringResource(R.string.v83_progress_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
