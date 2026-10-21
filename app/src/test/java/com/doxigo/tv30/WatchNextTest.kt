package com.doxigo.tv30

import android.media.tv.TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE
import android.media.tv.TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_NEW
import android.media.tv.TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT
import com.doxigo.tv30.WatchNext.Fate
import com.doxigo.tv30.WatchNext.Row
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class WatchNextTest {
    private fun row(key: String?, browsable: Boolean = true, type: Int = WATCH_NEXT_TYPE_NEXT) = Row(1, "7", key, type, browsable)

    private fun fate(row: Row, key: String?, known: Boolean = true, following: Boolean = true) = WatchNext.fate(row, key, known, following)

    @Test
    fun aRowTakenOffStaysOffWhileItsEntryIsTheSame() {
        val off = row("file b", browsable = false)
        assertEquals(Fate.UPDATE, fate(off, "file b"))
        // Another entry: a new row, shown.
        assertEquals(Fate.DELETE, fate(off, "continue 1 8 600"))
        assertEquals(Fate.UPDATE, fate(row("file b"), "continue 1 8 600"))
        assertEquals(Fate.DELETE, fate(row("file b"), null)) // no longer watched nor news
    }

    @Test
    fun aFailedLookupLeavesTheRowAsItIs() {
        // `stream` did not answer: its stand-in, where the title was left, must not bring a row taken off back.
        assertEquals(Fate.STAND, fate(row("file b", browsable = false), "continue 1 7 2580", known = false))
        assertEquals(Fate.STAND, fate(row("file b"), "continue 1 7 2580", known = false))
    }

    @Test
    fun newsWaitsForTheNotifyList() {
        val news = row("new 1792713600000", type = WATCH_NEXT_TYPE_NEW)
        assertEquals(Fate.STAND, fate(news, null, following = false))
        assertEquals(Fate.DELETE, fate(news, null, following = true))
        // A title gone from the row is not news: it goes at once.
        assertEquals(Fate.DELETE, fate(row("continue 1 7 2580", type = WATCH_NEXT_TYPE_CONTINUE), null, following = false))
    }

    @Test
    fun newOnlyForTheEpisodeJustOut() {
        val files = listOf("s1e3", "s1e4", "s2e8", "s2e9").map { JSONObject().put("info", JSONObject().put("file_id", it)) }
        assertEquals(WATCH_NEXT_TYPE_NEW, WatchNext.nextType(files[3], files, news = true))
        // Catching up on season 1 while S2E9 aired: S1E4 is next, not new.
        assertEquals(WATCH_NEXT_TYPE_NEXT, WatchNext.nextType(files[1], files, news = true))
        assertEquals(WATCH_NEXT_TYPE_NEXT, WatchNext.nextType(files[3], files, news = false))
    }
}
