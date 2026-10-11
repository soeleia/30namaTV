package com.doxigo.tv30

import org.junit.Assert.assertEquals
import org.junit.Test

class RangesTest {
    @Test
    fun stretches() {
        for (count in listOf(0, 1, 12, 49, 50)) assertEquals(emptyList<IntRange>(), ranges(count)) // fits in one: unchanged
        assertEquals(listOf(0..49, 50..50), ranges(51)) // a remainder of one
        assertEquals(listOf(0..49, 50..99), ranges(100)) // an exact multiple
        assertEquals(listOf(0..49, 50..99, 100..149, 150..169), ranges(170)) // Black Clover
        assertEquals(22, ranges(1100).size) // One Piece-long
        assertEquals(1050..1099, ranges(1100).last())
        assertEquals(listOf(0..2, 3..4), ranges(5, size = 3))
    }
}
