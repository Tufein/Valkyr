package com.tufein.valkyr.util

import com.tufein.valkyr.data.local.entity.DownloadableFileEntity
import com.tufein.valkyr.data.model.DownloadSourceKind
import com.tufein.valkyr.data.model.DownloadStatus
import java.net.URI

/** Rules shared by the download menu and the manual source selector. */
object DownloadSourcePolicy {
    fun canChange(status: DownloadStatus): Boolean = status in setOf(
        DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.PAUSED,
        DownloadStatus.FAILED, DownloadStatus.STOPPED
    )

    /** The queue is keyed by exact file name. Another console/version is never a replacement. */
    fun sameFile(current: DownloadableFileEntity, next: DownloadableFileEntity): Boolean =
        current.consoleId == next.consoleId && current.fileName == next.fileName &&
            SourceRanking.sameGame(
                SourceRanking.Copy(current.consoleId, current.fileName, current.name, emptyList(), current.fileSize),
                SourceRanking.Copy(next.consoleId, next.fileName, next.name, emptyList(), next.fileSize)
            )

    fun kind(file: DownloadableFileEntity): DownloadSourceKind = when {
        file.isTorrent -> DownloadSourceKind.TORRENT
        RommSource.isSource(file.sourceUrl) || RommSource.romIdOf(file.downloadUrl) != null -> DownloadSourceKind.ROMM
        else -> DownloadSourceKind.WEB
    }

    /** Only a parsed host, never URI user-info, query parameters, paths or magnet display names. */
    fun host(file: DownloadableFileEntity): String = sequenceOf(file.sourceUrl, file.downloadUrl)
        .mapNotNull { address -> runCatching {
            URI(address.trim()).takeIf { it.scheme.equals("http", true) || it.scheme.equals("https", true) }?.host
        }.getOrNull() }
        .firstOrNull().orEmpty().removePrefix("www.")

    fun safeLabel(file: DownloadableFileEntity): String = host(file).ifEmpty {
        when (kind(file)) {
            DownloadSourceKind.TORRENT -> "Torrent"
            DownloadSourceKind.ROMM -> "RomM"
            DownloadSourceKind.WEB -> "Source"
        }
    }

    /** A user-visible label for a stored source address: a host or a kind, never a key or a path. */
    fun safeLabel(url: String): String {
        val u = url.trim()
        if (u.isEmpty()) return "Source"
        return when (SourceKind.of(u)) {
            SourceKind.WEB -> runCatching { URI(u).host }.getOrNull().orEmpty().removePrefix("www.")
            SourceKind.ROMM -> "RomM"
            SourceKind.TORRENT -> "Torrent"
            SourceKind.OTHER -> "Source"
        }.ifEmpty { "Source" }
    }
}
