package com.tufein.valkyr.util

/** A remark shown next to a BIOS file; translated in the UI. */
enum class BiosNote { JAPAN, USA, EUROPE, USA_OLDER, USA_EUROPE, PS2_ANY, GBA_OPTIONAL, BOOT_LOGO, ORIGINAL_BIOS_MODE, NEOGEO_SET }

/**
 * One BIOS file an emulator looks for. [path] is relative to the emulator's system folder
 * (RetroArch: `system`; a sub-folder like `dc/` is part of it). [md5] lists the known good dumps;
 * empty means any file of that name will do (firmware that differs per console). [label] is a
 * product name shown as is; [minSize] keeps a wildcard from matching a smaller file of another
 * system (a 512 KB PS1 `scph5501.bin` is not a 4 MB PS2 BIOS).
 */
data class BiosFile(
    val path: String, val md5: List<String>, val required: Boolean,
    val note: BiosNote? = null, val label: String = "", val minSize: Long = 0
)

/** A system that needs (or can use) BIOS files; [keys] match console ids / folder names. */
data class BiosSystem(val name: String, val keys: List<String>, val files: List<BiosFile>, val anyOf: Boolean = false)

/**
 * The BIOS files the common emulator cores on Android look for, with the checksums of the good
 * dumps as libretro documents them. A file with another checksum is reported as "another
 * version", not as wrong: some emulators accept several.
 */
object BiosCatalog {

    val systems: List<BiosSystem> = listOf(
        BiosSystem("PlayStation", listOf("playstation", "psx", "ps1"), listOf(
            BiosFile("scph5500.bin", listOf("8dd7d5296a650fac7319bce665a6a53c"), false, BiosNote.JAPAN),
            BiosFile("scph5501.bin", listOf("490f666e1afb15b7362b406ed1cea246"), false, BiosNote.USA),
            BiosFile("scph5502.bin", listOf("32736f17079d0b2b7024407c39bd3050"), false, BiosNote.EUROPE),
            BiosFile("scph1001.bin", listOf("924e392ed05558ffdb115408c263dccf"), false, BiosNote.USA_OLDER)
        ), anyOf = true),
        BiosSystem("PlayStation 2", listOf("playstation_2", "ps2"), listOf(
            BiosFile("scph*.bin", emptyList(), true, BiosNote.PS2_ANY, minSize = 4L * 1024 * 1024)
        )),
        BiosSystem("Sega Saturn", listOf("saturn"), listOf(
            BiosFile("sega_101.bin", listOf("85ec9ca47d8f6807718151cbcca8b964"), false, BiosNote.JAPAN),
            BiosFile("mpr-17933.bin", listOf("3240872c70984b6cbfda1586cab68dbe"), false, BiosNote.USA_EUROPE)
        ), anyOf = true),
        BiosSystem("Sega CD / Mega CD", listOf("sega_cd", "segacd", "mega_cd", "megacd"), listOf(
            BiosFile("bios_CD_U.bin", listOf("2efd74e3232ff260e371b99f84024f7f"), false, BiosNote.USA),
            BiosFile("bios_CD_E.bin", listOf("e66fa1dc5820d254611fdcdba0662372"), false, BiosNote.EUROPE),
            BiosFile("bios_CD_J.bin", listOf("278a9397d192149e84e820ac621a8edd"), false, BiosNote.JAPAN)
        ), anyOf = true),
        BiosSystem("PC Engine CD", listOf("pc_engine_cd", "pcenginecd", "turbografx_cd", "pce_cd"), listOf(
            BiosFile("syscard3.pce", listOf("38179df8f4ac870017db21ebcbf53114"), true, label = "System Card 3.0")
        )),
        BiosSystem("PC-FX", listOf("pc_fx", "pcfx"), listOf(
            BiosFile("pcfx.rom", listOf("08e36edbea28a017f79f8d4f7ff9b6d7"), true)
        )),
        BiosSystem("Dreamcast", listOf("dreamcast", "dc"), listOf(
            BiosFile("dc/dc_boot.bin", listOf("e10c53c2f8b90bab96ead2d368858623"), true),
            BiosFile("dc/dc_flash.bin", listOf("0a93f7940c455905bea6e392dfde92a4"), false)
        )),
        BiosSystem("Nintendo DS", listOf("nintendo_ds", "nds"), listOf(
            BiosFile("bios7.bin", listOf("df692a80a5b1bc90728bc3dfc76cd948"), false, BiosNote.ORIGINAL_BIOS_MODE),
            BiosFile("bios9.bin", listOf("a392174eb3e572fed6447e956bde4b25"), false),
            BiosFile("firmware.bin", emptyList(), false)
        )),
        BiosSystem("Game Boy Advance", listOf("gameboy_advance", "game_boy_advance", "gba"), listOf(
            BiosFile("gba_bios.bin", listOf("a860e8c0b6d573d191e4ec7db1b1e4f6"), false, BiosNote.GBA_OPTIONAL)
        )),
        BiosSystem("Game Boy / Color", listOf("gameboy", "game_boy", "gb", "gbc"), listOf(
            BiosFile("gb_bios.bin", listOf("32fbbd84168d3482956eb3c5051637f5"), false, BiosNote.BOOT_LOGO),
            BiosFile("gbc_bios.bin", listOf("dbfce9db9deaa2567f6a84fde55f9680"), false, BiosNote.BOOT_LOGO)
        )),
        BiosSystem("Famicom Disk System", listOf("famicom_disk", "fds"), listOf(
            BiosFile("disksys.rom", listOf("ca30b50f880eb660a320674ed365ef7a"), true)
        )),
        BiosSystem("3DO", listOf("3do"), listOf(
            BiosFile("panafz10.bin", listOf("51f2f43ae2f3508a14d9f56597e2d3ce"), true, label = "Panasonic FZ-10")
        )),
        BiosSystem("Atari Lynx", listOf("lynx"), listOf(
            BiosFile("lynxboot.img", listOf("fcd403db69f54290b51035d82f835e7b"), true)
        )),
        BiosSystem("Atari 5200", listOf("5200"), listOf(
            BiosFile("5200.rom", listOf("281f20ea4320404ec820fb7ec0693b38"), true)
        )),
        BiosSystem("Atari 7800", listOf("7800"), listOf(
            BiosFile("7800 BIOS (U).rom", listOf("0763f1ffb006ddbe32e52d497ee848ae"), false)
        )),
        BiosSystem("ColecoVision", listOf("colecovision", "coleco"), listOf(
            BiosFile("colecovision.rom", listOf("2c66f5911e5b42b8ebe113403548eee7"), true)
        )),
        BiosSystem("Intellivision", listOf("intellivision"), listOf(
            BiosFile("exec.bin", listOf("62e761035cb657903761800f4437b8af"), true),
            BiosFile("grom.bin", listOf("0cd5946c6473e42e8e4c2137785e427f"), true)
        )),
        BiosSystem("Odyssey² / Videopac", listOf("odyssey", "videopac"), listOf(
            BiosFile("o2rom.bin", listOf("562d5ebf9e030a40d6fabfc2f33139fd"), true)
        )),
        BiosSystem("Neo Geo", listOf("neo_geo", "neogeo"), listOf(
            BiosFile("neogeo.zip", emptyList(), true, BiosNote.NEOGEO_SET)
        )),
        // 8.5: MAME and FBNeo name their board BIOS sets as zips that stay zipped next to the games; the games of a board need its set.
        BiosSystem("Arcade (MAME, FBNeo)", listOf("arcade_mame", "arcade_fbneo", "mame", "fbneo", "arcade"), listOf(
            BiosFile("neogeo.zip", emptyList(), false, BiosNote.NEOGEO_SET),
            BiosFile("pgm.zip", emptyList(), false),
            BiosFile("skns.zip", emptyList(), false),
            BiosFile("decocass.zip", emptyList(), false),
            BiosFile("cchip.zip", emptyList(), false),
            BiosFile("ym2608.zip", emptyList(), false),
            BiosFile("nmk004.zip", emptyList(), false),
            BiosFile("midssio.zip", emptyList(), false)
        ), anyOf = true),
        BiosSystem("Pokémon mini", listOf("pokemon_mini", "pokemini"), listOf(
            BiosFile("bios.min", listOf("1e4fb124a3a886865acb574f388c803d"), false)
        ))
    )

    /** The systems relevant for these console ids (or folder names), in catalog order; each id gets its most specific system. */
    fun systemsFor(consoleIds: Collection<String>): List<BiosSystem> {
        fun score(id: String, key: String): Int {
            val k = normalize(key)
            val tokens = id.split('_')
            val hit = id == k || id.endsWith("_$k") || k in tokens || (k.length >= 5 && id.replace("_", "").contains(k.replace("_", "")))
            return if (hit) k.length else 0
        }
        val chosen = consoleIds.map { normalize(it) }.mapNotNull { id ->
            systems.maxByOrNull { s -> s.keys.maxOf { score(id, it) } }?.takeIf { s -> s.keys.maxOf { score(id, it) } > 0 }
        }.toSet()
        return systems.filter { it in chosen }
    }

    private fun normalize(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')

    enum class State { OK, OTHER_VERSION, MISSING, PRESENT_UNCHECKED }

    data class FileResult(val file: BiosFile, val state: State, val foundAs: String? = null)

    /** A system is ready when every required file is there (or, for "any of", at least one is). */
    data class SystemResult(val system: BiosSystem, val files: List<FileResult>) {
        val ready: Boolean get() = if (system.anyOf) files.any { it.state != State.MISSING }
            else files.filter { it.file.required }.all { it.state != State.MISSING }
        val allGood: Boolean get() = ready && files.none { it.state == State.OTHER_VERSION }
    }

    /**
     * Checks [system] against what is in the folder: [present] maps a lower-case relative path
     * (`dc/dc_boot.bin`) to the file's name as it is on disk, [md5Of] hashes it (null when it
     * cannot be read), [sizeOf] gives its size when known. Names are compared without case, as
     * most cores do.
     */
    fun check(
        system: BiosSystem, present: Map<String, String>,
        sizeOf: (String) -> Long? = { null }, md5Of: (String) -> String?
    ): SystemResult =
        SystemResult(system, system.files.map { f ->
            val wanted = f.path.lowercase()
            val hit = if (wanted.contains('*')) {
                val regex = Regex(wanted.replace(".", "\\.").replace("*", ".*"))
                present.keys.firstOrNull { (regex.matches(it.substringAfterLast('/')) || regex.matches(it)) && (sizeOf(it) ?: Long.MAX_VALUE) >= f.minSize }
            } else present.keys.firstOrNull { it == wanted }
            when {
                hit == null -> FileResult(f, State.MISSING)
                f.md5.isEmpty() -> FileResult(f, State.PRESENT_UNCHECKED, present[hit])
                else -> {
                    val md5 = md5Of(hit)?.lowercase()
                    FileResult(f, if (md5 != null && md5 in f.md5) State.OK else State.OTHER_VERSION, present[hit])
                }
            }
        })
}
