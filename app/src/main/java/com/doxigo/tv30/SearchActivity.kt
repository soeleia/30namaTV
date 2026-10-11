package com.doxigo.tv30

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.BrowseFrameLayout
import androidx.leanback.widget.FocusHighlight
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.OnItemViewSelectedListener
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
        root.onFocusSearchListener = BrowseFrameLayout.OnFocusSearchListener { focused, direction ->
            when {
                direction == View.FOCUS_UP && grid.hasFocus() && grid.selectedPosition < 6 -> bar.findViewById(androidx.leanback.R.id.lb_search_text_editor)
                direction == View.FOCUS_DOWN && bar.hasFocus() && grid.isFocusable -> grid
                else -> null
            }
        }
        return root
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
        moveToResults = !grid.isFocusable
        if (grid.isFocusable) grid.requestFocus()
    }

    private fun title(value: CharSequence) {
        heading.text = latin(requireContext(), value, R.font.inter_bold)
        heading.typeface = requireContext().font(if (value.isLatin) R.font.inter_bold else R.font.peyda_bold)
    }

    /**
     * Results for [text], or, until there are two letters to go on, 30nama's suggestions, so the
     * page never opens on an empty screen. Either way the row stands at once, a skeleton until
     * the first page lands; results head it as «نتایج», then with their count once it is known
     * (never the query again: it is in the field just above, and Latin set in Peyda loses its spaces).
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
        val context = requireContext()
        val items = ArrayObjectAdapter(PosterPresenter(112, 168))
        val pager = Pager(viewLifecycleOwner.lifecycleScope, items, { t -> t.toCard(context) { context.openDetails(t) } }, onEmpty = {
            if (query == q && q.isNotEmpty()) title(getString(R.string.no_results))
            moveToResults = false
        }, onError = { if (query == q) context.toast(it) }, skeleton = Skeleton(12, 112, 168), onFirst = { total ->
            if (query == q) {
                grid.isFocusable = true
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
