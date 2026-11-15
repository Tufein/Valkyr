package com.tufein.valkyr.util

import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(FlowPreview::class)
class SearchDebounceTest {
    @Test fun `an empty box answers at once, text waits for a pause`() {
        assertEquals(0L, SearchDebounce.waitMs(""))
        assertEquals(0L, SearchDebounce.waitMs("  "))
        assertEquals(SearchDebounce.PAUSE_MS, SearchDebounce.waitMs("z"))
    }

    @Test fun `ten keystrokes become one query and clearing is immediate`() = runBlocking {
        val typed = flow {
            "zelda ocar".indices.forEach { emit("zelda ocar".substring(0, it + 1)); delay(20) }
            delay(SearchDebounce.PAUSE_MS + 150)
            emit("")
        }
        assertEquals(listOf("zelda ocar", ""), typed.debounce { SearchDebounce.waitMs(it) }.toList())
    }
}
