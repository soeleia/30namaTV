package com.doxigo.tv30

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.text.Normalizer
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * TMDB, for everything 30nama doesn't carry: the trending row, episode stills, Persian names, More
 * Like This and trailers. Off without a key: the viewer's own from Settings, else (the developer's
 * builds only) `tmdb.token` in local.properties. From Iran TMDB is often out of reach without a VPN,
 * so a failure backs every caller off for a minute: a page of five seasons, or a home of forty
 * names, waits on one timeout, not forty.
 */
object Tmdb {
    const val NAME = "TMDB"
    /** A v4 read access token (a JWT, "eyJ…") or a v3 API key (32 hex digits): TMDB's page offers both, and viewers paste either. */
    val key get() = Settings.pickKey(Settings.savedKey(NAME), BuildConfig.TMDB_TOKEN)
    val enabled get() = key.isNotEmpty()

    // Short: nothing waits on TMDB, but a blocked TMDB should give up quickly.
    private val client by lazy { Api.http.newBuilder().connectTimeout(4, TimeUnit.SECONDS).callTimeout(8, TimeUnit.SECONDS).build() }
    @Volatile private var failed = 0L
    val backingOff get() = System.currentTimeMillis() - failed < 60_000

    /** `GET /3/[path]`. A 404 (a season TMDB numbers differently, an id it lacks) is an empty answer, not TMDB being down. */
    suspend fun get(path: String): JSONObject {
        if (!enabled || backingOff) throw IOException("TMDB off")
        val request = request(path, key)
        return try {
            Api.run { client.newCall(request).await() }.use { response ->
                if (response.code == 404) return JSONObject()
                if (!response.isSuccessful) throw IOException("TMDB ${response.code}")
                withContext(Dispatchers.IO) { JSONObject(response.body!!.string()) }
            }
        } catch (e: IOException) {
            failed = System.currentTimeMillis()
            throw e
        }
    }

    /**
     * `GET /3/configuration`, TMDB's lightest call: true when it takes [key], false when it answers
     * 401, an IOException when it can't be reached. Off the main thread, closing included: an unread
     * HTTP/2 body is cancelled with a write to the socket.
     */
    suspend fun check(key: String): Boolean = withContext(Dispatchers.IO) {
        Api.run { client.newCall(request("configuration", key)).await() }.use { response ->
            when {
                response.isSuccessful -> true
                response.code == 401 -> false
                else -> throw IOException("TMDB ${response.code}")
            }
        }
    }

    /** `/3/[path]` with [key] as its kind is sent: a v3 key as the api_key parameter, a read token as a Bearer header. */
    internal fun request(path: String, key: String): Request {
        val url = "https://api.themoviedb.org/3/$path".toHttpUrl()
        if (isApiKey(key)) return Request.Builder().url(url.newBuilder().addQueryParameter("api_key", key).build()).build()
        return Request.Builder().url(url).header("Authorization", "Bearer $key").build()
    }

    internal fun isApiKey(key: String) = Regex("[0-9a-fA-F]{32}").matches(key)

    /**
     * TMDB's entry for an IMDb id, as [language] names it: the [series] kind first, then the other
     * (30nama files some miniseries as films). Its `media_type` says which it found.
     */
    suspend fun find(imdb: String, series: Boolean, language: String = "en-US"): JSONObject? {
        val found = get("find/$imdb?external_source=imdb_id&language=$language")
        return (if (series) listOf("tv", "movie") else listOf("movie", "tv")).firstNotNullOfOrNull { kind ->
            found.optJSONArray("${kind}_results").objects().firstOrNull()?.put("media_type", kind)
        }
    }

    private val entries = Mutex()
    private var last: Pair<String, JSONObject?>? = null

    /**
     * A 30nama title's TMDB entry with what its page wants appended in the same call: recommendations,
     * similar titles and videos (Persian and English). Only the last one is kept, which is enough
     * for the More Like This row and the trailer to share one call; null when TMDB doesn't know it.
     */
    suspend fun title(title: JSONObject): JSONObject? {
        val imdb = title.imdbId ?: return null
        return entries.withLock {
            last?.takeIf { it.first == imdb }?.let { return@withLock it.second }
            val entry = find(imdb, !title.isMovie)?.let { hit ->
                val kind = hit.optString("media_type")
                get("$kind/${hit.optInt("id")}?language=en-US&append_to_response=recommendations,similar,videos&include_video_language=fa,en")
                    .put("media_type", kind)
            }
            entry.also { last = imdb to it }
        }
    }

    /**
     * The streamable 30nama titles for TMDB [items], in their order and each once. An item's own
     * `media_type` says film or series; lists without one (`similar`) are all [kind]. 30nama sits
     * behind Cloudflare's rate limit, so at most four searches at once.
     */
    suspend fun match(items: List<JSONObject>, kind: String? = null): List<JSONObject> {
        val gate = Semaphore(4)
        return coroutineScope {
            items.map { async { runCatching { match(it, it.optString("media_type").ifEmpty { kind }, gate) }.getOrNull() } }.awaitAll()
        }.filterNotNull().distinctBy { it.titleId }
    }

    /**
     * One TMDB item's 30nama title: the search hit with the same IMDb id, else one with the same
     * English name, year (±1) and kind whose IMDb id does not contradict it.
     */
    private suspend fun match(item: JSONObject, kind: String?, gate: Semaphore): JSONObject? = coroutineScope {
        val name = item.str("title") ?: item.str("name") ?: return@coroutineScope null
        val imdb = async { runCatching { get("$kind/${item.optInt("id")}/external_ids").str("imdb_id") }.getOrNull() }
        val year = (item.str("release_date") ?: item.str("first_air_date"))?.take(4)?.toIntOrNull()
        // ponytail: searches the English name only; also try original_name if foreign titles go missing.
        val hits = gate.withPermit { Api.search(name, 1) }.optJSONArray("posts").objects()
        val id = imdb.await()
        hits.firstOrNull { id != null && it.imdbId == id }
            ?: hits.firstOrNull { (id == null || it.imdbId == null) && it.isNamed(name, year, kind == "tv") }
    }

    private fun JSONObject.isNamed(name: String, year: Int?, series: Boolean): Boolean {
        val own = obj("info")?.optInt("year") ?: 0
        return obj("options")?.optBoolean("is_series") == series &&
            englishName.normalized() == name.normalized() &&
            (year == null || own == 0 || abs(own - year) <= 1)
    }
}

/** The title's IMDb id ("tt0903747"), from the link 30nama gives its IMDb score. */
val JSONObject.imdbId get() = str("score", "imdb", "link")?.let { Regex("tt\\d+").find(it)?.value }

/**
 * A name as words to compare: lower case, accents and punctuation gone, "&" read as "and".
 * ponytail: exact match after this; the Tizen client's token-overlap score if near-misses show up.
 */
fun String.normalized() = Normalizer.normalize(lowercase(), Normalizer.Form.NFD)
    .replace(Regex("\\p{Mn}+"), "")
    .replace("&", " and ")
    .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
    .trim()
