package com.tufein.valkyr.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SnapshotCacheTest {
    private var t = 1_000L
    private val cache = SnapshotCache<String>(60_000) { t }

    @Test fun `a young answer for the same library is reused`() {
        cache.put(setOf("a"), "walk")
        t += 59_999
        assertEquals("walk", cache.get(setOf("a")))
    }

    @Test fun `an old answer is a miss`() {
        cache.put(setOf("a"), "walk")
        t += 60_000
        assertNull(cache.get(setOf("a")))
    }

    @Test fun `a changed library or an invalidation is a miss`() {
        cache.put(setOf("a"), "walk")
        assertNull(cache.get(setOf("a", "b")))
        assertEquals("walk", cache.get(setOf("a")))
        cache.invalidate()
        assertNull(cache.get(setOf("a")))
    }

    @Test fun `a clock that went backwards is a miss`() {
        cache.put(null, "walk")
        t -= 5
        assertNull(cache.get(null))
    }
}
