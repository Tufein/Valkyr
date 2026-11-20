package com.tufein.valkyr.util

import java.net.URI
import java.net.URLEncoder

/** One user-configured announce address and whether it is switched on. */
data class TrackerEntry(val url: String, val enabled: Boolean = true)

/**
 * The user's own torrent trackers (Sources tracker): a small, validated list that is added to every
 * torrent Valkyr fetches, alongside the trackers the source itself lists. Pure JVM for the tests.
 */
object TorrentTrackers {
    const val ORDER_KEY = "torrent_tracker_order"
    const val DISABLED_KEY = "torrent_tracker_disabled"
    /** A personal list is small on purpose: more trackers slow metadata fetching, not less. */
    const val MAX_TRACKERS = 8
    const val MAX_LENGTH = 512

    val SETTING_TYPES = mapOf(ORDER_KEY to "s", DISABLED_KEY to "ss")

    /** null when this cannot be an announce address. No credentials, no control characters. */
    fun validate(raw: String): TrackerEntry? {
        val url = normalize(raw)
        if (url.isEmpty() || url.length > MAX_LENGTH || url.any { it.isISOControl() }) return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme !in setOf("http", "https", "udp")) return null
        if (uri.userInfo != null || uri.fragment != null) return null
        val host = uri.host ?: return null
        if (host.isBlank() || host.any { it.isISOControl() }) return null
        return TrackerEntry(url)
    }

    fun normalize(raw: String): String = raw.trim().trimEnd('/')

    fun encode(entries: List<TrackerEntry>): String = entries.joinToString("\n") { it.url }

    /** [order] is newline-separated; [disabled] the URLs switched off. Bad/duplicate rows are dropped. */
    fun decode(order: String?, disabled: Set<String>?): List<TrackerEntry> {
        val seen = HashSet<String>()
        return order.orEmpty().lineSequence().mapNotNull { validate(it) }
            .filter { seen.add(it.url) }
            .take(MAX_TRACKERS)
            .map { it.copy(enabled = it.url !in (disabled.orEmpty())) }
            .toList()
    }

    fun add(entries: List<TrackerEntry>, raw: String): List<TrackerEntry>? {
        val entry = validate(raw) ?: return null
        if (entries.any { it.url.equals(entry.url, ignoreCase = true) } || entries.size >= MAX_TRACKERS) return null
        return entries + entry
    }

    fun setEnabled(entries: List<TrackerEntry>, url: String, on: Boolean): List<TrackerEntry> =
        entries.map { if (it.url == url) it.copy(enabled = on) else it }

    fun remove(entries: List<TrackerEntry>, url: String): List<TrackerEntry> = entries.filterNot { it.url == url }

    /** The URLs handed to libtorrent (only those switched on). */
    fun enabled(entries: List<TrackerEntry>): List<String> = entries.filter { it.enabled }.map { it.url }

    /**
     * A magnet with [trackers] appended as `tr=` parameters, URL-encoded. Used by the debrid route,
     * which does not pass through libtorrent. The libtorrent route adds the same list to its handle.
     */
    fun appendToMagnet(magnet: String, trackers: List<String>): String {
        if (!magnet.trim().startsWith("magnet:", ignoreCase = true) || trackers.isEmpty()) return magnet
        val suffix = trackers.joinToString("") { "&tr=" + URLEncoder.encode(it, "UTF-8") }
        return magnet + suffix
    }
}
