package com.doxigo.tv30

import android.content.Context
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.LayerDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.leanback.widget.Presenter
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import coil3.decode.DataSource
import coil3.dispose
import coil3.load
import coil3.size.Scale
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.max

// ---- Episodes: a season's row on a series' title page. ----

/**
 * An entry of the `stream` answer's `hls` as its tile: the number set large, a real name when 30nama
 * has one (its names are nearly always «فصل 1 - قسمت 2», which the row and the number already say),
 * and its length. [watching] is `continue_watching` when it points at this file: that tile gets the
 * red bar and says so instead. Any other goes by this TV's history (Watched): seen through, it is
 * marked so; started, it has its bar and the minutes left. The tile wears its own cut of the series'
 * [art] (see [slice]) until a frame of the episode itself is on disk (EpisodeFrames), found by the
 * [file] it carries as its source.
 */
fun Context.episodeCard(file: JSONObject, watching: JSONObject?, art: String? = null, onClick: () -> Unit): Card {
    val own = file.str("info", "episode_name")?.takeUnless { it.isGenericEpisode }?.unescaped()?.localDigits()
    // ponytail: the credits mark stands in for the length (a minute or two short); TMDB's runtime if that bothers anyone.
    val minutes = file.str("options", "end")?.split(':')?.fold(0) { total, part -> total * 60 + (part.toIntOrNull() ?: 0) }
        ?.let { (it + 30) / 60 }?.takeIf { it > 0 }
    val seen = if (watching == null) Watched.of(this, file) else null
    val left = seen?.minutesLeft
    return Card(
        own.orEmpty(),
        when {
            watching != null -> getString(R.string.episode_watching)
            left != null -> getString(R.string.time_left, getString(R.string.runtime_minutes, left))
            else -> minutes?.let { getString(R.string.runtime_minutes, it) }
        },
        backdrop = art,
        progress = if (watching != null) watching.optInt("progress").takeIf { it > 0 }?.let { it / 100f } else seen?.progress,
        source = file,
        numeral = file.episodeNumber.toString().localDigits(),
        watched = seen?.done == true,
        onClick = onClick,
    )
}

val JSONObject.episodeNumber get() = obj("info")?.let { it.optInt("episode_number", it.optInt("episode")) } ?: 0

/**
 * A long season's stretches of [size] episodes, as index ranges into its list, the last one
 * shorter. The official app lists a season fifty at a time: One Piece's thousand in one row is a
 * long walk on a remote. None when the season fits in one, so a short season stays as it was.
 */
fun ranges(count: Int, size: Int = 50): List<IntRange> =
    if (count <= size) emptyList() else (0 until count step size).map { it until minOf(it + size, count) }

/** «قسمت ۱ تا ۵۰», "Episodes 1–50": by the episodes' own numbers, which an anime's later seasons don't start at 1. */
fun Context.rangeLabel(files: List<JSONObject>, range: IntRange): String = getString(
    R.string.episode_range,
    files[range.first].episodeNumber.toString().localDigits(),
    files[range.last].episodeNumber.toString().localDigits(),
)

/** "فصل 1 - قسمت 2", "Episode 2": a number, not a name. */
val String.isGenericEpisode get() = any { it.isDigit() } && (contains("قسمت") || contains("Episode", ignoreCase = true))

/**
 * Episodes as tiles: the number large at the top, the name and length at the foot, Netflix's red
 * bar on the one being watched. Each tile first cuts its own frame from the series' art: a closer
 * crop, panned along the row by episode, greyed and shaded under the number, and brightening a
 * step under the remote. One decoded image serves the billboard and every tile (the same request,
 * so Coil's memory cache answers); the crops are only matrices. A frame of the episode itself
 * (EpisodeFrames) then fades in over it at its own colour, the number stepping back to its corner.
 * With an [owner] (the title page) a tile without one on disk has it extracted, one tile at a time
 * and only while the page shows; without (the player's sheet, over a playing video) tiles show only
 * what is on disk. The ring and lift are every card's.
 */
class EpisodePresenter(private val owner: LifecycleOwner? = null) : Presenter() {
    /** The name's two faces, resolved once: Peyda, and Inter for an English name (see PosterPresenter). */
    private var persian: Typeface? = null
    private var latin: Typeface? = null

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val view = EpisodeView(parent.context)
        if (latin == null) {
            persian = view.name.typeface
            latin = parent.context.font(R.font.inter_bold)
        }
        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val card = item as Card
        val view = viewHolder.view as EpisodeView
        view.numeral.text = card.numeral
        view.name.text = card.title
        view.name.typeface = if (card.title.isLatin) latin else persian
        view.name.visibility = if (card.title.isBlank()) View.GONE else View.VISIBLE
        view.caption.text = card.subtitle
        view.caption.visibility = if (card.subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
        // «در حال تماشا» or the minutes left in white over its bar; a length stays quiet, a seen one's too.
        view.caption.setTextColor(ContextCompat.getColor(view.context, if (card.progress != null && !card.watched) R.color.text else R.color.text_dim))
        view.progress(card.progress)
        view.contentDescription = listOfNotNull(
            view.context.getString(R.string.episode_numbered, card.numeral), card.title.ifBlank { null }, card.subtitle,
            view.context.getString(R.string.episode_watched).takeIf { card.watched },
        ).joinToString(if (Api.language == "fa") "، " else ", ")
        view.job?.cancel()
        view.dress(card.backdrop != null, card.watched)
        // The number back from its Persian digits, for where its cut of the art sits.
        val number = card.numeral?.mapNotNull(Char::digitToIntOrNull)?.fold(0) { n, digit -> n * 10 + digit } ?: 0
        val rtl = view.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        // The billboard's own request for this art (Billboard.show), so this is a memory hit, not a decode.
        if (card.backdrop != null) view.still.load(card.backdrop) {
            size(1280, 720)
            scale(Scale.FILL)
            listener(onSuccess = { _, _ -> view.slice(pan(number, rtl)) })
        } else view.still.apply { dispose(); setImageDrawable(null) }
        // Saving data, frames already on disk still show; the rest keep the series' art.
        frame(view, card.source, extract = owner != null && !Settings.saveData)
        view.settle()
    }

    /**
     * [episode]'s frame from disk: at once when Coil still holds it, else fading in as it is read.
     * Not there yet, and [extract], it is extracted while [owner] shows (a player opened over the
     * page stops it; back, it carries on), then read.
     */
    private fun frame(view: EpisodeView, episode: JSONObject?, extract: Boolean) {
        val file = episode?.let { EpisodeFrames.file(view.context, it) } ?: return view.frame.dispose()
        view.frame.load(file) {
            size(view.tileWidth, view.tileHeight)
            listener(
                onSuccess = { _, result ->
                    view.real(fade = result.dataSource != DataSource.MEMORY_CACHE)
                    if (result.dataSource == DataSource.DISK) EpisodeFrames.touch(file)
                },
                onError = { _, _ ->
                    val owner = owner?.takeIf { extract } ?: return@listener
                    view.job = owner.lifecycleScope.launch {
                        val job = this
                        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                            if (EpisodeFrames.extract(view.context, episode, view.tileWidth, view.tileHeight) != null) frame(view, episode, extract = false)
                            job.cancel() // once, whatever came of it
                        }
                    }
                },
            )
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val view = viewHolder.view as EpisodeView
        view.job?.cancel()
        for (image in listOf(view.still, view.frame)) image.apply { dispose(); setImageDrawable(null) }
    }
}

private class EpisodeView(context: Context) : FrameLayout(context) {
    val still = ImageView(context)
    /** The episode's own frame, over the art once it comes (see [real]). */
    val frame = ImageView(context)
    /** The frame's extraction, while this tile waits for it. */
    var job: Job? = null
    val numeral = TextView(context)
    /** Seen through: a check in the corner the number doesn't take (see [dress]). */
    private val check = ImageView(context)
    val name = TextView(context)
    val caption = TextView(context)
    /** Ink over the series' art, lifting a little under the remote (see [dress]). */
    private val shade = View(context)
    private var dressed = false
    // 16:9, a step up from the genre tiles: a name and a length have to read from the sofa.
    val tileWidth = (224 * resources.displayMetrics.density).toInt()
    val tileHeight = (126 * resources.displayMetrics.density).toInt()
    private val bar = ClipDrawable(ColorDrawable(ContextCompat.getColor(context, R.color.red)), Gravity.START, ClipDrawable.HORIZONTAL)
    private val track = View(context)

    init {
        val dp = resources.displayMetrics.density
        isFocusable = true
        isFocusableInTouchMode = true
        layoutParams = ViewGroup.LayoutParams(tileWidth, tileHeight)
        setBackgroundResource(R.color.surface_high)
        foreground = ContextCompat.getDrawable(context, R.drawable.focus_ring)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) =
                outline.setRoundRect(0, 0, view.width, view.height, view.resources.getDimension(R.dimen.corner))
        }
        clipToOutline = true
        still.scaleType = ImageView.ScaleType.CENTER_CROP
        still.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        frame.scaleType = ImageView.ScaleType.CENTER_CROP
        frame.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        check.setImageResource(R.drawable.episode_watched)
        check.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        numeral.setTextAppearance(R.style.TextAppearance_App_Numeral)
        numeral.textSize = 48f
        numeral.includeFontPadding = false
        // Lifts the number off a bright still; on the bare tile it is lost in the grey.
        numeral.setShadowLayer(12 * dp, 0f, 2 * dp, 0x99000000.toInt())
        // Aligned to the tile, not the text: an English name in the Persian UI keeps to the right.
        for (line in listOf(name, caption)) {
            line.maxLines = 1
            line.ellipsize = TextUtils.TruncateAt.END
            line.textAlignment = TEXT_ALIGNMENT_VIEW_START
        }
        name.setTextAppearance(R.style.TextAppearance_App_Tile)
        caption.setTextAppearance(R.style.TextAppearance_App_Caption)
        // Built in code, so not through the inflater's: Peyda's narrow space would run a name's words together.
        name.widenSpaces()
        caption.widenSpaces()
        val side = (12 * dp).toInt()
        val foot = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(side, 0, side, (10 * dp).toInt())
            addView(name)
            addView(caption)
        }
        // The foot's scrim keeps words readable on a still, and shades the bare tile from grey to ink.
        val scrim = View(context).apply { setBackgroundResource(R.drawable.tile_scrim) }
        track.background = LayerDrawable(arrayOf(ColorDrawable(ContextCompat.getColor(context, R.color.progress_track)), bar))
        shade.setBackgroundResource(R.color.ink)
        addView(still, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(frame, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(shade, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, (80 * dp).toInt(), Gravity.BOTTOM))
        // The digits' tops sit as far from the top edge as their side does from the start edge.
        addView(numeral, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            marginStart = side
            topMargin = (6 * dp).toInt()
        })
        // The corner the number leaves free, as far in from the end edge as from the top.
        addView(check, LayoutParams((24 * dp).toInt(), (24 * dp).toInt(), Gravity.TOP or Gravity.END).apply {
            marginEnd = (10 * dp).toInt()
            topMargin = (10 * dp).toInt()
        })
        addView(foot, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
        addView(track, LayoutParams(LayoutParams.MATCH_PARENT, (4 * dp).toInt(), Gravity.BOTTOM))
    }

    /** How far in (0..1), as the posters show it; none hides the bar. */
    fun progress(value: Float?) {
        track.visibility = if (value == null) View.GONE else View.VISIBLE
        bar.level = ((value ?: 0f).coerceIn(0f, 1f) * 10_000).toInt()
    }

    /**
     * The tile as it is drawn first: the series' [art], when there is any, greyed toward the page and
     * shaded so the number and the words keep their contrast, the crop placed by [slice]; no frame yet.
     * [seen] through, its pictures step back and the check shows: done, not gone, its words as bright.
     */
    fun dress(art: Boolean, seen: Boolean) {
        dressed = art
        still.scaleType = if (art) ImageView.ScaleType.MATRIX else ImageView.ScaleType.CENTER_CROP
        still.colorFilter = if (!art) null else if (seen) GREY_SEEN else GREY
        frame.colorFilter = if (seen) SEEN else null
        check.visibility = if (seen) View.VISIBLE else View.GONE
        for (view in listOf(shade, frame, numeral)) view.animate().cancel()
        shade.visibility = if (art) View.VISIBLE else View.GONE
        shade.alpha = if (isFocused) SHADE_FOCUSED else SHADE
        frame.alpha = 0f
        numeral.scaleX = 1f
        numeral.scaleY = 1f
    }

    /**
     * The episode's own frame over the art, at its own colour: the shade lifts, and the number steps
     * back to its corner, still saying which episode this is (the name is usually 30nama's
     * «قسمت ۲», dropped as saying nothing). A [fade] crossfades; reduced motion keeps the fade and
     * sets the number down at once.
     */
    fun real(fade: Boolean) {
        dressed = false
        if (!fade) { // set now: even a 0 ms animation would show the art for a frame
            frame.alpha = 1f
            shade.alpha = 0f
            numeral.scaleX = NUMERAL_SMALL
            numeral.scaleY = NUMERAL_SMALL
            return
        }
        val time = Motion.IMAGE.toLong()
        frame.animate().alpha(1f).setStartDelay(0).setInterpolator(Motion.easeOut).setDuration(time).start()
        shade.animate().alpha(0f).setStartDelay(0).setInterpolator(Motion.easeOut).setDuration(time).start()
        if (Motion.reduced) {
            numeral.scaleX = NUMERAL_SMALL
            numeral.scaleY = NUMERAL_SMALL
        } else {
            numeral.animate().scaleX(NUMERAL_SMALL).scaleY(NUMERAL_SMALL).setStartDelay(0).setInterpolator(Motion.easeOut).setDuration(time).start()
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // The number shrinks into its own corner, the top start.
        numeral.pivotX = if (layoutDirection == LAYOUT_DIRECTION_RTL) numeral.width.toFloat() else 0f
        numeral.pivotY = 0f
    }

    /**
     * Fits the loaded art (scaleType MATRIX) to cover the tile [ZOOM]× closer than it would, at
     * [across] from its left edge to its right (see [pan]) and [CROP_DOWN] of the way down, where a
     * backdrop's faces tend to sit. By the tile's fixed size: a memory hit lands before its first layout.
     */
    fun slice(across: Float) {
        val image = still.drawable ?: return
        val w = image.intrinsicWidth.toFloat()
        val h = image.intrinsicHeight.toFloat()
        if (w <= 0f || h <= 0f) return
        val scale = max(tileWidth / w, tileHeight / h) * ZOOM
        still.imageMatrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(-(w * scale - tileWidth) * across, -(h * scale - tileHeight) * CROP_DOWN)
        }
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        lift(gainFocus)
        if (dressed) shade.animate().alpha(if (gainFocus) SHADE_FOCUSED else SHADE).setStartDelay(0).setInterpolator(Motion.easeOut)
            .setDuration(if (gainFocus) Motion.FOCUS_IN else Motion.FOCUS_OUT).start()
    }

    private companion object {
        const val ZOOM = 2.2f
        const val CROP_DOWN = 0.3f
        // Ink at 60%, 45% focused: white digits keep about 3:1 even over a near-white frame, with their shadow on top.
        const val SHADE = 0.6f
        const val SHADE_FOCUSED = 0.45f
        /** The number over a real frame: half its size, a mark in the corner rather than the tile's subject. */
        const val NUMERAL_SMALL = 0.5f
        /** Half the colour: art behind a number, not a picture competing with it. */
        val GREY = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0.5f) })
        /** A seen episode's pictures at 70% light: still there, a step behind the ones to come. */
        private val DIM = ColorMatrix().apply { setScale(0.7f, 0.7f, 0.7f, 1f) }
        val SEEN = ColorMatrixColorFilter(DIM)
        val GREY_SEEN = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0.5f); postConcat(DIM) })
    }
}

/**
 * The seasons' row when they didn't come (DetailsActivity.noteEpisodes): under the row's title, why
 * ([Card.title]) in the body face and the Retry pill ([Card.subtitle]), as a person's page says it
 * (PersonActivity.showNote). The note is the row's one stop and the pill wears its state, so Down
 * from Play turns the pill white and OK asks again.
 */
class EpisodesNotePresenter : Presenter() {
    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val context = parent.context
        val dp = context.resources.displayMetrics.density
        val why = TextView(context).apply {
            setTextAppearance(R.style.TextAppearance_App_Body)
            setTextColor(ContextCompat.getColor(context, R.color.text_dim))
            maxWidth = (520 * dp).toInt()
            widenSpaces()
        }
        val retry = Button(context, null, 0, R.style.Widget_App_Button).apply {
            isFocusable = false
            isDuplicateParentStateEnabled = true
            widenSpaces()
        }
        return ViewHolder(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            isFocusableInTouchMode = true
            clipChildren = false // the pill's focus lift
            // Both wrapped: a vertical layout's default fills its width, here the pill's, and cut why to a line.
            addView(why, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(retry, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (20 * dp).toInt()
            })
        })
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val card = item as Card
        val note = viewHolder.view as LinearLayout
        (note.getChildAt(0) as TextView).text = card.title
        (note.getChildAt(1) as TextView).text = card.subtitle
        note.contentDescription = "${card.title} ${card.subtitle}"
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) = Unit
}

/**
 * Where an episode's cut of the series' art sits across it, from its left edge (0) to its right
 * (1): the row pans along the picture in [PAN_STOPS] steps, one per episode, from the side the row
 * reads from, so neighbours show neighbouring parts of the scene and a cut comes back only every
 * [PAN_STOPS] tiles, more than a screenful apart.
 */
fun pan(number: Int, rtl: Boolean): Float {
    val step = (number - 1).mod(PAN_STOPS) / (PAN_STOPS - 1f)
    return if (rtl) 1 - step else step
}

private const val PAN_STOPS = 5
