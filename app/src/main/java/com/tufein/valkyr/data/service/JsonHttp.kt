package com.tufein.valkyr.data.service

import com.tufein.valkyr.BuildConfig
import com.tufein.valkyr.util.BoundedStreams
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.io.File
import java.util.concurrent.ScheduledThreadPoolExecutor

/**
 * Minimal JSON-over-HTTP helper on `HttpURLConnection` (the app has no OkHttp), shared by the
 * TorBox and RomM clients. Same shape as `GameMetadataService.getJson`, plus method, headers
 * and a request body.
 */
object JsonHttp {

    class HttpException(val code: Int, val body: String?) : IOException("HTTP $code${body?.let { ": ${it.take(200)}" }.orEmpty()}")

    data class Response(val code: Int, val json: JsonElement?, val body: String) {
        val ok: Boolean get() = code in 200..299
    }

    val userAgent = "Valkyr/${BuildConfig.VERSION_NAME}"

    fun request(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        contentType: String? = null,
        connectTimeoutMs: Int = 15_000,
        readTimeoutMs: Int = 30_000,
        followRedirects: Boolean = true,
        deadlineMs: Long = 0,
        onConnection: ((HttpURLConnection) -> Unit)? = null
    ): Response {
        val connection = URL(url).openConnection() as HttpURLConnection
        TlsTrust.apply(connection)
        // A request that carries a key never follows a redirect to another host ([followRedirects] off).
        connection.instanceFollowRedirects = followRedirects
        onConnection?.invoke(connection)
        // Total deadline: the timeouts above are per read, so a slow trickle could hold the request for ever.
        val watchdog = if (deadlineMs > 0) WATCHDOG.schedule({ connection.disconnect() }, deadlineMs, TimeUnit.MILLISECONDS) else null
        try {
            connection.requestMethod = method
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            if (body != null) {
                connection.doOutput = true
                contentType?.let { connection.setRequestProperty("Content-Type", it) }
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JsonParser.parseString(text) }.getOrNull()?.takeUnless { it.isJsonNull }
            return Response(code, json, text)
        } finally {
            watchdog?.cancel(false)
            connection.disconnect()
        }
    }

    private val WATCHDOG = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "http-deadline").apply { isDaemon = true } }
        .apply { removeOnCancelPolicy = true }

    /**
     * A binary GET (save files, states): the whole body, or [HttpException] on a non-2xx status.
     * Refuses bodies above [maxBytes] so a wrong URL cannot fill the memory.
     */
    fun download(url: String, headers: Map<String, String> = emptyMap(), maxBytes: Long, readTimeoutMs: Int = 60_000): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        TlsTrust.apply(connection)
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = readTimeoutMs
            connection.setRequestProperty("User-Agent", userAgent)
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            val code = connection.responseCode
            if (code !in 200..299) {
                throw HttpException(code, connection.errorStream?.bufferedReader()?.use { it.readText() })
            }
            val length = connection.contentLengthLong
            if (length > maxBytes) throw IOException("File too large ($length bytes)")
            // With a known length the body goes into one array of that size (a 64 MB memory card is
            // held once, not copied while a buffer grows); a body that ends early is an error, not a save.
            return connection.inputStream.use { input ->
                BoundedStreams.readAll(input, length, maxBytes, tooLarge = { IOException("File too large") }, changed = { IOException("The download ended early") })
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * A binary GET streamed into [dest] (never held in memory), at most [maxBytes]; [dest] is removed on any
     * failure. Redirects are followed by hand, at most [MAX_REDIRECTS], and each target must pass [accept]
     * before it is requested (the key header goes only to addresses [accept] allows).
     */
    fun downloadToFile(
        url: String, headers: Map<String, String> = emptyMap(), dest: File, maxBytes: Long,
        readTimeoutMs: Int = 60_000, accept: (String) -> Boolean
    ): Long {
        try {
            var current = url
            repeat(MAX_REDIRECTS + 1) {
                if (!accept(current)) throw IOException("The address is not allowed")
                val connection = URL(current).openConnection() as HttpURLConnection
                TlsTrust.apply(connection)
                try {
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 15_000
                    connection.readTimeout = readTimeoutMs
                    connection.setRequestProperty("User-Agent", userAgent)
                    headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
                    val code = connection.responseCode
                    if (code in 300..399) {
                        val location = connection.getHeaderField("Location") ?: throw HttpException(code, null)
                        current = URL(URL(current), location).toString()
                        return@repeat
                    }
                    if (code !in 200..299) throw HttpException(code, connection.errorStream?.bufferedReader()?.use { it.readText() })
                    val length = connection.contentLengthLong
                    if (length > maxBytes) throw IOException("File too large ($length bytes)")
                    var total = 0L
                    connection.inputStream.use { input ->
                        dest.outputStream().use { out ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                total += n
                                if (total > maxBytes) throw IOException("File too large")
                                out.write(buffer, 0, n)
                            }
                        }
                    }
                    if (length >= 0 && total != length) throw IOException("The download ended early")
                    return total
                } finally {
                    connection.disconnect()
                }
            }
            throw IOException("Too many redirects")
        } catch (e: Throwable) {
            dest.delete()
            throw e
        }
    }

    private const val MAX_REDIRECTS = 3

    /** Like [request] but throws [HttpException] on a non-2xx status. */
    fun requireOk(response: Response): Response =
        if (response.ok) response else throw HttpException(response.code, response.body)

    fun formBody(fields: Map<String, String>): ByteArray =
        fields.entries.joinToString("&") { (k, v) -> URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8") }
            .toByteArray(Charsets.UTF_8)

    /** `multipart/form-data` body + its Content-Type (TorBox ignores url-encoded fields). */
    fun multipartBody(fields: Map<String, String>): Pair<ByteArray, String> {
        val boundary = "----Valkyr" + System.nanoTime()
        val sb = StringBuilder()
        fields.forEach { (k, v) ->
            sb.append("--").append(boundary).append("\r\n")
            sb.append("Content-Disposition: form-data; name=\"").append(k).append("\"\r\n\r\n")
            sb.append(v).append("\r\n")
        }
        sb.append("--").append(boundary).append("--\r\n")
        return sb.toString().toByteArray(Charsets.UTF_8) to "multipart/form-data; boundary=$boundary"
    }

    /** `multipart/form-data` body carrying one file under [fieldName], + its Content-Type. */
    fun multipartFileBody(fieldName: String, fileName: String, bytes: ByteArray): Pair<ByteArray, String> {
        val boundary = "----Valkyr" + System.nanoTime()
        // RFC 7578: the file name goes as a quoted string; quotes and line breaks cannot appear in it.
        val safeName = fileName.replace("\"", "%22").replace("\r", "").replace("\n", "")
        val head = "--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"$fieldName\"; filename=\"$safeName\"\r\n" +
            "Content-Type: application/octet-stream\r\n\r\n"
        val tail = "\r\n--$boundary--\r\n"
        // One array of the exact size: the file (up to a 64 MB memory card) is copied once, not twice.
        val headBytes = head.toByteArray(Charsets.UTF_8)
        val tailBytes = tail.toByteArray(Charsets.UTF_8)
        val body = ByteArray(headBytes.size + bytes.size + tailBytes.size)
        headBytes.copyInto(body)
        bytes.copyInto(body, headBytes.size)
        tailBytes.copyInto(body, headBytes.size + bytes.size)
        return body to "multipart/form-data; boundary=$boundary"
    }

    const val FORM_URLENCODED = "application/x-www-form-urlencoded"
    const val JSON = "application/json"
}
