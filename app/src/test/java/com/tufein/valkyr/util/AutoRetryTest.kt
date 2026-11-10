package com.tufein.valkyr.util

import com.tufein.valkyr.data.service.HttpStatusException
import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoRetryTest {
    @Test fun `waits grow and stop after the third retry`() {
        assertEquals(60_000L, AutoRetry.waitBeforeRetry(0))
        assertEquals(300_000L, AutoRetry.waitBeforeRetry(1))
        assertEquals(900_000L, AutoRetry.waitBeforeRetry(2))
        assertNull(AutoRetry.waitBeforeRetry(3))
    }

    @Test fun `server trouble is temporary and a missing file is not`() {
        listOf(408, 429, 500, 502, 503, 504).forEach { assertTrue("$it", AutoRetry.temporaryStatus(it)) }
        listOf(400, 401, 403, 404, 410).forEach { assertFalse("$it", AutoRetry.temporaryStatus(it)) }
    }

    @Test fun `RomM switched off is never retried or blamed on the source`() {
        val off = com.tufein.valkyr.data.service.RommSwitchedOffException()
        assertFalse(AutoRetry.isTemporary(off))
        assertFalse(SourceFailures.isSourceSide(off))
        assertTrue(AutoRetry.isTemporary(com.tufein.valkyr.data.service.RommException("RomM server URL not set")))
    }

    @Test fun `dropped connections are temporary`() {
        assertTrue(AutoRetry.isTemporary(SocketTimeoutException("timeout")))
        assertTrue(AutoRetry.isTemporary(IOException("Connection reset")))
        assertTrue(AutoRetry.isTemporary(HttpStatusException(503)))
    }

    @Test fun `permanent failures are left to the user`() {
        assertFalse(AutoRetry.isTemporary(HttpStatusException(404)))
        assertFalse(AutoRetry.isTemporary(Exception("Download directory not configured or no longer accessible.")))
    }

    @Test fun `the http exception keeps the message the diagnostics know`() {
        assertEquals("HTTP Error after redirect: 404", HttpStatusException(404).message)
    }
}
