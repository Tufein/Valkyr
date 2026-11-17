package com.tufein.valkyr.util

import org.junit.Assert.assertEquals
import org.junit.Test

class TextSizeTest {
    @Test fun `steps move one at a time and stop at both ends`() {
        assertEquals(115, TextSize.shift(100, 1))
        assertEquals(85, TextSize.shift(100, -1))
        assertEquals(200, TextSize.shift(200, 1))
        assertEquals(85, TextSize.shift(85, -1))
        assertEquals(200, TextSize.shift(150, 2))
        assertEquals(130, TextSize.shift(100, 2))
    }

    @Test fun `a value between steps counts as the nearest step`() {
        assertEquals(100, TextSize.shift(97, 0))
        assertEquals(130, TextSize.shift(120, 1))
    }

    @Test fun `unknown values fall back to the normal size`() {
        assertEquals(1.3f, TextSize.factor(130), 0.0001f)
        assertEquals(1f, TextSize.factor(42), 0.0001f)
        assertEquals(1f, TextSize.factor(TextSize.DEFAULT), 0.0001f)
    }
}
