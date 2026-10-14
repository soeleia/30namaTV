package com.doxigo.tv30

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BufferingTest {
    /** A segment as the CDN signs it: the viewer's IPv4, an expiry and a signature in the path. */
    private fun segment(ip: String, expiry: String, signature: String, file: String = "series/p/pre/x.ts") =
        "https://cdn.example/stream/2/5/552065/136993/641187/$ip/$expiry/$signature/$file"

    /** The shape of a real one, its 58-digit signature included. */
    private val signed = segment("135.136.6.98", "1791828263", "30cefca22d9f9502f41da06db2bbe66646b99f9b1759ca5eb6e2715369")

    @Test
    fun dropsTheSignedParts() {
        assertEquals("https://cdn.example/stream/2/5/552065/136993/641187/series/p/pre/x.ts", Buffering.cacheKey(signed))
        // Another stream call: another exit IP, expiry and signature, the same bytes.
        assertEquals(Buffering.cacheKey(signed), Buffering.cacheKey(segment("10.0.0.7", "1791914663", "9f8e7d6c5b4a39281706f5e4d3c2b1a0") + "?token=abc"))
    }

    @Test
    fun leavesOtherUrlsAlone() {
        val plain = "https://cdn.example/stream/2/5/552065/136993/641187/series/p/pre/x.ts"
        assertEquals(plain, Buffering.cacheKey(plain))
        assertEquals(plain, Buffering.cacheKey("$plain?sig=1"))
        // Look-alikes: a short hex, or no expiry, is not the CDN's signature.
        val short = "https://cdn.example/stream/135.136.6.98/1791828263/30cefca2/x.ts"
        assertEquals(short, Buffering.cacheKey(short))
        val noExpiry = "https://cdn.example/stream/135.136.6.98/30cefca22d9f9502f41da06db2bbe66646b99f9b1759ca5eb6e2715369/x.ts"
        assertEquals(noExpiry, Buffering.cacheKey(noExpiry))
    }

    @Test
    fun differentFilesStayApart() {
        assertNotEquals(Buffering.cacheKey(signed), Buffering.cacheKey(signed.replace("/x.ts", "/y.ts")))
        assertNotEquals(Buffering.cacheKey(signed), Buffering.cacheKey(signed.replace("/641187/", "/641188/")))
    }
}
