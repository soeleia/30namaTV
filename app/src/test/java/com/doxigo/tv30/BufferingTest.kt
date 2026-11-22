package com.doxigo.tv30

import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.TreeSet

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

    /** A 200 carrying [body] from the CDN, as the video client's interceptor passes it on. */
    private fun cdn(body: ByteArray, type: String): Response {
        val request = Request.Builder().url(signed).build()
        val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(body.toResponseBody(type.toMediaType())).build()
        val chain = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Interceptor.Chain::class.java)) { _, method, _ ->
            when (method.name) {
                "request" -> request
                "proceed" -> response
                else -> error(method.name)
            }
        } as Interceptor.Chain
        return Buffering.refused.intercept(chain)
    }

    @Test
    fun refusalPageIsA403() {
        val got = cdn("<html><body></body></html>".toByteArray(), "text/html")
        assertEquals(403, got.code)
        assertEquals(0, got.body!!.bytes().size)
    }

    @Test
    fun mediaPassesUntouched() {
        val playlist = "#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:6.0,\nx.ts\n"
        val list = cdn(playlist.toByteArray(), "application/vnd.apple.mpegurl")
        assertEquals(200, list.code)
        assertEquals(playlist, list.body!!.string())
        // A TS segment: 188-byte packets, each opening on the sync byte 0x47.
        val ts = ByteArray(188 * 2) { if (it % 188 == 0) 0x47 else 0x1f }
        val segment = cdn(ts, "video/mp2t")
        assertEquals(200, segment.code)
        assertArrayEquals(ts, segment.body!!.bytes())
    }

    @Test
    fun cachedRefusalPagesGo() {
        // SimpleCache needs Android to open: a stand-in with real span files, as SimpleCache keeps them.
        val dir = Files.createTempDirectory("video").toFile()
        val spans = mutableMapOf<String, TreeSet<CacheSpan>>()
        fun put(key: String, bytes: ByteArray) {
            val file = dir.resolve(key).apply { writeBytes(bytes) }
            spans[key] = TreeSet(listOf(CacheSpan(key, 0, bytes.size.toLong(), 0, file)))
        }
        put("page.ts", "<html><body></body></html>".toByteArray())
        put("x.ts", ByteArray(188 * 8) { if (it % 188 == 0) 0x47 else 0x1f }) // over a kilobyte: never read
        put("tiny.vtt", "WEBVTT\n".toByteArray())
        val cache = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Cache::class.java)) { _, method, args ->
            when (method.name) {
                "getKeys" -> spans.keys
                "getCachedSpans" -> spans.getValue(args[0] as String)
                "removeResource" -> { spans.remove(args[0]); null }
                else -> error(method.name)
            }
        } as Cache
        Buffering.dropRefusals(cache)
        assertEquals(setOf("x.ts", "tiny.vtt"), spans.keys)
        dir.deleteRecursively()
    }
}
