package com.doxigo.tv30

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.LineHeightSpan
import android.text.style.MetricAffectingSpan
import android.text.style.ReplacementSpan
import androidx.annotation.DrawableRes
import androidx.annotation.FontRes
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The hero's facts, set as Netflix and Apple TV set them rather than as a row of bullets: the
 * category in a quiet chip, [lead] (where a continue card left off), the score beside its
 * source's mark, the year, the age rating in an outlined chip, then the length. The chips, the
 * weights and the space between the parts already set them apart. A continue card's [left] (time
 * still to watch) follows its lead in white. The title's own [page] has room for every score (one
 * badge, the rest as words) and the Persian-subtitles chip.
 */
fun JSONObject.meta(context: Context, lead: String? = null, left: String? = null, page: Boolean = false, critics: Boolean = true): CharSequence {
    val minutes = obj("info", "time")?.optInt("default")?.takeIf { it > 0 }?.let { runtime(context, it) }
    val seasons = obj("info")?.optInt("seasons")?.takeIf { it > 0 && !isMovie }?.let { context.resources.getQuantityString(R.plurals.seasons, it, it) }
    return metaLine(
        context,
        category(context),
        lead?.tinted(ContextCompat.getColor(context, R.color.text)),
        left?.tinted(ContextCompat.getColor(context, R.color.text)),
        *(if (page) scores(context, critics) else listOf(rating(context))).toTypedArray(),
        if (page) years else year,
        str("info", "age")?.let { outlined(context, it) },
        // A continue card's lead already names the season, and its time left says the rest: no length.
        (seasons ?: minutes).takeIf { lead == null && left == null },
        context.getString(R.string.free).takeIf { isFree }?.tinted(ContextCompat.getColor(context, R.color.green)),
        // Solid, not outlined: a feature rather than a rating, and it must read over any frame of art.
        context.getString(R.string.persian_subtitle).takeIf { page && subtitled }
            ?.let { chip(context, it, ContextCompat.getColor(context, R.color.text_body), fill = ContextCompat.getColor(context, R.color.surface_high)) },
    )
}

/**
 * The player's line under the title's names, set as home's billboard sets a continue card's: what
 * plays leading in white (the [episode] and its [name], when 30nama has a real one), then the score
 * beside its source's mark and the [year]. No age rating: mid-film it is noise (the user's call). The
 * length and the end are the clock's, at the bar's end.
 */
fun JSONObject.playerMeta(context: Context, episode: String?, name: String?, year: String?): CharSequence {
    val white = ContextCompat.getColor(context, R.color.text)
    return metaLine(context, episode?.tinted(white), name?.tinted(white), rating(context), year)
}

/** What the title is, from options.title_type, in a chip; nothing for a type with no name here. */
fun JSONObject.category(context: Context): CharSequence? {
    val name = when (str("options", "title_type")) {
        "movie" -> R.string.category_movie
        "series" -> R.string.category_series
        "anime" -> R.string.category_anime
        "miniseries" -> R.string.category_miniseries
        else -> return null
    }
    return chip(context, context.getString(name), Color.WHITE, fill = ContextCompat.getColor(context, R.color.hero_chip))
}

/** A spec in an outlined chip, as Netflix sets the maturity rating: the age rating. */
private fun outlined(context: Context, word: String): CharSequence {
    val dim = ContextCompat.getColor(context, R.color.text_dim)
    return chip(context, word, dim, line = dim)
}

private fun chip(context: Context, word: String, ink: Int, fill: Int = 0, line: Int = 0, pad: Float = 0.35f) =
    SpannableString(word).spanned(Chip(context, word, ink, fill, line, pad))

/**
 * The one score a poster or home's billboard has room for: IMDb's, else MyAnimeList's or
 * MyDramaList's. 30nama's own members' score is never shown (the user's call). A [bare] one is a
 * poster's, see [facts].
 */
fun JSONObject.rating(context: Context, bare: Boolean = false): CharSequence? =
    imdbScore(context, bare = bare) ?: fanScore(context, bare = bare)

/**
 * Every score the title has, for its own page: the one [rating] picks in its source's badge, the
 * rest as quiet words, so a single mark leads the line instead of three coloured ones competing.
 * The critics' follow, each its site's glyph in the line's grey: Rotten Tomatoes' share, «۹۴٪», and
 * Metacritic's score out of 100, «۷۵».
 */
private fun JSONObject.scores(context: Context, critics: Boolean): List<CharSequence> {
    var quiet = false
    return listOf<(Boolean) -> CharSequence?>({ imdbScore(context, it) }, { fanScore(context, it) })
        .mapNotNull { score -> score(quiet)?.also { quiet = true } } +
        if (critics) listOfNotNull(
            critics(context, R.string.score_rotten_tomatoes, R.drawable.meta_tomato, "rottentomatoes", "%"),
            critics(context, R.string.score_metacritic, R.drawable.meta_metacritic, "metacritic", ""),
        ) else emptyList()
}

/**
 * A critics' score out of 100 beside its site's [glyph], in the line's digits (Persian's percent is
 * its own «٪»). The glyph stands in for the site's [name] in the UI's language, which TalkBack reads
 * and which sets the mark first in either reading order.
 */
private fun JSONObject.critics(context: Context, name: Int, glyph: Int, source: String, unit: String): CharSequence? = score(source)?.let {
    val number = "${it.roundToInt()}$unit".let { n -> if (Api.language == "fa") n.persianDigits().replace('%', '٪') else n }
    SpannableStringBuilder().append(context.getString(name), Glyph(context, glyph), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        .append(" ", Gap(0.3f), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE).append(number)
}

private fun JSONObject.imdbScore(context: Context, quiet: Boolean = false, bare: Boolean = false) =
    imdb?.let { scored(context, "IMDb", ContextCompat.getColor(context, R.color.imdb), Color.BLACK, it, quiet, bare) }

/** Anime carry MyAnimeList's score and Asian dramas MyDramaList's, each in its site's blue. */
private fun JSONObject.fanScore(context: Context, quiet: Boolean = false, bare: Boolean = false) =
    score("myanimelist")?.let { scored(context, "MAL", MAL_BLUE, Color.WHITE, it, quiet, bare) }
        ?: score("mydramalist")?.let { scored(context, "MDL", MDL_BLUE, Color.WHITE, it, quiet, bare) }

/**
 * A source's [mark] in its own colours, the score beside it in white bold: «[IMDb] ۷٫۷». A [Gap]
 * between them rather than a word space, whose width is whichever face the space falls to. A
 * [quiet] one is plain words in the line's own grey, «MAL ۸٫۷», as the year is. A [bare] one is
 * the mark's word alone in its colour, no box, at the badge's word size: a poster's, where a
 * filled badge under every card of a row read louder than the titles above them. A blue too dark
 * to read on ink is lifted toward white; IMDb's yellow reads as it is.
 */
private fun scored(context: Context, mark: String, fill: Int, ink: Int, value: Double, quiet: Boolean = false, bare: Boolean = false): CharSequence {
    val number = "%.1f".format(Locale.getDefault(), value)
    if (quiet) return SpannableStringBuilder(mark).append(" ", Gap(0.3f), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE).append(number)
    val face = context.font(if (number.isLatin) R.font.inter_bold else R.font.vazirmatn_bold)
    val badge = if (!bare) chip(context, mark, ink, fill = fill)
        else chip(context, mark, if (ColorUtils.calculateLuminance(fill) < 0.3) ColorUtils.blendARGB(fill, Color.WHITE, 0.45f) else fill, pad = 0f)
    return SpannableStringBuilder(badge)
        .append(" ", Gap(if (bare) 0.25f else 0.3f), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        .append(SpannableString(number).spanned(Face(face)).spanned(ForegroundColorSpan(Color.WHITE)))
}

// The sites' own blues, as IMDb's mark keeps its yellow.
private const val MAL_BLUE = 0xFF2E51A2.toInt()
private const val MDL_BLUE = 0xFF1675B6.toInt()

/**
 * What every poster says under its art, by the user's rule: the score, then the year. The score's
 * mark is [bare], which also gives an English caption the room a filled badge took: «IMDb 7.6
 * 2005» fits a 108dp poster's 104dp caption whole, as Persian's narrower digits already did.
 */
fun JSONObject.facts(context: Context): CharSequence = metaLine(context, rating(context, bare = true), year, gap = 0.6f)

/**
 * When an airing series' next episode comes, while that is still ahead: «قسمت بعد: شنبه ۲۵ مهر»,
 * "Next episode: Saturday, October 17". The day is the server's, Jalali in Persian and Gregorian
 * in English. Its caption joins in only when it names the episode; it mostly says «قسمت بعدی».
 */
fun JSONObject.nextEpisode(context: Context): String? {
    val date = obj("air", "next_episode_date") ?: return null
    // "17 October 2026 Saturday": parse() stops after the year.
    val day = date.str("g")?.let { runCatching { SimpleDateFormat("d MMMM yyyy", Locale.ENGLISH).parse(it) }.getOrNull() } ?: return null
    val today = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    if (day.before(today.time)) return null
    val words = if (Api.language == "fa") {
        // "۲۵ مهر ۱۴۰۵ شنبه": the day and month, after the weekday as Android writes it («سه‌شنبه», not «سه شنبه»).
        val jalali = date.str("j")?.split(' ')?.take(2)?.joinToString(" ")?.localDigits() ?: return null
        SimpleDateFormat("EEEE", Locale.forLanguageTag("fa")).format(day) + " " + jalali
    } else {
        SimpleDateFormat("EEEE, MMMM d", Locale.ENGLISH).format(day)
    }
    val episode = str("air", "next_episode_caption")?.takeIf { caption -> caption.any(Char::isDigit) }?.episodeLabel()
    return context.getString(R.string.next_air, dotted(episode, words))
}

/**
 * The title page's second line, in two groups told apart by more than space. What it is: the
 * [genres], in the line's own grey (Inter Medium in English). Where it is from: a [Glyph] globe,
 * then the countries and the language, a step quieter (meta_origin; Inter Regular). A [Rule]
 * between them, only when both are there. Null when the title has neither.
 */
fun JSONObject.genresLine(context: Context, genres: String): CharSequence? {
    val tone = ContextCompat.getColor(context, R.color.meta_origin)
    val where = dotted(*countries().toTypedArray(), spoken()).ifEmpty { null }?.let {
        SpannableStringBuilder()
            .append(" ", Glyph(context, R.drawable.meta_globe), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            .append(" ", Gap(0.35f), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            .append(latin(context, it, R.font.inter_regular)) // before metaLine's Medium, which then leaves it be
            .tinted(tone)
    }
    // "|", not a space: metaLine drops a blank part. The span draws it.
    val rule = SpannableString("|").spanned(Rule(context))
    return metaLine(context, *between(genres.ifEmpty { null }, where, rule).toTypedArray(), gap = 0.75f).ifEmpty { null }
}

/** [what] and [where], with [divider] between them only when both are there: never one at an end. */
internal fun <T : Any> between(what: T?, where: T?, divider: T): List<T> =
    if (what != null && where != null) listOf(what, divider, where) else listOfNotNull(what, where)

/** Up to two countries the title is from, named in the UI's language: «ایالات متحده», «ژاپن». */
fun JSONObject.countries(): List<String> =
    optJSONArray("country").objects().take(2).mapNotNull { it.named { code -> Locale.forLanguageTag("und-$code").getDisplayCountry(ui) } }

/** The language it is spoken in: «انگلیسی». */
fun JSONObject.spoken(): String? = obj("language", "primary")?.named { code -> Locale.forLanguageTag(code).getDisplayLanguage(ui) }

private val ui get() = Locale.forLanguageTag(Api.language)

/** By its code in the UI's own words (Android knows them in Persian), else as the server names it (in English). */
private fun JSONObject.named(byCode: (String) -> String): String? {
    val code = str("code")
    return code?.let(byCode)?.takeIf { it.isNotBlank() && !it.equals(code, ignoreCase = true) } ?: str("name")
}

/** Seconds still to watch, from how far in ([time] s) and what share of the whole that is ([progress] %); null outside (0, 100). */
fun secondsLeft(time: Long, progress: Double): Long? =
    if (progress <= 0 || progress >= 100) null else (time * (100 / progress - 1)).roundToLong()

/**
 * What a continue card has left, in whole minutes rounded up: «۸ دقیقه مانده», «۱ ساعت و ۵ دقیقه
 * مانده». From data.time (s) and data.progress (%); a film saved without progress goes by its length.
 */
fun JSONObject.timeLeft(context: Context): String? {
    val time = obj("data")?.optLong("time") ?: return null
    val progress = str("data", "progress")?.toDoubleOrNull()
    val seconds = if (progress != null) secondsLeft(time, progress)
    else obj("info", "time")?.optInt("default")?.takeIf { isMovie && it > 0 }?.let { it * 60L - time }?.takeIf { it > 0 }
    return seconds?.let { context.getString(R.string.time_left, runtime(context, maxOf(1, ((it + 59) / 60).toInt()))) }
}

/** A film's length as people say it: «۲ ساعت و ۳۵ دقیقه», "2h 35m". */
private fun runtime(context: Context, minutes: Int): String {
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours == 0 -> context.getString(R.string.runtime_minutes, rest)
        rest == 0 -> context.getString(R.string.runtime_hours, hours)
        else -> context.getString(R.string.runtime_hours_minutes, hours, rest)
    }
}

/**
 * Facts set apart by space ([gap] ems), each fenced (see [fence]), with their Latin runs in
 * Inter: the line's own Yekan Bakh draws Latin from Lato, which reads as a second, weaker face.
 * Its box is set by [Leading].
 */
fun metaLine(context: Context, vararg parts: CharSequence?, gap: Float = 0.8f): CharSequence {
    val line = SpannableStringBuilder()
    for (part in parts) {
        if (part.isNullOrBlank()) continue
        if (line.isNotEmpty()) line.append(" ", Gap(gap), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        line.append(fence(part))
    }
    if (line.isNotEmpty()) line.setSpan(Leading(Digits(context)), 0, line.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
    return latin(context, line, R.font.inter_medium)
}

/** [metaLine] with a colour per part (null keeps the line's own). */
fun metaLine(context: Context, vararg parts: Pair<CharSequence?, Int?>): CharSequence =
    metaLine(context, *parts.map { (text, color) -> color?.let { text?.tinted(ContextCompat.getColor(context, it)) } ?: text }.toTypedArray())

/**
 * A tile caption's parts, fenced (see [fence]) and joined as a list is in prose: «اشتراک قرمز، ۲۴ روز
 * مانده». Not a bullet: in a narrow caption "•" beside Persian digits reads as a zero («۲۴ •» → «۲۴۰»).
 */
fun dotted(vararg parts: String?): String =
    parts.filterNotNull().filter { it.isNotBlank() }.joinToString(if (Api.language == "fa") "، " else ", ") { fence(it) }

/**
 * Fences one part of a dotted line with marks of the UI's own direction (RLM in Persian), so a
 * Latin part ("IMDb ۷٫۷", "TV-MA") keeps its place among Persian ones. Marks, not isolates or
 * BidiFormatter's embeddings: those nest the part a bidi level deeper, and Android's line
 * reordering mis-orders runs once a line nests that deep after another reversed run (it drew
 * «IMDb ۷٫۷» as «٫۷IMDb ۷»). With marks no line goes past two levels, where nothing reorders.
 */
fun fence(text: CharSequence): CharSequence {
    val mark = if (Api.language == "fa") "‏" else "‎"
    return SpannableStringBuilder(mark).append(text).append(mark)
}

// ---- Latin in its own face. Peyda's and Yekan Bakh's Latin are afterthoughts (Peyda's word space
// ---- runs "Presumed Innocent" together), so each caller supplies its Latin face.

fun Context.font(@FontRes id: Int): Typeface? = ResourcesCompat.getFont(this, id) // cached by the framework

/** No Persian letter in it: an English name or plot can take its Latin face whole. */
val CharSequence.isLatin get() = none { it in '؀'..'ۿ' }

// Latin words and the spaces, punctuation and direction marks (see [fence]) between them, so a
// run's word spaces, the commas of a fenced list («Adventure, Drama») and a series' years'
// dash ("2004–2012") are Inter's too.
internal val LATIN_RUN = Regex("[A-Za-z0-9À-ɏ]+(?:[ .,:;'’&!?()\\-–\u200E\u200F]+[A-Za-z0-9À-ɏ]+)*")

/** [text] with each Latin run set in [face], around whatever already sets its own (a chip, a score, a gap). */
fun latin(context: Context, text: CharSequence, @FontRes face: Int): CharSequence {
    val runs = LATIN_RUN.findAll(text).toList()
    if (runs.isEmpty()) return text
    val typeface = context.font(face)
    return SpannableString(text).apply {
        for (run in runs) {
            var at = run.range.first
            val end = run.range.last + 1
            for (span in getSpans(at, end, MetricAffectingSpan::class.java).sortedBy(::getSpanStart)) {
                if (getSpanStart(span) > at) setSpan(Face(typeface), at, getSpanStart(span), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                at = maxOf(at, getSpanEnd(span))
            }
            if (at < end) setSpan(Face(typeface), at, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}

/**
 * A run set in [face], with that face's own word space rather than the one the line's Persian face
 * was widened to (TextSpacing.kt). TypefaceSpan(Typeface) would do, but needs API 28.
 */
class Face(private val face: Typeface?) : MetricAffectingSpan() {
    override fun updateMeasureState(paint: TextPaint) {
        paint.typeface = face
        paint.fitSpaces()
    }

    override fun updateDrawState(paint: TextPaint) {
        paint.typeface = face
        paint.fitSpaces()
    }
}

private fun CharSequence.tinted(color: Int) = SpannableString(this).spanned(ForegroundColorSpan(color))
private fun SpannableString.spanned(span: Any) = apply { setSpan(span, 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }

/** Space of [em] times the text size between two facts: wider than a word space, the same in every face. */
private class Gap(private val em: Float) : ReplacementSpan() {
    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        fm?.let(paint::getFontMetricsInt) // the layout hands a replacement whatever metrics it last held
        return (paint.textSize * em).roundToInt()
    }
    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) = Unit
}

/**
 * A glyph in the line's own ink, where-from's globe (meta_globe.xml) or a critics' mark (meta_tomato,
 * meta_metacritic), drawn 1 em square so its 1.8/24 stroke is the text's own stem, and centred on
 * [Digits]' middle as the chips are: an ImageSpan would sit it on the baseline, low beside Persian.
 * It takes 0.8 em of the line, the globe's circle's 0.74 and a hair: the drawable's margin
 * overhangs, so the space either side is the line's [Gap]s. The text it stands for is what TalkBack reads.
 */
private class Glyph(context: Context, @DrawableRes id: Int) : ReplacementSpan() {
    private val icon = ContextCompat.getDrawable(context, id)!!.mutate()
    private val digits = Digits(context)

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        fm?.let(paint::getFontMetricsInt)
        return (paint.textSize * 0.8f).roundToInt()
    }

    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val size = paint.textSize.roundToInt()
        val left = (x + (getSize(paint, text, start, end, null) - size) / 2f).roundToInt()
        val upper = (y + digits.middle(paint.textSize) - size / 2f).roundToInt()
        icon.setBounds(left, upper, left + size, upper + size)
        icon.setTint(paint.color)
        icon.draw(canvas)
    }
}

/** The hairline between a line's two groups: 1 dp on whole pixels, 0.9 em tall on [Digits]' middle. */
private class Rule(context: Context) : ReplacementSpan() {
    private val ink = Paint().apply { color = ContextCompat.getColor(context, R.color.meta_rule) }
    private val width = maxOf(1, context.resources.displayMetrics.density.roundToInt())
    private val digits = Digits(context)

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        fm?.let(paint::getFontMetricsInt)
        return width
    }

    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val left = x.roundToInt().toFloat()
        val middle = y + digits.middle(paint.textSize)
        val half = paint.textSize * 0.45f
        canvas.drawRect(left, (middle - half).roundToInt().toFloat(), left + width, (middle + half).roundToInt().toFloat(), ink)
    }
}

/**
 * Where a meta line's eye rests: the middle of its digits, since every chip stands beside a score
 * or the year. Measured in the face the numbers are set in (Yekan Bakh's Persian digits run 0.06 em
 * under the baseline to 0.53 over it, Inter's roughly -0.01 to 0.74), not read off the font box: Yekan
 * Bakh's 0.9/0.6 em box centres 0.1 em under its digits.
 */
private class Digits(context: Context) {
    private val digits = "0123456789".localDigits()
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = context.font(if (Api.language == "fa") R.font.vazirmatn_regular else R.font.inter_medium)
    }
    private val bounds = Rect()
    private var size = 0f
    private var middle = 0f

    /** The digits' middle at [textSize], in px from the baseline (negative: above it). */
    fun middle(textSize: Float): Float {
        if (textSize != size) {
            size = textSize
            paint.textSize = textSize
            paint.getTextBounds(digits, 0, digits.length, bounds)
            middle = bounds.exactCenterY()
        }
        return middle
    }
}

/**
 * A meta line's box: Yekan Bakh's own 1.5 em, whatever faces the line mixes (Inter's taller
 * ascent would grow it), with its middle on [Digits]' rather than the font box's, so a line of
 * chips and numbers sits centred in its view. A span because a TextAppearance can't carry a line
 * height: the platform reads lineHeight only off the view itself, and from API 28.
 */
private class Leading(private val digits: Digits) : LineHeightSpan.WithDensity {
    override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, lineHeight: Int, fm: Paint.FontMetricsInt, paint: TextPaint) {
        val height = (paint.textSize * 1.5f).roundToInt()
        fm.ascent = (digits.middle(paint.textSize) - height / 2f).roundToInt()
        fm.descent = fm.ascent + height
        fm.top = fm.ascent // and font padding adds nothing
        fm.bottom = fm.descent
    }

    // StaticLayout always calls the one with the paint.
    override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, lineHeight: Int, fm: Paint.FontMetricsInt) = Unit
}

/**
 * A word in a box on the line, drawn over the word itself: IMDb's yellow mark, the age rating's
 * outline, the category's quiet fill. The word stays in the text for screen readers. One spec for
 * every chip, in ems of the line's text: 1.25 tall, its word at 0.8, [pad] at either side (0.35;
 * a poster's bare score mark takes none, see [scored]), corners a fifth of the height, the box on
 * whole pixels and centred on the line's [Digits], so every chip and the score or year beside it
 * share one middle. The word centres in the box by
 * its own glyphs, not its font box (Yekan Bakh's would set a Persian word 0.15 em high): a Latin
 * word by its capitals, a Persian one by its teeth, the x-height zone where its letters join.
 */
class Chip(context: Context, word: String, private val ink: Int, private val fill: Int = 0, private val line: Int = 0, private val pad: Float = 0.35f) : ReplacementSpan() {
    private val face = context.font(if (word.isLatin) R.font.inter_bold else R.font.vazirmatn_bold)
    private val zone = if (word.isLatin) "H" else "ـسـ"
    private val digits = Digits(context)
    private val label = TextPaint()
    private val box = RectF()
    private val bounds = Rect()
    // For the line's text size [size]: the box's top and bottom from the baseline, its width, the
    // word's baseline from the line's, and the outline's width.
    private var size = 0f
    private var top = 0
    private var bottom = 0
    private var width = 0
    private var baseline = 0f
    private var stroke = 0f

    private fun fit(paint: Paint, text: CharSequence, start: Int, end: Int) {
        if (paint.textSize == size) return
        size = paint.textSize
        label.set(paint)
        label.typeface = face
        label.textSize = size * 0.8f
        label.fitSpaces() // the chip's own face, not the line's widened space
        val height = (size * 1.25f).roundToInt()
        top = (digits.middle(size) - height / 2f).roundToInt()
        bottom = top + height
        label.getTextBounds(zone, 0, zone.length, bounds)
        baseline = (top + bottom) / 2f - bounds.exactCenterY()
        width = (label.measureText(text, start, end) + size * pad * 2).roundToInt()
        // About 1dp on the meta line, whole pixels: a hairline would shimmer on a TV panel.
        stroke = maxOf(1, (size * 0.07f).roundToInt()).toFloat()
    }

    /** Reports the line's own metrics, grown only if the box would pass them, so a chip never clips nor stretches its line. */
    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        fit(paint, text, start, end)
        if (fm != null) {
            paint.getFontMetricsInt(fm)
            fm.ascent = minOf(fm.ascent, top)
            fm.top = minOf(fm.top, top)
            fm.descent = maxOf(fm.descent, bottom)
            fm.bottom = maxOf(fm.bottom, bottom)
        }
        return width
    }

    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        fit(paint, text, start, end)
        val left = x.roundToInt().toFloat()
        box.set(left, (y + this.top).toFloat(), left + width, (y + this.bottom).toFloat())
        val corner = box.height() * 0.2f
        if (fill != 0) {
            label.color = fill
            canvas.drawRoundRect(box, corner, corner, label)
        }
        if (line != 0) {
            // Inside the box, so outlined and filled chips are one size.
            box.inset(stroke / 2, stroke / 2)
            label.style = Paint.Style.STROKE
            label.strokeWidth = stroke
            label.color = line
            canvas.drawRoundRect(box, corner - stroke / 2, corner - stroke / 2, label)
            label.style = Paint.Style.FILL
        }
        label.color = ink
        canvas.drawText(text, start, end, left + size * pad, y + baseline, label)
    }
}
