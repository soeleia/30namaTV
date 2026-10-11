package com.doxigo.tv30

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsTest {
    @Test
    fun pickQuality() {
        val all = listOf("Auto", "1080p", "720p", "480p")
        assertEquals(1, Settings.pickQuality(all, "1080p"))
        assertEquals(0, Settings.pickQuality(all, "Auto"))
        assertEquals(1, Settings.pickQuality(listOf("Auto", "1080p HQ", "720p"), "1080p")) // same height, other label
        assertEquals(2, Settings.pickQuality(listOf("Auto", "480p", "720p"), "1080p")) // best not above
        assertEquals(1, Settings.pickQuality(listOf("Auto", "720p", "1080p"), "480p")) // all above: the lowest
        assertEquals(0, Settings.pickQuality(listOf(null, "720p"), "Auto")) // a null label is Auto
        assertEquals(0, Settings.pickQuality(listOf("1080p", "720p"), "Auto")) // no Auto: the first
        assertEquals(0, Settings.pickQuality(emptyList(), "720p"))
    }
}
