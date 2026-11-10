package com.tufein.valkyr.data.service

import android.app.ActivityManager
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.os.ParcelFileDescriptor
import com.tufein.valkyr.BuildConfig
import com.tufein.valkyr.data.local.dao.DownloadableFileDao
import com.tufein.valkyr.data.repository.DownloadableFileRepository
import com.tufein.valkyr.data.repository.SettingsRepository
import com.tufein.valkyr.util.PerfCheck
import com.tufein.valkyr.util.PerfCheck.Metric
import com.tufein.valkyr.util.PerfCheck.Outcome
import com.tufein.valkyr.util.PerfCheck.Skip
import com.tufein.valkyr.util.PerfLog
import com.tufein.valkyr.util.StorageHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Random
import javax.inject.Inject
import javax.inject.Singleton
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.OutputStream

/**
 * 4.7.0: the measuring half of the Performance check tool. Each function times one area on the
 * real data of the device (library database, cached covers, the download folder) and returns
 * [Outcome]s; nothing here keeps a title, a path or a key. The numbers are turned into words by
 * [PerfCheck], and the frame sample (which needs the window) is taken by the screen.
 */
@Singleton
class PerfCheckService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val repository: DownloadableFileRepository,
    private val fileDao: DownloadableFileDao,
    private val settings: SettingsRepository,
    private val diagnostics: DiagnosticsService
) {

    /** What the storage test would do, for the preview before it runs. */
    data class StoragePreview(val folderSet: Boolean, val usable: Boolean, val freeBytes: Long?) {
        val allowed: Boolean get() = folderSet && usable && PerfCheck.storageTestAllowed(freeBytes)
    }

    /** The storage test's numbers, and the hidden test file when it could not be deleted again. */
    data class StorageRun(val outcomes: List<Outcome>, val leftoverName: String?)

    /** The start-up number PerfLog already holds for this run. */
    fun startup(): Outcome? = PerfLog.phases.get("first_frame")?.let { Outcome.Measured(Metric.STARTUP, it.toDouble()) }

    /** Memory and device facts; [refreshRate] is the display's, read from the window by the caller. */
    fun deviceFacts(refreshRate: Float): PerfCheck.DeviceFacts {
        val am = context.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        return PerfCheck.DeviceFacts(
            apiLevel = Build.VERSION.SDK_INT,
            model = Build.MODEL.orEmpty(),
            totalRamBytes = info.totalMem,
            appHeapClassMb = am?.memoryClass ?: 0,
            largeHeapClassMb = am?.largeMemoryClass ?: 0,
            lowRamDevice = am?.isLowRamDevice ?: false,
            refreshRate = refreshRate,
            cores = Runtime.getRuntime().availableProcessors()
        )
    }

    suspend fun libraryGames(): Int? = withContext(Dispatchers.IO) { runCatching { fileDao.getFilesCount() }.getOrNull() }

    // ---- Database --------------------------------------------------------------------------

    /**
     * Search for a common word, the first page of the list, the count, and the Home shelves'
     * reading of the library (all consoles and their file names), each on the real library: one
     * cold run (the first, which also warms SQLite's page cache) and [WARM_RUNS] warm ones; the
     * value is the median of the warm runs, the first run is kept as the extra.
     */
    suspend fun databaseChecks(): List<Outcome> = withContext(Dispatchers.IO) {
        val games = try { fileDao.getFilesCount() } catch (e: CancellationException) { throw e } catch (_: Exception) {
            return@withContext DB_METRICS.map { Outcome.Skipped(it, Skip.FAILED) }
        }
        if (games == 0) return@withContext DB_METRICS.map { Outcome.Skipped(it, Skip.NO_LIBRARY) }
        listOf(
            timeRuns(Metric.DB_SEARCH) { repository.searchFilesWithTags(SEARCH_WORD) },
            timeRuns(Metric.DB_FIRST_PAGE) { repository.searchFilesWithTags("") },
            timeRuns(Metric.DB_COUNT) { fileDao.getFilesCount() },
            timeRuns(Metric.DB_SHELVES) {
                // What ShelvesViewModel reads for Home: every console with files, then the names of each.
                repository.getConsolesWithFiles("").forEach { fileDao.fileNamesFor(it.id) }
            }
        )
    }

    private suspend fun timeRuns(metric: Metric, block: suspend () -> Unit): Outcome = try {
        val runs = ArrayList<Double>(WARM_RUNS + 1)
        repeat(WARM_RUNS + 1) {
            currentCoroutineContext().ensureActive()
            val start = System.nanoTime()
            block()
            runs += (System.nanoTime() - start) / 1_000_000.0
        }
        Outcome.Measured(metric, PerfCheck.median(runs.drop(1)), extra = runs.first(), count = runs.size)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        Outcome.Skipped(metric, Skip.FAILED)
    }

    // ---- Covers ----------------------------------------------------------------------------

    /**
     * Decodes up to [PerfCheck.COVER_SAMPLE] images from Coil's disk cache folder at list-tile
     * size and reports the median time per cover (the slowest as the extra). Read-only: it only
     * opens files in the app's own cache folder; a file that is not an image is passed over.
     */
    suspend fun coverCheck(): Outcome = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "image_cache")
        val candidates = runCatching { dir.listFiles() }.getOrNull().orEmpty()
            .filter { it.isFile && it.length() > MIN_COVER_BYTES && !it.name.startsWith("journal") }
            .sortedBy { it.name }
        val times = ArrayList<Double>()
        var examined = 0
        for (file in candidates) {
            if (times.size >= PerfCheck.COVER_SAMPLE || examined >= MAX_EXAMINED) break
            currentCoroutineContext().ensureActive()
            examined++
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            runCatching { BitmapFactory.decodeFile(file.path, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) continue
            val options = BitmapFactory.Options().apply { inSampleSize = PerfCheck.sampleSize(bounds.outWidth, bounds.outHeight) }
            val start = System.nanoTime()
            val bitmap = runCatching { BitmapFactory.decodeFile(file.path, options) }.getOrNull() ?: continue
            times += (System.nanoTime() - start) / 1_000_000.0
            bitmap.recycle()
        }
        if (times.isEmpty()) Outcome.Skipped(Metric.COVER_DECODE, Skip.NO_COVERS)
        else Outcome.Measured(Metric.COVER_DECODE, PerfCheck.median(times), extra = times.max(), count = times.size)
    }

    // ---- Storage ---------------------------------------------------------------------------

    /** What the preview dialog needs: whether a folder is set and writable, and how much room it has. */
    suspend fun storagePreview(): StoragePreview = withContext(Dispatchers.IO) {
        val folder = settings.downloadDirectory.first()
        if (folder.isBlank()) return@withContext StoragePreview(folderSet = false, usable = false, freeBytes = null)
        StoragePreview(
            folderSet = true,
            usable = StorageHelper.isValidUri(context, folder),
            freeBytes = StorageHelper.getFreeBytes(context, folder)
        )
    }

    /** The room left in the download folder, as a result row; null when it cannot be read. */
    suspend fun freeSpace(): Outcome? = storagePreview().freeBytes?.let { Outcome.Measured(Metric.FREE_SPACE, it.toDouble()) }

    /**
     * Writes one [PerfCheck.TEST_FILE_BYTES] hidden temporary file into the download folder (a new
     * file with a random name: nothing existing is touched), forces it to the storage, reads it back
     * and deletes it again, whatever happens. Run only after the player confirmed the preview.
     */
    suspend fun storageCheck(): StorageRun = withContext(Dispatchers.IO) {
        val folder = settings.downloadDirectory.first()
        val preview = storagePreview()
        val metrics = listOf(Metric.STORAGE_WRITE, Metric.STORAGE_READ)
        fun skipped(reason: Skip) = StorageRun(metrics.map { Outcome.Skipped(it, reason) }, null)
        when {
            !preview.folderSet -> return@withContext skipped(Skip.NO_FOLDER)
            !preview.usable -> return@withContext skipped(Skip.FOLDER_UNUSABLE)
            !PerfCheck.storageTestAllowed(preview.freeBytes) -> return@withContext skipped(Skip.NOT_ENOUGH_SPACE)
        }
        val name = PerfCheck.testFileName(Random().nextLong())
        val doc = StorageHelper.createFile(context, folder, "", name, overwrite = false)
            ?: return@withContext skipped(Skip.FAILED)
        var leftover: String? = null
        var result: StorageRun? = null
        try {
            val buffer = ByteArray(CHUNK).also { Random(SEED).nextBytes(it) }
            val chunks = (PerfCheck.TEST_FILE_BYTES / CHUNK).toInt()

            val writeStart = System.nanoTime()
            openWrite(doc.uri).use { out ->
                repeat(chunks) { currentCoroutineContext().ensureActive(); out.write(buffer) }
                out.flush()
                (out as? ParcelFileDescriptor.AutoCloseOutputStream)?.fd?.sync()
            }
            val writeNanos = System.nanoTime() - writeStart

            val readStart = System.nanoTime()
            var total = 0L
            val sink = ByteArray(CHUNK)
            (context.contentResolver.openInputStream(doc.uri) ?: throw IOException("Could not read the test file")).use { input ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(sink)
                    if (n < 0) break
                    total += n
                }
            }
            val readNanos = System.nanoTime() - readStart
            if (total != PerfCheck.TEST_FILE_BYTES) throw IOException("The test file came back at a different size")

            result = StorageRun(
                listOf(
                    Outcome.Measured(Metric.STORAGE_WRITE, PerfCheck.throughputMiBPerSecond(PerfCheck.TEST_FILE_BYTES, writeNanos)),
                    Outcome.Measured(Metric.STORAGE_READ, PerfCheck.throughputMiBPerSecond(PerfCheck.TEST_FILE_BYTES, readNanos))
                ),
                null
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A failed write or read: the row says so, the finally block still removes the file.
        } finally {
            // Also when cancelled: the temporary file must not stay in the player's ROM folder.
            withContext(NonCancellable) { leftover = removeTestFile(doc, name) }
        }
        (result ?: skipped(Skip.FAILED)).copy(leftoverName = leftover)
    }

    /** Deletes the test file; returns its name when it is still there afterwards. */
    private fun removeTestFile(doc: DocumentFile, name: String): String? =
        if (StorageHelper.deleteFile(doc) || !runCatching { doc.exists() }.getOrDefault(true)) null else name

    /** The write stream of the test file: a real file descriptor where the provider has one (so it can be synced), else a plain stream. */
    private fun openWrite(uri: Uri): OutputStream {
        val pfd = runCatching { context.contentResolver.openFileDescriptor(uri, "w") }.getOrNull()
        if (pfd != null) return ParcelFileDescriptor.AutoCloseOutputStream(pfd)
        return context.contentResolver.openOutputStream(uri, "w") ?: throw IOException("Could not write the test file")
    }

    // ---- The report ------------------------------------------------------------------------

    /** The shared text: the measured numbers and PerfLog's own block (start-up marks, slow calls, cover cache use). */
    fun report(device: PerfCheck.DeviceFacts, outcomes: List<Outcome>, frames: PerfCheck.FrameSummary?, libraryGames: Int?, leftoverName: String?): String {
        val perf = PerfLog.report(PerfLog.phases.all(), PerfLog.ops.slowest(10), PerfLog.coverCounts(), databaseBytes())
        val base = PerfCheck.report(
            appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${if (BuildConfig.DEBUG) "debug" else "release"}",
            device = device, outcomes = outcomes, frames = frames, perfLogBlock = perf, libraryGames = libraryGames
        )
        return if (leftoverName == null) base else base + "Note: the test file $leftoverName could not be deleted from the download folder.\n"
    }

    /** Hands [text] to the share sheet through the Diagnose sharing (cache file, redaction, chooser). */
    suspend fun share(text: String, chooserTitle: String) = diagnostics.shareText("valkyr-performance.txt", text, chooserTitle)

    private fun databaseBytes(): Long = runCatching {
        val db = context.getDatabasePath("dogmatix_db")
        listOf(db, File(db.path + "-wal")).sumOf { if (it.exists()) it.length() else 0L }
    }.getOrDefault(-1L)

    private companion object {
        val DB_METRICS = listOf(Metric.DB_SEARCH, Metric.DB_FIRST_PAGE, Metric.DB_COUNT, Metric.DB_SHELVES)
        const val SEARCH_WORD = "the"
        const val WARM_RUNS = 3
        const val CHUNK = 1024 * 1024
        const val SEED = 47L
        const val MIN_COVER_BYTES = 2_048L
        const val MAX_EXAMINED = 400
    }
}
