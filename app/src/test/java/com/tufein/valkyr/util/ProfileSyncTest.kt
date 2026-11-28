package com.tufein.valkyr.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileSyncTest {
    private fun p(name: String, c: Set<String> = emptySet(), t: Set<String> = emptySet(), at: Long = 1L) = SyncProfile(name, c, t, at)
    private fun docOf(vararg ps: SyncProfile) = ps.associateBy { ProfileSync.key(it.name) }

    @Test fun profilesOnOneSideOnlyAreAddedAndNothingIsRemoved() {
        val merged = ProfileSync.merge(docOf(p("Kids", setOf("psx"))), docOf(p("Couch", t = setOf("Adult"))))
        assertEquals(setOf("kids", "couch"), merged.keys)
    }

    @Test fun nameMatchingIgnoresCaseAndSpaces() {
        val merged = ProfileSync.merge(docOf(p(" kids ", setOf("psx"), at = 5)), docOf(p("KIDS", setOf("n64"), at = 9)))
        assertEquals(1, merged.size)
        assertEquals(setOf("n64"), merged["kids"]!!.hiddenConsoles)
    }

    @Test fun newerEditWinsAndTieIsDeterministic() {
        val a = p("Kids", setOf("psx"), at = 7); val b = p("Kids", setOf("n64"), at = 7)
        assertEquals(ProfileSync.merge(docOf(a), docOf(b)), ProfileSync.merge(docOf(b), docOf(a)))
    }

    @Test fun equalContentKeepsTheLaterTime() {
        val merged = ProfileSync.merge(docOf(p("Kids", at = 3)), docOf(p("Kids", at = 8)))
        assertEquals(8L, merged["kids"]!!.updatedAt)
    }

    @Test fun noRemoteIsMine() {
        val mine = docOf(p("Kids"))
        assertEquals(mine, ProfileSync.merge(mine, null))
    }

    @Test fun snapshotKeepsTheOldTimeOnlyWhenContentIsUnchanged() {
        val local = listOf(Profile("1", "Kids", setOf("psx"), setOf("Adult")), Profile("2", "Couch", setOf("n64")), Profile("3", " ", setOf("x")))
        val base = mapOf(
            "kids" to ProfileSync.Base(ProfileSync.contentOf(setOf("psx"), setOf("adult")), 100L),
            "couch" to ProfileSync.Base(ProfileSync.contentOf(emptySet(), emptySet()), 100L)
        )
        val snap = ProfileSync.snapshot(local, base, now = 500L)
        assertEquals(100L, snap["kids"]!!.updatedAt)
        assertEquals(500L, snap["couch"]!!.updatedAt)
        assertEquals(2, snap.size)
    }

    @Test fun applyChangesHidingKeepsIdsAndAddsNewProfiles() {
        val local = listOf(Profile("keep-id", "Kids", setOf("psx"), emptySet()))
        val merged = docOf(p("Kids", setOf("n64"), setOf("Adult")), p("Couch", setOf("snes")))
        var n = 0
        val out = ProfileSync.apply(local, merged) { "new${++n}" }
        assertEquals(2, out.size)
        assertEquals("keep-id", out[0].id)
        assertEquals(setOf("n64"), out[0].hiddenConsoles)
        assertEquals(setOf("Adult"), out[0].hiddenTags)
        assertEquals("new1", out[1].id)
        assertEquals("Couch", out[1].name)
    }

    @Test fun applyLeavesAnUnchangedListAlone() {
        val local = listOf(Profile("a", "Kids", setOf("psx"), setOf("Adult")))
        val merged = ProfileSync.merge(ProfileSync.snapshot(local, emptyMap(), 1L), null)
        assertEquals(local, ProfileSync.apply(local, merged) { error("no new profile expected") })
    }

    @Test fun documentRoundTrips() {
        val doc = docOf(p("Kids", setOf("psx", "n64"), setOf("Adult"), at = 42))
        val parsed = ProfileSync.decode(ProfileSync.encode(doc, "rev1", 99L))
        assertTrue(parsed is DocParsed.Ok)
        assertEquals(doc, (parsed as DocParsed.Ok).value)
    }

    @Test fun brokenRowsAreSkippedAndForeignOrNewerFilesAreSaid() {
        val text = """{"format":"valkyr-profiles","version":1,"profiles":[{"n":"Ok","t":1,"c":["a"],"g":[]},{"n":"","t":1},{"t":3},"x"]}"""
        val parsed = ProfileSync.decode(text) as DocParsed.Ok
        assertEquals(setOf("ok"), parsed.value.keys)
        assertTrue(ProfileSync.decode("{\"format\":\"other\"}") is DocParsed.Invalid)
        assertTrue(ProfileSync.decode("""{"format":"valkyr-profiles","version":9,"profiles":[]}""") is DocParsed.Newer)
        assertTrue(ProfileSync.decode("nonsense") is DocParsed.Invalid)
    }

    @Test fun baseRoundTrips() {
        val base = mapOf("kids" to ProfileSync.Base("a|b", 5L))
        assertEquals(base, ProfileSync.decodeBase(ProfileSync.encodeBase(base)))
        assertEquals(emptyMap<String, ProfileSync.Base>(), ProfileSync.decodeBase("broken"))
    }
}
