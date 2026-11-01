package com.tufein.valkyr.util

import java.util.Locale

/**
 * Folder names for other systems (3.5.0): when games are copied to a memory card or USB folder
 * (Tools → Copy or send games) the console folders can get the names another system scans for,
 * instead of the names they have in this library. Pure JVM for the tests.
 *
 * Only the folder names change. Which games run there, and whether that system also wants its
 * own game lists, BIOS files or cores, is up to that system.
 *
 * Every name below comes from the system's own published configuration or documentation (read on
 * 2026-10-15); nothing was tried on a device. A console a system does not document is left out
 * ([folderFor] is null): its games are listed as not supported and skipped, never guessed.
 *  - ES-DE: `resources/systems/android/es_systems.xml` (identical names in `linux/`), master of
 *    https://gitlab.com/es-de/emulationstation-de (last commit 2026-10-10). Where ES-DE has a
 *    regional pair (megadrive / genesis, megacd / segacd) the European name is used.
 *  - Batocera (42 and newer): `package/batocera/emulationstation/batocera-es-system/es_systems.yml`
 *    of https://github.com/batocera-linux/batocera.linux (master), and
 *    https://wiki.batocera.org/systems:segacd ("megacd starting with Batocera 42, segacd for
 *    previous versions"). Games go to `/userdata/roms/<folder>`: `roms` on the SHARE partition.
 *  - Knulli: the same file in https://github.com/knulli-cfw/distribution (branch knulli-main),
 *    including its `path:` overrides (Odyssey² is `o2em`); https://knulli.org/play/add-games/game-storage/
 *    ("KNULLI only scans for games in the subfolders of the roms folder", "case-sensitive").
 *  - ArkOS: https://github.com/christianhaitian/arkos/wiki/ArkOS-Emulators-and-Ports-information
 *    ("Rom Folder:" of each system; the folders sit at the top of the EASYROMS card).
 *  - ROCKNIX: the system pages of https://rocknix.org/systems/ (source
 *    https://github.com/ROCKNIX/rocknix.org, the Markdown pages in docs/systems, "Game Path" `roms/<folder>`);
 *    https://rocknix.org/play/add-games/ (a `roms` folder on the games card). Pages without a
 *    game path (C64, Atari ST, MSX, PS Vita) are left out.
 *  - OnionOS (Miyoo Mini): https://onionui.github.io/docs/emulators/folders (source
 *    `website/docs/04-emulators/Rom folders - Quick reference.md` of https://github.com/OnionUI/Onion)
 *    and the per-system pages for the ⚠ "expert" ones (JAGUAR, ATARIST, PCFX, PANASONIC, UZEBOX,
 *    FBNEO). Folders sit in `Roms/` and are case sensitive; sub-folders work one level deep only.
 *  - muOS: `share/info/manifest/core.json` ("friendly" names) of https://github.com/MustardOS/internal
 *    (main): muOS lower-cases a folder name, drops spaces, `-`, `_` and `+`, and assigns a core
 *    when the result is one of these names (`script/system/assign.sh`, `automatic_assign_core` in
 *    https://github.com/MustardOS/frontend). Games go in `ROMS/` (`MAIN_ROM_DIR`).
 */
object TargetFolderLayouts {

    /**
     * How the console folders are named at the destination.
     *
     * @property romsFolder the folder the system keeps its console folders in; the console folders
     *  go inside it unless the picked folder is that folder ([destinationFolder]). Null = the
     *  console folders go straight into the picked folder.
     * @property maxSubfolders how many folders deep below a console folder the system still finds
     *  games; null = no documented limit.
     */
    enum class Layout(val key: String, val romsFolder: String?, val maxSubfolders: Int? = null) {
        /** The folders the games have in this library (what the copy always did). */
        LIBRARY("library", null),
        ES_DE("esde", null),
        BATOCERA("batocera", "roms"),
        KNULLI("knulli", "roms"),
        ARKOS("arkos", null),
        ROCKNIX("rocknix", "roms"),
        ONION("onion", "Roms", maxSubfolders = 1),
        MUOS("muos", "ROMS");

        companion object {
            /** The stored layout; an unknown or missing key is [LIBRARY]. */
            fun of(key: String?): Layout = entries.firstOrNull { it.key == key } ?: LIBRARY
        }
    }

    /**
     * The systems the table knows, matched from a console id (`nintendo_gameboy_advance`,
     * `sony_psx`, `super_nintendo_entertainment_system`, a folder-like `gba`) by [systemOf]: the
     * most specific key wins, scored like [EmulatorCatalog.systemOf] and the RetroAchievements
     * console match.
     */
    enum class System(val keys: List<String>) {
        NES(listOf("nintendo_entertainment_system", "nes", "famicom", "fc")),
        FDS(listOf("famicom_disk_system", "fds")),
        SNES(listOf("super_nintendo_entertainment_system", "super_nintendo", "snes", "super_famicom", "sfc")),
        N64(listOf("nintendo_64", "n64")),
        GB(listOf("gameboy", "game_boy", "gb")),
        GBC(listOf("gameboy_color", "game_boy_color", "gbc")),
        GBA(listOf("gameboy_advance", "game_boy_advance", "gba", "gameboy_micro", "game_boy_micro")),
        NDS(listOf("nintendo_ds", "nintendo_dsi", "nds", "dsi")),
        N3DS(listOf("nintendo_3ds", "3ds", "n3ds")),
        GAMECUBE(listOf("gamecube", "game_cube", "nintendo_gamecube", "gc", "ngc")),
        WII(listOf("wii", "nintendo_wii")),
        WII_U(listOf("wii_u", "wiiu", "nintendo_wii_u")),
        SWITCH(listOf("switch", "nintendo_switch")),
        VIRTUAL_BOY(listOf("virtual_boy", "virtualboy", "vb")),
        POKEMON_MINI(listOf("pokemon_mini", "pokemini")),
        GAME_AND_WATCH(listOf("game_and_watch", "gameandwatch", "game_watch", "gw")),
        PS1(listOf("playstation", "ps1", "psx", "ps_one", "psone")),
        PS2(listOf("playstation_2", "ps2")),
        PS3(listOf("playstation_3", "ps3")),
        PSP(listOf("playstation_portable", "psp")),
        PS_VITA(listOf("playstation_vita", "ps_vita", "psvita", "vita")),
        MASTER_SYSTEM(listOf("master_system", "mastersystem", "sms")),
        GENESIS(listOf("genesis", "mega_drive", "megadrive", "md")),
        GAME_GEAR(listOf("game_gear", "gamegear", "gg")),
        SEGA_CD(listOf("sega_cd", "segacd", "mega_cd", "megacd")),
        SEGA_32X(listOf("sega_32x", "32x", "mega_drive_32x", "genesis_32x")),
        SATURN(listOf("saturn", "sega_saturn")),
        DREAMCAST(listOf("dreamcast", "dc")),
        SG_1000(listOf("sg_1000", "sg1000")),
        PC_ENGINE(listOf("pc_engine", "pcengine", "turbografx_16", "turbografx16", "turbografx", "pce", "tg16")),
        PC_ENGINE_CD(listOf("pc_engine_cd", "pcenginecd", "turbografx_cd", "turbografx_16_cd", "turbografxcd", "pce_cd", "pcecd", "tg_cd", "tg16cd")),
        SUPERGRAFX(listOf("supergrafx", "pc_engine_supergrafx", "sgx")),
        PC_FX(listOf("pc_fx", "pcfx")),
        NEO_GEO(listOf("neo_geo", "neogeo", "neo_geo_aes", "neo_geo_mvs")),
        NEO_GEO_CD(listOf("neo_geo_cd", "neogeocd")),
        NEO_GEO_POCKET(listOf("neo_geo_pocket", "neogeo_pocket", "ngp")),
        NEO_GEO_POCKET_COLOR(listOf("neo_geo_pocket_color", "neogeo_pocket_color", "ngpc")),
        WONDERSWAN(listOf("wonderswan", "wonder_swan", "ws")),
        WONDERSWAN_COLOR(listOf("wonderswan_color", "wonder_swan_color", "wsc")),
        ATARI_2600(listOf("atari_2600", "2600", "atari2600")),
        ATARI_5200(listOf("atari_5200", "5200", "atari5200")),
        ATARI_7800(listOf("atari_7800", "7800", "atari7800")),
        ATARI_LYNX(listOf("atari_lynx", "lynx")),
        ATARI_JAGUAR(listOf("atari_jaguar", "jaguar")),
        ATARI_JAGUAR_CD(listOf("atari_jaguar_cd", "jaguar_cd", "jaguarcd")),
        ATARI_ST(listOf("atari_st", "atarist")),
        COLECOVISION(listOf("colecovision", "coleco")),
        INTELLIVISION(listOf("intellivision", "intv")),
        VECTREX(listOf("vectrex")),
        ODYSSEY_2(listOf("odyssey_2", "odyssey2", "videopac")),
        MSX(listOf("msx", "msx1")),
        MSX2(listOf("msx2", "msx_2")),
        C64(listOf("commodore_64", "c64")),
        AMIGA(listOf("amiga", "commodore_amiga")),
        DOS(listOf("dos", "ms_dos", "msdos")),
        SCUMMVM(listOf("scummvm")),
        PANASONIC_3DO(listOf("3do", "panasonic_3do")),
        CD_I(listOf("cd_i", "cdi")),
        XBOX(listOf("xbox", "microsoft_xbox")),
        XBOX_360(listOf("xbox_360", "xbox360", "microsoft_xbox_360")),
        ARCADE(listOf("arcade", "mame", "arcade_mame")),
        FBNEO(listOf("fbneo", "arcade_fbneo", "finalburn_neo", "final_burn_neo")),
        ARDUBOY(listOf("arduboy")),
        SUPERVISION(listOf("supervision", "watara_supervision")),
        CHIP_8(listOf("chip_8", "chip8")),
        TIC_80(listOf("tic_80", "tic80")),
        WASM_4(listOf("wasm_4", "wasm4")),
        UZEBOX(listOf("uzebox")),
        VIRCON32(listOf("vircon32", "vircon_32"))
    }

    /**
     * Consoles whose names contain the key of one in the table but are another machine (a newer
     * model, an add-on with its own games): they match nothing rather than the older one. Each
     * must score at least as high as the key it contains, so the long forms are listed too.
     */
    private val otherMachines = listOf(
        "playstation_4", "ps4", "playstation_5", "ps5", "nintendo_switch_2", "switch_2", "switch2",
        "xbox_one", "xbox_series", "microsoft_xbox_one", "microsoft_xbox_series", "microsoft_xbox_series_x",
        "nintendo_64dd", "n64dd", "64dd", "amiga_cd32", "amigacd32", "cd32", "commodore_amiga_cd32",
        "amiga_cdtv", "cdtv", "commodore_cdtv", "commodore_amiga_cdtv", "msx_turbo_r", "msxturbor", "turbo_r",
        // Variants with their own games that the systems' folders do not hold.
        "playstation_minis", "sony_playstation_minis", "playstation_mobile", "sony_playstation_mobile",
        "sega_cd_32x", "sega_cd_32x_games", "snes_msu1", "nintendo_snes_msu1", "nes_hacks", "nintendo_nes_hacks"
    )

    /** One row of the table: the folder of a system per layout; null = that system has none. */
    private data class Row(
        val esde: String?, val batocera: String?, val knulli: String?, val arkos: String?,
        val rocknix: String?, val onion: String?, val muos: String?
    ) {
        fun of(layout: Layout): String? = when (layout) {
            Layout.LIBRARY -> null
            Layout.ES_DE -> esde
            Layout.BATOCERA -> batocera
            Layout.KNULLI -> knulli
            Layout.ARKOS -> arkos
            Layout.ROCKNIX -> rocknix
            Layout.ONION -> onion
            Layout.MUOS -> muos
        }
    }

    //                                              ES-DE              Batocera        Knulli          ArkOS              ROCKNIX            OnionOS                muOS
    private val table: Map<System, Row> = mapOf(
        System.NES to Row("nes", "nes", "nes", "nes", "nes", "FC", "nes"),
        System.FDS to Row("fds", "fds", "fds", "fds", "fds", "FDS", "fds"),
        System.SNES to Row("snes", "snes", "snes", "snes", "snes", "SFC", "snes"),
        System.N64 to Row("n64", "n64", "n64", "n64", "n64", null, "n64"),
        System.GB to Row("gb", "gb", "gb", "gb", "gb", "GB", "gb"),
        System.GBC to Row("gbc", "gbc", "gbc", "gbc", "gbc", "GBC", "gbc"),
        System.GBA to Row("gba", "gba", "gba", "gba", "gba", "GBA", "gba"),
        System.NDS to Row("nds", "nds", "nds", "nds", "nds", "NDS", "nds"),
        System.N3DS to Row("n3ds", "3ds", "3ds", null, "3ds", null, "3ds"),
        System.GAMECUBE to Row("gc", "gamecube", "gamecube", "gc", "gamecube", null, null),
        System.WII to Row("wii", "wii", "wii", null, "wii", null, null),
        System.WII_U to Row("wiiu", "wiiu", "wiiu", null, "wiiu", null, null),
        System.SWITCH to Row("switch", null, "switch", null, null, null, null),
        System.VIRTUAL_BOY to Row("virtualboy", "virtualboy", "virtualboy", "virtualboy", "virtualboy", "VB", "virtualboy"),
        System.POKEMON_MINI to Row("pokemini", "pokemini", "pokemini", "pokemonmini", "pokemini", "POKE", "pokemini"),
        System.GAME_AND_WATCH to Row("gameandwatch", "gameandwatch", "gameandwatch", "gameandwatch", "gameandwatch", "GW", "gameandwatch"),
        System.PS1 to Row("psx", "psx", "psx", "psx", "psx", "PS", "psx"),
        System.PS2 to Row("ps2", "ps2", "ps2", null, "ps2", null, null),
        System.PS3 to Row("ps3", "ps3", "ps3", null, "ps3", null, null),
        System.PSP to Row("psp", "psp", "psp", "psp", "psp", null, "psp"),
        System.PS_VITA to Row("psvita", "psvita", "psvita", null, null, null, null),
        System.MASTER_SYSTEM to Row("mastersystem", "mastersystem", "mastersystem", "mastersystem", "mastersystem", "MS", "mastersystem"),
        System.GENESIS to Row("megadrive", "megadrive", "megadrive", "megadrive", "megadrive", "MD", "megadrive"),
        System.GAME_GEAR to Row("gamegear", "gamegear", "gamegear", "gamegear", "gamegear", "GG", "gamegear"),
        System.SEGA_CD to Row("megacd", "megacd", "segacd", "segacd", "megacd", "SEGACD", "megacd"),
        System.SEGA_32X to Row("sega32x", "sega32x", "sega32x", "sega32x", "sega32x", "THIRTYTWOX", "sega32x"),
        System.SATURN to Row("saturn", "saturn", "saturn", "saturn", "saturn", null, "saturn"),
        System.DREAMCAST to Row("dreamcast", "dreamcast", "dreamcast", "dreamcast", "dreamcast", null, "dreamcast"),
        System.SG_1000 to Row("sg-1000", "sg1000", "sg1000", "sg-1000", "sg-1000", "SEGASGONE", "sg1000"),
        System.PC_ENGINE to Row("pcengine", "pcengine", "pcengine", "pcengine", "pcengine", "PCE", "pcengine"),
        System.PC_ENGINE_CD to Row("pcenginecd", "pcenginecd", "pcenginecd", "pcenginecd", "pcenginecd", "PCECD", "pcenginecd"),
        System.SUPERGRAFX to Row("supergrafx", "supergrafx", "supergrafx", "supergrafx", "sgfx", "SGFX", "supergrafx"),
        System.PC_FX to Row("pcfx", "pcfx", "pcfx", "pcfx", "pcfx", "PCFX", "pcfx"),
        System.NEO_GEO to Row("neogeo", "neogeo", "neogeo", "neogeo", "neogeo", "NEOGEO", "neogeo"),
        System.NEO_GEO_CD to Row("neogeocd", "neogeocd", "neogeocd", "neogeocd", "neocd", "NEOCD", "neogeocd"),
        System.NEO_GEO_POCKET to Row("ngp", "ngp", "ngp", "ngp", "ngp", "NGP", "ngp"),
        System.NEO_GEO_POCKET_COLOR to Row("ngpc", "ngpc", "ngpc", "ngpc", "ngpc", "NGP", "ngpc"),
        System.WONDERSWAN to Row("wonderswan", "wswan", "wswan", "wonderswan", "wonderswan", "WS", "wonderswan"),
        System.WONDERSWAN_COLOR to Row("wonderswancolor", "wswanc", "wswanc", "wonderswancolor", "wonderswancolor", "WS", "wonderswancolor"),
        System.ATARI_2600 to Row("atari2600", "atari2600", "atari2600", "atari2600", "atari2600", "ATARI", "atari2600"),
        System.ATARI_5200 to Row("atari5200", "atari5200", "atari5200", "atari5200", "atari5200", "FIFTYTWOHUNDRED", "atari5200"),
        System.ATARI_7800 to Row("atari7800", "atari7800", "atari7800", "atari7800", "atari7800", "SEVENTYEIGHTHUNDRED", "atari7800"),
        System.ATARI_LYNX to Row("atarilynx", "lynx", "lynx", "atarilynx", "atarilynx", "LYNX", "atarilynx"),
        System.ATARI_JAGUAR to Row("atarijaguar", "jaguar", "jaguar", "atarijaguar", "atarijaguar", "JAGUAR", "atarijaguar"),
        System.ATARI_JAGUAR_CD to Row("atarijaguarcd", "jaguarcd", "jaguarcd", null, null, null, null),
        System.ATARI_ST to Row("atarist", "atarist", "atarist", "atarist", null, "ATARIST", "atarist"),
        System.COLECOVISION to Row("colecovision", "colecovision", "colecovision", "coleco", "coleco", "COLECO", "colecovision"),
        System.INTELLIVISION to Row("intellivision", "intellivision", "intellivision", "intellivision", "intellivision", "INTELLIVISION", "intellivision"),
        System.VECTREX to Row("vectrex", "vectrex", "vectrex", "vectrex", "vectrex", "VECTREX", "vectrex"),
        System.ODYSSEY_2 to Row("odyssey2", "odyssey2", "o2em", "odyssey2", "odyssey", "ODYSSEY", "odyssey2"),
        System.MSX to Row("msx", "msx1", "msx1", "msx", null, "MSX", "msx"),
        System.MSX2 to Row("msx2", "msx2", "msx2", "msx2", null, "MSX", "msx2"),
        System.C64 to Row("c64", "c64", "c64", "c64", null, "COMMODORE", "c64"),
        // Batocera and Knulli split the Amiga by model (amiga500 / amiga1200): which one a game needs is not known here.
        System.AMIGA to Row("amiga", null, null, "amiga", "amiga", "AMIGA", "amiga"),
        System.DOS to Row("dos", "dos", "dos", "dos", "pc", "DOS", "dos"),
        System.SCUMMVM to Row("scummvm", "scummvm", "scummvm", "scummvm", "scummvm", "SCUMMVM", "scummvm"),
        System.PANASONIC_3DO to Row("3do", "3do", "3do", "3do", "3do", "PANASONIC", "3do"),
        System.CD_I to Row("cdimono1", "cdi", "cdi", "cdimono1", "cdi", null, "cdi"),
        System.XBOX to Row("xbox", "xbox", "xbox", null, "xbox", null, null),
        System.XBOX_360 to Row("xbox360", "xbox360", "xbox360", null, null, null, null),
        System.ARCADE to Row("arcade", "mame", "mame", "arcade", "mame", "ARCADE", "arcade"),
        System.FBNEO to Row("fbneo", "fbneo", "fbneo", "arcade", "fbneo", "FBNEO", "fbneo"),
        System.ARDUBOY to Row("arduboy", "arduboy", "arduboy", "arduboy", "arduboy", null, "arduboy"),
        System.SUPERVISION to Row("supervision", "supervision", "supervision", "supervision", "supervision", "SUPERVISION", "supervision"),
        System.CHIP_8 to Row(null, null, null, null, "chip-8", null, "chip8"),
        System.TIC_80 to Row("tic80", "tic80", "tic80", "tic80", "tic-80", "TIC", "tic80"),
        System.WASM_4 to Row("wasm4", "wasm4", "wasm4", "wasm4", null, null, "wasm4"),
        System.UZEBOX to Row("uzebox", "uzebox", "uzebox", "uzebox", "uzebox", "UZEBOX", "uzebox"),
        System.VIRCON32 to Row("vircon32", "vircon32", "vircon32", "vircon32", "vircon32", null, "vircon32")
    )

    /**
     * Folders a system itself documents as holding more than one of these systems; any other
     * folder shared by two systems in one layout is a mistake in the table (see the tests).
     */
    val SHARED_FOLDERS: Map<Layout, Map<String, Set<System>>> = mapOf(
        // "Bandai WonderSwan & Color: WS", "SNK NeoGeo Pocket & Color: NGP", "MSX - MSX2: MSX".
        Layout.ONION to mapOf(
            "WS" to setOf(System.WONDERSWAN, System.WONDERSWAN_COLOR),
            "NGP" to setOf(System.NEO_GEO_POCKET, System.NEO_GEO_POCKET_COLOR),
            "MSX" to setOf(System.MSX, System.MSX2)
        ),
        // ArkOS' arcade folder runs FinalBurn Neo by default, MAME on request.
        Layout.ARKOS to mapOf("arcade" to setOf(System.ARCADE, System.FBNEO))
    )

    // ---- Matching ----------------------------------------------------------------------------

    private val notLettersOrDigits = Regex("[^a-z0-9]+")

    private fun normalize(s: String) = s.lowercase(Locale.ROOT).replace(notLettersOrDigits, "_").trim('_')

    private val normalizedKeys: Map<System, List<String>> by lazy { System.entries.associateWith { system -> system.keys.map(::normalize) } }
    private val normalizedOthers: List<String> by lazy { otherMachines.map(::normalize) }
    /** Console ids are few and asked for again and again (every game, every redraw of the setup list). */
    private val systemCache = java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<System>>()

    /** The system of a library console id or folder name, or null when the table does not know it. */
    fun systemOf(consoleId: String): System? {
        if (systemCache.size > 2000) systemCache.clear()
        return systemCache.getOrPut(consoleId) { java.util.Optional.ofNullable(resolve(consoleId)) }.orElse(null)
    }

    private fun resolve(consoleId: String): System? {
        val id = normalize(consoleId)
        if (id.isEmpty()) return null
        val tokens = id.split('_')
        val compact = id.replace("_", "")
        fun score(k: String): Int {
            val hit = id == k || id.endsWith("_$k") || k in tokens || (k.length >= 5 && compact.contains(k.replace("_", "")))
            return if (hit) k.length else 0
        }
        var best: System? = null
        var bestScore = 0
        for ((system, keys) in normalizedKeys) {
            val value = keys.maxOf(::score)
            if (value > bestScore) { best = system; bestScore = value }
        }
        if (best == null || bestScore == 0) return null
        return best.takeIf { normalizedOthers.maxOf(::score) < bestScore }
    }

    /**
     * The folder [layout] gives [system] (one path segment, without the [Layout.romsFolder]); null
     * when that system documents none, and always for [Layout.LIBRARY] (whose folders are the
     * library's own, see [destinationFolder]).
     */
    fun folderFor(layout: Layout, system: System): String? = table[system]?.of(layout)?.takeIf(::isSafeSegment)

    /** [folderFor] of the console [consoleId]; null when the layout has no folder for it or the console is unknown. */
    fun folderFor(layout: Layout, consoleId: String): String? = systemOf(consoleId)?.let { folderFor(layout, it) }

    /**
     * Where a game of [consoleId] goes below the picked folder: for [Layout.LIBRARY] its folder in
     * this library ([libraryFolder]); otherwise the layout's folder, inside [Layout.romsFolder]
     * unless the picked folder ([pickedFolderName], its last path segment; null or empty = the
     * card itself) already is that folder. Null when the layout has no folder for the console.
     */
    fun destinationFolder(layout: Layout, consoleId: String, libraryFolder: String, pickedFolderName: String?): String? {
        if (layout == Layout.LIBRARY) return libraryFolder
        val folder = folderFor(layout, consoleId) ?: return null
        return PackageCopyPlan.join(prefixFor(layout, pickedFolderName), folder)
    }

    /** The folder the console folders go in below the picked one ("" = the picked folder itself). */
    fun prefixFor(layout: Layout, pickedFolderName: String?): String {
        val roms = layout.romsFolder ?: return ""
        return if (pickedFolderName != null && pickedFolderName.equals(roms, ignoreCase = true)) "" else roms
    }

    /**
     * Where the system keeps BIOS files, relative to the card (not inside the roms folder), as its
     * own documentation says (read 2026-11-01): Batocera "the `bios/` folder in the SHARE partition"
     * (https://wiki.batocera.org/add_games_bios), Knulli `/userdata/bios`
     * (https://knulli.org/play/add-games/game-storage/), muOS `MUOS/bios`
     * (https://muos.dev/docs/infrastructure/storage). ROCKNIX's page only says games and BIOS files
     * can go in the per-system folders and ES-DE / ArkOS / OnionOS were not checked: null, never guessed.
     */
    fun biosFolder(layout: Layout): String? = when (layout) {
        Layout.BATOCERA, Layout.KNULLI -> "bios"
        Layout.MUOS -> "MUOS/bios"
        else -> null
    }

    /**
     * Whether the system's documentation asks for "Update Gamelists" (START, Game Settings) after
     * games are added: Batocera and ROCKNIX say so; Valkyr itself writes no game lists.
     */
    fun needsGamelistUpdate(layout: Layout): Boolean = layout == Layout.BATOCERA || layout == Layout.ROCKNIX

    /** One path segment a memory card and these systems accept as it is. */
    fun isSafeSegment(name: String): Boolean =
        name.isNotEmpty() && name.length <= 64 && '/' !in name && '\\' !in name && !name.startsWith(".") &&
            runCatching { ArchivePlan.safeRelativePath(name) == name }.getOrDefault(false)

    /** Folders of [layout] used by more than one system: folder → systems (compared like a memory card does). */
    fun sharedFolders(layout: Layout): Map<String, List<System>> =
        System.entries.mapNotNull { s -> folderFor(layout, s)?.let { it to s } }
            .groupBy({ ArchivePlan.portableKey(it.first) }, { it.second })
            .filterValues { it.size > 1 }

    // ---- Planning ----------------------------------------------------------------------------

    /** A planned folder that already exists at the destination under other letter case ([actual]); the system may expect [expected] exactly. */
    data class CaseDifference(val expected: String, val actual: String)

    data class Placement(
        /** The games with [PackageCopyPlan.Source.folder] set to where they go. */
        val sources: List<PackageCopyPlan.Source>,
        /** Games the layout has no folder for: they are not copied. */
        val unsupported: List<PackageCopyPlan.Source>,
        /** Games whose files sit deeper below the console folder than the system looks ([Layout.maxSubfolders]). */
        val tooDeep: List<PackageCopyPlan.Source>,
        val caseDifferences: List<CaseDifference>,
        /** 3.6: games with a subfolder whose name has an apostrophe, which [Layout.ONION] does not open. */
        val apostrophes: List<PackageCopyPlan.Source> = emptyList()
    )

    /**
     * The destination folder of each game of [sources] (whose [PackageCopyPlan.Source.folder] is
     * its folder in this library) under [layout]. [existingPath] tells how a folder path is
     * really spelled at the destination when it exists under any letter case (null = not there):
     * an existing folder is used as it is (a memory card cannot hold both spellings), and the
     * difference is reported. [LIBRARY][Layout.LIBRARY] changes nothing.
     */
    fun place(
        layout: Layout,
        sources: List<PackageCopyPlan.Source>,
        pickedFolderName: String?,
        existingPath: (String) -> String? = { null }
    ): Placement {
        if (layout == Layout.LIBRARY) return Placement(sources, emptyList(), emptyList(), emptyList())
        val placed = ArrayList<PackageCopyPlan.Source>()
        val unsupported = ArrayList<PackageCopyPlan.Source>()
        val differences = LinkedHashMap<String, CaseDifference>()
        val spelled = HashMap<String, String>()
        val roms = layout.romsFolder
        if (roms != null && pickedFolderName != null && pickedFolderName.equals(roms, ignoreCase = true) && pickedFolderName != roms) {
            differences[ArchivePlan.portableKey(roms)] = CaseDifference(roms, pickedFolderName)
        }
        for (source in sources) {
            val folder = destinationFolder(layout, source.consoleId, source.folder, pickedFolderName)
            if (folder == null) {
                unsupported += source
                continue
            }
            val actual = spelled.getOrPut(folder) { existingPath(folder)?.takeIf { ArchivePlan.portableKey(it) == ArchivePlan.portableKey(folder) } ?: folder }
            if (actual != folder) firstDifference(folder, actual).let { differences.getOrPut(ArchivePlan.portableKey(it.expected)) { it } }
            placed += source.copy(folder = actual)
        }
        val limit = layout.maxSubfolders
        val deep = if (limit == null) emptyList() else placed.filter { s ->
            s.parts.any { p -> !PackageCopyPlan.isSaveOrState(p.path) && p.path.count { it == '/' } > limit }
        }
        // Onion's documentation: a subfolder name must not contain an apostrophe.
        val apostrophes = if (layout != Layout.ONION) emptyList() else placed.filter { s ->
            s.parts.any { p -> !PackageCopyPlan.isSaveOrState(p.path) && p.path.substringBeforeLast('/', "").contains('\'') }
        }
        return Placement(placed, unsupported, deep, differences.values.toList(), apostrophes)
    }

    /** Only the first folder along [expected] that is spelled differently ("roms" of "roms/gba"), not every console folder inside it. */
    private fun firstDifference(expected: String, actual: String): CaseDifference {
        val e = expected.split('/')
        val a = actual.split('/')
        val i = e.indices.firstOrNull { e[it] != a.getOrNull(it) } ?: return CaseDifference(expected, actual)
        return CaseDifference(e.take(i + 1).joinToString("/"), a.take(i + 1).joinToString("/"))
    }

    // ---- Settings ----------------------------------------------------------------------------

    /** The layout chosen for copies to a folder (the only destination with layouts; RomM and the NAS keep their own naming). */
    const val FOLDER_SETTING_KEY = "layouts35_folder_layout"

    /** Its backup type, for [SettingSchema]. */
    val SETTING_TYPES: Map<String, String> = mapOf(FOLDER_SETTING_KEY to "s")
}
