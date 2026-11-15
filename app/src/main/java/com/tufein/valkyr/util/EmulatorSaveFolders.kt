package com.tufein.valkyr.util

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Saves folders of standalone emulators (DraStic, mGBA, Snes9x EX+…) synced with RomM next to
 * RetroArch's. Each folder is one console's and is shown to the sync as a folder named after the
 * emulator ([label]) inside the saves folder: its files go up with that emulator name, server
 * saves of that emulator come down into it, and [platforms] picks the right game when the same
 * file name exists on several platforms. The presets say "(standalone)" where RetroArch has a core
 * folder of the same name, so the two never mix. Only in-game saves (battery saves, memory cards), not
 * save states. Pure JVM for the tests.
 */
data class EmulatorSaveFolder(
    /** Emulator name, also the folder name the sync sees and RomM's *emulator* ("DraStic"). */
    val label: String,
    /** RomM platform names this folder's games are on, normalized ("nds"). Empty = no hint. */
    val platforms: Set<String>,
    /** Picked folder (Storage Access Framework tree URI). */
    val uri: String,
    /** How the emulator keeps its saves there (one file per game, memory cards, save folders). */
    val layout: SaveFolderLayout = SaveFolderLayout.FILES,
    /**
     * Shared memory cards (DuckStation's `shared_card_1.mcd`, every AetherSX2 / NetherSX2 and
     * Dolphin GameCube card) are synced only once the user picks a RomM game to keep them with
     * (RomM stores every save with a game): its id and name, null = not synced.
     */
    val sharedCardsRomId: Int? = null,
    val sharedCardsRomName: String = "",
    /**
     * Dolphin folders only: Dolphin's GCI folders (`GC/<region>/Card A|B`) are synced, one bundle per
     * folder, once the user picks a RomM game to keep them with; null = not synced ([DolphinSaveBundles]).
     */
    val gciRomId: Int? = null,
    val gciRomName: String = "",
    /** Dolphin folders only: the Wii saves (`Wii/title/00010000`) as one bundle, kept with this game; null = not synced. */
    val wiiRomId: Int? = null,
    val wiiRomName: String = "",
    /** Dolphin folders only: also the saves of channels discs install (`Wii/title/00010004`), as a second bundle. */
    val wiiChannels: Boolean = false,
    /** Dolphin folders only: also the saves of WiiWare and Virtual Console titles (`Wii/title/00010001`), as a third bundle. */
    val wiiWare: Boolean = false
) {
    /** The Dolphin bundles this folder syncs, from the user's choices. */
    val dolphinBundles: List<DolphinBundle>
        get() = if (layout != SaveFolderLayout.GC_MEMCARDS) emptyList()
        else DolphinSaveBundles.all(gciRomId != null, wiiRomId != null, wiiChannels, wiiWare)

    /** The RomM game [bundle] is kept with, or null when it is not synced. */
    fun romIdOf(bundle: DolphinBundle): Int? = when {
        bundle !in dolphinBundles -> null
        bundle.kind == DolphinBundleKind.GCI -> gciRomId
        else -> wiiRomId
    }
}

object EmulatorSaveFolders {
    /**
     * Emulators whose saves the sync can tie to a game: files named after the ROM, memory cards
     * (DuckStation's per game and shared, AetherSX2's / NetherSX2's and Dolphin's GameCube cards,
     * which are all shared) and PPSSPP's save folders ([layout]). [emulatorId] is the app in
     * [EmulatorCatalog]. A label is one plain folder name (it is RomM's *emulator* too), so it has no `/`.
     */
    data class Preset(
        val label: String,
        val platforms: Set<String>,
        val folderHint: String,
        val layout: SaveFolderLayout = SaveFolderLayout.FILES,
        val emulatorId: String? = null,
        /** Forks that keep their saves the same way (Dolphin MMJR for Dolphin). */
        val otherEmulatorIds: Set<String> = emptySet(),
        /**
         * Where the emulator keeps this folder on shared storage, as folder names from the storage
         * root ("DraStic/backup"): what [propose] looks for. Empty when the place is not fixed or
         * lies in the emulator's private `Android/data`, which other apps cannot read.
         */
        val knownPaths: List<String> = emptyList(),
        /** A folder that must sit inside a known path before it is proposed (Dolphin's `GC`). */
        val mustHold: String? = null
    ) {
        fun isFor(id: String): Boolean = id == emulatorId || id in otherEmulatorIds
    }

    val presets: List<Preset> = listOf(
        Preset("DraStic (standalone)", setOf("nds"), "DraStic/backup", emulatorId = "drastic", knownPaths = listOf("DraStic/backup")),
        Preset("melonDS (standalone)", setOf("nds"), "melonDS saves folder", emulatorId = "melonds"),
        Preset("mGBA (standalone)", setOf("gba", "gbc", "gb"), "mGBA saves folder"),
        Preset("Pizza Boy GBA", setOf("gba"), "PizzaBoyGBA/saves", emulatorId = "pizza_boy_gba", knownPaths = listOf("PizzaBoyGBA/saves")),
        Preset("Pizza Boy GBC", setOf("gbc", "gb"), "PizzaBoyGBC/saves", emulatorId = "pizza_boy_gbc", knownPaths = listOf("PizzaBoyGBC/saves")),
        Preset("GBA.emu", setOf("gba"), "GBA.emu saves", emulatorId = "gba_emu"),
        Preset("GBC.emu", setOf("gbc", "gb"), "GBC.emu saves", emulatorId = "gbc_emu"),
        Preset("Snes9x EX+", setOf("snes", "sfc"), "Snes9x EX+ saves", emulatorId = "snes9x_explus"),
        Preset("NES.emu", setOf("nes", "famicom"), "NES.emu saves", emulatorId = "nes_emu"),
        Preset("MD.emu", setOf("genesis", "megadrive", "md", "genesisslashmegadrive"), "MD.emu saves", emulatorId = "md_emu"),
        Preset("DuckStation (standalone)", setOf("psx", "ps", "ps1", "playstation"), "DuckStation/memcards", SaveFolderLayout.MEMCARDS, "duckstation", knownPaths = listOf("DuckStation/memcards")),
        Preset("PPSSPP (standalone)", setOf("psp", "playstationportable"), "PSP/SAVEDATA", SaveFolderLayout.SAVEDATA, "ppsspp", knownPaths = listOf("PSP/SAVEDATA")),
        // Both apps keep `Mcd001.ps2` / `Mcd002.ps2` in the `memcards` folder of their data folder.
        Preset("AetherSX2 - NetherSX2 (standalone)", setOf("ps2", "playstation2"), "AetherSX2 or NetherSX2 data folder/memcards", SaveFolderLayout.PS2_MEMCARDS, "nethersx2"),
        // GameCube cards (`GC/MemoryCardA.USA.raw`); GCI folders and Wii saves as opt-in bundles (see DolphinSaveBundles).
        Preset("Dolphin (standalone)", setOf("ngc", "gc", "gamecube"), "Dolphin user folder/GC", SaveFolderLayout.GC_MEMCARDS, "dolphin", setOf("dolphin_mmjr"), knownPaths = listOf("dolphin-emu"), mustHold = "GC")
    )

    /** A folder [propose] recognised: the emulator it belongs to and its path below the chosen root ("" = the root itself). */
    data class Proposal(val preset: Preset, val path: String)

    /**
     * The emulator folders below a chosen root (the storage root, or a folder on the way to one), found
     * by the fixed places emulators keep them in ([Preset.knownPaths]). [rootName] is the chosen
     * folder's own name, so a root that is already `DraStic` still finds `backup`; [dirsAt] lists the
     * folders at a path below the root (null when it cannot be read). Only folders that exist are
     * proposed, each once, and never a path already in [taken] (lower case). Nothing is guessed from
     * file names: a proposal is a place the emulator is known to use.
     */
    fun propose(rootName: String, taken: Set<String> = emptySet(), dirsAt: (String) -> Set<String>?): List<Proposal> {
        fun resolve(parts: List<String>): String? {
            var path = ""
            for (part in parts) {
                val real = dirsAt(path)?.firstOrNull { it.equals(part, ignoreCase = true) } ?: return null
                path = if (path.isEmpty()) real else "$path/$real"
            }
            return path
        }
        val out = mutableListOf<Proposal>()
        for (preset in presets) {
            for (known in preset.knownPaths) {
                val parts = known.split('/')
                // From the root, or from inside the known path when the root is one of its folders.
                val found = (0 until parts.size).firstNotNullOfOrNull { skip ->
                    if (skip > 0 && !rootName.equals(parts[skip - 1], ignoreCase = true)) null else resolve(parts.drop(skip))
                } ?: if (parts.size == 1 && rootName.equals(parts[0], ignoreCase = true)) "" else null
                if (found == null) continue
                if (preset.mustHold != null && dirsAt(found)?.any { it.equals(preset.mustHold, ignoreCase = true) } != true) continue
                if (found.lowercase() in taken || out.any { it.path.equals(found, ignoreCase = true) }) continue
                out += Proposal(preset, found)
                break
            }
        }
        return out
    }

    /** The preset a folder was added with: its label is the preset's, or the preset's with a number ("DraStic 2"). */
    fun presetOf(folder: EmulatorSaveFolder): Preset? = presets.firstOrNull { p ->
        folder.label.equals(p.label, ignoreCase = true) ||
            (folder.label.startsWith(p.label + " ", ignoreCase = true) && folder.label.substring(p.label.length + 1).all { it.isDigit() })
    }

    /** The picked folders of the app [emulatorId] (in [EmulatorCatalog]) that hold games of [consoleId]. */
    fun foldersFor(emulatorId: String, consoleId: String, folders: List<EmulatorSaveFolder>): List<EmulatorSaveFolder> =
        folders.filter { folder -> presetOf(folder)?.isFor(emulatorId) == true && (folder.platforms.isEmpty() || platformFits(consoleId, folder.platforms)) }

    /**
     * Whether a game of [consoleId] (a library console id such as `sony_playstation`) is on one of
     * the folder platforms [hints] (RomM's short names such as `psx`).
     */
    fun platformFits(consoleId: String, hints: Set<String>): Boolean {
        if (ConsoleFolderAliases.normalize(consoleId) in hints) return true
        val system = EmulatorCatalog.systemOf(consoleId) ?: return false
        return hints.any { EmulatorCatalog.systemOf(it) == system }
    }

    /** A folder name RomM accepts as *emulator* (see [SaveSyncPlanner.emulatorFor]); blank when none is left. */
    fun cleanLabel(name: String): String =
        name.filterNot { it in "/\\:*?\"<>|" || it.isISOControl() }.trim().take(50)

    /** [label] made unique among [taken] (case-insensitive): "DraStic", "DraStic 2"… */
    fun uniqueLabel(label: String, taken: Collection<String>): String {
        val used = taken.map { it.lowercase() }.toSet()
        if (label.lowercase() !in used) return label
        var n = 2
        while ("$label $n".lowercase() in used) n++
        return "$label $n"
    }

    /** The extra folder a sync path ("DraStic (standalone)/Game.dsv") points into, and the path inside it. */
    fun locate(path: String, folders: List<EmulatorSaveFolder>): Pair<EmulatorSaveFolder, String>? {
        if (!path.contains('/')) return null
        val top = path.substringBefore('/')
        val folder = folders.firstOrNull { it.label.equals(top, ignoreCase = true) } ?: return null
        return folder to path.substringAfter('/')
    }

    fun toJson(folders: List<EmulatorSaveFolder>): String = JsonArray().apply {
        folders.forEach { f ->
            add(JsonObject().apply {
                addProperty("label", f.label)
                add("platforms", JsonArray().apply { f.platforms.sorted().forEach(::add) })
                addProperty("uri", f.uri)
                if (f.layout != SaveFolderLayout.FILES) addProperty("layout", f.layout.name)
                f.sharedCardsRomId?.let { addProperty("sharedCardsRomId", it); addProperty("sharedCardsRomName", f.sharedCardsRomName) }
                if (f.layout == SaveFolderLayout.GC_MEMCARDS) {
                    f.gciRomId?.let { addProperty("gciRomId", it); addProperty("gciRomName", f.gciRomName) }
                    f.wiiRomId?.let { addProperty("wiiRomId", it); addProperty("wiiRomName", f.wiiRomName) }
                    if (f.wiiChannels) addProperty("wiiChannels", true)
                    if (f.wiiWare) addProperty("wiiWare", true)
                }
            })
        }
    }.toString()

    fun fromJson(json: String?): List<EmulatorSaveFolder> = runCatching {
        JsonParser.parseString(json?.takeIf { it.isNotBlank() } ?: "[]").asJsonArray.mapNotNull { el ->
            val o = el.asJsonObject
            val label = cleanLabel(o.get("label")?.asString.orEmpty()).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val uri = o.get("uri")?.asString?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            // An unknown layout (written by a newer version) is not read as plain files: the folder is skipped.
            val layout = o.get("layout")?.takeUnless { it.isJsonNull }?.asString?.let { name -> SaveFolderLayout.entries.firstOrNull { it.name == name } ?: return@mapNotNull null }
                ?: SaveFolderLayout.FILES
            fun romId(name: String, allowed: Boolean) = o.get(name)?.takeUnless { it.isJsonNull }?.let { runCatching { it.asInt }.getOrNull() }?.takeIf { it > 0 && allowed }
            fun romName(name: String, id: Int?) = if (id != null) o.get(name)?.takeUnless { it.isJsonNull }?.let { runCatching { it.asString }.getOrNull() }.orEmpty().take(200) else ""
            val shared = romId("sharedCardsRomId", layout.holdsSharedCards)
            val dolphin = layout == SaveFolderLayout.GC_MEMCARDS
            val gci = romId("gciRomId", dolphin)
            val wii = romId("wiiRomId", dolphin)
            fun flag(name: String) = wii != null && o.get(name)?.takeUnless { it.isJsonNull }?.let { runCatching { it.asBoolean }.getOrNull() } == true
            EmulatorSaveFolder(
                label, o.getAsJsonArray("platforms")?.map { ConsoleFolderAliases.normalize(it.asString) }?.toSet().orEmpty(), uri, layout,
                shared, romName("sharedCardsRomName", shared),
                gci, romName("gciRomName", gci), wii, romName("wiiRomName", wii),
                flag("wiiChannels"), flag("wiiWare")
            )
        }.distinctBy { it.label.lowercase() }
    }.getOrDefault(emptyList())
}
