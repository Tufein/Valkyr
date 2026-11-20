package com.tufein.valkyr.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.tufein.valkyr.data.local.entity.DownloadHistoryEntity

@Dao
interface DownloadHistoryDao {

    @Query("SELECT * FROM download_history ORDER BY startedAt ASC")
    suspend fun getAll(): List<DownloadHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: DownloadHistoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<DownloadHistoryEntity>)

    /** Restore from a backup: rows already in the list win over the backed-up ones. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMissing(entries: List<DownloadHistoryEntity>)

    @Query("UPDATE download_history SET status = :status, finishedAt = :finishedAt WHERE fileName = :fileName")
    suspend fun updateStatus(fileName: String, status: String, finishedAt: Long?)

    @Query("UPDATE download_history SET status = :status, finishedAt = :finishedAt, failureCategory = :failureCategory, failureHttpStatusCode = :failureHttpStatusCode, failureAt = :failureAt WHERE fileName = :fileName")
    suspend fun updateStatusAndFailure(fileName: String, status: String, finishedAt: Long?, failureCategory: String?, failureHttpStatusCode: Int?, failureAt: Long?)

    @Query("UPDATE download_history SET status = :status, startedAt = :startedAt, finishedAt = NULL, failureCategory = NULL, failureHttpStatusCode = NULL, failureAt = NULL WHERE fileName = :fileName")
    suspend fun markRestarted(fileName: String, status: String, startedAt: Long)

    /** [markRestarted] for many rows in one statement (keep [fileNames] under SQLite's variable limit). */
    @Query("UPDATE download_history SET status = :status, startedAt = :startedAt, finishedAt = NULL, failureCategory = NULL, failureHttpStatusCode = NULL, failureAt = NULL WHERE fileName IN (:fileNames)")
    suspend fun markRestartedAll(fileNames: List<String>, status: String, startedAt: Long)

    @Query("UPDATE download_history SET fileSize = :size WHERE fileName = :fileName")
    suspend fun setFileSize(fileName: String, size: Long)

    @Query("SELECT * FROM download_history WHERE fileName = :fileName LIMIT 1")
    suspend fun getByFileName(fileName: String): DownloadHistoryEntity?

    @Query("UPDATE download_history SET debridProvider = :provider, debridTorrentId = :torrentId, debridFileId = :fileId WHERE fileName = :fileName")
    suspend fun setDebrid(fileName: String, provider: String?, torrentId: String?, fileId: Int?)

    /** Set after a REPLACE upsert so a history rewrite cannot erase the first source or a switch. */
    @Query("UPDATE download_history SET sourceUrl = :sourceUrl, originalSourceUrl = :originalSourceUrl, switchedFrom = :switchedFrom WHERE fileName = :fileName")
    suspend fun setProvenance(fileName: String, sourceUrl: String, originalSourceUrl: String, switchedFrom: String)

    @Query("DELETE FROM download_history WHERE fileName = :fileName")
    suspend fun delete(fileName: String)
}
