package com.doxigo.tv30

import android.content.Context
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.FragmentActivity
import androidx.leanback.widget.BaseGridView
import androidx.leanback.widget.FocusHighlight
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.Row
import androidx.leanback.widget.RowHeaderPresenter
import androidx.leanback.widget.RowPresenter
import coil3.dispose
import coil3.load
import coil3.request.crossfade
import coil3.request.error
import coil3.request.placeholder
import org.json.JSONObject

/**
 * A title as a poster, carrying what the billboard shows when the remote rests on it. A continue
 * card's caption is its time [left] instead of the score and year, or [caption], a shorter way of saying it.
 * OK held opens the title's quick actions (Sheets.kt), unless [onLongClick] says otherwise, as
 * Continue Watching's does.
 */
fun JSONObject.toCard(
    context: Context, lead: String? = null, progress: Float? = null, left: String? = null, caption: String? = left,
    onLongClick: (() -> Unit)? = { (context as? FragmentActivity)?.quickActions(this) }, onClick: () -> Unit,
) = Card(englishName, meta(context, lead, left), poster, plot = plot, backdrop = cover, localTitle = localName, source = this,
    facts = caption ?: facts(context), progress = progress, free = isFree, onLongClick = onLongClick, onClick = onClick)

fun Context.openDetails(title: JSONObject) = startActivity(DetailsActivity.intent(this, title))

/** Play, from the title page or the hero: a guest without a free title signs in first, and a plan that can't stream is told so. */
fun Context.play(title: JSONObject) = when {
    title.streamable -> startActivity(PlayerActivity.intent(this, title.titleId, title.isFree, title = title))
    !Api.loggedIn -> startActivity(Intent(this, LoginActivity::class.java))
    else -> toast(getString(R.string.not_allowed_to_stream))
}

// ---- Cards. Titles are artwork only, as on Netflix; everything else is a labelled tile. ----

class Card(
    val title: String,
    /** The billboard's meta line, or a tile's caption. */
    val subtitle: CharSequence? = null,
    val image: String? = null,
    @DrawableRes val icon: Int? = null,
    /** A "see all" card's art: a few posters from its row (SeeAll.kt). */
    val mosaic: List<String> = emptyList(),
    /** How far in (0..1): Netflix's red bar along the artwork's foot. */
    val progress: Float? = null,
    val plot: String? = null,
    val backdrop: String? = null,
    /** The Persian name under the English one on the billboard. */
    val localTitle: String? = null,
    /** The 30nama title behind it, which the billboard asks TMDB about when 30nama has no Persian name. */
    val source: JSONObject? = null,
    /** A poster's caption under the title: the IMDb score and the year. */
    val facts: CharSequence? = null,
    /** An episode's number, set large on its tile. */
    val numeral: String? = null,
    /** An episode seen through (Watched.kt): its tile wears a check and steps back. */
    val watched: Boolean = false,
    /** Streams without a plan: a poster wears «رایگان». */
    val free: Boolean = false,
    val onLongClick: (() -> Unit)? = null,
    val onClick: () -> Unit,
)

/**
 * Rows of cards as Netflix and the Tizen client move them: the focused card stays at the content's
 * start edge and the ones before it slide away under the rail, instead of Leanback's default of
 * drifting focus to the middle of the screen; near the end the row stops with its last card
 * at the end edge. Each row remembers its card across recycling, so Up and Down land back on it.
 * Focus is the cards' own white ring and lift (see Motion); rows are never dimmed, only their
 * titles step down (see [RowTitle]).
 */
class CardRowPresenter : ListRowPresenter(FocusHighlight.ZOOM_FACTOR_NONE) {
    /** A row's last focused card, by row id (rows without an id, like search's, start over). */
    private val positions = HashMap<Long, Int>()

    init {
        shadowEnabled = false
        selectEffectEnabled = false
        headerPresenter = RowTitle()
    }

    /**
     * With the select effect off (it would dim the cards) Leanback leaves the title at its
     * unselected level for good; the row's own level, which it still animates, drives it here.
     */
    override fun onSelectLevelChanged(holder: RowPresenter.ViewHolder) {
        super.onSelectLevelChanged(holder)
        holder.headerViewHolder?.let { headerPresenter.setSelectLevel(it, holder.selectLevel) }
    }

    override fun initializeRowViewHolder(holder: RowPresenter.ViewHolder) {
        super.initializeRowViewHolder(holder)
        (holder as ViewHolder).gridView.apply {
            // The keyline sits on the start padding (the rail's width plus the gap), measured from
            // the start edge in either direction; a card lines up there by its own start edge.
            windowAlignment = BaseGridView.WINDOW_ALIGN_BOTH_EDGE
            windowAlignmentOffset = paddingStart
            windowAlignmentOffsetPercent = 0f
            itemAlignmentOffset = 0
            itemAlignmentOffsetPercent = 0f
            // The cards just past the row's end edge (about two posters) are laid out and attached
            // as well, so their art is already downloading when a press scrolls them in. Before,
            // each one asked for its poster only as it came on screen.
            prefetchAttachedRows()
        }
    }

    override fun onBindRowViewHolder(holder: RowPresenter.ViewHolder, item: Any) {
        super.onBindRowViewHolder(holder, item)
        val at = positions[(item as Row).id] ?: return
        val grid = (holder as ViewHolder).gridView
        grid.selectedPosition = at.coerceAtMost((grid.adapter?.itemCount ?: 0) - 1).coerceAtLeast(0)
    }

    override fun onUnbindRowViewHolder(holder: RowPresenter.ViewHolder) {
        holder.row?.takeIf { it.id >= 0 }?.let { positions[it.id] = (holder as ViewHolder).gridView.selectedPosition }
        super.onUnbindRowViewHolder(holder)
    }
}

/**
 * A row's title: white while the remote is on its row, text_dim under it, as Netflix ranks them
 * (Leanback's stock header sat at half alpha on every row, the focused one too). Each title takes
 * the face drawn for its script, Peyda for Persian and Inter for Latin: search's "attack on
 * titan" in Peyda's Latin ran its words together.
 */
private class RowTitle : RowHeaderPresenter() {
    private var latin: Typeface? = null
    private var persian: Typeface? = null

    override fun onBindViewHolder(viewHolder: Presenter.ViewHolder, item: Any?) {
        super.onBindViewHolder(viewHolder, item)
        val name = viewHolder.view.findViewById<TextView>(androidx.leanback.R.id.row_header) ?: return
        if (latin == null) {
            latin = name.context.font(R.font.inter_bold)
            persian = name.context.font(R.font.peyda_bold)
        }
        name.typeface = if (name.text.isLatin) latin else persian
    }

    override fun onSelectLevelChanged(holder: RowHeaderPresenter.ViewHolder) {
        val name = holder.view.findViewById<TextView>(androidx.leanback.R.id.row_header) ?: return
        val c = name.context
        name.setTextColor(ColorUtils.blendARGB(c.getColor(R.color.text_dim), c.getColor(R.color.text), holder.selectLevel))
    }
}

/** Posters: artwork only, the white focus frame, and the red progress bar when there is one. */
class PosterPresenter(private val width: Int = 124, private val height: Int = 186) : Presenter() {
    /**
     * The caption's two faces, resolved once: Inter for Latin names and Peyda for a title 30nama has only in Persian, which would otherwise fall back
     * to the system's Arabic font.
     */
    private var latin: Typeface? = null
    private var persian: Typeface? = null
    private var badge: FreeBadge? = null

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val card = PosterCard(parent.context)
        if (persian == null) {
            latin = card.title?.typeface
            persian = parent.context.font(R.font.peyda_semibold)
            badge = FreeBadge(parent.context)
        }
        val dp = parent.resources.displayMetrics.density
        card.setMainImageDimensions((width * dp).toInt(), (height * dp).toInt())
        card.isFocusable = true
        card.mainImageView!!.apply {
            outlineProvider = ROUNDED
            clipToOutline = true
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        return ViewHolder(card)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val card = item as Card
        val view = viewHolder.view as PosterCard
        view.titleText = card.title
        view.title?.typeface = if (card.title.isLatin) latin else persian
        view.contentText = card.facts
        view.contentDescription = listOfNotNull(card.title, card.facts?.toString()).joinToString("، ")
        view.light(light(view.context, card.progress))
        view.badge = badge.takeIf { card.free }
        view.mainImageView!!.load(card.image) { placeholder(blank(view.context)); error(blank(view.context)); crossfade(Motion.IMAGE) }
        view.rest()
        view.setOnLongClickListener(card.onLongClick?.let { f -> View.OnLongClickListener { f(); true } })
        view.isLongClickable = card.onLongClick != null
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        // Recycled cards drop their bitmaps; on a 2 GB TV the memory cache is where they belong.
        (viewHolder.view as ImageCardView).mainImageView?.apply { dispose(); setImageDrawable(null) }
    }

    private fun light(context: Context, progress: Float?): Drawable {
        val ring = ContextCompat.getDrawable(context, R.drawable.focus_ring)!!
        if (progress == null) return ring
        val track = ColorDrawable(ContextCompat.getColor(context, R.color.progress_track))
        val bar = ClipDrawable(ColorDrawable(ContextCompat.getColor(context, R.color.red)), Gravity.START, ClipDrawable.HORIZONTAL)
        bar.level = (progress.coerceIn(0f, 1f) * 10_000).toInt()
        val thickness = (4 * context.resources.displayMetrics.density).toInt()
        return LayerDrawable(arrayOf(track, bar, ring)).apply {
            for (layer in 0..1) {
                setLayerGravity(layer, Gravity.BOTTOM or Gravity.FILL_HORIZONTAL)
                setLayerHeight(layer, thickness)
            }
        }
    }
}

/**
 * Draws the focus frame, progress bar and free badge over the artwork. BaseCardView strips focus
 * out of its drawable state on purpose, so the frame follows real focus instead.
 *
 * Focus lifts the art from its foot and leaves the caption where it was: the card scales about
 * the art's bottom edge and the caption takes the inverse scale frame by frame, so captions keep
 * one line along the row, at one size, however focus moves. A name too long for the poster
 * scrolls once focused, as tvOS does, instead of ending at its twelfth letter.
 */
private class PosterCard(context: Context) : ImageCardView(context) {
    private var light: Drawable? = null
    val title: TextView? = findViewById(androidx.leanback.R.id.title_text)
    private val info: View? = findViewById(androidx.leanback.R.id.info_field)
    var badge: FreeBadge? = null
        set(value) {
            if (field !== value) invalidate()
            field = value
        }
    /** This press of OK already long-pressed, so its release must not also click. */
    private var longPressed = false

    init {
        title?.apply {
            setSingleLine(true)
            marqueeRepeatLimit = -1
            isHorizontalFadingEdgeEnabled = true
            setFadingEdgeLength((12 * resources.displayMetrics.density).toInt())
        }
        // The update listener stays with the card's animator, so every lift (Motion's) brings the caption along.
        animate().setUpdateListener { hold() }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val image = mainImageView ?: return
        pivotX = width / 2f
        pivotY = image.bottom.toFloat()
        info?.let {
            it.pivotX = pivotX - it.left
            it.pivotY = pivotY - it.top
        }
    }

    /** The caption undoes the card's scale. */
    private fun hold() {
        info?.scaleX = 1 / scaleX
        info?.scaleY = 1 / scaleY
    }

    /** A recycled card arrives at rest (see View.settle), its caption with it. */
    fun rest() {
        settle()
        hold()
    }

    fun light(drawable: Drawable) {
        light?.callback = null
        light = drawable.also {
            it.callback = this
            it.state = if (isFocused) FOCUSED else IDLE
            it.jumpToCurrentState()
        }
        invalidate()
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (light?.setState(if (gainFocus) FOCUSED else IDLE) == true) invalidate()
        lift(gainFocus)
        title?.apply {
            ellipsize = if (gainFocus && !Motion.reduced) TextUtils.TruncateAt.MARQUEE else TextUtils.TruncateAt.END
            isSelected = gainFocus // a marquee runs only on a selected (or focused) view
        }
    }

    override fun verifyDrawable(who: Drawable) = who === light || super.verifyDrawable(who)

    /**
     * A held OK long-presses even when nothing waits out View's own timer: a remote that only
     * auto-repeats, or `input keyevent --longpress`, whose presses all land at once and would
     * otherwise click (and play) instead of opening the long-press dialog.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!keyCode.isOk) return super.onKeyDown(keyCode, event)
        if (event.repeatCount == 0 && event.flags and KeyEvent.FLAG_LONG_PRESS == 0) longPressed = false
        else if (isLongClickable) {
            if (!longPressed) {
                cancelLongPress() // View's own timer must not fire it a second time
                performLongClick()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (!keyCode.isOk || !longPressed) return super.onKeyUp(keyCode, event)
        longPressed = false
        cancelLongPress()
        isPressed = false
        return true
    }

    /** Every long press, by View's timer or by [onKeyDown], marks the press. */
    override fun performLongClick(): Boolean {
        longPressed = true
        return super.performLongClick()
    }

    private val Int.isOk get() = this == KeyEvent.KEYCODE_DPAD_CENTER || this == KeyEvent.KEYCODE_ENTER || this == KeyEvent.KEYCODE_NUMPAD_ENTER

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val image = mainImageView ?: return
        badge?.draw(canvas, image)
        light?.run {
            setBounds(image.left, image.top, image.right, image.bottom)
            draw(canvas)
        }
    }
}

/**
 * «رایگان» in the artwork's top start corner. Green carries "free" and nothing else; ink on it,
 * since white on that green would not read from the sofa.
 */
private class FreeBadge(context: Context) {
    private val word = context.getString(R.string.free)
    private val metrics = context.resources.displayMetrics
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = context.font(if (word.isLatin) R.font.inter_bold else R.font.vazirmatn_bold)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, metrics)
        color = ContextCompat.getColor(context, R.color.ink)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.green) }
    private val box = RectF()

    fun draw(canvas: Canvas, image: View) {
        val dp = metrics.density
        val inset = 6 * dp
        val pad = 6 * dp
        val width = ink.measureText(word) + 2 * pad
        val start = if (image.layoutDirection == View.LAYOUT_DIRECTION_RTL) image.right - inset - width else image.left + inset
        box.set(start, image.top + inset, start + width, image.top + inset + 18 * dp)
        // The meta line's chips round their corners by a fifth of their height; so does this one.
        val corner = box.height() * 0.2f
        canvas.drawRoundRect(box, corner, corner, fill)
        val font = ink.fontMetrics
        canvas.drawText(word, box.left + pad, box.centerY() - (font.ascent + font.descent) / 2, ink)
    }
}

/** Tiles: artwork or an icon, with the label set on it (genres, episodes, cast, lists, settings). */
class TilePresenter(private val width: Int, private val height: Int) : Presenter() {
    // Labels are the server's words (genres, categories, a user name) in either script; each gets
    // the face drawn for it, resolved once per presenter.
    private var latin: Typeface? = null
    private var persian: Typeface? = null

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val dp = parent.resources.displayMetrics.density
        if (latin == null) {
            latin = parent.context.font(R.font.inter_bold)
            persian = parent.context.font(R.font.peyda_bold)
        }
        val tile = TileView(parent.context)
        tile.layoutParams = ViewGroup.LayoutParams((width * dp).toInt(), (height * dp).toInt())
        return ViewHolder(tile)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val card = item as Card
        val tile = viewHolder.view as TileView
        val captioned = !card.subtitle.isNullOrBlank()
        tile.label.text = card.title
        tile.label.typeface = if (card.title.isLatin) latin else persian
        tile.label.visibility = if (card.title.isBlank()) View.GONE else View.VISIBLE
        tile.label.maxLines = if (captioned) 1 else 2
        tile.caption.text = card.subtitle
        tile.caption.visibility = if (captioned) View.VISIBLE else View.GONE
        tile.contentDescription = listOfNotNull(card.title.ifBlank { null }, card.subtitle).joinToString("، ")
        // An icon sits centred in what the words leave free above them.
        val dp = tile.resources.displayMetrics.density
        (tile.image.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin = if (card.icon != null && captioned) (52 * dp).toInt() else 0
        if (card.icon != null) {
            tile.image.scaleType = ImageView.ScaleType.CENTER
            tile.image.setImageResource(card.icon)
            tile.scrim.visibility = View.GONE
        } else {
            tile.image.scaleType = ImageView.ScaleType.CENTER_CROP
            tile.image.load(card.image) { placeholder(blank(tile.context)); error(blank(tile.context)); crossfade(Motion.IMAGE) }
            tile.scrim.visibility = View.VISIBLE
        }
        tile.settle()
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        (viewHolder.view as TileView).image.apply { dispose(); setImageDrawable(null) }
    }
}

private class TileView(context: Context) : FrameLayout(context) {
    val image = ImageView(context)
    val scrim = View(context)
    val label = TextView(context)
    /** A second, quieter line: the subscription and days left, a language, a count. */
    val caption = TextView(context)

    init {
        val dp = resources.displayMetrics.density
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundResource(R.color.surface_high)
        foreground = ContextCompat.getDrawable(context, R.drawable.focus_ring)
        outlineProvider = ROUNDED
        clipToOutline = true
        scrim.setBackgroundResource(R.drawable.tile_scrim)
        label.setTextAppearance(R.style.TextAppearance_App_Tile)
        label.maxLines = 2
        label.ellipsize = TextUtils.TruncateAt.END
        caption.setTextAppearance(R.style.TextAppearance_App_Caption)
        caption.maxLines = 1
        caption.ellipsize = TextUtils.TruncateAt.END
        label.widenSpaces()
        caption.widenSpaces()
        val words = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), (10 * dp).toInt())
            addView(label)
            addView(caption)
        }
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, (80 * dp).toInt(), Gravity.BOTTOM))
        addView(words, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        lift(gainFocus)
    }
}

/**
 * Artwork's stand-in while it loads: a new one each time. Coil's crossfade fades the drawable it
 * starts from, and one shared instance stayed faded, so every later poster waited on bare ink.
 */
internal fun blank(context: Context): Drawable = ColorDrawable(ContextCompat.getColor(context, R.color.surface_high))

/**
 * Coil starts image requests when rows (or a row's cards) attach; binding alone does not prefetch
 * them. About a row of a grid, or two posters of a row, past each edge.
 */
@SuppressLint("RestrictedApi")
internal fun BaseGridView.prefetchAttachedRows() {
    setExtraLayoutSpace((240 * resources.displayMetrics.density).toInt())
}
private val FOCUSED = intArrayOf(android.R.attr.state_enabled, android.R.attr.state_focused)
private val IDLE = intArrayOf(android.R.attr.state_enabled)
internal val ROUNDED = object : ViewOutlineProvider() {
    override fun getOutline(view: View, outline: Outline) =
        outline.setRoundRect(0, 0, view.width, view.height, view.resources.getDimension(R.dimen.corner))
}
