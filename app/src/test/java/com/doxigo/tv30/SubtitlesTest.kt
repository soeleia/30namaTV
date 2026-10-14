package com.doxigo.tv30

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SubtitlesTest {
    @Test
    fun windows1256() {
        // «سلام ایران، کتاب و پیک» as a Persian subtitle from the Subscene years carries it: the page
        // has no Persian ی, so ی is the Arabic ي (0xED), and ک is the page's own (0x98).
        val bytes = "d3e1c7e320c7edd1c7e4a120dfcac7c820e62081ed98".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertEquals("سلام ایران، کتاب و پیک", Subtitles.decode(bytes, "fa"))
    }

    @Test
    fun utf8() {
        val text = "1\n00:00:01,000 --> 00:00:02,000\nسلام، NASA!\n"
        assertEquals(text, Subtitles.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray(), "fa"))
        assertEquals(text, Subtitles.decode(text.toByteArray(), "fa")) // UTF-8 without a mark is not taken for 1256
        assertEquals("Café", Subtitles.decode("Café".toByteArray(Charsets.UTF_16LE).let { byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + it }, "en"))
        assertEquals("Café", Subtitles.decode("Café".toByteArray(Charsets.ISO_8859_1), "en")) // English falls back to 1252
    }

    @Test
    fun seasonZip() {
        val zip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                for ((name, text) in listOf(
                    "Show.S01E04.720p.x265.srt" to "four",
                    "__MACOSX/._Show.S01E05.720p.x265.srt" to "mac shadow",
                    "Show.S01E05.720p.x265.srt" to "five",
                    "Show.S01E05.nfo" to "not a subtitle",
                )) {
                    z.putNextEntry(ZipEntry(name))
                    z.write(text.toByteArray())
                }
            }
        }.toByteArray()
        assertEquals("Show.S01E05.720p.x265.srt" to "five", Subtitles.open(zip, "/subtitle/1-2.zip", 5, "en"))
        assertEquals("Show.S01E04.720p.x265.srt" to "four", Subtitles.open(zip, "/subtitle/1-2.zip", 4, "en"))
        // A film's zip, or an episode the names don't say: its first subtitle.
        assertEquals("Show.S01E04.720p.x265.srt" to "four", Subtitles.open(zip, "/subtitle/1-2.zip", 0, "en"))
        // Sent bare rather than zipped.
        assertEquals("film.srt" to "one", Subtitles.open("one".toByteArray(), "film.srt", 0, "en"))
    }
}
