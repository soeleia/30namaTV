package com.doxigo.tv30

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.util.AttributeSet
import android.view.FocusFinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.leanback.app.VerticalGridSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.FocusHighlight
import androidx.leanback.widget.ObjectAdapter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.OnItemViewSelectedListener
import androidx.leanback.widget.TitleViewAdapter
import androidx.leanback.widget.VerticalGridPresenter
import androidx.leanback.widget.VerticalGridView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

internal const val POSTER_GRID_ROW_SPACING_DP = 28

/**
 * A paged grid of titles: one archive slug (a catalogue, category or genre) or one of the user's
 * lists, with the rail at its start edge lit on the section it belongs to. Movies, Series and
 * Anime can be narrowed by genre, country, IMDb score and decade.
 */
class GridActivity : TvActivity() {
    var rail: Rail? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.rail_screen)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(R.id.browse_host, GridFragment()).commit()
        }
        val slug = intent.getStringExtra("slug")
        val section = when {
            intent.hasExtra("group") -> Rail.Section.LISTS
            else -> Rail.Section.entries.firstOrNull { it.slug != null && it.slug == slug } ?: Rail.Section.CATEGORIES
        }
        rail = Rail(this, section) { supportFragmentManager.findFragmentById(R.id.browse_host)?.view }
    }

    companion object {
        fun archive(context: Context, slug: String, name: String): Intent =
            Intent(context, GridActivity::class.java).putExtra("slug", slug).putExtra("name", name)

        fun list(context: Context, group: String, name: String): Intent =
            Intent(context, GridActivity::class.java).putExtra("group", group).putExtra("name", name)

        fun genres(context: Context, entries: JSONArray): Intent =
            Intent(context, GridActivity::class.java).putExtra("genres", entries.toString()).putExtra("name", context.getString(R.string.genres))
    }
}

class GridFragment : VerticalGridSupportFragment() {
    private lateinit var pager: Pager
    private lateinit var sorts: List<Pair<String, Int>>
    private var sort = 0
    private var genreMenu = false
    /** advanced_search's name for this catalogue's titles; null where the page has no filters. */
    private var type: String? = null
    private var filter = Filter()
    private var choices: FilterChoices? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val context = requireContext()
        val extras = requireActivity().intent
        val slug = extras.getStringExtra("slug")
        val group = extras.getStringExtra("group")
        genreMenu = extras.hasExtra("genres")
        type = Filter.TYPES[slug] // lists and the genre menu have no slug
        setTitle(extras.getStringExtra("name").orEmpty())
        // The official archive and list screens' orders, less 30nama's own rating (never shown).
        val shared = listOf("update" to R.string.sort_update, "year" to R.string.sort_year, "favorite" to R.string.sort_favorite,
            "imdb-rate" to R.string.sort_imdb)
        sorts = listOf((if (group != null) "add_list" to R.string.sort_added else "relevant" to R.string.sort_relevant)) + shared

        setGridPresenter(VerticalGridPresenter(FocusHighlight.ZOOM_FACTOR_NONE, false).apply {
            numberOfColumns = if (genreMenu) 4 else 6
            shadowEnabled = false
        })
        val items = ArrayObjectAdapter(if (genreMenu) TilePresenter(172, 108) else PosterPresenter(112, 168))
        adapter = items
        // The rail waits for the first page (the skeleton's bones can't take focus), or it would be
        // the only thing to focus and open itself.
        items.registerObserver(object : ObjectAdapter.DataObserver() {
            override fun onChanged() = ready()
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = ready()
            private fun ready() {
                if (items.size() > 0 && items[0] is Card) (activity as? GridActivity)?.rail?.ready()
            }
        })
        setOnItemViewClickedListener(OnItemViewClickedListener { _, item, _, _ -> (item as? Card)?.onClick?.invoke() })
        if (genreMenu) {
            items.setItems(JSONArray(extras.getStringExtra("genres")).objects().map { it.toMenuCard(context) }, null)
            return
        }
        // Two rows of bones at the posters' size until a page lands: a cold grid (or a new sort)
        // takes seconds over a VPN, and a blank page reads as broken.
        pager = Pager(lifecycleScope, items, { t -> t.toCard(context) { context.openDetails(t) } }, onEmpty = {
            when {
                group != null -> showEmpty(R.string.lists_empty_title, R.string.lists_empty_hint, R.string.suggested_archive, focus = true) {
                    startActivity(GridActivity.archive(requireContext(), "suggested", getString(R.string.suggested)))
                    requireActivity().finish()
                }
                // The remote stays on the filter just set; the way out is a step down.
                !filter.isEmpty -> showEmpty(R.string.filter_empty_title, R.string.filter_empty_hint, R.string.filter_clear, focus = false) {
                    filter(Filter(), view?.findViewById(R.id.filter_genre))
                }
                else -> context.toast(getString(R.string.no_results))
            }
        }, onError = { context.toast(it) }, skeleton = Skeleton(12, 112, 168)) { page ->
            // Read when the page is asked for: a new sort or filter resets the pager, and the pages
            // still in flight for the old ones are dropped.
            val order = sorts[sort].first
            val type = type
            when {
                group != null -> Api.userList(group, page, order)
                type != null && !filter.isEmpty -> Api.advancedSearch(filter.parameters(type), page, order)
                else -> Api.archive(slug.orEmpty(), page, order)
            }
        }
        setOnItemViewSelectedListener(OnItemViewSelectedListener { _, item, _, _ -> pager.onSelected(item) })
        // The title bar's orb doubles as the sort button.
        setOnSearchClickedListener { chooseSort() }
        pager.reset()
        // The choices come once a run (per language), beside the first page.
        if (type != null) lifecycleScope.launch {
            choices = runCatching { FilterChoices.load() }.getOrNull() ?: return@launch
            showFilters(reveal = true)
        }
    }

    /** The page name and the sort button (layout/grid_header), in place of Leanback's title bar and its search orb. */
    override fun onInflateTitleView(inflater: LayoutInflater, parent: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.grid_header, parent, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // The page name is the server's (a genre may be Persian even in English): face by script.
        val name = requireActivity().intent.getStringExtra("name").orEmpty()
        view.findViewById<android.widget.TextView>(androidx.leanback.R.id.title_text)
            ?.typeface = requireContext().font(if (name.isLatin) R.font.inter_bold else R.font.peyda_bold)
        // Leanback centres the grid; pin it to the start instead, so its first column lines up
        // under the page title, past the rail (its own padding is browsePaddingStart).
        view.findViewById<VerticalGridView>(androidx.leanback.R.id.browse_grid)?.let { grid ->
            (grid.layoutParams as? android.widget.FrameLayout.LayoutParams)?.gravity = android.view.Gravity.START or android.view.Gravity.TOP
            grid.verticalSpacing = (POSTER_GRID_ROW_SPACING_DP * resources.displayMetrics.density).toInt()
            // Lay out and attach about a row beyond each edge: Coil only loads into attached
            // views, so the next row's posters download before they scroll in (RecyclerView's
            // prefetch binds without attaching, so it cannot do this).
            grid.prefetchAttachedRows()
        }
        if (genreMenu) {
            view.findViewById<View>(R.id.grid_sort)?.visibility = View.GONE
            (activity as? GridActivity)?.rail?.ready()
        } else showSort()
        if (type != null) {
            view.findViewById<View>(R.id.grid_filters).visibility = View.INVISIBLE
            if (choices != null) showFilters(reveal = false) // a view rebuilt over the same fragment
        }
    }

    /** The sort button names the order now, and tells TalkBack what it is for. */
    private fun showSort() {
        val button = titleViewAdapter?.searchAffordanceView as? Button ?: return
        button.text = getString(sorts[sort].second)
        button.contentDescription = dotted(getString(R.string.sort_by), button.text.toString())
    }

    /**
     * The filter pills, each naming its choice (or its kind, unset) on the brighter glass while
     * set, and clear beside them while any is. With [reveal], the row fades in as the choices land.
     */
    private fun showFilters(reveal: Boolean) {
        val bar = view?.findViewById<View>(R.id.grid_filters) ?: return
        val choices = choices ?: return
        val fa = Api.language == "fa"
        fun Button.show(kind: Int, chosen: String?, unset: String = getString(kind), pick: () -> Unit) {
            text = chosen ?: unset
            contentDescription = if (chosen == null) getString(kind) else dotted(getString(kind), chosen)
            isActivated = chosen != null // the brighter glass (button_filter)
            setOnClickListener { pick() }
        }
        val genre = bar.findViewById<Button>(R.id.filter_genre)
        genre.show(R.string.filter_genre, choices.genres.nameOf(filter.genre)) {
            pick(genre, R.string.filter_genre, R.string.filter_all_genres, choices.genres, filter.genre) { filter.copy(genre = it) }
        }
        val country = bar.findViewById<Button>(R.id.filter_country)
        country.show(R.string.filter_country, choices.countries.nameOf(filter.country)) {
            pick(country, R.string.filter_country, R.string.filter_all_countries, choices.countries, filter.country) { filter.copy(country = it) }
        }
        val score = bar.findViewById<Button>(R.id.filter_score)
        val scores = choices.scores.map { FilterOption(Filter.scoreLabel(it, fa), it) }
        // Unset, its kind in one word in either language.
        score.show(R.string.sort_imdb, scores.nameOf(filter.score), unset = "IMDb") {
            pick(score, R.string.sort_imdb, R.string.filter_any_score, scores, filter.score) { filter.copy(score = it) }
        }
        val decade = bar.findViewById<Button>(R.id.filter_decade)
        val decades = choices.decades.map { FilterOption(getString(R.string.filter_decade_value, it.toString().localDigits()), it) }
        decade.show(R.string.filter_decade, decades.nameOf(filter.decade)) {
            pick(decade, R.string.filter_decade, R.string.filter_any_decade, decades, filter.decade) { filter.copy(decade = it) }
        }
        val clear = bar.findViewById<View>(R.id.filter_clear)
        clear.setOnClickListener { filter(Filter(), genre) }
        val set = !filter.isEmpty
        if (set != (clear.visibility == View.VISIBLE)) {
            clear.animate().cancel()
            if (set) {
                clear.alpha = 0f
                clear.visibility = View.VISIBLE
                clear.animate().alpha(1f).setInterpolator(Motion.easeOut).setDuration(Motion.ENTER).start()
            } else clear.visibility = View.INVISIBLE // at once: it can't keep the remote while it fades
        }
        if (bar.visibility != View.VISIBLE) {
            bar.visibility = View.VISIBLE
            if (reveal) {
                bar.alpha = 0f
                bar.animate().alpha(1f).setInterpolator(Motion.easeOut).setDuration(Motion.ENTER).start()
            }
        }
    }

    /** [options] after a first row that takes the filter off, from choose's panel; [set] makes the filter a pick gives. */
    private fun <T> pick(opener: View, title: Int, none: Int, options: List<FilterOption<T>>, current: T?, set: (T?) -> Filter) {
        val at = options.indexOfFirst { it.value == current } + 1
        requireActivity().choose(getString(title), listOf(getString(none)) + options.map { it.name }, at) { which ->
            filter(set(options.getOrNull(which - 1)?.value), opener)
        }
    }

    /** A new filter starts the grid over, as a new sort does; [focus] takes the remote if what had it goes (clear, the empty result's button). */
    private fun filter(next: Filter, focus: View?) {
        if (next == filter) return
        filter = next
        if (view?.findFocus()?.let { it.id == R.id.filter_clear || it.id == R.id.list_empty_action } == true) focus?.requestFocus()
        showFilters(reveal = false)
        hideEmpty()
        pager.reset()
    }

    /**
     * Nothing here says so where the grid would be, with one button: a list offers somewhere to
     * fill it from, as the official app does; a filtered catalogue takes its filters off. With
     * [focus], the button takes the remote.
     */
    private fun showEmpty(title: Int, hint: Int, action: Int, focus: Boolean, onAction: () -> Unit) {
        val dock = view?.findViewById<ViewGroup>(androidx.leanback.R.id.browse_grid_dock) ?: return
        if (dock.findViewById<View>(R.id.list_empty) != null) return
        val empty = layoutInflater.inflate(R.layout.list_empty, dock, false)
        empty.findViewById<TextView>(R.id.list_empty_title).setText(title)
        empty.findViewById<TextView>(R.id.list_empty_hint).setText(hint)
        val button = empty.findViewById<Button>(R.id.list_empty_action)
        button.setText(action)
        button.setOnClickListener { onAction() }
        // An empty grid would still take the remote (back down from the sort orb, say) and show nothing.
        dock.findViewById<View>(androidx.leanback.R.id.browse_grid)?.visibility = View.GONE
        dock.addView(empty)
        rise(listOf(empty))
        if (focus) button.requestFocus()
        (activity as? GridActivity)?.rail?.ready() // the button is something to come back to
    }

    private fun hideEmpty() {
        val dock = view?.findViewById<ViewGroup>(androidx.leanback.R.id.browse_grid_dock) ?: return
        dock.findViewById<View>(R.id.list_empty)?.let(dock::removeView)
        dock.findViewById<View>(androidx.leanback.R.id.browse_grid)?.visibility = View.VISIBLE
    }

    private fun chooseSort() {
        requireActivity().choose(getString(R.string.sort_by), sorts.map { getString(it.second) }, sort) { which ->
            if (which != sort) {
                sort = which
                showSort()
                pager.reset()
            }
        }
    }
}

internal fun JSONObject.toMenuCard(context: Context): Card {
    val name = str("name").orEmpty()
    val count = optInt("count").takeIf { it > 0 }?.let { context.getString(R.string.titles_count, it.grouped()) }
    return Card(name, count, str("image", "webp", "full") ?: str("image", "webp", "thumb")) {
        context.startActivity(GridActivity.archive(context, str("slug").orEmpty(), name))
    }
}

/**
 * A grid's title bar (layout/grid_header), standing in as Leanback's title view: Leanback sets the
 * page name here and gives the sort button the search orb's click and its place for Up.
 */
class GridHeader(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs), TitleViewAdapter.Provider {
    private val adapter = object : TitleViewAdapter() {
        override fun getSearchAffordanceView(): View = this@GridHeader.findViewById(R.id.grid_sort)
        override fun setTitle(titleText: CharSequence?) {
            this@GridHeader.findViewById<TextView>(androidx.leanback.R.id.title_text).text = titleText
        }
    }

    override fun getTitleViewAdapter(): TitleViewAdapter = adapter

    /**
     * Between the bar's own buttons. Leanback's title helper sends Up from anything in the bar
     * back to the sort button and the reading-forward key down to the grid; here Up, Left and
     * Right reach the button that way in the bar (Up from sort stays), and only Down or past the
     * bar's ends does Leanback's way apply.
     */
    override fun focusSearch(focused: View?, direction: Int): View? {
        // Asked by a child passing the search up, so [focused] is in the bar.
        if (direction == FOCUS_DOWN || focused == null) return super.focusSearch(focused, direction)
        return FocusFinder.getInstance().findNextFocus(this, focused, direction)
            ?: if (direction == FOCUS_UP) focused else super.focusSearch(focused, direction)
    }

    /**
     * Up from the grid lands on what is above the poster it leaves: a filter over the first
     * columns, sort over the last. Never on clear, which one more OK would act on.
     */
    override fun onRequestFocusInDescendants(direction: Int, previouslyFocusedRect: Rect?): Boolean {
        if (direction == FOCUS_UP && previouslyFocusedRect != null) {
            val above = FocusFinder.getInstance().findNextFocusFromRect(this, previouslyFocusedRect, direction)
            val target = if (above?.id == R.id.filter_clear) findViewById(R.id.filter_decade) else above
            if (target?.requestFocus() == true) return true
        }
        return super.onRequestFocusInDescendants(direction, previouslyFocusedRect)
    }
}

/** One of a filter's choices: the words for it and what advanced_search is sent. */
internal data class FilterOption<T>(val name: String, val value: T)

private fun <T> List<FilterOption<T>>.nameOf(value: T?): String? = value?.let { v -> firstOrNull { it.value == v }?.name }

/** What advanced_search_parameters offers a catalogue, in the app's language. */
internal class FilterChoices(json: JSONObject) {
    val genres = json.choices("genre")
    val countries = json.choices("country")
    /** The floors only: "under 5" comes as -5. */
    val scores = json.optJSONArray("imdb_rate").objects().map { it.optDouble("value") }.filter { it > 0 }
    /** The first year of each decade 30nama has titles for, newest first. */
    val decades: List<Int> = (json.optInt("max_year", 2020) / 10 * 10 downTo json.optInt("min_year", 1950) / 10 * 10 step 10).toList()

    private fun JSONObject.choices(key: String) = optJSONArray(key).objects()
        .mapNotNull { c -> c.str("value")?.let { FilterOption(c.str("name") ?: it, it) } }

    companion object {
        private var cached: Pair<String, FilterChoices>? = null

        /** Once a run per language: the names come in it, and the lists change with 30nama's catalogue, not by the hour. */
        suspend fun load(): FilterChoices {
            val language = Api.language
            cached?.takeIf { it.first == language }?.let { return it.second }
            return FilterChoices(Api.filterChoices()).also { cached = language to it }
        }
    }
}

/** What a catalogue is narrowed to; nothing set, it is the plain archive. */
internal data class Filter(val genre: String? = null, val country: String? = null, val score: Double? = null, val decade: Int? = null) {
    val isEmpty get() = this == Filter()

    /**
     * advanced_search's `parameters` for [type]'s streamable titles. In the shapes the server reads
     * (tried against it): `type` and `genre` as lists, which it ignores as strings; `country` as a
     * string, which as a list finds nothing; the score and years as numbers.
     */
    fun parameters(type: String): JSONObject = JSONObject().put("stream", "true").put("type", JSONArray().put(type)).apply {
        genre?.let { put("genre", JSONArray().put(it)) }
        country?.let { put("country", it) }
        score?.let { put("imdb_rate", it) }
        decade?.let { put("min_year", it).put("max_year", it + 9) }
    }

    companion object {
        /** The catalogue slugs that can be narrowed, and the type advanced_search knows each one's titles by. */
        val TYPES = mapOf("movies" to "movie", "series" to "series", "anime" to "anime")

        /**
         * A score floor as the viewer picks by it: «IMDb ۷+», «IMDb ۷٫۵+». Read left to right in
         * Persian too, as the mark and its number are on every poster; the closing mark keeps the
         * plus from flipping to the front.
         */
        fun scoreLabel(floor: Double, fa: Boolean): String {
            val number = if (floor % 1.0 == 0.0) floor.toInt().toString() else floor.toString()
            return "IMDb ${if (fa) number.replace('.', '٫').persianDigits() else number}+\u200E"
        }
    }
}
