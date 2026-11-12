package com.tufein.valkyr.ui.screens.sources.components

import android.content.Context
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.tufein.valkyr.R
import com.tufein.valkyr.ui.components.ActionPill
import com.tufein.valkyr.ui.components.DialogButton
import com.tufein.valkyr.ui.components.IconTile
import com.tufein.valkyr.ui.components.closeOnGamepadB
import com.tufein.valkyr.ui.components.focusRing
import com.tufein.valkyr.ui.components.rememberFocusSource
import com.tufein.valkyr.ui.components.rememberInitialFocus
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** QR codes for the source list: drawing them, and reading them back from a photo. */
object QrCodes {

    /**
     * The code as a bitmap of at most [maxPx] pixels wide, every module the same whole number of
     * pixels: drawn 1:1 on screen it stays sharp, where scaling by an odd factor makes modules of
     * uneven width that cameras (and ZXing) cannot read.
     */
    fun bitmap(content: String, maxPx: Int): Bitmap {
        val matrix = QRCodeWriter().encode(
            content, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 4)
        )
        val scale = (maxPx / matrix.width).coerceAtLeast(1)
        val size = matrix.width * scale
        val pixels = IntArray(size * size) { i ->
            if (matrix.get((i % size) / scale, (i / size) / scale)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    /**
     * The text of the QR code in the picture at [uri], or null when none can be read. ZXing's finder
     * sometimes misses a perfectly sharp code at one size and finds it at another, so the picture
     * is tried at a few sizes and with both binarizers, and finally as a "pure" code (a screenshot
     * or a tight crop).
     */
    fun read(context: Context, uri: Uri): String? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2000) sample *= 2
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        decode(bitmap)
    }.getOrNull()

    fun decode(bitmap: Bitmap): String? {
        val reader = MultiFormatReader().apply {
            setHints(mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
        }
        fun attempt(b: Bitmap): String? {
            val pixels = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
            val source = RGBLuminanceSource(b.width, b.height, pixels)
            return runCatching { reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text }.getOrNull()
                ?: runCatching { reader.decodeWithState(BinaryBitmap(GlobalHistogramBinarizer(source))).text }.getOrNull()
        }
        for (divisor in listOf(1, 2, 3, 4)) {
            val w = bitmap.width / divisor
            val h = bitmap.height / divisor
            if (minOf(w, h) < 150) break
            val scaled = if (divisor == 1) bitmap else Bitmap.createScaledBitmap(bitmap, w, h, false)
            attempt(scaled)?.let { return it }
        }
        val pixels = IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
        return runCatching {
            MultiFormatReader().decode(
                BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels))),
                mapOf(DecodeHintType.PURE_BARCODE to true, DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))
            ).text
        }.getOrNull()
    }
}

/** The source list as QR codes, one at a time when it needs several. */
@Composable
fun QrShowDialog(parts: List<String>, onDismiss: () -> Unit) {
    var index by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val maxPx = with(density) { 300.dp.roundToPx() }
    val bitmap = remember(index, parts, maxPx) { QrCodes.bitmap(parts[index], maxPx).asImageBitmap() }
    val closeFocus = rememberInitialFocus()
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconTile(R.drawable.ic_qr_code, size = 36.dp)
                Text(stringResource(R.string.qr_show_title))
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.qr_show_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // Drawn at its own pixel size (no scaling), with the quiet zone inside the bitmap.
                Box(Modifier.background(Color.White)) {
                    Image(bitmap, contentDescription = null, filterQuality = FilterQuality.None, modifier = Modifier.size(with(density) { bitmap.width.toDp() }))
                }
                if (parts.size > 1) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ActionPill(stringResource(R.string.q5_previous), { index = (index - 1 + parts.size) % parts.size }, icon = R.drawable.ic_chevron_left)
                    Text(stringResource(R.string.qr_part, index + 1, parts.size), style = MaterialTheme.typography.titleMedium)
                    ActionPill(stringResource(R.string.q5_next), { index = (index + 1) % parts.size }, icon = R.drawable.ic_chevron_right)
                }
            }
        },
        confirmButton = { DialogButton(text = stringResource(R.string.dialog_close), onClick = onDismiss, initialFocus = closeFocus) }
    )
}

/** Share menu of the Sources screen: as a file, as QR codes, or read QR codes from photos. */
@Composable
fun ShareSourcesDialog(onFile: () -> Unit, onShowQr: () -> Unit, onReadQrCamera: () -> Unit, onReadQrPictures: () -> Unit, onDismiss: () -> Unit) {
    val fileFocus = rememberInitialFocus()
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.share_sources_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.share_sources_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ShareOption(R.drawable.ic_share, stringResource(R.string.share_sources_file), onFile, Modifier.focusRequester(fileFocus))
                ShareOption(R.drawable.ic_qr_code, stringResource(R.string.share_sources_qr), onShowQr)
                ShareOption(R.drawable.ic_photo_camera, stringResource(R.string.share_sources_read_camera), onReadQrCamera)
                ShareOption(R.drawable.ic_photos, stringResource(R.string.share_sources_read_pictures), onReadQrPictures)
            }
        },
        confirmButton = { DialogButton(text = stringResource(R.string.dialog_close), onClick = onDismiss) }
    )
}

/** One way to move the source list: an icon tile and a line, the whole row focusable. */
@Composable
private fun ShareOption(icon: Int, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val source = rememberFocusSource()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .focusRing(source, cornerRadius = 12.dp)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        IconTile(icon, size = 36.dp)
        Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
    }
}
