package com.tufein.valkyr.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppTourTest {
    @Test fun `four to five cards, each with its own heading and text`() {
        assertTrue(AppTour.cards.size in 4..5)
        assertEquals(AppTour.cards.size, AppTour.cards.map { it.title }.toSet().size)
        assertEquals(AppTour.cards.size, AppTour.cards.map { it.body }.toSet().size)
    }

    @Test fun `shown once, only after the first-run setup`() {
        assertTrue(AppTour.shouldShow(seen = 0, onboarded = true))
        assertFalse(AppTour.shouldShow(seen = AppTour.VERSION, onboarded = true))
        assertFalse(AppTour.shouldShow(seen = 0, onboarded = false))
    }

    @Test fun `a later tour version shows again`() {
        assertTrue(AppTour.shouldShow(seen = AppTour.VERSION - 1, onboarded = true))
    }

    @Test fun `next and previous stay inside the cards`() {
        assertNull(AppTour.previous(0))
        assertEquals(1, AppTour.next(0))
        assertNull(AppTour.next(AppTour.cards.lastIndex))
        assertEquals(AppTour.cards.lastIndex - 1, AppTour.previous(AppTour.cards.lastIndex))
    }
}
