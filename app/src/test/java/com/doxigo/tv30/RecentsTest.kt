package com.doxigo.tv30

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentsTest {
    @Test
    fun newestFirst() {
        assertEquals(listOf("dune", "alien"), Recents.push(listOf("alien"), "dune"))
    }

    @Test
    fun againMovesToFront() {
        assertEquals(listOf("Alien", "dune", "heat"), Recents.push(listOf("dune", "alien", "heat"), "Alien"))
        assertEquals(listOf("بازی تاج و تخت", "dune"), Recents.push(listOf("dune", "بازی تاج و تخت"), " بازی  تاج و تخت "))
    }

    @Test
    fun cappedOldestOut() {
        val full = (1..Recents.CAP).map { "q$it" }
        val pushed = Recents.push(full, "new")
        assertEquals(Recents.CAP, pushed.size)
        assertEquals("new", pushed.first())
        assertEquals("q${Recents.CAP - 1}", pushed.last())
    }

    @Test
    fun blankAndLineBreaks() {
        assertEquals(listOf("dune"), Recents.push(listOf("dune"), "  "))
        assertEquals(listOf("the bear"), Recents.push(emptyList(), "the\nbear"))
    }
}
