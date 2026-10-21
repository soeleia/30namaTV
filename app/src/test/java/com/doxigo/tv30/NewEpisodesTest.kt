package com.doxigo.tv30

import com.doxigo.tv30.NewEpisodes.Followed
import com.doxigo.tv30.NewEpisodes.State
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NewEpisodesTest {
    /** Friday 23 October 2026, UTC midnight: the day 30nama names. */
    private val day = 1_792_713_600_000L
    private val hour = 3_600_000L
    private val week = 7 * 24 * hour

    /** Series 7, left at the credits of S1E7, its next episode on [day]. */
    private fun follow(day: Long?, watching: String = "1 7 2580", id: Int = 7) = Followed(id, day, watching)

    private fun look(state: State, vararg followed: Followed, at: Long) = NewEpisodes.look(state, followed.toList(), at)

    @Test
    fun outOnceItsGraceHasPassed() {
        val ahead = look(State(), follow(day), at = day - 48 * hour)
        assertEquals(day, ahead.ahead[7]?.day)
        assertTrue(ahead.out.isEmpty())
        // Eleven hours into the day, still within the half day it may take to reach 30nama, or a viewer's time zone.
        val early = look(ahead, follow(day), at = day + 11 * hour)
        assertTrue(early.out.isEmpty())
        val out = look(early, follow(day + week), at = day + 13 * hour)
        assertEquals(day, out.out[7]?.day)
        assertEquals(day + week, out.ahead[7]?.day) // and the next one is waited for
    }

    @Test
    fun waitsOutItsGraceThoughTheNextIsNamed() {
        val ahead = look(State(), follow(day), at = day - hour)
        val moved = look(ahead, follow(day + week), at = day + 2 * hour)
        assertEquals(day, moved.ahead[7]?.day)
        val out = look(moved, follow(day + week), at = day + 13 * hour)
        assertEquals(day, out.out[7]?.day)
        assertEquals(day + week, out.ahead[7]?.day)
    }

    @Test
    fun movedBeforeItCame() {
        val ahead = look(State(), follow(day), at = day - 48 * hour)
        val later = look(ahead, follow(day + 48 * hour), at = day - 24 * hour)
        assertEquals(day + 48 * hour, later.ahead[7]?.day)
        assertTrue(look(later, follow(day + 48 * hour), at = day + 13 * hour).out.isEmpty())
    }

    @Test
    fun aDayAlreadyPastIsNeverNews() {
        // The first look comes too late for it.
        val late = look(State(), follow(day), at = day + 13 * hour)
        assertTrue(late.ahead.isEmpty())
        assertTrue(late.out.isEmpty())
        // Told once, and 30nama still names the day: not waited for again, nor told twice once gone back to.
        val told = look(look(State(), follow(day), at = day - hour), follow(day), at = day + 13 * hour)
        assertEquals(setOf(7), told.out.keys)
        assertTrue(told.ahead.isEmpty())
        val back = look(told, follow(day, watching = "1 8 600"), at = day + 20 * hour)
        assertTrue(back.out.isEmpty())
        assertTrue(back.ahead.isEmpty())
    }

    @Test
    fun newsAsOfTheLookBeforeIt() {
        // Last week's episode watched on Tuesday: by Thursday's look the series stands at its credits.
        var state = look(State(), follow(day, watching = "1 6 1200"), at = day - 5 * 24 * hour)
        state = look(state, follow(day, watching = "1 7 2580"), at = day - 24 * hour)
        val now = day + 13 * hour
        state = look(state, follow(day + week, watching = "1 7 2580"), at = now)
        val news = state.out.getValue(7)
        assertTrue(NewEpisodes.isNew(news, "1 7 2580", now))
        assertFalse(NewEpisodes.isNew(news, "1 8 120", now)) // since watched, here or on another device
    }

    @Test
    fun watchedWithinItsGraceIsNotNews() {
        var state = look(State(), follow(day, watching = "1 7 2580"), at = day - hour)
        state = look(state, follow(day, watching = "1 8 2400"), at = day + 9 * hour) // there early, and seen
        state = look(state, follow(day + week, watching = "1 8 2400"), at = day + 13 * hour)
        assertTrue(state.out.isEmpty())
    }

    @Test
    fun aWeekAtMost() {
        val out = look(look(State(), follow(day), at = day - hour), follow(null), at = day + 13 * hour)
        val news = out.out.getValue(7)
        assertTrue(NewEpisodes.isNew(news, "1 7 2580", day + week - hour))
        assertFalse(NewEpisodes.isNew(news, "1 7 2580", day + week))
        assertTrue(look(out, follow(null), at = day + week).out.isEmpty())
    }

    @Test
    fun unfollowedIsForgotten() {
        val out = look(look(State(), follow(day), follow(day, id = 8), at = day - hour), follow(null), follow(day + week, id = 8), at = day + 13 * hour)
        assertEquals(setOf(7, 8), out.out.keys)
        val left = look(out, follow(null), at = day + 14 * hour)
        assertEquals(setOf(7), left.out.keys)
        assertTrue(left.ahead.isEmpty())
    }

    @Test
    fun theFirstOfASeriesCounts() {
        // Continue Watching's post comes first and says where the series stands; the notify list's, nowhere.
        val state = look(State(), follow(day), follow(day, watching = ""), at = day - hour)
        assertEquals("1 7 2580", state.ahead[7]?.watching)
    }

    @Test
    fun keptAcrossLaunches() {
        val state = look(look(State(), follow(day), follow(day + week, id = 8, watching = ""), at = day - hour), follow(day + week), follow(day + week, id = 8, watching = ""), at = day + 13 * hour)
        val back = NewEpisodes.decode(NewEpisodes.encode(state))
        assertEquals(day, back.out[7]?.day)
        assertEquals("1 7 2580", back.out[7]?.watching)
        assertEquals(day + week, back.ahead[8]?.day)
        assertEquals("", back.ahead[8]?.watching)
        assertTrue(NewEpisodes.decode("not json").out.isEmpty())
        assertTrue(NewEpisodes.decode(null).ahead.isEmpty())
    }

    @Test
    fun daysAndPoints() {
        val airing = JSONObject("""{"air":{"next_episode_caption":"قسمت بعدی","next_episode_date":{"g":"23 October 2026 Friday","j":"۱ آبان ۱۴۰۵ جمعه"}}}""")
        assertEquals(day, airing.nextEpisodeDay)
        assertNull(JSONObject("""{"air":{"next_episode_date":null}}""").nextEpisodeDay)
        assertNull(JSONObject("""{"air":{"next_episode_date":{"g":"soon"}}}""").nextEpisodeDay)
        // continue_watching's own entry (Reacher, S3E4 at 17:30).
        val watching = JSONObject("""{"id":283405,"data":{"display_time":"00:17:30","episode":4,"episode_title":"فصل 3 - قسمت 4","progress":"35.9836","season":3,"time":1050}}""")
        assertEquals("3 4 1050", watching.watchingAt)
        assertEquals("", JSONObject("""{"id":283405}""").watchingAt)
    }
}
