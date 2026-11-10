package com.tufein.valkyr.util

import java.util.concurrent.atomic.AtomicLong
import android.os.StrictMode
import android.os.SystemClock

/**
 * 4.5.0: measure points for startup and slow calls, kept in memory only (no file or log I/O on the
 * hot path) and shown as numbers in the Diagnose report. Names are fixed labels chosen in code,
 * never a title, a path or any user text ([clean] enforces that for anything passed in).
 */
object PerfLog {
    /** Startup phases, in ms since the process was forked; the first mark of a name wins. */
    val phases = Phases()

    /** Marks [name] as reached now, in ms since this process was forked (API 24+; minSdk is 29). */
    fun mark(name: String) {
        phases.mark(name, SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime())
    }

    /** Debug builds only (the caller checks): StrictMode logs main-thread disk/network use and leaks to Logcat, never crashes. */
    fun enableStrictMode() {
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectAll().penaltyLog().build())
    }

    /** Calls slower than [SLOW_MS] (database queries, lookups), newest [CAPACITY] kept. */
    val ops = SlowOps(CAPACITY, SLOW_MS)

    private val coverMemory = AtomicLong()
    private val coverDisk = AtomicLong()
    private val coverNetwork = AtomicLong()

    /** A cover came from the memory cache, the disk cache or the network (Coil's event listener). */
    fun coverLoaded(source: CoverSource) {
        when (source) {
            CoverSource.MEMORY -> coverMemory.incrementAndGet()
            CoverSource.DISK -> coverDisk.incrementAndGet()
            CoverSource.NETWORK -> coverNetwork.incrementAndGet()
        }
    }

    fun coverCounts(): CoverCounts = CoverCounts(coverMemory.get(), coverDisk.get(), coverNetwork.get())

    /** Times [block] and keeps it when it took longer than the threshold. Works around suspend calls too (inline). */
    inline fun <T> timed(name: String, block: () -> T): T {
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            ops.record(name, (System.nanoTime() - start) / 1_000_000L)
        }
    }

    const val CAPACITY = 64
    const val SLOW_MS = 50L
    const val MAX_NAME = 40

    /** Keeps [raw] to letters, digits and `_ . : -`, at most [MAX_NAME] characters: a label, never user text. */
    fun clean(raw: String): String = raw.filter { it.isLetterOrDigit() && it.code < 128 || it in "_.:-" }.take(MAX_NAME).ifEmpty { "op" }

    enum class CoverSource { MEMORY, DISK, NETWORK }

    data class CoverCounts(val memory: Long, val disk: Long, val network: Long) {
        val total: Long get() = memory + disk + network

        /** Share of covers served without the network, 0..100; null before any cover loaded. */
        val hitPercent: Int? get() = if (total == 0L) null else ((memory + disk) * 100 / total).toInt()
    }

    /** A named startup mark. */
    data class Phase(val name: String, val ms: Long)

    /** One slow call. */
    data class Op(val name: String, val ms: Long)

    /** Startup marks; thread safe. */
    class Phases {
        private val marks = LinkedHashMap<String, Long>()

        @Synchronized
        fun mark(name: String, msSinceProcessStart: Long) {
            marks.putIfAbsent(clean(name), msSinceProcessStart.coerceAtLeast(0L))
        }

        @Synchronized
        fun all(): List<Phase> = marks.map { Phase(it.key, it.value) }.sortedBy { it.ms }

        @Synchronized
        fun get(name: String): Long? = marks[name]
    }

    /** A fixed-size ring of calls at or above [thresholdMs]; recording is O(1) and allocation-light. */
    class SlowOps(private val capacity: Int, private val thresholdMs: Long) {
        private val ring = arrayOfNulls<Op>(capacity)
        private var next = 0
        private var size = 0

        @Synchronized
        fun record(name: String, ms: Long) {
            if (ms < thresholdMs) return
            ring[next] = Op(clean(name), ms)
            next = (next + 1) % capacity
            if (size < capacity) size++
        }

        /** The [n] slowest calls still in the ring, slowest first. */
        @Synchronized
        fun slowest(n: Int): List<Op> = (0 until size).mapNotNull { ring[it] }.sortedByDescending { it.ms }.take(n)

        @Synchronized
        fun count(): Int = size

        @Synchronized
        fun clear() {
            ring.fill(null)
            next = 0
            size = 0
        }
    }

    /** The "Performance" block of the Diagnose report: numbers and fixed labels only. */
    fun report(phases: List<Phase>, slowest: List<Op>, covers: CoverCounts, dbBytes: Long): String = buildString {
        appendLine("Performance")
        if (phases.isEmpty()) appendLine("  startup: no marks yet")
        else appendLine("  startup (ms since process start): " + phases.joinToString { "${it.name}=${it.ms}" })
        if (slowest.isEmpty()) appendLine("  slow calls (>= ${SLOW_MS} ms): none recorded this run")
        else {
            appendLine("  slowest calls this run (>= ${SLOW_MS} ms):")
            slowest.forEach { appendLine("    ${it.name}: ${it.ms} ms") }
        }
        val hit = covers.hitPercent
        appendLine(
            "  covers: " + if (hit == null) "none loaded this run"
            else "$hit% without network (memory ${covers.memory}, disk ${covers.disk}, network ${covers.network})"
        )
        appendLine("  database size: " + if (dbBytes < 0) "unknown" else "${dbBytes / 1024} KiB")
    }
}
