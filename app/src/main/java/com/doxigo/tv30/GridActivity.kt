package com.doxigo.tv30

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.AttributeSet
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
import org.json.JSONArray
import org.json.JSONObject

internal const val POSTER_GRID_ROW_SPACING_DP = 28

/**
 * A paged grid of titles: one archive slug (a catalogue, category or genre) or one of the user's
 * lists, with the rail at its start edge lit on the section it belongs to.
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val context = requireContext()
        val extras = requireActivity().intent
        val slug = extras.getStringExtra("slug")
        val group = extras.getStringExtra("group")
        genreMenu = extras.hasExtra("genres")
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
            if (group != null) showEmpty() else context.toast(getString(R.string.no_results))
        }, onError = { context.toast(it) }, skeleton = Skeleton(12, 112, 168)) { page ->
            val order = sorts[sort].first
            if (group != null) Api.userList(group, page, order) else Api.archive(slug.orEmpty(), page, order)
        }
        setOnItemViewSelectedListener(OnItemViewSelectedListener { _, item, _, _ -> pager.onSelected(item) })
        // The title bar's orb doubles as the sort button.
        setOnSearchClickedListener { chooseSort() }
        pager.reset()
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
    }

    /** The sort button names the order now, and tells TalkBack what it is for. */
    private fun showSort() {
        val button = titleViewAdapter?.searchAffordanceView as? Button ?: return
        button.text = getString(sorts[sort].second)
        button.contentDescription = dotted(getString(R.string.sort_by), button.text.toString())
    }

    /**
     * An empty list says so where the grid would be and offers somewhere to fill it from, as the
     * official app does: the suggested archive, which takes this page's place.
     */
    private fun showEmpty() {
        val dock = view?.findViewById<ViewGroup>(androidx.leanback.R.id.browse_grid_dock) ?: return
        if (dock.findViewById<View>(R.id.list_empty) != null) return
        val empty = layoutInflater.inflate(R.layout.list_empty, dock, false)
        val action = empty.findViewById<View>(R.id.list_empty_action)
        action.setOnClickListener {
            startActivity(GridActivity.archive(requireContext(), "suggested", getString(R.string.suggested)))
            requireActivity().finish()
        }
        // An empty grid would still take the remote (back down from the sort orb, say) and show nothing.
        dock.findViewById<View>(androidx.leanback.R.id.browse_grid)?.visibility = View.GONE
        dock.addView(empty)
        rise(listOf(empty))
        action.requestFocus()
        (activity as? GridActivity)?.rail?.ready() // the button is something to come back to
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
}
