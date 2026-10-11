package com.doxigo.tv30

import android.content.Context
import android.graphics.Rect
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.leanback.widget.Presenter
import coil3.dispose
import coil3.load
import coil3.request.crossfade
import coil3.request.error
import coil3.request.placeholder

/**
 * The card that ends a catalogue row and opens the whole of it: poster-sized, a contact sheet of
 * four of the row's posters under the ink, the onward arrow and «بیشتر» over them. It reads as one
 * more title at the end of the row rather than a grey box, and lifts from its foot as posters do.
 * A row with fewer than four posters repeats them round the sheet.
 */
class SeeAllPresenter(private val width: Int, private val height: Int) : Presenter() {
    private var latin: Typeface? = null
    private var persian: Typeface? = null

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val dp = parent.resources.displayMetrics.density
        if (latin == null) {
            latin = parent.context.font(R.font.inter_bold)
            persian = parent.context.font(R.font.peyda_bold)
        }
        return ViewHolder(SeeAllView(parent.context, (width * dp).toInt(), (height * dp).toInt()))
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val card = item as Card
        val view = viewHolder.view as SeeAllView
        view.label.text = card.title
        view.label.typeface = if (card.title.isLatin) latin else persian
        card.icon?.let(view.arrow::setImageResource)
        view.contentDescription = card.title
        view.cells.forEachIndexed { i, cell ->
            val url = card.mosaic.takeIf { it.isNotEmpty() }?.let { it[i % it.size] }
            cell.load(url) { placeholder(blank(view.context)); error(blank(view.context)); crossfade(Motion.IMAGE) }
        }
        view.settle()
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        (viewHolder.view as SeeAllView).cells.forEach { it.dispose(); it.setImageDrawable(null) }
    }
}

private class SeeAllView(context: Context, width: Int, height: Int) : FrameLayout(context) {
    val cells = List(4) { ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP } }
    val arrow = ImageView(context)
    val label = TextView(context)

    init {
        val dp = resources.displayMetrics.density
        layoutParams = ViewGroup.LayoutParams(width, height)
        isFocusable = true
        isFocusableInTouchMode = true
        // The gutters between the posters are the ink showing through.
        setBackgroundResource(R.color.ink)
        foreground = ContextCompat.getDrawable(context, R.drawable.focus_ring)
        outlineProvider = ROUNDED
        clipToOutline = true
        // Two by two, a 2 dp gutter between; each cell keeps about a poster's shape.
        val gutter = (2 * dp).toInt()
        cells.forEachIndexed { i, cell ->
            val side = if (i % 2 == 0) Gravity.START else Gravity.END
            val edge = if (i < 2) Gravity.TOP else Gravity.BOTTOM
            addView(cell, LayoutParams((width - gutter) / 2, (height - gutter) / 2, side or edge))
        }
        // Dark enough that the sheet reads as texture and the words stay white on it.
        addView(View(context).apply { setBackgroundResource(R.color.scrim_mid) }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        label.setTextAppearance(R.style.TextAppearance_App_Tile)
        label.maxLines = 1
        label.gravity = Gravity.CENTER
        val words = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(arrow)
            addView(label, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = (4 * dp).toInt() })
        }
        addView(words, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // From its foot, as a poster's art lifts.
        pivotX = w / 2f
        pivotY = h.toFloat()
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        lift(gainFocus)
    }
}
