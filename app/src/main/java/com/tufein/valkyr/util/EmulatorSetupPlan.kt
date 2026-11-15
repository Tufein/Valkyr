package com.tufein.valkyr.util

/**
 * 7.4: what is left to do before a console's games start, as ordered steps. Tools → Emulator check
 * says what is missing; this turns the gap into steps, each naming what it would change. It only
 * decides: every action opens a screen the app already has or a page outside it, and nothing is
 * installed or written from here. No Android calls, so every rule is unit-tested.
 */
object EmulatorSetupPlan {

    enum class Kind { EMULATOR, CORE, BIOS, SAVES, DISCS }

    /** [CHECK]: the app cannot see it (a RetroArch core lives in RetroArch's private folder); a test launch tells. */
    enum class State { DONE, TODO, CHECK, NOT_NEEDED }

    /** Which sentence a step shows; the screen maps each to a string filled with [Step.args]. */
    enum class Detail {
        EMULATOR_STANDALONE, EMULATOR_RETROARCH, EMULATOR_MISSING, EMULATOR_UNKNOWN,
        CORE_NEEDED, CORE_NOT_USED,
        BIOS_NONE_NEEDED, BIOS_NO_FOLDER, BIOS_MISSING, BIOS_OPTIONAL_MISSING, BIOS_OK,
        SAVES_EMULATOR_FOLDER, SAVES_RETROARCH_FOLDERS, SAVES_NOT_SET,
        DISCS_AUTO, DISCS_OFF, DISCS_NOT_DISC
    }

    /** What a step's button does. */
    enum class Action { INSTALL, OPEN_PLAYLISTS, OPEN_BIOS, OPEN_SAVE_SYNC, OPEN_SETTINGS }

    /** What following a step changes, said before it is done. */
    enum class Writes {
        /** Nothing in Valkyr or your files: you install an app yourself, outside Valkyr. */
        NOTHING_HERE,
        /** A setting in RetroArch that you change there. */
        IN_RETROARCH,
        /** The BIOS folder setting; files are only read. */
        BIOS_FOLDER_SETTING,
        /** The save folder settings; saves move only when you start a sync. */
        SAVE_FOLDER_SETTING,
        /** The automatic `.m3u` setting; a disc list is written beside a game after its download. */
        M3U_SETTING,
        NOTHING
    }

    /** An app that would start the console's games and is not installed. */
    data class Install(val label: String, val packageName: String, val retroArch: Boolean)

    data class Step(
        val kind: Kind,
        val state: State,
        val detail: Detail,
        /** Names for the sentence (installed apps, a core, missing files). */
        val args: List<String> = emptyList(),
        val action: Action? = null,
        val writes: Writes = Writes.NOTHING,
        /** For [Action.INSTALL]: the apps to choose from, standalone first. */
        val installs: List<Install> = emptyList()
    )

    data class Input(
        val consoleId: String,
        val installedPackages: Set<String>,
        /** The BIOS check of the console's system; null when the catalogue lists no BIOS for it. */
        val bios: BiosCatalog.SystemResult? = null,
        val biosFolderSet: Boolean = false,
        val savesFolderSet: Boolean = false,
        val statesFolderSet: Boolean = false,
        val emulatorSaveFolders: List<EmulatorSaveFolder> = emptyList(),
        val autoM3u: Boolean = true
    )

    data class Plan(val consoleId: String, val system: PlaySystem?, val steps: List<Step>) {
        /** Nothing is left that Valkyr can see: an emulator is there and no required BIOS file is missing. */
        val ready: Boolean get() = steps.none { it.state == State.TODO && it.kind in setOf(Kind.EMULATOR, Kind.BIOS) && it.detail != Detail.BIOS_OPTIONAL_MISSING }
        /** The steps that still ask for something, in order. */
        val open: List<Step> get() = steps.filter { it.state == State.TODO || it.state == State.CHECK }
    }

    fun plan(input: Input): Plan {
        val coverage = EmulatorCheck.coverage(input.consoleId, input.installedPackages)
        val system = coverage.system
        val steps = mutableListOf<Step>()

        // 1. Something that starts the games.
        val candidates = system?.let { EmulatorCatalog.emulatorsFor(it) }.orEmpty()
        val standaloneIds = candidates.filter { !it.isRetroArch() && EmulatorCatalog.installedVariant(it, input.installedPackages) != null }.map { it.id }
        steps += when (coverage.status) {
            EmulatorCheck.Status.STANDALONE -> Step(Kind.EMULATOR, State.DONE, Detail.EMULATOR_STANDALONE, coverage.installed)
            EmulatorCheck.Status.RETROARCH -> Step(Kind.EMULATOR, State.DONE, Detail.EMULATOR_RETROARCH, coverage.installed)
            EmulatorCheck.Status.MISSING -> Step(
                Kind.EMULATOR, State.TODO, Detail.EMULATOR_MISSING, coverage.suggestions, Action.INSTALL, Writes.NOTHING_HERE,
                // A few standalone apps first, and RetroArch always when it can run the console.
                (candidates.filter { !it.isRetroArch() }.take(MAX_INSTALLS - 1) + candidates.filter { it.isRetroArch() }.take(1))
                    .mapNotNull { e -> e.variants.firstOrNull()?.let { Install(e.label, it.packageName, e.isRetroArch()) } }
                    .distinctBy { it.packageName }
            )
            // No emulator is known for it: the manual way stays (any app that opens the file), it is no dead end.
            EmulatorCheck.Status.UNSUPPORTED -> Step(Kind.EMULATOR, State.CHECK, Detail.EMULATOR_UNKNOWN)
        }

        // 2. The RetroArch core, when RetroArch is what will start the games.
        val cores = system?.cores.orEmpty()
        steps += if (coverage.status == EmulatorCheck.Status.RETROARCH && cores.isNotEmpty()) {
            Step(Kind.CORE, State.CHECK, Detail.CORE_NEEDED, cores.take(MAX_CORES).map(EmulatorCatalog::coreLabel), Action.OPEN_PLAYLISTS, Writes.IN_RETROARCH)
        } else Step(Kind.CORE, State.NOT_NEEDED, Detail.CORE_NOT_USED)

        // 3. BIOS files.
        val bios = input.bios
        steps += when {
            bios == null -> Step(Kind.BIOS, State.NOT_NEEDED, Detail.BIOS_NONE_NEEDED)
            !input.biosFolderSet -> Step(Kind.BIOS, State.TODO, Detail.BIOS_NO_FOLDER, wanted(bios), Action.OPEN_BIOS, Writes.BIOS_FOLDER_SETTING)
            !bios.ready -> Step(Kind.BIOS, State.TODO, Detail.BIOS_MISSING, missing(bios, requiredOnly = !bios.system.anyOf), Action.OPEN_BIOS, Writes.BIOS_FOLDER_SETTING)
            missing(bios, requiredOnly = false).isNotEmpty() && !bios.system.anyOf ->
                Step(Kind.BIOS, State.TODO, Detail.BIOS_OPTIONAL_MISSING, missing(bios, requiredOnly = false), Action.OPEN_BIOS, Writes.BIOS_FOLDER_SETTING)
            else -> Step(Kind.BIOS, State.DONE, Detail.BIOS_OK, bios.files.filter { it.state != BiosCatalog.State.MISSING }.map { it.foundAs ?: it.file.path })
        }

        // 4. Where the saves are, so they can travel.
        val ownFolders = standaloneIds.flatMap { id -> EmulatorSaveFolders.foldersFor(id, input.consoleId, input.emulatorSaveFolders) }.map { it.label }.distinct()
        steps += when {
            ownFolders.isNotEmpty() -> Step(Kind.SAVES, State.DONE, Detail.SAVES_EMULATOR_FOLDER, ownFolders)
            coverage.status == EmulatorCheck.Status.RETROARCH && (input.savesFolderSet || input.statesFolderSet) -> Step(Kind.SAVES, State.DONE, Detail.SAVES_RETROARCH_FOLDERS)
            coverage.status == EmulatorCheck.Status.MISSING || coverage.status == EmulatorCheck.Status.UNSUPPORTED -> Step(Kind.SAVES, State.NOT_NEEDED, Detail.SAVES_NOT_SET)
            else -> Step(Kind.SAVES, State.TODO, Detail.SAVES_NOT_SET, action = Action.OPEN_SAVE_SYNC, writes = Writes.SAVE_FOLDER_SETTING)
        }

        // 5. Disc lists for games on several discs.
        steps += when {
            system?.disc != true -> Step(Kind.DISCS, State.NOT_NEEDED, Detail.DISCS_NOT_DISC)
            input.autoM3u -> Step(Kind.DISCS, State.DONE, Detail.DISCS_AUTO)
            else -> Step(Kind.DISCS, State.TODO, Detail.DISCS_OFF, action = Action.OPEN_SETTINGS, writes = Writes.M3U_SETTING)
        }
        return Plan(input.consoleId, system, steps)
    }

    private fun Emulator.isRetroArch() = libretro || id == "retroarch"

    /** The BIOS files a system asks for (required ones; all of them for "any of"). */
    private fun wanted(bios: BiosCatalog.SystemResult): List<String> =
        bios.files.filter { it.file.required || bios.system.anyOf }.map { it.file.path }.take(MAX_FILES)

    private fun missing(bios: BiosCatalog.SystemResult, requiredOnly: Boolean): List<String> =
        bios.files.filter { it.state == BiosCatalog.State.MISSING && (!requiredOnly || it.file.required) }.map { it.file.path }.take(MAX_FILES)

    private const val MAX_INSTALLS = 4
    private const val MAX_CORES = 3
    private const val MAX_FILES = 6
}
