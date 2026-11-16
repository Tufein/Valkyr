package com.tufein.valkyr.util

import java.io.ByteArrayOutputStream

/**
 * Addresses on a WebDAV server: cleaning up what the user typed, building folder and file URLs
 * with properly encoded names, comparing the hrefs a server sends back (absolute or relative,
 * encoded in its own way), and the Nextcloud / ownCloud address shapes
 * (`…/remote.php/dav/files/<user>/`, the older `…/remote.php/webdav/`, or just the server).
 * Pure JVM for the tests.
 */
object WebDavPaths {

    /** The folder used when the user sets none. */
    const val DEFAULT_FOLDER = "Dogmatix"

    /**
     * [input] as a collection URL: trimmed, `https://` assumed when no scheme is given, without
     * query or fragment, ending in `/`. Null when it cannot be an http(s) address.
     */
    fun normalizeServer(input: String): String? {
        // 8.2: an S3 bucket (s3://host/bucket) is addressed path-style over http(s), like any other server here.
        if (S3Target.isS3(input)) return S3Target.parse(input)?.baseUrl
        var s = input.trim()
        if (s.isEmpty() || s.any { it.isWhitespace() && it != ' ' }) return null
        if (!s.contains("://")) s = "https://$s"
        val scheme = s.substringBefore("://").lowercase()
        if (scheme != "http" && scheme != "https") return null
        var rest = s.substringAfter("://").substringBefore('#').substringBefore('?')
        // user:password@ in the address would end up in logs; the login has its own fields.
        val authority = rest.substringBefore('/')
        if (authority.contains('@')) return null
        if (authority.isEmpty() || authority.startsWith(':') || authority.contains(' ')) return null
        rest = rest.replace(Regex("/{2,}"), "/")
        val path = if (rest.contains('/')) rest.substring(rest.indexOf('/')) else "/"
        return "$scheme://${authority.lowercase()}${if (path.endsWith("/")) path else "$path/"}"
    }

    /**
     * [input] without a `user:password@` part in front of the host (the login has its own fields,
     * and an address with a password in it ends up in logs and backups). Everything else as typed.
     */
    fun stripCredentials(input: String): String {
        val trimmed = input.trim()
        val schemeEnd = trimmed.indexOf("://").let { if (it < 0) 0 else it + 3 }
        val authorityEnd = trimmed.indexOf('/', schemeEnd).let { if (it < 0) trimmed.length else it }
        val authority = trimmed.substring(schemeEnd, authorityEnd)
        val at = authority.lastIndexOf('@')
        if (at < 0) return trimmed
        return trimmed.substring(0, schemeEnd) + authority.substring(at + 1) + trimmed.substring(authorityEnd)
    }

    /**
     * The password would travel in clear text: [server] starts with `http://` and its host is not
     * on a private network (localhost, 10.x, 172.16-31.x, 192.168.x, 127.x, link-local 169.254.x,
     * `*.local`, a name without a dot, `fe80::` / `fc00::/7` / `::1`). Plain http on a home
     * network is common and fine; over the internet it is not.
     */
    fun isCleartextRisk(server: String): Boolean {
        val url = normalizeServer(stripCredentials(server)) ?: return false
        if (isHttps(url)) return false
        val host = hostOf(url).removeSurrounding("[", "]")
        if (host.isEmpty()) return false
        if (host == "localhost" || host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".home.arpa") || !host.contains('.') && !host.contains(':')) return false
        if (host.contains(':')) return !(host == "::1" || host.startsWith("fe8") || host.startsWith("fe9") || host.startsWith("fea") || host.startsWith("feb") || host.startsWith("fc") || host.startsWith("fd"))
        val octets = host.split('.').map { it.toIntOrNull() }
        if (octets.size == 4 && octets.all { it != null && it in 0..255 }) {
            val a = octets[0]!!
            val b = octets[1]!!
            val private = a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)
            return !private
        }
        return true
    }

    fun isHttps(url: String): Boolean = url.trim().startsWith("https://", ignoreCase = true)

    /** `scheme://host[:port]` of a normalized URL. */
    fun originOf(url: String): String = url.substringBefore("://") + "://" + url.substringAfter("://").substringBefore('/')

    /** Host of [url] (without port), lower case; empty when there is none. */
    fun hostOf(url: String): String =
        url.trim().substringAfter("://", "").substringBefore('/').substringBefore('?').substringAfterLast('@').let { authority ->
            if (authority.startsWith("[")) authority.substringBefore(']') + "]" else authority.substringBefore(':')
        }.lowercase()

    /** The raw (still encoded) path of [url], starting with `/`. */
    fun rawPathOf(url: String): String {
        val noScheme = if (url.contains("://")) url.substringAfter("://") else return url.substringBefore('?').substringBefore('#').ifEmpty { "/" }
        val i = noScheme.indexOf('/')
        return if (i < 0) "/" else noScheme.substring(i).substringBefore('?').substringBefore('#')
    }

    /** Percent-encodes one path segment: everything but RFC 3986 unreserved characters. */
    fun encodeSegment(segment: String): String {
        val out = StringBuilder()
        for (b in segment.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '.' || ch == '_' || ch == '~') out.append(ch)
            else out.append('%').append(HEX[c shr 4]).append(HEX[c and 0x0F])
        }
        return out.toString()
    }

    /**
     * Decodes `%XX` escapes (UTF-8). A `+` stays a `+` (paths are not form data); a broken escape
     * is kept as it is.
     */
    fun percentDecode(text: String): String {
        if (!text.contains('%')) return text
        val out = ByteArrayOutputStream()
        val bytes = text.toByteArray(Charsets.UTF_8)
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i]
            if (b == '%'.code.toByte() && i + 2 < bytes.size) {
                val hi = Character.digit(bytes[i + 1].toInt().toChar(), 16)
                val lo = Character.digit(bytes[i + 2].toInt().toChar(), 16)
                if (hi >= 0 && lo >= 0) {
                    out.write(hi * 16 + lo)
                    i += 3
                    continue
                }
            }
            out.write(b.toInt())
            i++
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /** "/Games/Dogmatix/" → [Games, Dogmatix]; empty names, "." and ".." are dropped. */
    fun folderSegments(folder: String): List<String> =
        folder.replace('\\', '/').split('/').map { it.trim() }.filter { it.isNotEmpty() && it != "." && it != ".." }

    /** [collection] (ending in `/`) plus encoded [segments]; a trailing `/` when [asCollection]. */
    fun join(collection: String, segments: List<String>, asCollection: Boolean): String {
        val base = if (collection.endsWith("/")) collection else "$collection/"
        if (segments.isEmpty()) return base
        val tail = segments.joinToString("/") { encodeSegment(it) }
        return base + tail + if (asCollection) "/" else ""
    }

    fun child(collection: String, name: String): String = join(collection, listOf(name), asCollection = false)

    fun childCollection(collection: String, name: String): String = join(collection, listOf(name), asCollection = true)

    /** The app's folder on the server: [server] (normalized) + [folder] (default [DEFAULT_FOLDER]); null for a bad address. */
    fun folderUrl(server: String, folder: String): String? {
        val base = normalizeServer(server) ?: return null
        val segments = folderSegments(folder).ifEmpty { listOf(DEFAULT_FOLDER) }
        return join(base, segments, asCollection = true)
    }

    /** The collection that holds [url] (`…/a/b/` → `…/a/`, `…/a/f.txt` → `…/a/`); null at the root. */
    fun parentOf(url: String): String? {
        val trimmed = url.trimEnd('/')
        val origin = originOf(url)
        if (trimmed.length <= origin.length) return null
        return trimmed.substring(0, trimmed.lastIndexOf('/') + 1).takeIf { it.length > origin.length }
    }

    /**
     * The decoded path of an href from a multistatus answer, comparable with [pathKey] of a
     * request URL: absolute URLs lose scheme and host, escapes are decoded, a trailing `/` and
     * repeated slashes are dropped.
     */
    fun pathKey(hrefOrUrl: String): String {
        val raw = rawPathOf(hrefOrUrl.trim())
        val decoded = percentDecode(raw).replace(Regex("/{2,}"), "/")
        return decoded.trimEnd('/').ifEmpty { "/" }
    }

    /** Last name in an href or URL, decoded ("" for the root). */
    fun nameOf(hrefOrUrl: String): String = pathKey(hrefOrUrl).substringAfterLast('/')

    /**
     * Addresses to try for [server], best first. Nextcloud and ownCloud serve files under
     * `/remote.php/dav/files/<user>/` (and the older `/remote.php/webdav/`); users often type
     * just the server, the `/remote.php/dav/` root, or paste a link from the web interface.
     * Any other server is tried as typed first.
     */
    fun candidates(server: String, user: String): List<String> {
        val base = normalizeServer(server) ?: return emptyList()
        val origin = originOf(base)
        val path = base.substring(origin.length)          // starts and ends with '/'
        val lower = path.lowercase()
        val u = user.trim()
        fun files(prefix: String): String? =
            if (u.isEmpty()) null else origin + prefix.trimEnd('/') + "/remote.php/dav/files/" + encodeSegment(u) + "/"
        val out = mutableListOf<String?>()
        val remote = lower.indexOf("/remote.php/")
        when {
            lower.contains("/remote.php/dav/files/") -> out += base
            lower.endsWith("/remote.php/webdav/") -> { out += base; out += files(path.substring(0, remote)) }
            lower.endsWith("/remote.php/dav/") -> { out += files(path.substring(0, remote)); out += origin + path.substring(0, remote) + "/remote.php/webdav/" }
            remote >= 0 -> { out += base; out += files(path.substring(0, remote)) }
            lower.contains("/index.php/apps/") || lower.contains("/apps/files") -> {
                val cut = listOf(lower.indexOf("/index.php/"), lower.indexOf("/apps/")).filter { it >= 0 }.min()
                out += files(path.substring(0, cut))
                out += base
            }
            else -> { out += base; out += files(path) }
        }
        return out.filterNotNull().distinct()
    }

    private val HEX = "0123456789ABCDEF".toCharArray()
}
