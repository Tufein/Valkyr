package com.tufein.valkyr.ui.screens.game

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.tufein.valkyr.R
import com.tufein.valkyr.data.service.StatePreviewService
import com.tufein.valkyr.ui.components.ActionPill
import com.tufein.valkyr.ui.components.ActionTone
import com.tufein.valkyr.ui.components.closeOnGamepadB
import com.tufein.valkyr.ui.components.focusRing
import com.tufein.valkyr.ui.components.rememberFocusSource
import com.tufein.valkyr.ui.components.rememberInitialFocus
import com.tufein.valkyr.util.StatePreviews

/**
 * 9.6: every state picture of a game as a row of thumbnails, from the states folder picked in
 * Save sync (nothing is copied, nothing is deleted). A tap, or A on a focused thumbnail, opens it
 * full screen; ◀ ▶ move between pictures, B closes, and one picture at a time can be shared.
 */
@Composable
fun ScreenshotGallery(gameFileName: String, pictures: List<StatePreviewService.Picture>) {
    if (pictures.size < 2) return
    var open by remember(pictures) { mutableStateOf<Int?>(null) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(pluralStringResource(R.plurals.v96_gallery_title, pictures.size, pictures.size), style = MaterialTheme.typography.titleSmall)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(pictures, key = { _, p -> p.uri.toString() }) { index, picture ->
                val source = rememberFocusSource()
                AsyncImage(
                    model = picture.uri,
                    contentDescription = pictureName(picture, gameFileName),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .height(84.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .focusRing(source, cornerRadius = 10.dp)
                        .clickable(interactionSource = source, indication = null) { open = index }
                )
            }
        }
        Text(stringResource(R.string.v96_gallery_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    open?.let { start -> ScreenshotViewer(gameFileName, pictures, start) { open = null } }
}

@Composable
private fun pictureName(picture: StatePreviewService.Picture, gameFileName: String): String {
    val label = StatePreviews.label(picture.name, gameFileName)
    val base = when (label.kind) {
        StatePreviews.Kind.SLOT -> stringResource(R.string.v96_slot, label.slot)
        StatePreviews.Kind.AUTO -> stringResource(R.string.v96_auto)
        StatePreviews.Kind.STATE -> stringResource(R.string.v96_state)
        StatePreviews.Kind.OTHER -> label.raw
    }
    val whenText = picture.modified.takeIf { it > 0 }?.let {
        DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
    }
    return if (whenText == null) base else "$base · $whenText"
}

@Composable
private fun ScreenshotViewer(gameFileName: String, pictures: List<StatePreviewService.Picture>, start: Int, onClose: () -> Unit) {
    var index by remember { mutableStateOf(start.coerceIn(pictures.indices)) }
    val context = LocalContext.current
    val focus = rememberInitialFocus()
    fun move(by: Int) { index = (index + by).mod(pictures.size) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .closeOnGamepadB(onClose)
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.DirectionLeft -> { move(-1); true }
                        Key.DirectionRight -> { move(1); true }
                        else -> false
                    }
                },
            color = MaterialTheme.colorScheme.background
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val picture = pictures[index]
                AsyncImage(
                    model = picture.uri,
                    contentDescription = pictureName(picture, gameFileName),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
                Text(
                    stringResource(R.string.v96_position, index + 1, pictures.size, pictureName(picture, gameFileName)),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                    ActionPill(stringResource(R.string.v96_previous), { move(-1) }, icon = R.drawable.ic_chevron_left)
                    ActionPill(stringResource(R.string.v96_next), { move(1) }, icon = R.drawable.ic_chevron_right)
                    ActionPill(stringResource(R.string.v96_share), { sharePicture(context, picture.uri) }, icon = R.drawable.ic_share, tone = ActionTone.Accent, modifier = Modifier.focusRequester(focus))
                    ActionPill(stringResource(R.string.v96_close), onClose)
                }
            }
        }
    }
}

/** Hands one picture to the share sheet; the receiving app gets read access to that one file only. */
private fun sharePicture(context: Context, uri: Uri) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = android.content.ClipData.newRawUri(null, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
