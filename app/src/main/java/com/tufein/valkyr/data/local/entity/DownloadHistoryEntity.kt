package com.tufein.valkyr.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.tufein.valkyr.data.model.DownloadItemModel
import com.tufein.valkyr.data.model.DownloadStatus
import com.tufein.valkyr.util.PersistedDownloadFailure

/**
 * One row per entry of the Downloads list, so the list survives app restarts.
 * Keeps enough of the indexed file to rebuild a [DownloadableFileEntity] for retries,
 * even if the file has since been re-indexed or removed from the library.
 */
@Entity(tableName = "download_history")
data class DownloadHistoryEntity(
    @PrimaryKey val fileName: String,
    val name: String,
    val consoleId: String,
    val downloadUrl: String,
    val fileSize: Long,
    val fileExtension: String,
    val torrentFileIndex: Int?,
    val torrentMagnet: String?,
    val status: String,
    /** Epoch millis when the download was (last) started. */
    val startedAt: Long,
    /** Epoch millis when it reached COMPLETED / FAILED / STOPPED; null while in progress. */
    val finishedAt: Long?,
    /** Debrid service + its torrent/file ids while a debrid download is in flight, so a retry can resume it. */
    @ColumnInfo(defaultValue = "NULL") val debridProvider: String? = null,
    @ColumnInfo(defaultValue = "NULL") val debridTorrentId: String? = null,
    @ColumnInfo(defaultValue = "NULL") val debridFileId: Int? = null,
    /** Hash the source published for the file (`algorithm:hex`); the finished download is checked against it. */
    @ColumnInfo(defaultValue = "NULL") val expectedHash: String? = null,
    /** Safe diagnostic metadata only; exception text, provider messages and URLs are never stored here. */
    @ColumnInfo(defaultValue = "NULL") val failureCategory: String? = null,
    @ColumnInfo(defaultValue = "NULL") val failureHttpStatusCode: Int? = null,
    @ColumnInfo(defaultValue = "NULL") val failureAt: Long? = null,
    /** The source this download actually came from (the address as configured in Sources). */
    @ColumnInfo(defaultValue = "") val sourceUrl: String = "",
    /** The first source, kept when a failed download is switched to another one. */
    @ColumnInfo(defaultValue = "") val originalSourceUrl: String = "",
    /** A safe, short label of the source switched away from; empty when there was no switch. */
    @ColumnInfo(defaultValue = "") val switchedFrom: String = ""
) {
    fun toEntity(): DownloadableFileEntity = DownloadableFileEntity(
        name = name,
        fileName = fileName,
        consoleId = consoleId,
        downloadUrl = downloadUrl,
        fileSize = fileSize,
        fileExtension = fileExtension,
        torrentFileIndex = torrentFileIndex,
        torrentMagnet = torrentMagnet,
        expectedHash = expectedHash
    )

    /** In-flight statuses can't be resumed after a process death, so they come back as STOPPED. */
    fun toItem(): DownloadItemModel {
        val restored = when (val s = DownloadStatus.valueOf(status)) {
            // PAUSED comes back as STOPPED too: the startup sweep clears the cache its resume relied on.
            DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.COPYING, DownloadStatus.UNZIPPING,
            DownloadStatus.PAUSED -> DownloadStatus.STOPPED
            else -> s
        }
        return DownloadItemModel(
            name = name,
            fileName = fileName,
            downloadSpeed = 0f,
            progress = if (restored == DownloadStatus.COMPLETED) 1f else 0f,
            fileSize = fileSize,
            downloadedBytes = if (restored == DownloadStatus.COMPLETED) fileSize else 0L,
            status = restored,
            startedAt = startedAt,
            finishedAt = finishedAt ?: if (restored == DownloadStatus.STOPPED) startedAt else null,
            failure = if (restored == DownloadStatus.FAILED || restored == DownloadStatus.STOPPED)
                PersistedDownloadFailure.restore(failureCategory, failureHttpStatusCode) else null,
            failureAt = if (restored == DownloadStatus.FAILED || restored == DownloadStatus.STOPPED)
                PersistedDownloadFailure.timestamp(failureAt) else null
        )
    }

    companion object {
        fun from(file: DownloadableFileEntity, item: DownloadItemModel) = DownloadHistoryEntity(
            fileName = file.fileName,
            name = file.name,
            consoleId = file.consoleId,
            downloadUrl = file.downloadUrl,
            fileSize = file.fileSize,
            fileExtension = file.fileExtension,
            torrentFileIndex = file.torrentFileIndex,
            torrentMagnet = file.torrentMagnet,
            status = item.status.name,
            startedAt = item.startedAt,
            finishedAt = item.finishedAt,
            expectedHash = file.expectedHash,
            sourceUrl = file.sourceUrl,
            originalSourceUrl = file.sourceUrl,
            failureCategory = item.failure?.category?.name,
            failureHttpStatusCode = PersistedDownloadFailure.httpCode(item.failure?.httpStatusCode),
            failureAt = if (item.failure != null) PersistedDownloadFailure.timestamp(item.failureAt) else null
        )
    }
}
