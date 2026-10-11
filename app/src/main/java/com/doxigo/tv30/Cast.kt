package com.doxigo.tv30

import android.content.Context
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Rect
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import androidx.leanback.widget.Presenter
import coil3.dispose
import coil3.load
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The cast as Apple TV shows it: a round portrait, the name under it, and who they play. A face
 * 30nama has no photo for gets its initials instead of an empty disc. A name wraps rather than cut
 * off; the role keeps to one line, ending in «…», and runs through in full under the remote, so
 * a long part never makes one card taller than its neighbours. Leanback pins every item to the
 * row's top, so portraits and names line up whatever the lengths.
 */
class CastPresenter : Presenter() {
    override fun onCreateViewHolder(parent: ViewGroup) = ViewHolder(CastView(parent.context))

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val card = item as Card
        val view = viewHolder.view as CastView
        val latin = card.title.isLatin
        view.name.text = card.title
        // A name 30nama has only in Persian gets Peyda, as a poster's caption does.
        view.name.typeface = view.context.font(if (latin) R.font.inter_medium else R.font.peyda_semibold)
        view.role.text = card.subtitle
        view.role.visibility = if (card.subtitle.isNullOrBlank()) View.INVISIBLE else View.VISIBLE
        view.initials.text = initials(card.title)
        view.initials.typeface = view.context.font(if (latin) R.font.inter_bold else R.font.peyda_bold)
        view.initials.visibility = View.VISIBLE
        view.contentDescription = listOfNotNull(card.title, card.subtitle?.toString()).joinToString("، ")
        view.photo.load(card.image) { listener(onSuccess = { _, _ -> view.showPhoto() }) }
        view.portrait.settle()
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        (viewHolder.view as CastView).photo.apply { dispose(); setImageDrawable(null) }
    }
}

private class CastView(context: Context) : LinearLayout(context) {
    val photo = ImageView(context)
    val initials = TextView(context)
    val name = TextView(context)
    val role = TextView(context)
    /** The disc that lifts on focus, ring and all; a recycled one is settled on bind. */
    val portrait = FrameLayout(context)
    private val size = (112 * resources.displayMetrics.density).toInt()
    /** The photo's and the initials' circle: a pixel inside the ring's outer edge (see init). */
    private val face = (size - 2).toFloat()

    init {
        val dp = resources.displayMetrics.density
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        isFocusable = true
        isFocusableInTouchMode = true
        // ListRowPresenter clips every card to its own bounds (its rounded corners), and the disc
        // lifts from its centre, so the lift needs room inside the card above the disc: padding
        // the card draws into. The negative margin hands that room back, keeping the disc level
        // with the artwork in the rows above; it reaches into the row's top padding, which the
        // row draws.
        val room = ceil(size * (Motion.LIFT - 1) / 2).toInt()
        setPadding(0, room, 0, 0)
        clipToPadding = false
        // Wide enough for most names on one line; a longer role runs through on focus (see onFocusChanged).
        layoutParams = MarginLayoutParams((160 * dp).toInt(), LayoutParams.WRAP_CONTENT).apply { topMargin = -room }
        // The disc copies this view's focus so its ring lights with it. Nothing clips the ring:
        // its stroke sits flush inside the disc's edge.
        portrait.isDuplicateParentStateEnabled = true
        portrait.foreground = ContextCompat.getDrawable(context, R.drawable.cast_ring)
        // Photo and initials sit a pixel inside the ring's outer edge, so its opaque white covers
        // their soft rim and the ring's own anti-aliasing meets the page, not the photo.
        val inside = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        inside.setMargins(1, 1, 1, 1)
        initials.setTextAppearance(R.style.TextAppearance_App_Initials)
        initials.gravity = Gravity.CENTER
        initials.setBackgroundResource(R.drawable.cast_disc)
        photo.scaleType = ImageView.ScaleType.MATRIX
        photo.outlineProvider = OVAL
        photo.clipToOutline = true
        photo.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        portrait.addView(initials, inside)
        portrait.addView(photo, FrameLayout.LayoutParams(inside))
        addView(portrait, LayoutParams(size, size))
        name.setTextAppearance(R.style.TextAppearance_Leanback_ImageCardView_Title)
        name.gravity = Gravity.CENTER_HORIZONTAL
        name.maxLines = 3
        name.ellipsize = TextUtils.TruncateAt.END
        // Peyda's own leading (1.26em) crowds a name stacked on two or three lines.
        TextViewCompat.setLineHeight(name, (name.textSize * 1.4f).roundToInt())
        role.setTextAppearance(R.style.TextAppearance_Leanback_ImageCardView_Content)
        role.gravity = Gravity.CENTER_HORIZONTAL
        role.isSingleLine = true
        role.ellipsize = TextUtils.TruncateAt.END
        role.marqueeRepeatLimit = 1
        // Built in code, so not through the inflater's: «در نقش» would read «درنقش».
        name.widenSpaces()
        role.widenSpaces()
        addView(name, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = (12 * dp).toInt() })
        addView(role, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = (2 * dp).toInt() })
    }

    /** The photo then stands alone, with no disc behind its edge to show through as a grey rim. */
    fun showPhoto() {
        if (photo.fitHead(face)) initials.visibility = INVISIBLE
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        portrait.lift(gainFocus)
        // A marquee runs only while selected; at rest the role ends in «…» rather than a hard cut.
        role.ellipsize = if (gainFocus) TextUtils.TruncateAt.MARQUEE else TextUtils.TruncateAt.END
        role.isSelected = gainFocus
    }
}

/**
 * Fits a loaded headshot (scaleType MATRIX) to a circle [face] px across: scaled to its width,
 * centred across, and cut from [CROWN] of the way down. False while nothing has loaded.
 */
fun ImageView.fitHead(face: Float): Boolean {
    val image = drawable ?: return false
    val w = image.intrinsicWidth.toFloat()
    val h = image.intrinsicHeight.toFloat()
    if (w <= 0f || h <= 0f) return false
    val scale = max(face / w, face / h)
    imageMatrix = Matrix().apply {
        setScale(scale, scale)
        postTranslate((face - w * scale) / 2, -min(h * scale - face, h * scale * CROWN))
    }
    return true
}

/** First and last name's first letters. Persian ones would join into a word; a non-joiner keeps each whole. */
fun initials(name: String): String = name.split(' ').filter { it.isNotBlank() }
    .let { words -> listOfNotNull(words.firstOrNull(), words.drop(1).lastOrNull()) }
    .joinToString(if (name.isLatin) "" else "‌") { it.take(1).uppercase() }

/**
 * 30nama's headshots are TMDB's 2:3 profiles: crown about 7% down, eyes at a third, chin near
 * 56%. Cut from 4% down, the circle holds the whole head with the eyes just above its middle,
 * where a face looks centred; much more and the crown goes.
 */
private const val CROWN = 0.04f

/** A portrait's circle, to clip a photo (or the disc holding it) to. */
val OVAL = object : ViewOutlineProvider() {
    override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
}
