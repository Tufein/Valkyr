package com.tufein.valkyr.data.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** Reachability of a personal tracker address. UDP trackers cannot be checked with an HTTP request. */
object TorrentTrackerCheck {
    sealed class Result {
        data class Http(val code: Int) : Result()
        data class Failed(val kind: String) : Result()
        object NotTestable : Result()
    }

    suspend fun check(url: String): Result = withContext(Dispatchers.IO) {
        val uri = runCatching { URI(url) }.getOrNull() ?: return@withContext Result.Failed("URI")
        when (uri.scheme?.lowercase()) {
            "http", "https" -> runCatching {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                connection.instanceFollowRedirects = false
                connection.requestMethod = "GET"
                val code = connection.responseCode
                connection.disconnect()
                Result.Http(code)
            }.getOrElse { Result.Failed(it.javaClass.simpleName.ifBlank { "IO" }) }
            // A udp:// announce is a stateless datagram exchange, not an HTTP status. Claiming
            // success from a syntax check would be dishonest, so the UI says it was only validated.
            else -> Result.NotTestable
        }
    }
}
