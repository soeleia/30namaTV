package com.doxigo.tv30

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.leanback.widget.Presenter

class ListsPresenter : Presenter() {
    private var latin: Typeface? = null
    private var persian: Typeface? = null

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        if (latin == null) {
            latin = parent.context.font(R.font.inter_bold)
            persian = parent.context.font(R.font.peyda_bold)
        }
        return ViewHolder(ListLink(parent.context))
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val card = item as Card
        val link = viewHolder.view as ListLink
        val featured = card.icon == R.drawable.ic_watchlist
        val width = if (featured) 304 else 208
        link.layoutParams = ViewGroup.LayoutParams(link.dp(width), link.dp(128))
        link.label.text = card.title
        link.label.typeface = if (card.title.isLatin) latin else persian
        var size = if (featured) 32f else 24f
        link.label.textSize = size
        link.label.paint.fitSpaces()
        while (size > 18f && link.label.paint.measureText(card.title) > link.dp(width - 48)) {
            link.label.textSize = --size
            link.label.paint.fitSpaces()
        }
        (link.label.layoutParams as FrameLayout.LayoutParams).bottomMargin =
            link.dp(24) - link.label.paint.fontMetricsInt.descent
        card.icon?.let(link.icon::setImageResource)
        link.contentDescription = card.title
        link.background = link.surface(if (featured) R.color.surface_high else R.color.ink_deep)
        link.settle()
        link.jumpDrawablesToCurrentState()
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) = Unit
}

private class ListLink(context: Context) : FrameLayout(context) {
    val icon = ImageView(context)
    val label = TextView(context)

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        outlineProvider = ROUNDED
        clipToOutline = true
        val ink = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf(android.R.attr.state_focused), intArrayOf()),
            intArrayOf(context.getColor(R.color.ink), context.getColor(R.color.ink), context.getColor(R.color.text)),
        )
        icon.imageTintList = ink
        icon.isDuplicateParentStateEnabled = true
        icon.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(icon, LayoutParams(dp(28), dp(28), Gravity.TOP or Gravity.START).apply {
            marginStart = dp(24)
            topMargin = dp(20)
        })
        val arrow = ImageView(context).apply {
            setImageResource(R.drawable.ic_more)
            imageTintList = ink
            isDuplicateParentStateEnabled = true
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        addView(arrow, LayoutParams(dp(24), dp(24), Gravity.TOP or Gravity.END).apply {
            marginEnd = dp(24)
            topMargin = dp(22)
        })
        label.setTextAppearance(R.style.TextAppearance_App_Tile)
        label.setTextColor(ink)
        label.isDuplicateParentStateEnabled = true
        label.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        label.setSingleLine(true)
        label.includeFontPadding = false
        label.ellipsize = TextUtils.TruncateAt.END
        label.gravity = Gravity.START
        label.textAlignment = TEXT_ALIGNMENT_VIEW_START
        label.textDirection = TEXT_DIRECTION_FIRST_STRONG
        label.widenSpaces()
        addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            marginStart = dp(24)
            marginEnd = dp(24)
        })
    }

    fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    fun surface(id: Int): StateListDrawable {
        fun plate(color: Int, border: Boolean = false) = GradientDrawable().apply {
            cornerRadius = resources.getDimension(R.dimen.corner)
            setColor(context.getColor(color))
            if (border) setStroke(dp(1), context.getColor(R.color.hairline))
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), plate(R.color.text_body))
            addState(intArrayOf(android.R.attr.state_focused), plate(R.color.text))
            addState(intArrayOf(), plate(id, border = true))
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        pivotX = w / 2f
        pivotY = h.toFloat()
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        lift(gainFocus)
    }
}
