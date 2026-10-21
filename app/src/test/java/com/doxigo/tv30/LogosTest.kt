package com.doxigo.tv30

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogosTest {
    private val images = JSONObject(
        """{"logos":[
            {"iso_639_1":"en","file_path":"/en-low.png","vote_average":5.2,"width":500},
            {"iso_639_1":"en","file_path":"/en-wide.svg","vote_average":5.3,"width":800},
            {"iso_639_1":"en","file_path":"/en-narrow.png","vote_average":5.3,"width":400},
            {"iso_639_1":null,"file_path":"/plain.png","vote_average":9.0,"width":900},
            {"iso_639_1":"fa","file_path":"/fa.png","vote_average":0,"width":300}
        ]}""",
    )

    @Test
    fun logo() {
        val w500 = "https://image.tmdb.org/t/p/w500"
        assertEquals("$w500/fa.png", Tmdb.logo(images, "fa")) // the interface's language first, however voted
        assertEquals("$w500/en-wide.png", Tmdb.logo(images, "en")) // best voted, then widest; an SVG as its PNG
        assertEquals("$w500/plain.png", Tmdb.logo(JSONObject("""{"logos":[{"iso_639_1":null,"file_path":"/plain.png"},{"iso_639_1":"de","file_path":"/de.png"}]}"""), "en"))
        assertEquals(null, Tmdb.logo(JSONObject("""{"logos":[{"iso_639_1":"de","file_path":"/de.png"}]}"""), "fa"))
        assertEquals(null, Tmdb.logo(null, "fa"))
    }

    @Test
    fun legible() {
        val clear = 0x00000000
        assertTrue(Logos.legible(intArrayOf(0xFFFFFFFF.toInt(), clear, clear))) // white lettering, mostly air
        assertTrue(Logos.legible(intArrayOf(0xFFE50914.toInt()))) // Netflix red still reads over ink
        val navy = 0xFF1A2A5A.toInt()
        assertTrue(Logos.legible(IntArray(9) { navy } + 0xFFE50914.toInt())) // red letters in a deep extrusion (Spider-Man's)
        assertFalse(Logos.legible(IntArray(19) { navy } + 0xFFFFFFFF.toInt())) // a white fleck on navy lettering
        assertFalse(Logos.legible(intArrayOf(0xFF000000.toInt(), 0xFF8B0000.toInt(), 0xFF5A5A5A.toInt()))) // black, dark red, grey: unseen
        assertFalse(Logos.legible(intArrayOf(clear, clear)))
    }

    @Test
    fun translated() {
        val entry = JSONObject("""{"translations":{"translations":[{"iso_639_1":"fa","data":{"overview":"","tagline":"شعار"}},{"iso_639_1":"fa","data":{"overview":"خلاصه"}}]}}""")
        assertEquals("خلاصه", entry.translated("overview", "fa")) // a blank one is no answer
        assertEquals("شعار", entry.translated("tagline", "fa"))
        assertEquals(null, entry.translated("overview", "de"))
    }
}
