package com.doxigo.tv30

import android.animation.LayoutTransition
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.leanback.app.RowsSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.DiffCallback
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.VerticalGridView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * A title, Netflix-style: its art and facts, Play, the trailer and the three list toggles, then
 * seasons, cast and titles like it as rows. List endpoints already return the whole title, so the page draws at once and
 * `single` only fills in what the list left out.
 */
class DetailsActivity : TvActivity() {
    private lateinit var title: JSONObject
    private lateinit var billboard: Billboard
    private lateinit var watch: Button
    private lateinit var trailer: Button
    private val toggles = mutableMapOf<String, Button>()
    private lateinit var rowsFragment: RowsSupportFragment
    /** Start-anchored rows that remember their card, as on home; each row's id is its place here. */
    private val rows = ArrayObjectAdapter(CardRowPresenter())
    private var resumeLabel: String? = null
    /** What Play does with [resumeLabel]'s episode: resume it, start the next one (UpNext.kt), or start it over. */
    private var playVerb = R.string.resume
    /** The row and tile of the episode Play leads to: where Down first lands, in its season (and its stretch of a long one). */
    private var landing: Pair<Int, Int>? = null
    /** The seasons' rows of tiles, in order; coming back to the page their tiles catch up in place (see [onRestart]). */
    private val episodeRows = mutableListOf<ArrayObjectAdapter>()
    /** TMDB's entry for the title, once it lands: the logo, the tagline, a series' state and network, a synopsis where 30nama has none. */
    private var tmdb: JSONObject? = null
    /**
     * Whether TMDB is asked for it at all. What it may bring keeps its room from the first bind,
     * whether it brings it or not, so nothing under Play moves when it lands (see [bind]).
     */
    private val tmdbAsked by lazy { Tmdb.enabled && !Tmdb.backingOff && title.imdbId != null }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_details)
        title = JSONObject(intent.getStringExtra(EXTRA_TITLE)!!)
        // The logo comes with the page's one TMDB call, which the trailer and More Like This share.
        billboard = Billboard(findViewById(android.R.id.content), lifecycleScope) { Tmdb.title(it) }
        shadeText()

        val fragment = supportFragmentManager.findFragmentById(R.id.rows) as? RowsSupportFragment
            ?: RowsSupportFragment().also { supportFragmentManager.beginTransaction().replace(R.id.rows, it).commitNow() }
        rowsFragment = fragment
        fragment.adapter = rows
        fragment.onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ -> (item as? Card)?.onClick?.invoke() }
        // The rows wait under the actions. Once the remote moves into them they rise to just below
        // the names, and the facts, synopsis and actions step aside; moving back up reverses it.
        val waiting = resources.getDimensionPixelSize(R.dimen.details_rows_top)
        val raised = resources.getDimensionPixelSize(R.dimen.details_rows_raised)
        val aside = listOf(R.id.bb_meta, R.id.genres, R.id.next_air, R.id.bb_plot, R.id.credits, R.id.actions).map { findViewById<View>(it) }
        val rowsView = findViewById<View>(R.id.rows)
        var risen = false
        // The names stay over every row: a row scrolled above the focused one fades away rather
        // than sliding over them, or being cut off at the top of the screen.
        val past = PastRows { fragment.verticalGridView }
        fragment.setOnItemViewSelectedListener { _, _, _, _ -> past.update() }
        fragment.setAlignment(waiting)
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, _ ->
            val inRows = fragment.view?.hasFocus() == true
            if (inRows == risen) return@addOnGlobalFocusChangeListener
            risen = inRows
            // Once, on the way in: straight to the episode to resume, not rows and tiles short of it.
            if (inRows) landing?.let { (row, tile) ->
                landing = null
                fragment.setSelectedPosition(row, false, ListRowPresenter.SelectItemViewHolderTask(tile).apply { isSmoothScroll = false })
            }
            fragment.setAlignment(if (inRows) raised else waiting)
            // The grid re-aligns in one layout pass; offset it back by the distance and let it travel.
            rowsView.translationY = (if (inRows) waiting - raised else raised - waiting).toFloat()
            rowsView.animate().translationY(0f).setInterpolator(Motion.drawer).setDuration(Motion.MOVE).start()
            aside.forEach {
                it.animate().alpha(if (inRows) 0f else 1f).setInterpolator(Motion.easeOut)
                    .setDuration(if (inRows) Motion.EXIT else Motion.ENTER).start()
            }
        }

        buildActions()
        bind()
        watch.requestFocus()

        // None of them waits on another, or holds up the page; all stop when the page closes.
        // TMDB's entry (the call the trailer and More Like This share) adds to the credit line.
        lifecycleScope.launch { showTrailer() }
        lifecycleScope.launch {
            tmdb = runCatching { Tmdb.title(title) }.getOrNull() ?: return@launch
            bind()
        }
        lifecycleScope.launch {
            // TMDB plus up to twelve 30nama searches take seconds, so the row is fetched from the
            // start but added last, under the cast.
            val similar = async { Similar.titles(this@DetailsActivity, title) }
            runCatching { Api.single(title.titleId) }.onSuccess {
                title = it
                bind()
                updateActions() // a list that left options.stream out may have led to a title 30nama doesn't stream
            }
            loadEpisodes()
            showCast()
            similar.await()?.let(::showSimilar)
        }
    }

    /**
     * Home's side scrim, carried further: the reading lines keep to the start 58%
     * (@dimen/details_text_width) and the ink holds at 88% or more to their end, so grey text keeps
     * 4.5:1 over even a white frame of art (6.5:1). A crowded meta line runs on to 68% at most, past
     * 72% ink, ending in the solid subtitles chip; the picture clears over the rest.
     */
    private fun shadeText() {
        val rtl = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val ink = getColor(R.color.scrim) and 0xFFFFFF
        // Evenly spaced stops, an eighth of the width apart (offsets need API 29).
        val alphas = intArrayOf(0xFF, 0xFF, 0xFF, 0xFF, 0xF2, 0xD9, 0x8C, 0x33, 0x00)
        findViewById<View>(R.id.scrim_side).background = GradientDrawable(
            if (rtl) GradientDrawable.Orientation.RIGHT_LEFT else GradientDrawable.Orientation.LEFT_RIGHT,
            IntArray(alphas.size) { alphas[it] shl 24 or ink },
        )
    }

    /** Risen rows cover the actions, so Up from the first row has nothing above it: send it to Play. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP && rowsFragment.view?.hasFocus() == true && rowsFragment.selectedPosition == 0) {
            if (event.action == KeyEvent.ACTION_DOWN) watch.requestFocus()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        updateActions() // signed in, or a list changed, while we were away
    }

    /** Back from the player, most likely: the server's point has moved and Watched with it, so Play and the tiles follow. */
    override fun onRestart() {
        super.onRestart()
        if (episodeRows.isNotEmpty()) lifecycleScope.launch { loadEpisodes() }
    }

    private fun bind() {
        // The critics' scores make way when the line would otherwise lose its end, the length and
        // the Persian subtitles chip, to the ellipsis.
        val room = findViewById<View>(R.id.billboard).layoutParams.width
        val facts = title.meta(this, page = true).takeIf { Layout.getDesiredWidth(it, findViewById<TextView>(R.id.bb_meta).paint) <= room }
            ?: title.meta(this, page = true, critics = false)
        billboard.show(Card(title.englishName, facts, plot = plot(), backdrop = title.cover, localTitle = title.localName, source = title) {})
        // The genres get their own quieter line under the facts, where the countries and the
        // language follow once `single` brings them; the cast has its own row below.
        val genres = dotted(*title.optJSONArray("genre").objects().mapNotNull { it.str("name") }.take(3).toTypedArray())
        // Who made it: the director for a film, the creator for a series (or its director).
        val crew = title.obj("crew")
        fun names(key: String) = dotted(*crew?.optJSONArray(key).objects().mapNotNull { it.str("name") }.take(2).toTypedArray()).ifEmpty { null }
        val credit = if (title.isMovie) names("director")?.let { getString(R.string.director, it) }
        else names("creator")?.let { getString(R.string.creator, it) } ?: names("director")?.let { getString(R.string.director, it) }
        line(R.id.genres, title.genresLine(this, genres))
        // An empty credit line TMDB may add to keeps its place, unseen, as the Persian name's
        // does: a stand-in of the same height.
        val made = creditLine(this, credit, tmdb?.let(::about))
        line(R.id.credits, made ?: creditLine(this, "·", null).takeIf { tmdbAsked })
            .apply { if (made == null && tmdbAsked) visibility = View.INVISIBLE }
        // An airing series' next episode takes the synopsis' third line, so the actions never
        // move down into the waiting rows (see details_rows_top).
        val next = title.nextEpisode(this)
        line(R.id.next_air, next)
        findViewById<TextView>(R.id.bb_plot).apply {
            maxLines = if (next == null) 3 else 2
            // Where TMDB may bring the Persian synopsis 30nama lacks (plot()), the synopsis keeps the
            // room of all its lines from the start, Persian lines being 1.65 apart (Billboard.write):
            // more than the English one before it takes, or a shorter Persian one, so the actions
            // stay put when it lands.
            minHeight = if (tmdbAsked && Api.language == "fa" && title.str("plot", "local") == null) maxLines * (textSize * 1.65f).roundToInt() else 0
        }
    }

    /**
     * 30nama's synopsis; in Persian, TMDB's Persian one where 30nama has none at all (none of 92
     * titles on home in October 2026: its own fills in nearly always).
     */
    private fun plot(): String? =
        if (Api.language == "fa" && title.str("plot", "local") == null) tmdb?.translated("overview", "fa")?.inPersian()?.trim() ?: title.plot
        else title.plot

    /**
     * What TMDB adds to the credit line: a series' state and network («در حال پخش، HBO»), else the
     * tagline in the interface's language. Persian has only TMDB's Persian tagline, never an English one.
     */
    private fun about(entry: JSONObject): String? {
        val tagline = if (Api.language == "fa") entry.translated("tagline", "fa")?.inPersian() else entry.str("tagline")
        if (entry.optString("media_type") != "tv") return tagline
        val state = when (entry.str("status")) {
            "Returning Series" -> R.string.status_returning
            "Ended" -> R.string.status_ended
            "Canceled" -> R.string.status_canceled
            "In Production" -> R.string.status_in_production
            "Planned" -> R.string.status_planned
            "Pilot" -> R.string.status_pilot
            else -> null
        }?.let(::getString)
        return dotted(state, entry.optJSONArray("networks").objects().firstOrNull()?.str("name")).ifEmpty { null } ?: tagline
    }

    /** One of the quiet lines under the facts, its Latin names in Inter, gone when empty. */
    private fun line(id: Int, text: CharSequence?) = findViewById<TextView>(id).apply {
        this.text = text?.let { latin(context, it, R.font.inter_regular) }
        visibility = if (text == null) View.GONE else View.VISIBLE
    }

    private fun buildActions() {
        val bar = findViewById<LinearLayout>(R.id.actions)
        // Play's focus lift reaches past the bar's start edge and the last toggle's past its end:
        // neither the text column nor the page clips them.
        (bar.parent as android.view.ViewGroup).run {
            clipChildren = false
            (parent as android.view.ViewGroup).clipChildren = false
        }
        watch = button(R.drawable.act_play, R.style.Widget_App_Button_Primary).also { bar.addView(it) }
        watch.setOnClickListener { play(title) }
        // Icon and label, between Play and the round toggles; hidden until a trailer is found (showTrailer).
        trailer = button(R.drawable.act_trailer, R.style.Widget_App_Button_Icon).apply {
            text = getString(R.string.trailer)
            visibility = View.GONE
        }.also { bar.addView(it) }
        // The list toggles are Netflix's round icon buttons; focus slides each one's label out.
        bar.layoutTransition = LayoutTransition().apply {
            disableTransitionType(LayoutTransition.APPEARING)
            disableTransitionType(LayoutTransition.DISAPPEARING)
            enableTransitionType(LayoutTransition.CHANGING)
            setInterpolator(LayoutTransition.CHANGING, Motion.easeOut)
            setDuration(LayoutTransition.CHANGING, if (Motion.reduced) 0L else 180L)
        }
        for (group in Library.GROUPS) {
            val toggle = button(R.drawable.act_favorite, R.style.Widget_App_Button_Round).apply {
                setOnFocusChangeListener { _, _ -> updateActions() }
            }
            toggle.setOnClickListener {
                // Library flips its state before suspending, so the button updates at once.
                lifecycleScope.launch { runCatching { Library.toggle(group, title.titleId) }.onFailure { toast(it) } }
                updateActions()
            }
            toggles[group] = toggle
            bar.addView(toggle)
        }
        updateActions()
    }

    private fun button(@DrawableRes icon: Int, style: Int): Button {
        val gap = resources.getDimensionPixelSize(R.dimen.button_gap)
        return Button(this, null, 0, style).apply {
            widenSpaces() // built in code: «ادامهٔ تماشا  فصل ۱، قسمت ۴» keeps its word spaces
            setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = gap
            }
        }
    }

    private fun updateActions() {
        dressWatch(title.streams)
        watch.text = when {
            !title.streams -> getString(if (title.comingSoon) R.string.coming_soon else R.string.not_streaming)
            !Api.loggedIn && !title.isFree -> getString(R.string.login_and_watch)
            // The episode a step quieter than the action, in whichever ink the button has now.
            // ponytail: upnext_play ends in its %s in both languages, so the episode still trails the verb.
            resumeLabel != null -> SpannableStringBuilder(if (playVerb == R.string.upnext_play) getString(R.string.upnext_play, "") else getString(playVerb) + "  ").append(resumeLabel, object : CharacterStyle() {
                override fun updateDrawState(paint: TextPaint) { paint.alpha = paint.alpha * 7 / 10 }
            }, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            else -> getString(R.string.watch)
        }
        val labels = mapOf("favorite" to R.string.list_favorite, "watchlist" to R.string.list_watchlist, "notify" to R.string.list_notify)
        // Outline while the title is not in the list, filled once it is: drawn icons, not glyphs.
        val icons = mapOf(
            "favorite" to (R.drawable.act_favorite to R.drawable.act_favorite_on),
            "watchlist" to (R.drawable.act_watchlist to R.drawable.act_watchlist_on),
            "notify" to (R.drawable.act_notify to R.drawable.act_notify_on),
        )
        val round = resources.getDimensionPixelSize(R.dimen.button_pad_round)
        for ((group, toggle) in toggles) {
            toggle.visibility = if (Api.loggedIn) View.VISIBLE else View.GONE
            // At rest a 44dp circle around the 20dp icon; focused, it opens into the icon button's pill.
            val open = toggle.isFocused
            toggle.text = if (open) getString(labels.getValue(group)) else ""
            toggle.contentDescription = getString(labels.getValue(group))
            toggle.compoundDrawablePadding = if (open) resources.getDimensionPixelSize(R.dimen.button_icon_gap) else 0
            toggle.setPaddingRelative(
                if (open) resources.getDimensionPixelSize(R.dimen.button_pad_icon) else round, toggle.paddingTop,
                if (open) resources.getDimensionPixelSize(R.dimen.button_pad) else round, toggle.paddingBottom,
            )
            val (off, on) = icons.getValue(group)
            toggle.setCompoundDrawablesRelativeWithIntrinsicBounds(if (Library.has(group, title.titleId)) on else off, 0, 0, 0)
        }
    }

    /** Whether Play wears its play look; a title 30nama doesn't stream may only learn so from `single`. */
    private var watchStreams = true

    /**
     * For a title 30nama doesn't stream, Play gives way to what stands in its place («به‌زودی», «پخش
     * آنلاین ندارد»): no icon, a secondary button's glass and a dim label at rest, a step below the
     * action it replaces, and still where the remote starts, so the page reads the same. OK says why
     * (Context.play).
     */
    private fun dressWatch(streams: Boolean) {
        if (streams == watchStreams) return
        watchStreams = streams
        watch.setBackgroundResource(if (streams) R.drawable.button_primary else R.drawable.button)
        watch.setTextColor(getColorStateList(if (streams) R.color.on_button else R.color.person_pill_ink))
        watch.setCompoundDrawablesRelativeWithIntrinsicBounds(if (streams) R.drawable.act_play else 0, 0, 0, 0)
        val pad = resources.getDimensionPixelSize(R.dimen.button_pad)
        watch.setPaddingRelative(if (streams) resources.getDimensionPixelSize(R.dimen.button_pad_icon) else pad, watch.paddingTop, pad, watch.paddingBottom)
        // A new background takes its state before its callback, so its fade into focus would never run.
        watch.jumpDrawablesToCurrentState()
    }

    /**
     * Series list their episodes up front; the stream answer is where they live. Each season's row
     * draws at once as designed tiles; each tile's own frame (EpisodeFrames, from the same answer's
     * files) fades in once it is on screen and extracted, or at once when it was before.
     * A season longer than fifty is a row per fifty, «فصل ۱، قسمت ۱ تا ۵۰», as the official app chunks
     * it: Down jumps fifty episodes, every header says where it is, and Leanback draws only the rows on screen.
     * Called again once the rows are up, it only brings their tiles and Play up to date.
     */
    private suspend fun loadEpisodes() {
        if (title.isMovie || !title.streamable) return
        val stream = runCatching { Api.stream(title.titleId, title.isFree) }.getOrNull() ?: return
        // Play's episode by the player's own rule (UpNext.kt): saved in the credits, it is the next one
        // not yet seen, from its start (a finale starts over), so the button and the marked tile say what Play does.
        val all = stream.optJSONArray("hls").objects()
        val saved = stream.optJSONObject("continue_watching")
        // The server's point or where this TV left it, as the player picks (Watched.resumePoint).
        val point = Watched.resumePoint(this, title.titleId, all, saved)
        Watched.seed(this, all, saved)
        val savedIndex = point?.first ?: -1
        val (playIndex, playAt) = Watched.upNext(this, all, savedIndex, point?.second ?: 0L)
        val resumeFile = all.getOrNull(playIndex)?.str("info", "file_id")
        // The marked tile: how far in where Play resumes it, none where Play starts it.
        val length = all.getOrNull(playIndex)?.let { Watched.of(this, it)?.duration }?.takeIf { it > 0 }
        val watching = resumeFile?.let { JSONObject().put("progress", if (playIndex == savedIndex && length != null) playAt * 100 / length else 0) }
        playVerb = when {
            playIndex != savedIndex -> R.string.upnext_play
            playAt > 0 -> R.string.resume
            else -> R.string.watch
        }
        val episodes = EpisodePresenter(this)
        val shown = episodeRows.toList()
        resumeLabel = null
        landing = null
        var index = 0
        stream.optJSONArray("hls").objects().groupBy { it.obj("info")?.optInt("season") ?: 0 }.toSortedMap().forEach { (season, files) ->
            if (season == 0) return@forEach
            fun card(file: JSONObject): Card {
                val fileId = file.str("info", "file_id")
                return episodeCard(file, watching?.takeIf { fileId != null && fileId == resumeFile }, title.cover) {
                    startActivity(PlayerActivity.intent(this, title.titleId, title.isFree, fileId, title))
                }
            }
            val resumeAt = files.indexOfFirst { resumeFile != null && it.str("info", "file_id") == resumeFile }
            files.getOrNull(resumeAt)?.let { resumeLabel = getString(R.string.season_episode, season, it.episodeNumber) }
            val name = getString(R.string.season_n, season)
            val stretches = ranges(files.size)
            for (range in stretches.ifEmpty { listOf(files.indices) }) {
                val at = index++
                if (resumeAt in range) landing = at to resumeAt - range.first
                val cards = files.slice(range).map(::card)
                if (shown.isNotEmpty()) {
                    shown.getOrNull(at)?.setItems(cards, SameEpisode) // a season out since then waits for the next visit
                    continue
                }
                val header = if (stretches.isEmpty()) name else getString(R.string.season_range, name, rangeLabel(files, range))
                val row = ArrayObjectAdapter(episodes).apply { setItems(cards, null) }
                rows.add(ListRow(HeaderItem(rows.size().toLong(), header), row))
                episodeRows += row
            }
        }
        updateActions()
    }

    private fun showCast() {
        // Each face opens a page of what 30nama streams with them. advanced_search finds people by
        // IMDb id (names match loosely), so a face without one, or a [role] it can't search, stays put.
        fun page(person: JSONObject, role: String?): () -> Unit = {
            val imdb = person.str("imdb")
            if (role != null && imdb != null) {
                startActivity(PersonActivity.intent(this, role, imdb, person.str("name").orEmpty(), person.str("image", "poster", "webp", "large")))
            }
        }
        val cast = title.optJSONArray("cast").objects().map { person ->
            // Fenced, so a role that starts with a digit keeps its place in RTL; Latin in Inter.
            val role = person.str("as")?.let { latin(this, getString(R.string.as_role, fence(it)), R.font.inter_regular) }
            Card(person.str("name").orEmpty(), role, image = person.str("image", "poster", "webp", "large"), onClick = page(person, "actor"))
        }
        // The makers follow the cast, as the credit line names them: a film's director, a series'
        // creator (or its director when it names none). "director" is a verified advanced_search
        // key; "creator" is not yet, so creators show but open nothing until it is.
        val crew = title.obj("crew")
        fun makers(key: String, part: Int, role: String?) = crew?.optJSONArray(key).objects().map { person ->
            Card(person.str("name").orEmpty(), getString(part), onClick = page(person, role))
        }
        val makers = if (title.isMovie) makers("director", R.string.person_director, "director")
        else makers("creator", R.string.person_creator, null).ifEmpty { makers("director", R.string.person_director, "director") }
        val cards = cast + makers
        if (cards.isEmpty()) return
        val header = getString(if (makers.isEmpty()) R.string.cast else R.string.person_cast_crew)
        rows.add(ListRow(HeaderItem(rows.size().toLong(), header), ArrayObjectAdapter(CastPresenter()).apply { setItems(cards, null) }))
    }

    /** «تریلر» once there is somewhere to play one from: Aparat in Iran, YouTube elsewhere (Trailer.kt). */
    private suspend fun showTrailer() {
        val sources = Trailer.find(title) ?: return
        trailer.setCompoundDrawablesRelativeWithIntrinsicBounds(sources.icon, 0, 0, 0)
        if (sources.viaYoutube) {
            trailer.contentDescription = getString(R.string.trailer_on_youtube)
            TrailerActivity.preload(this, sources.youtube!!)
        }
        trailer.setOnClickListener { Trailer.play(this, sources) }
        // With both sources, a long press asks: «آپارات» or «یوتیوب».
        trailer.setOnLongClickListener { Trailer.choose(this, sources); true }
        trailer.visibility = View.VISIBLE
    }

    /** The last row: «عناوین مشابه» from TMDB, else «بیشتر در درام», as posters. */
    private fun showSimilar(shelf: Similar.Shelf) {
        val cards = shelf.titles.map { t -> t.toCard(this) { openDetails(t) } }
        rows.add(ListRow(HeaderItem(rows.size().toLong(), shelf.name), ArrayObjectAdapter(PosterPresenter(108, 162)).apply { setItems(cards, null) }))
    }

    companion object {
        const val EXTRA_TITLE = "title"

        fun intent(context: Context, title: JSONObject): Intent =
            Intent(context, DetailsActivity::class.java).putExtra(EXTRA_TITLE, title.toString())
    }
}

/**
 * An episode's tile is the same tile by its file; one whose state moved is bound again where it is
 * (a payload, not a swap), so the one under the remote keeps it.
 */
private object SameEpisode : DiffCallback<Card>() {
    override fun areItemsTheSame(oldItem: Card, newItem: Card) = oldItem.source?.str("info", "file_id") == newItem.source?.str("info", "file_id")
    override fun areContentsTheSame(oldItem: Card, newItem: Card) =
        oldItem.subtitle?.toString() == newItem.subtitle?.toString() && oldItem.progress == newItem.progress && oldItem.watched == newItem.watched
    override fun getChangePayload(oldItem: Card, newItem: Card): Any = newItem
}

/**
 * Rows scrolled above the focused one fade out instead of being cut off at the top of the screen,
 * and come back as the remote returns to them, as home's do (MainActivity.fadePast). Rows recycle,
 * so each takes the right alpha again as it is attached. The row is the grid's own selection: a
 * fragment's selectedPosition is posted, and read here can still be the row just left.
 */
class PastRows(private val rowsGrid: () -> VerticalGridView?) {
    private var grid: VerticalGridView? = null
    private var focused = -1

    fun update() {
        val grid = rowsGrid() ?: return
        if (grid === this.grid && grid.selectedPosition == focused) return
        focused = grid.selectedPosition
        if (grid !== this.grid) {
            this.grid = grid
            grid.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    view.animate().cancel()
                    view.alpha = if (grid.getChildAdapterPosition(view) < grid.selectedPosition) 0f else 1f
                }

                override fun onChildViewDetachedFromWindow(view: View) = Unit
            })
        }
        for (i in 0 until grid.childCount) {
            val row = grid.getChildAt(i)
            val past = grid.getChildAdapterPosition(row) < focused
            val alpha = if (past) 0f else 1f
            row.animate().cancel()
            if (row.alpha == alpha) continue
            row.animate().alpha(alpha).setStartDelay(0).setInterpolator(Motion.easeOut)
                .setDuration(if (past) Motion.EXIT else Motion.ENTER).withLayer().start()
        }
    }
}
