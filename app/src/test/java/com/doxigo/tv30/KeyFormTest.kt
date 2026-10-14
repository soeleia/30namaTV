package com.doxigo.tv30

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyFormTest {
    private val token = "k7m2xq9pab"

    private fun get(path: String) = "GET $path HTTP/1.1\r\nHost: 192.168.1.5:41234\r\n\r\n"

    private fun post(body: String, path: String = "/$token", length: Int = body.length) =
        "POST $path HTTP/1.1\r\nHost: 192.168.1.5:41234\r\ncontent-length: $length\r\n" +
            "Content-Type: application/x-www-form-urlencoded\r\n\r\n$body"

    private fun read(raw: String) = KeyRequest.read(raw, token)

    @Test
    fun onlyTheTokenServes() {
        assertEquals(KeyRequest.Page, read(get("/$token")))
        assertEquals(KeyRequest.Page, read(get("/$token?from=qr")))
        assertEquals(KeyRequest.Refused(404), read(get("/")))
        assertEquals(KeyRequest.Refused(404), read(get("/favicon.ico")))
        assertEquals(KeyRequest.Refused(404), read(get("/k7m2xq9pac"))) // one letter off
        assertEquals(KeyRequest.Refused(404), read(get("/$token/")))
        assertEquals(KeyRequest.Refused(404), read(post("key=0123456789abcdef", path = "/nope")))
        assertEquals(KeyRequest.Refused(405), read("PUT /$token HTTP/1.1\r\n\r\n"))
    }

    @Test
    fun formBodyToKey() {
        val v3 = "0123456789abcdef0123456789ABCDEF"
        assertEquals(KeyRequest.Key(v3), read(post("key=$v3")))
        // A v4 token's dots, and a paste's edges: a space and a line break, trimmed.
        assertEquals(KeyRequest.Key("eyJhbGc.eyJhdWQ.c2ln"), read(post("key=%20eyJhbGc.eyJhdWQ.c2ln%0D%0A")))
        assertEquals(KeyRequest.Key("a+b/c="), read(post("other=1&key=a%2Bb%2Fc%3D")))
    }

    @Test
    fun junkRefused() {
        assertEquals(KeyRequest.Refused(400), read(post("key=")))
        assertEquals(KeyRequest.Refused(400), read(post("key=+++"))) // spaces only
        assertEquals(KeyRequest.Refused(400), read(post("key=two+words"))) // a form's + is a space
        assertEquals(KeyRequest.Refused(400), read(post("key=%DA%A9%D9%84%DB%8C%D8%AF"))) // «کلید», a Persian keyboard's
        assertEquals(KeyRequest.Refused(400), read(post("key=abc%0Adef"))) // a break inside
        assertEquals(KeyRequest.Refused(400), read(post("key=%zz"))) // not percent-encoding
        assertEquals(KeyRequest.Refused(400), read(post("token=0123456789abcdef")))
        assertEquals(KeyRequest.Refused(411), read("POST /$token HTTP/1.1\r\n\r\nkey=abc"))
        assertEquals(KeyRequest.Refused(411), read(post("key=abc", length = -1)))
    }

    @Test
    fun waitsForTheWholeRequest() {
        assertNull(read("GET /$token HTTP/1.1\r\nHost: 192.168.1.5"))
        assertNull(read(post("key=0123", length = 20)))
        // Only Content-Length's bytes are the body.
        assertEquals(KeyRequest.Key("0123"), read(post("key=0123&junk", length = 8)))
    }

    @Test
    fun sizeCapped() {
        assertEquals(KeyRequest.Refused(413), read(post("key=" + "a".repeat(KeyRequest.MAX))))
        // A length past the cap never completes; it is refused once the bytes pass it.
        assertNull(read(post("key=abc", length = 100_000)))
        assertEquals(KeyRequest.Refused(413), read(get("/$token").dropLast(2) + "X-Pad: " + "a".repeat(KeyRequest.MAX)))
    }
}
