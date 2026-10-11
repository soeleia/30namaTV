package com.doxigo.tv30

import org.json.JSONObject

// ---- Up Next: where Play goes for a title already started, Apple TV's way. ----

/**
 * Where to start, given each file's credits mark ([ends], in seconds; null for none) and the saved
 * point. Saved inside the credits, the next file plays from its start, and with none after it (a
 * film, a finale) the same one starts over, as Netflix restarts a finished title; anywhere else, the
 * saved point stands. The player and the title page's Play both go by it, so they agree.
 */
fun upNext(ends: List<Long?>, resumeIndex: Int, resumeSeconds: Long): Pair<Int, Long> {
    val end = ends.getOrNull(resumeIndex)
    if (end == null || resumeSeconds < end) return resumeIndex to resumeSeconds
    return if (resumeIndex + 1 < ends.size) resumeIndex + 1 to 0L else resumeIndex to 0L
}

/** "HH:MM:SS" (or "MM:SS") as seconds, the way the `stream` answer writes its marks. */
fun seconds(hms: String): Long? {
    val parts = hms.split(':').map { it.trim().toLongOrNull() ?: return null }
    return parts.fold(0L) { total, part -> total * 60 + part }
}

/** Where a file's credits start (`options.end`); a 0 mark is no mark. */
val JSONObject.endMark get() = str("options", "end")?.let(::seconds)?.takeIf { it > 0 }
