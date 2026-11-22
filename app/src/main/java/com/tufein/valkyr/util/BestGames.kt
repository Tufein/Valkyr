package com.tufein.valkyr.util

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale

/** One RetroAchievements console and the names a library console may go by (see [RaConsoleMap]). */
data class RaConsoleInfo(
    val id: Int,
    val name: String,
    /** Spellings of the console; compared word by word with the console's id and name. */
    val keys: List<String>,
    /** Words that rule the console out (`Game Boy` is not `Game Boy Color`, PlayStation is not PlayStation 2). */
    val exclude: Set<String> = emptySet()
)

/**
 * Which RetroAchievements console a library console is: a fixed table of RA's console ids (from RA's
 * own list, cartridge and disc systems) with the spellings the app's console ids and names come in
 * (`nintendo_gba`, `sony_psp`, `super_nintendo_entertainment_system`, "PlayStation Portable"…).
 * The most specific spelling wins, a console that is only a look-alike (Jaguar CD, Famicom Disk
 * System, DSi…) maps to nothing. Pure JVM for the tests.
 */
object RaConsoleMap {

    val all: List<RaConsoleInfo> = listOf(
        RaConsoleInfo(1, "Mega Drive / Genesis", listOf("genesis", "mega drive", "md"), setOf("cd", "32x")),
        RaConsoleInfo(2, "Nintendo 64", listOf("nintendo 64", "n64")),
        RaConsoleInfo(3, "SNES / Super Famicom", listOf("super nintendo entertainment system", "super nintendo", "super nes", "super famicom", "snes", "sfc")),
        RaConsoleInfo(4, "Game Boy", listOf("game boy", "gb"), setOf("color", "colour", "advance", "gbc", "gba")),
        RaConsoleInfo(5, "Game Boy Advance", listOf("game boy advance", "gba")),
        RaConsoleInfo(6, "Game Boy Color", listOf("game boy color", "game boy colour", "gbc")),
        RaConsoleInfo(7, "NES / Famicom", listOf("nintendo entertainment system", "nes", "famicom"), setOf("disk", "fds", "super", "snes")),
        RaConsoleInfo(8, "PC Engine / TurboGrafx-16", listOf("pc engine", "turbografx 16", "turbografx", "pce", "tg16", "supergrafx"), setOf("cd", "cdrom", "duo")),
        RaConsoleInfo(9, "Sega CD / Mega CD", listOf("sega cd", "mega cd")),
        RaConsoleInfo(10, "32X", listOf("32x")),
        RaConsoleInfo(11, "Master System", listOf("master system", "sms")),
        RaConsoleInfo(12, "PlayStation", listOf("playstation", "psx", "ps1", "ps one", "psone"), setOf("2", "3", "4", "5", "vita", "portable", "psp", "ps2", "ps3", "ps4", "ps5", "minis")),
        RaConsoleInfo(13, "Atari Lynx", listOf("lynx")),
        RaConsoleInfo(14, "Neo Geo Pocket", listOf("neo geo pocket", "ngp", "ngpc")),
        RaConsoleInfo(15, "Game Gear", listOf("game gear", "gg")),
        RaConsoleInfo(16, "GameCube", listOf("gamecube", "gc", "ngc")),
        RaConsoleInfo(17, "Atari Jaguar", listOf("jaguar"), setOf("cd")),
        RaConsoleInfo(18, "Nintendo DS", listOf("nintendo ds", "nds", "ds")),
        RaConsoleInfo(19, "Wii", listOf("wii"), setOf("u")),
        RaConsoleInfo(20, "Wii U", listOf("wii u")),
        RaConsoleInfo(21, "PlayStation 2", listOf("playstation 2", "ps2")),
        RaConsoleInfo(22, "Xbox", listOf("xbox"), setOf("360", "one", "series")),
        RaConsoleInfo(23, "Odyssey² / Videopac", listOf("odyssey 2", "videopac")),
        RaConsoleInfo(24, "Pokémon mini", listOf("pokemon mini", "pokemini")),
        RaConsoleInfo(25, "Atari 2600", listOf("atari 2600", "2600")),
        RaConsoleInfo(26, "MS-DOS", listOf("ms dos", "msdos", "dos"), setOf("amiga", "pc")),
        RaConsoleInfo(27, "Arcade", listOf("arcade", "mame")),
        RaConsoleInfo(28, "Virtual Boy", listOf("virtual boy", "vb")),
        RaConsoleInfo(29, "MSX", listOf("msx", "msx2")),
        RaConsoleInfo(30, "Commodore 64", listOf("c64", "commodore 64", "commodore64")),
        RaConsoleInfo(31, "ZX81", listOf("zx81", "zx 81")),
        RaConsoleInfo(32, "Oric", listOf("oric", "oric atmos")),
        RaConsoleInfo(33, "SG-1000", listOf("sg 1000")),
        RaConsoleInfo(34, "VIC-20", listOf("vic20", "vic 20", "vic-20")),
        RaConsoleInfo(35, "Amiga", listOf("amiga", "commodore amiga")),
        RaConsoleInfo(36, "Atari ST", listOf("atari st", "atari ST", "atari ste")),
        RaConsoleInfo(37, "Amstrad CPC", listOf("amstrad cpc", "cpc")),
        RaConsoleInfo(38, "Apple II", listOf("apple 2", "apple ii", "appleii", "apple 2e")),
        RaConsoleInfo(39, "Sega Saturn", listOf("saturn")),
        RaConsoleInfo(40, "Dreamcast", listOf("dreamcast", "dc")),
        RaConsoleInfo(41, "PlayStation Portable", listOf("playstation portable", "psp")),
        RaConsoleInfo(42, "CD-i", listOf("cd i", "cdi", "philips cdi")),
        RaConsoleInfo(43, "3DO", listOf("3do")),
        RaConsoleInfo(44, "ColecoVision", listOf("colecovision", "coleco")),
        RaConsoleInfo(45, "Intellivision", listOf("intellivision")),
        RaConsoleInfo(46, "Vectrex", listOf("vectrex")),
        RaConsoleInfo(47, "PC-8800", listOf("pc 8800", "pc88")),
        RaConsoleInfo(48, "PC-9800", listOf("pc 9800", "pc98")),
        RaConsoleInfo(49, "PC-FX", listOf("pc fx")),
        RaConsoleInfo(50, "Atari 5200", listOf("atari 5200", "5200")),
        RaConsoleInfo(51, "Atari 7800", listOf("atari 7800", "7800")),
        RaConsoleInfo(52, "Sharp X68000", listOf("x68000", "x68k", "sharp x68000")),
        RaConsoleInfo(53, "WonderSwan", listOf("wonderswan", "ws", "wsc")),
        RaConsoleInfo(54, "Cassette Vision", listOf("cassette vision"), setOf("super")),
        RaConsoleInfo(55, "Super Cassette Vision", listOf("super cassette vision")),
        RaConsoleInfo(56, "Neo Geo CD", listOf("neo geo cd")),
        RaConsoleInfo(57, "FM Towns", listOf("fm towns", "fmtowns")),
        RaConsoleInfo(58, "ZX Spectrum", listOf("zx spectrum", "spectrum")),
        RaConsoleInfo(59, "Game & Watch", listOf("game and watch", "game watch", "g w")),
        RaConsoleInfo(61, "Nokia N-Gage", listOf("n gage", "ngage", "nokia ngage")),
        RaConsoleInfo(62, "Nintendo 3DS", listOf("nintendo 3ds", "3ds", "n3ds")),
        RaConsoleInfo(63, "Watara Supervision", listOf("supervision", "watara")),
        RaConsoleInfo(64, "Sharp X1", listOf("sharp x1", "x1")),
        RaConsoleInfo(65, "TIC-80", listOf("tic 80", "tic80")),
        RaConsoleInfo(66, "Thomson TO8", listOf("thomson to8", "to8")),
        RaConsoleInfo(67, "PC6000", listOf("pc 6000", "pc6000")),
        RaConsoleInfo(68, "Pico", listOf("pico", "raspberry pico")),
        RaConsoleInfo(69, "Mega Duck", listOf("mega duck")),
        RaConsoleInfo(70, "Zeebo", listOf("zeebo")),
        RaConsoleInfo(71, "Arduboy", listOf("arduboy")),
        RaConsoleInfo(72, "WASM-4", listOf("wasm 4", "wasm4")),
        RaConsoleInfo(73, "Arcadia 2001", listOf("arcadia 2001")),
        RaConsoleInfo(74, "Interton VC 4000", listOf("interton vc 4000", "vc 4000")),
        RaConsoleInfo(75, "Elektor TV Games", listOf("elektor tv games")),
        RaConsoleInfo(76, "PC Engine CD / TurboGrafx-CD", listOf("pc engine cd", "turbografx cd", "turbografx 16 cd", "pce cd", "turbo duo")),
        RaConsoleInfo(79, "TI-83", listOf("ti 83", "ti83")),
        RaConsoleInfo(80, "Uzebox", listOf("uzebox"))
    )

    private val byRaId = all.associateBy { it.id }

    fun byId(raId: Int): RaConsoleInfo? = byRaId[raId]

    /**
     * The RA console of a library console, from its [consoleId] (`nintendo_gba`) and, optionally, its
     * display [consoleName]; null when RetroAchievements has no such system.
     */
    fun forConsole(consoleId: String, consoleName: String = ""): RaConsoleInfo? {
        val sources = listOf(tokens(consoleId), tokens(consoleName)).filter { it.isNotEmpty() }
        var best: RaConsoleInfo? = null
        var bestScore = 0
        for (entry in all) {
            val score = sources.maxOfOrNull { score(entry, it) } ?: 0
            if (score > bestScore) { best = entry; bestScore = score }
        }
        return best
    }

    /** [forConsole] for many consoles at once: console id to RA console, only those RA has. */
    fun mapAll(consoles: List<Pair<String, String>>): Map<String, RaConsoleInfo> {
        val out = LinkedHashMap<String, RaConsoleInfo>()
        for ((id, name) in consoles) forConsole(id, name)?.let { out[id] = it }
        return out
    }

    /** Length of the best spelling of [entry] found in [tokens] as a run of whole words, 0 for none. */
    private fun score(entry: RaConsoleInfo, tokens: List<String>): Int {
        if (entry.exclude.any { it in tokens }) return 0
        var best = 0
        for (key in entry.keys) {
            val parts = key.split(' ')
            val compact = parts.joinToString("")
            if (compact.length > best && runMatches(tokens, compact)) best = compact.length
        }
        return best
    }

    /** Whether some run of adjacent [tokens], joined, spells [compact] ("game","boy" or "gameboy"). */
    private fun runMatches(tokens: List<String>, compact: String): Boolean {
        for (i in tokens.indices) {
            val sb = StringBuilder()
            for (j in i until tokens.size) {
                sb.append(tokens[j])
                if (sb.length > compact.length) break
                if (sb.length == compact.length) { if (sb.toString() == compact) return true; break }
            }
        }
        return false
    }

    internal fun tokens(text: String): List<String> =
        Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
            .split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
}

/** A game of RA's list for a console, as the ranking needs it. [players] is filled once RA was asked. */
data class BestGame(
    val id: Int,
    val title: String,
    val achievements: Int,
    val points: Int,
    /** RA's icon path ("/Images/067895.png"), shown when no cover is found. */
    val icon: String? = null,
    /** Extra facts from RA's list and (for the candidates) its game-details call. */
    val leaderboards: Int = 0,
    val released: String? = null,
    val genre: String? = null,
    val hardcore: Int = 0
)

/** How the list is ordered, and so what the screen may claim about it. */
enum class RankBasis {
    /** Distinct players RA counts for each of the top games by achievement points. */
    PLAYERS,
    /** Distinct hardcore players of the same games. */
    HARDCORE,
    /** Achievement points of the set: a stand-in for popularity, as the cheap list call allows. */
    POINTS
}

/** A second, purely local ordering of the same ranked list. */
enum class BestSort { RANK, TITLE, YEAR, POINTS, PLAYERS }

/** Which games of the list are shown; the counts above the list always cover all of them. */
enum class OwnFilter { ALL, DEVICE, SOURCE, MISSING }

data class RankedGame(val rank: Int, val game: BestGame, val players: Int?, val hardcore: Int? = null)

/** The ranked list plus how much of it rests on player counts: [checked] of [candidates] were asked. */
data class Ranking(val basis: RankBasis, val games: List<RankedGame>, val checked: Int, val candidates: Int)

/** The extra facts one `API_GetGameExtended` answer carries, besides the player count. */
data class GameDetail(val players: Int, val hardcore: Int, val released: String?, val genre: String?)

/** What one console's player counts look like after a week, including the hardcore counts. */
data class PlayerStats(val players: Map<Int, Int>, val hardcore: Map<Int, Int>, val details: Map<Int, GameDetail>)

/** A player count with the time it was fetched, for the 7-day cache. */
data class PlayerCount(
    val players: Int,
    val at: Long,
    /** Null when an older cache entry predates hardcore counts; a real zero is kept. */
    val hardcore: Int? = null,
    val released: String? = null,
    val genre: String? = null
)

enum class OwnState { ON_DEVICE, IN_SOURCE, MISSING }

/** A ranked game against the library: where it is. [sourceIndices] index the source names given to [BestGames.match]. */
data class GameMatch(val ranked: RankedGame, val state: OwnState, val deviceName: String?, val sourceIndices: List<Int>)

/**
 * "Best games per console" from RetroAchievements: the URLs and parsers of the two calls used
 * (`API_GetGameList` for the games with achievement sets and their points, `API_GetGameExtended` for
 * `NumDistinctPlayers`), the ranking, its cache format, and the match of each ranked game against
 * what is on the device and in the sources. RA has no "most played" list, so the list is ranked by
 * achievement points and only the top [CANDIDATES] get a (throttled) player count. Pure JVM for the tests.
 */
object BestGames {

    /** How many of the top games (by points) get their player count asked, and how long the list is. */
    const val CANDIDATES = 100

    /** Games kept per console in the cache: more than the list shows, a few KB in all. */
    const val KEEP = 500

    const val WEEK_MS = 7L * 24 * 3_600_000

    private const val API_BASE = "https://retroachievements.org/API/"

    // ---- URLs (the key is the user's own web API key; never log these) ----

    /** `API_GetGameList.php`: games of one console that have achievements (`f=1`), without their hashes. */
    fun gameListUrl(raConsoleId: Int, user: String, key: String): String =
        "${API_BASE}API_GetGameList.php?z=${enc(user)}&y=${enc(key)}&i=$raConsoleId&f=1"

    /** `API_GetGameExtended.php`: one game with its player count (and its achievements, which are not read). */
    fun gameExtendedUrl(gameId: Int, user: String, key: String): String =
        "${API_BASE}API_GetGameExtended.php?z=${enc(user)}&y=${enc(key)}&i=$gameId"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ---- Parsing ----

    /** Games with achievements of `API_GetGameList`; anything that is not a list gives nothing. Never throws. */
    fun parseGameList(json: String): List<BestGame> = runCatching {
        val root = JsonParser.parseString(json)
        if (!root.isJsonArray) return@runCatching emptyList()
        root.asJsonArray.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.int("ID", "id") ?: return@mapNotNull null
            val count = o.int("NumAchievements") ?: 0
            if (id <= 0 || count <= 0) return@mapNotNull null
            BestGame(
                id, o.str("Title").orEmpty().trim(), count, (o.int("Points") ?: 0).coerceAtLeast(0),
                o.str("ImageIcon")?.takeIf { it.isNotBlank() },
                (o.int("NumLeaderboards", "numLeaderboards") ?: 0).coerceAtLeast(0)
            )
        }.distinctBy { it.id }
    }.getOrDefault(emptyList())

    /**
     * The distinct player count of `API_GetGameExtended`: 0 for a game that carries none, null when the
     * body is not a game at all (an error object, garbage).
     */
    fun parsePlayers(json: String): Int? = parseDetails(json)?.players

    /** The player counts, hardcore counts, release date and genre of one `API_GetGameExtended` answer. */
    fun parseDetails(json: String): GameDetail? = runCatching {
        val root = JsonParser.parseString(json) as? JsonObject ?: return@runCatching null
        if (root.int("ID", "Id", "id") == null && root.str("Title") == null) return@runCatching null
        GameDetail(
            players = root.int("NumDistinctPlayers", "NumDistinctPlayersCasual", "players_total")?.coerceAtLeast(0) ?: 0,
            hardcore = (root.int("NumDistinctPlayersHardcore", "numDistinctPlayersHardcore", "players_hardcore") ?: 0).coerceAtLeast(0),
            released = root.str("Released", "released")?.take(10)?.takeIf { it.length >= 4 },
            genre = root.str("Genre", "genre")?.takeIf { it.isNotBlank() }
        )
    }.getOrNull()

    private fun JsonObject.str(vararg names: String): String? {
        for (n in names) {
            val el = get(n) ?: continue
            if (el.isJsonNull || !el.isJsonPrimitive) continue
            return el.asString
        }
        return null
    }

    /** RA's numbers arrive as numbers or as strings ("1234", "1,234", "12.0"). */
    private fun JsonObject.int(vararg names: String): Int? {
        for (n in names) {
            val el: JsonElement = get(n) ?: continue
            if (el.isJsonNull || !el.isJsonPrimitive) continue
            val text = el.asString.trim().replace(",", "")
            text.toIntOrNull()?.let { return it }
            text.toDoubleOrNull()?.let { return it.toInt() }
        }
        return null
    }

    // ---- What counts as a game ----

    /**
     * Whether an RA entry is a real game of the console: hacks, homebrew, demos, prototypes and test
     * kits carry a `~Tag~` prefix on RA, and a subset (`[Subset - Bonus]`) is extra achievements of
     * another game, not a game of its own.
     */
    fun isOfficial(title: String): Boolean {
        val t = title.trim()
        if (t.isEmpty() || t.startsWith("~")) return false
        return !t.contains("[Subset", ignoreCase = true)
    }

    // ---- Ranking ----

    private val byPoints = compareByDescending<BestGame> { it.points }
        .thenByDescending { it.achievements }
        .thenBy { it.title.lowercase(Locale.ROOT) }
        .thenBy { it.id }

    /** The games whose player counts are worth asking: the official ones with the biggest achievement sets. */
    fun candidates(games: List<BestGame>, size: Int = CANDIDATES): List<BestGame> =
        games.filter { isOfficial(it.title) }.sortedWith(byPoints).take(size)

    /**
     * The list for a console. Only when every candidate has a player count ([players]: game id to count)
     * is it ranked by players ([RankBasis.PLAYERS]); otherwise it is the same candidates by points
     * ([RankBasis.POINTS]), with the counts that are known shown next to them but not used to order.
     */
    fun rank(games: List<BestGame>, players: Map<Int, Int>, size: Int = CANDIDATES): Ranking =
        rank(games, players, emptyMap(), size, RankBasis.PLAYERS)

    /**
     * The list for a console. Only when every candidate has a count for [wanted] is it ordered by
     * that count; otherwise it stays ordered by points and the screen says so.
     */
    fun rank(
        games: List<BestGame>,
        players: Map<Int, Int>,
        hardcore: Map<Int, Int> = emptyMap(),
        size: Int = CANDIDATES,
        wanted: RankBasis = RankBasis.PLAYERS
    ): Ranking {
        val pool = candidates(games, size)
        val counts = if (wanted == RankBasis.HARDCORE) hardcore else players
        val checked = pool.count { it.id in counts }
        val complete = pool.isNotEmpty() && checked == pool.size
        val ordered = if (complete && wanted != RankBasis.POINTS) {
            pool.sortedWith(compareByDescending<BestGame> { counts[it.id] ?: 0 }.then(byPoints))
        } else pool
        val basis = if (complete) wanted else RankBasis.POINTS
        return Ranking(basis, ordered.mapIndexed { i, g ->
            RankedGame(i + 1, g, players[g.id], hardcore[g.id])
        }, checked, pool.size)
    }

    /** A local re-ordering; the rank number stays the one RA's numbers earned. */
    fun sort(rows: List<GameMatch>, by: BestSort, details: Map<Int, GameDetail> = emptyMap()): List<GameMatch> = when (by) {
        BestSort.RANK -> rows.sortedBy { it.ranked.rank }
        BestSort.TITLE -> rows.sortedBy { it.ranked.game.title.lowercase(Locale.ROOT) }
        // The release date comes from the extended call, not from the list; fall back to the list when absent.
        BestSort.YEAR -> rows.sortedByDescending { details[it.ranked.game.id]?.released ?: it.ranked.game.released.orEmpty() }
        BestSort.POINTS -> rows.sortedWith(compareByDescending<GameMatch> { it.ranked.game.points }.thenBy { it.ranked.rank })
        BestSort.PLAYERS -> rows.sortedWith(compareByDescending<GameMatch> { it.ranked.players ?: -1 }.thenBy { it.ranked.rank })
    }

    fun filter(rows: List<GameMatch>, by: OwnFilter): List<GameMatch> = when (by) {
        OwnFilter.ALL -> rows
        OwnFilter.DEVICE -> rows.filter { it.state == OwnState.ON_DEVICE }
        OwnFilter.SOURCE -> rows.filter { it.state == OwnState.IN_SOURCE }
        OwnFilter.MISSING -> rows.filter { it.state == OwnState.MISSING }
    }

    /** The candidates still without a (fresh) count: what a throttled fetch has left to do, in ranking order. */
    fun pending(candidates: List<BestGame>, players: Map<Int, Int>): List<BestGame> = candidates.filter { it.id !in players }

    // ---- Cache format ----

    /** The [KEEP] biggest official sets, compact: the cache of one console's list. */
    fun encodeGames(games: List<BestGame>): String {
        val arr = JsonArray()
        games.filter { isOfficial(it.title) }.sortedWith(byPoints).take(KEEP).forEach { g ->
            arr.add(JsonObject().apply {
                addProperty("i", g.id); addProperty("t", g.title); addProperty("a", g.achievements); addProperty("p", g.points)
                g.icon?.let { addProperty("m", it) }
                if (g.leaderboards > 0) addProperty("l", g.leaderboards)
                g.released?.let { addProperty("r", it) }
                g.genre?.let { addProperty("g", it) }
                if (g.hardcore > 0) addProperty("h", g.hardcore)
            })
        }
        return arr.toString()
    }

    fun decodeGames(json: String): List<BestGame> = runCatching {
        JsonParser.parseString(json).asJsonArray.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.int("i") ?: return@mapNotNull null
            BestGame(
                id, o.str("t").orEmpty(), o.int("a") ?: 0, o.int("p") ?: 0, o.str("m"),
                o.int("l") ?: 0, o.str("r"), o.str("g"), o.int("h") ?: 0
            )
        }
    }.getOrDefault(emptyList())

    fun encodePlayers(counts: Map<Int, PlayerCount>): String {
        val root = JsonObject()
        counts.forEach { (id, c) -> root.add(id.toString(), JsonObject().apply {
            addProperty("n", c.players); addProperty("at", c.at)
            c.hardcore?.let { addProperty("h", it) }
            c.released?.let { addProperty("r", it) }
            c.genre?.let { addProperty("g", it) }
        }) }
        return root.toString()
    }

    fun decodePlayers(json: String): Map<Int, PlayerCount> = runCatching {
        val root = JsonParser.parseString(json).asJsonObject
        val out = LinkedHashMap<Int, PlayerCount>()
        for ((key, el) in root.entrySet()) {
            val id = key.toIntOrNull() ?: continue
            val o = el as? JsonObject ?: continue
            out[id] = PlayerCount(
                o.int("n") ?: continue,
                (o.get("at")?.takeIf { it.isJsonPrimitive }?.asLong) ?: 0L,
                o.int("h"), o.str("r"), o.str("g")
            )
        }
        out
    }.getOrDefault(emptyMap())

    /** Whether something fetched at [at] is still good at [now] (under a week old, not from the future). */
    fun isFresh(at: Long, now: Long): Boolean = at in 1..now && now - at < WEEK_MS

    /** The counts still good at [now], as game id to players. */
    fun freshPlayers(counts: Map<Int, PlayerCount>, now: Long): Map<Int, Int> =
        counts.filterValues { isFresh(it.at, now) }.mapValues { it.value.players }

    /** All fresh facts of one console, as the screen needs them. */
    fun freshStats(counts: Map<Int, PlayerCount>, now: Long): PlayerStats {
        val fresh = counts.filterValues { isFresh(it.at, now) }
        return PlayerStats(
            fresh.mapValues { it.value.players },
            // An entry from the older cache has no hardcore count: leave it out rather than claim a zero.
            fresh.mapNotNull { (id, c) -> c.hardcore?.let { id to it } }.toMap(),
            fresh.mapValues { (_, c) ->
                // A count from an older cache has no extra facts; keep what we already have in the list.
                GameDetail(c.players, c.hardcore ?: 0, c.released, c.genre)
            }
        )
    }

    // ---- Matching against the library ----

    /** The words that identify a game, order and tags aside: what [GameTitleCleaner.sameTitle] compares. */
    fun titleKey(name: String): String = GameTitleCleaner.words(name).sorted().joinToString(" ")

    /** RA writes a game's other names after a bar ("Rockman X | Mega Man X"); a file may carry either. */
    fun alternatives(title: String): List<String> = title.split('|').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * Where each ranked game is: on the device ([deviceNames]: file names, with or without extension, of
     * the console's folders), else in a source ([sourceNames]: the listed file names; the result holds
     * their indices so the caller finds its rows again), else missing. A game matches a file when both
     * have the same words once tags, the extension and "the" are left out, so "Super Mario World" never
     * takes "Super Mario World 2 - Yoshi's Island" (the rule of [GameTitleCleaner.sameTitle]). Any of a
     * game's [alternatives] may be the one the file carries.
     */
    fun match(ranked: List<RankedGame>, deviceNames: Collection<String>, sourceNames: List<String>): List<GameMatch> {
        val device = HashMap<String, String>()
        for (name in deviceNames) {
            val key = titleKey(name)
            if (key.isNotEmpty()) device.putIfAbsent(key, name)
        }
        val sources = HashMap<String, MutableList<Int>>()
        sourceNames.forEachIndexed { i, name ->
            val key = titleKey(name)
            if (key.isNotEmpty()) sources.getOrPut(key) { ArrayList() } += i
        }
        return ranked.map { r ->
            val keys = alternatives(r.game.title).map { titleKey(it) }.filter { it.isNotEmpty() }.distinct()
            val onDevice = keys.firstNotNullOfOrNull { device[it] }
            val inSources = keys.flatMap { sources[it].orEmpty() }.distinct().sorted()
            when {
                keys.isEmpty() -> GameMatch(r, OwnState.MISSING, null, emptyList())
                onDevice != null -> GameMatch(r, OwnState.ON_DEVICE, onDevice, inSources)
                inSources.isNotEmpty() -> GameMatch(r, OwnState.IN_SOURCE, null, inSources)
                else -> GameMatch(r, OwnState.MISSING, null, emptyList())
            }
        }
    }

    /** The games a "Download the ones I can get" would take: in a source and not on the device. */
    fun downloadable(matches: List<GameMatch>): List<GameMatch> = matches.filter { it.state == OwnState.IN_SOURCE }

    /**
     * The games for "Put the missing ones on the wishlist": found nowhere, titled as RA writes them (its
     * punctuation squashes to the same search key as a No-Intro name), the first name when RA gives several.
     */
    fun wishTitles(matches: List<GameMatch>): List<String> =
        matches.filter { it.state == OwnState.MISSING }.mapNotNull { alternatives(it.ranked.game.title).firstOrNull() }.filter { it.length >= 2 }

    /** "3 of 40 on the device": counts of each state. */
    fun tally(matches: List<GameMatch>): Triple<Int, Int, Int> =
        Triple(matches.count { it.state == OwnState.ON_DEVICE }, matches.count { it.state == OwnState.IN_SOURCE }, matches.count { it.state == OwnState.MISSING })

    /** A game's icon as a full URL, or null. */
    fun iconUrl(game: BestGame): String? = RaApi.mediaUrl(game.icon)
}
