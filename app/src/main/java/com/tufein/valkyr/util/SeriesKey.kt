package com.tufein.valkyr.util

import java.text.Normalizer

/**
 * 8.1 "Series": which franchise a game belongs to, from its title alone. Pure JVM.
 *
 * Known franchises live in [FRANCHISES] as data: a display name plus the word runs that identify it
 * ("rockman" is Mega Man, "pocket monsters" is Pokémon), matched anywhere in the cleaned title so
 * that *Super Mario Kart* and *Yoshi's Island* meet *Super Mario World* under Mario. Any other game
 * falls back to its first two meaningful words ([SimilarGames.seriesWords]), and a fallback series
 * only counts once [SeriesGroups.MIN_HEURISTIC] games share it. Nothing is stored: the key is
 * recomputed from the name, so a rename of the list here changes nothing on disk.
 */
object SeriesKey {

    /** A franchise: [id] is the stable key, [name] what the user sees, [phrases] the word runs that identify it. */
    data class Franchise(val id: String, val name: String, val phrases: List<String>)

    /** The series of a game: [key] groups games, [name] is shown, [known] is false for the first-two-words fallback. */
    data class Series(val key: String, val name: String, val known: Boolean)

    private fun f(id: String, name: String, vararg phrases: String) = Franchise(id, name, phrases.toList())

    /** Order matters: the first franchise whose phrase is found in a title wins, so specific names come first. */
    val FRANCHISES: List<Franchise> = listOf(
        f("pokemon", "Pokémon", "pokemon", "pocket monsters"),
        f("megaman", "Mega Man", "mega man", "megaman", "rockman"),
        f("zelda", "The Legend of Zelda", "zelda"),
        f("metroid", "Metroid", "metroid"),
        f("kirby", "Kirby", "kirby"),
        f("donkeykong", "Donkey Kong", "donkey kong"),
        f("mario", "Mario", "mario", "luigi", "yoshi", "wario"),
        f("sonic", "Sonic the Hedgehog", "sonic"),
        f("streetfighter", "Street Fighter", "street fighter"),
        f("mortalkombat", "Mortal Kombat", "mortal kombat"),
        f("finalfantasy", "Final Fantasy", "final fantasy"),
        f("dragonquest", "Dragon Quest", "dragon quest", "dragon warrior"),
        f("castlevania", "Castlevania", "castlevania", "akumajou", "akumajo"),
        f("residentevil", "Resident Evil", "resident evil", "biohazard"),
        f("metalgear", "Metal Gear", "metal gear"),
        f("gta", "Grand Theft Auto", "grand theft auto"),
        f("tombraider", "Tomb Raider", "tomb raider"),
        f("crash", "Crash Bandicoot", "crash bandicoot", "crash team racing"),
        f("spyro", "Spyro", "spyro"),
        f("tekken", "Tekken", "tekken"),
        f("granturismo", "Gran Turismo", "gran turismo"),
        f("silenthill", "Silent Hill", "silent hill"),
        f("fireemblem", "Fire Emblem", "fire emblem"),
        f("starfox", "Star Fox", "star fox", "starfox"),
        f("fzero", "F-Zero", "f zero"),
        f("earthbound", "EarthBound", "earthbound", "mother"),
        f("contra", "Contra", "contra", "probotector"),
        f("gradius", "Gradius", "gradius", "nemesis"),
        f("rtype", "R-Type", "r type"),
        f("bomberman", "Bomberman", "bomberman", "bomber man"),
        f("tetris", "Tetris", "tetris"),
        f("worms", "Worms", "worms"),
        f("lemmings", "Lemmings", "lemmings"),
        f("pacman", "Pac-Man", "pac man", "pacman", "ms pac"),
        f("doubledragon", "Double Dragon", "double dragon"),
        f("streetsrage", "Streets of Rage", "streets of rage", "bare knuckle"),
        f("harvest", "Harvest Moon", "harvest moon"),
        f("shin", "Shin Megami Tensei", "megami tensei", "persona"),
        f("tales", "Tales of", "tales of"),
        f("suikoden", "Suikoden", "suikoden"),
        f("kingdomhearts", "Kingdom Hearts", "kingdom hearts"),
        f("ninjagaiden", "Ninja Gaiden", "ninja gaiden"),
        f("gundam", "Gundam", "gundam"),
        f("dragonball", "Dragon Ball", "dragon ball", "dragonball"),
        f("naruto", "Naruto", "naruto"),
        f("wipeout", "Wipeout", "wipeout"),
        f("rayman", "Rayman", "rayman")
    )

    private val accents = Regex("\\p{InCombiningDiacriticalMarks}+")
    private val phraseWords: List<Pair<Franchise, List<List<String>>>> =
        FRANCHISES.map { fr -> fr to fr.phrases.map { it.split(' ') } }

    /** Lower-case words of the cleaned title without accents and without apostrophes ("Pokémon", "Yoshi's"). */
    fun words(name: String): List<String> =
        Normalizer.normalize(GameTitleCleaner.clean(name), Normalizer.Form.NFD).replace(accents, "")
            .lowercase().replace(Regex("['’]s\\b"), "").replace("'", "")
            .split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }

    private fun containsRun(words: List<String>, run: List<String>): Boolean {
        if (run.size > words.size) return false
        for (i in 0..words.size - run.size) if (run.indices.all { words[i + it] == run[it] }) return true
        return false
    }

    /** The series of [name], or null when it has no meaningful word at all. */
    fun of(name: String): Series? {
        val words = words(name)
        for ((franchise, runs) in phraseWords) {
            if (runs.any { containsRun(words, it) }) return Series(franchise.id, franchise.name, known = true)
        }
        val lead = SimilarGames.seriesWords(
            Normalizer.normalize(name, Normalizer.Form.NFD).replace(accents, "")
        ).take(2)
        if (lead.isEmpty()) return null
        return Series("w:" + lead.joinToString(" "), lead.joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }, known = false)
    }

    /** The key under which games of one series meet on one console; null when [of] finds nothing. */
    fun key(consoleId: String, name: String): String? = of(name)?.let { consoleId + "|" + it.key }

    /** A search text that lists the library's games of this series: the franchise's own name or the two lead words. */
    fun query(name: String): String {
        val s = of(name) ?: return ""
        return if (s.known) FRANCHISES.first { it.id == s.key }.phrases.first() else s.key.removePrefix("w:")
    }
}
