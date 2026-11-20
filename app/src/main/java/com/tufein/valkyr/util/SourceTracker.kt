package com.tufein.valkyr.util

import com.tufein.valkyr.data.model.Manufacturer
import com.tufein.valkyr.data.state.SourceScanResult

/** One source in the Sources tracker, with both records joined and already reduced to safe labels. */
data class SourceTrackerRow(
    val consoleId: String,
    /** Position of this address in its console's list, the handle for switching it on or off. */
    val index: Int,
    val consoleName: String,
    val manufacturerName: String,
    val url: String,
    val enabled: Boolean,
    val kind: SourceKind,
    val label: String,
    val games: Int?,
    val scanFailure: FailureKind?,
    val scanAt: Long?,
    val scanTrend: SourceHealth.Trend?,
    val record: SourceRecord?,
    val downloadTrend: DownloadTrend?,
    val score: Int
)

/** Pure join of the configured sources with their scan and download history, for the tracker screen. */
object SourceTracker {
    fun rows(
        manufacturers: List<Manufacturer>,
        results: Map<String, SourceScanResult>,
        records: Map<String, SourceRecord>,
        now: Long
    ): List<SourceTrackerRow> {
        val rows = manufacturers.flatMap { manufacturer ->
            manufacturer.consoles.flatMap { console ->
                console.urls.mapIndexed { index, entry ->
                    val result = results[scanKey(console.id, entry.url)]
                    val record = records[entry.url]
                    SourceTrackerRow(
                        consoleId = console.id,
                        index = index,
                        consoleName = console.name,
                        manufacturerName = manufacturer.name,
                        url = entry.url,
                        enabled = entry.enabled,
                        kind = SourceKind.of(entry.url),
                        label = SourceRanking.label(entry.url),
                        games = result?.files,
                        scanFailure = result?.failure,
                        scanAt = result?.at,
                        scanTrend = result?.let { SourceHealth.trend(it.history, now) },
                        record = record,
                        downloadTrend = SourceRanking.trend(record, now),
                        score = SourceRanking.score(record, now)
                    )
                }
            }
        }
        return rows.sortedWith(compareBy<SourceTrackerRow> { !it.enabled }.thenBy { it.score }.thenBy { it.label.lowercase() })
    }

    /** Whether a source is worth pausing: its last scan failed, or its downloads keep failing. */
    fun needsAttention(row: SourceTrackerRow, now: Long): Boolean =
        row.enabled && (row.scanFailure != null || SourceRanking.isDemoted(row.record, now))

    /**
     * The sources a "Pause failing sources" would switch off. A console always keeps at least one
     * enabled source that is not itself failing, so pausing never leaves a console with nothing to
     * download from; a console where every enabled source fails is left alone.
     */
    fun pausable(rows: List<SourceTrackerRow>, now: Long): List<SourceTrackerRow> =
        rows.groupBy { it.consoleId }.values.flatMap { group ->
            val healthy = group.count { it.enabled && !needsAttention(it, now) }
            if (healthy == 0) emptyList() else group.filter { needsAttention(it, now) }
        }
}

/** Kept local so this object needs no Android dependency; the same shape SourceScanResults uses. */
private fun scanKey(consoleId: String, url: String): String = "$consoleId|$url"
