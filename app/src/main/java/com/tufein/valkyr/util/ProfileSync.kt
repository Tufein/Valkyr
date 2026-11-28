package com.tufein.valkyr.util

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** 10.0: one profile as it travels: what it hides, and when it was last changed on any handheld. The PIN never travels. */
data class SyncProfile(val name: String, val hiddenConsoles: Set<String>, val hiddenTags: Set<String>, val updatedAt: Long) {
    /** What makes two copies the same profile content, whoever edited them. */
    val content: String get() = ProfileSync.contentOf(hiddenConsoles, hiddenTags)
}

/**
 * 10.0: profiles between your own handhelds (`<root>/sync/profiles.json`). Profiles are matched by
 * name (case ignored); the same name on two handhelds is one profile. A profile that exists on one
 * side only is added to the other. When both sides differ the newer edit wins (a tie goes to the
 * larger content string, so every handheld picks the same one). Nothing is ever removed by sync:
 * deleting a profile on one handheld does not delete it on the others, and the active profile and
 * the PIN stay on the device. Pure JVM.
 */
object ProfileSync {
    const val FORMAT = "valkyr-profiles"
    const val VERSION = 1
    const val FILE_NAME = "profiles.json"
    const val MAX_PROFILES = 50
    const val MAX_NAME = 40
    const val MAX_ITEMS = 500
    const val MAX_ITEM = 80

    fun key(name: String): String = name.trim().lowercase()

    fun contentOf(consoles: Set<String>, tags: Set<String>): String =
        consoles.sorted().joinToString(",") + "|" + tags.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSortedSet().joinToString(",")

    /** What the last sync left for one profile: its content then, and the time it counted as edited. */
    data class Base(val content: String, val updatedAt: Long)

    /** This handheld's profiles: unchanged since the last sync keep their old time, anything else counts as edited [now]. */
    fun snapshot(local: List<Profile>, base: Map<String, Base>, now: Long): Map<String, SyncProfile> {
        val out = LinkedHashMap<String, SyncProfile>()
        for (p in local) {
            val name = p.name.trim().take(MAX_NAME)
            val key = key(name)
            if (key.isEmpty() || key in out) continue
            val tags = p.hiddenTags.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            val content = contentOf(p.hiddenConsoles, tags)
            val known = base[key]?.takeIf { it.content == content }
            out[key] = SyncProfile(name, p.hiddenConsoles, tags, known?.updatedAt ?: now)
            if (out.size >= MAX_PROFILES) break
        }
        return out
    }

    private fun newer(a: SyncProfile, b: SyncProfile): SyncProfile = when {
        a.updatedAt != b.updatedAt -> if (a.updatedAt > b.updatedAt) a else b
        else -> if (a.content >= b.content) a else b
    }

    fun merge(mine: Map<String, SyncProfile>, remote: Map<String, SyncProfile>?): Map<String, SyncProfile> {
        if (remote == null) return mine
        val out = LinkedHashMap<String, SyncProfile>()
        for (key in (mine.keys + remote.keys).sorted()) {
            val a = mine[key]; val b = remote[key]
            out[key] = when {
                a == null -> b!!
                b == null -> a
                a.content == b.content -> if (a.updatedAt >= b.updatedAt) a else b
                else -> newer(a, b)
            }
            if (out.size >= MAX_PROFILES) break
        }
        return out
    }

    fun baseOf(merged: Map<String, SyncProfile>): Map<String, Base> = merged.mapValues { Base(it.value.content, it.value.updatedAt) }

    /** The device's profile list after taking [merged] in: same ids, PINs and names, changed hiding, new profiles added. */
    fun apply(local: List<Profile>, merged: Map<String, SyncProfile>, newId: () -> String): List<Profile> {
        val known = HashSet<String>()
        val updated = local.map { p ->
            val key = key(p.name)
            known += key
            val m = merged[key]
            if (m != null && contentOf(p.hiddenConsoles, p.hiddenTags.map { it.trim() }.filter { it.isNotEmpty() }.toSet()) != m.content) p.copy(hiddenConsoles = m.hiddenConsoles, hiddenTags = m.hiddenTags) else p
        }
        val added = merged.filter { it.key !in known }.values.map { Profile(newId(), it.name, it.hiddenConsoles, it.hiddenTags) }
        return updated + added
    }

    fun encode(doc: Map<String, SyncProfile>, rev: String, now: Long): String = JsonObject().apply {
        addProperty("format", FORMAT); addProperty("version", VERSION); addProperty("updatedAt", now); addProperty("rev", rev)
        add("profiles", JsonArray().also { arr ->
            doc.values.forEach { p ->
                arr.add(JsonObject().apply {
                    addProperty("n", p.name); addProperty("t", p.updatedAt)
                    add("c", JsonArray().also { a -> p.hiddenConsoles.sorted().forEach(a::add) })
                    add("g", JsonArray().also { a -> p.hiddenTags.sorted().forEach(a::add) })
                })
            }
        })
    }.toString()

    /** A broken row is skipped, never the whole document. */
    fun decode(text: String): DocParsed<Map<String, SyncProfile>> = runCatching {
        val o = JsonParser.parseString(text).asJsonObject
        if (o.get("format")?.asString != FORMAT) return@runCatching DocParsed.Invalid
        val version = o.get("version")?.asInt ?: return@runCatching DocParsed.Invalid
        if (version > VERSION) return@runCatching DocParsed.Newer(version)
        val out = LinkedHashMap<String, SyncProfile>()
        for (el in o.getAsJsonArray("profiles")) {
            val e = runCatching { el.asJsonObject }.getOrNull() ?: continue
            val name = runCatching { e.get("n").asString }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_NAME } ?: continue
            val at = runCatching { e.get("t").asLong }.getOrNull()?.takeIf { it >= 0L } ?: continue
            fun items(field: String) = runCatching { e.getAsJsonArray(field).map { it.asString } }.getOrNull().orEmpty()
                .filter { it.isNotBlank() && it.length <= MAX_ITEM }.take(MAX_ITEMS).toSet()
            val key = key(name)
            if (key !in out && out.size < MAX_PROFILES) out[key] = SyncProfile(name, items("c"), items("g"), at)
        }
        DocParsed.Ok(out, o.get("rev")?.asString.orEmpty())
    }.getOrDefault(DocParsed.Invalid)

    fun encodeBase(base: Map<String, Base>): String = JsonObject().also { o ->
        base.forEach { (k, v) -> o.add(k, JsonObject().apply { addProperty("c", v.content); addProperty("t", v.updatedAt) }) }
    }.toString()

    fun decodeBase(text: String?): Map<String, Base> = runCatching {
        val o = JsonParser.parseString(text ?: "{}").asJsonObject
        o.entrySet().mapNotNull { (k, v) ->
            val e = v.asJsonObject
            Base(e.get("c").asString, e.get("t").asLong).let { k to it }
        }.toMap()
    }.getOrDefault(emptyMap())
}

object ProfilesCodec : DocSyncEngine.Codec<Map<String, SyncProfile>> {
    override fun decode(text: String) = ProfileSync.decode(text)
    override fun encode(doc: Map<String, SyncProfile>, rev: String, now: Long) = ProfileSync.encode(doc, rev, now)
    override fun isEmpty(doc: Map<String, SyncProfile>) = doc.isEmpty()
}
