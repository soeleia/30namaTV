package com.doxigo.tv30

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MetaTest {
    @Test
    fun secondsLeft() {
        // continue_watching's own sample: 33 minutes in at 79.8387% of a 41-minute episode.
        assertEquals(500.0, secondsLeft(1980, 79.8387)!!.toDouble(), 1.0)
        assertNull(secondsLeft(1980, 0.0)) // not started: nothing to divide by
        assertNull(secondsLeft(1980, 100.0)) // finished
    }

    @Test
    fun genresLineDividerOnlyBetweenTwoGroups() {
        assertEquals(listOf("Drama", "|", "United States"), between("Drama", "United States", "|"))
        assertEquals(listOf("Drama"), between("Drama", null, "|")) // no origin: no dangling rule
        assertEquals(listOf("English"), between(null, "English", "|")) // no genres
        assertEquals(emptyList<String>(), between<String>(null, null, "|"))
    }
}
