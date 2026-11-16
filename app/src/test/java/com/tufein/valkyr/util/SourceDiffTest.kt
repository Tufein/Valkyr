package com.tufein.valkyr.util

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceDiffTest {

    private fun plan(stored: List<Pair<Long, String>>, listed: List<String>) = SourceDiff.plan(stored, listed)

    @Test fun `an unchanged listing writes nothing`() {
        val p = plan(listOf(1L to "a", 2L to "b", 3L to "c"), listOf("a", "b", "c"))
        assertEquals(emptyList<Long>(), p.deleteIds)
        assertEquals(emptyList<Int>(), p.insertIndexes)
        assertEquals(3, p.unchanged)
    }

    @Test fun `one added and one removed line touch exactly two rows`() {
        val p = plan(listOf(1L to "a", 2L to "b", 3L to "c"), listOf("a", "c", "d"))
        assertEquals(listOf(2L), p.deleteIds)
        assertEquals(listOf(2), p.insertIndexes)
        assertEquals(2, p.unchanged)
    }

    @Test fun `order of the listing does not matter`() {
        val p = plan(listOf(1L to "a", 2L to "b"), listOf("b", "a"))
        assertEquals(0, p.deleteIds.size + p.insertIndexes.size)
    }

    @Test fun `identical rows stay as many rows as there were`() {
        val same = plan(listOf(1L to "a", 2L to "a"), listOf("a", "a"))
        assertEquals(2, same.unchanged)
        val fewer = plan(listOf(1L to "a", 2L to "a", 3L to "a"), listOf("a"))
        assertEquals(listOf(2L, 3L), fewer.deleteIds)
        val more = plan(listOf(1L to "a"), listOf("a", "a", "a"))
        assertEquals(listOf(1, 2), more.insertIndexes)
    }

    @Test fun `a first scan inserts everything and an emptied source deletes everything`() {
        assertEquals(listOf(0, 1), plan(emptyList(), listOf("a", "b")).insertIndexes)
        assertEquals(listOf(5L, 6L), plan(listOf(5L to "a", 6L to "b"), emptyList()).deleteIds)
    }

    @Test fun `a changed row is a delete plus an insert`() {
        val p = plan(listOf(1L to "a|size=1"), listOf("a|size=2"))
        assertEquals(listOf(1L), p.deleteIds)
        assertEquals(listOf(0), p.insertIndexes)
    }
}
