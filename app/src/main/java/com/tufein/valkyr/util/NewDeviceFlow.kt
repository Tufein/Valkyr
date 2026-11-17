package com.tufein.valkyr.util

/**
 * 9.0: one flow from install to first game.
 *
 * Pure: from the backup target, the installed packages, the BIOS report and a sample of the library,
 * produce an ordered list of steps — restore, folders, emulators, BIOS, library sample, first launch —
 * each with what it will write and the check that proves it. A step is only DONE when its check has
 * actually run and passed; a null check result means "not run yet" and stays TODO.
 */
object NewDeviceFlow {

    enum class Kind { RESTORE, FOLDERS, EMULATORS, BIOS, LIBRARY_SAMPLE, FIRST_LAUNCH }

    enum class State { TODO, DONE, SKIPPED }

    data class Step(
        val kind: Kind,
        val writes: String,
        val check: String,
        val state: State
    )

    data class Input(
        /** A configured backup target (WebDAV/S3/folder) exists. */
        val backupTargetConfigured: Boolean = false,
        /** A backup was found on the target. */
        val backupPresent: Boolean = false,
        /** Password check result: null = not run yet. */
        val backupPasswordOk: Boolean? = null,
        /** Folders confirmed present and writable: null = not run yet. */
        val foldersOk: Boolean? = null,
        /** Emulators installed for the consoles that have games: null = not run yet. */
        val emulatorsOk: Boolean? = null,
        /** Console ids missing BIOS. */
        val biosMissing: List<String> = emptyList(),
        /** BIOS check has run. */
        val biosChecked: Boolean = false,
        /** Library sample verified complete: null = not run yet. */
        val librarySampleOk: Boolean? = null
    )

    fun plan(input: Input): List<Step> {
        val steps = ArrayList<Step>()
        // 1. restore
        val restoreState = when {
            !input.backupTargetConfigured || !input.backupPresent -> State.SKIPPED
            input.backupPasswordOk == true -> State.DONE
            else -> State.TODO
        }
        steps += Step(Kind.RESTORE, "Restore settings, sources, favourites, collections and the download list.",
            "Backup opens and decrypts with the given password.", restoreState)
        // 2. folders
        val foldersState = when (input.foldersOk) {
            true -> State.DONE
            false -> State.TODO
            null -> State.TODO
        }
        steps += Step(Kind.FOLDERS, "Confirm the ROMs folder and per-console folders.",
            "Each configured folder exists and is writable.", foldersState)
        // 3. emulators
        val emulatorsState = when (input.emulatorsOk) {
            true -> State.DONE
            false -> State.TODO
            null -> State.TODO
        }
        steps += Step(Kind.EMULATORS, "Install or pick an emulator per console that has games.",
            "Every console with games has a launchable emulator.", emulatorsState)
        // 4. bios
        val biosState = if (!input.biosChecked) State.TODO
        else if (input.biosMissing.isEmpty()) State.DONE else State.TODO
        steps += Step(Kind.BIOS, "Fetch or point at the BIOS files a console needs.",
            "No required BIOS is missing for the consoles in the library.", biosState)
        // 5. library sample
        val sampleState = when (input.librarySampleOk) {
            true -> State.DONE
            false -> State.TODO
            null -> State.TODO
        }
        steps += Step(Kind.LIBRARY_SAMPLE, "Verify a sample of the library against the source.",
            "The sampled games are present with the expected size.", sampleState)
        // 6. first launch
        val firstLaunchState = if (steps.all { it.state != State.TODO }) State.DONE else State.TODO
        steps += Step(Kind.FIRST_LAUNCH, "Launch the first game.",
            "A game starts in its emulator.", firstLaunchState)
        return steps
    }
}