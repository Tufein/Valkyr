package com.tufein.valkyr.util

import org.junit.Assert.assertEquals
import org.junit.Test

class GameInfoTextTest {

    private val labels = GameInfoText.Labels("Console", "Released", "Developer", "Genre", "File", "Tags")

    @Test
    fun fullFactsGiveOneLineEach() {
        val text = GameInfoText.build(
            GameInfoText.Facts(
                title = "Pokemon Emerald", console = "Game Boy Advance", released = "2004", developer = "Game Freak",
                genres = listOf("RPG", "Adventure"), fileName = "Pokemon Emerald (USA).zip", sizeText = "14 MB", tags = listOf("USA", "En")
            ),
            labels,
            link = "valkyr://library?q=x"
        )
        assertEquals(
            "Pokemon Emerald\nConsole: Game Boy Advance\nReleased: 2004\nDeveloper: Game Freak\nGenre: RPG, Adventure\n" +
                "File: Pokemon Emerald (USA).zip (14 MB)\nTags: USA, En\nvalkyr://library?q=x",
            text
        )
    }

    @Test
    fun unknownValuesAreLeftOut() {
        val text = GameInfoText.build(GameInfoText.Facts(title = " Tetris ", console = "Game Boy", genres = listOf(" ", "")), labels)
        assertEquals("Tetris\nConsole: Game Boy", text)
    }

    @Test
    fun sizeAloneOrFileAloneStillShows() {
        assertEquals("T\nFile: 2 MB", GameInfoText.build(GameInfoText.Facts("T", "", sizeText = "2 MB"), labels))
        assertEquals("T\nFile: a.zip", GameInfoText.build(GameInfoText.Facts("T", "", fileName = "a.zip"), labels))
    }

    @Test
    fun tagsAreDeduplicatedAndCapped() {
        val tags = (1..20).map { "t$it" } + "t1"
        val line = GameInfoText.build(GameInfoText.Facts("T", "", tags = tags), labels).lines().last()
        assertEquals("Tags: " + (1..GameInfoText.MAX_TAGS).joinToString(", ") { "t$it" }, line)
    }
}
