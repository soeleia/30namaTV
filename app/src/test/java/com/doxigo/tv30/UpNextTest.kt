package com.doxigo.tv30

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpNextTest {
    /** Three episodes, their credits at 43, 44 and 45 minutes. */
    private val ends = listOf(2_580L, 2_640L, 2_700L)

    @Test
    fun midEpisode() {
        assertEquals(1 to 1_200L, upNext(ends, 1, 1_200))
        assertEquals(1 to 2_639L, upNext(ends, 1, 2_639)) // a second short of the credits
    }

    @Test
    fun pastCreditsWithNext() {
        assertEquals(2 to 0L, upNext(ends, 1, 2_640)) // on the mark
        assertEquals(1 to 0L, upNext(ends, 0, 2_900)) // 91% in
    }

    @Test
    fun pastCreditsAtFinale() {
        assertEquals(2 to 0L, upNext(ends, 2, 2_750)) // starts over
        assertEquals(0 to 0L, upNext(listOf(6_900L), 0, 7_000)) // a film
    }

    @Test
    fun noEndMark() {
        assertEquals(0 to 2_900L, upNext(listOf(null, 2_640L), 0, 2_900))
        assertEquals(-1 to 300L, upNext(ends, -1, 300)) // a saved file the list no longer has
    }

    @Test
    fun marks() {
        assertEquals(2_580L, seconds("00:43:00"))
        assertEquals(95L, seconds("1:35"))
        assertNull(seconds("43m"))
    }
}
