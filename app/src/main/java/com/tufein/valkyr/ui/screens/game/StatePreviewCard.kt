package com.tufein.valkyr.ui.screens.game

import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.tufein.valkyr.R
import com.tufein.valkyr.util.StatePreviews

/**
 * 6.5: the picture RetroArch saved with the game's newest state, as a "resume here" reminder.
 * 7.3: and a line with the game's newest save and when it was written, also when no picture
 * exists (a battery save, another emulator). Nothing when there is neither.
 */
@Composable
fun StatePreviewCard(uri: Uri?, save: StatePreviews.SaveLine? = null) {
    if (uri == null && save == null) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (uri != null) {
            Text(stringResource(R.string.state65_title), style = MaterialTheme.typography.titleSmall)
            AsyncImage(
                model = uri,
                contentDescription = stringResource(R.string.state65_description),
                contentScale = ContentScale.Fit,
                modifier = Modifier.heightIn(max = 180.dp).clip(RoundedCornerShape(12.dp)).padding(0.dp)
            )
            Text(stringResource(R.string.state65_hint), style = MaterialTheme.typography.bodySmall)
        }
        if (save != null) {
            val whenText = save.modified.takeIf { it > 0 }?.let {
                DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
            }
            Text(
                when {
                    whenText == null -> stringResource(if (save.state) R.string.saves73_last_state_undated else R.string.saves73_last_save_undated, save.name)
                    else -> stringResource(if (save.state) R.string.saves73_last_state else R.string.saves73_last_save, whenText, save.name)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
