package com.tufein.valkyr.util

import java.net.URI

/** Decides whether a URL is a file of the configured RomM server (6.0). Pure. */
object ServerUrlGuard {

    /**
     * True when [url] has the same scheme, host and port as [base], lies below the path of [base], and
     * has no `..` segment (plain or percent-encoded) and no backslash or control character. A string
     * prefix test is not enough: `https://romm.lan.evil.com/` starts with `https://romm.lan`.
     */
    fun isOnServer(base: String, url: String): Boolean {
        val b = parse(base.trim().trimEnd('/')) ?: return false
        val u = parse(url.trim()) ?: return false
        if (u.rawUserInfo != null) return false
        if (!b.scheme.equals(u.scheme, ignoreCase = true)) return false
        if (!b.host.equals(u.host, ignoreCase = true)) return false
        if (portOf(b) != portOf(u)) return false
        val path = u.rawPath.orEmpty()
        if (path.any { it == '\\' || it.code < 0x20 }) return false
        val segments = path.split('/').map { decode(it) }
        if (segments.any { it == ".." || it == "." || it.contains('/') || it.contains('\\') }) return false
        val basePath = b.rawPath.orEmpty().trimEnd('/')
        return path.startsWith("$basePath/")
    }

    private fun parse(text: String): URI? =
        runCatching { URI(text) }.getOrNull()?.takeIf { it.scheme != null && !it.host.isNullOrEmpty() && (it.scheme.equals("http", true) || it.scheme.equals("https", true)) }

    private fun portOf(u: URI): Int = if (u.port != -1) u.port else if (u.scheme.equals("https", true)) 443 else 80

    private fun decode(segment: String): String =
        runCatching { java.net.URLDecoder.decode(segment.replace("+", "%2B"), "UTF-8") }.getOrDefault(segment)
}
