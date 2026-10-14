package com.doxigo.tv30

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchedTest {
    @Test
    fun lines() {
        assertEquals(2_580L, Watched.line(2_580, 2_700)) // the credits mark
        assertEquals(2_565L, Watched.line(null, 2_700)) // 95% without one
        assertEquals(2_565L, Watched.line(2_800, 2_700)) // a mark past the end is no mark
        assertEquals(2_580L, Watched.line(2_580, 0)) // the mark alone, before the length is known
        assertNull(Watched.line(null, 0))
    }

    @Test
    fun seenPastTheLine() {
        assertFalse(Watched.merge(null, 2_564, 2_700, null).done)
        assertTrue(Watched.merge(null, 2_565, 2_700, null).done)
        // The credits mark beats 95%: an 85% mark is seen at 86%, where 95% would not be.
        assertTrue(Watched.merge(null, 2_330, 2_700, 2_300).done)
        assertFalse(Watched.merge(null, 2_330, 2_700, null).done)
        assertFalse(Watched.merge(null, 2_299, 2_700, 2_300).done)
    }

    @Test
    fun furthestAndSeenStay() {
        val seen = Watched.merge(null, 2_650, 2_700, 2_580)
        val again = Watched.merge(seen, 300, 2_700, 2_580) // watched again from the start
        assertTrue(again.done)
        assertEquals(2_650L, again.furthest)
        assertEquals(1f, again.progress)
        assertNull(again.minutesLeft)
    }

    @Test
    fun serverPointFoldedIn() {
        // The server's point carries no length when its progress is missing: the one known is kept.
        val local = Watched.merge(null, 600, 2_700, 2_580)
        val server = Watched.merge(local, 1_200, 0, 2_580)
        assertEquals(1_200L, server.furthest)
        assertEquals(2_700L, server.duration)
        assertFalse(server.done)
        // Finished on another device: past the mark, with no length at all.
        assertTrue(Watched.merge(null, 2_600, 0, 2_580).done)
        // An earlier point from the server doesn't pull this TV's back.
        assertEquals(1_200L, Watched.merge(server, 900, 0, 2_580).furthest)
    }

    @Test
    fun barAndMinutesLeft() {
        assertNull(Watched.merge(null, 59, 2_700, null).progress) // a stray press
        assertNull(Watched.merge(null, 600, 0, null).progress) // no length to measure by
        val started = Watched.merge(null, 1_350, 2_700, 2_580)
        assertEquals(0.5f, started.progress)
        assertEquals(23, started.minutesLeft) // 1,350 s, rounded up
    }

    @Test
    fun cappedOldestOut() {
        val history = LinkedHashMap<String, Watched.Entry>()
        repeat(Watched.CAP) { Watched.put(history, "f$it", Watched.Entry(it.toLong(), 100, false)) }
        Watched.put(history, "f0", Watched.Entry(1, 100, false)) // again: now the newest
        Watched.put(history, "new", Watched.Entry(1, 100, true))
        assertEquals(Watched.CAP, history.size)
        assertFalse("f1" in history)
        assertEquals(listOf("f0", "new"), history.keys.toList().takeLast(2))
    }

    @Test
    fun storedAndRead() {
        val history = LinkedHashMap<String, Watched.Entry>()
        Watched.put(history, "136993", Watched.Entry(1_980, 2_480, false))
        Watched.put(history, "e1", Watched.Entry(2_650, 2_700, true))
        val read = Watched.decode(Watched.encode(history) + "\nbroken line\n")
        assertEquals(listOf("136993", "e1"), read.keys.toList())
        assertEquals(1_980L, read.getValue("136993").furthest)
        assertTrue(read.getValue("e1").done)
        assertTrue(Watched.decode(null).isEmpty())
    }

    @Test
    fun upNextPastSeenEpisodes() {
        val ends = listOf(2_580L, 2_640L, 2_700L, 2_760L)
        // E2 watched again to its credits, E3 already seen: Play goes on to E4.
        assertEquals(3 to 0L, upNext(ends, 1, 2_650) { it == 2 })
        // Everything after it seen: simply the next.
        assertEquals(2 to 0L, upNext(ends, 1, 2_650) { true })
        // Mid-episode, seen or not, it resumes.
        assertEquals(1 to 600L, upNext(ends, 1, 600) { true })
        // The finale starts over.
        assertEquals(3 to 0L, upNext(ends, 3, 2_800) { false })
    }
}
