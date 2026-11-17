package com.tufein.valkyr.ui.screens.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tufein.valkyr.R
import com.tufein.valkyr.data.service.GameOverrideService
import com.tufein.valkyr.util.MetadataOverrides
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FixMatchViewModel @Inject constructor(private val overrides: GameOverrideService) : ViewModel() {
    suspend fun load(consoleId: String, fileName: String) = overrides.get(consoleId, fileName)
    fun save(consoleId: String, fileName: String, fields: com.tufein.valkyr.data.local.entity.GameOverrideEntity) {
        viewModelScope.launch { overrides.save(consoleId, fileName, fields) }
    }
    fun clear(consoleId: String, fileName: String) {
        viewModelScope.launch { overrides.clear(consoleId, fileName) }
    }
}

/**
 * 9.0: correct the fields a provider got wrong, per game. Empty means "leave automatic"; a saved
 * correction lives under `consoleId|stem`, survives a rescan and travels in a backup.
 */
@Composable
fun FixMatchDialog(
    consoleId: String,
    fileName: String,
    title: String,
    released: String,
    genre: String,
    onDismiss: () -> Unit,
    viewModel: FixMatchViewModel = hiltViewModel()
) {
    var titleText by remember(consoleId, fileName) { mutableStateOf("") }
    var yearText by remember(consoleId, fileName) { mutableStateOf("") }
    var genreText by remember(consoleId, fileName) { mutableStateOf("") }
    var coverText by remember(consoleId, fileName) { mutableStateOf("") }
    LaunchedEffect(consoleId, fileName) {
        val saved = viewModel.load(consoleId, fileName)
        titleText = saved?.title.orEmpty()
        yearText = saved?.year?.toString().orEmpty()
        genreText = saved?.genre.orEmpty()
        coverText = saved?.pinnedCoverUrl.orEmpty()
    }
    val save: () -> Unit = {
        viewModel.save(consoleId, fileName, MetadataOverrides.fields(titleText, yearText, genreText, coverText))
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.v90_fix_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.v90_fix_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(titleText, { titleText = it }, label = { Text(stringResource(R.string.v90_fix_name, title)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(yearText, { yearText = it }, label = { Text(stringResource(R.string.v90_fix_year, released.take(4))) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(genreText, { genreText = it }, label = { Text(stringResource(R.string.v90_fix_genre, genre)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(coverText, { coverText = it }, label = { Text(stringResource(R.string.v90_fix_cover)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = save) { Text(stringResource(R.string.v90_fix_save)) } },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = { viewModel.clear(consoleId, fileName); onDismiss() }) {
                Text(stringResource(R.string.v90_fix_reset))
            }
        }
    )
}
