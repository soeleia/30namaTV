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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
    /** Start-anchored rows that remember their card, as on home, by [rowIds]. */
    private val rows = ArrayObjectAdapter(CardRowPresenter())
    /** What Play picks up, after its verb: a series' episode («فصل ۱، قسمت ۴»), a film's time left («۴۳ دقیقه مانده»). */
    private var resumeLabel: String? = null
    /** What Play does with [resumeLabel]'s episode or film: resume it, start the next one (UpNext.kt), or start it over. */
    private var playVerb = R.string.watch
    /** The plan asked for again from Play (see [knowUser]); one at a time. */
    private var userAsk: Job? = null
    /** The row and tile of the episode Play leads to: where Down first lands, in its season (and its stretch of a long one). */
    private var landing: Pair<Int, Int>? = null
    /** The seasons' rows of tiles, in order; coming back to the page their tiles catch up in place (see [onRestart]). */
    private val episodeRows = mutableListOf<ArrayObjectAdapter>()
    /** In the seasons' place while 30nama's answer failed or came back empty (see [noteEpisodes]). */
    private var episodesNote: ListRow? = null
    /** Another ask for the episodes, from the note or on coming back; one at a time. */
    private var episodesAsk: Job? = null
    /** Each row's id, which keeps its last focused card: rows come and go, so not their place. */
    private var rowIds = 0L
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
        val page = findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
        fun onPage(view: View?) = generateSequence(view) { it.parent as? View }.any { it === page }
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { old, new ->
            // Focus from nowhere fell from a view taken away (the seasons replacing their note), and
            // the grid gives it back in the same layout: not the remote moving, so nothing moves.
            if (old == null) return@addOnGlobalFocusChangeListener
            // Nor is focus going to a sheet over the page and coming back (OK held on a poster): the
            // rows slid down behind its scrim and up again as it closed, landing on Play's episode.
            if (!onPage(old) || new != null && !onPage(new)) return@addOnGlobalFocusChangeListener
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

    /**
     * Back from the player, most likely: the server's point has moved and Watched with it, so Play and
     * the tiles follow, a film's Play too. A page with nothing to bring up to date answers at once.
     */
    override fun onRestart() {
        super.onRestart()
        askEpisodes()
    }

    private fun askEpisodes() {
        if (episodesAsk?.isActive != true) episodesAsk = lifecycleScope.launch { loadEpisodes() }
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
        watch.setOnClickListener {
            if (!Api.loggedIn || Api.user != null) return@setOnClickListener play(title)
            // A session with no plan yet (knowUser): asked for first, or Play would say to upgrade a plan it never saw.
            if (userAsk?.isActive != true) userAsk = lifecycleScope.launch {
                runCatching { knowUser() }.onFailure {
                    if (it is CancellationException) throw it
                    return@launch toast(it)
                }
                play(title)
            }
        }
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
                toggleList(group, title.titleId) {
                    if (!isDestroyed) updateActions() // a flip the server did not take is undone, one that waited for the lists is made
                }
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
            // Resumed with nothing to say after the verb (a film of unknown length): the verb alone.
            else -> getString(if (playVerb == R.string.resume) R.string.resume else R.string.watch)
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
     * Called again once the rows are up, it only brings their tiles and Play up to date, unless the
     * rows themselves have changed. A film has no rows; the same answer says where its Play picks up.
     */
    private suspend fun loadEpisodes() {
        // Not a failure to say: Play asks again and says why (buildActions).
        runCatching { knowUser() }.onFailure { if (it is CancellationException) throw it }
        if (!title.streamable) return
        val stream = runCatching { Api.stream(title.titleId, title.isFree) }.getOrElse {
            if (it is CancellationException) throw it
            // Tiles already up keep what they show; none yet, their place says why. A film's Play
            // keeps its word, and the player says why if it can't play either.
            if (!title.isMovie && episodeRows.isEmpty()) noteEpisodes(errorText(it))
            return
        }
        val all = stream.optJSONArray("hls").objects()
        if (title.isMovie) return resumeFilm(all, stream.optJSONObject("continue_watching"))
        // Play's episode by the player's own rule (UpNext.kt): saved in the credits, it is the next one
        // not yet seen, from its start (a finale starts over), so the button and the marked tile say what Play does.
        val seasons = all.groupBy { it.obj("info")?.optInt("season") ?: 0 }.filterKeys { it != 0 }.toSortedMap()
        if (seasons.isEmpty()) return run { if (episodeRows.isEmpty()) noteEpisodes(getString(R.string.episodes_none)) }
        val saved = stream.optJSONObject("continue_watching")
        // The server's point or where this TV left it, as the player picks (Watched.resumePoint).
        val point = Watched.resumePoint(this, title.titleId, all, saved)
        Watched.seed(this, all, saved)
        val savedIndex = point?.first ?: -1
        val (playIndex, playAt) = Watched.upNext(this, all, savedIndex, point?.second ?: 0L)
        val resumeFile = all.getOrNull(playIndex)?.str("info", "file_id")
        // The marked tile: how far in where Play resumes it, none where Play starts it, which it says as the next one.
        val length = all.getOrNull(playIndex)?.let { Watched.of(this, it)?.duration }?.takeIf { it > 0 }
        // Nor is a tile marked that Play starts over, seen to its end (a finished series' finale): it keeps its check.
        val again = playIndex == savedIndex && playAt == 0L && all.getOrNull(playIndex)?.let { Watched.of(this, it)?.done } == true
        val watching = resumeFile?.takeUnless { again }?.let { JSONObject().put("progress", if (playIndex == savedIndex && length != null) playAt * 100 / length else 0).put("next", playIndex != savedIndex) }
        playVerb = when {
            playIndex != savedIndex -> R.string.upnext_play
            playAt > 0 -> R.string.resume
            else -> R.string.watch
        }
        resumeLabel = null
        landing = null
        // Every row the seasons make now, by its header: a season's own, or one per fifty of a long one.
        val built = mutableListOf<Pair<String, List<Card>>>()
        seasons.forEach { (season, files) ->
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
                if (resumeAt in range) landing = built.size to resumeAt - range.first
                val header = if (stretches.isEmpty()) name else getString(R.string.season_range, name, rangeLabel(files, range))
                built += header to files.slice(range).map(::card)
            }
        }
        updateActions()
        // The rows as they stand: their tiles catch up in place, the one under the remote keeping it.
        if (built.map { it.first } == episodeRows.indices.map { (rows[it] as ListRow).headerItem.name }) {
            return built.forEachIndexed { at, (_, cards) -> episodeRows[at].setItems(cards, SameEpisode) }
        }
        // Else they are made again: the first time, in the note's place, or because a season came out
        // or a long one ran into its next fifty while the page was open. Filled in by their old
        // places, every row after such a change showed the stretch before its own under its old header.
        val old = if (episodesNote != null) 1 else episodeRows.size
        val among = rowsFragment.view?.hasFocus() == true && (rowsFragment.verticalGridView?.selectedPosition ?: old) < old
        val episodes = EpisodePresenter(this)
        val made = built.map { (header, cards) -> ListRow(HeaderItem(rowIds++, header), ArrayObjectAdapter(episodes).apply { setItems(cards, null) }) }
        if (old == 0) rows.addAll(0, made) else {
            // What was first gives its place to the first season, so a remote on it stays in that row.
            rows.replace(0, made.first())
            rows.removeItems(1, old - 1)
            rows.addAll(1, made.drop(1))
        }
        episodeRows.clear()
        made.mapTo(episodeRows) { it.adapter as ArrayObjectAdapter }
        episodesNote = null
        // The remote was among them (asked again from the note, say): on to Play's episode.
        if (among) {
            val (row, tile) = landing ?: (0 to 0)
            landing = null
            rowsFragment.setSelectedPosition(row, false, ListRowPresenter.SelectItemViewHolderTask(tile).apply { isSmoothScroll = false })
        }
    }

    /**
     * A film's Play, as a series' is: left part-way it reads «ادامهٔ تماشا  ۴۳ دقیقه مانده», where the
     * player picks it up by its own rule (PlayerActivity.load) and in the words of home's continue
     * card. Only series asked before, and a half-watched film said «تماشا».
     */
    private fun resumeFilm(files: List<JSONObject>, saved: JSONObject?) {
        val point = Watched.resumePoint(this, title.titleId, files, saved)
        Watched.seed(this, files, saved)
        // Left in its credits, a film starts over (UpNext.kt), and Play says «تماشا» again.
        val (index, at) = Watched.upNext(this, files, point?.first ?: -1, point?.second ?: 0L)
        // Its length as this TV played it or the server's point implies it, else 30nama's own minutes.
        val length = files.getOrNull(index)?.let { Watched.of(this, it)?.duration }?.takeIf { it > 0 }
            ?: title.obj("info", "time")?.optInt("default")?.let { it * 60L } ?: 0L
        playVerb = if (at > 0) R.string.resume else R.string.watch
        resumeLabel = if (at <= 0 || at >= length) null
        else JSONObject().put("data", JSONObject().put("time", at).put("progress", at * 100.0 / length)).shortTimeLeft(this)
        updateActions()
    }

    /**
     * Straight after a sign-in whose `user` answer never came (LoginActivity waits 5 s for it, home
     * asks once more), the TV holds a session and no plan: every paid title then read as one the plan
     * can't stream, a series without its episodes and Play saying to upgrade. Asked for again here,
     * before either is decided. A session 30nama refuses is home's to end (MainActivity.loadUser).
     */
    private suspend fun knowUser() {
        if (!Api.loggedIn || Api.user != null) return
        Api.user().takeIf { it.optBoolean("success") }?.let { Api.user = it.optJSONObject("result") }
    }

    /**
     * The seasons' place while 30nama's answer failed or came back empty: «قسمت‌ها بارگیری نشد» over
     * [why] and Retry, where the first season would be, so the page never just goes without them.
     * Down from Play lands on it; coming back to the page asks again on its own (see [onRestart]).
     */
    private fun noteEpisodes(why: String) {
        val card = Card(why, getString(R.string.retry)) {
            if (episodesAsk?.isActive == true) return@Card
            (episodesNote?.adapter as? ArrayObjectAdapter)?.replace(0, Card(why, getString(R.string.intro_loading)) {})
            askEpisodes()
        }
        episodesNote?.let { return (it.adapter as ArrayObjectAdapter).replace(0, card) }
        val note = ListRow(HeaderItem(rowIds++, getString(R.string.episodes_failed)), ArrayObjectAdapter(EpisodesNotePresenter()).apply { add(card) })
        rows.add(0, note)
        episodesNote = note
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
        rows.add(ListRow(HeaderItem(rowIds++, header), ArrayObjectAdapter(CastPresenter()).apply { setItems(cards, null) }))
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
        rows.add(ListRow(HeaderItem(rowIds++, shelf.name), ArrayObjectAdapter(PosterPresenter(108, 162)).apply { setItems(cards, null) }))
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
