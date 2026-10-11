package com.doxigo.tv30

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * This week's trending films and series from TMDB, mapped back to titles 30nama can stream, as the
 * community Tizen client does (Tmdb.match). Off without a TMDB token, and out of reach from Iran
 * without a VPN; that, like any other failure, just means no row.
 */
object Trending {
    private const val TTL = 6 * 3600_000L
    private val lock = Mutex()
    private val memo = HashMap<String, Pair<Long, List<JSONObject>>>()

    /** 30nama titles in TMDB's trending order; empty when off, out of reach or nothing matched. */
    suspend fun titles(context: Context): List<JSONObject> {
        if (!Tmdb.enabled) return emptyList()
        return lock.withLock {
            // Names and genres come back in the UI language, so each language keeps its own copy.
            val file = context.cacheDir.resolve("trending-${Api.language}.json")
            memo[file.name]?.takeIf { fresh(it.first) }?.second
                // A failed fetch leaves the file stale, so the next visit to home tries again.
                ?: (read(file) ?: fetch(file)).also { memo[file.name] = file.lastModified() to it }
        }
    }

    private fun fresh(time: Long) = System.currentTimeMillis() - time < TTL

    private suspend fun read(file: File) = withContext(Dispatchers.IO) {
        if (fresh(file.lastModified())) runCatching { JSONArray(file.readText()).objects() }.getOrNull() else null
    }

    // ponytail: page 1 only (20 titles, about 15 on 30nama); fetch page 2 if the row runs short.
    private suspend fun fetch(file: File): List<JSONObject> {
        val trending = runCatching { Tmdb.get("trending/all/week?language=en-US").optJSONArray("results").objects() }
            .getOrNull() ?: return emptyList()
        val titles = Tmdb.match(trending.filter { it.optString("media_type") in setOf("movie", "tv") })
        if (titles.isNotEmpty()) withContext(Dispatchers.IO) { runCatching { file.writeText(JSONArray(titles).toString()) } }
        return titles
    }
}
