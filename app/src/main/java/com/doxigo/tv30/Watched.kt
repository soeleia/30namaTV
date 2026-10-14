package com.doxigo.tv30

import android.content.Context
import org.json.JSONObject

// ---- Watched: which episodes this TV has seen, for their tiles. ----

/**
 * 30nama keeps one point per title (`continue_watching`: the last file and how far in it got), and
 * its `stream` answer says nothing of the others, so which episodes were seen is kept here, by file
 * id: the furthest point reached, the length, and whether it was seen through, past its credits mark
 * (95% of it without one). The player writes it as it reports progress, and wherever a `stream`
 * answer brings the server's point it is folded in ([seed]), so an episode finished on another
 * device counts too. The last [CAP] files, in app-private preferences; a debug build's read-only
 * switch (Api.call) is for the account, and this is not it.
 */
object Watched {
    const val CAP = 2000
    private const val KEY = "files"
    /** Less than a minute in is a stray press, not a start: no bar for it. */
    private const val STARTED = 60L

    class Entry(val furthest: Long, val duration: Long, val done: Boolean) {
        /** The tile's bar: full once seen, else how far in, from a minute in and with a known length; null for none. */
        val progress: Float? get() = when {
            done -> 1f
            duration > 0 && furthest >= STARTED -> furthest.toFloat() / duration
            else -> null
        }

        /** Whole minutes after the furthest point, rounded up, for a started one ([progress] short of full). */
        val minutesLeft: Int? get() = if (done || progress == null) null else maxOf(1, ((duration - furthest + 59) / 60).toInt())
    }

    /** Where a file counts as seen: its credits mark ([end]) unless that lies past its [duration], else 95% of it; null knowing neither. */
    fun line(end: Long?, duration: Long): Long? =
        end?.takeIf { duration <= 0 || it < duration } ?: (duration * 95 / 100).takeIf { it > 0 }

    /** [old] once a viewing reached [at] seconds of [duration] (0: not known, the old one kept), [end] its credits mark. Seen stays seen. */
    fun merge(old: Entry?, at: Long, duration: Long, end: Long?): Entry {
        val furthest = maxOf(old?.furthest ?: 0, at)
        val length = duration.takeIf { it > 0 } ?: old?.duration ?: 0
        return Entry(furthest, length, old?.done == true || line(end, length)?.let { furthest >= it } == true)
    }

    /** [entry] as [id]'s, now the newest, the oldest past [CAP] dropped. */
    fun put(history: LinkedHashMap<String, Entry>, id: String, entry: Entry) {
        history.remove(id)
        history[id] = entry
        while (history.size > CAP) history.remove(history.keys.first())
    }

    /** A line a file, oldest first: "id furthest duration 1" (0 not seen). */
    fun encode(history: Map<String, Entry>) =
        history.entries.joinToString("\n") { (id, e) -> "$id ${e.furthest} ${e.duration} ${if (e.done) 1 else 0}" }

    fun decode(text: String?): LinkedHashMap<String, Entry> = LinkedHashMap<String, Entry>().apply {
        for (line in text.orEmpty().lines()) {
            val (id, furthest, duration, done) = line.split(' ').takeIf { it.size == 4 } ?: continue
            put(id, Entry(furthest.toLongOrNull() ?: 0, duration.toLongOrNull() ?: 0, done == "1"))
        }
    }

    /** Read once, then kept: every tile asks. Main thread only. */
    private var history: LinkedHashMap<String, Entry>? = null

    private fun prefs(context: Context) = context.getSharedPreferences("watched", Context.MODE_PRIVATE)

    private fun history(context: Context) = history ?: decode(prefs(context).getString(KEY, null)).also { history = it }

    fun of(context: Context, file: JSONObject): Entry? = file.str("info", "file_id")?.let { history(context)[it] }

    /** Forgets everything: the history is the signed-in account's, kept on this TV. */
    fun clear(context: Context) {
        history = LinkedHashMap()
        prefs(context).edit().clear().apply()
    }

    /** The player at [at] seconds into [file], [duration] long. */
    fun record(context: Context, file: JSONObject, at: Long, duration: Long) {
        val id = file.str("info", "file_id") ?: return
        val all = history(context)
        put(all, id, merge(all[id], at, duration, file.endMark))
        prefs(context).edit().putString(KEY, encode(all)).apply()
    }

    /**
     * The server's point, a `stream` answer's [saved] `continue_watching`, folded in: its file, how far
     * in, and the length its `progress` (%) implies when this TV has none of its own.
     */
    fun seed(context: Context, files: List<JSONObject>, saved: JSONObject?) {
        val id = saved?.str("file_id") ?: return
        val file = files.firstOrNull { it.str("info", "file_id") == id } ?: return
        val at = saved.optLong("time").takeIf { it > 0 } ?: return
        val progress = saved.optDouble("progress") // NaN without it
        val length = of(context, file)?.duration?.takeIf { it > 0 } ?: if (progress > 0) (at * 100 / progress).toLong() else 0
        record(context, file, at, length)
    }

    /** Play's file and second ([upNext]) by this history: where each file counts as seen, and which were. */
    fun upNext(context: Context, files: List<JSONObject>, index: Int, seconds: Long) =
        upNext(files.map { line(it.endMark, of(context, it)?.duration ?: 0) }, index, seconds) { of(context, files[it])?.done == true }
}
