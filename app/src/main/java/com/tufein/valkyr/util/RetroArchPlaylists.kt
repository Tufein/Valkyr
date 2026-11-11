package com.tufein.valkyr.util

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale

/**
 * RetroArch playlists (3.5.0): one `.lpl` per libretro system in RetroArch's playlists folder,
 * listing the games on the device, so RetroArch's main menu shows them without a content scan.
 * Pure JVM for the tests.
 *
 * The format is RetroArch's JSON playlist (RetroArch 1.7.6 and later, written here as version
 * "1.5"): a few top-level settings and `items`, each with `path`, `label`, `core_path`,
 * `core_name`, `crc32` and `db_name`. This follows RetroArch's documentation and source, not a
 * tested device. A playlist the user already has is only ever added to: entries already there,
 * their order, their cores and every field this app does not know stay exactly as they are.
 * Playlists in RetroArch's old plain-text format, compressed ones and anything that is not
 * clean JSON are left alone.
 *
 *  - The playlist and its `db_name` are named after the libretro system
 *    ("Nintendo - Game Boy Advance.lpl"), the same name *Covers for RetroArch* uses for the
 *    thumbnails folder ([FrontendArtwork.retroArchSystem]).
 *  - The label is the game's file name without extension ("Advance Wars (USA)"): RetroArch shows
 *    it and finds the box art by it with libretro's character rule ([thumbnailName]), which is
 *    exactly the name *Covers for RetroArch* gives the image.
 *  - The path is the file system path RetroArch opens ([filesystemPath]), never a SAF uri.
 *  - Cores stay `DETECT`: RetroArch asks for one at the first start of an entry.
 *  - A game of several discs is one entry for its `.m3u`; discs without one are not listed.
 */
object RetroArchPlaylists {

    const val EXTENSION = "lpl"
    /** The playlist format version written into a new playlist. */
    const val VERSION = "1.5"
    /** RetroArch picks (or asks for) the core when the entry starts. */
    const val DETECT = "DETECT"
    /** "No checksum known", as RetroArch writes it itself. */
    const val NO_CRC = "00000000|crc"
    /** A playlist larger than this is not read (and so left alone). */
    const val MAX_PLAYLIST_BYTES = 16 * 1024 * 1024
    /** An `.m3u` larger than this is not read: it would not be a disc list. */
    const val MAX_M3U_BYTES = 64 * 1024
    /** Shared storage as RetroArch sees it; the service passes the device's own when it differs. */
    const val DEFAULT_PRIMARY_ROOT = "/storage/emulated/0"
    /** Nesting deeper than this is not a playlist. */
    private const val MAX_DEPTH = 64

    /** The preference keys (see RetroArchPlaylistsSettings): the playlists folder and the systems switched off. */
    const val FOLDER_KEY = "playlists35_folder"
    const val EXCLUDED_KEY = "playlists35_excluded"

    /** 6.2: "Keep RetroArch playlists up to date" (default off). */
    const val AUTO_KEY = "playlists62_auto"

    /** 6.5: the core chosen per system, as `system<TAB>core` entries. */
    const val CORES_KEY = "playlists65_cores"

    /** Their backup types, for [SettingSchema]. */
    val SETTING_TYPES: Map<String, String> = mapOf(FOLDER_KEY to "s", EXCLUDED_KEY to "ss", AUTO_KEY to "b", CORES_KEY to "ss")

    /**
     * 6.5: the core a playlist opens its games with, as RetroArch's own *Set Core Association*
     * stores it (`default_core_path` / `default_core_name` in the playlist). An entry that stays
     * `DETECT` then starts with it instead of asking.
     */
    data class DefaultCore(val id: String, val path: String, val name: String)

    /** The core file inside RetroArch's private `cores` folder of [packageName]. */
    fun defaultCore(coreId: String, packageName: String): DefaultCore = DefaultCore(
        coreId, "/data/data/$packageName/cores/${coreId}_libretro_android.so", coreId
    )

    /** The cores RetroArch can run [system]'s consoles with, preferred first (from the emulator catalogue). */
    fun coresOf(consoleIds: List<String>): List<String> =
        consoleIds.firstNotNullOfOrNull { EmulatorCatalog.systemOf(it)?.cores?.takeIf { c -> c.isNotEmpty() } }.orEmpty()

    /** `system<TAB>core` entries → system to core id. */
    fun parseCores(entries: Set<String>): Map<String, String> =
        entries.mapNotNull { e -> e.split('\t', limit = 2).takeIf { it.size == 2 && it[0].isNotBlank() && it[1].isNotBlank() }?.let { it[0] to it[1] } }.toMap()

    fun encodeCores(cores: Map<String, String>): Set<String> = cores.map { (k, v) -> "$k\t$v" }.toSet()

    /** The next choice when tapping: none (RetroArch asks) → first core → second … → none. */
    fun nextCore(options: List<String>, current: String?): String? {
        if (options.isEmpty()) return null
        val at = options.indexOf(current)
        return if (current == null || at < 0) options.first() else options.getOrNull(at + 1)
    }

    private fun blankDefault(root: JsonObject): Boolean =
        (root.get("default_core_path")?.takeIf { it.isJsonPrimitive }?.asString).isNullOrBlank() ||
            root.get("default_core_path")?.asString == DETECT

    private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"

    // ---- Names ---------------------------------------------------------------------------------

    /** The playlist name of a library console: "Nintendo - Game Boy Advance"; null when libretro has none for it. */
    fun systemOf(consoleId: String): String? = LibretroThumbnails.systemFor(consoleId)?.let(FrontendArtwork::retroArchSystem)

    /** `Nintendo - Game Boy Advance.lpl`: the playlist file and every entry's `db_name`. */
    fun fileName(system: String): String = "$system.$EXTENSION"

    /** The box art name RetroArch looks for an entry's [label] under: libretro's character rule, as *Covers for RetroArch* names it. */
    fun thumbnailName(label: String): String = LibretroThumbnails.thumbnailName(label)

    // ---- Paths ---------------------------------------------------------------------------------

    /**
     * The file system path of a SAF document ([documentUri], a tree or document uri) as RetroArch
     * opens it: `primary:ROMs/gba/Game.gba` is `/storage/emulated/0/ROMs/gba/Game.gba`, an SD card
     * volume `ABCD-1234:Roms/gba/Game.gba` is `/storage/ABCD-1234/Roms/gba/Game.gba`, a Downloads
     * `raw:` id carries its path. Null when the storage provider's ids do not spell a path (a cloud
     * provider, a Downloads `msf:` id, the "Documents" `home:` root): such games cannot be listed.
     * [primaryRoot] is shared storage on this device when it is not the usual one (a work profile).
     */
    fun filesystemPath(documentUri: String, primaryRoot: String = DEFAULT_PRIMARY_ROOT): String? {
        val (authority, documentId) = documentRef(documentUri) ?: return null
        // The "home" root of the external storage provider is the Documents folder, not a volume.
        if (authority == EXTERNAL_STORAGE && documentId.substringBefore(':', "").equals("home", ignoreCase = true)) return null
        val path = DiskScanner.canonicalPath(authority, documentId) ?: return null
        if (!path.startsWith('/')) return null
        val clean = path.replace(MULTI_SLASH, "/").trimEnd('/')
        val segments = clean.split('/').drop(1)
        if (segments.isEmpty() || segments.any { it.isEmpty() || it == "." || it == ".." || it.any(Char::isISOControl) }) return null
        val root = primaryRoot.trimEnd('/').ifEmpty { DEFAULT_PRIMARY_ROOT }
        return if (root != DEFAULT_PRIMARY_ROOT && (clean == DEFAULT_PRIMARY_ROOT || clean.startsWith("$DEFAULT_PRIMARY_ROOT/")))
            root + clean.removePrefix(DEFAULT_PRIMARY_ROOT)
        else clean
    }

    /** The authority and (decoded) document id of a `content://` tree or document uri; null for anything else. */
    internal fun documentRef(uri: String): Pair<String, String>? {
        if (!uri.startsWith("content://")) return null
        val rest = uri.removePrefix("content://").substringBefore('?').substringBefore('#')
        val authority = rest.substringBefore('/')
        if (authority.isEmpty()) return null
        val segments = rest.substringAfter('/', "").split('/')
        val encoded = when {
            segments.size >= 4 && segments[0] == "tree" && segments[2] == "document" -> segments[3]
            segments.size >= 2 && segments[0] == "document" -> segments[1]
            segments.size >= 2 && segments[0] == "tree" -> segments[1]
            else -> return null
        }
        val id = percentDecode(encoded)?.takeIf { it.isNotEmpty() } ?: return null
        return authority to id
    }

    /** `%xx` escapes as UTF-8 (a `+` stays a `+`, as Android writes uris); null when the escapes are broken. */
    private fun percentDecode(s: String): String? {
        val out = ByteArrayOutputStream(s.length)
        var i = 0
        val run = StringBuilder()
        fun flush() { if (run.isNotEmpty()) { out.write(run.toString().toByteArray(Charsets.UTF_8)); run.setLength(0) } }
        while (i < s.length) {
            val c = s[i]
            if (c == '%') {
                if (i + 2 >= s.length) return null
                val b = s.substring(i + 1, i + 3).toIntOrNull(16) ?: return null
                flush()
                out.write(b)
                i += 3
            } else {
                run.append(c)
                i++
            }
        }
        flush()
        return strictUtf8(out.toByteArray())
    }

    /**
     * The key two playlist paths are compared by: the same file however a playlist spells it. Case
     * does not matter (shared storage ignores it), an entry inside an archive (`Game.zip#Game.gba`)
     * is its archive, and `/sdcard`, `/storage/self/primary`, `/storage/emulated/<user>` and
     * `/mnt/media_rw/<volume>` are the storage the app knows as `/storage/emulated/0` and `/storage/<volume>`.
     */
    fun pathKey(path: String): String {
        var p = path.trim().replace('\\', '/')
        ARCHIVE_DELIMITER.find(p)?.let { p = p.substring(0, it.range.last) }
        p = p.replace(MULTI_SLASH, "/").trimEnd('/').lowercase(Locale.ROOT)
        p = p.replace(SDCARD, DEFAULT_PRIMARY_ROOT)
            .replace(SELF_PRIMARY, DEFAULT_PRIMARY_ROOT)
            .replace(EMULATED_USER, DEFAULT_PRIMARY_ROOT)
            .replace(MEDIA_RW) { "/storage/${it.groupValues[1]}" }
        return p
    }

    private val MULTI_SLASH = Regex("/{2,}")
    /** RetroArch's archive delimiter: the `#` right after `.zip`, `.7z` or `.apk`. */
    private val ARCHIVE_DELIMITER = Regex("""\.(?:zip|7z|apk)#""", RegexOption.IGNORE_CASE)
    private val SDCARD = Regex("^/(?:mnt/)?sdcard(?=/|$)")
    private val SELF_PRIMARY = Regex("^/storage/self/primary(?=/|$)")
    private val EMULATED_USER = Regex("^/storage/emulated/\\d+(?=/|$)")
    private val MEDIA_RW = Regex("^/mnt/media_rw/([^/]+)(?=/|$)")

    // ---- The games -----------------------------------------------------------------------------

    /**
     * One playlist entry to be: the console it is from, the name of the file it starts
     * ([fileName], to find its library row), the [label] RetroArch shows, the file system [path]
     * (null when the storage gives none) and the [otherPaths] of the game's other files (the discs
     * of an `.m3u`, the tracks of a `.cue`): a playlist that already lists one of those already has the game.
     */
    data class Game(val consoleId: String, val fileName: String, val label: String, val path: String?, val otherPaths: List<String> = emptyList())

    /** The games of one libretro system, from all library consoles that are that system. */
    data class SystemGames(
        val system: String,
        val consoleIds: List<String>,
        val games: List<Game>,
        /** Games of several discs in one folder without an `.m3u`: not listed (each disc would be an entry of its own). */
        val discSetsWithoutM3u: Int = 0
    )

    data class Found(
        /** By system name. */
        val systems: List<SystemGames>,
        /** Games of consoles libretro has no playlist name for (arcade, PC…), and those consoles. */
        val noSystem: Int,
        val noSystemConsoles: List<String>
    )

    /**
     * The games of a library walk ([files]) by libretro system: one entry per game (the file it
     * starts, the `.m3u` of a disc set), only real games (no shortcuts, saves, states, patches,
     * images, BIOS or add-ons: [DuplicateFinder.isPlainGame]), none of [hiddenConsoles]. [m3uLines]
     * holds the lines of the `.m3u` files that could be read, by [DiskFile.fileId]: the discs they
     * name belong to them; without its lines an `.m3u` claims the discs named after it
     * (`Game (Disc 1).cue` for `Game.m3u`). [primaryRoot]: see [filesystemPath].
     */
    fun gamesOf(
        files: List<DiskFile>,
        hiddenConsoles: Set<String> = emptySet(),
        m3uLines: Map<String, List<String>> = emptyMap(),
        primaryRoot: String = DEFAULT_PRIMARY_ROOT
    ): Found {
        val entries = DuplicateFinder.entries(files).filter { e ->
            val console = e.consoleId
            console != null && console !in hiddenConsoles && DuplicateFinder.isPlainGame(e)
        }
        val bySystem = LinkedHashMap<String, MutableList<GameEntry>>()
        val consolesOf = LinkedHashMap<String, MutableSet<String>>()
        var noSystem = 0
        val noSystemConsoles = sortedSetOf<String>()
        for (e in entries) {
            val console = e.consoleId!!
            val system = systemOf(console)
            if (system == null) { noSystem++; noSystemConsoles += console; continue }
            bySystem.getOrPut(system) { ArrayList() } += e
            consolesOf.getOrPut(system) { sortedSetOf() } += console
        }
        val systems = bySystem.map { (system, list) ->
            val (games, withoutM3u) = gamesOfSystem(list, m3uLines, primaryRoot)
            SystemGames(system, consolesOf[system].orEmpty().toList(), games, withoutM3u)
        }.sortedBy { it.system.lowercase(Locale.ROOT) }
        return Found(systems, noSystem, noSystemConsoles.toList())
    }

    private class Candidate(val entry: GameEntry, val main: DiskFile) {
        val ext: String get() = main.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val stem: String get() = LibraryKeys.baseName(main.name)
        /** Every file of the game as `folder/name`, lower-cased: what an `.m3u` line resolves to. */
        val displayPaths: List<String> get() = entry.files.map { "${it.folder}/${it.name}".lowercase(Locale.ROOT) }
    }

    /** The entries of one system's games, and how many disc sets were left out for want of an `.m3u`. */
    private fun gamesOfSystem(entries: List<GameEntry>, m3uLines: Map<String, List<String>>, primaryRoot: String): Pair<List<Game>, Int> {
        val candidates = entries.mapNotNull { e ->
            val main = FrontendMetadata.mainFile(e.files.map { it.name }) ?: return@mapNotNull null
            Candidate(e, e.files.first { it.name == main })
        }
        val (playlists, others) = candidates.partition { it.ext == "m3u" }
        // The discs each .m3u stands for: the files its lines name, else the discs named after it in its folder.
        val claimed = HashMap<Candidate, MutableList<Candidate>>()
        val taken = HashSet<Candidate>()
        for (m3u in playlists) {
            val folder = m3u.main.folder
            val lines = m3uLines[m3u.main.fileId]
            val named = lines?.mapNotNull { line -> resolve(folder, line) }?.toSet()
            val stemKey = m3u.stem.lowercase(Locale.ROOT)
            for (disc in others) {
                if (disc in taken || disc.entry.consoleId != m3u.entry.consoleId) continue
                val byLine = named != null && disc.displayPaths.any { it in named }
                val byName = disc.main.dirId == m3u.main.dirId &&
                    PlaylistPlanner.split(disc.stem)?.first?.lowercase(Locale.ROOT) == stemKey
                if (byLine || byName) { taken += disc; claimed.getOrPut(m3u) { ArrayList() } += disc }
            }
        }
        val games = ArrayList<Game>()
        for (m3u in playlists) games += gameOf(m3u, claimed[m3u].orEmpty(), primaryRoot)
        // Discs of one game in one folder that no .m3u claims: several entries for one game, so none.
        val rest = others.filterNot { it in taken }
        val sets = rest.groupBy { c -> PlaylistPlanner.split(c.stem)?.let { "${c.main.dirId}|${it.first.lowercase(Locale.ROOT)}" } }
        var withoutM3u = 0
        for ((key, members) in sets) {
            val discs = members.mapNotNull { PlaylistPlanner.split(it.stem)?.second }.toSet()
            if (key != null && discs.size >= 2) { withoutM3u++; continue }
            members.forEach { games += gameOf(it, emptyList(), primaryRoot) }
        }
        return games to withoutM3u
    }

    /** `folder` + an `.m3u` line (relative, `./` and `\` allowed) as a lower-cased `folder/name`; null for an empty, comment or absolute line. */
    private fun resolve(folder: String, line: String): String? {
        val l = line.trim().removePrefix("\uFEFF").replace('\\', '/')
        if (l.isEmpty() || l.startsWith("#") || l.startsWith("/") || ':' in l) return null
        val parts = ArrayList<String>(folder.split('/'))
        for (part in l.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts += part
            }
        }
        return parts.joinToString("/").lowercase(Locale.ROOT)
    }

    private fun gameOf(c: Candidate, discs: List<Candidate>, primaryRoot: String): Game {
        // A per-game folder with generic file names (`Crazy Taxi/disc.gdi`) is named after the game, not after the file.
        val label = cleanLabel(if (c.entry.isFolderGame) c.entry.baseName else c.stem).ifEmpty { cleanLabel(c.entry.baseName) }
        val others = (c.entry.files.filter { it !== c.main } + discs.flatMap { it.entry.files })
            .mapNotNull { filesystemPath(it.uri, primaryRoot) }
        return Game(c.entry.consoleId.orEmpty(), c.main.name, label, filesystemPath(c.main.uri, primaryRoot), others)
    }

    /** One line without control characters. */
    private fun cleanLabel(s: String): String = s.map { if (it.isISOControl()) ' ' else it }.joinToString("").trim()

    // ---- Reading a playlist --------------------------------------------------------------------

    /** Why a playlist that is there is not changed. */
    enum class LeftAlone { OLD_FORMAT, COMPRESSED, UNREADABLE, TOO_BIG, FOLDER }

    /** What is in a playlist file as far as this tool is concerned. */
    sealed interface Read {
        /** No playlist of that name (or an empty file): a new one is written. */
        data object New : Read
        /** A JSON playlist; [root] is never changed (writing works on a copy). */
        class Json(val root: JsonObject) : Read {
            val items: List<JsonObject> get() = root.get("items")?.takeIf { it.isJsonArray }?.asJsonArray?.map { it.asJsonObject }.orEmpty()
        }
        data class Kept(val why: LeftAlone) : Read
    }

    /**
     * Reads a playlist file's bytes (null: there is none). Only clean JSON whose `items` is a list
     * of objects can be added to; a file that does not start with `{` is RetroArch's old
     * plain-text format (RetroArch's own rule), a `#RZIPv` header a compressed playlist, anything
     * else (broken JSON, a key twice, text that is not UTF-8) unreadable — all left alone.
     */
    fun read(bytes: ByteArray?): Read {
        if (bytes == null) return Read.New
        if (bytes.size > MAX_PLAYLIST_BYTES) return Read.Kept(LeftAlone.TOO_BIG)
        if (startsWith(bytes, RZIP)) return Read.Kept(LeftAlone.COMPRESSED)
        val text = strictUtf8(bytes)?.removePrefix("\uFEFF") ?: return Read.Kept(LeftAlone.UNREADABLE)
        if (text.isBlank()) return Read.New
        if (text.trimStart().first() != '{') {
            val binary = text.any { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }
            return Read.Kept(if (binary) LeftAlone.UNREADABLE else LeftAlone.OLD_FORMAT)
        }
        val root = runCatching { parseStrict(text) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
            ?: return Read.Kept(LeftAlone.UNREADABLE)
        val items = root.get("items")
        if (items != null && (!items.isJsonArray || items.asJsonArray.any { !it.isJsonObject })) return Read.Kept(LeftAlone.UNREADABLE)
        return Read.Json(root)
    }

    private val RZIP = "#RZIPv".toByteArray(Charsets.US_ASCII)

    private fun startsWith(bytes: ByteArray, prefix: ByteArray): Boolean =
        bytes.size >= prefix.size && prefix.indices.all { bytes[it] == prefix[it] }

    private fun strictUtf8(bytes: ByteArray): String? = runCatching {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    }.getOrNull()

    /** Strict JSON into a tree that keeps every key in its order and every number as written; a key twice is an error. */
    private fun parseStrict(text: String): JsonElement = JsonReader(StringReader(text)).use { reader ->
        reader.strictness = Strictness.STRICT
        val root = readValue(reader, 0)
        require(reader.peek() == JsonToken.END_DOCUMENT)
        root
    }

    private fun readValue(reader: JsonReader, depth: Int): JsonElement {
        require(depth <= MAX_DEPTH)
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                val o = JsonObject()
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    require(!o.has(name)) { "Duplicate key" }
                    o.add(name, readValue(reader, depth + 1))
                }
                reader.endObject()
                o
            }
            JsonToken.BEGIN_ARRAY -> {
                val a = JsonArray()
                reader.beginArray()
                while (reader.hasNext()) a.add(readValue(reader, depth + 1))
                reader.endArray()
                a
            }
            JsonToken.STRING -> JsonPrimitive(reader.nextString())
            JsonToken.NUMBER -> JsonPrimitive(BigDecimal(reader.nextString()))
            JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
            JsonToken.NULL -> { reader.nextNull(); JsonNull.INSTANCE }
            else -> throw IllegalArgumentException("Unexpected JSON")
        }
    }

    // ---- Planning ------------------------------------------------------------------------------

    enum class State {
        /** No playlist yet: a new one with [Plan.add] is written. */
        NEW,
        /** The playlist gets [Plan.add] at its end. */
        ADD,
        /** Every game that can be listed is in the playlist already (or none can be). */
        NOTHING_TO_ADD,
        /** Nothing to add, but the playlist has no default core yet and one is chosen: only that is written. */
        CORE,
        /** The playlist is there but is not changed ([Plan.leftAlone] says why). */
        LEFT_ALONE
    }

    data class Plan(
        val system: String,
        val consoleIds: List<String>,
        val state: State,
        /** New entries, in label order. */
        val add: List<Game>,
        /** Games the playlist already lists (by any of their files). */
        val already: Int,
        /** Games whose storage gives no file path RetroArch could open. */
        val noPath: Int,
        val discSetsWithoutM3u: Int,
        /** Games of this system on the device. */
        val games: Int,
        val leftAlone: LeftAlone? = null,
        /** Entries in the playlist now (0 for a new one). */
        val existingEntries: Int = 0
    ) {
        val fileName: String get() = fileName(system)
        /** Whether a run writes this playlist. */
        val writes: Boolean get() = state == State.NEW || state == State.ADD || state == State.CORE
    }

    /**
     * What writing [games] into [existing] would do. A game is in the playlist already when an
     * entry's path is one of its files ([pathKey]); a game without a path cannot be listed; two
     * games with the same path are one entry.
     */
    fun plan(games: SystemGames, existing: Read, core: DefaultCore? = null): Plan {
        val base = Plan(games.system, games.consoleIds, State.NOTHING_TO_ADD, emptyList(), 0, 0, games.discSetsWithoutM3u, games.games.size)
        if (existing is Read.Kept) return base.copy(state = State.LEFT_ALONE, leftAlone = existing.why)
        val items = (existing as? Read.Json)?.items.orEmpty()
        val known = HashSet<String>()
        for (item in items) {
            val path = item.get("path")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: continue
            if (path.isNotBlank()) known += pathKey(path)
        }
        val add = ArrayList<Game>()
        var already = 0
        var noPath = 0
        for (game in games.games) {
            val path = game.path
            if (path == null) { noPath++; continue }
            if ((listOf(path) + game.otherPaths).any { pathKey(it) in known }) { already++; continue }
            known += pathKey(path)
            add += game
        }
        val sorted = add.sortedWith(compareBy({ it.label.lowercase(Locale.ROOT) }, { it.label }, { it.path }))
        val state = when {
            sorted.isEmpty() -> if (core != null && existing is Read.Json && blankDefault(existing.root)) State.CORE else State.NOTHING_TO_ADD
            existing is Read.Json -> State.ADD
            else -> State.NEW
        }
        return base.copy(state = state, add = sorted, already = already, noPath = noPath, existingEntries = items.size)
    }

    // ---- Keeping up to date (6.2) -------------------------------------------------------------

    /**
     * The playlists the automatic refresh may touch after a rescan or a finished download: only
     * ones that exist already and have games to add ([State.ADD]), of systems that are switched
     * on. It never creates a playlist, never changes one that is left alone and never removes an
     * entry (a plan only adds). Null plans (no usable folder or listing) give nothing.
     */
    fun autoTargets(plans: List<Plan>?, excluded: Set<String>): List<Plan> =
        plans.orEmpty().filter { it.state == State.ADD && it.system !in excluded }

    /**
     * Whether finished downloads call for a refresh: another download finished since the last look
     * ([finishedBefore] to [finishedNow]) and none is still running, so a queue of many causes one
     * refresh at its end rather than one per game.
     */
    fun refreshAfterDownloads(finishedBefore: Int, finishedNow: Int, anyActive: Boolean): Boolean =
        finishedNow > finishedBefore && !anyActive

    // ---- Writing -------------------------------------------------------------------------------

    private val GSON = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create()

    /**
     * The playlist with [add] appended, as UTF-8 JSON: a copy of [existing] with everything in it
     * kept as it is, or a new playlist for [system]. Throws for a playlist that is left alone.
     */
    fun write(existing: Read, system: String, add: List<Game>, core: DefaultCore? = null): ByteArray {
        val root = when (existing) {
            is Read.Json -> existing.root.deepCopy()
            Read.New -> newPlaylist()
            is Read.Kept -> throw IllegalStateException("This playlist is left alone")
        }
        // Only a playlist without a default core gets one: a choice made in RetroArch stays.
        if (core != null && blankDefault(root)) {
            root.addProperty("default_core_path", core.path)
            root.addProperty("default_core_name", core.name)
        }
        val items = root.get("items")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray().also { root.add("items", it) }
        for (game in add) items.add(entry(game, system))
        return (GSON.toJson(root) + "\n").toByteArray(Charsets.UTF_8)
    }

    private fun newPlaylist(): JsonObject = JsonObject().apply {
        addProperty("version", VERSION)
        addProperty("default_core_path", "")
        addProperty("default_core_name", "")
        addProperty("label_display_mode", 0)
        addProperty("right_thumbnail_mode", 0)
        addProperty("left_thumbnail_mode", 0)
        addProperty("sort_mode", 0)
        add("items", JsonArray())
    }

    private fun entry(game: Game, system: String): JsonObject = JsonObject().apply {
        addProperty("path", requireNotNull(game.path) { "A game without a path is not listed" })
        addProperty("label", game.label)
        addProperty("core_path", DETECT)
        addProperty("core_name", DETECT)
        addProperty("crc32", NO_CRC)
        addProperty("db_name", fileName(system))
    }

    /** The lines of an `.m3u` file's [bytes]; null when it is too big or not text. */
    fun m3uLines(bytes: ByteArray): List<String>? {
        if (bytes.size > MAX_M3U_BYTES) return null
        val text = strictUtf8(bytes) ?: String(bytes, Charsets.ISO_8859_1)
        if (text.any { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }) return null
        return text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }
}
