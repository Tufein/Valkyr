package com.tufein.valkyr.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualsTest {
    @Test fun pathsAreSafe() {
        assertEquals("manuals/snes/Super Mario World (USA)", Manuals.relativeDir("snes", "Super Mario World (USA).sfc"))
        assertFalse(Manuals.relativeDir("../..", "../..").contains(".."))
        assertEquals("a_b", Manuals.safeSegment("a/b"))
        assertEquals("game", Manuals.safeSegment("..."))
        assertTrue(Manuals.safeSegment("x".repeat(300)).length <= 80)
    }

    @Test fun kindsAndNames() {
        assertEquals(Manuals.Kind.PDF, Manuals.kindOf("Manual.PDF"))
        assertEquals(Manuals.Kind.IMAGE, Manuals.kindOf("p1.webp"))
        assertEquals(Manuals.Kind.TEXT, Manuals.kindOf("readme.md"))
        assertEquals(Manuals.Kind.OTHER, Manuals.kindOf("x.exe"))
        assertEquals("manual.pdf", Manuals.fileNameFor("https://h/a/b/manual.pdf?x=1"))
        assertEquals("12.pdf", Manuals.fileNameFor("https://h/a/12"))
        assertEquals("my manual.pdf", Manuals.fileNameFor("https://h/my%20manual.pdf"))
    }

    @Test fun docsGroupImagesInNaturalOrder() {
        val docs = Manuals.docsIn(listOf("p10.png", "p2.png", "b.pdf", "a.pdf", "notes.txt", "p1.jpg"))
        assertEquals(listOf("a.pdf", "b.pdf"), docs.take(2).map { it.title })
        val img = docs.first { it.kind == Manuals.Kind.IMAGE }
        assertEquals(listOf("p1.jpg", "p2.png", "p10.png"), img.files)
        assertEquals(Manuals.Kind.TEXT, docs.last().kind)
    }

    @Test fun capChoices() {
        assertEquals(500, Manuals.clampCapMb(null))
        assertEquals(250, Manuals.clampCapMb(260))
        assertEquals(2000, Manuals.stepCapMb(2000, 1))
        assertEquals(50, Manuals.stepCapMb(50, -1))
        assertEquals(100, Manuals.stepCapMb(50, 1))
    }

    @Test fun fitRemovesOldestFirst() {
        val cap = 100L * 1024 * 1024
        val mb = 1024L * 1024
        val files = listOf(Manuals.Entry("a", 40 * mb, 1), Manuals.Entry("b", 40 * mb, 2), Manuals.Entry("c", 10 * mb, 3))
        assertEquals(Manuals.Fit.Ok(emptyList()), Manuals.fit(files, 5 * mb, cap))
        val fit = Manuals.fit(files, 25 * mb, cap) as Manuals.Fit.Ok
        assertEquals(listOf("a"), fit.remove.map { it.path })
        assertEquals(Manuals.Fit.TooBig, Manuals.fit(files, 90 * mb, cap))
        val kept = Manuals.fit(files, 25 * mb, cap, protect = setOf("a")) as Manuals.Fit.Ok
        assertEquals(listOf("b"), kept.remove.map { it.path })
    }

    @Test fun lastPagesRoundTrip() {
        var t: String? = null
        t = Manuals.LastPages.put(t, "manuals/x/y/m.pdf", 4)
        t = Manuals.LastPages.put(t, "manuals/x/z/m.pdf", 7)
        t = Manuals.LastPages.put(t, "manuals/x/y/m.pdf", 5)
        assertEquals(5, Manuals.LastPages.get(t, "manuals/x/y/m.pdf"))
        assertEquals(0, Manuals.LastPages.get(t, "none"))
        assertEquals(listOf("manuals/x/z/m.pdf", "manuals/x/y/m.pdf"), Manuals.LastPages.decode(t).keys.toList())
        assertEquals(0, Manuals.LastPages.get("junk\n-3|a", "a"))
    }

    @Test fun pageState() {
        var s = ReaderState(count = 3)
        assertTrue(s.atStart)
        s = s.next().next().next()
        assertEquals(2, s.current); assertTrue(s.atEnd)
        assertEquals("3 / 3", s.label())
        assertEquals(1, s.prev().current)
        assertEquals(0, ReaderState(0).current)
        assertEquals("", ReaderState(0).label())
        assertEquals(2, ReaderState(5, page = 9).goTo(2).current)
        val z = ReaderState(2).zoomIn().zoomIn()
        assertEquals(2f, z.zoom, 0.001f)
        assertEquals(1f, z.next().zoom, 0.001f)
        assertEquals(1f, ReaderState(2, zoom = 4f).zoomCycle().zoom, 0.001f)
        assertEquals(1.5f, ReaderState(2, zoom = 2f).zoomOut().zoom, 0.001f)
        assertEquals(4f, ReaderState(2).withZoom(9f).zoom, 0.001f)
    }

    @Test fun markdownBlocks() {
        val b = MarkdownLite.parse("# Title\n- one **bold**\ntext `code`")
        assertEquals(1, b[0].level); assertEquals("Title", b[0].text)
        assertTrue(b[1].bullet); assertEquals("one bold", b[1].text)
        assertEquals("text code", b[2].text)
    }
}
