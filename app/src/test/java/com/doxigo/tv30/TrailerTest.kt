package com.doxigo.tv30

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Aparat search hits as they came back for «تریلر <name>» (October 2026). */
class TrailerTest {
    private fun trailer(hit: String, name: String, year: Int) = Trailer.isTrailer(hit, name.normalized(), year)

    @Test
    fun aparat() {
        assertTrue(trailer("تریلر فیلم Oppenheimer به نویسندگی و کارگردانی کریستوفر نولان", "Oppenheimer", 2023))
        assertTrue(trailer("تریلر فیلم Dune 2021 (تل‌ماسه)", "Dune", 2021))
        assertTrue(trailer("تریلر فیلم DUNE 2020 - تریلر فیلم اکشن جدید - DUNE Trailer 2020", "Dune", 2021)) // a year early: released then
        assertTrue(trailer("تریلر فیلم تل ماسه: بخش دوم Dune: Part Two", "Dune: Part Two", 2024))
        assertTrue(trailer("تریلر سریال هدف ویژه: شکار صدام High Value Target: The Hunt for Saddam", "High Value Target: The Hunt for Saddam", 2026))
        assertTrue(trailer("تريلر سریال Breaking Bad 2008-2013", "Breaking Bad", 2008)) // Arabic ي
        assertFalse(trailer("اولین تریلر Dune: Part Three منتشر شد | و اینک حماسه تلماسه", "Dune", 2021)) // a sequel
        assertFalse(trailer("تریلر فیلم Breaking Bad: El Camino - گیمر", "Breaking Bad", 2008)) // the film, not the series
        assertFalse(trailer("ماشا و خرس 2022 شاهزاده خانم یا اژدها؟ (تریلر) Masha and the Bear", "The Bear", 2022)) // inside another name
        assertFalse(trailer("تریلر بازی The Lost Bear", "The Bear", 2022)) // a game
        assertFalse(trailer("تریلر فیلم The Lion King 1994", "The Lion King", 2019)) // the other one
        assertFalse(trailer("سریال شوگان Shogun 2024 فصل 1 قسمت 6", "Shogun", 2024)) // an episode, not a trailer
        assertTrue(trailer("تریلر فیلم Animals به کارگردانی بن افلک", "Animals", 2026))
        assertFalse(trailer("تریلر فیلم American Animals 2018", "Animals", 2026))
        assertFalse(trailer("تریلر Party Animals", "Animals", 2026))
    }

    @Test
    fun aparatStreamsPreferAdaptivePlaybackAndKeepSignedQueries() {
        val hls = "https://www.aparat.com/external/chelsea/manifest.m3u8?q=signed%2Bquery&opt=token"
        val mp4 = "https://caspian26.cdn.asset.aparat.com/aparat-video/animals-720p.mp4?signature=value"
        assertEquals(hls, Trailer.aparatMediaUrl(hls, listOf(720 to mp4), null))
        assertEquals(mp4, Trailer.aparatMediaUrl(null, listOf(144 to mp4.replace("720p", "144p"), 720 to mp4), null))
        assertEquals(mp4, Trailer.aparatMediaUrl(null, emptyList(), mp4))
        assertNull(Trailer.aparatMediaUrl(null, emptyList(), null))
    }

    @Test
    fun aparatStreamsRejectUnsupportedAndOffSiteLinks() {
        assertNull(Trailer.aparatMediaUrl("javascript:play()", emptyList(), "http://www.aparat.com/movie.mp4"))
        assertNull(Trailer.aparatMediaUrl("https://aparat.com.evil.example/manifest.m3u8", emptyList(), "https://www.aparat.com/watch"))
        val fallback = "https://caspian27.cdn.asset.aparat.com/aparat-video/animals-1080p.apt?signature=value"
        assertEquals(fallback, Trailer.aparatMediaUrl(null, listOf(1080 to "file:///video.mp4"), fallback))
    }
}
