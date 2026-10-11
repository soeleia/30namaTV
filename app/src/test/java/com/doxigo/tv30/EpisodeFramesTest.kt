package com.doxigo.tv30

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpisodeFramesTest {
    @Test
    fun points() {
        assertEquals(675_000L to 1_080_000L, framePoints(45 * 60)) // a 45-minute episode: 11:15, then 18:00
        assertEquals(300_000L to 480_000L, framePoints(null)) // no credits mark: 5 and 8 minutes
    }

    @Test
    fun names() {
        assertEquals("123456.jpg", frameName("123456"))
        assertEquals("etcpasswd.jpg", frameName("../etc/passwd")) // never outside the folder
        assertNull(frameName(null))
        assertNull(frameName("/.."))
    }

    @Test
    fun brightness() {
        assertEquals(0, luma(intArrayOf(0xFF000000.toInt(), 0xFF000000.toInt())))
        assertEquals(255, luma(intArrayOf(0xFFFFFFFF.toInt())))
        assertEquals(149, luma(intArrayOf(0xFF00FF00.toInt()))) // green weighs most
    }
}
