package com.tufein.valkyr.data.service

import android.util.Log
import com.google.gson.JsonElement
import com.tufein.valkyr.util.CoverApis
import com.tufein.valkyr.util.CoverSources
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection

/** A cover source the chain can ask ([CoverRepository]): ScreenScraper and the three 4.4.0 sources. */
interface CoverLookupSource {
    val source: CoverSources.Source

    /** Whether the key(s) the source needs are filled in (without them it is simply skipped). */
    suspend fun configured(): Boolean

    /** Whether the source can be asked right now: configured, not paused for a quota, not backing off. */
    suspend fun usable(now: Long): Boolean

    /** The cover of a game. Only a "not found" is a [CoverSources.Answer.Miss]; anything that stopped the source from answering is [CoverSources.Answer.Unavailable]. */
    suspend fun find(consoleId: String, fileName: String, title: String, regions: List<String>, nonBoxArt: Boolean): CoverSources.Answer

    /** "Find missing covers again": the short rests end, a pause that is over is forgotten. */
    fun clearPause()
}

/**
 * The shared part of the 4.4.0 sources' requests: a limit on requests at once, the rests after a
 * rate limit / refused key / busy server (a rest ends at once when the key changes), the status
 * line's problem, and the rule that a key is only ever sent to the hosts of its own source
 * ([CoverApis.sendsKeyTo]). Only status codes are logged, never an address or a header.
 */
class CoverApiGate(
    private val source: CoverSources.Source,
    concurrency: Int,
    private val stats: CoverSourceStats,
    /** How long a refused key rests. TheGamesDB answers 403 for a used-up allowance too, so it rests only briefly. */
    private val keyPauseMs: Long = CoverApis.KEY_PAUSE_MS
) {
    private val permits = Semaphore(concurrency)
    @Volatile private var backoffUntil = 0L
    /** The key the rest belongs to: a new key ends it. */
    @Volatile private var backoffFor = 0

    /** [status] is null for a network failure (offline). */
    class Reply(val status: CoverApis.Status?, val code: Int, val json: JsonElement?)

    fun usable(now: Long, fingerprint: Int): Boolean =
        now >= stats.state.value.pausedUntil(source) && !(now < backoffUntil && backoffFor == fingerprint)

    /** A test is a deliberate new try: it is not held back by an earlier refusal. */
    fun forget() { backoffUntil = 0 }

    fun clearPause() {
        forget()
        if (stats.state.value.pausedUntil(source) <= System.currentTimeMillis()) stats.pauseSource(source, 0)
    }

    private fun backoff(fingerprint: Int, until: Long) {
        backoffFor = fingerprint
        backoffUntil = maxOf(backoffUntil, until)
    }

    /**
     * One request within the concurrency limit. [classify] turns the HTTP code into a status (a
     * service may differ from the common rule), [reacts] is false for a test (it changes no rest
     * and no problem) or for a status the caller handles itself.
     */
    suspend fun request(
        method: String,
        url: String,
        fingerprint: Int,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        contentType: String? = null,
        classify: (Int) -> CoverApis.Status = CoverApis::status,
        reacts: (CoverApis.Status) -> Boolean = { true }
    ): Reply {
        require(CoverApis.sendsKeyTo(source, url)) { "Not an address of ${source.key}" }
        return permits.withPermit {
            val connection = java.util.concurrent.atomic.AtomicReference<HttpURLConnection?>()
            coroutineScope {
                // Cancelling the caller closes the connection, so a hung read does not outlive it.
                val watcher = launch { try { awaitCancellation() } finally { connection.get()?.disconnect() } }
                try {
                    requestOnce(method, url, fingerprint, headers, body, contentType, classify, reacts, connection)
                } finally {
                    watcher.cancel()
                }
            }
        }
    }

    private suspend fun requestOnce(
        method: String, url: String, fingerprint: Int, headers: Map<String, String>, body: ByteArray?, contentType: String?,
        classify: (Int) -> CoverApis.Status, reacts: (CoverApis.Status) -> Boolean,
        connection: java.util.concurrent.atomic.AtomicReference<HttpURLConnection?>
    ): Reply {
        return run {
            withContext(Dispatchers.IO) {
                val response = try {
                    // No redirects (a 30x would carry the key to another host) and a total deadline of its own.
                    JsonHttp.request(
                        method, url, headers, body, contentType, connectTimeoutMs = 15_000, readTimeoutMs = 30_000,
                        followRedirects = false, deadlineMs = CoverApis.DEADLINE_MS, onConnection = { connection.set(it) }
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    ensureActive()
                    Log.w(TAG, "${source.key} unreachable: ${e.javaClass.simpleName}")
                    if (reacts(CoverApis.Status.BUSY)) stats.sourceProblem(source, CoverSourceStats.Problem.OFFLINE)
                    return@withContext Reply(null, 0, null)
                }
                val status = classify(response.code)
                if (status != CoverApis.Status.OK && status != CoverApis.Status.NOT_FOUND) Log.w(TAG, "${source.key} answered HTTP ${response.code}")
                if (reacts(status)) react(status, fingerprint, System.currentTimeMillis())
                Reply(status, response.code, response.json)
            }
        }
    }

    private fun react(status: CoverApis.Status, fingerprint: Int, now: Long) {
        when (status) {
            CoverApis.Status.OK, CoverApis.Status.NOT_FOUND, CoverApis.Status.BAD_REQUEST -> {
                // Answering again ends the problem; a used-up allowance stays until its pause is over.
                if (stats.state.value.problemOf(source) != CoverSourceStats.Problem.QUOTA) stats.sourceProblem(source, null)
            }
            CoverApis.Status.RATE_LIMITED -> { backoff(fingerprint, now + CoverApis.RATE_LIMIT_PAUSE_MS); stats.sourceProblem(source, CoverSourceStats.Problem.RATE_LIMITED) }
            CoverApis.Status.KEY_REJECTED -> { backoff(fingerprint, now + keyPauseMs); stats.sourceProblem(source, CoverSourceStats.Problem.LOGIN) }
            CoverApis.Status.BUSY -> { backoff(fingerprint, now + CoverApis.BUSY_PAUSE_MS); stats.sourceProblem(source, CoverSourceStats.Problem.BUSY) }
        }
    }

    /** A test that worked: the problem and the rests are over. */
    fun worked() {
        forget()
        stats.sourceProblem(source, null)
    }

    /** A test that found the key refused: shown on the status line too. */
    fun refused(fingerprint: Int) {
        stats.sourceProblem(source, CoverSourceStats.Problem.LOGIN)
        backoff(fingerprint, System.currentTimeMillis() + keyPauseMs)
    }

    private companion object { const val TAG = "CoverSources" }
}
