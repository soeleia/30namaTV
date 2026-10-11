package com.doxigo.tv30

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.view.FocusFinder
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.leanback.widget.BrowseFrameLayout
import kotlin.math.roundToInt

/**
 * Netflix's rail, as the 30nama Tizen client draws it: Search, Home, the three catalogues, the
 * home rows worth jumping to, then Settings. Every top-level page carries it (home, the catalogue
 * grids, search, settings), laid out with R.layout.rail_screen around the page in R.id.browse_host.
 *
 * Shut, the rail is a scope of its own that focus search never wanders into: the remote enters
 * only toward the start edge from content with nothing further that way (a row's first card,
 * or the hero's Play), or with Back on home, and lands on the page's own item. Up and Down glide the one white
 * pill between items, OK goes, and the way back toward the content returns to the card it came
 * from. Catalogues replace one another rather than stack, and the home rows are reached by
 * returning to home, so Back from any page lands on home.
 */
class Rail(private val activity: TvActivity, private val current: Section?, private val content: () -> View?) {
    enum class Section(@DrawableRes val icon: Int, @StringRes val label: Int, val slug: String? = null, val row: Int? = null) {
        SEARCH(R.drawable.nav_search, R.string.nav_search),
        HOME(R.drawable.nav_home, R.string.nav_home, row = HomeFragment.TOP),
        MOVIES(R.drawable.nav_movies, R.string.movies, slug = "movies"),
        SERIES(R.drawable.nav_series, R.string.series, slug = "series"),
        ANIME(R.drawable.nav_anime, R.string.anime, slug = "anime"),
        LISTS(R.drawable.nav_lists, R.string.my_lists, row = HomeFragment.LISTS),
        CATEGORIES(R.drawable.nav_categories, R.string.categories, row = HomeFragment.CATEGORIES),
        SETTINGS(R.drawable.nav_settings, R.string.settings),
    }

    private val rail: ViewGroup = activity.findViewById(R.id.rail)
    private val list: LinearLayout = activity.findViewById(R.id.rail_items)
    private val panel: View = activity.findViewById(R.id.rail_panel)
    private val scrim: View = activity.findViewById(R.id.rail_scrim)
    private val pill: View = activity.findViewById(R.id.rail_pill)
    private val items: List<View>
    private val landing: View
    private val labels = mutableListOf<View>()
    private val rtl = activity.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
    /** Toward the start edge, in pixels: where the panel and labels come in from. */
    private val away get() = Motion.travel(activity.resources.getDimension(R.dimen.rail_slide) * if (rtl) 1 else -1)
    /** Where the remote was before it entered the rail, to hand it back on the way out. */
    private var lastContent: View? = null
    /** Until the page has something to focus, the rail stays shut even to Back. */
    private var ready = false
    private var isOpen = false
    /** When Back in the rail last asked to be pressed again. */
    private var backAt = -EXIT_WINDOW

    init {
        val sections = Section.entries.filter { it != Section.LISTS || Api.loggedIn }
        items = sections.map(::item)
        landing = items[sections.indexOf(current ?: Section.HOME)].apply { isSelected = true }
        // Up and Down stay in the rail, stopping at either end.
        items.forEachIndexed { i, item ->
            item.nextFocusUpId = items[maxOf(i - 1, 0)].id
            item.nextFocusDownId = items[minOf(i + 1, items.lastIndex)].id
        }
        // The red mark sits at the edge beside the page's own item, wherever the list lays out.
        val mark = activity.findViewById<View>(R.id.rail_mark)
        list.addOnLayoutChangeListener { _, _, top, _, _, _, _, _, _ ->
            mark.translationY = (top + landing.top + (landing.height - mark.height) / 2).toFloat()
        }

        // Shade and panel both darken from the start edge, whichever side that is: solid ink under
        // the icon column (shut) and under the pill and labels (open), so no artwork shows through
        // them as a row scrolls under the rail, then a feather that ends where the content begins.
        val fromStart = if (rtl) GradientDrawable.Orientation.RIGHT_LEFT else GradientDrawable.Orientation.LEFT_RIGHT
        val res = activity.resources
        activity.findViewById<View>(R.id.rail_shade).background = GradientDrawable(fromStart,
            band(activity.getColor(R.color.ink), res.getDimension(R.dimen.rail_width) / res.getDimension(R.dimen.content_start)))
        val pillEnd = res.getDimension(R.dimen.rail_pill_width) + 2 * res.getDimension(R.dimen.rail_item_inset)
        panel.background = GradientDrawable(fromStart,
            band(activity.getColor(R.color.ink_deep), pillEnd / res.getDimension(R.dimen.rail_panel_width)))

        // Toward the start edge with nothing further that way, the remote enters the rail. In
        // it, only Up and Down search (the item's own key listener takes the way back out).
        val intoRail = if (rtl) View.FOCUS_RIGHT else View.FOCUS_LEFT
        val root = activity.findViewById<BrowseFrameLayout>(R.id.rail_root)
        root.onFocusSearchListener = BrowseFrameLayout.OnFocusSearchListener { focused, direction ->
            when {
                focused == null -> null
                focused.inRail() -> if (direction == View.FOCUS_UP || direction == View.FOCUS_DOWN) null else focused
                direction == intoRail && ready && FocusFinder.getInstance().findNextFocus(root, focused, direction) == null -> enter()
                else -> null
            }
        }
        activity.window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { old, new ->
            val wasIn = old?.inRail() == true
            val isIn = new?.inRail() == true
            if (isIn && !wasIn) {
                lastContent = old
                open(true)
            } else if (wasIn && !isIn) {
                open(false)
            }
        }
    }

    /** The page has something to focus: from now on the rail can take the remote. */
    fun ready() {
        ready = true
    }

    /**
     * For home: Back from the content opens the rail, and Back in the rail leaves the app only when
     * pressed again within [EXIT_WINDOW], as the official app asks (module 10147).
     */
    fun openOnBack(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_BACK || !ready) return false
        if (event.action == KeyEvent.ACTION_UP) when {
            !rail.hasFocus() -> enter().requestFocus()
            event.eventTime - backAt <= EXIT_WINDOW -> activity.finish()
            else -> {
                backAt = event.eventTime
                activity.toast(activity.getString(R.string.exit_again))
            }
        }
        return true
    }

    /** Lets focus into the rail; it lands on the page's own item. */
    private fun enter(): View {
        rail.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        return landing
    }

    private fun item(section: Section): View {
        val item = activity.layoutInflater.inflate(R.layout.rail_item, list, false)
        item.id = View.generateViewId()
        item.findViewById<ImageView>(R.id.rail_icon).setImageResource(section.icon)
        item.findViewById<TextView>(R.id.rail_label).let {
            it.setText(section.label)
            // English labels take the app's Latin face, as every Latin title does.
            if (it.text.isLatin) it.typeface = activity.font(R.font.inter_medium)
            labels += it
        }
        item.contentDescription = activity.getString(section.label)
        item.setOnClickListener {
            backToContent() // close the rail first, so coming back finds the content, not the rail
            go(section)
        }
        val towardContent = if (rtl) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        item.setOnKeyListener { _, keyCode, event ->
            if (keyCode == towardContent && event.action == KeyEvent.ACTION_DOWN) backToContent().let { true } else false
        }
        // The pill follows focus: one view gliding, rather than a highlight per item.
        item.setOnFocusChangeListener { _, focused ->
            if (focused && isOpen) pill.animate().translationY(pillY(item)).setInterpolator(Motion.easeOut).setDuration(Motion.GLIDE).start()
        }
        list.addView(item)
        return item
    }

    private fun pillY(item: View) = (list.top + item.top).toFloat()

    private fun go(section: Section) {
        val row = section.row
        when {
            row != null && activity is MainActivity -> activity.jumpTo(row)
            row != null -> activity.startActivity(MainActivity.jump(activity, row)) // home comes back to the front, this page closes
            section == current -> Unit // already here
            section == Section.SEARCH -> activity.startActivity(Intent(activity, SearchActivity::class.java))
            section == Section.SETTINGS -> activity.startActivity(Intent(activity, SettingsActivity::class.java))
            else -> activity.startActivity(GridActivity.archive(activity, section.slug!!, activity.getString(section.label)))
        }
        // Top-level pages replace each other above home instead of piling up behind Back.
        if (row == null && section != current && activity !is MainActivity) activity.finish()
    }

    private fun backToContent() {
        (lastContent?.takeIf { it.isAttachedToWindow && !it.inRail() } ?: content())?.requestFocus()
    }

    /**
     * The rail's one moment. Opening, the panel slides in from the edge as the scrim dims the
     * page, the pill appears on the page's own item and the labels follow 20 ms apart; closing,
     * everything leaves together and quicker, and the rail shuts its scope behind the remote.
     */
    private fun open(open: Boolean) {
        isOpen = open
        items.forEach { it.isActivated = open }
        if (open) {
            panel.translationX = away
            pill.animate().cancel()
            pill.translationY = pillY(landing)
        } else {
            rail.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        val duration = if (open) Motion.EXPAND else Motion.EXIT
        panel.animate().alpha(if (open) 1f else 0f).translationX(if (open) 0f else away)
            .setInterpolator(Motion.easeOut).setDuration(duration).start()
        scrim.animate().alpha(if (open) .45f else 0f).setInterpolator(Motion.easeOut).setDuration(duration).start()
        pill.animate().alpha(if (open) 1f else 0f).setInterpolator(Motion.easeOut).setDuration(duration).start()
        labels.forEachIndexed { i, label ->
            if (open) label.translationX = away / 2
            label.animate().alpha(if (open) 1f else 0f).translationX(0f).setInterpolator(Motion.easeOut)
                .setStartDelay(if (open) i * Motion.STAGGER else 0L).setDuration(duration).start()
        }
    }

    private fun View.inRail(): Boolean {
        var view: View? = this
        while (view != null) {
            if (view === rail) return true
            view = view.parent as? View
        }
        return false
    }

    private companion object {
        const val EXIT_WINDOW = 3_000L
        /** Evenly spaced gradient stops: stop positions need API 29, so the curve is sampled. */
        const val BAND_STOPS = 16

        /**
         * [color] solid across the first [solid] of the width, then a smoothstep feather to clear:
         * no hard line where the solid ends, even over bright art. No blur, which a 2 GB TV can't afford.
         */
        fun band(color: Int, solid: Float) = IntArray(BAND_STOPS + 1) { i ->
            val t = ((i.toFloat() / BAND_STOPS - solid) / (1 - solid)).coerceIn(0f, 1f)
            color and 0xFFFFFF or ((1 - t * t * (3 - 2 * t)) * 255).roundToInt().shl(24)
        }
    }
}
