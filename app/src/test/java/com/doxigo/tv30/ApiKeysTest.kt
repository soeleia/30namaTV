package com.doxigo.tv30

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiKeysTest {
    @Test
    fun savedBeatsBuilt() {
        assertEquals("mine", Settings.pickKey("mine", "built"))
        assertEquals("mine", Settings.pickKey(" mine\n", "")) // a paste's edges
        assertEquals("built", Settings.pickKey("", "built")) // the developer's own build
        assertEquals("built", Settings.pickKey("  ", "built")) // blank is unset
        assertEquals("", Settings.pickKey("", "")) // a public build: off
    }

    @Test
    fun tmdbKeyKinds() {
        val v3 = "0123456789abcdef0123456789ABCDEF"
        val v4 = "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiIwMTIzIn0.c2lnbmF0dXJl"
        assertTrue(Tmdb.isApiKey(v3))
        assertFalse(Tmdb.isApiKey(v4))
        assertFalse(Tmdb.isApiKey("0123456789abcdef")) // too short for a v3 key: sent as a token, and refused

        // A v3 key goes as the api_key parameter, beside the path's own; a token as a Bearer header.
        val key = Tmdb.request("find/tt0903747?external_source=imdb_id", v3)
        assertEquals(v3, key.url.queryParameter("api_key"))
        assertEquals("imdb_id", key.url.queryParameter("external_source"))
        assertNull(key.header("Authorization"))
        val token = Tmdb.request("configuration", v4)
        assertEquals("Bearer $v4", token.header("Authorization"))
        assertNull(token.url.queryParameter("api_key"))
    }
}
