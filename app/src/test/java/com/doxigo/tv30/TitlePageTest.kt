package com.doxigo.tv30

import org.junit.Assert.assertEquals
import org.junit.Test

class TitlePageTest {
    @Test
    fun pan() {
        // Episode 1 from the side the row reads from, then a step along per episode, every fifth again.
        assertEquals(listOf(0f, 0.25f, 0.5f, 0.75f, 1f, 0f), (1..6).map { pan(it, rtl = false) })
        assertEquals(listOf(1f, 0.75f, 0.5f, 0.25f, 0f, 1f), (1..6).map { pan(it, rtl = true) })
        assertEquals(1f, pan(0, rtl = false)) // a file with no number still lands in the picture
    }

    @Test
    fun latinRunsCrossFences() {
        // A fenced English list is one run, so its commas and spaces are Inter's, not Vazir's.
        val lrm = '‎'
        val genres = "${lrm}Adventure$lrm, ${lrm}Drama$lrm, ${lrm}Sci-Fi$lrm"
        assertEquals(listOf("Adventure$lrm, ${lrm}Drama$lrm, ${lrm}Sci-Fi"), LATIN_RUN.findAll(genres).map { it.value }.toList())
        // The Persian comma still ends a run: «،» belongs to the line's own face.
        val rlm = '‏'
        assertEquals(listOf("Slow Horses", "MobLand"), LATIN_RUN.findAll("${rlm}Slow Horses$rlm، ${rlm}MobLand$rlm").map { it.value }.toList())
    }
}
