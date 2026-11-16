package com.tufein.valkyr.data.service

import com.tufein.valkyr.BuildConfig
import com.tufein.valkyr.util.DavException
import com.tufein.valkyr.util.DavFile
import com.tufein.valkyr.util.DavProblem
import com.tufein.valkyr.util.DavStore
import com.tufein.valkyr.util.S3Signer
import com.tufein.valkyr.util.S3Target
import com.tufein.valkyr.util.S3Xml
import com.tufein.valkyr.util.WebDavPaths
import com.tufein.valkyr.util.WebDavStatus
import com.tufein.valkyr.util.WebDavXml
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * 8.2: an S3-compatible bucket behind the same [DavStore] interface the WebDAV client implements,
 * so backups, device sync and game transfer run on it unchanged. Addresses are path-style
 * `https://host/bucket/key`; "folders" are key prefixes (nothing to create, nothing to leave
 * behind); `/`-delimited listings give the members. Every call is blocking and fails with a
 * [DavException] whose [DavProblem] the screens already know.
 *
 * Conditional writes use `If-None-Match: *` and `If-Match` on PutObject; a store that ignores them is
 * handled by [com.tufein.valkyr.util.DavConditionalWriter]'s read-before and read-back checks.
 * Nothing here logs a key.
 */
class S3Client(
    private val target: S3Target,
    private val accessKey: String,
    private val secretKey: String,
    private val http: OkHttpClient = WebDavClient.sharedHttp,
    private val clock: () -> Instant = Instant::now
) : DavStore {

    @Volatile private var finalUrl: String = ""
    override val lastUrl: String get() = finalUrl

    private val bucketPath = "/" + S3Signer.encode(target.bucket) + "/"

    /** The object key behind [url] (decoded, no leading slash); empty for the bucket root. */
    private fun keyOf(url: String): String {
        val path = WebDavPaths.pathKey(url) + if (url.endsWith("/")) "/" else ""
        val bucket = "/" + target.bucket
        if (!path.startsWith(bucket)) throw DavException(DavProblem.BAD_URL)
        return path.removePrefix(bucket).removePrefix("/").let { if (url.endsWith("/") && it.isNotEmpty() && !it.endsWith("/")) "$it/" else it }
    }

    private fun urlOf(key: String): String = target.baseUrl + S3Signer.encode(key, keepSlash = true)

    override fun propfind(url: String, depth: Int): List<WebDavXml.Entry> {
        val key = keyOf(url)
        if (key.isEmpty() || key.endsWith("/")) {
            val members = list(url)
            val self = WebDavXml.Entry(url, WebDavPaths.pathKey(url), WebDavPaths.nameOf(url), isCollection = true)
            return if (depth == 0) listOf(self) else listOf(self) + members
        }
        val entry = head(key) ?: throw DavException(DavProblem.NOT_FOUND, 404)
        return listOf(entry)
    }

    override fun list(url: String): List<WebDavXml.Entry> {
        val prefix = keyOf(url).let { if (it.isEmpty() || it.endsWith("/")) it else "$it/" }
        val out = ArrayList<WebDavXml.Entry>()
        var token: String? = null
        do {
            val query = buildList {
                add("list-type" to "2"); add("delimiter" to "/"); add("prefix" to prefix)
                token?.let { add("continuation-token" to it) }
            }
            val listing = request("GET", "", query, null, emptyMap()).use { r ->
                if (r.code != 200) throw failure(r)
                S3Xml.parseListing(readText(r, MAX_LISTING_BYTES))
            }
            for (item in listing.items) {
                if (item.key == prefix) continue
                val href = urlOf(item.key)
                out += WebDavXml.Entry(href, WebDavPaths.pathKey(href), item.key.substringAfterLast('/'), false, item.size, item.lastModified, item.etag?.let { "\"$it\"" })
            }
            for (p in listing.prefixes) {
                val href = urlOf(p)
                out += WebDavXml.Entry(href, WebDavPaths.pathKey(href), p.trimEnd('/').substringAfterLast('/'), true)
            }
            token = listing.nextToken
        } while (token != null && out.size < MAX_ENTRIES)
        return out
    }

    override fun exists(url: String): Boolean {
        val key = keyOf(url)
        if (key.isEmpty() || key.endsWith("/")) return list(url).isNotEmpty()
        return head(key) != null
    }

    private fun head(key: String): WebDavXml.Entry? = request("HEAD", key, emptyList(), null, emptyMap()).use { r ->
        when (r.code) {
            200 -> {
                val href = urlOf(key)
                WebDavXml.Entry(href, WebDavPaths.pathKey(href), key.substringAfterLast('/'), false, r.header("Content-Length")?.toLongOrNull(),
                    r.header("Last-Modified")?.let { WebDavXml.parseDate(it) }, WebDavStatus.strongEtag(r.header("ETag")), r.header("Content-Type"))
            }
            404, 410 -> null
            else -> throw failure(r)
        }
    }

    override fun get(url: String, maxBytes: Long): DavFile? = request("GET", keyOf(url), emptyList(), null, emptyMap()).use { r ->
        when (r.code) {
            200 -> DavFile(readCapped(r, maxBytes), WebDavStatus.strongEtag(r.header("ETag")))
            404, 410 -> null
            else -> throw failure(r)
        }
    }

    override fun put(url: String, bytes: ByteArray, contentType: String, ifMatch: String?, ifNoneMatch: Boolean): String? {
        val headers = buildMap {
            ifMatch?.let { put("If-Match", it) }
            if (ifNoneMatch) put("If-None-Match", "*")
        }
        request("PUT", keyOf(url), emptyList(), bytes.toRequestBody(contentType.toMediaType()), headers, S3Signer.sha256Hex(bytes)).use { r ->
            if (r.code in 200..299) return WebDavStatus.strongEtag(r.header("ETag"))
            throw failure(r)
        }
    }

    /** There are no folders: a "collection" appears with the first object written below it. */
    override fun ensureCollection(url: String, root: String): Boolean = false

    /** Copy, then delete: S3 has no rename. The new copy is checked before the old one goes. */
    override fun move(from: String, to: String): Boolean {
        val source = "/" + S3Signer.encode(target.bucket) + "/" + S3Signer.encode(keyOf(from), keepSlash = true)
        request("PUT", keyOf(to), emptyList(), EMPTY, mapOf("x-amz-copy-source" to source)).use { r ->
            // CopyObject can answer 200 with an error in the body.
            val body = if (r.code in 200..299) readText(r, MAX_LISTING_BYTES) else ""
            if (r.code !in 200..299) throw failure(r)
            if (body.contains("<Error>")) throw DavException(DavProblem.SERVER, r.code, S3Xml.parseError(body)?.code)
        }
        delete(from)
        return true
    }

    override fun delete(url: String) {
        request("DELETE", keyOf(url), emptyList(), null, emptyMap()).use { r ->
            if (r.code in 200..299 || r.code == 404 || r.code == 410) return
            throw failure(r)
        }
    }

    override fun putStream(url: String, size: Long, open: () -> InputStream, ifNoneMatch: Boolean) {
        // The hash comes first, in one pass over the file; the body is read again when sending.
        val digest = MessageDigest.getInstance("SHA-256")
        open().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        val headers = if (ifNoneMatch) mapOf("If-None-Match" to "*") else emptyMap()
        request("PUT", keyOf(url), emptyList(), StreamBody(size, open), headers, hash, bulk).use { r ->
            if (r.code in 200..299) return
            throw failure(r)
        }
    }

    override fun <T> getStream(url: String, read: (InputStream) -> T): T? =
        request("GET", keyOf(url), emptyList(), null, emptyMap(), client = bulk).use { r ->
            when (r.code) {
                200 -> try {
                    read(r.body?.byteStream() ?: throw DavException(DavProblem.BAD_RESPONSE, 200, "no body"))
                } catch (e: DavException) { throw e } catch (e: IOException) { throw WebDavClient.problemOf(e) }
                404, 410 -> null
                else -> throw failure(r)
            }
        }

    /** CopyObject cannot refuse an existing target, so a caller that needs that writes the file itself. */
    override fun moveNoReplace(from: String, to: String): Boolean = false

    private val bulk: OkHttpClient by lazy { http.newBuilder().readTimeout(10, TimeUnit.MINUTES).build() }

    private class StreamBody(private val size: Long, private val open: () -> InputStream) : RequestBody() {
        override fun contentType() = OCTET
        override fun contentLength(): Long = size
        override fun writeTo(sink: BufferedSink) {
            val written = open().use { input -> sink.writeAll(input.source()) }
            if (written != size) throw IOException("File changed while it was being sent")
        }
    }

    /** One signed request against the bucket; [key] "" is the bucket itself (a listing). */
    private fun request(
        method: String,
        key: String,
        query: List<Pair<String, String>>,
        body: RequestBody?,
        headers: Map<String, String>,
        payloadHash: String = S3Signer.EMPTY_SHA256,
        client: OkHttpClient = http
    ): Response {
        val path = bucketPath + S3Signer.encode(key, keepSlash = true)
        val canonicalPath = if (key.isEmpty()) bucketPath.trimEnd('/').ifEmpty { "/" } else path
        val amzDate = DATE.format(clock())
        val signed = S3Signer.sign(method, target.host, canonicalPath, query, headers.filterKeys { it.startsWith("x-amz-") }, payloadHash, accessKey, secretKey, target.region, amzDate)
        val queryText = S3Signer.canonicalQuery(query)
        val url = "${target.scheme}://${target.host}$canonicalPath" + if (queryText.isEmpty()) "" else "?$queryText"
        val request = try {
            val builder = Request.Builder().url(url).method(method, body ?: if (method == "PUT") EMPTY else null)
                .header("User-Agent", "Valkyr/${BuildConfig.VERSION_NAME}")
            headers.forEach { (k, v) -> builder.header(k, v) }
            signed.forEach { (k, v) -> builder.header(k, v) }
            builder.build()
        } catch (e: IllegalArgumentException) {
            throw DavException(DavProblem.BAD_URL)
        }
        return try {
            client.newCall(request).execute().also { finalUrl = it.request.url.toString() }
        } catch (e: IOException) {
            throw WebDavClient.problemOf(e)
        }
    }

    private fun failure(response: Response): DavException {
        val error = runCatching { response.body?.string()?.take(MAX_ERROR_BYTES)?.let(S3Xml::parseError) }.getOrNull()
        return S3Errors.classify(response.code, error)
    }

    private fun readText(response: Response, max: Long): String = readCapped(response, max).toString(Charsets.UTF_8)

    private fun readCapped(response: Response, max: Long): ByteArray {
        val body = response.body ?: return ByteArray(0)
        if (body.contentLength() > max) throw DavException(DavProblem.BAD_RESPONSE, response.code, "too large")
        val out = ByteArrayOutputStream()
        try {
            body.byteStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (out.size() + n > max) throw DavException(DavProblem.BAD_RESPONSE, response.code, "too large")
                    out.write(buffer, 0, n)
                }
            }
        } catch (e: DavException) { throw e } catch (e: IOException) { throw WebDavClient.problemOf(e) }
        return out.toByteArray()
    }

    companion object {
        private val OCTET = "application/octet-stream".toMediaType()
        private val EMPTY: RequestBody = ByteArray(0).toRequestBody()
        private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
        private const val MAX_LISTING_BYTES = 8L * 1024 * 1024
        private const val MAX_ENTRIES = 20_000
        private const val MAX_ERROR_BYTES = 4096
    }
}

/** What an S3 status plus its error code mean in the terms the cloud screens already have. Pure. */
object S3Errors {
    fun classify(status: Int, error: S3Xml.Error?): DavException {
        val code = error?.code.orEmpty()
        val problem = when {
            code == "InvalidAccessKeyId" || code == "SignatureDoesNotMatch" || code == "InvalidToken" || status == 401 -> DavProblem.AUTH
            code == "AccessDenied" || code == "AllAccessDisabled" -> DavProblem.FORBIDDEN
            code == "NoSuchBucket" -> DavProblem.NOT_FOUND
            code == "PreconditionFailed" || code == "ConditionalRequestConflict" || status == 412 -> DavProblem.PRECONDITION
            code == "EntityTooLarge" || status == 413 -> DavProblem.TOO_LARGE
            code == "PermanentRedirect" || code == "AuthorizationHeaderMalformed" || status in 301..308 -> DavProblem.REDIRECT
            code == "RequestTimeTooSkewed" -> DavProblem.HTTP
            code == "SlowDown" || code == "ServiceUnavailable" || status == 503 -> DavProblem.SERVER
            code == "QuotaExceeded" || code == "StorageLimitExceeded" || status == 507 -> DavProblem.NO_SPACE
            else -> WebDavStatus.classify(status)
        }
        val detail = when (code) {
            "NoSuchBucket" -> "bucket"
            "RequestTimeTooSkewed" -> "clock"
            "AuthorizationHeaderMalformed", "PermanentRedirect" -> error?.region?.let { "region $it" } ?: "region"
            else -> code.ifEmpty { null }
        }
        return DavException(problem, status, detail)
    }
}
