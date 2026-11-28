package com.tufein.valkyr.ui.screens.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tufein.valkyr.R
import com.tufein.valkyr.data.local.entity.DownloadableFileEntity
import com.tufein.valkyr.data.service.FingerprintService
import com.tufein.valkyr.ui.components.ActionPill
import com.tufein.valkyr.ui.components.ActionTone
import com.tufein.valkyr.ui.components.Panel
import com.tufein.valkyr.ui.components.SectionTitle
import com.tufein.valkyr.util.Fingerprints
import com.tufein.valkyr.util.CollectionExport
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FingerprintUi(
    val running: Boolean = false,
    val progress: String = "",
    val results: List<Fingerprints.Fingerprint>? = null,
    val failed: Boolean = false
)

@HiltViewModel
class FingerprintViewModel @Inject constructor(private val service: FingerprintService) : ViewModel() {
    private val _ui = MutableStateFlow(FingerprintUi())
    val ui = _ui.asStateFlow()
    private var job: Job? = null

    fun start(file: DownloadableFileEntity) {
        if (job?.isActive == true) return
        _ui.value = FingerprintUi(running = true)
        job = viewModelScope.launch {
            try {
                var lastShown = 0L
                val result = service.compute(file) { _, bytes, name ->
                    // Not every 256 KB: a screen update per second is plenty.
                    val now = System.currentTimeMillis()
                    if (now - lastShown > 500) { lastShown = now; _ui.value = _ui.value.copy(progress = "$name · ${CollectionExport.humanSize(bytes)}") }
                }
                _ui.value = FingerprintUi(results = result, failed = result.isEmpty())
            } catch (e: CancellationException) {
                _ui.value = FingerprintUi()
                throw e
            } catch (_: Exception) {
                _ui.value = FingerprintUi(failed = true)
            }
        }
    }

    fun cancel() { job?.cancel() }
}

/**
 * 10.0: the CRC32, MD5 and SHA-1 of the game's files, on request, with a copy button. For comparing a
 * dump with a DAT or Redump entry. Reads only; a large disc image takes a while and can be stopped.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FingerprintCard(file: DownloadableFileEntity, viewModel: FingerprintViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    Panel(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionTitle(stringResource(R.string.v100_fp_title), icon = R.drawable.ic_key)
        Text(stringResource(R.string.v100_fp_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (ui.running) {
            Text(stringResource(R.string.v100_fp_reading, ui.progress), style = MaterialTheme.typography.bodySmall)
        }
        if (ui.failed) {
            Text(stringResource(R.string.v100_fp_failed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        ui.results?.forEach { f ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(f.name, style = MaterialTheme.typography.labelLarge)
                Text("CRC32  ${f.crc32}\nMD5    ${f.md5}\nSHA-1  ${f.sha1}", style = MaterialTheme.typography.bodySmall)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ui.running) {
                ActionPill(stringResource(R.string.v100_fp_stop), viewModel::cancel, tone = ActionTone.Neutral)
            } else {
                ActionPill(stringResource(if (ui.results == null) R.string.v100_fp_start else R.string.v100_fp_again), { viewModel.start(file) }, icon = R.drawable.ic_key, tone = ActionTone.Accent)
            }
            val results = ui.results
            if (!results.isNullOrEmpty()) {
                ActionPill(stringResource(R.string.v100_fp_copy), { clipboard.setText(AnnotatedString(Fingerprints.format(results))) }, icon = R.drawable.ic_content_copy)
            }
        }
    }
}
