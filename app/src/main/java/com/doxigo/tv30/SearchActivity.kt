package com.doxigo.tv30

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.BaseGridView
import androidx.leanback.widget.BrowseFrameLayout
import androidx.leanback.widget.ClassPresenterSelector
import androidx.leanback.widget.FocusHighlight
import androidx.leanback.widget.HorizontalGridView
import androidx.leanback.widget.ItemBridgeAdapter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.OnItemViewSelectedListener
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.SearchBar
import androidx.leanback.widget.SearchOrbView
import androidx.leanback.widget.VerticalGridPresenter
import androidx.leanback.widget.VerticalGridView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import java.util.Locale

class SearchActivity : TvActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.rail_screen)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(R.id.browse_host, SearchFragment()).commit()
        }
        // The search field takes focus at once, so the rail is ready from the start.
        Rail(this, Rail.Section.SEARCH) { supportFragmentManager.findFragmentById(R.id.browse_host)?.view }.ready()
    }
}

class SearchFragment : Fragment(), SearchBar.SearchBarListener {
    private lateinit var bar: SearchBar
    private lateinit var heading: TextView
    /** The recent searches: their heading and [strip], shown while the box is empty and there are some. */
    private lateinit var recents: LinearLayout
    private lateinit var strip: HorizontalGridView
    private val recentItems = ArrayObjectAdapter(ClassPresenterSelector()
        .addClassPresenter(String::class.java, Pill(clear = false))
        .addClassPresenter(ClearRecents::class.java, Pill(clear = true)))
    private lateinit var grid: VerticalGridView
    private lateinit var gridHolder: VerticalGridPresenter.ViewHolder
    private val presenter = VerticalGridPresenter(FocusHighlight.ZOOM_FACTOR_NONE, false).apply {
        numberOfColumns = 6
        shadowEnabled = false
    }
    /** What the results show: a query of two letters or more, or "" for the suggestions. */
    private var query: String? = null
    private var pager: Pager? = null
    private var waitingForTyping = false
    private var moveToResults = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = inflater.inflate(androidx.leanback.R.layout.lb_search_fragment, container, false) as BrowseFrameLayout
        val context = root.context
        val dp = resources.displayMetrics.density
        bar = root.findViewById(androidx.leanback.R.id.lb_search_bar)
        val dock = root.findViewById<FrameLayout>(androidx.leanback.R.id.lb_results_frame)
        dock.layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        recents = inflater.inflate(R.layout.search_recents, content, false) as LinearLayout
        content.addView(recents)
        // The recent searches line up as a row of posters does: the focused pill on the content's
        // start edge, the ones before it sliding under the rail.
        strip = recents.findViewById<HorizontalGridView>(R.id.search_recent_strip).apply {
            adapter = ItemBridgeAdapter(recentItems).apply {
                setAdapterListener(object : ItemBridgeAdapter.AdapterListener() {
                    override fun onCreate(viewHolder: ItemBridgeAdapter.ViewHolder) {
                        viewHolder.itemView.setOnClickListener { (viewHolder.item as? String)?.let(::rerun) ?: clearRecents() }
                    }
                })
            }
            windowAlignment = BaseGridView.WINDOW_ALIGN_BOTH_EDGE
            windowAlignmentOffset = paddingStart
            windowAlignmentOffsetPercent = 0f
            itemAlignmentOffset = 0
            itemAlignmentOffsetPercent = 0f
        }
        heading = TextView(context).apply {
            setTextAppearance(R.style.TextAppearance_Leanback_Header)
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            val inset = resources.getDimensionPixelSize(R.dimen.content_start)
            setPaddingRelative(inset, 0, inset, 0)
            widenSpaces()
        }
        content.addView(heading, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        presenter.setOnItemViewClickedListener(OnItemViewClickedListener { _, item, _, _ -> (item as? Card)?.onClick?.invoke() })
        presenter.setOnItemViewSelectedListener(OnItemViewSelectedListener { _, item, _, _ -> pager?.onSelected(item) })
        gridHolder = presenter.onCreateViewHolder(content)
        grid = gridHolder.gridView.apply {
            isFocusable = false
            setPaddingRelative(paddingStart, (12 * dp).toInt(), paddingEnd, (32 * dp).toInt())
            verticalSpacing = (POSTER_GRID_ROW_SPACING_DP * dp).toInt()
            prefetchAttachedRows()
        }
        bar.nextFocusDownId = grid.id
        content.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        dock.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.TOP).apply {
            topMargin = (108 * dp).toInt()
        })
        // Box, recent searches, results, top to bottom, whichever of the middle two are there.
        root.onFocusSearchListener = BrowseFrameLayout.OnFocusSearchListener { _, direction ->
            when {
                direction == View.FOCUS_UP && grid.hasFocus() && grid.selectedPosition < 6 -> if (recentsShown) strip else editor
                direction == View.FOCUS_UP && strip.hasFocus() -> editor
                direction == View.FOCUS_DOWN && bar.hasFocus() -> below()
                direction == View.FOCUS_DOWN && strip.hasFocus() && grid.isFocusable -> grid
                else -> null
            }
        }
        // Down from the box, ahead of the keyboard: see [down].
        return object : FrameLayout(context) {
            override fun dispatchKeyEventPreIme(event: KeyEvent) = down(event) || super.dispatchKeyEventPreIme(event)
        }.apply { addView(root) }
    }

    /**
     * Down from the box with the keyboard down and the caret at the end goes [below] before the
     * keyboard is offered it. A keyboard that is down still serves the box and sees every key
     * first, and what it makes of Down is its own: on Android TV 11 (CI's search check) the remote
     * stayed in the box over the recent searches. From Android 11, where the window can tell the
     * keyboard is down; Android 9 and 10 keep the focus search's way, as before.
     */
    private fun down(event: KeyEvent): Boolean {
        if (Build.VERSION.SDK_INT < 30 || event.keyCode != KeyEvent.KEYCODE_DPAD_DOWN || event.action != KeyEvent.ACTION_DOWN) return false
        val box = editor
        if (!box.isFocused || box.selectionEnd != box.length() || box.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) != false) return false
        return below()?.requestFocusFromTouch() == true
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val initial = savedInstanceState?.getString("query").orEmpty()
        bar.setSearchQuery(initial)
        bar.setSearchBarListener(this)
        @Suppress("DEPRECATION")
        bar.setSpeechRecognitionCallback {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, bar.hint)
            runCatching { startActivityForResult(intent, VOICE) }.onFailure { requireContext().toast(it) }
        }
        styleBar(view)
        search(initial, debounce = false)
        view.post { view.findViewById<View>(androidx.leanback.R.id.lb_search_text_editor)?.requestFocus() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("query", bar.findViewById<TextView>(androidx.leanback.R.id.lb_search_text_editor).text.toString())
    }

    override fun onPause() {
        bar.stopRecognition()
        super.onPause()
    }

    override fun onDestroyView() {
        pager?.cancel()
        presenter.onUnbindViewHolder(gridHolder)
        query = null
        super.onDestroyView()
    }

    /**
     * Leanback's bar in the app's own finish: the field a dark pill like the app's other controls,
     * ringed in white under the remote as a poster is (drawable/search_field, sized in
     * values/search.xml); the orb the round buttons' white disc when it has the remote, with a mic
     * drawn in the rail's stroke. The bar repaints the field's colours whenever the field or the
     * orb gains or loses the remote, so each keeps Leanback's listener and repaints after it.
     */
    private fun styleBar(root: View) {
        val context = root.context
        val field = root.findViewById<ViewGroup>(androidx.leanback.R.id.lb_search_bar_items) ?: return
        val editor = root.findViewById<TextView>(androidx.leanback.R.id.lb_search_text_editor) ?: return
        val orb = root.findViewById<SearchOrbView>(androidx.leanback.R.id.lb_search_bar_speech_orb) ?: return
        field.background = ContextCompat.getDrawable(context, R.drawable.search_field)
        field.setAddStatesFromChildren(true) // the ring follows the editor's focus
        // A long query stops short of the pill's round end, as it starts short of the other.
        editor.setPaddingRelative(0, 0, resources.getDimensionPixelSize(androidx.leanback.R.dimen.lb_search_bar_edit_text_margin_start), 0)
        // Ask for the UI's language on the keyboard, where the IME has it.
        if (Build.VERSION.SDK_INT >= 24) editor.imeHintLocales = LocaleList(Locale.getDefault())
        ContextCompat.getDrawable(context, R.drawable.search_mic)?.let(orb::setOrbIcon)
        val rest = orb.orbColors // the theme's, set as the orb is built
        val lit = SearchOrbView.Colors(context.getColor(R.color.text), context.getColor(R.color.text), context.getColor(R.color.ink))
        (if (orb.isFocused) lit else rest)?.let(orb::setOrbColors)
        for (control in listOf(editor, orb)) {
            val stock = control.onFocusChangeListener
            control.setOnFocusChangeListener { v, focused ->
                stock?.onFocusChange(v, focused)
                if (v === orb) (if (focused) lit else rest)?.let(orb::setOrbColors)
                paintField()
            }
        }
        paintField()
    }

    /**
     * The hint in text_dim (Leanback's grey falls short of 4.5:1 on the field) and the face by
     * script: Yekan Bakh for Persian, Inter for Latin (Yekan Bakh's own Latin is Lato's),
     * judged on the query, or on the hint while there is none.
     */
    private fun paintField() {
        val editor = view?.findViewById<TextView>(androidx.leanback.R.id.lb_search_text_editor) ?: return
        editor.setHintTextColor(editor.context.getColor(R.color.text_dim))
        val words: CharSequence = editor.text.ifEmpty { editor.hint ?: "" }
        editor.typeface = editor.context.font(if (words.isLatin) R.font.inter_regular else R.font.vazir)
    }

    @Deprecated("Fragment result API is overkill for one voice intent")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != VOICE || resultCode != Activity.RESULT_OK) return
        val spoken = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
        bar.setSearchQuery(spoken)
        onSearchQuerySubmit(spoken)
    }

    override fun onSearchQueryChange(newQuery: String) {
        paintField()
        search(newQuery, debounce = true)
    }

    override fun onSearchQuerySubmit(query: String) {
        search(query, debounce = false)
        focusResults()
    }

    override fun onKeyboardDismiss(query: String) = focusResults()

    private fun focusResults() {
        val next = below()
        moveToResults = next == null
        next?.requestFocus()
    }

    /** Down from the box: the recent searches while they show, else the results once they can take the remote. */
    private fun below(): View? = when {
        recentsShown -> strip
        grid.isFocusable -> grid
        else -> null
    }

    private val editor: TextView get() = bar.findViewById(androidx.leanback.R.id.lb_search_text_editor)

    private val recentsShown get() = recents.visibility == View.VISIBLE

    /** A recent search again: into the box, and the remote on to its results as they land (see [showRecents]). */
    private fun rerun(text: String) {
        bar.setSearchQuery(text)
        search(text, debounce = false)
    }

    private fun clearRecents() {
        Recents.clear(requireContext())
        showRecents(false)
    }

    /**
     * The recent searches, newest first and ending in Clear, while the box is empty. Going while the
     * remote is on them, they hand it on first, to the results or else the field: a view that leaves
     * with the focus sends it back to the field by itself, and Leanback's keyboard comes up with it.
     */
    private fun showRecents(show: Boolean) {
        val queries = if (show) Recents.read(requireContext()) else emptyList()
        if (queries.isEmpty()) {
            if (strip.hasFocus()) (if (grid.isFocusable) grid else editor).requestFocus()
            recents.visibility = View.GONE
            return
        }
        val items = queries + ClearRecents
        if (recentItems.unmodifiableList<Any>() != items) {
            recentItems.setItems(items, null)
            strip.selectedPosition = 0
        }
        recents.visibility = View.VISIBLE
    }

    private fun title(value: CharSequence) {
        heading.text = latin(requireContext(), value, R.font.inter_bold)
        heading.typeface = requireContext().font(if (value.isLatin) R.font.inter_bold else R.font.peyda_bold)
    }

    /**
     * Results for [text], or, until there are two letters to go on, the recent searches over
     * 30nama's suggestions, so the page never opens on an empty screen. Either way the row stands at
     * once, a skeleton until the first page lands; results head it as «نتایج», then with their count
     * once it is known (never the query again: it is in the field just above, and Latin set in Peyda
     * loses its spaces). Opening a result keeps its query among the recent searches.
     */
    private fun search(text: String, debounce: Boolean) {
        val q = text.trim().takeIf { it.length >= 2 }.orEmpty()
        if (q == query) {
            if (!debounce && waitingForTyping) {
                waitingForTyping = false
                pager?.reset()
            }
            return
        }
        query = q
        pager?.cancel()
        waitingForTyping = debounce && q.isNotEmpty()
        moveToResults = false
        grid.isFocusable = false
        // A recent search being run again keeps the remote until its results can take it.
        if (!strip.hasFocus()) showRecents(q.isEmpty())
        val context = requireContext()
        val items = ArrayObjectAdapter(PosterPresenter(112, 168))
        val pager = Pager(viewLifecycleOwner.lifecycleScope, items, { t ->
            t.toCard(context) {
                if (q.isNotEmpty()) Recents.add(context, q)
                context.openDetails(t)
            }
        }, onEmpty = {
            if (query == q) {
                if (q.isNotEmpty()) title(getString(R.string.no_results))
                showRecents(q.isEmpty())
            }
            moveToResults = false
        }, onError = {
            if (query == q) {
                context.toast(it)
                showRecents(q.isEmpty())
            }
        }, skeleton = Skeleton(12, 112, 168), onFirst = { total ->
            if (query == q) {
                grid.isFocusable = true
                showRecents(q.isEmpty())
                if (q.isNotEmpty() && total != null) title(resources.getQuantityString(R.plurals.search_count, total, total))
                if (moveToResults) focusResults()
            }
        }) { page ->
            if (page == 1 && waitingForTyping) {
                delay(600)
                waitingForTyping = false
            }
            if (q.isEmpty()) Api.archive("suggested", page, "relevant") else Api.search(q, page)
        }
        this.pager = pager
        title(getString(if (q.isEmpty()) R.string.suggested else R.string.search_results))
        presenter.onBindViewHolder(gridHolder, items)
        pager.reset()
    }

    private companion object {
        const val VOICE = 1
    }
}

/** The end of the recent searches: forget them. */
private object ClearRecents

/**
 * A recent search as the app's secondary pill (Widget.App.Button), set in its script's face; Clear
 * ends the strip as a bare pill with the bin (Widget.App.Button.Clear), so it never reads as one
 * more query.
 */
private class Pill(private val clear: Boolean) : Presenter() {
    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val context = parent.context
        val button = if (clear) Button(context, null, 0, R.style.Widget_App_Button_Clear).apply {
            setText(R.string.search_recent_clear)
            contentDescription = context.getString(R.string.search_recent_clear_all)
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.settings_ic_clear, 0, 0, 0)
        } else Button(context).apply { maxWidth = (280 * resources.displayMetrics.density).toInt() }
        button.widenSpaces()
        return ViewHolder(button)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val query = item as? String ?: return
        val button = viewHolder.view as Button
        button.typeface = button.context.font(if (query.isLatin) R.font.inter_medium else R.font.vazirmatn_medium)
        button.text = latin(button.context, query, R.font.inter_medium)
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) = Unit
}

/**
 * The last [CAP] searches that opened a title, newest first. A search counts once one of its
 * results is opened rather than when it is submitted: with a remote most people type and press
 * Down, never the keyboard's search key, and a query that led somewhere is worth running again
 * where a typo or a dead end is not. One list for both languages, as a query runs the same in either.
 */
object Recents {
    const val CAP = 8
    private const val KEY = "recent"
    private val SPACES = Regex("\\s+")

    fun read(context: Context): List<String> = tidy(prefs(context).getString(KEY, null)?.split('\n').orEmpty())

    fun add(context: Context, query: String) = prefs(context).edit().putString(KEY, push(read(context), query).joinToString("\n")).apply()

    fun clear(context: Context) = prefs(context).edit().remove(KEY).apply()

    /** [query] at the front with its spacing tidied (so never a line break), the same query dropped from further back (see [tidy]). */
    fun push(recents: List<String>, query: String): List<String> {
        val q = query.trim().replace(SPACES, " ")
        if (q.isEmpty()) return recents
        return tidy(listOf(q) + recents)
    }

    /**
     * Each query once, in Persian letters, in any case, the oldest past [CAP] gone. An Arabic
     * keyboard types ي, ى and ك where Persian has ی and ک: the same word, which 30nama finds
     * either way, so the same query (and one kept twice before this counts once).
     */
    private fun tidy(queries: List<String>) = queries.filter(String::isNotEmpty)
        .map { it.replace('ي', 'ی').replace('ى', 'ی').replace('ك', 'ک') }
        .distinctBy { it.lowercase() }.take(CAP)

    private fun prefs(context: Context) = context.getSharedPreferences("search", Context.MODE_PRIVATE)
}
