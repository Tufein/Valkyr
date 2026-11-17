package com.tufein.valkyr.ui.screens.game

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tufein.valkyr.R
import com.tufein.valkyr.data.local.AppSettings
import com.tufein.valkyr.data.service.CheatsService
import com.tufein.valkyr.ui.components.ActionPill
import com.tufein.valkyr.ui.components.Panel
import com.tufein.valkyr.ui.components.SectionTitle
import com.tufein.valkyr.util.Cheats
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CheatsViewModel @Inject constructor(
    private val service: CheatsService,
    private val settings: AppSettings
) : ViewModel() {
    private val _files = MutableStateFlow<List<String>>(emptyList())
    val files = _files.asStateFlow()
    val hardcore = settings.cheatsHardcore

    fun refresh() { viewModelScope.launch { _files.value = service.files() } }

    fun import(context: Context, uri: Uri) = viewModelScope.launch {
        val name = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        } ?: uri.lastPathSegment.orEmpty()
        runCatching { service.import(uri, name.substringAfterLast('/')) }
        refresh()
    }

    suspend fun parsed(name: String): List<Cheats.Cheat> = service.parsed(name)

    suspend fun enabled(name: String, index: Int): Boolean = service.enabled(name, index)

    fun setEnabled(name: String, index: Int, on: Boolean) {
        viewModelScope.launch { service.setEnabled(name, index, on) }
    }

    fun setHardcore(on: Boolean) { viewModelScope.launch { settings.setCheatsHardcore(on) } }

    fun share(context: Context, name: String) {
        val intent = service.shareIntent(name) ?: return
        runCatching { context.startActivity(Intent.createChooser(intent, context.getString(R.string.v90_cheats_share))) }
    }
}

/** 9.0: cheats the user imported, with the RetroAchievements hardcore guard in plain words. */
@Composable
fun CheatsSection(consoleId: String, fileName: String, viewModel: CheatsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val files by viewModel.files.collectAsStateWithLifecycle()
    val hardcore by viewModel.hardcore.collectAsStateWithLifecycle(initialValue = true)
    var open by remember { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf<List<Cheats.Cheat>>(emptyList()) }
    var enabled by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { viewModel.refresh() }
    LaunchedEffect(open) {
        val name = open
        if (name == null) { entries = emptyList(); enabled = emptySet() }
        else {
            entries = viewModel.parsed(name)
            enabled = buildSet { entries.forEachIndexed { i, _ -> if (viewModel.enabled(name, i)) add(i) } }
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            viewModel.import(context, uri)
            notice = context.getString(R.string.v90_cheats_imported)
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.v90_cheats_title), icon = R.drawable.ic_controller)
        Panel(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (files.isEmpty()) Text(stringResource(R.string.v90_cheats_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            files.forEach { name ->
                ActionPill(name, {
                    open = if (open == name) null else name
                }, modifier = Modifier.heightIn(min = 48.dp), icon = R.drawable.ic_content_copy)
                if (open == name) {
                    entries.forEachIndexed { index, cheat ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(cheat.name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            Switch(
                                checked = enabled.contains(index) && !hardcore,
                                onCheckedChange = { on ->
                                    viewModel.setEnabled(name, index, on)
                                    enabled = if (on) enabled + index else enabled - index
                                },
                                enabled = !hardcore
                            )
                        }
                    }
                    TextButton(onClick = { viewModel.share(context, name) }) { Text(stringResource(R.string.v90_cheats_share)) }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.v90_cheats_hardcore), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Switch(checked = hardcore, onCheckedChange = { viewModel.setHardcore(it) })
            }
            if (hardcore) Text(stringResource(R.string.v90_cheats_hardcore_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.v90_cheats_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            ActionPill(stringResource(R.string.v90_cheats_import), { import.launch(arrayOf("*/*")) }, icon = R.drawable.ic_content_copy)
        }
    }
}
