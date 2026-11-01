package com.tufein.valkyr.util

/**
 * The decisions behind the second-screen hub (dual-screen handhelds such as the AYN Thor or the
 * Retroid Flip): which display to use, which layout fits it, which console chips and sections to
 * show, and how to read the battery and heat. Pure JVM for the tests.
 *
 * What comes from the public Android documentation and was NOT tried on a device: that
 * `DisplayManager.getDisplays(DISPLAY_CATEGORY_PRESENTATION)` lists the lower screen of those
 * handhelds, that `ActivityOptions.setLaunchDisplayId` can start an activity there, and the
 * display sizes. The rules below only work on the numbers they are given.
 */
object DualScreen {

    /** One display as `Display` / `DisplayManager` report it. */
    data class DisplayInfo(
        val id: Int,
        val valid: Boolean,
        /** It is in `DISPLAY_CATEGORY_PRESENTATION`. */
        val presentation: Boolean
    )

    /**
     * The display the hub is shown on as a presentation: a valid presentation display other than
     * the one the activity itself is on (an activity that was moved to the second screen has
     * nothing to present on). The lowest id wins so the choice does not change between calls.
     */
    fun presentationTarget(displays: List<DisplayInfo>, activityDisplayId: Int): Int? =
        displays.filter { it.valid && it.presentation && it.id != activityDisplayId }.minOfOrNull { it.id }

    /** The display "Open on the second screen" starts the hub activity on; same rule as [presentationTarget]. */
    fun launchTarget(displays: List<DisplayInfo>, activityDisplayId: Int): Int? =
        presentationTarget(displays, activityDisplayId)

    enum class Layout {
        /** Two columns: the games on the left, downloads and device state on the right. */
        WIDE,
        /** One scrolling column, for a nearly square or narrow screen. */
        STACKED
    }

    /** Wide only when the screen is both roomy and clearly landscape; everything else stacks. */
    fun layout(widthDp: Int, heightDp: Int): Layout =
        if (widthDp >= WIDE_MIN_WIDTH_DP && widthDp * 10 >= heightDp * 12) Layout.WIDE else Layout.STACKED

    /** A short display gets tighter spacing. */
    fun compact(heightDp: Int): Boolean = heightDp < COMPACT_BELOW_DP

    const val WIDE_MIN_WIDTH_DP = 560
    const val COMPACT_BELOW_DP = 440

    /** A console chip with the number of recent games of that console. */
    data class ConsoleChip(val consoleId: String, val count: Int)

    /** The consoles of [consoleIds] (one per game), most games first, at most [limit]. Needs two consoles to be worth showing. */
    fun chips(consoleIds: List<String>, limit: Int = MAX_CHIPS): List<ConsoleChip> {
        val counted = consoleIds.filter { it.isNotBlank() }.groupingBy { it }.eachCount()
        if (counted.size < 2) return emptyList()
        return counted.map { ConsoleChip(it.key, it.value) }
            .sortedWith(compareByDescending<ConsoleChip> { it.count }.thenBy { it.consoleId })
            .take(limit.coerceAtLeast(0))
    }

    /** The selected console, or null (all) when its chip is gone. */
    fun resolveSelection(selected: String?, chips: List<ConsoleChip>): String? =
        selected?.takeIf { s -> chips.any { it.consoleId == s } }

    fun <T> filter(items: List<T>, selected: String?, consoleOf: (T) -> String): List<T> =
        if (selected == null) items else items.filter { consoleOf(it) == selected }

    const val MAX_CHIPS = 8

    /** Which blocks the hub draws. */
    data class Sections(
        val continueCard: Boolean,
        val rail: Boolean,
        val chips: Boolean,
        val queue: Boolean,
        /** Nothing but the device state to show: the hub adds the mascot and a line of help. */
        val empty: Boolean
    )

    /**
     * @param historyCount recent games after the console filter
     * @param historyTotal recent games before the filter (chips stay while a filter empties the rail)
     * @param shelfEnabled the *Continue playing* setting; off hides the card and the rail
     * @param queueMode the downloads state; [SecondScreenControls.Mode.IDLE] has no queue block
     */
    fun sections(historyCount: Int, historyTotal: Int, shelfEnabled: Boolean, chipCount: Int, queueMode: SecondScreenControls.Mode): Sections {
        val games = shelfEnabled && historyTotal > 0
        val queue = queueMode != SecondScreenControls.Mode.IDLE
        return Sections(
            continueCard = games && historyCount > 0,
            rail = games && historyCount > 1,
            chips = games && chipCount >= 2,
            queue = queue,
            empty = !games && !queue
        )
    }

    /** The rail shows the games after the first, which the card above already offers. */
    fun <T> railItems(items: List<T>): List<T> = items.drop(1)

    enum class Level { OK, WARN, CRITICAL }

    /**
     * @property batteryPercent 0..100, null when the battery does not say
     * @property tempCelsius battery temperature, null when unknown
     */
    data class DeviceState(
        val batteryPercent: Int?,
        val charging: Boolean,
        val tempCelsius: Float?,
        val battery: Level,
        val heat: Level
    )

    /**
     * @param tenthsCelsius `BatteryManager.EXTRA_TEMPERATURE`, tenths of a degree
     * @param thermalStatus `PowerManager.getCurrentThermalStatus()`: 0 none, 1 light, 2 moderate, 3 severe, 4+ critical
     *
     * Heat is critical from 45 degrees (the same edge as the *Pause when hot* rule) or a severe
     * system status, a warning from 40 degrees or a moderate status.
     */
    fun deviceState(levelPercent: Int?, charging: Boolean, tenthsCelsius: Int?, thermalStatus: Int): DeviceState {
        val battery = when {
            levelPercent == null || charging -> Level.OK
            levelPercent <= 15 -> Level.CRITICAL
            levelPercent <= 30 -> Level.WARN
            else -> Level.OK
        }
        val heat = when {
            thermalStatus >= 3 || (tenthsCelsius ?: 0) >= 450 -> Level.CRITICAL
            thermalStatus == 2 || (tenthsCelsius ?: 0) >= 400 -> Level.WARN
            else -> Level.OK
        }
        return DeviceState(levelPercent, charging, tenthsCelsius?.let { it / 10f }, battery, heat)
    }
}
