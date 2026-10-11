package com.doxigo.tv30

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvContract.WatchNextPrograms
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Continue Watching mirrored onto the launcher's Play Next row, the Android TV take on Apple's
 * Top Shelf: a title resumes from the home screen without opening the app first. The platform
 * TvProvider (Android 8+) only; some TVs ship without one, so every failure is ignored.
 * The provider scopes every query, update and delete to our own rows and refuses a selection,
 * hence whole-table reads and per-row URIs.
 */
object WatchNext {
    /** The launcher's way back in: MainActivity opens the player on this title over home. */
    const val EXTRA_PLAY = "play"
    const val EXTRA_FREE = "free"

    /** Loads overlap (start, return from the player, a dismiss): one sync at a time, or a title lands twice. */
    private val lock = Mutex()

    /** Makes the row match [posts], the server's continue_watching in its order. */
    suspend fun sync(context: Context, posts: List<JSONObject>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        lock.withLock {
            withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = context.contentResolver
                    val now = System.currentTimeMillis()
                    // Engagement time orders the row; a second apart keeps the server's order.
                    val wanted = posts.mapIndexed { i, t -> t.titleId.toString() to t.program(context, now - i * 1000L) }.toMap()
                    val rows = HashMap<String, Long>()
                    resolver.query(WatchNextPrograms.CONTENT_URI, arrayOf(WatchNextPrograms._ID, WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID), null, null, null)?.use {
                        while (it.moveToNext()) {
                            val id = it.getString(1).orEmpty()
                            if (id in wanted && id !in rows) rows[id] = it.getLong(0)
                            else resolver.delete(TvContract.buildWatchNextProgramUri(it.getLong(0)), null, null)
                        }
                    }
                    for ((id, values) in wanted) {
                        val row = rows[id]
                        if (row == null) resolver.insert(WatchNextPrograms.CONTENT_URI, values)
                        else resolver.update(TvContract.buildWatchNextProgramUri(row), values, null, null)
                    }
                }
            }
        }
    }

    /** Signed out: nothing of the account stays on the home screen. */
    suspend fun clear(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        lock.withLock {
            withContext(Dispatchers.IO) { runCatching { context.contentResolver.delete(WatchNextPrograms.CONTENT_URI, null, null) } }
        }
    }

    /**
     * data: {episode_title: "فصل 1 - قسمت 4", season: 1, episode: 4, time: 1980 (s), progress: "79.8387" (%)}.
     * Nulls are written too, so an updated row drops what no longer applies.
     */
    private fun JSONObject.program(context: Context, engaged: Long) = ContentValues().apply {
        val seconds = str("data", "time")?.toLongOrNull() ?: 0
        val progress = str("data", "progress")?.toDoubleOrNull()?.takeIf { it > 0 }
        put(WatchNextPrograms.COLUMN_TYPE, if (isMovie) WatchNextPrograms.TYPE_MOVIE else WatchNextPrograms.TYPE_TV_EPISODE)
        put(WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE, WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
        put(WatchNextPrograms.COLUMN_TITLE, englishName)
        put(WatchNextPrograms.COLUMN_EPISODE_TITLE, str("data", "episode_title")?.episodeLabel())
        put(WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER, str("data", "season"))
        put(WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER, str("data", "episode"))
        put(WatchNextPrograms.COLUMN_POSTER_ART_URI, str("image", "cover", "jpg"))
        put(WatchNextPrograms.COLUMN_POSTER_ART_ASPECT_RATIO, WatchNextPrograms.ASPECT_RATIO_16_9)
        put(WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS, seconds * 1000)
        // The server sends how far in and what share that is; the launcher's bar wants the length.
        put(WatchNextPrograms.COLUMN_DURATION_MILLIS, progress?.let { (seconds * 100_000 / it).toLong() })
        put(WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS, engaged)
        put(WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID, titleId.toString())
        put(WatchNextPrograms.COLUMN_INTENT_URI, Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_PLAY, titleId)
            .putExtra(EXTRA_FREE, isFree)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .toUri(Intent.URI_INTENT_SCHEME))
    }
}
