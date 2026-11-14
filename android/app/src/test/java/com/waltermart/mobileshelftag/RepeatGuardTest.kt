package com.waltermart.mobileshelftag

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepeatGuardTest {
    @Test fun heldCodeIsAcceptedOnce() {
        val g = RepeatGuard(1500)
        assertTrue(g.accept("A", 0))
        assertFalse(g.accept("A", 700))
        assertFalse(g.accept("A", 2000)) // still in view: sightings keep extending the quiet period
    }

    @Test fun differentCodeIsAcceptedAtOnce() {
        val g = RepeatGuard(1500)
        assertTrue(g.accept("A", 0))
        assertTrue(g.accept("B", 100))
    }

    @Test fun sameCodeAfterQuietPeriodIsAcceptedAgain() {
        val g = RepeatGuard(1500)
        assertTrue(g.accept("A", 0))
        assertTrue(g.accept("A", 1600))
    }
}
