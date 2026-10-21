package com.doxigo.tv30

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvContract.WatchNextPrograms
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Continue Watching mirrored onto the launcher's Play Next row, the Android TV take on Apple's
 * Top Shelf: a title resumes from the home screen without opening the app first. A series whose
 * episode was seen through offers the one after it instead (WATCH_NEXT_TYPE_NEXT), and a followed
 * series with an episode out that its viewer has not gone back to (NewEpisodes) says so
 * (WATCH_NEXT_TYPE_NEW). The platform TvProvider (Android 8+) only; some TVs ship without one, so
 * every failure is ignored. The provider scopes every query, update and delete to our own rows and
 * refuses a selection, hence whole-table reads and per-row URIs.
 */
object WatchNext {
    /** The launcher's way back in: MainActivity opens the player on this title over home. */
    const val EXTRA_PLAY = "play"
    const val EXTRA_FREE = "free"
    const val EXTRA_TITLE = "title"
    /** The file to play, when the row names one: a next or new episode. */
    const val EXTRA_FILE = "file"
    /** A title page to open over home instead: a followed series not being watched, with an episode out. */
    const val EXTRA_DETAILS = "details"

    /** Loads overlap (start, return from the player, a dismiss): one sync at a time, or a title lands twice. */
    private val lock = Mutex()

    /**
     * A title's row: [key] tells this entry from the next one the title gets (see [sync]). Not [known]
     * when its next could not be looked up: a row the title has stands, and this is only for one without.
     */
    private class Program(val key: String, val values: ContentValues, val known: Boolean = true)

    /** One of our rows: its _ID, the title it is for, its entry's [key], its type and whether the launcher shows it. */
    internal class Row(val id: Long, val title: String, val key: String?, val type: Int, val browsable: Boolean)

    internal enum class Fate { STAND, UPDATE, DELETE }

    /**
     * Makes the row match [watching], the server's continue_watching in its order, then the series of
     * [following] (the notify list, null until it is in) with an episode out. One row a title. A row
     * the viewer took off the launcher (COLUMN_BROWSABLE 0) stays off while the title's entry is the
     * same one: back only once there is another to offer, a later point or another episode; a new
     * episode taken off is no longer news in the app either.
     */
    suspend fun sync(context: Context, watching: List<JSONObject>, following: List<JSONObject>?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        lock.withLock {
            runCatching {
                val resolver = context.contentResolver
                val rows = withContext(Dispatchers.IO) {
                    val columns = arrayOf(WatchNextPrograms._ID, WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID, WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_DATA,
                        WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE, WatchNextPrograms.COLUMN_BROWSABLE)
                    resolver.query(WatchNextPrograms.CONTENT_URI, columns, null, null, null)?.use {
                        generateSequence { if (it.moveToNext()) Row(it.getLong(0), it.getString(1).orEmpty(), it.getBlob(2)?.decodeToString(), it.getInt(3), it.getInt(4) != 0) else null }.toList()
                    }.orEmpty()
                }
                for (row in rows) if (!row.browsable && row.type == WatchNextPrograms.WATCH_NEXT_TYPE_NEW) row.title.toIntOrNull()?.let { NewEpisodes.dismiss(context, it) }
                val wanted = programs(context, watching, following)
                withContext(Dispatchers.IO) {
                    val kept = HashSet<String>()
                    for (row in rows) {
                        val program = wanted[row.title]
                        val uri = TvContract.buildWatchNextProgramUri(row.id)
                        // A title's second row is a stray: only the first is kept.
                        when (if (row.title in kept) Fate.DELETE else fate(row, program?.key, program?.known != false, following != null)) {
                            Fate.STAND -> kept += row.title
                            Fate.UPDATE -> {
                                kept += row.title
                                program?.let { resolver.update(uri, it.values, null, null) }
                            }
                            Fate.DELETE -> resolver.delete(uri, null, null)
                        }
                    }
                    for ((title, program) in wanted) if (title !in kept) resolver.insert(WatchNextPrograms.CONTENT_URI, program.values)
                }
            }
        }
    }

    /**
     * What [sync] does with [row], its title's first: the title's wanted entry is [key] (null for
     * none), not [known] when its next could not be looked up, and [following] false before the
     * notify list is in. Not known, it stands as it is: a failed lookup says nothing of the entry,
     * so a row taken off is not put back, and a followed series' news waits for the list rather
     * than going and coming back. Taken off, it is updated only while it is the same entry (a row
     * from before keys were kept counts as one), so the update leaves it off; another entry gets a new row.
     */
    internal fun fate(row: Row, key: String?, known: Boolean, following: Boolean): Fate = when {
        key != null && !known -> Fate.STAND
        key == null && !following && row.type == WatchNextPrograms.WATCH_NEXT_TYPE_NEW -> Fate.STAND
        key != null && (row.browsable || row.key == null || row.key == key) -> Fate.UPDATE
        else -> Fate.DELETE
    }

    /** Signed out: nothing of the account stays on the home screen. */
    suspend fun clear(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        lock.withLock {
            nexts.clear()
            withContext(Dispatchers.IO) { runCatching { context.contentResolver.delete(WatchNextPrograms.CONTENT_URI, null, null) } }
        }
    }

    /** The rows wanted, by title id. Engagement time orders the row; a second apart keeps the server's order, news after it. */
    private suspend fun programs(context: Context, watching: List<JSONObject>, following: List<JSONObject>?): Map<String, Program> {
        val now = System.currentTimeMillis()
        val wanted = LinkedHashMap<String, Program>()
        watching.forEachIndexed { i, t ->
            val engaged = now - i * 1000L
            wanted[t.titleId.toString()] = next(context, t).fold(
                onSuccess = { next ->
                    if (next == null) t.resume(context, engaged)
                    else t.next(context, next.file, nextType(next.file, next.files, NewEpisodes.of(context, t, now) != null), engaged)
                },
                // Not known: where it was left, for a title without a row yet; one it has stands (see [fate]).
                onFailure = { t.resume(context, engaged).let { Program(it.key, it.values, known = false) } },
            )
        }
        for (t in following.orEmpty()) {
            val id = t.titleId.toString()
            if (id in wanted) continue
            val news = NewEpisodes.of(context, t, now) ?: continue
            wanted[id] = t.news(context, context.getString(R.string.new_episode), news.day)
        }
        return wanted
    }

    /**
     * The launcher's type for a series' next [file] of its [files]: new for the last one out while
     * the series has [news], the episode just aired; next otherwise, as is one caught up on from
     * further back, whatever has come out since.
     */
    // ponytail: the last file stands for the aired episode; two out on the same day offer the first as next.
    internal fun nextType(file: JSONObject, files: List<JSONObject>, news: Boolean) =
        if (news && file === files.lastOrNull()) WatchNextPrograms.WATCH_NEXT_TYPE_NEW else WatchNextPrograms.WATCH_NEXT_TYPE_NEXT

    /** [next]'s file, and the series' [files] it is one of. */
    private class Next(val file: JSONObject, val files: List<JSONObject>)

    /**
     * A series' next file after a finished episode, by the `stream` answer and this TV's history, as
     * the player picks it (Watched.next); null for none, or before its episode can have finished; a
     * failure when `stream` did not answer, which says nothing of what is next. Each title's answer
     * is kept an hour for the point it was asked at, a failure a few minutes: home syncs on every
     * return, and a new episode reaches the list without the point moving.
     */
    // ponytail: in memory, so a launch asks again for each finished series (a few calls at most).
    private suspend fun next(context: Context, title: JSONObject): Result<Next?> {
        // Seen counts from the credits mark or 95% in: well short of both by the server's point, and
        // not seen through where this TV left it, it is still being watched.
        if (title.isMovie) return Result.success(null)
        if ((title.str("data", "progress")?.toDoubleOrNull() ?: 0.0) < FINISHING && !Watched.leftSeen(context, title.titleId)) return Result.success(null)
        val point = "${title.titleId} ${title.watchingAt}"
        nexts[point]?.takeIf { (at, answer) -> SystemClock.elapsedRealtime() - at < if (answer.isSuccess) HOUR else RETRY }?.let { return it.second }
        return runCatching {
            val stream = Api.stream(title.titleId, title.isFree)
            // Signed out while it was asked: the history was just cleared, and the old account's point must not land in it.
            check(Api.loggedIn) { "signed out" }
            val files = stream.optJSONArray("hls").objects()
            Watched.next(context, title.titleId, files, stream.optJSONObject("continue_watching"))?.let { Next(it, files) }
        }.also { nexts[point] = SystemClock.elapsedRealtime() to it }
    }

    private const val FINISHING = 80.0
    private const val HOUR = 3_600_000L
    private const val RETRY = 5 * 60_000L
    /** [next]'s answers by title and point: when asked, and the answer. Main thread only. */
    private val nexts = HashMap<String, Pair<Long, Result<Next?>>>()

    /**
     * Where Continue Watching left it. data: {episode_title: "فصل 1 - قسمت 4", season: 1, episode: 4,
     * time: 1980 (s), progress: "79.8387" (%)}. The same entry until the point moves.
     */
    private fun JSONObject.resume(context: Context, engaged: Long) = program("continue $watchingAt", engaged, play(context)) {
        val seconds = str("data", "time")?.toLongOrNull() ?: 0
        val progress = str("data", "progress")?.toDoubleOrNull()?.takeIf { it > 0 }
        put(WatchNextPrograms.COLUMN_TYPE, if (isMovie) WatchNextPrograms.TYPE_MOVIE else WatchNextPrograms.TYPE_TV_EPISODE)
        put(WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE, WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
        put(WatchNextPrograms.COLUMN_EPISODE_TITLE, str("data", "episode_title")?.episodeLabel())
        put(WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER, str("data", "season"))
        put(WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER, str("data", "episode"))
        put(WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS, seconds * 1000)
        // The server sends how far in and what share that is; the launcher's bar wants the length.
        put(WatchNextPrograms.COLUMN_DURATION_MILLIS, progress?.let { (seconds * 100_000 / it).toLong() })
    }

    /** The episode after a finished one, of [type] ([nextType]): no bar, and straight into [file]. The same entry while it is that file. */
    private fun JSONObject.next(context: Context, file: JSONObject, type: Int, engaged: Long) =
        program("file ${file.str("info", "file_id")}", engaged, play(context).putExtra(EXTRA_FILE, file.str("info", "file_id"))) {
            put(WatchNextPrograms.COLUMN_TYPE, WatchNextPrograms.TYPE_TV_EPISODE)
            put(WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE, type)
            // A real name, else 30nama's own «فصل 1 - قسمت 5», as the continue row has it.
            put(WatchNextPrograms.COLUMN_EPISODE_TITLE, file.str("info", "episode_name")?.unescaped()?.episodeLabel())
            put(WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER, file.str("info", "season"))
            put(WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER, file.episodeNumber.takeIf { it > 0 }?.toString())
            putNull(WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS)
            putNull(WatchNextPrograms.COLUMN_DURATION_MILLIS)
        }

    /**
     * A followed series not being watched, its episode of [day] out: its page, where the viewer picks
     * the episode, «قسمت تازه» ([label]) under its name. Ordered by when the episode counted as out.
     * The same entry for that day's episode.
     */
    private fun JSONObject.news(context: Context, label: String, day: Long) =
        program("new $day", NewEpisodes.outAt(day), home(context).putExtra(EXTRA_DETAILS, toString())) {
            put(WatchNextPrograms.COLUMN_TYPE, WatchNextPrograms.TYPE_TV_SERIES)
            put(WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE, WatchNextPrograms.WATCH_NEXT_TYPE_NEW)
            put(WatchNextPrograms.COLUMN_EPISODE_TITLE, label)
            putNull(WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER)
            putNull(WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER)
            putNull(WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS)
            putNull(WatchNextPrograms.COLUMN_DURATION_MILLIS)
        }

    /** Every entry: [fill]'s columns, then the title, its art, when, its [key] and the way in. Nulls are written too, so an updated row drops what no longer applies. */
    private fun JSONObject.program(key: String, engaged: Long, intent: Intent, fill: ContentValues.() -> Unit) = Program(key, ContentValues().apply {
        fill()
        put(WatchNextPrograms.COLUMN_TITLE, englishName)
        put(WatchNextPrograms.COLUMN_POSTER_ART_URI, str("image", "cover", "jpg"))
        put(WatchNextPrograms.COLUMN_POSTER_ART_ASPECT_RATIO, WatchNextPrograms.ASPECT_RATIO_16_9)
        put(WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS, engaged)
        put(WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID, titleId.toString())
        put(WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_DATA, key.encodeToByteArray())
        put(WatchNextPrograms.COLUMN_INTENT_URI, intent.toUri(Intent.URI_INTENT_SCHEME))
    })

    private fun JSONObject.play(context: Context) = home(context)
        .putExtra(EXTRA_PLAY, titleId)
        .putExtra(EXTRA_FREE, isFree)
        .putExtra(EXTRA_TITLE, playerFacts.toString())

    private fun home(context: Context) =
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
}
