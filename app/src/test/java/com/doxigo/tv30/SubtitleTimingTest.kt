package com.doxigo.tv30

import androidx.media3.common.text.Cue
import androidx.media3.extractor.text.CuesWithTiming
import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleTimingTest {
    private fun cue(text: String, fromMs: Long, toMs: Long) =
        CuesWithTiming(listOf(Cue.Builder().setText(text).build()), fromMs * 1000, (toMs - fromMs) * 1000)

    private fun shown(timed: List<CuesWithTiming>, atMs: Long, offsetMs: Long = 0) =
        cuesAt(timed, atMs * 1000, offsetMs * 1000).map { it.text.toString() }

    @Test
    fun boundaries() {
        val timed = listOf(cue("a", 1_000, 2_000), cue("b", 2_000, 3_000))
        assertEquals(emptyList<String>(), shown(timed, 999))
        assertEquals(listOf("a"), shown(timed, 1_000)) // from its start
        assertEquals(listOf("a"), shown(timed, 1_999))
        assertEquals(listOf("b"), shown(timed, 2_000)) // gone at its end, where the next starts
        assertEquals(emptyList<String>(), shown(timed, 3_000))
    }

    @Test
    fun later() {
        val timed = listOf(cue("a", 1_000, 2_000))
        assertEquals(emptyList<String>(), shown(timed, 1_000, offsetMs = 250))
        assertEquals(listOf("a"), shown(timed, 1_250, offsetMs = 250))
        assertEquals(listOf("a"), shown(timed, 2_249, offsetMs = 250))
        assertEquals(emptyList<String>(), shown(timed, 2_250, offsetMs = 250))
    }

    @Test
    fun earlier() {
        val timed = listOf(cue("a", 1_000, 2_000))
        assertEquals(listOf("a"), shown(timed, 500, offsetMs = -500))
        assertEquals(emptyList<String>(), shown(timed, 1_500, offsetMs = -500))
        // Moved before the video starts, it never shows.
        assertEquals(emptyList<String>(), shown(timed, 0, offsetMs = -10_000))
    }

    @Test
    fun overlapping() {
        // Out of order in the file, as some are: the one that started first stays on top.
        val timed = listOf(cue("short", 2_000, 3_000), cue("long", 1_000, 4_000), cue("same start, shorter", 1_000, 2_500))
        assertEquals(listOf("long", "same start, shorter", "short"), shown(timed, 2_200))
        assertEquals(listOf("long", "short"), shown(timed, 2_700))
        assertEquals(listOf("long", "short"), shown(timed, 3_200, offsetMs = 500))
    }
}
