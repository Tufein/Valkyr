package com.tufein.valkyr.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class ProblemReportTest {
    private val utc = ZoneOffset.UTC

    private val facts = ReportFacts(
        generatedAt = 1_760_000_000_000L,
        appVersion = "3.0.0", buildCode = 42, packageName = "com.tufein.valkyr", signing = ReportSigning.PRODUCTION,
        manufacturer = "AYN", model = "Thor", androidRelease = "13", sdk = 33, firmware = "TQ3A.230805.001", abi = "arm64-v8a",
        windowDp = "960x540 dp", displays = listOf(ReportDisplay(1920, 1080, false), ReportDisplay(1240, 1080, true)), controller = true,
        appLanguage = "nl", systemLocale = "nl-NL", fontScale = 1.3f, textSizePercent = 120,
        volumes = listOf(ReportVolume(true, false, "mounted", 40L shl 30, 118L shl 30), ReportVolume(false, true, "mounted", null, null)),
        settings = mapOf("auto_unzip" to "true", "concurrent_downloads" to "3", "rom_folder" to "/storage/emulated/0/ROMs", "theme" to "https://evil.example/x"),
        rommConfigured = true, rommVersion = "4.1.0", webDav = true, retroAchievements = false, saveSync = true, debrid = "NONE",
        apps = listOf(ReportApp("RetroArch", "com.retroarch.aarch64", "1.19.1")),
        consoles = 12, sourcesEnabled = 20, sourcesTotal = 22, libraryGames = 12_345, profiles = 2,
        downloadsByStatus = mapOf("QUEUED" to 4, "FAILED" to 2, "COMPLETED" to 0),
        failedByCategory = mapOf("NETWORK" to 1, "STORAGE_FULL" to 1),
        health = listOf(
            HealthResult(HealthCheck.STORAGE, HealthStatus.OK, HealthDetail.STORAGE_OK, a = 40L shl 30, b = 2L shl 30),
            HealthResult(HealthCheck.SOURCES, HealthStatus.LOOK, HealthDetail.SOURCES_PARTIAL, a = 2, b = 22)
        ),
        healthSkipped = listOf(HealthCheck.ROMM, HealthCheck.UPDATE),
        actions = listOf(ReportHistoryLine(1_760_000_000_000L, "DOWNLOAD_FAILED", "dl:NETWORK"), ReportHistoryLine(1_759_000_000_000L, "SYNC_FAILED", "Failed at https://nas.lan/dav")),
        operations = listOf(ReportHistoryLine(1_759_900_000_000L, "trash", "stored")),
        crash = """
            Mon Oct 12 10:00:00 CEST 2026 on thread main
            java.lang.IllegalStateException: Cannot open /storage/emulated/0/ROMs/secret.zip for sander@example.com
            	at android.os.Handler.dispatchMessage(Handler.java:106)
            	at com.tufein.valkyr.data.service.DownloadService.start(DownloadService.kt:120)
            	at com.tufein.valkyr.ui.MainActivity.onCreate(MainActivity.kt:88)
            Caused by: java.io.IOException: token=abc123 https://romm.home.lan/api
            	at com.tufein.valkyr.data.service.RommClient${'$'}get${'$'}2.invokeSuspend(Unknown Source:12)
            	at kotlinx.coroutines.DispatchedTask.run(DispatchedTask.kt:108)
            	... 12 more
        """.trimIndent(),
        crashAt = 1_759_990_000_000L
    )

    private fun full(text: ReportText = ReportText(), category: ReportCategory = ReportCategory.BUG, secrets: List<String> = emptyList()) =
        ProblemReport.build(facts, text, category, ReportSection.entries.toSet(), secrets, utc)

    @Test fun `report follows the launch template headings`() {
        val out = full()
        listOf("### What happened", "### Expected result", "### Steps to reproduce", "### App version and build",
            "### Device, Android version and firmware", "### Emulator or server versions", "### Sanitized diagnostics").forEach {
            assertTrue(it, out.contains(it))
        }
        assertTrue(out.contains("**Category:** Bug"))
        assertTrue(out.contains("1. \n2. \n3. "))
        assertTrue(out.contains("Valkyr 3.0.0 (build 42), release"))
        assertTrue(out.contains("production app"))
        assertTrue(out.contains("Second display: yes"))
        assertTrue(out.contains("Controller connected: yes"))
        assertTrue(out.contains("set up, server version 4.1.0"))
        assertTrue(out.contains("RetroArch (com.retroarch.aarch64) 1.19.1"))
        assertTrue(out.contains("library size: 10,000-49,999 games"))
        assertTrue(out.contains("FAILED=2"))
        assertTrue(out.contains("STORAGE_FULL=1"))
        assertFalse("zero counts are left out", out.contains("COMPLETED"))
        assertTrue(out.contains("sources: LOOK (SOURCES_PARTIAL; 2, 22)"))
        assertTrue(out.contains("storage: OK (STORAGE_OK; 40.0 GB, 2.0 GB)"))
        assertTrue(out.contains("not run here (they need the network): romm, update"))
        assertTrue(out.contains("2025-10-09 08:53 | DOWNLOAD_FAILED | dl:NETWORK"))
        assertTrue(out.contains("trash | stored"))
        // Storage-change heading only appears when filled in or for a data-loss report.
        assertFalse(out.contains("### Storage or save changes"))
        assertTrue(full(category = ReportCategory.DATA_LOSS_RISK).contains("### Storage or save changes"))
    }

    @Test fun `switched off sections are not written`() {
        val out = ProblemReport.build(facts, ReportText(), ReportCategory.IDEA, setOf(ReportSection.APP), zone = utc)
        assertTrue(out.contains("Valkyr 3.0.0"))
        assertFalse(out.contains("Thor"))
        assertFalse(out.contains("RetroArch"))
        assertFalse(out.contains("Health checks"))
        assertFalse(out.contains("Last crash"))
        assertFalse(out.contains("nl-NL"))
        assertFalse(out.contains("GB"))
        assertTrue(out.contains("_Not included by the reporter._"))
    }

    @Test fun `only whitelisted settings with plain values are written`() {
        val out = full()
        assertTrue(out.contains("auto_unzip=true"))
        assertTrue(out.contains("concurrent_downloads=3"))
        assertTrue(out.contains("theme=<omitted>"))
        assertFalse(out.contains("rom_folder"))
        assertFalse(out.contains("evil.example"))
        assertFalse(out.contains("ROMs"))
    }

    @Test fun `crash keeps class names and app frames only`() {
        val lines = ProblemReport.crashSummary(facts.crash)
        assertEquals("java.lang.IllegalStateException", lines.first())
        assertTrue(lines.any { it.contains("DownloadService.start(DownloadService.kt:120)") })
        assertTrue(lines.any { it.contains("MainActivity.onCreate(MainActivity.kt:88)") })
        assertTrue(lines.contains("Caused by: java.io.IOException"))
        assertTrue(lines.any { it.contains("invokeSuspend(Unknown Source:12)") })
        assertFalse(lines.any { it.contains("Handler.dispatchMessage") })
        assertFalse(lines.any { it.contains("kotlinx.coroutines") })
        val out = full()
        assertFalse(out.contains("secret.zip"))
        assertFalse(out.contains("abc123"))
        assertFalse(out.contains("romm.home.lan"))
        assertFalse(out.contains("on thread"))
        assertTrue("frame line numbers survive the cleaning", out.contains("(DownloadService.kt:120)"))
        assertTrue(out.contains("(Unknown Source:12)"))
        assertTrue(ProblemReport.crashSummary(null).isEmpty())
    }

    @Test fun `obfuscated release frames are kept, platform frames are not`() {
        val raw = "x.y: boom\n\tat a3.b.c(DownloadService.kt:12)\n\tat android.app.Activity.performCreate(Activity.java:8000)"
        assertEquals(listOf("x.y", "  at a3.b.c(DownloadService.kt:12)", "  (+1 other frames)"), ProblemReport.crashSummary(raw))
    }

    @Test fun `typed text with private details is cleaned`() {
        val text = ReportText(
            whatHappened = "Download from https://myserver.example.org:8443/roms/game.zip?token=abc failed, see /storage/emulated/0/Download/log.txt",
            expected = "Mail me at player.one@example.com, my RomM key is rmm_AbCdEf1234567890 and api_key=zzz999",
            steps = "1. Pick folder primary:ROMs/SNES\n2. Pick 1A2B-3C4D:Games/PS1\n3. Server 192.168.1.20:8080 and nas.local:5000 and C:\\Users\\Me\\roms",
            storage = "Key ghp_9f8e7d6c5b4a3f2e1d0c9b8a7f6e5d4c and file:///sdcard/x.txt and smb://nas/share and content://com.android.externalstorage.documents/tree/primary%3AROMs"
        )
        val out = full(text, secrets = listOf("SanderV"))
        listOf("myserver", "example.org", "abc", "/storage", "log.txt", "player.one", "@example.com", "AbCdEf1234567890", "zzz999",
            "ROMs/SNES", "1A2B-3C4D", "Games/PS1", "192.168", "nas.local", "5000", "Users", "ghp_9f8e", "sdcard", "nas/share", "externalstorage").forEach {
            assertFalse("'$it' leaked:\n$out", out.contains(it))
        }
        assertTrue(out.contains("<path>"))
        assertTrue(out.contains("<host>"))
        assertTrue(out.contains("<email>"))
        assertTrue(out.contains("<redacted>"))
    }

    @Test fun `cleaning twice changes nothing more`() {
        val once = ProblemReport.clean("see https://a.example/x, /storage/emulated/0/a, primary:ROMs, me@x.org, nas.lan:8080, content://p/tree/1, magnet:?xt=1")
        assertEquals(once, ProblemReport.clean(once))
    }

    @Test fun `known secrets such as a user name are removed`() {
        val out = full(ReportText(whatHappened = "Logged in as SanderV on the RA site"), secrets = listOf("SanderV"))
        assertFalse(out.contains("SanderV"))
    }

    @Test fun `history details that are not codes are dropped`() {
        val out = full()
        assertFalse(out.contains("nas.lan"))
        assertTrue(out.contains("SYNC_FAILED | <other>"))
    }

    @Test fun `ordinary text is left alone`() {
        val line = "On Android 13/15 the game Super Mario World (USA).zip froze at 14:03 after 2 minutes; RetroArch 1.19.1 and/or DuckStation."
        assertEquals(line, ProblemReport.clean(line))
        assertEquals("at com.tufein.valkyr.ui.MainActivity.onCreate(MainActivity.kt:88)", ProblemReport.clean("at com.tufein.valkyr.ui.MainActivity.onCreate(MainActivity.kt:88)"))
    }

    @Test fun `signing kind and library bands`() {
        assertEquals(ReportSigning.PRODUCTION, ProblemReport.signingOf(listOf(ProblemReport.PRODUCTION_SIGNER.uppercase())))
        assertEquals(ReportSigning.TEST, ProblemReport.signingOf(listOf(ProblemReport.TEST_SIGNER)))
        assertEquals(ReportSigning.OTHER, ProblemReport.signingOf(listOf("00ff")))
        assertEquals(ReportSigning.UNKNOWN, ProblemReport.signingOf(emptyList()))
        assertEquals("none", ProblemReport.libraryBand(0))
        assertEquals("1-99", ProblemReport.libraryBand(99))
        assertEquals("100-999", ProblemReport.libraryBand(100))
        assertEquals("50,000 or more", ProblemReport.libraryBand(50_000))
    }

    @Test fun `the signer digests never appear in a report`() {
        val out = full()
        assertFalse(out.contains(ProblemReport.PRODUCTION_SIGNER))
        assertFalse(out.contains("e0e4ea"))
    }
}
