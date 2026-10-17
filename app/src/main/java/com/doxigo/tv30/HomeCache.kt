package com.doxigo.tv30

import java.io.File

/**
 * What home was last drawn from (home_stream, the menu, Continue Watching, My List and the ranked
 * most-favourited rows), kept in filesDir so the next launch draws home at once while the fresh
 * answers are on their way (HomeFragment.load). Each answer is a file whose first line says whose
 * home it is and in which language; anything else there, or no file at all, is a miss.
 */
object HomeCache {
    const val HOME = "home"
    const val MENU = "menu"
    const val WATCHING = "continue"
    const val WATCHLIST = "watchlist"

    /** A ranked row's archive page: one per catalogue slug. */
    fun loved(slug: String) = "loved-$slug"

    /** Raised when what a file holds changes shape, so an older build's files read as misses. */
    private const val FORMAT = 1

    /**
     * Whose home, in which language: the account, or any guest. Signed in without knowing who
     * (the user call has never answered) is no one's, and nothing is kept.
     */
    fun key(loggedIn: Boolean, userId: String?, language: String): String? = when {
        !loggedIn -> "$FORMAT guest $language"
        userId.isNullOrBlank() -> null
        else -> "$FORMAT user $userId $language"
    }

    fun read(dir: File, key: String, part: String): String? = runCatching {
        dir.resolve("$part.json").bufferedReader().use { file -> if (file.readLine() == key) file.readText() else null }
    }.getOrNull()

    /** Whole or not at all: a write cut short (the TV switched off) leaves the last whole one in place. */
    fun write(dir: File, key: String, part: String, json: String) {
        runCatching {
            dir.mkdirs()
            val next = dir.resolve("$part.tmp")
            next.writeText("$key\n$json")
            next.renameTo(dir.resolve("$part.json"))
        }
    }
}
