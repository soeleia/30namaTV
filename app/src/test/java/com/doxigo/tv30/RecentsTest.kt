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
    fun arabicLettersArePersian() {
        // «کیک» typed on an Arabic keyboard (ك, ي), kept from before as «كيك», and «مستی» with an alef maksura.
        assertEquals(listOf("کیک", "dune"), Recents.push(listOf("dune", "كيك"), "كيك"))
        assertEquals(listOf("کیک", "dune"), Recents.push(listOf("كيك", "dune"), "کیک"))
        assertEquals(listOf("مستی"), Recents.push(listOf("مستى"), "مستي"))
        // Both kept before the two counted as one: they are one now.
        assertEquals(listOf("dune", "کیک"), Recents.push(listOf("كيك", "کیک"), "dune"))
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

    @Test
    fun shortQueriesShowTheSuggestions() {
        assertEquals("", searchQuery(""))
        assertEquals("", searchQuery(" d "))
        assertEquals("", searchQuery("ب"))
        assertEquals("du", searchQuery(" du"))
        assertEquals("با", searchQuery("با "))
        assertEquals("dune", searchQuery("dune"))
    }
}
