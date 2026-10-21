package com.doxigo.tv30

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TitlesTest {
    private fun title(options: String) = JSONObject("""{"id": 1, "options": $options}""")

    @Test
    fun streamsByItsFlag() {
        // As `single` and `archive` send them: Kite Man is coming_soon and streams all the same.
        assertTrue(title("""{"stream": true, "coming_soon": true}""").streams)
        assertFalse(title("""{"stream": false, "coming_soon": true}""").streams)
        assertTrue(title("""{"stream": false, "coming_soon": true}""").comingSoon)
        // Continue watching and older lists leave it out: they stream.
        assertTrue(title("""{"title_type": "movie"}""").streams)
        assertTrue(JSONObject("""{"id": 1}""").streams)
    }

    @Test
    fun aTitleThatDoesNotStreamIsNeverStreamable() {
        // Free or not, signed in or not: 30nama has nothing to play.
        assertFalse(title("""{"stream": false, "free_stream": true}""").streamable)
        assertTrue(title("""{"stream": true, "free_stream": true}""").streamable)
        assertTrue(title("""{"free_stream": true}""").streamable)
    }

    @Test
    fun yearRange() {
        assertEquals("2004–2012", yearRange("2004", "2012")) // Bleach
        assertNull(yearRange("2026", "2026")) // ended the year it began: the year says it
        assertNull(yearRange("2005", null)) // still on the air
        assertNull(yearRange("2012", "2004"))
        assertNull(yearRange(null, "2012"))
    }

    @Test
    fun yearsAreOneLatinRun() {
        // The dash joins its years, so all of "2004–2012" is Inter's.
        assertEquals(listOf("2004–2012"), LATIN_RUN.findAll("\u200E2004–2012\u200E").map { it.value }.toList())
    }
}
