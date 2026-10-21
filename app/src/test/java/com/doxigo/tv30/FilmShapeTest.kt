package com.doxigo.tv30

import org.junit.Assert.assertEquals
import org.junit.Test

class FilmShapeTest {
    @Test
    fun aDecoderThatPadsToSixteenNineGivesWayToTheStream() {
        // Issue #2: a 2.39:1 film the decoder reports as 1920x1080.
        assertEquals(1920 to 800, filmShape(1920 to 1080, 1920 to 800))
        assertEquals(1280 to 536, filmShape(1280 to 720, 1280 to 536))
    }

    @Test
    fun otherwiseTheDecoderStands() {
        assertEquals(1920 to 800, filmShape(1920 to 800, 1920 to 1080)) // a playlist that says 16:9 for everything
        assertEquals(1920 to 1080, filmShape(1920 to 1080, 1920 to 1080))
        assertEquals(1920 to 1080, filmShape(1920 to 1080, 1920 to 1088)) // within 2%: the same shape
        assertEquals(1920 to 800, filmShape(1920 to 800, 1920 to 804))
        assertEquals(1920 to 1080, filmShape(1920 to 1080, null))
        assertEquals(1920 to 1080, filmShape(1920 to 1080, -1 to -1)) // no RESOLUTION in the playlist
    }
}
