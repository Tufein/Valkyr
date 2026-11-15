package com.tufein.valkyr.util

/**
 * 7.6: one remembered answer for the screens that only look at the disk (Storage, health check).
 * It is reused while it is young and while the library's [stamp] is the one it was made for; any
 * change of the stamp, [invalidate], or a failed walk (never stored) means a real walk.
 */
class SnapshotCache<T : Any>(private val ttlMs: Long, private val now: () -> Long = System::currentTimeMillis) {
    private var value: T? = null
    private var stamp: Any? = null
    private var madeAt = 0L

    @Synchronized fun get(stamp: Any?): T? {
        val v = value ?: return null
        return if (this.stamp == stamp && now() - madeAt in 0 until ttlMs) v else null
    }

    @Synchronized fun put(stamp: Any?, v: T) { value = v; this.stamp = stamp; madeAt = now() }

    @Synchronized fun invalidate() { value = null }

    companion object { const val DEFAULT_TTL_MS = 60_000L }
}
