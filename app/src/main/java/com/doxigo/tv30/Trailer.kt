package com.doxigo.tv30

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * A title's trailer. 30nama has none (no field in any answer, nothing in the official app), so it
 * comes from the two places its audience watches trailers:
 * - YouTube: the official trailer TMDB lists for the title, handed to the TV's YouTube app. Blocked
 *   in Iran without a VPN, so offered only while youtube.com answers from here.
 * - Aparat, Iran's own video site, where trailers are re-uploaded, often with Persian subtitles:
 *   its public search and streams, played by the native TV player. Reachable in Iran and out.
 * The button plays YouTube's when it can (TMDB matches by IMDb id, Aparat only by name); a long
 * press offers both.
 */
object Trailer {
    /** A YouTube video key and an Aparat video hash; either may be missing, never both. */
    class Sources(val youtube: String?, val aparat: String?)

    // SmartTube first: a TV it is sideloaded on is one whose owner watches YouTube there. Package
    // visibility needs each of these in the manifest's <queries>.
    private val YOUTUBE_APPS = listOf(
        "com.teamsmart.videomanager.tv", "com.liskovsoft.smarttubetv.beta", "com.liskovsoft.smarttubetv",
        "com.google.android.youtube.tv", "com.google.android.youtube",
    )
    private const val AWHILE = 5 * 60_000L

    private val client by lazy { Api.http.newBuilder().connectTimeout(4, TimeUnit.SECONDS).callTimeout(6, TimeUnit.SECONDS).build() }
    /** By title id, "" for none, for the process's life. */
    private val youtubeKeys = ConcurrentHashMap<Int, String>()
    private val aparatIds = ConcurrentHashMap<Int, String>()
    @Volatile private var checked = 0L
    @Volatile private var reachable = false

    /** Where [title]'s trailer can play from here, both looked up at once; null when nowhere. */
    suspend fun find(context: Context, title: JSONObject): Sources? = coroutineScope {
        val youtube = async { if (app(context, "") != null && youtubeReachable()) youtubeKey(title) else null }
        val aparat = async { aparatId(title) }
        Sources(youtube.await(), aparat.await()).takeIf { it.youtube != null || it.aparat != null }
    }

    /** The best source: YouTube's when there is one, else Aparat's. */
    fun play(activity: Activity, sources: Sources) {
        if (sources.youtube != null && youtube(activity, sources.youtube)) return
        sources.aparat?.let { activity.startActivity(TrailerActivity.intent(activity, it)) }
    }

    /** «یوتیوب» or «آپارات», for a long press; with only one of them, just [play]. */
    fun choose(activity: Activity, sources: Sources) {
        val youtube = sources.youtube ?: return play(activity, sources)
        val aparat = sources.aparat ?: return play(activity, sources)
        val options = listOf<CharSequence>(activity.getString(R.string.trailer_youtube), activity.getString(R.string.trailer_aparat))
        // YouTube is what a plain press plays, so it is the current choice.
        activity.choose(activity.getString(R.string.watch_trailer), options, 0) { which ->
            if (which != 0 || !youtube(activity, youtube)) activity.startActivity(TrailerActivity.intent(activity, aparat))
        }
    }

    /** Hands [key] to the first YouTube app that takes it; false when none does. */
    private fun youtube(context: Context, key: String): Boolean {
        val intent = app(context, key) ?: return false
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /** The watch intent the first installed YouTube app answers: its web link, else the old vnd.youtube one. */
    private fun app(context: Context, key: String): Intent? = YOUTUBE_APPS.firstNotNullOfOrNull { app ->
        listOf("https://www.youtube.com/watch?v=$key", "vnd.youtube:$key")
            .map { Intent(Intent.ACTION_VIEW, Uri.parse(it)).setPackage(app) }
            .firstOrNull { it.resolveActivity(context.packageManager) != null }
    }

    /** Whether YouTube answers from here, asked every five minutes at most: VPNs come and go. */
    private suspend fun youtubeReachable(): Boolean {
        if (System.currentTimeMillis() - checked < AWHILE) return reachable
        val request = Request.Builder().url("https://www.youtube.com/generate_204").head().build()
        reachable = try {
            Api.run { client.newCall(request).await() }.use { it.code == 204 }
        } catch (e: IOException) {
            false
        }
        checked = System.currentTimeMillis()
        return reachable
    }

    /** TMDB's trailer on YouTube: Persian before English, a trailer before a teaser, the studio's own first. */
    private suspend fun youtubeKey(title: JSONObject): String? {
        youtubeKeys[title.titleId]?.let { return it.ifEmpty { null } }
        // Off or out of reach is not "none": nothing kept, so the next visit asks again.
        val entry = runCatching { Tmdb.title(title) }.getOrElse { return null }
        val key = entry?.obj("videos")?.optJSONArray("results").objects()
            .filter { it.optString("site") == "YouTube" && it.optString("type") in setOf("Trailer", "Teaser") }
            .sortedWith(compareBy({ it.optString("iso_639_1") != "fa" }, { it.optString("type") != "Trailer" }, { !it.optBoolean("official") }))
            .firstNotNullOfOrNull { it.str("key") }
        youtubeKeys[title.titleId] = key.orEmpty()
        return key
    }

    /**
     * Aparat's most watched upload, by its public search for «تریلر <name>», that [isTrailer] for
     * this title. ponytail: first page of ten, English name only; the Persian name matched sequels
     * ("تلماسه" in "Dune: Part Three"), so add it back only with the same whole-name rule.
     */
    private suspend fun aparatId(title: JSONObject): String? {
        aparatIds[title.titleId]?.let { return it.ifEmpty { null } }
        val name = title.englishName
        // Aparat answers 400 to an apostrophe, "&", "?" or "#": letters and digits only.
        val query = "تریلر " + name.replace(Regex("['’]"), "").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        val request = Request.Builder().url("https://www.aparat.com/etc/api/videoBySearch/text/${Uri.encode(query)}/perpage/10").build()
        val hits = try {
            Api.run { client.newCall(request).await() }.use { response ->
                if (!response.isSuccessful) throw IOException("Aparat ${response.code}")
                withContext(Dispatchers.IO) { JSONObject(response.body!!.string()) }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return null
        }
        val year = title.obj("info")?.optInt("year") ?: 0
        val id = hits.optJSONArray("videobysearch").objects()
            .filter { it.optInt("duration") in 20..400 && isTrailer(it.optString("title"), name.normalized(), year) }
            .maxByOrNull { it.optLong("visit_cnt") }?.str("uid")
        aparatIds[title.titleId] = id.orEmpty()
        return id
    }

    /** Signed media links expire, so resolve them when playback starts, including after a retry. */
    suspend fun aparatStream(id: String): String {
        require(id.matches(Regex("[A-Za-z0-9]+")))
        val request = Request.Builder()
            .url("https://www.aparat.com/api/fa/v1/video/video/show/videohash/$id/watchtype/embed")
            .header("Jsontype", "simple")
            .build()
        val video = Api.run { client.newCall(request).await() }.use { response ->
            if (!response.isSuccessful) throw IOException("Aparat ${response.code}")
            withContext(Dispatchers.IO) { JSONObject(response.body!!.string()).obj("data") }
        } ?: throw IOException("Aparat video missing")
        val files = video.optJSONArray("file_link_all").objects().flatMap { file ->
            val height = file.optString("profile").removeSuffix("p").toIntOrNull() ?: 0
            val urls = file.optJSONArray("urls")
            (0 until (urls?.length() ?: 0)).map { height to urls!!.optString(it) }
        }
        return aparatMediaUrl(video.str("hls_link"), files, video.str("file_link"))
            ?: throw IOException("Aparat stream missing")
    }

    internal fun aparatMediaUrl(hls: String?, files: List<Pair<Int, String>>, fallback: String?): String? {
        fun media(url: String?, vararg extensions: String): String? = url?.toHttpUrlOrNull()?.takeIf {
            it.isHttps && (it.host == "aparat.com" || it.host.endsWith(".aparat.com")) &&
                extensions.any { extension -> it.encodedPath.endsWith(extension, ignoreCase = true) }
        }?.toString()
        return media(hls, ".m3u8") ?: files.sortedByDescending { it.first }.firstNotNullOfOrNull {
            media(it.second, ".mp4", ".apt")
        } ?: media(fallback, ".mp4", ".apt")
    }

    /**
     * Whether an Aparat video [hit] (its title) is the trailer for [name] (normalized) of [year]:
     * it says trailer or teaser, is no game's, gives no year far from the title's, and names the
     * title whole: Persian, a year or "trailer" on either side of "Dune", not "Part Three" after
     * it or "Masha and" before "The Bear".
     */
    internal fun isTrailer(hit: String, name: String, year: Int): Boolean {
        val words = " ${hit.replace('ي', 'ی').replace('ك', 'ک').normalized()} "
        if (listOf("تریلر", "تیزر", "trailer", "teaser").none { " $it " in words } || " بازی " in words) return false
        val years = Regex("(?<!\\d)(19|20)\\d\\d(?!\\d)").findAll(hit).map { it.value.toInt() }.toList()
        if (year > 0 && years.isNotEmpty() && years.none { abs(it - year) <= 1 }) return false
        if (name.isEmpty()) return false
        fun apart(word: String) = word.none { it in 'a'..'z' || it in '0'..'9' } ||
            word in setOf("trailer", "teaser", "official", "final") || word.matches(Regex("(19|20)\\d\\d"))
        var at = words.indexOf(" $name ")
        while (at >= 0) {
            if (apart(words.substring(0, at).substringAfterLast(' ')) && apart(words.substring(at + name.length + 2).substringBefore(' '))) return true
            at = words.indexOf(" $name ", at + 1)
        }
        return false
    }
}
