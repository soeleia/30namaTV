package com.doxigo.tv30

import org.json.JSONObject

// ---- Up Next: where Play goes for a title already started, Apple TV's way. ----

/**
 * Where to start, given where each file counts as finished ([ends], in seconds: its credits mark, or
 * Watched.line; null for neither) and the saved point. Saved past it, the next file not yet [seen]
 * plays from its start (an old episode watched again leads back to where the series was left, else
 * simply the next), and with none after it (a film, a finale) the same one starts over, as Netflix
 * restarts a finished title; anywhere else, the saved point stands. The player and the title page's
 * Play both go by it, so they agree.
 */
fun upNext(ends: List<Long?>, resumeIndex: Int, resumeSeconds: Long, seen: (Int) -> Boolean = { false }): Pair<Int, Long> {
    val end = ends.getOrNull(resumeIndex)
    if (end == null || resumeSeconds < end) return resumeIndex to resumeSeconds
    val next = (resumeIndex + 1 until ends.size).firstOrNull { !seen(it) } ?: (resumeIndex + 1)
    return if (next < ends.size) next to 0L else resumeIndex to 0L
}

/**
 * The file a title left at [point] (an index into its files and a second, Watched.resumePoint) goes
 * on to: [upNext]'s, when that is another file. Null when the point stands, or starts over (a film,
 * a finale): only a finished episode with one after it has a next. The launcher's Play Next row
 * offers it, so the row says what Play then plays.
 */
fun nextFile(point: Pair<Int, Long>?, ends: List<Long?>, seen: (Int) -> Boolean = { false }): Int? {
    val (index, seconds) = point ?: return null
    return upNext(ends, index, seconds, seen).first.takeIf { it != index }
}

/** "HH:MM:SS" (or "MM:SS") as seconds, the way the `stream` answer writes its marks. */
fun seconds(hms: String): Long? {
    val parts = hms.split(':').map { it.trim().toLongOrNull() ?: return null }
    return parts.fold(0L) { total, part -> total * 60 + part }
}

/** Where a file's credits start (`options.end`); a 0 mark is no mark. */
val JSONObject.endMark get() = str("options", "end")?.let(::seconds)?.takeIf { it > 0 }
