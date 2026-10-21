package com.doxigo.tv30

import android.content.Context
import androidx.core.text.HtmlCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

// ---- JSON: the API nests everything and uses null, "" and missing interchangeably. ----

fun JSONObject.obj(vararg path: String): JSONObject? {
    var o: JSONObject? = this
    for (p in path) o = o?.optJSONObject(p)
    return o
}

/** The string at [path], or null when it is missing, JSON null or blank (org.json would say "null"). */
fun JSONObject.str(vararg path: String): String? {
    val v = obj(*path.dropLast(1).toTypedArray())?.opt(path.last())
    return when (v) {
        is String -> v.takeIf { it.isNotBlank() }
        is Number -> v.toString()
        else -> null
    }
}

fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else List(length()) { optJSONObject(it) }.filterNotNull()

// ---- Titles, as every list endpoint returns them. ----

val JSONObject.titleId get() = optInt("id")
// The English name ends in the year ("Black Clover 2017"); the official app trims it and shows the year apart.
val JSONObject.englishName get() = (str("title", "english") ?: str("title", "local").orEmpty()).withoutYear(str("info", "year")).unescaped()
/**
 * Now and then the year comes first ("2019 1917", the film 1917), so a leading [year] goes before
 * any trailing four digits. Not [year] alone at the end: it can differ by one ("The Forsytes 2026", 2025).
 */
private fun String.withoutYear(year: String?) =
    if (year != null && startsWith("$year ")) removePrefix("$year ") else replace(Regex("\\s+\\d{4}$"), "")
/** The Persian name, when 30nama has one that says more than the English. */
val JSONObject.localName get() = str("title", "local")?.unescaped()?.takeIf { it != englishName }
val JSONObject.poster get() = str("image", "poster", "webp", "large") ?: str("image", "poster", "jpg", "large")
val JSONObject.cover get() = str("image", "cover", "webp") ?: str("image", "cover", "jpg")
/**
 * Of a title, what the player's controls show beside its name (poster, score, year), and 30nama's
 * links to SubSource for Find subtitles: small enough for an intent.
 */
val JSONObject.playerFacts get() = JSONObject().also { facts -> for (key in arrayOf("title", "image", "info", "score", "options", "subtitle")) opt(key)?.let { facts.put(key, it) } }
val JSONObject.isFree get() = obj("options")?.optBoolean("free_stream") == true
/** The official app's rule (module 10223): a free title plays for anyone, the rest for a signed-in plan that streams. */
val JSONObject.streamable get() = Api.loggedIn && Api.user?.optBoolean("user_allowed_to_stream") == true || isFree
/** Persian subtitles, 30nama's own (exclusive) or not. */
val JSONObject.subtitled get() = obj("options")?.run { optBoolean("persian_subtitle") || optBoolean("exclusive_subtitle") } == true
val JSONObject.isMovie get() = str("options", "title_type") == "movie"
/** A score out of 10 from [source] ("imdb", "myanimelist", "mydramalist"); an unrated title says 0. */
fun JSONObject.score(source: String) = obj("score", source)?.optDouble("score")?.takeIf { it > 0 }
val JSONObject.imdb get() = score("imdb")
val JSONObject.year get() = str("info", "year")?.localDigits()
val JSONObject.plot get() = (if (Api.language == "fa") str("plot", "local") ?: str("plot", "english") else str("plot", "english") ?: str("plot", "local"))?.unescaped()?.trailed()

/** The server cuts plots short with its own ".." or "..." («درگ..»); the cut reads as one ellipsis, «درگ…». */
fun String.trailed(): String = replace(TRAILING_DOTS, "…")
private val TRAILING_DOTS = Regex("\\s*[.…]{2,}\\s*$")

/** Names and plots arrive HTML-escaped now and then ("Attorneys&#039; office"). */
fun String.unescaped(): String = if ('&' in this) HtmlCompat.fromHtml(this, HtmlCompat.FROM_HTML_MODE_LEGACY).toString() else this

/** The server writes episodes as "فصل 1 - قسمت 4"; Persian wants «فصل ۱، قسمت ۴», English "Season 1, Episode 4". */
fun String.episodeLabel(): String = localDigits().replace(" - ", if (Api.language == "fa") "، " else ", ")

/**
 * Persian digits inside Persian text, as Persian orthography wants. For numbers the server spells
 * in Latin ("فصل 1 - قسمت 4", "00:33:00"); film names and version strings are left alone.
 */
fun String.localDigits(): String = if (Api.language != "fa") this else persianDigits()

fun String.persianDigits(): String = String(CharArray(length) { i -> this[i].let { if (it in '0'..'9') '۰' + (it - '0') else it } })

/**
 * A count with its thousands grouped as the language writes them: «۸۴٬۸۰۶» with Persian's own
 * separator (U+066C), "84,806" in English. Not the locale's %,d, which pairs Persian digits with a
 * Latin comma («۸۴,۸۰۶»).
 */
fun Int.grouped(persian: Boolean = Api.language == "fa"): String =
    "%,d".format(Locale.US, this).let { if (persian) it.replace(',', '٬').persianDigits() else it }

/**
 * A continue poster's caption: what Meta's timeLeft says, short enough to keep its verb under a
 * poster. Persian counts the minutes alone, «۸۹ دقیقه مانده», where «۱ ساعت و ۲۹ دقیقه مانده» lost
 * «مانده» to the ellipsis; English already says "1h 29m left". The billboard keeps the long form.
 */
fun JSONObject.shortTimeLeft(context: Context): String? {
    if (Api.language != "fa") return timeLeft(context)
    // timeLeft's own reckoning: data.time (s) and data.progress (%), else a film's length; whole minutes, rounded up.
    val time = obj("data")?.optLong("time") ?: return null
    val progress = str("data", "progress")?.toDoubleOrNull()
    val seconds = (if (progress != null) secondsLeft(time, progress)
    else obj("info", "time")?.optInt("default")?.takeIf { isMovie && it > 0 }?.let { it * 60L - time }?.takeIf { it > 0 }) ?: return null
    val minutes = maxOf(1, ((seconds + 59) / 60).toInt())
    return context.getString(R.string.time_left, context.getString(R.string.runtime_minutes, minutes))
}
