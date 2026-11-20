package com.tufein.valkyr.util

import com.tufein.valkyr.data.local.SettingsKeys
import com.tufein.valkyr.data.local.entity.DownloadHistoryEntity
import com.tufein.valkyr.data.local.entity.FavouriteEntity
import com.tufein.valkyr.data.local.entity.GameOverrideEntity
import com.tufein.valkyr.data.local.entity.WishlistEntity
import com.tufein.valkyr.data.model.DownloadStatus
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

/**
 * The parts of a backup file that are plain data, read and written by hand with fixed field
 * names. Reflection-based Gson would follow the field names of the classes, which R8 renames
 * in release builds — a backup made by one build would then not restore in another.
 *
 * Reading never throws on bad input: a malformed row or setting is skipped, so a hand-edited
 * or damaged file restores what it can instead of failing half-way.
 */
object BackupJson {

    const val FORMAT = "dogmatix-backup"
    const val VERSION = 1

    // ---- Settings -----------------------------------------------------------------------------

    /** Sets whose entries are `id:value`; their readers split on ':' and fail on anything else. */
    private val pairSets = setOf(
        SettingsKeys.CONSOLE_DOWNLOAD_DIRECTORIES.name, SettingsKeys.ROMM_PLATFORM_MAP.name, SettingsKeys.CONSOLE_SCANNED_AT.name
    )

    /** `{"t": type, "v": value}` for a DataStore value; null for types a backup does not carry. */
    fun encodeSetting(value: Any): JsonObject? {
        val (type, json) = when (value) {
            is Boolean -> "b" to JsonPrimitive(value)
            is Int -> "i" to JsonPrimitive(value)
            is Long -> "l" to JsonPrimitive(value)
            // As text: the speed limit is +Infinity, which JSON numbers cannot hold.
            is Float -> "f" to JsonPrimitive(value.toString())
            is Double -> "d" to JsonPrimitive(value.toString())
            is String -> "s" to JsonPrimitive(value)
            is Set<*> -> "ss" to JsonArray().apply { value.filterIsInstance<String>().forEach { add(it) } }
            else -> return null
        }
        return JsonObject().apply { addProperty("t", type); add("v", json) }
    }

    /**
     * The typed value of a backed-up setting (Boolean, Int, Long, Float, Double, String or
     * Set<String>), or null when it is malformed, has the wrong type for a known setting, or
     * holds a value its reader could not cope with. Numbers are kept within what Settings offers.
     */
    fun decodeSetting(name: String, element: JsonElement?): Any? {
        // A secret that backups never carry (the WebDAV password) is ignored even when a file has it.
        if (CloudSettingKeys.isSecret(SettingSchema.localName(name))) return null
        val obj = element as? JsonObject ?: return null
        val type = (obj.get("t") as? JsonPrimitive)?.takeIf { it.isString }?.asString ?: return null
        SettingSchema.expectedType(name)?.let { if (it != type) return null }
        val v = obj.get("v") ?: return null
        val value: Any = runCatching {
            when (type) {
                "b" -> (v as JsonPrimitive).takeIf { it.isBoolean }?.asBoolean
                "i" -> (v as JsonPrimitive).takeIf { it.isNumber }?.asBigDecimal?.intValueExact()
                "l" -> (v as JsonPrimitive).takeIf { it.isNumber }?.asBigDecimal?.longValueExact()
                "f" -> (v as JsonPrimitive).asString.toFloat()
                "d" -> (v as JsonPrimitive).asString.toDouble()
                "s" -> (v as JsonPrimitive).takeIf { it.isString }?.asString
                "ss" -> (v as JsonArray).mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.asString }.toSet()
                else -> null
            }
        }.getOrNull() ?: return null
        if (!SettingSchema.compatible(name, value)) return null
        return sanitize(SettingSchema.localName(name), value)
    }

    private fun sanitize(name: String, value: Any): Any? = when {
        name == DownloadPresets.KEY -> DownloadPresets.backupValue(value as String)
        name in pairSets -> (value as Set<*>).filterIsInstance<String>()
            .filter { it.indexOf(':') > 0 && it.substringAfter(':').isNotEmpty() }.toSet()
        name in CloudSettingKeys.TYPES -> CloudSettingKeys.sanitize(name, value)
        name == "offline_collections_ids" -> (value as Set<*>).filterIsInstance<String>().filter { it.toLongOrNull()?.let { id -> id > 0 } == true }.toSet()
        name == "offline_collections_quotas" -> (value as Set<*>).filterIsInstance<String>().mapNotNull { entry ->
            val id = entry.substringBefore(':').toLongOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            val gb = entry.substringAfter(':', "").toIntOrNull()?.takeIf { it in 1..2048 } ?: return@mapNotNull null
            "$id:$gb"
        }.toSet()
        else -> SettingSchema.sanitizeNumber(name, value)
    }

    // ---- Favourites ---------------------------------------------------------------------------

    fun favouritesToJson(rows: List<FavouriteEntity>): JsonArray = JsonArray().apply {
        rows.forEach { f ->
            add(JsonObject().apply {
                addProperty("consoleId", f.consoleId)
                addProperty("fileName", f.fileName)
                addProperty("addedAt", f.addedAt)
                addProperty("profileId", f.profileId)
            })
        }
    }

    fun favouritesFromJson(array: JsonElement?): List<FavouriteEntity> =
        (array as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            FavouriteEntity(
                consoleId = o.string("consoleId") ?: return@mapNotNull null,
                fileName = o.string("fileName") ?: return@mapNotNull null,
                addedAt = o.long("addedAt") ?: System.currentTimeMillis(),
                profileId = o.string("profileId").orEmpty()
            )
        }

    // ---- Wishlist -----------------------------------------------------------------------------

    fun wishlistToJson(rows: List<WishlistEntity>): JsonArray = JsonArray().apply {
        rows.forEach { w ->
            add(JsonObject().apply {
                addProperty("title", w.title)
                w.consoleId?.let { addProperty("consoleId", it) }
                addProperty("addedAt", w.addedAt)
                w.notifiedAt?.let { addProperty("notifiedAt", it) }
            })
        }
    }

    fun wishlistFromJson(array: JsonElement?): List<WishlistEntity> =
        (array as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            WishlistEntity(
                title = o.string("title")?.trim()?.takeIf { it.length >= 2 } ?: return@mapNotNull null,
                consoleId = o.string("consoleId")?.takeIf { it.isNotBlank() },
                addedAt = o.long("addedAt") ?: System.currentTimeMillis(),
                notifiedAt = o.long("notifiedAt")
            )
        }

    // ---- Downloads list -----------------------------------------------------------------------

    /** Debrid ids are left out: they belong to transfers on the service that will not exist any more. */
    fun historyToJson(rows: List<DownloadHistoryEntity>): JsonArray = JsonArray().apply {
        rows.forEach { h ->
            add(JsonObject().apply {
                addProperty("fileName", h.fileName)
                addProperty("name", h.name)
                addProperty("consoleId", h.consoleId)
                addProperty("downloadUrl", h.downloadUrl)
                addProperty("fileSize", h.fileSize)
                addProperty("fileExtension", h.fileExtension)
                h.torrentFileIndex?.let { addProperty("torrentFileIndex", it) }
                h.torrentMagnet?.let { addProperty("torrentMagnet", it) }
                if (h.sourceUrl.isNotEmpty()) addProperty("sourceUrl", h.sourceUrl)
                if (h.originalSourceUrl.isNotEmpty()) addProperty("originalSourceUrl", h.originalSourceUrl)
                if (h.switchedFrom.isNotEmpty()) addProperty("switchedFrom", h.switchedFrom)
                addProperty("status", h.status)
                addProperty("startedAt", h.startedAt)
                h.finishedAt?.let { addProperty("finishedAt", it) }
                PersistedDownloadFailure.restore(h.failureCategory, h.failureHttpStatusCode)?.let { failure ->
                    addProperty("failureCategory", failure.category.name)
                    failure.httpStatusCode?.let { addProperty("failureHttpStatusCode", it) }
                    PersistedDownloadFailure.timestamp(h.failureAt)?.let { addProperty("failureAt", it) }
                }
            })
        }
    }

    fun historyFromJson(array: JsonElement?): List<DownloadHistoryEntity> =
        (array as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val status = o.string("status")?.takeIf { s -> DownloadStatus.entries.any { it.name == s } } ?: return@mapNotNull null
            val failure = PersistedDownloadFailure.restore(
                o.string("failureCategory"), o.long("failureHttpStatusCode")?.takeIf { it in 100L..599L }?.toInt()
            )
            DownloadHistoryEntity(
                fileName = o.string("fileName") ?: return@mapNotNull null,
                name = o.string("name") ?: return@mapNotNull null,
                consoleId = o.string("consoleId") ?: return@mapNotNull null,
                downloadUrl = o.string("downloadUrl") ?: return@mapNotNull null,
                fileSize = o.long("fileSize") ?: 0L,
                fileExtension = o.string("fileExtension").orEmpty(),
                torrentFileIndex = o.long("torrentFileIndex")?.toInt(),
                torrentMagnet = o.string("torrentMagnet"),
                status = status,
                startedAt = o.long("startedAt") ?: 0L,
                sourceUrl = o.string("sourceUrl").orEmpty(),
                originalSourceUrl = o.string("originalSourceUrl").orEmpty(),
                switchedFrom = o.string("switchedFrom").orEmpty(),
                finishedAt = o.long("finishedAt"),
                failureCategory = failure?.category?.name,
                failureHttpStatusCode = failure?.httpStatusCode,
                failureAt = if (failure != null) PersistedDownloadFailure.timestamp(o.long("failureAt")) else null
            )
        }

    // ---- 9.0: per-game corrections -------------------------------------------------------------

    /** Fixed field names; an older backup simply has no "gameOverrides" section. */
    fun overridesToJson(entries: List<GameOverrideEntity>): JsonArray = JsonArray().apply {
        entries.forEach { o ->
            val row = JsonObject()
            row.addProperty("key", o.key)
            o.title?.let { row.addProperty("title", it) }
            o.year?.let { row.addProperty("year", it) }
            o.genre?.let { row.addProperty("genre", it) }
            o.pinnedCoverUrl?.let { row.addProperty("pinnedCoverUrl", it) }
            row.addProperty("updatedAt", o.updatedAt)
            add(row)
        }
    }

    fun overridesFromJson(element: JsonElement?): List<GameOverrideEntity> {
        val array = element as? JsonArray ?: return emptyList()
        return array.mapNotNull { row ->
            val o = row as? JsonObject ?: return@mapNotNull null
            val key = o.string("key") ?: return@mapNotNull null
            GameOverrideEntity(
                key = key,
                title = o.string("title"),
                year = o.long("year")?.toInt(),
                genre = o.string("genre"),
                pinnedCoverUrl = o.string("pinnedCoverUrl"),
                updatedAt = o.long("updatedAt") ?: 0L
            )
        }
    }

    private fun JsonArray?.orEmpty(): List<JsonElement> = this?.toList().orEmpty()

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.asString

    private fun JsonObject.long(key: String): Long? =
        (get(key) as? JsonPrimitive)?.takeIf { it.isNumber }?.let { runCatching { it.asLong }.getOrNull() }
}
