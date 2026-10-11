package com.doxigo.tv30

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.leanback.app.ProgressBarManager
import androidx.leanback.app.RowsSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.DiffCallback
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.lifecycle.lifecycleScope
import coil3.load
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * One person from a title page, in home's frame: their portrait and name where the billboard's
 * words would be, over the art of what they're best known for, and what 30nama streams with them
 * as home's rows, films then series. Once the remote moves, the art follows it as home's does. A
 * sub-page, so no rail; Back returns to the title.
 */
class PersonActivity : TvActivity() {
    private lateinit var billboard: Billboard
    private lateinit var rowsFragment: RowsSupportFragment
    private lateinit var sort: View
    private lateinit var relevant: Button
    private lateinit var newest: Button
    private lateinit var part: String
    private val rows = ArrayObjectAdapter(CardRowPresenter())
    private val posters = PosterPresenter(108, 162)
    private val progress = ProgressBarManager()
    /** Every title so far, in the order 30nama ranks them ("relevant"); each card's source is its title. */
    private val titles = ArrayList<Card>()
    private var byYear = false
    /** The art stays on their best-known title until the remote first moves; from then it follows. */
    private var moved = false
    /** Where the art goes once a held D-pad lets go. */
    private var waiting: Card? = null
    /** Rows scrolled above the focused one fade out instead of sliding under the header, as on home. */
    private val past = PastRows { rowsFragment.verticalGridView }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.person_page)
        val root = findViewById<ViewGroup>(android.R.id.content)
        billboard = Billboard(root, lifecycleScope)
        // The billboard lends its art and scrims; the person stands where its words would.
        findViewById<View>(R.id.billboard).visibility = View.GONE
        // Here the art sets the scene rather than being the subject: a shade darker than home's.
        findViewById<ImageView>(R.id.backdrop).setColorFilter(DIM)
        part = getString(if (intent.getStringExtra("role") == "director") R.string.person_director else R.string.person_actor)

        val name = intent.getStringExtra("name").orEmpty()
        val nameView = findViewById<TextView>(R.id.person_name).apply {
            // As the billboard sets a title: an English name in Inter, a touch tight.
            typeface = font(if (name.isLatin) R.font.inter_bold else R.font.peyda_bold)
            letterSpacing = if (name.isLatin) -0.01f else 0f
            text = name
        }
        // The cast row's portrait: initials until the headshot comes, then the headshot alone.
        val monogram = findViewById<TextView>(R.id.person_initials).apply {
            text = initials(name)
            typeface = font(if (name.isLatin) R.font.inter_bold else R.font.peyda_bold)
        }
        val face = resources.getDimension(R.dimen.person_portrait)
        val photo = findViewById<ImageView>(R.id.person_photo)
        photo.load(intent.getStringExtra("image")) {
            listener(onSuccess = { _, _ -> if (photo.fitHead(face)) monogram.visibility = View.INVISIBLE })
        }
        val portrait = findViewById<View>(R.id.person_portrait).apply {
            outlineProvider = OVAL
            clipToOutline = true
        }
        showMeta(null)
        rise(listOf(portrait, nameView, findViewById(R.id.person_meta)))

        sort = findViewById(R.id.person_sort)
        relevant = findViewById(R.id.person_sort_relevant)
        newest = findViewById(R.id.person_sort_newest)
        relevant.isSelected = true
        relevant.setOnClickListener { sortByYear(false) }
        newest.setOnClickListener { sortByYear(true) }

        rowsFragment = supportFragmentManager.findFragmentById(R.id.person_rows) as? RowsSupportFragment
            ?: RowsSupportFragment().also { supportFragmentManager.beginTransaction().replace(R.id.person_rows, it).commitNow() }
        rowsFragment.adapter = rows
        // The focused row's art where home's is; its title takes the room above.
        rowsFragment.setAlignment(resources.getDimensionPixelSize(R.dimen.billboard_height) + resources.getDimensionPixelSize(R.dimen.rows_title_room))
        rowsFragment.onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ -> (item as? Card)?.onClick?.invoke() }
        rowsFragment.setOnItemViewSelectedListener { _, item, _, _ ->
            past.update()
            if (moved) (item as? Card)?.let(::stage)
        }
        progress.setRootView(root)
        load()
    }

    /**
     * Every page, one after another: the rows split one list into films and series, so each needs
     * all of it, and Newest re-sorts it here at once instead of asking again. The first page draws
     * the page; later ones join the rows where they belong.
     */
    private fun load() {
        // ponytail: a request per 24 titles up front; page on focus (as Pager does) if someone's
        // hundreds of titles ever make that slow.
        val filter = JSONObject().put(intent.getStringExtra("role") ?: "actor", intent.getStringExtra("imdb")).put("stream", "true")
        progress.show()
        lifecycleScope.launch {
            val seen = HashSet<Int>()
            var page = 1
            var pages = 1
            while (page <= pages) {
                val r = runCatching { Api.advancedSearch(filter, page, "relevant") }.getOrElse {
                    if (it is CancellationException) throw it
                    // A later page that fails leaves the rows as they are.
                    if (page == 1) showNote(it) else toast(it)
                    return@launch
                }
                pages = r.optInt("pages", page)
                val fresh = r.optJSONArray("posts").objects().filter { seen.add(it.titleId) }
                // Spans and faces for a page of cards off the main thread, as Pager builds them.
                val context = this@PersonActivity
                titles += withContext(Dispatchers.Default) { fresh.map { t -> t.toCard(context) { openDetails(t) } } }
                if (page == 1) arrive(r.optInt("total", titles.size)) else showRows()
                page++
            }
        }
    }

    /**
     * The first page: the count, the art and the line of what they're best known for (by IMDb
     * votes, as far as it knows), and the rows, rising in with the remote on their first poster.
     * Someone with less than a row's worth needs no summary and no order.
     */
    private fun arrive(total: Int) {
        progress.hide()
        if (titles.isEmpty()) return showNote(null)
        showMeta(resources.getQuantityString(R.plurals.person_titles, total, total))
        val known = titles.sortedByDescending { it.source?.votes ?: 0 }
        known.firstOrNull { it.backdrop != null }?.let(::stage)
        val more = mutableListOf<View>()
        if (total > ROW) {
            more += findViewById<TextView>(R.id.person_known).apply { text = knownFor(known.take(3)) }
            more += sort
        }
        showRows()
        val rowsView = findViewById<View>(R.id.person_rows)
        more += rowsView
        more.forEach { it.visibility = View.VISIBLE }
        rise(more)
        rowsView.post { rowsFragment.view?.requestFocus() }
    }

    /** «شناخته‌شده برای Slow Horses، MobLand، Backdraft»: the names a step brighter than the words before them. */
    private fun knownFor(cards: List<Card>): CharSequence {
        val names = dotted(*cards.map { it.title }.toTypedArray())
        val line = getString(R.string.person_known_for, names)
        val at = line.indexOf(names)
        val spanned = SpannableString(line).apply {
            setSpan(ForegroundColorSpan(getColor(R.color.text_body)), at, at + names.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return latin(this, spanned, R.font.inter_medium)
    }

    /** Their part in white, then how many titles 30nama streams with them once the first page says. */
    private fun showMeta(count: CharSequence?) {
        findViewById<TextView>(R.id.person_meta).text = metaLine(this, part to R.color.text, count to null)
    }

    /** Films, then series, each in the order chosen; a page landing later joins them in place. */
    private fun showRows() {
        val order = if (byYear) titles.sortedByDescending { it.source?.obj("info")?.optInt("year") ?: 0 } else titles
        val (series, films) = order.partition { it.source?.isSeries == true }
        var at = 0
        for ((kind, cards) in listOf(FILMS to films, SERIES to series)) {
            if (cards.isEmpty()) continue
            // Each order has rows of its own, so each remembers its own place (CardRowPresenter).
            val id = kind + if (byYear) 2 else 0
            val row = rows.unmodifiableList<ListRow>().firstOrNull { it.id == id }
            if (row != null) (row.adapter as ArrayObjectAdapter).setItems(cards, SAME)
            else rows.add(at, ListRow(HeaderItem(id, getString(if (kind == FILMS) R.string.movies else R.string.series)),
                ArrayObjectAdapter(posters).apply { setItems(cards, null) }))
            at++
        }
    }

    private fun sortByYear(year: Boolean) {
        relevant.isSelected = !year
        newest.isSelected = year
        if (year == byYear) return
        byYear = year
        rows.clear()
        showRows()
        rowsFragment.setSelectedPosition(0, false)
        // The new order fades up from its first poster rather than cutting in.
        findViewById<View>(R.id.person_rows).apply {
            alpha = 0f
            animate().alpha(1f).setStartDelay(0).setInterpolator(Motion.easeOut).setDuration(Motion.ENTER).start()
        }
    }

    /** The art behind follows [card], once a held D-pad lets go; the billboard's words stay out of it. */
    private fun stage(card: Card) {
        if (Motion.fast) waiting = card
        else billboard.show(Card(card.title, backdrop = card.backdrop) {})
    }

    override fun onSettled() {
        waiting?.let(::stage)
        waiting = null
    }

    /** Where the rows would be: nothing with them yet (with Back), or a first page that failed (with Retry). */
    private fun showNote(error: Throwable?) {
        progress.hide()
        val note = findViewById<View>(R.id.person_note)
        findViewById<TextView>(R.id.person_note_title).setText(if (error == null) R.string.person_empty else R.string.person_error)
        findViewById<TextView>(R.id.person_note_hint).text = when (error) {
            null -> getString(R.string.person_empty_hint)
            is Api.ApiError -> error.message
            else -> getString(R.string.no_internet)
        }
        val action = findViewById<Button>(R.id.person_note_action)
        action.setText(if (error == null) R.string.person_back else R.string.retry)
        action.setOnClickListener {
            if (error == null) return@setOnClickListener finish()
            note.visibility = View.GONE
            load()
        }
        note.reveal(true)
        action.requestFocus()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (event.action == KeyEvent.ACTION_DOWN && code in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT) moved = true
        val sideways = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
        val towardEnd = if (window.decorView.layoutDirection == View.LAYOUT_DIRECTION_RTL) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        val target = when {
            // Up from the first row: the order in use, not whichever pill sits nearer the poster.
            code == KeyEvent.KEYCODE_DPAD_UP && rowsFragment.view?.hasFocus() == true && rowsFragment.selectedPosition == 0 ->
                (if (byYear) newest else relevant).takeIf { sort.visibility == View.VISIBLE }
            // Down from the pair: back to the poster the rows remember.
            code == KeyEvent.KEYCODE_DPAD_DOWN && sort.hasFocus() -> rowsFragment.view
            // Sideways the pair keeps the remote: past either end the nearest thing is a poster below.
            sideways && sort.hasFocus() -> if (code == towardEnd) newest else relevant
            else -> null
        } ?: return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN) target.requestFocus()
        return true
    }

    companion object {
        /** [role] is the `advanced_search` key the [imdb] id goes under: "actor" or "director". */
        fun intent(context: Context, role: String, imdb: String, name: String, image: String?): Intent =
            Intent(context, PersonActivity::class.java)
                .putExtra("role", role).putExtra("imdb", imdb).putExtra("name", name).putExtra("image", image)

        /** About as many posters as a row shows at once: past this a summary and an order earn their place. */
        private const val ROW = 7
        /** 40% ink over the art, on top of the billboard's scrims. */
        private const val DIM = 0x66141414
        private const val FILMS = 0L
        private const val SERIES = 1L

        /** Cards are made once per title, so a page landing later only adds or moves them. */
        private val SAME = object : DiffCallback<Card>() {
            override fun areItemsTheSame(old: Card, new: Card) = old === new
            override fun areContentsTheSame(old: Card, new: Card) = true
        }
    }
}

/** A series row's title: series and miniseries, anime series too; a few series carry the type but not the flag. */
private val JSONObject.isSeries get() =
    obj("options")?.optBoolean("is_series") == true || str("options", "title_type").let { it == "series" || it == "miniseries" }

private val JSONObject.votes get() = obj("score", "imdb")?.optInt("votes") ?: 0
