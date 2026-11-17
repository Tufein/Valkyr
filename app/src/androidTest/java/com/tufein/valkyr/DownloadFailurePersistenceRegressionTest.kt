package com.tufein.valkyr

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.tufein.valkyr.data.local.DogmatixDatabase
import com.tufein.valkyr.data.local.entity.DownloadHistoryEntity
import com.tufein.valkyr.data.local.entity.DownloadableFileEntity
import com.tufein.valkyr.data.model.DownloadFailure
import com.tufein.valkyr.data.model.DownloadFailureCategory
import com.tufein.valkyr.data.model.DownloadItemModel
import com.tufein.valkyr.data.model.DownloadStatus
import com.tufein.valkyr.data.service.DownloadProgressTracker
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Rebuild the old table rather than depending on Android's optional DROP COLUMN support. */
internal fun restoreVersion14DownloadHistorySchema(db: SQLiteDatabase) {
    val columns = "fileName,name,consoleId,downloadUrl,fileSize,fileExtension,torrentFileIndex,torrentMagnet,status,startedAt,finishedAt,debridProvider,debridTorrentId,debridFileId,expectedHash"
    db.execSQL("CREATE TABLE download_history_v14 (fileName TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, consoleId TEXT NOT NULL, downloadUrl TEXT NOT NULL, fileSize INTEGER NOT NULL, fileExtension TEXT NOT NULL, torrentFileIndex INTEGER, torrentMagnet TEXT, status TEXT NOT NULL, startedAt INTEGER NOT NULL, finishedAt INTEGER, debridProvider TEXT DEFAULT NULL, debridTorrentId TEXT DEFAULT NULL, debridFileId INTEGER DEFAULT NULL, expectedHash TEXT DEFAULT NULL)")
    db.execSQL("INSERT INTO download_history_v14 ($columns) SELECT $columns FROM download_history")
    db.execSQL("DROP TABLE download_history")
    db.execSQL("ALTER TABLE download_history_v14 RENAME TO download_history")
}

class DownloadFailurePersistenceRegressionTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun migrationPreservesOldHistoryAndSafeFailuresSurviveReopening() = runBlocking {
        val name = "failure-migration-${UUID.randomUUID()}"
        fun open() = Room.databaseBuilder(context, DogmatixDatabase::class.java, name)
            .addMigrations(DogmatixDatabase.MIGRATION_14_15, DogmatixDatabase.MIGRATION_15_16, DogmatixDatabase.MIGRATION_16_17).build()
        val file = file("legacy.gba")
        try {
            open().also { db ->
                db.downloadHistoryDao().upsert(DownloadHistoryEntity.from(file, row(file).copy(status = DownloadStatus.FAILED)))
                db.close()
            }
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { old ->
                restoreVersion14DownloadHistorySchema(old)
                old.execSQL("DELETE FROM room_master_table")
                old.version = 14
            }
            open().also { db ->
                val dao = db.downloadHistoryDao()
                val legacy = dao.getAll().single()
                assertEquals(file.downloadUrl, legacy.downloadUrl)
                assertEquals(DownloadStatus.FAILED, legacy.toItem().status)
                assertNull(legacy.toItem().failure)
                val tracker = DownloadProgressTracker(dao)
                tracker.restore(listOf(legacy.toItem()))
                tracker.updateDownloadStatus(file.fileName, DownloadStatus.FAILED, failure = DownloadFailure(DownloadFailureCategory.HTTP_RATE_LIMITED, 429))
                withTimeout(5_000) {
                    while (dao.getByFileName(file.fileName)?.failureCategory != DownloadFailureCategory.HTTP_RATE_LIMITED.name) delay(20)
                }
                assertNotNull(dao.getAll().single().failureAt)
                db.close()
            }
            open().also { db ->
                val restored = db.downloadHistoryDao().getAll().single().toItem()
                assertEquals(DownloadFailure(DownloadFailureCategory.HTTP_RATE_LIMITED, 429), restored.failure)
                assertNotNull(restored.failureAt)
                db.close()
            }
            Unit
        } finally { context.deleteDatabase(name) }
    }

    @Test fun rapidBatchRetryAndCompletionClearEarlierFailuresInRoom() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, DogmatixDatabase::class.java).build()
        try {
            val dao = db.downloadHistoryDao()
            val tracker = DownloadProgressTracker(dao)
            val files = (0 until 100).map { file("failure-$it.gba") }
            val initial = files.map(::row)
            dao.upsertAll(files.zip(initial) { file, item -> DownloadHistoryEntity.from(file, item) })
            tracker.addDownloads(initial)
            files.forEach { tracker.updateDownloadStatus(it.fileName, DownloadStatus.FAILED, failure = DownloadFailure(DownloadFailureCategory.TIMEOUT)) }
            assertTrue(tracker.getDownloads().all { it.failureAt != null })
            tracker.resetDownloadsForRetry(files.map { it.fileName })
            assertTrue(tracker.getDownloads().all { it.failure == null && it.failureAt == null })
            files.forEach { tracker.updateDownloadStatus(it.fileName, DownloadStatus.COMPLETED) }
            withTimeout(10_000) {
                while (dao.getAll().any { it.status != DownloadStatus.COMPLETED.name || it.failureCategory != null || it.failureHttpStatusCode != null || it.failureAt != null }) delay(20)
            }
            assertTrue(dao.getAll().all { it.toItem().failure == null && it.toItem().failureAt == null })
            Unit
        } finally { db.close() }
    }

    @Test fun bothRestartQueriesClearPersistedDiagnosticFields() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, DogmatixDatabase::class.java).build()
        try {
            val dao = db.downloadHistoryDao()
            val entries = listOf(file("one.gba"), file("two.gba")).map { file ->
                DownloadHistoryEntity.from(file, row(file).copy(status = DownloadStatus.FAILED, failure = DownloadFailure(DownloadFailureCategory.HTTP_SERVER, 503), failureAt = 1234L))
            }
            dao.upsertAll(entries)
            dao.markRestarted("one.gba", DownloadStatus.DOWNLOADING.name, 2000L)
            dao.markRestartedAll(listOf("two.gba"), DownloadStatus.DOWNLOADING.name, 2000L)
            assertTrue(dao.getAll().all { it.failureCategory == null && it.failureHttpStatusCode == null && it.failureAt == null })
            Unit
        } finally { db.close() }
    }

    private fun file(name: String) = DownloadableFileEntity(name = name, fileName = name, consoleId = "gba", downloadUrl = "https://example.invalid/$name", fileSize = 100L, fileExtension = "gba")
    private fun row(file: DownloadableFileEntity) = DownloadItemModel(file.name, file.fileName, 0f, 0f, file.fileSize, status = DownloadStatus.DOWNLOADING, startedAt = 1000L)
}
