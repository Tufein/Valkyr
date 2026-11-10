package com.tufein.valkyr.data.service

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.FileProvider
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toBitmap
import coil.Coil
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.tufein.valkyr.R
import com.tufein.valkyr.data.local.dao.DownloadableFileDao
import com.tufein.valkyr.data.repository.WishlistRepository
import com.tufein.valkyr.util.ConsoleFamily
import com.tufein.valkyr.util.ConsoleFormatter
import com.tufein.valkyr.util.GameShareText
import com.tufein.valkyr.util.WishlistShare
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import android.graphics.Path
import com.tufein.valkyr.util.ToastUtil

@EntryPoint
@InstallIn(SingletonComponent::class)
interface GameShareEntryPoint {
    fun covers(): CoverRepository
    fun fileDao(): DownloadableFileDao
    fun wishlist(): WishlistRepository
}

/**
 * Sharing (6.0): a game as a picture card plus a short text with a `valkyr://library` link, and
 * the wishlist as text plus its export file. The card is drawn with android.graphics (no view
 * needed), saved under cache `exports/` (a FileProvider path) and handed to the share sheet.
 *
 * The wishlist share carries no QR code: the app can draw QR codes ([QrCodes]) but its reader only
 * takes source lists, so a wishlist QR could not be imported back.
 */
object GameShare {

    private const val COVER_WAIT_MS = 6_000L
    private val INK = Color.WHITE
    private val GROUND = 0xFF1E1B24.toInt()

    /**
     * Draws the card of a game and opens the share sheet. [fileName] is the library file (cover and
     * tags are looked up with it), [title] the name shown. Never throws: on failure nothing is shared.
     * Call from a coroutine (works off the main thread, opens the sheet on it).
     */
    suspend fun share(context: Context, consoleId: String, fileName: String, title: String) {
        val app = context.applicationContext
        val result = runCatching {
            val entry = EntryPointAccessors.fromApplication(app, GameShareEntryPoint::class.java)
            val tags = withContext(Dispatchers.IO) {
                runCatching {
                    entry.fileDao().filesByFileNames(listOf(fileName)).firstOrNull { it.consoleId == consoleId }
                        ?.let { entry.fileDao().tagsOf(it.id) }
                }.getOrNull().orEmpty()
            }
            val cover = loadCover(app, entry.covers(), consoleId, fileName, title)
            val console = ConsoleFormatter.getConsoleDisplayName(consoleId)
            val file = withContext(Dispatchers.IO) {
                val bitmap = drawCard(app, consoleId, title, console, GameShareText.cardTags(tags), cover)
                val out = File(File(app.cacheDir, "exports").apply { mkdirs() }, GameShareText.fileName(title))
                out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                out
            }
            val message = app.getString(R.string.share6_game_message, title, console)
            val text = GameShareText.withLink(message, GameShareText.deepLink(consoleId, title))
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.provider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, text)
                putExtra(Intent.EXTRA_SUBJECT, title)
                clipData = ClipData.newRawUri(title, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            withContext(Dispatchers.Main) { launchChooser(app, send, app.getString(R.string.share6_chooser_game)) }
        }
        if (result.isFailure) toastFailure(app, R.string.share6_failed)
    }

    /**
     * Shares the wishlist: a text list and the export file (the file another device can import).
     * Does nothing but tell the user when the list is empty.
     */
    suspend fun shareWishlist(context: Context) {
        val app = context.applicationContext
        val result = runCatching {
            val wishlist = EntryPointAccessors.fromApplication(app, GameShareEntryPoint::class.java).wishlist()
            val wishes = wishlist.items.first()
            if (wishes.isEmpty()) return@runCatching false
            val header = app.resources.getQuantityString(R.plurals.share6_wishlist_header, wishes.size, wishes.size)
            val text = GameShareText.wishlistText(
                header,
                wishes.sortedByDescending { it.addedAt }.map { it.title to it.consoleId?.let(ConsoleFormatter::getConsoleDisplayName) }
            ) { app.getString(R.string.share6_wishlist_more, it) }
            val file = withContext(Dispatchers.IO) {
                File(File(app.cacheDir, "exports").apply { mkdirs() }, "valkyr-wishlist.json")
                    .also { it.writeText(WishlistShare.export(wishes), Charsets.UTF_8) }
            }
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.provider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, text)
                putExtra(Intent.EXTRA_SUBJECT, header)
                clipData = ClipData.newRawUri(file.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            withContext(Dispatchers.Main) { launchChooser(app, send, app.getString(R.string.share6_chooser_wishlist)) }
            true
        }
        when {
            result.isFailure -> toastFailure(app, R.string.share6_failed)
            result.getOrNull() == false -> toastFailure(app, R.string.share6_wishlist_empty)
        }
    }

    private fun launchChooser(app: Context, send: Intent, chooserTitle: String) {
        val chooser = Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        app.startActivity(chooser)
    }

    private suspend fun toastFailure(app: Context, res: Int) = withContext(Dispatchers.Main) {
        runCatching { ToastUtil.showError(app, app.getString(res)) }
    }

    /** The cover as a bitmap (software, so it can be drawn on a canvas), or null when none loads. */
    private suspend fun loadCover(app: Context, covers: CoverRepository, consoleId: String, fileName: String, title: String): Bitmap? =
        withTimeoutOrNull(COVER_WAIT_MS) {
            runCatching {
                val url = covers.cached(consoleId, fileName) ?: covers.coverUrl(consoleId, fileName, title) ?: return@runCatching null
                val request = ImageRequest.Builder(app).data(url).allowHardware(false).size(900, 1200).build()
                (Coil.imageLoader(app).execute(request) as? SuccessResult)?.drawable?.toBitmap()
            }.getOrNull()
        }

    private fun drawCard(app: Context, consoleId: String, title: String, console: String, tags: List<String>, cover: Bitmap?): Bitmap {
        val w = GameShareText.CARD_WIDTH
        val h = GameShareText.CARD_HEIGHT
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val base = ConsoleFamily.of(consoleId).argb.toInt()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        // Background: the console-coloured diagonal gradient, as behind a cover in the app.
        val from = ColorUtils.blendARGB(GROUND, base, 0.55f)
        val to = ColorUtils.blendARGB(GROUND, base, 0.18f)
        paint.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), from, to, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        paint.shader = null

        // Cover (or the placeholder) with a soft shadow.
        val box = GameShareText.coverBox()
        val rect = RectF(box.left, box.top, box.right, box.bottom)
        val radius = 44f
        paint.color = Color.argb(90, 0, 0, 0)
        canvas.drawRoundRect(RectF(rect.left, rect.top + 18f, rect.right, rect.bottom + 18f), radius, radius, paint)
        val save = canvas.save()
        val clip = Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) }
        canvas.clipPath(clip)
        if (cover != null) {
            val src = GameShareText.centerCrop(cover.width, cover.height, box)
            canvas.drawBitmap(cover, Rect(src.left.toInt(), src.top.toInt(), src.right.toInt(), src.bottom.toInt()), rect, paint)
        } else {
            paint.shader = LinearGradient(
                rect.left, rect.top, rect.right, rect.bottom,
                ColorUtils.blendARGB(GROUND, base, 0.85f), ColorUtils.blendARGB(GROUND, base, 0.4f), Shader.TileMode.CLAMP
            )
            canvas.drawRect(rect, paint)
            paint.shader = null
            val label = ConsoleFormatter.getConsoleShortName(consoleId)
            val labelPaint = textPaint(120f, Typeface.create(Typeface.DEFAULT, Typeface.BOLD), Color.argb(230, 255, 255, 255))
            labelPaint.textSize = GameShareText.fitSize(120f, labelPaint.measureText(label), rect.width() - 80f, 40f).also { labelPaint.textSize = it }
            canvas.drawText(label, rect.centerX(), rect.centerY() - (labelPaint.ascent() + labelPaint.descent()) / 2f, labelPaint)
        }
        canvas.restoreToCount(save)

        // Title: up to two lines, shrunk to fit.
        val titlePaint = textPaint(68f, Typeface.create(Typeface.DEFAULT, Typeface.BOLD), INK)
        val lines = wrap(title, titlePaint, w - 160f, 2)
        var y = rect.bottom + 110f
        lines.forEach { line ->
            titlePaint.textSize = GameShareText.fitSize(68f, titlePaint.apply { textSize = 68f }.measureText(line), w - 160f, 36f)
            canvas.drawText(line, w / 2f, y, titlePaint)
            y += 82f
        }

        // Console name.
        val consolePaint = textPaint(42f, Typeface.DEFAULT, Color.argb(215, 255, 255, 255))
        canvas.drawText(console, w / 2f, y - 8f, consolePaint)

        // Tag chips, centred in one row.
        if (tags.isNotEmpty()) {
            val chipPaint = textPaint(32f, Typeface.create(Typeface.DEFAULT, Typeface.BOLD), INK)
            val padX = 26f
            val gap = 16f
            val widths = tags.map { chipPaint.measureText(it) + padX * 2 }
            var x = (w - (widths.sum() + gap * (tags.size - 1))) / 2f
            val chipTop = y + 36f
            paint.color = Color.argb(70, 255, 255, 255)
            tags.forEachIndexed { i, tag ->
                canvas.drawRoundRect(RectF(x, chipTop, x + widths[i], chipTop + 62f), 31f, 31f, paint)
                canvas.drawText(tag, x + widths[i] / 2f, chipTop + 43f, chipPaint)
                x += widths[i] + gap
            }
        }

        // Footer.
        val footer = textPaint(36f, Typeface.create(Typeface.DEFAULT, Typeface.BOLD), Color.argb(200, 255, 255, 255))
        canvas.drawText(app.getString(R.string.share6_card_footer), w / 2f, h - 56f, footer)
        return bitmap
    }

    private fun textPaint(size: Float, face: Typeface, color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        typeface = face
        this.color = color
        textAlign = Paint.Align.CENTER
    }

    /** [text] cut into at most [maxLines] lines no wider than [maxWidth]; the last one ends with an ellipsis when cut. */
    private fun wrap(text: String, paint: Paint, maxWidth: Float, maxLines: Int): List<String> {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return listOf("")
        val lines = mutableListOf<String>()
        var current = ""
        for (word in words) {
            val next = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(next) <= maxWidth || current.isEmpty()) current = next
            else { lines += current; current = word }
        }
        lines += current
        if (lines.size <= maxLines) return lines
        val kept = lines.take(maxLines).toMutableList()
        kept[maxLines - 1] = lines.drop(maxLines - 1).joinToString(" ").let { rest ->
            var s = rest
            while (s.length > 1 && paint.measureText("$s…") > maxWidth) s = s.dropLast(1)
            "${s.trimEnd()}…"
        }
        return kept
    }
}
