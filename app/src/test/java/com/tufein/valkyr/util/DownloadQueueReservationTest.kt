package com.tufein.valkyr.util

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadQueueReservationTest {
    @Test fun `reverse asynchronous arrivals keep registered queue order`() = runBlocking {
        val queue = DownloadQueue(1)
        queue.acquire("running")
        queue.reserveOrder(listOf("a", "b", "c"))
        val jobs = listOf("c", "b", "a").associateWith { name ->
            launch(start = CoroutineStart.UNDISPATCHED) { queue.acquire(name) }
        }
        assertEquals(listOf("a", "b", "c"), queue.waiting.value)
        for (name in listOf("a", "b", "c")) {
            queue.release()
            withTimeout(1_000) { jobs.getValue(name).join() }
        }
        queue.release()
    }

    @Test fun `reserved condition waiter never holds up a ready download`() = runBlocking {
        val queue = DownloadQueue(1)
        queue.reserveOrder(listOf("condition-blocked", "ready"))
        withTimeout(1_000) { queue.acquire("ready") }
        assertEquals(emptyList<String>(), queue.waiting.value)
        queue.release()
        queue.forgetOrder("condition-blocked")
    }

    @Test fun `user priority wins over automatic order and canceled reservations are forgotten`() = runBlocking {
        val queue = DownloadQueue(1)
        queue.acquire("running")
        queue.reserveOrder(listOf("a", "b", "c", "canceled"))
        val jobs = mutableListOf<kotlinx.coroutines.Job>()
        for (name in listOf("a", "b")) jobs += launch(start = CoroutineStart.UNDISPATCHED) { queue.acquire(name) }
        queue.moveToFront("b")
        queue.forgetOrder("canceled")
        for (name in listOf("c", "canceled")) jobs += launch(start = CoroutineStart.UNDISPATCHED) { queue.acquire(name) }
        assertEquals(listOf("b", "a", "c", "canceled"), queue.waiting.value)
        jobs.forEach { it.cancel() }
        jobs.forEach { it.join() }
        queue.release()
    }
}
