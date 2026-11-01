package com.tufein.valkyr.ui.screens.manuals

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tufein.valkyr.R
import com.tufein.valkyr.ui.components.ActionPill
import com.tufein.valkyr.ui.components.closeOnGamepadB
import com.tufein.valkyr.ui.components.rememberInitialFocus
import com.tufein.valkyr.util.Manuals
import com.tufein.valkyr.util.MarkdownLite
import com.tufein.valkyr.util.ReaderState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen reader for a downloaded manual (6.0). PDFs and picture sequences show one page at a time, drawn
 * when needed; text and markdown scroll. Keys: ◀ ▶ or L1 / R1 turn the page, ▲ ▼ move a zoomed page, A or Y
 * zooms, B closes. The last page read is remembered. The page rendering is the platform's PdfRenderer; the
 * key layout follows common gamepad conventions and has not been tried on a handheld here.
 */
@Composable
fun ManualReaderDialog(consoleId: String, fileName: String, doc: Manuals.Doc, vm: ManualsViewModel, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(
            Modifier.fillMaxSize().background(Color.Black).closeOnGamepadB(onClose),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(doc.title, color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                ActionPill(stringResource(R.string.dialog_close), onClose, icon = R.drawable.ic_close)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (doc.kind == Manuals.Kind.TEXT) TextReader(consoleId, fileName, doc, vm)
                else PageReader(consoleId, fileName, doc, vm)
            }
            Text(
                stringResource(if (doc.kind == Manuals.Kind.TEXT) R.string.man60_hint_text else R.string.man60_hint_pages),
                color = Color(0xFFB0B0B0), style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun PageReader(consoleId: String, fileName: String, doc: Manuals.Doc, vm: ManualsViewModel) {
    var source by remember { mutableStateOf<ManualPageSource?>(null) }
    var failed by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf(ReaderState(0)) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val focus = rememberInitialFocus()

    LaunchedEffect(doc) {
        val files = doc.files.map { vm.fileOf(consoleId, fileName, it) }
        val s: ManualPageSource? = if (doc.kind == Manuals.Kind.PDF) PdfPageSource.open(files.first()) else ImagePageSource(files)
        if (s == null || s.count == 0) { failed = true; return@LaunchedEffect }
        source = s
        state = ReaderState(s.count).goTo(vm.lastPage(consoleId, fileName, doc))
    }
    DisposableEffect(doc) { onDispose { source?.close() } }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Twice the screen width, so a zoomed page stays sharp; the platform scales it down otherwise.
        val widthPx = with(LocalDensity.current) { (maxWidth.toPx() * 2).toInt() }
        val boxW = with(LocalDensity.current) { maxWidth.toPx() }
        val boxH = with(LocalDensity.current) { maxHeight.toPx() }

        fun clamp(o: Offset, zoom: Float) = Offset(
            o.x.coerceIn(-(boxW * (zoom - 1) / 2), boxW * (zoom - 1) / 2),
            o.y.coerceIn(-(boxH * (zoom - 1) / 2), boxH * (zoom - 1) / 2)
        )
        fun turn(next: ReaderState) { state = next; offset = Offset.Zero }

        // Draws the shown page, then the next one ahead of time. Only these pages ever exist as bitmaps.
        LaunchedEffect(source, state.current) {
            val s = source ?: return@LaunchedEffect
            bitmap = s.render(state.current, widthPx) ?: bitmap
            vm.saveLastPage(consoleId, fileName, doc, state.current)
            if (!state.atEnd) s.render(state.current + 1, widthPx)
        }

        val page = bitmap
        Box(
            Modifier.fillMaxSize()
                .focusRequester(focus).focusable()
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val step = boxW * 0.2f
                    when (e.key) {
                        Key.ButtonR1, Key.PageDown -> { turn(state.next()); true }
                        Key.ButtonL1, Key.PageUp -> { turn(state.prev()); true }
                        Key.DirectionRight -> { if (state.zoom > 1f) offset = clamp(offset - Offset(step, 0f), state.zoom) else turn(state.next()); true }
                        Key.DirectionLeft -> { if (state.zoom > 1f) offset = clamp(offset + Offset(step, 0f), state.zoom) else turn(state.prev()); true }
                        Key.DirectionDown -> { if (state.zoom > 1f) offset = clamp(offset - Offset(0f, step), state.zoom) else turn(state.next()); true }
                        Key.DirectionUp -> { if (state.zoom > 1f) offset = clamp(offset + Offset(0f, step), state.zoom) else turn(state.prev()); true }
                        Key.ButtonA, Key.ButtonY, Key.Enter, Key.DirectionCenter -> { state = state.zoomCycle(); offset = clamp(offset, state.zoom); true }
                        Key.ButtonL2 -> { state = state.zoomOut(); offset = clamp(offset, state.zoom); true }
                        Key.ButtonR2 -> { state = state.zoomIn(); offset = clamp(offset, state.zoom); true }
                        else -> false
                    }
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        state = state.withZoom(state.zoom * zoom)
                        offset = clamp(offset + pan, state.zoom)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { state = state.zoomCycle(); offset = clamp(offset, state.zoom) },
                        onTap = { p ->
                            if (state.zoom <= 1f) when {
                                p.x < size.width * 0.3f -> turn(state.prev())
                                p.x > size.width * 0.7f -> turn(state.next())
                            }
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            when {
                failed -> Text(stringResource(R.string.man60_unreadable), color = Color.White, modifier = Modifier.padding(24.dp))
                page == null -> CircularProgressIndicator()
                else -> Image(
                    bitmap = page.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        scaleX = state.zoom; scaleY = state.zoom; translationX = offset.x; translationY = offset.y
                    }
                )
            }
            if (state.count > 0) Text(
                state.label(), color = Color.White, fontSize = 12.sp,
                modifier = Modifier.align(Alignment.BottomCenter).background(Color(0x99000000)).padding(horizontal = 10.dp, vertical = 3.dp)
            )
        }
    }
}

@Composable
private fun TextReader(consoleId: String, fileName: String, doc: Manuals.Doc, vm: ManualsViewModel) {
    var blocks by remember { mutableStateOf<List<MarkdownLite.Block>?>(null) }
    val list = rememberLazyListState()
    val focus = rememberInitialFocus()
    val scope = rememberCoroutineScope()
    LaunchedEffect(doc) {
        val file = vm.fileOf(consoleId, fileName, doc.files.first())
        val text = withContext(Dispatchers.IO) { runCatching { file.inputStream().use { readUpTo(it, 2 * 1024 * 1024).toString(Charsets.UTF_8) } }.getOrDefault("") }
        blocks = if (Manuals.isMarkdown(file.name)) MarkdownLite.parse(text) else text.lines().map { MarkdownLite.Block(0, it) }
        list.scrollToItem(vm.lastPage(consoleId, fileName, doc).coerceAtMost((blocks!!.size - 1).coerceAtLeast(0)))
    }
    LaunchedEffect(list) {
        androidx.compose.runtime.snapshotFlow { list.firstVisibleItemIndex }.collect { if (blocks != null) vm.saveLastPage(consoleId, fileName, doc, it) }
    }
    val shown = blocks
    if (shown == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return }
    LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize().focusRequester(focus).focusable().onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val screen = list.layoutInfo.viewportSize.height.toFloat()
            when (e.key) {
                Key.DirectionDown -> { scope.launch { list.animateScrollBy(screen * 0.2f) }; true }
                Key.DirectionUp -> { scope.launch { list.animateScrollBy(-screen * 0.2f) }; true }
                Key.ButtonR1, Key.PageDown, Key.DirectionRight -> { scope.launch { list.animateScrollBy(screen * 0.9f) }; true }
                Key.ButtonL1, Key.PageUp, Key.DirectionLeft -> { scope.launch { list.animateScrollBy(-screen * 0.9f) }; true }
                else -> false
            }
        },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 8.dp)
    ) {
        itemsIndexed(shown) { _, b ->
            Text(
                (if (b.bullet) "• " else "") + b.text,
                color = Color.White,
                fontSize = when (b.level) { 1 -> 24.sp; 2 -> 20.sp; 3 -> 18.sp; else -> 16.sp },
                fontWeight = if (b.level > 0) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.fillMaxWidth().padding(top = if (b.level > 0) 10.dp else 0.dp)
            )
        }
    }
}

/** Reads at most [limit] bytes (InputStream.readNBytes needs API 33; the app supports 29). */
private fun readUpTo(input: java.io.InputStream, limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    while (out.size() < limit) {
        val n = input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}
