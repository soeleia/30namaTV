package com.doxigo.tv30

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    // SubSource, from answers recorded live in October 2026 (test resources, subsource/), trimmed
    // to the fields read; single_subtitle.json is a real `single`'s subtitle list.

    private fun recorded(name: String) = javaClass.getResource("/subsource/$name")!!.readText()

    @Test
    fun subSourceFrom30nama() {
        val pages = JSONArray(recorded("single_subtitle.json"))
        // The season's listing, by whichever language's link; a season it doesn't link, none.
        assertEquals("dark-matter-2024/season-2", Subtitles.subSourcePage(pages, Subtitles.Wanted(null, "Dark Matter", 2024, 2, 8)))
        assertNull(Subtitles.subSourcePage(pages, Subtitles.Wanted(null, "Dark Matter", 2024, 3, 1)))
        // A film's link has no season; the site once wrote a season "season=3".
        val film = JSONArray().put(JSONObject().put("link", "https://subsource.net/subtitles/hush-2016/farsi_persian").put("season", JSONObject.NULL))
        assertEquals("hush-2016", Subtitles.subSourcePage(film, Subtitles.Wanted(null, "Hush", 2016, 0, 0)))
        val old = JSONArray().put(JSONObject().put("link", "https://subsource.net/subtitles/x/season=3/farsi_persian").put("season", 3))
        assertEquals("x/season-3", Subtitles.subSourcePage(old, Subtitles.Wanted(null, "X", null, 3, 1)))
        assertNull(Subtitles.subSourcePage(null, Subtitles.Wanted(null, "Hush", 2016, 0, 0)))
    }

    @Test
    fun subSourceSearch() {
        // By name: the same kind of title, named the same, its year within one.
        val byName = JSONObject(recorded("search_name.json"))
        assertEquals("dark-matter-2024/season-2", Subtitles.subSourcePath(byName, Subtitles.Wanted(null, "Dark Matter", 2024, 2, 8), byImdb = false))
        assertEquals("dark-matter/season-3", Subtitles.subSourcePath(byName, Subtitles.Wanted(null, "Dark Matter", 2015, 3, 1), byImdb = false))
        assertEquals("dark-matter-2007", Subtitles.subSourcePath(byName, Subtitles.Wanted(null, "Dark Matter", 2008, 0, 0), byImdb = false))
        assertNull("a film of another year", Subtitles.subSourcePath(byName, Subtitles.Wanted(null, "Dark Matter", 2019, 0, 0), byImdb = false))
        assertNull("a season it has no listing for", Subtitles.subSourcePath(byName, Subtitles.Wanted(null, "Dark Matter", 2024, 4, 1), byImdb = false))
        // By IMDb id SubSource answers with that title alone.
        val byImdb = JSONObject(recorded("search_imdb.json"))
        assertEquals("dark-matter-2024/season-1", Subtitles.subSourcePath(byImdb, Subtitles.Wanted("tt19231492", "Dark Matter", null, 1, 3), byImdb = true))
        assertNull("a series is no film", Subtitles.subSourcePath(byImdb, Subtitles.Wanted("tt19231492", "Dark Matter", null, 0, 0), byImdb = true))
    }

    @Test
    fun subSourceSeason() {
        // A season's listing, for its fifth episode in 1080p: the episode's own subtitles first, its
        // packs ("Season1", "S01") after, the fourth's gone; each once, under its release that fits best.
        val wanted = Subtitles.Wanted("tt19231492", "Dark Matter", 2024, 1, 5, release = "1080p")
        val found = Subtitles.ranked(Subtitles.subSourceFound(JSONObject(recorded("subtitles_season.json")), wanted), wanted)
        assertEquals(
            listOf(
                "en dark.matter.2024.s01e05.1080p.web.h264-successfulcrab",
                "fa Dark.Matter.2024.S01E05.720p/1080p.10bit.WEBRip.x265.HEVC-PSA",
                "en Dark.Matter.2024.S01E05.ATVP.WEB.H264-SuccessfulCrab HI",
                "fa Dark.Matter.Season1.720p/1080p.10bit.WEBRip.x265.HEVC-PSA",
                "en Dark.Matter.2024.S01.ATVP.WEB-DL.DDP5.1.H.264-NTb",
            ),
            found.map { listOfNotNull(it.language, it.release, "HI".takeIf { _ -> it.hearingImpaired }).joinToString(" ") },
        )
        assertEquals("dark-matter-season-1-2024/farsi_persian/10006286", found[1].ref)
        assertEquals(Subtitles.SUBSOURCE, found[1].source)
    }

    @Test
    fun subSourceFilm() {
        val wanted = Subtitles.Wanted("tt5022702", "Hush", 2016, 0, 0, release = "1080p")
        val found = Subtitles.subSourceFound(JSONObject(recorded("subtitles_film.json")), wanted)
        assertEquals(10, found.size) // 16 rows, one subtitle in seven of them
        assertEquals("Hush.2016.1080p.BluRay.x264.[YTS.MX]", found.first().release)
        assertEquals(listOf("10065050"), found.filter { it.hearingImpaired }.map { it.ref.substringAfterLast('/') }) // 1 is, 0 and null aren't
        assertEquals("Hush.2016.WEBRip.XviD.MP3-RARBG", found.first { it.language == "fa" }.release) // its trailing tab gone
    }

    @Test
    fun episodesNamed() {
        val fifth = Subtitles.Wanted(null, "Show", null, 2, 5)
        for (fits in listOf("Show.S02E05.720p", "show s02 e05", "Show.2x05.HDTV", "Show.S02E01-E10.WEB", "Show.S02E01-10", "Show - Season 2 ~ Eps 05", "Show.E05.1080p")) {
            assertEquals(fits, 100, Subtitles.score(fits, fifth))
        }
        for (other in listOf("Show.S02E04.720p", "Show.S01E05.720p", "Show - Season 2 ~ Eps 07. The Pyramid", "Show.S02E06-E10")) {
            assertNull(other, Subtitles.score(other, fifth))
        }
        // A season's pack, or a codec's number: nothing said of the episode.
        for (pack in listOf("Show.Season2.WEB", "Show.S02.1080p.x265-PSA", "Show.S02 720p.10bit.WEBRip.2CH.X265", "Show.H.264-NTb")) {
            assertEquals(pack, 20, Subtitles.score(pack, fifth))
        }
    }

    @Test
    fun ranking() {
        // After the Mac client's own test: this episode first, by source; a pack after; another episode gone.
        fun found(source: String, release: String) = Subtitles.Found(source, "fa", release, 0, false, release)
        val list = listOf(
            found(Subtitles.SUBDL, "Show.S01E05.WEB"),
            found(Subtitles.OPENSUBTITLES, "Show.S01E02.HDTV"),
            found(Subtitles.SUBSOURCE, "Show.S01.WEB"),
            found(Subtitles.SUBDL, "Show.S01E02.WEB-DL"),
        )
        assertEquals(
            listOf("Show.S01E02.WEB-DL", "Show.S01E02.HDTV", "Show.S01.WEB"),
            Subtitles.ranked(list, Subtitles.Wanted(null, "Show", null, 1, 2, release = "Show.S01E02.1080p.WEB-DL")).map { it.release },
        )
        // A film: the source over the resolution, the resolution and the group alike; ties as they came.
        val films = listOf(found(Subtitles.SUBDL, "Film.2016.1080p.WEBRip-AAA"), found(Subtitles.SUBSOURCE, "Film.2016.720p.BRRip-BBB"),
            found(Subtitles.OPENSUBTITLES, "Film.2016.1080p.BluRay-CCC"), found(Subtitles.SUBDL, "Film.2016.720p.HDTV-DDD"), found(Subtitles.SUBDL, "Film.2016.480p.HDTV-CCC"))
        assertEquals(
            listOf("Film.2016.1080p.BluRay-CCC", "Film.2016.720p.BRRip-BBB", "Film.2016.1080p.WEBRip-AAA", "Film.2016.480p.HDTV-CCC", "Film.2016.720p.HDTV-DDD"),
            Subtitles.ranked(films, Subtitles.Wanted(null, "Film", 2016, 0, 0, release = "1080p.Blu-ray-CCC")).map { it.release },
        )
    }
}
