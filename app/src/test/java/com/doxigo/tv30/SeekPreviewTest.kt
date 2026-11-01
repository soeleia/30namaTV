package com.doxigo.tv30

import org.junit.Assert.assertEquals
import org.junit.Test

class SeekPreviewTest {
    @Test
    fun storyboardOrder() {
        assertEquals(listOf(0, 4, 2, 1, 3), coarseToFine(5)) // the ends and middle, then between
        assertEquals((0 until 120).toList(), coarseToFine(120).sorted()) // each once
        assertEquals(listOf(0, 64, 32, 96), coarseToFine(120).take(4)) // the whole bar within four
        assertEquals(emptyList<Int>(), coarseToFine(0))
        assertEquals(listOf(0), coarseToFine(1))
    }

    @Test
    fun segments() {
        val starts = longArrayOf(0, 10_000, 20_000, 26_000)
        assertEquals(0, segmentAt(starts, 0))
        assertEquals(0, segmentAt(starts, 9_999))
        assertEquals(1, segmentAt(starts, 10_000))
        assertEquals(2, segmentAt(starts, 25_000))
        assertEquals(3, segmentAt(starts, 90_000)) // past the last start: the last
    }
}
