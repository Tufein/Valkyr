package com.tufein.valkyr.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 7.5: which failed cloud runs are tried again, and when ([CloudRetryPolicy]). */
class CloudRetryPolicyTest {
    private val offline = DavException(DavProblem.NETWORK)
    private val wrongLogin = DavException(DavProblem.AUTH)

    @Test
    fun `a passing problem is retried with growing waits and then given up`() {
        assertEquals(30_000L, CloudRetryPolicy.waitBefore(2, offline))
        assertEquals(120_000L, CloudRetryPolicy.waitBefore(3, offline))
        assertEquals(600_000L, CloudRetryPolicy.waitBefore(4, offline))
        assertNull(CloudRetryPolicy.waitBefore(5, offline))
        assertTrue(CloudRetryPolicy.WAITS_MS.zipWithNext().all { (a, b) -> b > a })
        assertEquals(4, CloudRetryPolicy.MAX_ATTEMPTS)
    }

    @Test
    fun `a slow server is retried too`() {
        assertEquals(30_000L, CloudRetryPolicy.waitBefore(2, DavException(DavProblem.TIMEOUT)))
    }

    @Test
    fun `a final problem is never retried`() {
        listOf(DavProblem.AUTH, DavProblem.TLS, DavProblem.BAD_URL, DavProblem.NO_SPACE, DavProblem.TOO_LARGE, DavProblem.NOT_FOUND).forEach {
            assertNull(it.name, CloudRetryPolicy.waitBefore(2, DavException(it)))
        }
        assertNull(CloudRetryPolicy.waitBefore(2, wrongLogin))
        assertNull(CloudRetryPolicy.waitBefore(2, IllegalStateException("not a cloud error")))
    }

    @Test
    fun `a device that should rest stops the retries`() {
        assertNull(CloudRetryPolicy.waitBefore(2, offline, stop = true))
    }
}
