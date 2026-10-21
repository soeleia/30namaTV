package com.doxigo.tv30

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSessionTest {
    @Test
    fun previousRestartsFirst() {
        assertTrue(restarts(RESTART_MS + 1, hasPrevious = true)) // well in: back to the start
        assertFalse(restarts(RESTART_MS, hasPrevious = true)) // near the start: the episode before
        assertFalse(restarts(0, hasPrevious = true))
        assertTrue(restarts(0, hasPrevious = false)) // a film or a first episode: its start again
    }
}
