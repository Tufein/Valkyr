package com.tufein.valkyr.util

/**
 * 6.7: the logic of Tools → Emulator check: which catalogue emulators are installed, and for every
 * console of the library whether something installed can start its games. Pure JVM for the tests;
 * the screen reads the installed packages and versions from the device.
 */
object EmulatorCheck {

    enum class Status { STANDALONE, RETROARCH, MISSING, UNSUPPORTED }

    /** An installed catalogue emulator: the package found (first variant installed) and its version. */
    data class Installed(val emulator: Emulator, val variant: Variant, val version: String?)

    data class Coverage(
        val consoleId: String,
        val system: PlaySystem?,
        val status: Status,
        /** Installed apps that can start this console's games (standalone first). */
        val installed: List<String>,
        /** For [Status.MISSING]: catalogue emulators for it that are not installed (labels, standalone first). */
        val suggestions: List<String>
    )

    fun installed(installedPackages: Set<String>, versionOf: (String) -> String?): List<Installed> =
        EmulatorCatalog.all.mapNotNull { e ->
            val variant = EmulatorCatalog.installedVariant(e, installedPackages) ?: return@mapNotNull null
            Installed(e, variant, versionOf(variant.packageName))
        }.sortedBy { it.emulator.label.lowercase() }

    /** The label of an emulator with its variant when it has one ("RetroArch (64-bit)"). */
    fun label(emulator: Emulator, variant: Variant): String = variant.label?.let { "${emulator.label} ($it)" } ?: emulator.label

    fun coverage(consoleId: String, installedPackages: Set<String>): Coverage {
        val system = EmulatorCatalog.systemOf(consoleId)
        val candidates = system?.let { EmulatorCatalog.emulatorsFor(it) }.orEmpty()
        val hasCores = system?.cores?.isNotEmpty() == true
        if (system == null || (candidates.isEmpty() && !hasCores)) return Coverage(consoleId, system, Status.UNSUPPORTED, emptyList(), emptyList())
        val ready = candidates.mapNotNull { e -> EmulatorCatalog.installedVariant(e, installedPackages)?.let { e to it } }
        val standalone = ready.filter { !it.first.libretro && it.first.id != "retroarch" }
        val retro = ready.filter { it.first.libretro || it.first.id == "retroarch" }
        val names = (standalone + retro).map { label(it.first, it.second) }.distinct()
        return when {
            standalone.isNotEmpty() -> Coverage(consoleId, system, Status.STANDALONE, names, emptyList())
            retro.isNotEmpty() -> Coverage(consoleId, system, Status.RETROARCH, names, emptyList())
            else -> {
                val missing = candidates.sortedBy { if (it.libretro || it.id == "retroarch") 1 else 0 }.map { it.label }.distinct().take(4)
                Coverage(consoleId, system, Status.MISSING, emptyList(), missing)
            }
        }
    }

    /** Problems first (missing, unsupported), then the rest, each by name. */
    fun coverages(consoleIds: Collection<String>, installedPackages: Set<String>, nameOf: (String) -> String): List<Coverage> =
        consoleIds.distinct().map { coverage(it, installedPackages) }
            .sortedWith(compareBy<Coverage>({ order(it.status) }, { nameOf(it.consoleId).lowercase() }))

    private fun order(s: Status) = when (s) { Status.MISSING -> 0; Status.UNSUPPORTED -> 1; Status.RETROARCH -> 2; Status.STANDALONE -> 3 }
}
