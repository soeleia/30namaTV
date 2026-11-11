package com.doxigo.tv30

import android.animation.AnimatorInflater
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.speech.RecognizerIntent
import android.util.TypedValue
import android.view.FocusFinder
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
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
import org.json.JSONObject
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
        numberOfColumns = COLUMNS
        shadowEnabled = false
    }
    /** Recent searches, heading and results, which step aside for [keys]. */
    private lateinit var content: LinearLayout
    /** The box's own Persian keyboard ([keyboard]), out while [open], and the button beside the field that brings it out. */
    private lateinit var keys: LinearLayout
    private lateinit var toggle: Button
    private var open = false
    /** Where the remote comes back into the keys: the key it was last on, the first letter until then. */
    private lateinit var firstKey: View
    private var lastKey: View? = null
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
        content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
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
            appearance(R.style.TextAppearance_Leanback_Header)
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            val inset = resources.getDimensionPixelSize(R.dimen.content_start)
            setPaddingRelative(inset, 0, inset, 0)
            widenSpaces()
        }
        content.addView(heading, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        presenter.setOnItemViewClickedListener(OnItemViewClickedListener { _, item, _, _ -> (item as? Card)?.onClick?.invoke() })
        // Only while the remote is on the results: Leanback selects the first poster as it lays
        // them out, focus or no focus, and the pager took that for the viewer reaching the list and
        // fetched a second page for every query typed, one more search against a rate-limited host.
        // ponytail: the remote arriving on the first poster selects nothing new, so page two starts
        // with its first move; tell the pager on arrival if a held D-pad ever outruns that.
        presenter.setOnItemViewSelectedListener(OnItemViewSelectedListener { _, item, _, _ -> if (grid.hasFocus()) pager?.onSelected(item) })
        gridHolder = presenter.onCreateViewHolder(content)
        grid = gridHolder.gridView.apply {
            isFocusable = false
            setPaddingRelative(paddingStart, (12 * dp).toInt(), paddingEnd, (32 * dp).toInt())
            verticalSpacing = (POSTER_GRID_ROW_SPACING_DP * dp).toInt()
            // The posters' own width, fixed: measuring columns that wrap their posters, Leanback reads
            // the rows it laid out last, and three of them fall short of six as the keys hand the
            // results their width back (GridLayoutManager.processRowSizeSecondary).
            setColumnWidth((112 * dp).toInt())
            prefetchAttachedRows()
        }
        bar.nextFocusDownId = grid.id
        content.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        dock.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.TOP).apply {
            topMargin = (108 * dp).toInt()
        })
        // Over the content, so a recent search scrolled past the results' start slides under it.
        keys = keyboard(context)
        dock.addView(keys, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            topMargin = (108 * dp).toInt()
            marginStart = resources.getDimensionPixelSize(R.dimen.content_start)
        })
        // The keyboard's button ends the bar, past the field: a secondary pill with its glyph.
        toggle = Button(context, null, 0, R.style.Widget_App_Button_Icon).apply {
            setText(R.string.search_keyboard)
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.search_keyboard, 0, 0, 0)
            setOnClickListener {
                showKeys(!open)
                SearchKeys.choose(context, open)
            }
            widenSpaces()
        }
        bar.addView(toggle, RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            addRule(RelativeLayout.END_OF, androidx.leanback.R.id.lb_search_bar_items)
            addRule(RelativeLayout.CENTER_VERTICAL)
            marginStart = resources.getDimensionPixelSize(R.dimen.button_gap)
        })
        // Box, recent searches, results, top to bottom, whichever of the middle two are there;
        // the keys at the start beside them (see [offKeys]).
        root.onFocusSearchListener = BrowseFrameLayout.OnFocusSearchListener { focused, direction ->
            when {
                keys.hasFocus() -> offKeys(focused, direction)
                direction == View.FOCUS_UP && (grid.hasFocus() && grid.selectedPosition < columns || note?.hasFocus() == true) ->
                    if (recentsShown) strip else box(focused)
                direction == View.FOCUS_UP && strip.hasFocus() -> box(focused)
                direction == View.FOCUS_DOWN && bar.hasFocus() -> under()
                direction == View.FOCUS_DOWN && strip.hasFocus() -> results()
                else -> null
            }
        }
        // Down from the box, ahead of the keyboard: see [down]; after it, with nothing under the box
        // yet: see [early]. Back puts the keys away: see [back].
        return object : FrameLayout(context) {
            override fun dispatchKeyEventPreIme(event: KeyEvent) = down(event) || super.dispatchKeyEventPreIme(event)
            override fun dispatchKeyEvent(event: KeyEvent) = back(event) || early(event) || super.dispatchKeyEvent(event)
        }.apply { addView(root) }
    }

    /**
     * The box's own Persian keyboard, for a TV whose keyboard has no Persian: Netflix's grid in the
     * alphabet's order, seven keys to a row and right to left in either language, as Persian reads,
     * under a row of space and delete. The digits run on after ؤ and Close fills the last
     * row. A letter or digit is its glyph alone, on the white disc under the remote as a transport
     * button is (trailer_button); the rest are the secondary pill. Built shut: see [showKeys].
     */
    private fun keyboard(context: Context) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        val cell = resources.getDimensionPixelSize(R.dimen.button_target)
        val feather = resources.getDimensionPixelSize(R.dimen.search_keys_feather)
        setPaddingRelative(0, 0, feather, 0)
        background = GradientDrawable(if (rtl) GradientDrawable.Orientation.RIGHT_LEFT else GradientDrawable.Orientation.LEFT_RIGHT,
            Rail.band(context.getColor(R.color.ink), KEY_COLUMNS * cell / (KEY_COLUMNS * cell + feather).toFloat()))
        fun row() = LinearLayout(context).also {
            it.layoutDirection = View.LAYOUT_DIRECTION_RTL
            addView(it)
        }
        fun add(row: LinearLayout, key: View, span: Int, pill: Boolean, action: () -> Unit) = key.run {
            isFocusable = true
            setOnClickListener { action() }
            setOnFocusChangeListener { v, focused -> if (focused) lastKey = v }
            background = ContextCompat.getDrawable(context, if (pill) R.drawable.button else R.drawable.trailer_button)
            stateListAnimator = AnimatorInflater.loadStateListAnimator(context, R.animator.button_lift)
            val margin = if (pill) resources.getDimensionPixelSize(R.dimen.search_key_pill_margin) else 0
            row.addView(this, LinearLayout.LayoutParams(span * cell - 2 * margin, cell).apply {
                marginStart = margin
                marginEnd = margin
            })
        }
        fun label(text: String, letter: Boolean) = TextView(context).apply {
            gravity = Gravity.CENTER
            if (letter) {
                typeface = context.font(R.font.vazirmatn_medium)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.search_key_text))
                setTextColor(context.getColorStateList(R.color.on_button))
            } else appearance(R.style.TextAppearance_App_Button)
            this.text = text
        }
        row().let { top ->
            add(top, label(getString(R.string.search_key_space), false), 5, true) { type(" ") }
            add(top, ImageView(context).apply {
                setImageResource(R.drawable.search_key_delete)
                scaleType = ImageView.ScaleType.CENTER
                contentDescription = getString(R.string.search_key_delete)
                setOnLongClickListener { delete(all = true); true }
            }, 2, true) { delete(all = false) }
        }
        for (letters in KEYS.chunked(KEY_COLUMNS)) {
            val row = row()
            for (letter in letters) add(row, label(letter.toString(), true), 1, false) { type(letter.toString()) }
        }
        val last = getChildAt(childCount - 1) as LinearLayout
        add(last, label(getString(R.string.search_key_close), false), KEY_COLUMNS - last.childCount, true) { showKeys(false, toResults = true) }
        firstKey = (getChildAt(1) as ViewGroup).getChildAt(0)
    }

    /**
     * The keys out or away. Out, they come in from the start edge as the rail's panel does, the
     * remote on the key it was last on, and the results step aside to three columns beside them,
     * fading in where they land (a reflow can't glide). Away, the keys fade back toward the edge,
     * the remote going on to the results ([toResults]) or else the button, and the results take the
     * width again. Under Reduce Motion both are fades alone.
     */
    private fun showKeys(show: Boolean, toResults: Boolean = false) {
        if (open == show) return
        open = show
        toggle.setText(if (show) R.string.search_keyboard_close else R.string.search_keyboard)
        val slide = Motion.travel(resources.getDimension(R.dimen.rail_slide)) * if (rtl) 1 else -1
        if (show) {
            keys.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            keys.visibility = View.VISIBLE
            keys.alpha = 0f
            keys.translationX = slide
            keys.animate().alpha(1f).translationX(0f).setInterpolator(Motion.emph).setDuration(Motion.EXPAND).start()
            (lastKey ?: firstKey).requestFocus()
        } else {
            if (keys.hasFocus()) ((if (toResults) below() else null) ?: toggle).requestFocus()
            keys.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            keys.animate().alpha(0f).translationX(slide / 2).setInterpolator(Motion.easeOut).setDuration(Motion.EXIT)
                .withEndAction { if (!open) keys.visibility = View.GONE }.start()
        }
        // Set again rather than changed in place: a start margin only resolves as the params are set.
        content.layoutParams = (content.layoutParams as ViewGroup.MarginLayoutParams).apply {
            marginStart = if (show) KEY_COLUMNS * resources.getDimensionPixelSize(R.dimen.button_target) + resources.getDimensionPixelSize(R.dimen.search_keys_feather) else 0
        }
        grid.setNumColumns(columns)
        // Its selected poster lands in another row at the new width; results away from the remote start from the top again.
        if (!grid.hasFocus()) grid.selectedPosition = 0
        content.alpha = 0f
        content.animate().alpha(1f).setInterpolator(Motion.easeOut).setDuration(Motion.ENTER).start()
    }

    /** A key into the box, at its end, where the keys only ever write; the bar hands it on as typing (onSearchQueryChange). */
    private fun type(text: String) {
        val box = editor as EditText
        box.text.append(text)
        box.setSelection(box.length())
    }

    /** ⌫: the last letter, or held, the whole query. */
    private fun delete(all: Boolean) {
        val box = editor as EditText
        if (box.length() > 0) box.text.delete(if (all) 0 else box.length() - 1, box.length())
    }

    /**
     * Off the keys' edge: up to their button, never the field, whose own keyboard would come up over
     * these; down nowhere; toward the results once there is something there to take the remote; and
     * past the start edge into the rail, as from any first column.
     */
    private fun offKeys(focused: View?, direction: Int): View? = when {
        FocusFinder.getInstance().findNextFocus(keys, focused, direction) != null -> null
        direction == View.FOCUS_UP -> toggle
        direction == View.FOCUS_DOWN -> focused
        direction == (if (rtl) View.FOCUS_LEFT else View.FOCUS_RIGHT) && results() == null && !recentsShown -> focused
        else -> null
    }

    /** Back with the keys out puts them away, the remote going on to the results if it was on them. */
    private fun back(event: KeyEvent): Boolean {
        if (!open || event.keyCode != KeyEvent.KEYCODE_BACK) return false
        if (event.action == KeyEvent.ACTION_UP) showKeys(false, toResults = true)
        return true
    }

    /**
     * Down from the box with the keyboard down and the caret at the end goes [under] before the
     * keyboard is offered it. A keyboard that is down still serves the box and sees every key
     * first, and what it makes of Down is its own: on Android TV 11 (CI's search check) the remote
     * stayed in the box over the recent searches. From Android 11, where the window can tell the
     * keyboard is down; Android 9 and 10 keep the focus search's way, as before.
     */
    private fun down(event: KeyEvent): Boolean {
        if (Build.VERSION.SDK_INT < 30 || event.keyCode != KeyEvent.KEYCODE_DPAD_DOWN || event.action != KeyEvent.ACTION_DOWN) return false
        val box = editor
        if (!box.isFocused || box.selectionEnd != box.length() || box.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) != false) return false
        // Nothing under the box yet: on to the results as they land, as [early] would, had the keyboard let it see the key.
        val next = under() ?: return false.also { moveToResults = true }
        return next.requestFocusFromTouch()
    }

    /**
     * Down from the box that the keyboard left to the page before anything is under the box: the
     * remote goes on to the results as they land ([focusResults]), rather than staying in the box,
     * where OK brings the keyboard back up. Here, not in the focus search, which the field also runs
     * as the keyboard connects. Never takes the key, so the focus search still runs.
     */
    private fun early(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && event.action == KeyEvent.ACTION_DOWN && bar.hasFocus() && under() == null) moveToResults = true
        return false
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val initial = savedInstanceState?.getString("query").orEmpty()
        bar.setSearchQuery(initial)
        bar.setSearchBarListener(this)
        styleBar(view)
        search(initial, debounce = false)
        // On the keys if their button last brought them out, so they are never a trip past the field away.
        view.post { if (SearchKeys.chosen(view.context)) showKeys(true) else editor.requestFocus() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("query", bar.findViewById<TextView>(androidx.leanback.R.id.lb_search_text_editor).text.toString())
    }

    /** Coming back to the page while the results didn't come asks again on its own, as a catalogue does. */
    override fun onStart() {
        super.onStart()
        if (failed) retry()
    }

    override fun onDestroyView() {
        pager?.cancel()
        presenter.onUnbindViewHolder(gridHolder)
        query = null
        shown = null
        note = null
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
        orb.setOnOrbClickedListener { listen() }
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

    /**
     * The orb: the TV's speech recognizer, whose words come back to [onActivityResult]. Asked for
     * here, not through the bar's recognition callback: Leanback blanks the field, its hint and so
     * the results before it knows a recognizer will answer, and a viewer who then said nothing had
     * lost the query. A TV with none says so in words of its own, where the failure was put down
     * to 30nama.
     */
    private fun listen() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, bar.hint)
        @Suppress("DEPRECATION")
        runCatching { startActivityForResult(intent, VOICE) }.onFailure { requireContext().toast(getString(R.string.search_no_voice)) }
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

    /** Down from the box: the keys while they are out, else [below]. */
    private fun under(): View? = if (open) lastKey ?: firstKey else below()

    /**
     * Up to the box: the field, or the keyboard's button while the keys are out or the remote comes
     * up from right under it. A TV's own keyboard takes the D-pad while the field has the remote,
     * so from under the button is the way to it that never goes through the field.
     */
    private fun box(from: View?): View = if (open || from != null && beneath(from, toggle)) toggle else editor

    /** [view]'s middle within [above]'s width on screen. */
    private fun beneath(view: View, above: View): Boolean {
        val at = IntArray(2)
        view.getLocationOnScreen(at)
        val middle = at[0] + view.width / 2
        above.getLocationOnScreen(at)
        return middle in at[0]..at[0] + above.width
    }

    /** The results' columns: six, or three beside the keys. */
    private val columns get() = if (open) COLUMNS / 2 else COLUMNS

    private val rtl get() = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL

    /** Down from the box: the recent searches while they show, else [results]. */
    private fun below(): View? = if (recentsShown) strip else results()

    /** What stands under the heading for the remote: the results once they can take it, or the note's Retry in their place. */
    private fun results(): View? = if (grid.isFocusable) grid else note?.findViewById(R.id.list_empty_action)

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
            if (strip.hasFocus()) (results() ?: if (open) toggle else editor).requestFocus()
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

    /** The first page of [query] failed: the note in the results' place says so until it is asked again. */
    private var failed = false
    /** In the results' place while their first page has failed ([showNote]). */
    private var note: View? = null
    /** The adapter the grid shows: the query before's, while this one's first page is on its way ([search]). */
    private var shown: ArrayObjectAdapter? = null

    /**
     * Results for [text], or, until there are two letters to go on, the recent searches over
     * 30nama's suggestions, so the page never opens on an empty screen. Over nothing the row stands
     * at once, a skeleton until the first page lands. Over results it waits: they stay up, dim and
     * out of the remote's reach, until this query's first page takes their place, where every
     * letter typed used to swap them for the skeleton and back before the search had even been
     * sent. Results head it as «نتایج», then with their count once it is known (never the query
     * again: it is in the field just above, and Latin set in Peyda loses its spaces), or say there
     * are none; a first page that fails leaves a note in their place ([showNote]). Opening a result
     * keeps its query among the recent searches.
     */
    private fun search(text: String, debounce: Boolean) {
        val q = searchQuery(text)
        if (q == query) {
            // Submitted again after it failed, the same words are asked again: the search key did nothing, and only other words would.
            if (!debounce && (waitingForTyping || failed)) {
                waitingForTyping = false
                if (failed) retry() else pager?.reset()
            }
            return
        }
        query = q
        failed = false
        pager?.cancel()
        waitingForTyping = debounce && q.isNotEmpty()
        moveToResults = false
        grid.isFocusable = false
        // A recent search being run again keeps the remote until its results can take it.
        if (!strip.hasFocus()) showRecents(q.isEmpty())
        hideNote() // the query before's
        val context = requireContext()
        val name = getString(if (q.isEmpty()) R.string.suggested else R.string.search_results)
        val items = ArrayObjectAdapter(PosterPresenter(112, 168))
        val stale = shown?.let { it.size() > 0 && it[0] is Card } == true
        val pager = Pager(viewLifecycleOwner.lifecycleScope, items, { t ->
            t.toCard(context) {
                if (q.isNotEmpty()) Recents.add(context, q)
                context.openDetails(t)
            }
        }, onEmpty = {
            if (query == q) {
                show(items)
                hideNote()
                title(if (q.isEmpty()) name else getString(R.string.no_results))
                showRecents(q.isEmpty())
            }
            moveToResults = false
        }, onError = {
            if (query == q) {
                // A later page's failure keeps the results and says why in the notice. A first
                // page's leaves nothing to keep: the note in their place says it, Retry taking a
                // remote that was waiting to go down to the results.
                if (items.size() == 0) {
                    show(items)
                    showNote(if (q.isEmpty()) R.string.suggestions_failed else R.string.search_failed, context.errorText(it))
                    if (moveToResults && bar.hasFocus()) focusResults()
                } else context.toast(it)
                showRecents(q.isEmpty())
            }
        }, skeleton = if (stale) null else Skeleton(12, 112, 168), onFirst = { total ->
            if (query == q) {
                grid.isFocusable = true
                show(items)
                hideNote()
                showRecents(q.isEmpty())
                title(if (q.isNotEmpty() && total != null) resources.getQuantityString(R.plurals.search_count, total, total) else name)
                // Only from the box: a remote that has gone on to the rail or the keys meanwhile stays there.
                if (moveToResults && bar.hasFocus()) focusResults()
            }
        }) { page ->
            if (page == 1 && waitingForTyping) {
                delay(600)
                waitingForTyping = false
            }
            if (q.isEmpty()) Api.archive("suggested", page, "relevant")
            else runCatching { Api.search(q, page) }.getOrElse { if (Api.tooShort(it)) JSONObject() else throw it }
        }
        this.pager = pager
        if (stale) {
            // Leanback offers the selected poster to a focus search whatever the grid's own
            // focusability says, so the search itself is switched off: Down must not land on a
            // result that is about to go.
            grid.setFocusSearchDisabled(true)
            grid.animate().alpha(STALE).setStartDelay(0).setInterpolator(Motion.easeOut).setDuration(Motion.EXIT).withLayer().start()
        } else {
            title(name)
            show(items)
        }
        pager.reset()
    }

    /** [items] take the grid, whole and within the remote's reach: at once over nothing, else as their first page (or what came of it) is in. */
    private fun show(items: ArrayObjectAdapter) {
        if (shown === items) return
        shown = items
        presenter.onBindViewHolder(gridHolder, items)
        grid.setFocusSearchDisabled(false)
        grid.animate().alpha(1f).setStartDelay(0).setInterpolator(Motion.easeOut).setDuration(Motion.ENTER).withLayer().start()
    }

    /**
     * The results' place when their first page fails: what failed over why, and Retry, as a
     * catalogue's grid says it (layout/list_empty). The heading alone said it over an empty grid,
     * which left the remote nowhere to go but back to the box. Retry reads «در حال بارگذاری» while
     * it asks ([retry]), and the results, or the note's words again, take its place; coming back
     * to the page asks on its own ([onStart]). It never takes the remote from someone typing.
     */
    private fun showNote(title: Int, why: CharSequence) {
        val note = note ?: (layoutInflater.inflate(R.layout.list_empty, content, false) as LinearLayout).also {
            it.gravity = Gravity.CENTER
            it.findViewById<View>(R.id.list_empty_action).setOnClickListener { if (failed) retry() }
            content.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            rise(listOf(it))
            this.note = it
        }
        note.findViewById<TextView>(R.id.list_empty_title).setText(title)
        note.findViewById<TextView>(R.id.list_empty_hint).text = why
        note.findViewById<Button>(R.id.list_empty_action).setText(R.string.retry)
        heading.visibility = View.GONE
        grid.visibility = View.GONE
        failed = true
    }

    /** The note gives the results their place back; a remote on its Retry goes on to them first, or with none there to the box. */
    private fun hideNote() {
        val note = note ?: return
        heading.visibility = View.VISIBLE
        grid.visibility = View.VISIBLE
        if (note.hasFocus()) (if (grid.isFocusable) grid else if (open) toggle else editor).requestFocus()
        content.removeView(note)
        this.note = null
    }

    /** The first page asked again, the note standing by with its pill saying so. */
    private fun retry() {
        failed = false
        note?.findViewById<Button>(R.id.list_empty_action)?.setText(R.string.intro_loading)
        pager?.reset()
    }

    private companion object {
        const val VOICE = 1
        const val COLUMNS = 6
        const val KEY_COLUMNS = 7
        /** How much of the query before's results shows while this one's are on their way: there still, plainly not the answer. */
        const val STALE = 0.4f
        /** The keyboard's letters in the alphabet's order, its hamzas after ی, then the digits. */
        const val KEYS = "اآبپتثجچحخدذرزژسشصضطظعغفقکگلمنوهیءئؤ۱۲۳۴۵۶۷۸۹۰"
    }
}

/**
 * Letters a query takes before it is searched; under that, the page offers the suggestions.
 * Unverified: rival clients say 30nama turns away queries under 3 (see [Api.tooShort]); until
 * that is measured the app keeps its 2, and a refusal shows as no results.
 */
internal const val MIN_QUERY = 2

/** What [text] searches for: itself, trimmed, or "" for the suggestions while it is shorter than [MIN_QUERY]. */
internal fun searchQuery(text: String) = text.trim().takeIf { it.length >= MIN_QUERY }.orEmpty()

/** Whether search opens on its own Persian keys: the choice last made with their button, which Back and Close leave be. */
object SearchKeys {
    private const val KEY = "keys"

    fun chosen(context: Context) = prefs(context).getBoolean(KEY, false)

    fun choose(context: Context, keys: Boolean) = prefs(context).edit().putBoolean(KEY, keys).apply()

    private fun prefs(context: Context) = context.getSharedPreferences("search", Context.MODE_PRIVATE)
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
