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
    fun persianLetters() {
        // Persian (پ, گ): every Arabic ي, ى and ك goes, in cues with no Persian-only letter too,
        // and a yeh before a ZWNJ.
        assertEquals(
            listOf("پیشنهاد کردی؟", "نمی\u200Cدونی کی میاد", "یک موسی"),
            Subtitles.cleaned(listOf("پيشنهاد كردي؟", "نمي\u200Cدوني كي مياد", "يك موسى")),
        )
        // Arabic, Persian-only letters nowhere: left as it is.
        assertEquals(listOf("كيف حالك؟", "إلى اللقاء"), Subtitles.cleaned(listOf("كيف حالك؟", "إلى اللقاء")))
        assertEquals("كيف حالك", Subtitles.decode("كيف حالك".toByteArray(), "fa"))
        assertEquals("گفتی که", Subtitles.persianised("گفتي كه"))
    }

    @Test
    fun leftToRightAuthored() {
        // Typed for a player that lays every line out left to right: the closing marks lead.
        val file = listOf(".سلام، حالت چطوره", "!یه لحظه صبر کن\n...پس کجا رفتی", "OK", "?NASA کجاست")
        assertEquals(
            listOf("\u2066.سلام، حالت چطوره\u2069", "\u2066!یه لحظه صبر کن\u2069\n\u2066...پس کجا رفتی\u2069", "\u2066OK\u2069", "\u2066?NASA کجاست\u2069"),
            Subtitles.cleaned(file),
        )
    }

    @Test
    fun rightToLeftAuthored() {
        // Written right: closing marks close, a continuation's leading «...» included, so nothing changes.
        val file = listOf("سلام، حالت چطوره.", "یه لحظه صبر کن!\nپس کجا رفتی...", "...که گفتم نه.", "\u202Bنسخه 1080p آماده است؟\u202C")
        assertEquals(file, Subtitles.cleaned(file))
    }

    @Test
    fun mixedFile() {
        // Mostly written right, one line typed left to right: that line alone is held. An ellipsis
        // either side, or a stop at both ends, says nothing of its own and goes with the file.
        assertEquals(
            listOf("سلام.", "صبر کن!", "کجا؟ نمی\u200Cدونم.", "\u2066.باشه\u2069", "...که گفتم", "!نه!"),
            Subtitles.cleaned(listOf("سلام.", "صبر کن!", "کجا؟ نمی\u200Cدونم.", ".باشه", "...که گفتم", "!نه!")),
        )
        // Mostly typed left to right, one line written right: that line is left as it is.
        assertEquals(
            listOf("\u2066.سلام\u2069", "\u2066!صبر کن\u2069", "باشه.", "\u2066...که گفتم\u2069"),
            Subtitles.cleaned(listOf(".سلام", "!صبر کن", "باشه.", "...که گفتم")),
        )
    }

    @Test
    fun latinLines() {
        // In a Persian file a Latin-only line is held left to right, its full stop after it; a line
        // of digits and marks alone takes its side from the line, and is left as it is.
        assertEquals(
            listOf("سلام!", "\u2066Hello, world.\u2069", "یه آهنگ:\n\u2066Yesterday (1965)\u2069", "\u2066OK.\u2069", "2026...", "- ۱۰۸۰"),
            Subtitles.cleaned(listOf("سلام!", "Hello, world.", "یه آهنگ:\nYesterday (1965)", "OK.", "2026...", "- ۱۰۸۰")),
        )
        // An English file: unchanged.
        val english = listOf("Hello, world.", "OK.\n- Fine!", "2026...")
        assertEquals(english, Subtitles.cleaned(english))
    }

    @Test
    fun arabicDigits() {
        // An Arabic keyboard's ٠–٩ (٤ ٥ ٦ drawn otherwise) as Persian ۰–۹, where the file is Persian.
        assertEquals(listOf("ساعت ۴:۵۶ گفت", "۰۱۲۳۴۵۶۷۸۹"), Subtitles.cleaned(listOf("ساعت ٤:٥٦ گفت", "٠١٢٣٤٥٦٧٨٩")))
        assertEquals("گفت ۱۴۰۵", Subtitles.persianised("گفت ١٤٠٥"))
        // Arabic, Persian-only letters nowhere: its digits stay.
        assertEquals(listOf("الساعة ٤:٥٦"), Subtitles.cleaned(listOf("الساعة ٤:٥٦")))
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
