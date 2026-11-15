package com.tufein.valkyr.data.state

import android.content.Context
import com.tufein.valkyr.util.FailureKind
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** How the last scan of one source went: how many games it gave, or why it failed. */
data class SourceScanResult(
    val files: Int?,
    val failure: FailureKind? = null,
    val httpCode: Int? = null,
    val at: Long = System.currentTimeMillis(),
    /** The listing had not changed since the scan before, so its rows were kept as they were. */
    val unchanged: Boolean = false,
    /** Files this scan found that the source did not list before. */
    val newFiles: Int = 0,
    /** The address that answered when it was not the source's own (a reserve address). */
    val servedBy: String? = null,
    /** What the listing looked like ([com.tufein.valkyr.util.ListingCheck]); used to skip an unchanged one next time. */
    val etag: String? = null,
    val lastModified: String? = null,
    val bodyHash: String? = null,
    val fingerprint: String? = null,
    /** 7.8: the last scans of this source, oldest first (see [com.tufein.valkyr.util.SourceHealth.trend]). */
    val history: List<com.tufein.valkyr.util.SourceHealth.Outcome> = emptyList()
)

/**
 * The outcome of the last scan of every source, shown under each URL in Sources so a source that
 * keeps failing (or suddenly gives nothing) stands out. Kept in a small file across restarts.
 */
@Singleton
class SourceScanResults @Inject constructor(@param:ApplicationContext context: Context) {
    private val file = File(context.filesDir, "source_scan_results.json")
    private val _results = MutableStateFlow(load())
    val results: StateFlow<Map<String, SourceScanResult>> = _results.asStateFlow()

    fun record(consoleId: String, url: String, result: SourceScanResult) {
        _results.update { all ->
            val before = all[key(consoleId, url)]?.history.orEmpty()
            all + (key(consoleId, url) to result.copy(history = com.tufein.valkyr.util.SourceHealth.append(before, com.tufein.valkyr.util.SourceHealth.Outcome(result.at, result.failure))))
        }
        save(_results.value)
    }

    fun forget(consoleId: String) {
        _results.update { all -> all.filterKeys { !it.startsWith("$consoleId|") } }
        save(_results.value)
    }

    fun get(consoleId: String, url: String): SourceScanResult? = _results.value[key(consoleId, url)]

    private fun JsonObject.str(name: String): String? = get(name)?.takeUnless { it.isJsonNull }?.asString

    private fun load(): Map<String, SourceScanResult> = runCatching {
        if (!file.exists()) return emptyMap()
        JsonParser.parseString(file.readText()).asJsonObject.getAsJsonArray("results").mapNotNull { el ->
            runCatching {
                val o = el.asJsonObject
                o.get("key").asString to SourceScanResult(
                    files = o.get("files")?.takeUnless { it.isJsonNull }?.asInt,
                    failure = o.get("failure")?.takeUnless { it.isJsonNull }?.asString?.let { FailureKind.valueOf(it) },
                    httpCode = o.get("httpCode")?.takeUnless { it.isJsonNull }?.asInt,
                    at = o.get("at").asLong,
                    unchanged = o.get("unchanged")?.takeUnless { it.isJsonNull }?.asBoolean ?: false,
                    newFiles = o.get("newFiles")?.takeUnless { it.isJsonNull }?.asInt ?: 0,
                    servedBy = o.str("servedBy"),
                    etag = o.str("etag"),
                    lastModified = o.str("lastModified"),
                    bodyHash = o.str("bodyHash"),
                    fingerprint = o.str("fingerprint"),
                    history = com.tufein.valkyr.util.SourceHealth.decode(o.str("history"))
                )
            }.getOrNull()
        }.toMap()
    }.getOrDefault(emptyMap())

    @Synchronized
    private fun save(map: Map<String, SourceScanResult>) {
        runCatching {
            val array = JsonArray()
            map.forEach { (k, r) ->
                array.add(JsonObject().apply {
                    addProperty("key", k)
                    r.files?.let { addProperty("files", it) }
                    r.failure?.let { addProperty("failure", it.name) }
                    r.httpCode?.let { addProperty("httpCode", it) }
                    addProperty("at", r.at)
                    if (r.unchanged) addProperty("unchanged", true)
                    if (r.newFiles > 0) addProperty("newFiles", r.newFiles)
                    r.servedBy?.let { addProperty("servedBy", it) }
                    r.etag?.let { addProperty("etag", it) }
                    r.lastModified?.let { addProperty("lastModified", it) }
                    r.bodyHash?.let { addProperty("bodyHash", it) }
                    r.fingerprint?.let { addProperty("fingerprint", it) }
                    if (r.history.isNotEmpty()) addProperty("history", com.tufein.valkyr.util.SourceHealth.encode(r.history))
                })
            }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(JsonObject().apply { add("results", array) }.toString())
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
    }

    companion object {
        fun key(consoleId: String, url: String) = "$consoleId|$url"
    }
}
