package com.tufein.valkyr.ui.screens.manuals

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

/** Pages of one manual as bitmaps, made on demand on a background dispatcher (a large PDF is never rendered whole). */
interface ManualPageSource : Closeable {
    /** Number of pages; 0 when the file cannot be read. */
    val count: Int
    /** Page [index] fitted to [widthPx]; null when it cannot be drawn. */
    suspend fun render(index: Int, widthPx: Int): Bitmap?
}

/**
 * A PDF through the platform's [PdfRenderer] (public Android API, API 21+). The renderer allows one open page at
 * a time, so rendering is serialised; each page is drawn only when the reader asks for it.
 */
class PdfPageSource private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer
) : ManualPageSource {
    private val lock = Mutex()
    override val count: Int = renderer.pageCount

    override suspend fun render(index: Int, widthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        lock.withLock {
            runCatching {
                renderer.openPage(index.coerceIn(0, count - 1)).use { page ->
                    val (w, h) = fit(page.width, page.height, widthPx)
                    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                }
            }.getOrNull()
        }
    }

    override fun close() { runCatching { renderer.close() }; runCatching { descriptor.close() } }

    companion object {
        /** Opens [file]; null for a file that is not a readable PDF (damaged, or password protected). */
        suspend fun open(file: File): PdfPageSource? = withContext(Dispatchers.IO) {
            var fd: ParcelFileDescriptor? = null
            try {
                fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                PdfPageSource(fd, PdfRenderer(fd))
            } catch (e: Exception) {
                runCatching { fd?.close() }
                null
            }
        }

        /** [widthPx] wide, never more than ~8 megapixels. */
        internal fun fit(pageW: Int, pageH: Int, widthPx: Int): Pair<Int, Int> {
            var w = widthPx.coerceIn(200, 3000)
            var h = (w.toLong() * pageH / pageW.coerceAtLeast(1)).toInt().coerceAtLeast(1)
            val maxPixels = 8_000_000L
            if (w.toLong() * h > maxPixels) {
                val k = Math.sqrt(maxPixels.toDouble() / (w.toLong() * h))
                w = (w * k).toInt().coerceAtLeast(1); h = (h * k).toInt().coerceAtLeast(1)
            }
            return w to h
        }
    }
}

/** A sequence of pictures (jpg, png, webp), one per page, decoded down to the size they are shown at. */
class ImagePageSource(private val files: List<File>) : ManualPageSource {
    override val count: Int = files.size

    override suspend fun render(index: Int, widthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        val file = files.getOrNull(index) ?: return@withContext null
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= widthPx && bounds.outHeight / (sample * 2) >= 200) sample *= 2
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
    }

    override fun close() = Unit
}
