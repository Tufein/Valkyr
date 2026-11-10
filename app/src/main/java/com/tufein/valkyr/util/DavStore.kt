package com.tufein.valkyr.util

import java.io.InputStream

/** A file read from a WebDAV server, with its ETag when the server gave a strong one. */
class DavFile(val bytes: ByteArray, val etag: String?)

/**
 * The WebDAV operations the cloud backup and device sync need, so their logic
 * ([CloudBackupEngine], [DeviceSyncEngine]) runs against a fake in the tests and against
 * [com.tufein.valkyr.data.service.WebDavClient] in the app. Every call is blocking and
 * fails with a [DavException].
 */
interface DavStore {
    /** The resource [url] (`depth` 0) or it and its members (`depth` 1). */
    fun propfind(url: String, depth: Int): List<WebDavXml.Entry>

    /** The members of the collection [url] (itself left out); empty when it does not exist. */
    fun list(url: String): List<WebDavXml.Entry>

    fun exists(url: String): Boolean

    /** The file at [url]; null when there is none. */
    fun get(url: String, maxBytes: Long): DavFile?

    /**
     * Writes [bytes] to [url]; with [ifMatch] only if the file still has that ETag, with
     * [ifNoneMatch] only if there is no file yet ([DavProblem.PRECONDITION] otherwise).
     * Returns the new ETag when known.
     */
    fun put(url: String, bytes: ByteArray, contentType: String, ifMatch: String? = null, ifNoneMatch: Boolean = false): String?

    /** Creates the collection [url] and its missing parents below [root]; true when something was created. */
    fun ensureCollection(url: String, root: String): Boolean

    /**
     * Moves the file [from] to [to] (replacing it). True when done; false when this server cannot
     * (the caller then writes directly). Other failures throw [DavException].
     */
    fun move(from: String, to: String): Boolean = false

    /** Deletes [url]; already gone counts as done. */
    fun delete(url: String)

    /**
     * Streams a large file of [size] bytes to [url] without holding it in memory. [open] gives the
     * bytes and is called again when a request has to be repeated (a redirect). With [ifNoneMatch]
     * the write only happens when there is no file yet.
     */
    fun putStream(url: String, size: Long, open: () -> InputStream, ifNoneMatch: Boolean = false): Unit =
        throw UnsupportedOperationException("Streaming upload not supported")

    /** Reads the file at [url] through [read] without holding it in memory; null when there is none. */
    fun <T> getStream(url: String, read: (InputStream) -> T): T? =
        throw UnsupportedOperationException("Streaming download not supported")

    /**
     * Moves [from] to [to] only when nothing is at [to] yet ([DavProblem.PRECONDITION] otherwise).
     * False when this server cannot move files.
     */
    fun moveNoReplace(from: String, to: String): Boolean = false

    /** Where the last request really ended (after same-host redirects). */
    val lastUrl: String
}
