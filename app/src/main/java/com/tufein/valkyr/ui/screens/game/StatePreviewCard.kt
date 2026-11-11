package com.tufein.valkyr.ui.screens.game

import android.net.Uri
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

/** 6.5: the picture RetroArch saved with the game's newest state, as a "resume here" reminder. Nothing when there is none. */
@Composable
fun StatePreviewCard(uri: Uri?) {
    if (uri == null) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.state65_title), style = MaterialTheme.typography.titleSmall)
        AsyncImage(
            model = uri,
            contentDescription = stringResource(R.string.state65_description),
            contentScale = ContentScale.Fit,
            modifier = Modifier.heightIn(max = 180.dp).clip(RoundedCornerShape(12.dp)).padding(0.dp)
        )
        Text(stringResource(R.string.state65_hint), style = MaterialTheme.typography.bodySmall)
    }
}
