package com.tufein.valkyr.util

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.Instant

/** What happened to a game. */
enum class HistoryKind { DOWNLOADED, PLAYED, SAVED }

/** One entry of the play history timeline. [openable] is true when it is tied to a library game. */
data class HistoryEvent(
    val kind: HistoryKind,
    val at: Long,
    val consoleId: String,
    val fileName: String,
    val title: String,
    /** Device or emulator that saved it, when known. */
    val via: String? = null,
    val openable: Boolean = true
)

/** The heading of a day group; the screen turns it into text (Today, Yesterday, a weekday, a date). */
sealed interface DayLabel {
    data object Today : DayLabel
    data object Yesterday : DayLabel
    /** Within the last week: the weekday's name. */
    data class Weekday(val date: LocalDate) : DayLabel
    data class Date(val date: LocalDate) : DayLabel
}

data class DayGroup(val label: DayLabel, val date: LocalDate, val events: List<HistoryEvent>)

/** Which events the chips show. */
enum class HistoryFilter { ALL, DOWNLOADS, PLAYED }

/**
 * The timeline of the Play history tool: merging the sources of events, filtering, grouping by day
 * and counting activity per day and week. Pure JVM; time and zone are passed in for the tests.
 */
object PlayHistory {

    fun filter(events: List<HistoryEvent>, filter: HistoryFilter): List<HistoryEvent> = when (filter) {
        HistoryFilter.ALL -> events
        HistoryFilter.DOWNLOADS -> events.filter { it.kind == HistoryKind.DOWNLOADED }
        // Saving to RomM means the game was played somewhere.
        HistoryFilter.PLAYED -> events.filter { it.kind == HistoryKind.PLAYED || it.kind == HistoryKind.SAVED }
    }

    /**
     * Merges event lists, newest first, without events from the future (clock skew) or without a
     * time. A save at exactly the time of a play of the same game is the play seen from RomM's
     * side (the shelf falls back to ES-DE) and is dropped.
     */
    fun merge(downloads: List<HistoryEvent>, plays: List<HistoryEvent>, saves: List<HistoryEvent>, now: Long): List<HistoryEvent> {
        val playKeys = plays.mapTo(HashSet()) { Triple(it.consoleId, it.fileName.lowercase(), it.at) }
        val keptSaves = saves.filter { Triple(it.consoleId, it.fileName.lowercase(), it.at) !in playKeys }
        return (downloads + plays + keptSaves)
            .filter { it.at > 0L && it.at <= now + CLOCK_SKEW_MS }
            .sortedWith(compareByDescending<HistoryEvent> { it.at }.thenBy { it.title.lowercase() }.thenBy { it.kind.ordinal })
    }

    private const val CLOCK_SKEW_MS = 5 * 60 * 1000L

    /** The label of [date] seen from [today]. */
    fun labelFor(date: LocalDate, today: LocalDate): DayLabel = when {
        date == today -> DayLabel.Today
        date == today.minusDays(1) -> DayLabel.Yesterday
        date.isAfter(today.minusDays(7)) && date.isBefore(today) -> DayLabel.Weekday(date)
        else -> DayLabel.Date(date)
    }

    fun dayOf(at: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()

    /** [events] (newest first) in consecutive day groups. */
    fun groupByDay(events: List<HistoryEvent>, now: Long, zone: ZoneId): List<DayGroup> {
        val today = dayOf(now, zone)
        val out = ArrayList<DayGroup>()
        var currentDate: LocalDate? = null
        var bucket = ArrayList<HistoryEvent>()
        fun flush() {
            val d = currentDate ?: return
            out += DayGroup(labelFor(d, today), d, bucket)
            bucket = ArrayList()
        }
        for (event in events) {
            val d = dayOf(event.at, zone)
            if (d != currentDate) { flush(); currentDate = d }
            bucket += event
        }
        flush()
        return out
    }

    /** Events per day for the last [days] days, oldest first, ending today. */
    fun dailyCounts(events: List<HistoryEvent>, now: Long, zone: ZoneId, days: Int = 7): List<Int> {
        val today = dayOf(now, zone)
        val counts = IntArray(days)
        for (e in events) {
            val ago = java.time.temporal.ChronoUnit.DAYS.between(dayOf(e.at, zone), today).toInt()
            if (ago in 0 until days) counts[days - 1 - ago]++
        }
        return counts.toList()
    }

    /** The dates the [dailyCounts] columns stand for, oldest first. */
    fun dailyDates(now: Long, zone: ZoneId, days: Int = 7): List<LocalDate> {
        val today = dayOf(now, zone)
        return (days - 1 downTo 0).map { today.minusDays(it.toLong()) }
    }

    /** Events per week (Monday to Sunday) for the last [weeks] weeks, oldest first, the last one being this week. */
    fun weeklyCounts(events: List<HistoryEvent>, now: Long, zone: ZoneId, weeks: Int = 8): List<Int> {
        val thisMonday = dayOf(now, zone).with(DayOfWeek.MONDAY)
        val counts = IntArray(weeks)
        for (e in events) {
            val monday = dayOf(e.at, zone).with(DayOfWeek.MONDAY)
            val ago = java.time.temporal.ChronoUnit.WEEKS.between(monday, thisMonday).toInt()
            if (ago in 0 until weeks) counts[weeks - 1 - ago]++
        }
        return counts.toList()
    }

    /** The next page: [count] events from [shown] on, and whether more remain. */
    fun pageEnd(total: Int, shown: Int, pageSize: Int = PAGE_SIZE): Int = (shown + pageSize).coerceAtMost(total)

    const val PAGE_SIZE = 50
}
