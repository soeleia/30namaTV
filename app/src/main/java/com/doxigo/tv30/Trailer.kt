package com.doxigo.tv30

import android.app.Activity
import android.net.Uri
import android.os.Build
import android.webkit.WebView
import androidx.annotation.DrawableRes
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
 * comes from the two places its audience watches trailers, both played in the app (TrailerActivity):
 * - Aparat, Iran's own video site, where trailers are re-uploaded, often with Persian subtitles:
 *   its public search and streams. Reachable in Iran and out, without a VPN.
 * - YouTube: the official trailer TMDB lists for the title. Blocked in Iran without a VPN, so
 *   offered only while youtube.com answers from here.
 * The button plays Aparat's in Iran, unfiltered and on domestic traffic, and YouTube's, the studio's
 * own, elsewhere; either falls back to the other. It says YouTube when it would play that; a long
 * press offers both.
 */
object Trailer {
    /** A YouTube video key and an Aparat video hash, either may be missing, never both; and whether the viewer is in Iran. */
    class Sources(val youtube: String?, val aparat: String?, val iran: Boolean) {
        /** What a press plays: in Iran Aparat's, else YouTube's, each when there is one. */
        val viaYoutube get() = if (iran) aparat == null else youtube != null

        /** The button's icon: YouTube's mark when a press plays YouTube, else the clapperboard. */
        @get:DrawableRes val icon get() = if (viaYoutube) R.drawable.act_youtube else R.drawable.act_trailer
    }

    private const val AWHILE = 5 * 60_000L

    private val client by lazy { Api.http.newBuilder().connectTimeout(4, TimeUnit.SECONDS).callTimeout(6, TimeUnit.SECONDS).build() }
    /** By title id, "" for none, for the process's life. */
    private val youtubeKeys = ConcurrentHashMap<Int, String>()
    private val aparatIds = ConcurrentHashMap<Int, String>()
    @Volatile private var checked = 0L
    @Volatile private var reachable = false
    @Volatile private var located = 0L
    @Volatile private var iran = true

    /** Where [title]'s trailer can play from here, all looked up at once; null when nowhere. */
    suspend fun find(title: JSONObject): Sources? = coroutineScope {
        val youtube = async { if (youtubeEmbeds() && youtubeReachable()) youtubeKey(title) else null }
        val aparat = async { aparatId(title) }
        val iran = async { inIran() }
        Sources(youtube.await(), aparat.await(), iran.await()).takeIf { it.youtube != null || it.aparat != null }
    }

    /** The best source for where the viewer is ([Sources.viaYoutube]). */
    fun play(activity: Activity, sources: Sources) {
        val intent = if (sources.viaYoutube) TrailerActivity.intent(activity, sources.youtube!!, youtube = true)
            else TrailerActivity.intent(activity, sources.aparat!!)
        activity.startActivity(intent)
    }

    /** «آپارات» or «یوتیوب», for a long press; with only one of them, just [play]. */
    fun choose(activity: Activity, sources: Sources) {
        val aparat = sources.aparat ?: return play(activity, sources)
        val youtube = sources.youtube ?: return play(activity, sources)
        val options = listOf<CharSequence>(activity.getString(R.string.trailer_aparat), activity.getString(R.string.trailer_youtube))
        // What a plain press plays is the current choice.
        activity.choose(activity.getString(R.string.watch_trailer), options, if (sources.viaYoutube) 1 else 0) { which ->
            activity.startActivity(if (which == 0) TrailerActivity.intent(activity, aparat) else TrailerActivity.intent(activity, youtube, youtube = true))
        }
    }

    /**
     * Whether the TV's WebView can run YouTube's embed: Chrome 71 (2018) on, as its script calls
     * queueMicrotask; older ones play it black. Before Android 8 the version can't be asked without
     * loading WebView, so the page checks for itself (TrailerActivity's YoutubePlayer).
     */
    private fun youtubeEmbeds() = Build.VERSION.SDK_INT < 26 ||
        (WebView.getCurrentWebViewPackage()?.versionName?.substringBefore('.')?.toIntOrNull() ?: 0) >= 71

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

    /**
     * Whether the viewer is in Iran: the country Cloudflare sees this connection from, at 30nama's own
     * site (no third party learns of it), so a VPN counts where it comes out. Not knowing counts as
     * Iran, where Aparat is the safe choice. Asked every five minutes at most: VPNs come and go.
     */
    private suspend fun inIran(): Boolean {
        if (System.currentTimeMillis() - located < AWHILE) return iran
        val request = Request.Builder().url("https://30nama.com/cdn-cgi/trace").build()
        iran = try {
            Api.run { client.newCall(request).await() }.use { response ->
                country(withContext(Dispatchers.IO) { response.body!!.string() }) in setOf(null, "IR")
            }
        } catch (e: IOException) {
            true
        }
        located = System.currentTimeMillis()
        return iran
    }

    /** The `loc=` line of a Cloudflare trace ("SE"), or null without one. */
    internal fun country(trace: String) = trace.lineSequence().firstOrNull { it.startsWith("loc=") }?.removePrefix("loc=")?.trim()

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
