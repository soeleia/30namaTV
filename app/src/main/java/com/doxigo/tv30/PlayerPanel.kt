package com.doxigo.tv30

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.FocusFinder
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HorizontalGridView
import androidx.leanback.widget.ItemBridgeAdapter

// ---- The player's settings: a side panel of choices, and the episodes along the bottom. ----

/** A row in a column: [checked] marks the current choice; null makes it a row that opens a page. */
class Choice(val label: CharSequence, val checked: Boolean?, val onClick: () -> Unit)

/** Choices under a header; a group with no header is set apart by space alone. */
class Group(val header: String?, val choices: List<Choice>)

/** One page: its title, an optional view over the columns, the columns, and the one the remote lands in. */
class Page(val title: String, val columns: List<List<Group>>, val preview: View? = null, val landing: Int = 0)

/**
 * Settings as Netflix and Apple TV do them on a TV: a panel that slides in from the end edge over
 * the dimmed video, its choices in columns (Up and Down within one, Left and Right between them),
 * the current one checked and the focused one on the white pill. OK applies at once, so a change
 * shows behind the panel; Back steps back a page, then closes. Episodes rise from the bottom as the
 * season's tiles. Neither lets the remote out to the controls or the skip button underneath.
 */
class PlayerPanel(private val root: ViewGroup, under: View, private val onClosed: (controls: Boolean) -> Unit) {
    private val context: Context = root.context
    private val dp = context.resources.displayMetrics.density
    private val rtl = context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
    private val ink = ContextCompat.getColorStateList(context, R.color.player_ink)
    /** Dims the video, but under the subtitles, so a change to them shows bright beside the panel. */
    private val scrim = object : View(context) {
        override fun hasOverlappingRendering() = false
    }
    private val side = Trap(context)
    private val sheet = Trap(context)
    private val stack = ArrayList<Level>()
    private val rows = ArrayList<View>()
    private var showing: View? = null

    /** The side panel's width; the subtitles keep to the rest of the screen while it shows. */
    val width = (580 * dp).toInt()
    val isOpen get() = showing != null
    val covers get() = showing === side

    /** A page in the stack and the row the remote was on when it went a page deeper. */
    private class Level(val page: () -> Page) {
        var row = -1
    }

    init {
        scrim.setBackgroundColor(0x80000000.toInt())
        root.addView(scrim, root.indexOfChild(under), FrameLayout.LayoutParams(MATCH, MATCH))
        side.setBackgroundColor(0xEB141414.toInt()) // ink at 92%
        root.addView(side, FrameLayout.LayoutParams(width, MATCH, Gravity.END))
        // Melts into the video at its top edge rather than cutting across it.
        sheet.background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xF2141414.toInt(), 0xEB141414.toInt(), 0x00141414))
        root.addView(sheet, FrameLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        for (view in listOf(scrim, side, sheet)) view.visibility = View.GONE
    }

    fun open(page: () -> Page) {
        stack.clear()
        stack.add(Level(page))
        show(side)
        render(0)
    }

    fun push(page: () -> Page) {
        stack.last().row = rows.indexOf(side.findFocus())
        stack.add(Level(page))
        render(1)
    }

    /** Back: a page at a time, then the panel. */
    fun back() {
        if (showing === side && stack.size > 1) {
            stack.removeAt(stack.lastIndex)
            render(-1)
        } else {
            close(controls = true)
        }
    }

    fun close(controls: Boolean) {
        val view = showing ?: return
        showing = null
        stack.clear()
        sheet.redirect = null
        view.animate().alpha(0f).translationX(if (view === side) slide() else 0f).translationY(if (view === sheet) Motion.travel(48 * dp) else 0f)
            .setStartDelay(0).setInterpolator(Motion.easeOut).setDuration(Motion.EXIT).withLayer()
            .withEndAction {
                view.visibility = View.GONE
                (view as ViewGroup).removeAllViews()
            }.start()
        scrim.animate().alpha(0f).setStartDelay(0).setInterpolator(Motion.easeOut).setDuration(Motion.EXIT)
            .withEndAction { scrim.visibility = View.GONE }.start()
        onClosed(controls)
    }

    /** Toward the end edge, where the side panel comes in from. */
    private fun slide() = Motion.travel(48 * dp * if (rtl) -1 else 1)

    /** Slides and fades [view] in over the dimming video; a close still under way is cancelled. */
    private fun show(view: View) {
        showing = view
        for (v in listOf(scrim, view)) {
            v.animate().cancel()
            v.alpha = 0f
            v.visibility = View.VISIBLE
        }
        if (view === side) view.translationX = slide() else view.translationY = Motion.travel(48 * dp)
        scrim.animate().alpha(1f).setStartDelay(0).setInterpolator(Motion.easeOut).setDuration(Motion.ENTER).start()
        view.animate().alpha(1f).translationX(0f).translationY(0f).setStartDelay(0).setInterpolator(Motion.easeOut)
            .setDuration(Motion.ENTER).withLayer().start()
    }

    /**
     * Draws the top page and puts the remote on the row it left, or on the landing column's current
     * choice. A page going deeper ([direction] 1) slides in from the end, one coming back from the start.
     */
    private fun render(direction: Int) {
        val level = stack.last()
        val page = level.page()
        rows.clear()
        val old = side.getChildAt(0)
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // Wider at the screen's edge: TVs overscan.
            setPaddingRelative(px(40), px(48), px(48), px(32))
        }
        content.addView(text(page.title, R.style.TextAppearance_App_DialogTitle), linear(MATCH, WRAP).apply { bottomMargin = px(20) })
        page.preview?.let { content.addView(it, linear(MATCH, px(120)).apply { bottomMargin = px(20) }) }
        val columns = LinearLayout(context)
        var landing: View? = null
        page.columns.forEachIndexed { c, groups ->
            val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val first = rows.size
            groups.forEachIndexed { g, group ->
                val gap = if (g > 0) px(20) else 0
                group.header?.let { column.addView(header(it), linear(MATCH, WRAP).apply { topMargin = gap; bottomMargin = px(6) }) }
                val views = group.choices.map { row(it, group.header) }
                views.forEachIndexed { i, view ->
                    val choice = group.choices[i]
                    view.setOnClickListener {
                        // The check moves at once; the choice applies behind the panel.
                        if (choice.checked != null) views.forEachIndexed { j, v -> if (group.choices[j].checked != null) mark(v, v === view) }
                        choice.onClick()
                    }
                    column.addView(view, linear(MATCH, px(44)).apply {
                        bottomMargin = px(4)
                        if (i == 0 && group.header == null) topMargin = gap
                    })
                    if (c == page.landing && (landing == null || choice.checked == true && !landing!!.isSelected)) landing = view
                }
                rows += views
            }
            // Up and Down stay in their column, stopping at either end.
            if (rows.size > first) {
                rows[first].let { it.nextFocusUpId = it.id }
                rows.last().let { it.nextFocusDownId = it.id }
            }
            columns.addView(column, linear(0, WRAP, 1f).apply { if (c > 0) marginStart = px(16) })
        }
        // Only scrolls when a long list outgrows the screen.
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(columns)
        }
        content.addView(scroll, linear(MATCH, 0, 1f))
        side.addView(content)
        // Focus moves before the old page goes: removing a focused view hands focus to the window's
        // first focusable, the hidden controls.
        (rows.getOrNull(level.row) ?: landing)?.requestFocus()
        side.removeView(old)
        if (direction != 0) {
            content.alpha = 0f
            content.translationX = Motion.travel(24 * dp * direction * if (rtl) -1 else 1)
            content.animate().alpha(1f).translationX(0f).setInterpolator(Motion.easeOut).setDuration(Motion.ENTER).withLayer().start()
        }
    }

    /**
     * The series' episodes, a strip of tiles per season. Seasons are chips beside the title that
     * switch the strip as the remote lands on them; a season longer than fifty adds chips for its
     * stretches («۱–۵۰», «۵۱–۱۰۰»), as the official app does, so the strip never holds more than
     * fifty tiles. Up from the strip lands on the chip it shows. The remote starts on [episode] of [season].
     */
    fun episodes(title: String, seasons: List<Pair<String, List<Card>>>, season: Int, episode: Int) {
        show(sheet)
        sheet.removeAllViews()
        val tiles = ArrayObjectAdapter(EpisodePresenter())
        val grid = HorizontalGridView(context).apply {
            adapter = ItemBridgeAdapter(tiles).apply {
                setAdapterListener(object : ItemBridgeAdapter.AdapterListener() {
                    override fun onCreate(viewHolder: ItemBridgeAdapter.ViewHolder) {
                        viewHolder.itemView.setOnClickListener { (viewHolder.item as Card).onClick() }
                    }
                })
            }
            horizontalSpacing = px(16)
            // Room for a focused tile's lift on every side.
            setPadding(px(48), px(12), px(48), px(12))
            clipToPadding = false
        }
        val chips = if (seasons.size > 1) seasons.map { chip(it.first) } else emptyList()
        // The shown season's stretches, rebuilt with it: a season of a thousand has twenty, one of twelve none.
        val stretchChips = LinearLayout(context)
        var stretches = emptyList<IntRange>()
        var shown = -1
        var stretch = -1
        fun showStretch(r: Int) {
            stretch = r
            for (j in 0 until stretchChips.childCount) stretchChips.getChildAt(j).isSelected = j == r
            val range = stretches.getOrElse(r) { seasons[shown].second.indices }
            tiles.setItems(seasons[shown].second.slice(range), null)
            grid.selectedPosition = if (shown == season && episode in range) episode - range.first else 0
        }
        fun showSeason(i: Int) {
            shown = i
            chips.forEachIndexed { j, chip -> chip.isSelected = j == i }
            val cards = seasons[i].second
            stretches = ranges(cards.size)
            stretchChips.removeAllViews()
            stretches.forEachIndexed { r, range ->
                val first = cards[range.first].numeral
                val last = cards[range.last].numeral
                stretchChips.addView(chip("$first–$last").apply {
                    contentDescription = context.getString(R.string.episode_range, first, last)
                    setOnFocusChangeListener { _, has -> if (has && r != stretch) showStretch(r) }
                }, linear(WRAP, px(44)).apply { marginEnd = px(8) })
            }
            showStretch(if (i == season) stretches.indexOfFirst { episode in it }.coerceAtLeast(0) else 0)
        }
        chips.forEachIndexed { i, chip -> chip.setOnFocusChangeListener { _, has -> if (has && i != shown) showSeason(i) } }
        sheet.redirect = { direction ->
            (stretchChips.getChildAt(stretch) ?: chips.getOrNull(shown)).takeIf { direction == View.FOCUS_UP && grid.hasFocus() }
        }
        showSeason(season)

        // An anime's twenty stretches, or its many seasons, outrun the screen: the chips scroll with the remote.
        val strip = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(LinearLayout(context).apply {
                chips.forEach { addView(it, linear(WRAP, px(44)).apply { marginEnd = px(8) }) }
                // Set apart from the seasons: a finer choice within the one shown.
                addView(stretchChips, linear(WRAP, WRAP).apply { if (chips.isNotEmpty()) marginStart = px(16) })
            })
        }
        val head = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(px(48), 0, px(48), 0)
            addView(text(title, R.style.TextAppearance_App_DialogTitle), linear(WRAP, WRAP).apply { marginEnd = px(24) })
            addView(strip, linear(0, WRAP, 1f))
        }
        sheet.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, px(56), 0, px(28))
            addView(head, linear(MATCH, px(44)).apply { bottomMargin = px(8) })
            addView(grid, linear(MATCH, px(150)))
        })
        grid.requestFocus()
        // The shown chip in view, as the remote would bring it, though it may be the twentieth.
        (stretchChips.getChildAt(stretch) ?: chips.getOrNull(shown))?.let { c -> c.post { c.requestRectangleOnScreen(Rect(0, 0, c.width, c.height), true) } }
    }

    private fun row(choice: Choice, header: String?): View = LinearLayout(context).apply {
        id = View.generateViewId()
        isFocusable = true
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(R.drawable.player_row)
        setPaddingRelative(px(12), 0, px(16), 0)
        contentDescription = header?.let { "$it${if (Api.language == "fa") "، " else ", "}${choice.label}" } ?: choice.label
        val label = text(latin(context, choice.label, R.font.inter_regular), R.style.TextAppearance_App_Body).apply {
            setTextColor(ink)
            isDuplicateParentStateEnabled = true
        }
        val icon = ImageView(context).apply {
            setImageResource(if (choice.checked == null) R.drawable.player_chevron else R.drawable.player_check)
            imageTintList = ink
            isDuplicateParentStateEnabled = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        if (choice.checked == null) {
            // The label lines up with the choices' labels, past their check's gutter.
            label.setPaddingRelative(px(28), 0, 0, 0)
            addView(label, linear(0, WRAP, 1f))
            addView(icon, linear(px(18), px(18)))
        } else {
            addView(icon, linear(px(18), px(18)).apply { marginEnd = px(10) })
            addView(label, linear(0, WRAP, 1f))
            mark(this, choice.checked)
        }
    }

    /** The current choice: white, with its check (and "selected" to TalkBack). */
    private fun mark(row: View, checked: Boolean) {
        row.isSelected = checked
        (row as ViewGroup).getChildAt(0).visibility = if (checked) View.VISIBLE else View.INVISIBLE
    }

    /** Peyda, a step quieter than the rows, lined up with their labels. */
    private fun header(value: String) = text(value, R.style.TextAppearance_App_Nav).apply {
        textSize = 15f
        setTextColor(ContextCompat.getColor(context, R.color.text_faint))
        setPaddingRelative(px(40), 0, 0, 0)
    }

    private fun chip(label: String) = text(label, R.style.TextAppearance_App_Nav).apply {
        isFocusable = true
        gravity = Gravity.CENTER
        textAlignment = View.TEXT_ALIGNMENT_CENTER
        setTextColor(ink)
        setBackgroundResource(R.drawable.player_row)
        setPaddingRelative(px(18), 0, px(18), 0)
    }

    private fun text(value: CharSequence, style: Int) = TextView(context).apply {
        setTextAppearance(style)
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        widenSpaces()
        text = value
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun px(value: Int) = (value * dp).toInt()

    private fun linear(width: Int, height: Int, weight: Float = 0f) = LinearLayout.LayoutParams(width, height, weight)

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}

/**
 * Keeps the remote inside: the next focus is searched among the panel's own views only, so it never
 * reaches the controls or the skip button under it. [redirect] picks a target first where geometry
 * alone would pick the wrong one.
 */
private class Trap(context: Context) : FrameLayout(context) {
    var redirect: ((Int) -> View?)? = null

    override fun focusSearch(focused: View?, direction: Int): View? =
        redirect?.invoke(direction) ?: FocusFinder.getInstance().findNextFocus(this, focused, direction)
}
