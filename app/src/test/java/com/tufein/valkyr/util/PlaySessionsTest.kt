package com.tufein.valkyr.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaySessionsTest {

    private val g = "nes|mario"
    private val p = "default"

    @Test fun `clamp turns a backwards clock into a zero length session`() {
        val s = PlaySessions.Session(g, p, 1000L, 500L)
        val c = PlaySessions.clamp(s)
        assertEquals(1000L, c.endMs)
        assertEquals(0L, c.lengthMs)
    }

    @Test fun `a normal session keeps its length`() {
        val s = PlaySessions.Session(g, p, 1000L, 4000L)
        assertEquals(3000L, PlaySessions.clamp(s).lengthMs)
    }

    @Test fun `a crash loses the running session but keeps finished ones`() {
        val sessions = listOf(
            PlaySessions.Session(g, p, 1000L, 4000L),
            PlaySessions.Session(g, p, 5000L, 0L) // running, lost on crash
        )
        val t = PlaySessions.totals(sessions, g, p)
        assertEquals(3000L, t.timePlayedMs)
        assertEquals(1, t.sessions)
    }

    @Test fun `two sessions in one day add up`() {
        val sessions = listOf(
            PlaySessions.Session(g, p, 1000L, 2000L),
            PlaySessions.Session(g, p, 3000L, 3500L)
        )
        val t = PlaySessions.totals(sessions, g, p)
        assertEquals(1500L, t.timePlayedMs)
        assertEquals(2, t.sessions)
        assertEquals(1000L, t.firstPlayedMs)
        assertEquals(3500L, t.lastPlayedMs)
    }

    @Test fun `profile switch keeps sessions separate`() {
        val sessions = listOf(
            PlaySessions.Session(g, "a", 1000L, 2000L),
            PlaySessions.Session(g, "b", 3000L, 3500L)
        )
        assertEquals(1000L, PlaySessions.totals(sessions, g, "a").timePlayedMs)
        assertEquals(500L, PlaySessions.totals(sessions, g, "b").timePlayedMs)
        assertEquals(0, PlaySessions.totals(sessions, g, "c").sessions)
    }

    @Test fun `merge drops exact duplicates`() {
        val s = PlaySessions.Session(g, p, 1000L, 2000L)
        val merged = PlaySessions.merge(listOf(s), listOf(s))
        assertEquals(1, merged.size)
    }

    @Test fun `pause contributes nothing until resumed`() {
        // A paused-then-resumed session is recorded as two finished sessions.
        val sessions = listOf(
            PlaySessions.Session(g, p, 1000L, 1500L),
            PlaySessions.Session(g, p, 2000L, 2500L)
        )
        assertEquals(1000L, PlaySessions.totals(sessions, g, p).timePlayedMs)
    }

    @Test fun `empty totals`() {
        assertEquals(PlaySessions.Totals.EMPTY, PlaySessions.totals(emptyList(), g, p))
        assertNull(PlaySessions.totals(emptyList(), g, p).firstPlayedMs)
    }

    @Test fun `closing bounds a session and drops an absurd one`() {
        val running = PlaySessions.Session(g, p, 1_000L, 0L)
        assertEquals(5_000L, PlaySessions.close(running, 6_000L).lengthMs)
        assertEquals(PlaySessions.MAX_SESSION_MS, PlaySessions.close(running, Long.MAX_VALUE).lengthMs)
        assertEquals(0L, PlaySessions.close(running, 500L).lengthMs)
    }

    @Test fun `the file round trips and ignores a future version`() {
        val sessions = listOf(PlaySessions.Session(g, p, 1_000L, 6_000L), PlaySessions.Session("nds|z", p, 9_000L, 12_000L))
        assertEquals(sessions.sortedBy { it.startMs }, PlaySessions.fromJson(PlaySessions.toJson(sessions)).sortedBy { it.startMs })
        assertEquals(emptyList<PlaySessions.Session>(), PlaySessions.fromJson("""{"format":"valkyr-play-sessions","version":99,"sessions":[]}"""))
        assertEquals(emptyList<PlaySessions.Session>(), PlaySessions.fromJson("{ broken"))
    }

    @Test fun `a bounded file keeps the newest sessions`() {
        val many = (1L..4_010L).map { PlaySessions.Session(g, p, it, it + 10L) }
        assertEquals(PlaySessions.MAX_SESSIONS, PlaySessions.trim(many).size)
        assertEquals(4_010L, PlaySessions.trim(many).first().startMs)
    }
}