package com.tufein.valkyr.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BestGamesTest {

    private fun game(id: Int, title: String, points: Int, achievements: Int = 20, icon: String? = null) = BestGame(id, title, achievements, points, icon)

    // ---- parsing ----

    @Test
    fun `game list keeps games with achievements and reads loose numbers`() {
        val json = """
            [
              {"Title":"Super Mario World","ID":228,"ConsoleID":3,"ImageIcon":"/Images/001.png","NumAchievements":"57","NumLeaderboards":0,"Points":"1,000"},
              {"Title":"Empty Set","ID":5,"NumAchievements":0,"Points":0},
              {"Title":"No Id","NumAchievements":3,"Points":10},
              {"Title":"Dup","ID":9,"NumAchievements":3,"Points":10},
              {"Title":"Dup again","ID":9,"NumAchievements":4,"Points":12}
            ]
        """.trimIndent()
        val games = BestGames.parseGameList(json)
        assertEquals(listOf(228, 9), games.map { it.id })
        assertEquals(57, games[0].achievements)
        assertEquals(1000, games[0].points)
        assertEquals("/Images/001.png", games[0].icon)
    }

    @Test
    fun `game list of garbage or an error object is empty`() {
        assertTrue(BestGames.parseGameList("<html>").isEmpty())
        assertTrue(BestGames.parseGameList("""{"message":"Invalid API Key"}""").isEmpty())
        assertTrue(BestGames.parseGameList("[]").isEmpty())
    }

    @Test
    fun `players are read from the extended game`() {
        assertEquals(12345, BestGames.parsePlayers("""{"ID":228,"Title":"X","NumDistinctPlayers":12345,"Achievements":{}}"""))
        assertEquals(777, BestGames.parsePlayers("""{"ID":228,"Title":"X","NumDistinctPlayers":"777"}"""))
        assertEquals(500, BestGames.parsePlayers("""{"ID":228,"Title":"X","NumDistinctPlayersCasual":500}"""))
        // A game that carries no count is a game with 0, not a failure.
        assertEquals(0, BestGames.parsePlayers("""{"ID":228,"Title":"X"}"""))
    }

    @Test
    fun `players of something that is not a game is null`() {
        assertNull(BestGames.parsePlayers("""{"message":"Unauthenticated."}"""))
        assertNull(BestGames.parsePlayers("[]"))
        assertNull(BestGames.parsePlayers("nope"))
    }

    @Test
    fun `urls carry the console, the game and an escaped key`() {
        assertEquals(
            "https://retroachievements.org/API/API_GetGameList.php?z=Me+Too&y=a%26b&i=3&f=1",
            BestGames.gameListUrl(3, "Me Too", "a&b")
        )
        assertEquals(
            "https://retroachievements.org/API/API_GetGameExtended.php?z=u&y=k&i=228",
            BestGames.gameExtendedUrl(228, "u", "k")
        )
    }

    // ---- what counts as a game ----

    @Test
    fun `hacks, homebrew and subsets are not games of the console`() {
        assertFalse(BestGames.isOfficial("~Hack~ Super Mario World: Something"))
        assertFalse(BestGames.isOfficial("~Homebrew~ Cool Thing"))
        assertFalse(BestGames.isOfficial("~Demo~ Beta"))
        assertFalse(BestGames.isOfficial("Super Mario World [Subset - Bonus]"))
        assertFalse(BestGames.isOfficial("  "))
        assertTrue(BestGames.isOfficial("Super Mario World"))
        assertTrue(BestGames.isOfficial("Legend of Zelda, The: A Link to the Past"))
    }

    // ---- ranking ----

    @Test
    fun `candidates are the biggest official sets, ties kept stable`() {
        val games = listOf(
            game(1, "B Game", 300), game(2, "~Hack~ Huge", 5000), game(3, "A Game", 300),
            game(4, "Top", 900), game(5, "Subset Host [Subset - Bonus]", 800), game(6, "Small", 10)
        )
        assertEquals(listOf(4, 3, 1), BestGames.candidates(games, size = 3).map { it.id })
    }

    @Test
    fun `without every player count the list is ranked by points and says so`() {
        val games = listOf(game(1, "A", 100), game(2, "B", 300), game(3, "C", 200))
        val ranking = BestGames.rank(games, mapOf(1 to 9_000), size = 3)
        assertEquals(RankBasis.POINTS, ranking.basis)
        assertEquals(listOf(2, 3, 1), ranking.games.map { it.game.id })
        assertEquals(listOf(1, 2, 3), ranking.games.map { it.rank })
        assertEquals(1, ranking.checked)
        assertEquals(3, ranking.candidates)
        // The count that is known is still shown.
        assertEquals(9_000, ranking.games.last().players)
    }

    @Test
    fun `with every player count the candidates are ranked by players`() {
        val games = listOf(game(1, "A", 100), game(2, "B", 300), game(3, "C", 200), game(4, "Outside", 1))
        val ranking = BestGames.rank(games, mapOf(1 to 9_000, 2 to 100, 3 to 9_000), size = 3)
        assertEquals(RankBasis.PLAYERS, ranking.basis)
        // 1 and 3 tie on players: the bigger set (3, 200 points) goes first.
        assertEquals(listOf(3, 1, 2), ranking.games.map { it.game.id })
        assertEquals(3, ranking.checked)
        assertEquals(listOf(9_000, 9_000, 100), ranking.games.map { it.players })
    }

    @Test
    fun `an empty console has an empty points ranking`() {
        val ranking = BestGames.rank(emptyList(), emptyMap())
        assertEquals(RankBasis.POINTS, ranking.basis)
        assertTrue(ranking.games.isEmpty())
    }

    @Test
    fun `pending is what still lacks a count, in ranking order`() {
        val pool = listOf(game(1, "A", 3), game(2, "B", 2), game(3, "C", 1))
        assertEquals(listOf(1, 3), BestGames.pending(pool, mapOf(2 to 10)).map { it.id })
    }

    // ---- cache ----

    @Test
    fun `game cache keeps only official sets, the biggest first, and round trips`() {
        val games = listOf(game(1, "Small", 10, icon = "/Images/1.png"), game(2, "~Hack~ X", 999), game(3, "Big", 500))
        val back = BestGames.decodeGames(BestGames.encodeGames(games))
        assertEquals(listOf(3, 1), back.map { it.id })
        assertEquals("/Images/1.png", back[1].icon)
        assertNull(back[0].icon)
        assertEquals(500, back[0].points)
    }

    @Test
    fun `game cache is bounded`() {
        val many = (1..500).map { game(it, "Game $it", it) }
        assertEquals(BestGames.KEEP, BestGames.decodeGames(BestGames.encodeGames(many)).size)
    }

    @Test
    fun `broken caches read as empty`() {
        assertTrue(BestGames.decodeGames("{").isEmpty())
        assertTrue(BestGames.decodePlayers("[1,2]").isEmpty())
    }

    @Test
    fun `player cache round trips and a week is the limit`() {
        val now = 10L * BestGames.WEEK_MS
        val counts = mapOf(1 to PlayerCount(500, now - 1_000), 2 to PlayerCount(40, now - BestGames.WEEK_MS - 1), 3 to PlayerCount(7, now + 5_000))
        val back = BestGames.decodePlayers(BestGames.encodePlayers(counts))
        assertEquals(counts, back)
        // Over a week old and from the future are both not trusted.
        assertEquals(mapOf(1 to 500), BestGames.freshPlayers(back, now))
        assertTrue(BestGames.isFresh(now - BestGames.WEEK_MS + 1, now))
        assertFalse(BestGames.isFresh(0L, now))
    }

    // ---- matching ----

    private fun ranked(vararg titles: String) = titles.mapIndexed { i, t -> RankedGame(i + 1, game(i + 1, t, 100), null) }

    @Test
    fun `a game is on the device, in a source or missing`() {
        val r = ranked("Super Mario World", "Donkey Kong Country", "Chrono Trigger")
        val matches = BestGames.match(
            r,
            deviceNames = listOf("Super Mario World (USA).sfc", "super mario world (usa)", "Other.sfc"),
            sourceNames = listOf("Chrono Trigger (USA).zip", "Chrono Trigger (Europe).zip", "Super Mario World (USA).zip")
        )
        assertEquals(listOf(OwnState.ON_DEVICE, OwnState.MISSING, OwnState.IN_SOURCE), matches.map { it.state })
        assertEquals("Super Mario World (USA).sfc", matches[0].deviceName)
        // Both versions of Chrono Trigger are found, by index.
        assertEquals(listOf(0, 1), matches[2].sourceIndices)
        assertTrue(matches[1].sourceIndices.isEmpty())
    }

    @Test
    fun `a sequel is not the game`() {
        val r = ranked("Super Mario World")
        val m = BestGames.match(r, listOf("Super Mario World 2 - Yoshi's Island (USA).sfc"), listOf("Super Mario World 2 - Yoshi's Island (USA).zip"))
        assertEquals(OwnState.MISSING, m.single().state)
    }

    @Test
    fun `RA's and No-Intro's ways of writing a title meet`() {
        val r = ranked("Legend of Zelda, The: A Link to the Past", "Pokémon Emerald Version", "Sonic the Hedgehog 2")
        val m = BestGames.match(
            r,
            deviceNames = listOf("Legend of Zelda, The - A Link to the Past (USA).sfc"),
            sourceNames = listOf("Pokemon - Emerald Version (USA, Europe).gba", "Sonic The Hedgehog 2 (World) (Rev A).md")
        )
        assertEquals(listOf(OwnState.ON_DEVICE, OwnState.IN_SOURCE, OwnState.IN_SOURCE), m.map { it.state })
    }

    @Test
    fun `RA's other names after a bar are matched too`() {
        val r = ranked("Rockman X | Mega Man X", "Mother 3 | Something Else")
        val m = BestGames.match(r, emptyList(), listOf("Mega Man X (USA).zip", "Rockman X (Japan).zip", "Mega Man X2 (USA).zip"))
        assertEquals(OwnState.IN_SOURCE, m[0].state)
        // Both spellings are found, once each, in the order of the list.
        assertEquals(listOf(0, 1), m[0].sourceIndices)
        assertEquals(OwnState.MISSING, m[1].state)
        assertEquals(listOf("Mother 3"), BestGames.wishTitles(m.drop(1)))
        assertEquals(listOf("Rockman X", "Mega Man X"), BestGames.alternatives("Rockman X | Mega Man X"))
        assertTrue(BestGames.alternatives(" | ").isEmpty())
    }

    @Test
    fun `matching agrees with sameTitle`() {
        val pairs = listOf(
            "Super Mario World" to "Super Mario World (USA).sfc",
            "Super Mario World" to "Super Mario World 2 - Yoshi's Island (USA).sfc",
            "Mega Man X" to "Mega Man X (USA) (Rev 1).sfc",
            "Mega Man X" to "Mega Man X2 (USA).sfc",
            "The Legend of Zelda" to "Legend of Zelda, The (USA).nes"
        )
        for ((title, file) in pairs) {
            val state = BestGames.match(ranked(title), emptyList(), listOf(file)).single().state
            assertEquals("$title vs $file", GameTitleCleaner.sameTitle(title, file), state == OwnState.IN_SOURCE)
        }
    }

    @Test
    fun `being on the device beats being in a source, and an empty title matches nothing`() {
        val r = ranked("Tetris", "(USA)")
        val m = BestGames.match(r, listOf("Tetris (World).gb"), listOf("Tetris (World).zip", "(USA).zip"))
        assertEquals(OwnState.ON_DEVICE, m[0].state)
        assertEquals(listOf(0), m[0].sourceIndices)
        assertEquals(OwnState.MISSING, m[1].state)
    }

    @Test
    fun `download and wishlist lists follow the state`() {
        val r = ranked("A Game", "B Game", "C Game", "D")
        val m = BestGames.match(r, listOf("A Game.gb"), listOf("B Game.zip"))
        assertEquals(listOf("B Game"), BestGames.downloadable(m).map { it.ranked.game.title })
        // "D" is too short to be a wish.
        assertEquals(listOf("C Game"), BestGames.wishTitles(m))
        assertEquals(Triple(1, 1, 2), BestGames.tally(m))
    }

    @Test
    fun `icon is a full url`() {
        assertEquals("https://media.retroachievements.org/Images/001.png", BestGames.iconUrl(game(1, "A", 1, icon = "/Images/001.png")))
        assertNull(BestGames.iconUrl(game(1, "A", 1)))
    }

    // ---- 9.2: more consoles, more facts, local sorting and filtering ----

    @Test
    fun `more retro console ids map to their RA systems`() {
        assertEquals(30, RaConsoleMap.forConsole("commodore_c64")?.id)
        assertEquals(30, RaConsoleMap.forConsole("c64")?.id)
        assertEquals(58, RaConsoleMap.forConsole("zx_spectrum")?.id)
        assertEquals(59, RaConsoleMap.forConsole("game_and_watch")?.id)
        assertEquals(27, RaConsoleMap.forConsole("mame")?.id)
        assertEquals(54, RaConsoleMap.forConsole("cassette_vision")?.id)
        assertEquals(55, RaConsoleMap.forConsole("super_cassette_vision")?.id)
        assertEquals(76, RaConsoleMap.forConsole("pc_engine_cd")?.id)
        // Look-alikes still have no set of their own.
        assertNull(RaConsoleMap.forConsole("atari_jaguar_cd"))
        assertNull(RaConsoleMap.forConsole("nintendo_famicom_disk_system"))
    }

    @Test
    fun `the extended answer carries hardcore counts, release and genre`() {
        val detail = BestGames.parseDetails("""{
            "ID":228,"Title":"X","NumDistinctPlayers":1000,"NumDistinctPlayersHardcore":"420",
            "Released":"1990-11-21","ReleasedAtGranularity":"day","Genre":"Platformer"
        }""")!!
        assertEquals(1000, detail.players)
        assertEquals(420, detail.hardcore)
        assertEquals("1990-11-21", detail.released)
        assertEquals("Platformer", detail.genre)
        assertEquals(1000, BestGames.parsePlayers("""{"ID":1,"NumDistinctPlayers":1000}"""))
        assertNull(BestGames.parseDetails("""{"message":"Unauthenticated."}"""))
    }

    @Test
    fun `leaderboards and extra facts survive the cache`() {
        val games = listOf(BestGame(1, "Game", 20, 500, "/Images/1.png", 12, "1990-11-21", "Platformer", 420))
        val back = BestGames.decodeGames(BestGames.encodeGames(games)).single()
        assertEquals(12, back.leaderboards)
        assertEquals("1990-11-21", back.released)
        assertEquals("Platformer", back.genre)
        assertEquals(420, back.hardcore)
        // Old cache entries without the new keys still load.
        val old = BestGames.decodeGames("""[{"i":2,"t":"Old","a":3,"p":4}]""").single()
        assertEquals(0, old.leaderboards)
        assertNull(old.released)
    }

    @Test
    fun `hardcore ranking only claims the basis when every candidate has a count`() {
        val games = listOf(game(1, "A", 100), game(2, "B", 300), game(3, "C", 200))
        val partial = BestGames.rank(games, emptyMap(), mapOf(1 to 50, 2 to 10, 3 to 5), wanted = RankBasis.HARDCORE)
        assertEquals(RankBasis.HARDCORE, partial.basis)
        assertEquals(listOf(1, 2, 3), partial.games.map { it.game.id })
        val missingOne = BestGames.rank(games, emptyMap(), mapOf(1 to 50), wanted = RankBasis.HARDCORE)
        assertEquals(RankBasis.POINTS, missingOne.basis)
        assertEquals(1, missingOne.checked)
    }

    @Test
    fun `local sort and filter do not change the earned ranks`() {
        val ranked = listOf(game(3, "Zelda", 200), game(1, "Mario", 900), game(2, "Metroid", 400))
        val matches = BestGames.match(
            ranked.mapIndexed { i, g -> RankedGame(i + 1, g, null) },
            listOf("Mario.gb"), listOf("Zelda.zip")
        )
        assertEquals(listOf("Zelda", "Mario", "Metroid"), BestGames.sort(matches, BestSort.RANK).map { it.ranked.game.title })
        assertEquals(listOf("Mario", "Metroid", "Zelda"), BestGames.sort(matches, BestSort.TITLE).map { it.ranked.game.title })
        assertEquals(listOf(OwnState.ON_DEVICE), BestGames.filter(matches, OwnFilter.DEVICE).map { it.state })
        assertEquals(listOf(OwnState.IN_SOURCE), BestGames.filter(matches, OwnFilter.SOURCE).map { it.state })
        assertEquals(listOf(OwnState.MISSING), BestGames.filter(matches, OwnFilter.MISSING).map { it.state })
        // Sorting moves the row, not the rank RA's numbers earned.
        assertEquals(2, BestGames.sort(matches, BestSort.TITLE).first().ranked.rank)
    }

    @Test
    fun `fresh stats keep hardcore and details, an old cache stays empty`() {
        val now = 10L * BestGames.WEEK_MS
        val counts = mapOf(
            1 to PlayerCount(500, now - 1_000, 200, "1990-01-01", "Action"),
            2 to PlayerCount(40, now - BestGames.WEEK_MS - 1, 10, "1985-01-01", "Puzzle")
        )
        val back = BestGames.decodePlayers(BestGames.encodePlayers(counts))
        val stats = BestGames.freshStats(back, now)
        assertEquals(mapOf(1 to 500), stats.players)
        assertEquals(mapOf(1 to 200), stats.hardcore)
        assertEquals("Action", stats.details[1]?.genre)
        assertTrue(stats.details[2] == null)
    }
}
