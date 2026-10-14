package com.doxigo.tv30

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HomeCacheTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun keptForTheSameAccountAndLanguageOnly() {
        val dir = folder.root.resolve("home") // the first write makes it
        val mine = HomeCache.key(true, "42", "fa")!!
        val home = """{"movies":{"posts":[{"id":1,"title":{"english":"Line\nbreak"}}]}}"""
        HomeCache.write(dir, mine, HomeCache.HOME, home)
        assertEquals(home, HomeCache.read(dir, mine, HomeCache.HOME))
        assertNull(HomeCache.read(dir, mine, HomeCache.MENU)) // never written
        assertNull(HomeCache.read(dir, HomeCache.key(true, "43", "fa")!!, HomeCache.HOME)) // another account
        assertNull(HomeCache.read(dir, HomeCache.key(true, "42", "en")!!, HomeCache.HOME)) // the other language
        assertNull(HomeCache.read(dir, HomeCache.key(false, null, "fa")!!, HomeCache.HOME)) // a guest
        assertNull(HomeCache.key(true, null, "fa")) // signed in as no one known: nothing is kept
        assertNull(HomeCache.key(true, " ", "fa"))
        assertNotEquals(HomeCache.key(false, null, "fa"), HomeCache.key(true, "guest", "fa"))
        assertEquals(HomeCache.key(false, null, "fa"), HomeCache.key(false, "42", "fa")) // every guest is one

        // The next write replaces it, whoever's it is.
        val guest = HomeCache.key(false, null, "fa")!!
        HomeCache.write(dir, guest, HomeCache.HOME, "{}")
        assertEquals("{}", HomeCache.read(dir, guest, HomeCache.HOME))
        assertNull(HomeCache.read(dir, mine, HomeCache.HOME))
    }

    @Test
    fun brokenFilesAreMisses() {
        val dir = folder.newFolder("home")
        val key = HomeCache.key(false, null, "en")!!
        val file = dir.resolve("${HomeCache.HOME}.json")
        file.writeBytes(byteArrayOf(0, -1, 10, 127, -128, 10, 123, 125)) // garbage
        assertNull(HomeCache.read(dir, key, HomeCache.HOME))
        file.writeText("")
        assertNull(HomeCache.read(dir, key, HomeCache.HOME))
        file.writeText("0 guest en\n{}") // an older build's
        assertNull(HomeCache.read(dir, key, HomeCache.HOME))
        file.writeText("{}") // no key line at all
        assertNull(HomeCache.read(dir, key, HomeCache.HOME))

        // Nowhere to write (a file where the folder should be): quietly nothing.
        val blocked = folder.newFile("blocked")
        HomeCache.write(blocked, key, HomeCache.HOME, "{}")
        assertNull(HomeCache.read(blocked, key, HomeCache.HOME))
    }
}
