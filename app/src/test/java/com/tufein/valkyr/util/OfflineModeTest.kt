package com.tufein.valkyr.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineModeTest {
    @Test fun chipRules() {
        assertTrue(OfflineMode.chipVisible(false, OfflineMode.Screen.HOME))
        assertTrue(OfflineMode.chipVisible(false, OfflineMode.Screen.DOWNLOADS))
        assertFalse(OfflineMode.chipVisible(true, OfflineMode.Screen.HOME))
        assertFalse(OfflineMode.chipVisible(false, OfflineMode.Screen.OTHER))
        assertFalse(OfflineMode.isOnline(true, false))
        assertTrue(OfflineMode.isOnline(true, true))
    }

    @Test fun mergeKeepsNewestPerField() {
        val a = OfflineMode.Pending(OfflineMode.Pending.KIND_ROMM_PROPS, 1, mapOf("status" to "finished", "rating" to 5), 1)
        val b = OfflineMode.Pending(OfflineMode.Pending.KIND_ROMM_PROPS, 2, mapOf("backlogged" to true), 2)
        val c = OfflineMode.Pending(OfflineMode.Pending.KIND_ROMM_PROPS, 1, mapOf("status" to null), 3)
        val m = OfflineMode.merge(OfflineMode.merge(listOf(a), b), c)
        assertEquals(2, m.size)
        assertEquals(mapOf("status" to null, "rating" to 5), m[0].changes)
        assertEquals(3L, m[0].at)
    }

    @Test fun codecRoundTripAndJunk() {
        val list = listOf(OfflineMode.Pending(OfflineMode.Pending.KIND_ROMM_PROPS, 7, mapOf("status" to "retired", "now_playing" to true, "rating" to 8, "x" to null), 9))
        assertEquals(list, OfflineMode.decode(OfflineMode.encode(list)))
        assertTrue(OfflineMode.decode("not json").isEmpty())
        assertTrue(OfflineMode.decode(null).isEmpty())
        assertTrue(OfflineMode.decode("[{\"kind\":\"k\"},{\"rom\":1,\"changes\":{}}]").isEmpty())
    }

    @Test fun offlineList() {
        assertTrue(OfflineMode.Works.PLAY in OfflineMode.worksOffline)
    }

    @Test fun discardRemovesOnlyTheWrittenFieldsOfThatGame() {
        val a = OfflineMode.Pending(OfflineMode.Pending.KIND_ROMM_PROPS, 1, mapOf("status" to "finished", "rating" to 5), 1)
        val b = OfflineMode.Pending(OfflineMode.Pending.KIND_ROMM_PROPS, 2, mapOf("rating" to 3), 2)
        val out = OfflineMode.discard(listOf(a, b), 1, setOf("rating"))
        assertEquals(listOf(a.copy(changes = mapOf("status" to "finished")), b), out)
        assertEquals(listOf(b), OfflineMode.discard(listOf(a, b), 1, setOf("status", "rating")))
        assertEquals(listOf(a, b), OfflineMode.discard(listOf(a, b), 3, setOf("rating")))
    }

    @Test fun onlyHopelessAnswersDropAQueuedChange() {
        assertTrue(OfflineMode.dropsEntry(404))
        assertTrue(OfflineMode.dropsEntry(422))
        assertTrue(OfflineMode.dropsEntry(400))
        assertFalse(OfflineMode.dropsEntry(401))
        assertFalse(OfflineMode.dropsEntry(403))
        assertFalse(OfflineMode.dropsEntry(429))
        assertFalse(OfflineMode.dropsEntry(500))
        assertFalse(OfflineMode.dropsEntry(503))
        assertFalse(OfflineMode.dropsEntry(null))
    }
}
