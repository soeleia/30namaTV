package com.doxigo.tv30

import android.os.Build
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlin.math.abs

/**
 * Subtitles from outside 30nama, for when its own are off or missing: SubSource, the site 30nama
 * itself links to, with no key at all; SubDL (a free key, no login, and the Persian archive it took
 * over from Subscene) and OpenSubtitles beside it once they have theirs: the viewer's own from
 * Settings, else (the developer's builds only) `subdl.key`, `opensubtitles.key` in local.properties.
 * API keys only, never a login. What they find is ranked by how well its release fits the file
 * that plays ([ranked]).
 *
 * The shapes, as checked in October 2026:
 * - SubSource has no API for programs without a key; these are the requests its own pages make
 *   (subsource.net's script, seen live from here with no key, cookie or Cloudflare challenge), so
 *   they may change when the site does. After Core/SubSource.swift in the Mac client
 *   (github.com/thesaikodelik/30nama-apps, MIT), which reads the listing's page instead. 30nama's
 *   `single` links each title's listings in `subtitle` [{language, link
 *   ("https://subsource.net/subtitles/dark-matter-2024/season-2/farsi_persian"), season}]; without
 *   them, `POST api.subsource.net/v1/movie/search {query, includeSeasons, limit}` takes an IMDb id
 *   (answering that title alone) or a name and answers {results: [{title, type ("movie" |
 *   "tvseries"), releaseYear, link ("/subtitles/hush-2016"), seasons: [{season, link
 *   ("/subtitles/dark-matter-2024/season=2")}]}]}. `GET /v1/subtitles/{dark-matter-2024/season-2}
 *   ?language=farsi_persian,english` lists a season's or a film's subtitles, a row for each release
 *   one fits: {subtitles: [{id, language, release_info, hearing_impaired (0, 1 or null), link
 *   ("dark-matter-season-2-1/farsi_persian/10270792")}]}, and a title it has no page for is a 404.
 *   `GET /v1/subtitle/{link}` gives {subtitle: {download_token}}, and `GET
 *   /v1/subtitle/download/{token}` the zip.
 * - SubDL documents its API behind a bot wall (subdl.com/api-doc), so from the clients that track
 *   it: Bazarr's provider and its tests (github.com/morpheus65535/bazarr,
 *   custom_libs/subliminal_patch/providers/subdl.py, tests/subliminal_patch/test_subdl.py) and
 *   Cloudstream's (github.com/recloudstream/cloudstream, …/syncproviders/providers/Subdl.kt).
 *   `GET api.subdl.com/api/v1/subtitles` takes api_key, imdb_id (the series' own for an episode)
 *   or film_name and year, type movie|tv, season_number, episode_number, languages=FA,EN and
 *   subs_per_page (30 at most). It answers {status, subtitles: [{release_name, name, language
 *   ("FA"), lang, url ("/subtitle/….zip"), hi, season, episode, episode_from, episode_end}]}, and
 *   the zip is at dl.subdl.com + url. A bad key answers 403 {"error":"not_authorized"} (seen
 *   live); a spent allowance 429 {"error":"daily_limit" | "rate_limit"}.
 * - OpenSubtitles from its own OpenAPI file (opensubtitles.stoplight.io, open_api.json), its live
 *   /infos/languages ("fa", "en") and Bazarr's opensubtitlescom.py. `GET /api/v1/subtitles` takes
 *   imdb_id (a film) or parent_imdb_id with season_number and episode_number, ids as plain numbers,
 *   parameters sorted (else it answers with a redirect first); results are data[].attributes
 *   {language, release, download_count, hearing_impaired, files: [{file_id, file_name}]}. `POST
 *   /api/v1/download {file_id}` gives a temporary link to the file, always UTF-8. Every call sends
 *   Api-Key and a "name vVERSION" User-Agent. With no signed-in user, a free key downloads 5 a day
 *   per IP; 406 says they are spent.
 */
object Subtitles {
    const val SUBSOURCE = "SubSource"
    const val SUBDL = "SubDL"
    const val OPENSUBTITLES = "OpenSubtitles"
    private const val SUBSOURCE_API = "https://api.subsource.net/v1"
    // ponytail: 20 per language, the first page of each source; ask for a second page if a title ever outgrows it.
    private const val PER_LANGUAGE = 20
    /** No subtitle, nor a season's zip of them, comes near this: anything bigger is not one. */
    private const val LIMIT = 8 shl 20
    private val TYPES = setOf("srt", "ass", "ssa", "vtt")
    private val AGENT = "30namaTV v${BuildConfig.VERSION_NAME}"

    val subdlKey get() = Settings.pickKey(Settings.savedKey(SUBDL), BuildConfig.SUBDL_KEY)
    val openSubtitlesKey get() = Settings.pickKey(Settings.savedKey(OPENSUBTITLES), BuildConfig.OPENSUBTITLES_KEY)

    // Short, as for TMDB: the remote waits on this, and from Iran any of the sites may be out of reach.
    private val client by lazy { Api.http.newBuilder().connectTimeout(5, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build() }

    /**
     * What plays: a film, or one episode ([season] and [episode] above 0), by IMDb id when 30nama
     * links one, else by name and year. [release] is what is known of the file itself (its quality,
     * its name), to rank what is found by; [pages], 30nama's own links to SubSource (`single`'s
     * `subtitle`), when the title came with them.
     */
    class Wanted(val imdb: String?, val name: String, val year: Int?, val season: Int, val episode: Int, val release: String = "", val pages: JSONArray? = null) {
        val isEpisode get() = season > 0 && episode > 0
    }

    /** One subtitle on offer: [ref] is what its source downloads it by (SubSource's link, SubDL's path, OpenSubtitles' file id). */
    class Found(val source: String, val language: String, val release: String, val downloads: Int, val hearingImpaired: Boolean, internal val ref: String)

    /** A source said no: its allowance for today is spent ([quota]), or it is down or refused the key. */
    class Refused(val source: String, val quota: Boolean) : IOException("$source refused")

    /** A download with no subtitle in it this player reads: a rar, an image format, a broken zip. */
    class Unreadable : IOException("no readable subtitle")

    /**
     * Every source at once: Persian, then English, at most [PER_LANGUAGE] of each, best [ranked]
     * first, ties in the sources' order (SubSource's, SubDL's, OpenSubtitles'). One source failing
     * leaves the others' answers; with nothing found, the first failure is what went wrong.
     */
    suspend fun search(wanted: Wanted): List<Found> = coroutineScope {
        val sources = listOfNotNull<suspend (Wanted) -> List<Found>>(
            ::subSource,
            ::subdl.takeIf { subdlKey.isNotEmpty() },
            ::openSubtitles.takeIf { openSubtitlesKey.isNotEmpty() },
        )
        val answers = sources.map { async { runCatching { it(wanted) } } }.awaitAll()
        val found = answers.flatMap { it.getOrDefault(emptyList()) }
        if (found.isEmpty()) answers.firstNotNullOfOrNull { it.exceptionOrNull() }?.let { throw it }
        val ranked = ranked(found, wanted)
        listOf("fa", "en").flatMap { language -> ranked.filter { it.language == language }.take(PER_LANGUAGE) }
    }

    /** Words of a release that say where the copy came from, which decides its timing most. */
    private val SOURCES = setOf("bluray", "web", "webrip", "hdtv", "dvdrip", "hdrip")

    /** An episode a release names: "S02E08", "S01E01-E10", "2x08", "Eps 07", "E08"; not the 265 of "x265". */
    private val EPISODE = Regex("(?i)(?<![a-z0-9])(?:s(\\d{1,2})[ ._-]?e|(\\d{1,2})x|(?:episode|eps|ep|e)[ ._-]*)(\\d{1,3})(?:-e?(\\d{1,3}))?(?!\\d)")

    /** A name's words, a source's spellings as one ("WEB-DL" is "web", "BRRip" "bluray"): "1080p", "s02e08", "cakes". */
    internal fun words(name: String) = name.lowercase().replace("web-dl", "webdl").replace("blu-ray", "bluray")
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .mapNotNull { word ->
            when (word) {
                "" -> null
                "webdl" -> "web"
                "bdrip", "brrip" -> "bluray"
                else -> word
            }
        }.toSet()

    /**
     * How well [release] fits what plays, after best(for:) in the Mac client's Core/Models.swift:
     * for an episode, naming it is worth 100 and naming none (a season's pack) 20, and a release
     * naming another episode is no use (null). Then each word shared with the file's [Wanted.release]:
     * a source 30, anything else (resolution, group) 1.
     */
    internal fun score(release: String, wanted: Wanted): Int? {
        var score = 0
        if (wanted.isEpisode) {
            val named = EPISODE.find(release)?.destructured
            score += when {
                named == null -> 20
                named.let { (season, otherSeason, from, to) ->
                    (season.ifEmpty { otherSeason }.toIntOrNull() ?: wanted.season) == wanted.season &&
                        wanted.episode in from.toInt()..(to.toIntOrNull() ?: from.toInt())
                } -> 100
                else -> return null
            }
        }
        val shared = words(wanted.release) intersect words(release)
        return score + shared.sumOf { if (it in SOURCES) 30 else 1 }
    }

    /** [found] best [score] first, ties as they came; those for another episode go. */
    internal fun ranked(found: List<Found>, wanted: Wanted) =
        found.mapNotNull { f -> score(f.release, wanted)?.let { f to it } }.sortedByDescending { it.second }.map { it.first }

    private suspend fun subSource(wanted: Wanted): List<Found> {
        val path = subSourcePage(wanted.pages, wanted) ?: subSourceSearch(wanted) ?: return emptyList()
        val url = "$SUBSOURCE_API/subtitles/$path".toHttpUrl().newBuilder().addEncodedQueryParameter("language", "farsi_persian,english").build()
        // ponytail: a stale 30nama link (SubSource's 404) reads as SubSource down; search when one turns up.
        return subSourceFound(json(SUBSOURCE, Request.Builder().url(url).build()), wanted)
    }

    /** The listing's path ("dark-matter-2024/season-2") in 30nama's own link for what plays, any language's; "season=2" is how the site once wrote it. */
    internal fun subSourcePage(pages: JSONArray?, wanted: Wanted) = pages.objects()
        .filter { it.optInt("season") == wanted.season }
        .firstNotNullOfOrNull { Regex("subsource\\.net/subtitles/(.+)/[a-z_]+$").find(it.str("link").orEmpty())?.groupValues?.get(1) }
        ?.replace("season=", "season-")

    /** The listing's path by SubSource's own search: by IMDb id, else (or when it has none by that id) by name. */
    private suspend fun subSourceSearch(wanted: Wanted): String? {
        suspend fun search(query: String): JSONObject {
            val body = JSONObject().put("query", query).put("includeSeasons", true).put("limit", 15).toString()
            return json(SUBSOURCE, Request.Builder().url("$SUBSOURCE_API/movie/search").post(body.toRequestBody("application/json".toMediaType())).build())
        }
        return wanted.imdb?.let { subSourcePath(search(it), wanted, byImdb = true) } ?: subSourcePath(search(wanted.name), wanted, byImdb = false)
    }

    /**
     * The listing's path in a search [answer]: a series for an episode (its season's listing), else
     * a film. By IMDb id the first, as an id finds that title alone; by name one named the same
     * (as TMDB's are matched) within a year, the nearest year first.
     */
    internal fun subSourcePath(answer: JSONObject, wanted: Wanted, byImdb: Boolean): String? {
        /** Years between [result] and what plays, 0 when either has none. */
        fun off(result: JSONObject) = result.optInt("releaseYear").takeIf { it > 0 }?.let { year -> wanted.year?.let { abs(it - year) } } ?: 0
        val kind = answer.optJSONArray("results").objects().filter { (it.str("type") == "tvseries") == wanted.isEpisode }
        val title = (if (byImdb) kind.firstOrNull() else kind
            .filter { it.str("title")?.normalized() == wanted.name.normalized() && off(it) <= 1 }
            .minByOrNull(::off)) ?: return null
        val link = if (!wanted.isEpisode) title.str("link") else title.optJSONArray("seasons").objects().firstOrNull { it.optInt("season") == wanted.season }?.str("link")
        return link?.removePrefix("/subtitles/")?.replace("season=", "season-")
    }

    /**
     * A listing [answer]'s subtitles, each once under the release it lists that fits [wanted] best
     * (its rows are one a release), Persian and English; one whose every release names another
     * episode, as most of a season's do, is left out.
     */
    internal fun subSourceFound(answer: JSONObject, wanted: Wanted) = answer.optJSONArray("subtitles").objects()
        .groupBy { it.optLong("id") }.values.mapNotNull { rows ->
            val row = rows.first()
            Found(
                SUBSOURCE,
                when (row.str("language")) {
                    "farsi_persian" -> "fa"
                    "english" -> "en"
                    else -> return@mapNotNull null
                },
                rows.mapNotNull { it.str("release_info")?.trim() }.mapNotNull { r -> score(r, wanted)?.let { r to it } }.maxByOrNull { it.second }?.first ?: return@mapNotNull null,
                0, // only its page says
                row.optInt("hearing_impaired") == 1,
                row.str("link") ?: return@mapNotNull null,
            )
        }

    private suspend fun subdl(wanted: Wanted): List<Found> {
        val url = "https://api.subdl.com/api/v1/subtitles".toHttpUrl().newBuilder()
            .addQueryParameter("api_key", subdlKey)
            .apply {
                if (wanted.imdb != null) addQueryParameter("imdb_id", wanted.imdb) else {
                    addQueryParameter("film_name", wanted.name)
                    wanted.year?.let { addQueryParameter("year", "$it") }
                }
                addQueryParameter("type", if (wanted.isEpisode) "tv" else "movie")
                if (wanted.isEpisode) {
                    addQueryParameter("season_number", "${wanted.season}")
                    addQueryParameter("episode_number", "${wanted.episode}")
                }
            }
            .addQueryParameter("languages", "FA,EN")
            .addQueryParameter("subs_per_page", "30")
            .build()
        // Nothing found is {status: false, error: "can't find…"}: no subtitles, not a failure.
        return json(SUBDL, Request.Builder().url(url).build()).optJSONArray("subtitles").objects().mapNotNull { item ->
            // A pack whose own range leaves this episode out (a season's other half) is no use.
            val from = item.optInt("episode_from")
            val to = item.optInt("episode_end")
            if (wanted.isEpisode && from > 0 && to >= from && wanted.episode !in from..to) return@mapNotNull null
            Found(
                SUBDL,
                subdlLanguage(item) ?: return@mapNotNull null,
                item.str("release_name") ?: item.str("name") ?: return@mapNotNull null,
                // Not in Bazarr's reading; some clients see one or the other.
                item.optInt("download_count", item.optInt("downloads")),
                item.optBoolean("hi") || item.optInt("hi") == 1,
                item.str("url") ?: return@mapNotNull null,
            )
        }
    }

    /** "fa" or "en": the code Bazarr reads from `language` ("FA"), or the name Cloudstream's clients see ("farsi_persian"). */
    private fun subdlLanguage(item: JSONObject) = listOf("language", "lang").firstNotNullOfOrNull { key ->
        when (item.str(key)?.lowercase()) {
            "fa", "farsi", "persian", "farsi_persian" -> "fa"
            "en", "english" -> "en"
            else -> null
        }
    }

    private suspend fun openSubtitles(wanted: Wanted): List<Found> {
        val imdb = wanted.imdb?.removePrefix("tt")?.trimStart('0')
        // Sorted, lower case and without defaults, as the API asks: otherwise it redirects first.
        val params = sortedMapOf("languages" to "en,fa")
        if (wanted.isEpisode) {
            params["season_number"] = "${wanted.season}"
            params["episode_number"] = "${wanted.episode}"
        }
        when {
            imdb != null -> params[if (wanted.isEpisode) "parent_imdb_id" else "imdb_id"] = imdb
            else -> {
                params["query"] = wanted.name.lowercase()
                params["type"] = if (wanted.isEpisode) "episode" else "movie"
                if (!wanted.isEpisode) wanted.year?.let { params["year"] = "$it" }
            }
        }
        val url = "https://api.opensubtitles.com/api/v1/subtitles".toHttpUrl().newBuilder()
            .apply { for ((key, value) in params) if (key == "languages") addEncodedQueryParameter(key, value) else addQueryParameter(key, value) }
            .build()
        return json(OPENSUBTITLES, openSubtitlesCall(url.toString()).build()).optJSONArray("data").objects().mapNotNull { item ->
            val attributes = item.optJSONObject("attributes") ?: return@mapNotNull null
            // ponytail: a subtitle split over two CDs (two files) is skipped: 30nama's files are whole.
            val file = attributes.optJSONArray("files").objects().singleOrNull() ?: return@mapNotNull null
            Found(
                OPENSUBTITLES,
                attributes.str("language")?.takeIf { it == "fa" || it == "en" } ?: return@mapNotNull null,
                attributes.str("release") ?: file.str("file_name") ?: return@mapNotNull null,
                attributes.optInt("download_count"),
                attributes.optBoolean("hearing_impaired"),
                file.optLong("file_id").takeIf { it > 0 }?.toString() ?: return@mapNotNull null,
            )
        }
    }

    private fun openSubtitlesCall(url: String, key: String = openSubtitlesKey) = Request.Builder().url(url)
        .header("Api-Key", key)
        .header("Accept", "application/json")

    /**
     * Whether SubDL takes [key]: the smallest search there is. True for any answer, nothing found
     * included; false for its 403 (not_authorized); an IOException when it can't say.
     */
    suspend fun checkSubdl(key: String): Boolean {
        val url = "https://api.subdl.com/api/v1/subtitles".toHttpUrl().newBuilder()
            .addQueryParameter("api_key", key)
            .addQueryParameter("film_name", "test")
            .addQueryParameter("subs_per_page", "1")
            .build()
        return check(Request.Builder().url(url).build(), 403)
    }

    /**
     * Whether OpenSubtitles takes [key]: a guess at a file name's parts, the lightest call that
     * checks the key (the /infos lists answer any key, seen live); false for its 403 ("You cannot
     * consume this service") or a 401.
     */
    suspend fun checkOpenSubtitles(key: String): Boolean =
        check(openSubtitlesCall("https://api.opensubtitles.com/api/v1/utilities/guessit?filename=test.mkv", key).build(), 401, 403)

    /** Off the main thread, closing included: an unread HTTP/2 body is cancelled with a write to the socket. */
    internal suspend fun check(request: Request, vararg refused: Int): Boolean = withContext(Dispatchers.IO) {
        Api.run { client.newCall(request.newBuilder().header("User-Agent", AGENT).build()).await() }.use { response ->
            when {
                response.isSuccessful -> true
                response.code in refused -> false
                else -> throw IOException("${response.code}")
            }
        }
    }

    /**
     * [found]'s subtitle, out of its zip and in UTF-8, written to [dir] for the player to read from
     * disk. [episode] picks the right file from a season's zip.
     */
    suspend fun download(found: Found, episode: Int, dir: File): File {
        val (bytes, name) = when (found.source) {
            SUBSOURCE -> {
                // Its page holds the token its file is fetched by.
                val page = json(SUBSOURCE, Request.Builder().url("$SUBSOURCE_API/subtitle/${found.ref}").build())
                val token = page.str("subtitle", "download_token") ?: throw Unreadable()
                bytes(SUBSOURCE, Request.Builder().url("$SUBSOURCE_API/subtitle/download/$token").build()) to found.ref
            }
            SUBDL -> bytes(SUBDL, Request.Builder().url(found.ref.takeIf { it.startsWith("https://") } ?: "https://dl.subdl.com${found.ref}").build()) to found.ref
            else -> {
                val body = JSONObject().put("file_id", found.ref.toLong()).toString().toRequestBody("application/json".toMediaType())
                val link = json(OPENSUBTITLES, openSubtitlesCall("https://api.opensubtitles.com/api/v1/download").post(body).build())
                val url = link.str("link") ?: throw Refused(OPENSUBTITLES, quota = false)
                bytes(OPENSUBTITLES, Request.Builder().url(url).build()) to (link.str("file_name") ?: "subtitle.srt")
            }
        }
        return withContext(Dispatchers.IO) {
            val (file, text) = runCatching { open(bytes, name, episode, found.language) }.getOrElse { throw Unreadable() }
            // A SubSource or SubDL file sent bare, not zipped, is named after its zip or page: SubRip, as nearly all are.
            val type = file.substringAfterLast('.').lowercase().takeIf { it in TYPES } ?: "srt"
            File(dir.apply { mkdirs() }, "${found.source}-${found.ref.hashCode().toUInt()}.$type")
                .apply { writeText(text) }
        }
    }

    /** What ExoPlayer is told a found file is, by its extension. */
    fun mimeType(file: File) = when (file.extension) {
        "vtt" -> MimeTypes.TEXT_VTT
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        else -> MimeTypes.APPLICATION_SUBRIP
    }

    /**
     * A download's subtitle file and its text: the file itself, or out of a zip the one whose name
     * says [episode] (a season's zip holds them all), else its first. Mac zips' `__MACOSX` shadows
     * of each file are no subtitles.
     */
    internal fun open(bytes: ByteArray, name: String, episode: Int, language: String): Pair<String, String> {
        val isZip = bytes.size > 3 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        // Latin-1 names (7.0 and later take a charset): an old zip's Persian file name in some code
        // page must not fail the whole zip, as strict UTF-8 would.
        val stream = bytes.inputStream()
        val files = if (!isZip) listOf(name to bytes) else (if (Build.VERSION.SDK_INT >= 24) ZipInputStream(stream, Charsets.ISO_8859_1) else ZipInputStream(stream)).use { zip ->
            generateSequence { zip.nextEntry }
                .filter { !it.isDirectory && "__MACOSX" !in it.name && it.name.substringAfterLast('.').lowercase() in TYPES }
                .map { it.name to zip.readAtMost(LIMIT) }
                .toList()
        }
        // "S01E05", "1x05", "E05", "Episode 5", «قسمت ۵» spelt with Latin digits; not the 5 in "x265" or "Release 5".
        val names = Regex("(?i)(?<![a-z])(?:e|ep|episode|x|قسمت)[ ._-]*0*$episode(?!\\d)")
        val (file, data) = files.firstOrNull { episode > 0 && names.containsMatchIn(it.first.substringAfterLast('/')) } ?: files.firstOrNull() ?: throw Unreadable()
        return file to decode(data, language)
    }

    /**
     * Subtitle bytes as text. A byte-order mark says UTF-8 or UTF-16; without one, text that reads
     * as UTF-8 is UTF-8, and the rest is the Windows code page of its language: Persian subtitles
     * very often come in Windows-1256. That page has no Persian ی, so its Persian is spelt with the
     * Arabic ي (and Arabic-keyboard typing adds ك): both read wrong, and are put right ([persianised]).
     */
    internal fun decode(bytes: ByteArray, language: String): String {
        fun starts(vararg prefix: Int) = bytes.size >= prefix.size && prefix.indices.all { bytes[it] == prefix[it].toByte() }
        val text = when {
            starts(0xEF, 0xBB, 0xBF) -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            starts(0xFF, 0xFE) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            starts(0xFE, 0xFF) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            // newDecoder() reports a malformed byte where String() would quietly replace it.
            else -> runCatching { Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString() }
                .getOrElse { String(bytes, Charset.forName(if (language == "fa") "windows-1256" else "windows-1252")) }
        }
        return persianised(text)
    }

    /** Letters Persian has and Arabic lacks: a subtitle with any of them is Persian, not Arabic. */
    private const val PERSIAN = "پچژگ"
    /**
     * Marks that take their side from the line's direction. Not the Arabic ؟: it belongs to the
     * script and stays with its sentence either way.
     */
    private const val CLOSERS = ".!?…:"

    private fun letters(text: String) = String(CharArray(text.length) { i ->
        when (val c = text[i]) {
            'ي', 'ى' -> 'ی'
            'ك' -> 'ک'
            in '٠'..'٩' -> c + ('۰' - '٠')
            else -> c
        }
    })

    /**
     * [text], a whole subtitle, with the Arabic ي and ى as the Persian ی, the Arabic ك as ک and
     * the Arabic-Indic digits of an Arabic keyboard as Persian ones (its ٤ ٥ ٦ are drawn otherwise),
     * where it is Persian ([PERSIAN]). Character by character, so a yeh before a ZWNJ («مي‌دوني»)
     * goes too.
     */
    internal fun persianised(text: String) = if (text.any { it in PERSIAN }) letters(text) else text

    /** Whether [line] has a right-to-left letter: Hebrew, Arabic, or their presentation forms. */
    private fun rightToLeft(line: String) = line.any { it in '\u0590'..'\u08FF' || it in '\uFB1D'..'\uFDFF' || it in '\uFE70'..'\uFEFC' }

    /** Whether [line] has a left-to-right letter (Latin, mostly): not a digit or a mark, which take a side. */
    private fun latin(line: String) = line.any { Character.getDirectionality(it) == Character.DIRECTIONALITY_LEFT_TO_RIGHT }

    /** [line]'s runs of [CLOSERS] at its start and its end, spaces and bidi controls (an RLM, a file's own isolates) aside. */
    private fun marks(line: String): Pair<String, String> {
        val bare = line.trim { it.isWhitespace() || Character.getType(it) == Character.FORMAT.toInt() }
        return bare.takeWhile { it in CLOSERS } to bare.takeLastWhile { it in CLOSERS }
    }

    /** Whether [run] ends a sentence: not an ellipsis, which opens a continuation as well. */
    private fun stop(run: String) = run.isNotEmpty() && '…' !in run && ".." !in run

    /**
     * A whole subtitle's cue texts with its Persian put right: its letters as [persianised], and
     * its lines' direction. Many Persian files were written for players that lay every line out left
     * to right, so the full stop is typed first (".سلام") to land at the left end, where a Persian
     * sentence finishes; drawn right to left, as the player draws Persian, it jumps to the start.
     * More of a file's lines opening on such a mark than closing on one says it is one of those, and
     * each of its right-to-left lines is then held left to right in an isolate, as it was written.
     * A file written right is left as it is. A line whose own stop says otherwise goes its own way,
     * as in a file typed by two hands: opening on a stop and not closing on one, it was typed left
     * to right; the other way round, right to left. Either way a line with no right-to-left letter but a
     * Latin one ("OK.", a song's name) is held left to right as well: the player lays a Persian
     * track's lines out right to left, which would put its full stop before it. A file with no
     * right-to-left line, an English one, is left alone. After persianised() and directed() in the
     * Mac client's Core/Subtitles.swift (github.com/thesaikodelik/30nama-apps, MIT).
     */
    internal fun cleaned(texts: List<String>): List<String> {
        val persian = texts.any { text -> text.any { it in PERSIAN } }
        val lines = texts.flatMap { it.split('\n') }.filter(::rightToLeft)
        if (lines.isEmpty()) return texts
        var opening = 0
        var closing = 0
        for (line in lines) {
            val (open, close) = marks(line)
            if (open.isNotEmpty()) opening++
            if (close.isNotEmpty()) closing++
        }
        val leftToRight = opening > closing
        return texts.map { text ->
            val lettered = if (persian) letters(text) else text
            lettered.split('\n').joinToString("\n") { line ->
                val (open, close) = marks(line)
                val held = when {
                    !rightToLeft(line) -> latin(line)
                    stop(open) != stop(close) -> stop(open)
                    else -> leftToRight
                }
                if (held) "\u2066$line\u2069" else line
            }
        }
    }

    /**
     * Media3's subtitle parsers, each file's cues [cleaned] together: ExoPlayer hands a side-loaded
     * subtitle to its parser whole (its SubtitleExtractor reads to the end first), so a file's habits
     * are judged on all of it. An unchanged cue keeps its styling; a changed one is plain text, as
     * the player draws every cue anyway.
     */
    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    class Parsers(private val parsers: SubtitleParser.Factory = DefaultSubtitleParserFactory()) : SubtitleParser.Factory by parsers {
        override fun create(format: Format): SubtitleParser {
            val parser = parsers.create(format)
            return object : SubtitleParser {
                override fun parse(data: ByteArray, offset: Int, length: Int, outputOptions: SubtitleParser.OutputOptions, output: Consumer<CuesWithTiming>) {
                    val timed = ArrayList<CuesWithTiming>()
                    parser.parse(data, offset, length, outputOptions) { timed.add(it) }
                    val texts = cleaned(timed.flatMap { t -> t.cues.mapNotNull { it.text?.toString() } }).iterator()
                    for (t in timed) output.accept(CuesWithTiming(t.cues.map { cue ->
                        val text = cue.text?.toString() ?: return@map cue
                        val clean = texts.next()
                        if (clean == text) cue else cue.buildUpon().setText(clean).build()
                    }, t.startTimeUs, t.durationUs))
                }

                override fun getCueReplacementBehavior() = parser.cueReplacementBehavior

                override fun reset() = parser.reset()
            }
        }
    }

    private suspend fun json(source: String, request: Request): JSONObject {
        val bytes = bytes(source, request)
        // A page in place of JSON (a challenge, an error page) is the source not answering.
        return runCatching { JSONObject(String(bytes)) }.getOrElse { throw Refused(source, quota = false) }
    }

    /**
     * The body, or why not: [Refused] for the source's own no (406 and 429 its allowance), a plain
     * IOException for the network. The sites ask for a User-Agent that names the app.
     */
    private suspend fun bytes(source: String, request: Request): ByteArray =
        Api.run { client.newCall(request.newBuilder().header("User-Agent", AGENT).build()).await() }.use { response ->
            when {
                response.isSuccessful -> withContext(Dispatchers.IO) { response.body!!.byteStream().readAtMost(LIMIT) }
                response.code == 406 || response.code == 429 -> throw Refused(source, quota = true)
                else -> throw Refused(source, quota = false)
            }
        }

    private fun InputStream.readAtMost(limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = read(buffer)
            if (read < 0) return out.toByteArray()
            out.write(buffer, 0, read)
            if (out.size() > limit) throw Unreadable()
        }
    }
}
