package com.doxigo.tv30

import android.content.Intent
import android.annotation.SuppressLint
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.app.ErrorSupportFragment
import androidx.leanback.app.RowsSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.DiffCallback
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.OnItemViewSelectedListener
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.PresenterSelector
import androidx.leanback.widget.SinglePresenterSelector
import androidx.leanback.widget.VerticalGridView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray

class MainActivity : TvActivity() {
    private var rail: Rail? = null
    private var intro: Intro? = null
    private val home get() = supportFragmentManager.findFragmentById(R.id.browse_host) as? HomeFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Like the official app: without a session every launch opens on the QR login, which
        // offers to carry on as a guest.
        if (savedInstanceState == null && !Api.loggedIn && !intent.getBooleanExtra(EXTRA_GUEST, false)) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.rail_screen)
        if (savedInstanceState == null) supportFragmentManager.beginTransaction().replace(R.id.browse_host, HomeFragment()).commit()
        rail = Rail(this, Rail.Section.HOME) { home?.mainFragment?.view }
        // From the launcher's Play Next row (WatchNext): straight into the player, with home
        // loading behind it for Back. The intro would only play unseen under the player.
        val play = if (savedInstanceState == null) intent.getIntExtra(WatchNext.EXTRA_PLAY, 0) else 0
        if (play != 0) {
            Intro.shown = true
            startActivity(PlayerActivity.intent(this, play, intent.getBooleanExtra(WatchNext.EXTRA_FREE, false)))
        }
        // The first home of the process opens on the intro, which plays while home loads.
        if (!Intro.shown) {
            Intro.shown = true
            intro = Intro(this)
        }
        lifecycleScope.launch {
            delay(5_000)
            AppUpdates.check()
        }
    }

    /** Another page's rail asked for one of home's rows. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasExtra(EXTRA_JUMP)) jumpTo(intent.getIntExtra(EXTRA_JUMP, HomeFragment.TOP))
    }

    fun jumpTo(row: Int) {
        home?.jumpTo(row)
    }

    /** Reveal ready rows or the offline notice immediately. */
    fun homeReady(rows: Boolean) {
        if (rows) rail?.ready()
        intro?.leave()
    }

    override fun onSettled() {
        home?.settled()
    }

    override fun onDestroy() {
        intro?.dispose()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        (supportFragmentManager.backStackEntryCount == 0 && (rail?.openOnBack(event) == true || home?.upToHero(event) == true)) ||
            super.dispatchKeyEvent(event)

    companion object {
        const val EXTRA_GUEST = "guest"
        private const val EXTRA_JUMP = "jump"

        /** A fresh task on home, with [then] (a page to come back to, say) on top of it. */
        fun restart(activity: android.app.Activity, guest: Boolean = false, then: Intent? = null) {
            val home = Intent(activity, MainActivity::class.java)
                .putExtra(EXTRA_GUEST, guest)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            activity.startActivities(listOfNotNull(home, then).toTypedArray())
        }

        /** Back to the home already running underneath, at [row]. */
        fun jump(context: android.content.Context, row: Int): Intent =
            Intent(context, MainActivity::class.java).putExtra(EXTRA_JUMP, row)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}

class HomeFragment : BrowseSupportFragment() {
    private val rows = ArrayObjectAdapter(CardRowPresenter())
    private val rowsByKey = sortedMapOf<Int, ListRow>()
    private val posters = PosterPresenter(108, 162)
    private val tiles = SinglePresenterSelector(TilePresenter(208, 117))
    private val lists = SinglePresenterSelector(ListsPresenter())
    /** A title row: posters, ended by a poster-sized "see all" card where the row has a catalogue. */
    private val titles = object : PresenterSelector() {
        private val seeAll = SeeAllPresenter(108, 162)
        override fun getPresenter(item: Any?): Presenter = if ((item as Card).icon != null) seeAll else posters
        override fun getPresenters(): Array<Presenter> = arrayOf(posters, seeAll)
    }
    /** 30nama's promoted titles over the rows. It owns the billboard; the rows reach it through [PromoHero.follow]. */
    private var hero: PromoHero? = null
    private var loaded = false
    /** The card focus rests on while a held D-pad races past: the billboard catches up on release. */
    private var waiting: Card? = null
    /** The rows grid: its selection is the row the remote is on. */
    private val rowsGrid get() = (mainFragment as? RowsSupportFragment)?.verticalGridView
    /** The row [fadePast] last faded the others for, and the grid whose children follow it. */
    private var focusedRow = -1
    private var grid: VerticalGridView? = null
    /** When home last fetched everything, and the wait for the next time (see [keepFresh]). */
    private var loadedAt = 0L
    private var freshness: Job? = null
    /** Library.changes as the My List row was fetched: it is fetched again only after a toggle. */
    private var watchlistAt = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The activity's rail carries the brand, search and the sections, so Leanback's own
        // title bar and section list stay off.
        headersState = HEADERS_DISABLED
        adapter = rows
        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ -> (item as? Card)?.onClick?.invoke() }
        onItemViewSelectedListener = OnItemViewSelectedListener { _, item, _, _ ->
            (item as? Card)?.let(::feature)
            fadePast()
        }
        load()
    }

    /** The billboard follows focus, but not through a held D-pad: rewriting it per card would make the row stutter. */
    private fun feature(card: Card) {
        if (Motion.fast) waiting = card else hero?.follow(card)
    }

    /** The D-pad was let go (see TvActivity.onSettled). */
    fun settled() {
        waiting?.let { hero?.follow(it) }
        waiting = null
    }

    /**
     * Rows that scroll above the focused one fade out on their way up instead of being cut off
     * at the billboard's foot, and come back as the remote returns to them. Rows recycle, so
     * each one takes the right alpha again as it is attached.
     *
     * The row is the grid's own selection, never BrowseSupportFragment.selectedPosition: Leanback
     * posts that one, so read here it is still the row just left, and on the way up it faded out
     * the row the remote had just landed on (and on Up again the hero came back over no rows).
     */
    private fun fadePast() {
        val rowsGrid = rowsGrid ?: return
        if (rowsGrid === grid && rowsGrid.selectedPosition == focusedRow) return
        focusedRow = rowsGrid.selectedPosition
        if (rowsGrid !== grid) {
            grid = rowsGrid
            rowsGrid.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    view.animate().cancel()
                    view.alpha = if (rowsGrid.getChildAdapterPosition(view) < rowsGrid.selectedPosition) 0f else 1f
                }

                override fun onChildViewDetachedFromWindow(view: View) = Unit
            })
        }
        for (i in 0 until rowsGrid.childCount) {
            val row = rowsGrid.getChildAt(i)
            val past = rowsGrid.getChildAdapterPosition(row) < focusedRow
            val alpha = if (past) 0f else 1f
            row.animate().cancel()
            if (row.alpha == alpha) continue
            row.animate().alpha(alpha).setStartDelay(0).setInterpolator(Motion.easeOut)
                .setDuration(if (past) Motion.EXIT else Motion.ENTER).withLayer().start()
        }
    }

    /** Brings the row at [key] under the billboard and puts the remote on it; [TOP] is the hero, else the first row. */
    fun jumpTo(key: Int) {
        if (key == TOP && hero?.focus() == true) {
            setSelectedPosition(0, false)
            return
        }
        val row = rowsByKey[key] ?: rowsByKey.values.firstOrNull() ?: return
        setSelectedPosition(rows.indexOf(row), true)
        mainFragment?.view?.requestFocus()
    }

    /**
     * Up from the first row: only the hero is above it. A key hook, because Leanback answers Up
     * from the first row with its own empty title view and the press goes nowhere.
     */
    fun upToHero(event: KeyEvent): Boolean =
        event.keyCode == KeyEvent.KEYCODE_DPAD_UP && event.action == KeyEvent.ACTION_DOWN && rowsGrid?.selectedPosition == 0 &&
            mainFragment?.view?.hasFocus() == true && hero?.focus() == true

    /** The rows sit on the billboard: its art and text behind, the rows' dock below it. */
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val browse = super.onCreateView(inflater, container, savedInstanceState)!!
        val root = inflater.inflate(R.layout.billboard, container, false) as FrameLayout
        root.addView(browse)
        browse.findViewById<View>(androidx.leanback.R.id.browse_container_dock)?.let { dock ->
            (dock.layoutParams as ViewGroup.MarginLayoutParams).topMargin = resources.getDimensionPixelSize(R.dimen.billboard_height)
            // The rows arrive together after the parallel load; they rise in once, at launch.
            if (!loaded) {
                dock.alpha = 0f
                dock.translationY = Motion.travel(16 * resources.displayMetrics.density)
            }
        }
        val scope = viewLifecycleOwner.lifecycleScope
        hero = PromoHero(root, scope, Billboard(root, scope), browse)
        return root
    }

    override fun onDestroyView() {
        hero = null
        super.onDestroyView()
    }

    override fun onStart() {
        val onHero = hero?.active == true
        super.onStart()
        // Leanback hands the rows the remote on every start; back from Play or More, it returns to the hero.
        if (onHero) hero?.focus()
        if (!loaded) return
        // Back from the player: progress moved, and the continue row with it; back from a title
        // page whose toggle changed the watchlist, My List too. A stale home reloads them with the rest.
        if (Api.loggedIn && !stale) {
            lifecycleScope.launch { loadContinueWatching() }
            if (Library.changes != watchlistAt) lifecycleScope.launch { loadWatchlist() }
        }
        keepFresh()
    }

    override fun onStop() {
        freshness?.cancel()
        super.onStop()
    }

    private val stale get() = SystemClock.elapsedRealtime() - loadedAt >= STALE

    /**
     * As the official home does, everything is fetched again once it is half an hour old: at once
     * if home comes back to the front that old, else once it has been in front that long. The
     * count only runs while home is started.
     */
    private fun keepFresh() {
        freshness?.cancel()
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        freshness = lifecycleScope.launch {
            while (true) {
                delay(loadedAt + STALE - SystemClock.elapsedRealtime())
                refresh()
            }
        }
    }

    /**
     * The hero, every row, the lists and the user (their days left) fetched again and swapped in
     * place: rows keep their slots and the cards still in them (see [setRow]), so focus and each
     * row's place hold. Whatever fails keeps what it had, quietly.
     */
    private fun refresh() {
        loadedAt = SystemClock.elapsedRealtime()
        val context = context ?: return
        lifecycleScope.launch {
            val trending = async { Trending.titles(context) }
            coroutineScope {
                if (Api.loggedIn) {
                    launch { loadUser() }
                    launch { runCatching { Library.load() } }
                    launch { loadContinueWatching() }
                    launch { loadWatchlist() }
                }
                launch { runCatching { Api.home() }.onSuccess { showHome(it) } }
                launch { runCatching { Api.menu() }.onSuccess(::showMenu) }
            }
            showLists()
            showTrending(trending.await())
        }
    }

    private fun load() {
        lifecycleScope.launch {
            progressBarManager.show()
            if (!Api.initialize()) {
                progressBarManager.hide()
                (activity as? MainActivity)?.homeReady(rows = false)
                showOffline()
                return@launch
            }
            // Account and menu requests must not hold ready catalogue rows behind the intro.
            val home = async { runCatching { Api.home() } }
            launch { runCatching { Api.menu() }.onSuccess(::showMenu) }
            if (Api.loggedIn) {
                launch { loadUser(); showLists() }
                launch { runCatching { Library.load() } }
                launch { loadContinueWatching(); keepFirstRow() }
                launch { loadWatchlist() }
            }
            home.await().onSuccess { showHome(it) }.onFailure { context?.toast(it) }
            showLists()
            setSelectedPosition(0, false)
            // Once the rows are laid out, the remote starts on Play (or the first poster, with
            // nothing promoted). The rows and the hero's buttons rise in as the launch intro (if
            // any) starts to dissolve, so it all reads as one move.
            (activity as? MainActivity)?.homeReady(rows = true)
            view?.post { if (hero?.focus() != true) mainFragment?.view?.requestFocus() }
            view?.findViewById<View>(androidx.leanback.R.id.browse_container_dock)?.animate()
                ?.alpha(1f)?.translationY(0f)?.setStartDelay(0)?.setInterpolator(Motion.emph)?.setDuration(Motion.PAGE)?.start()
            hero?.arrive(0)
            loaded = true
            loadedAt = SystemClock.elapsedRealtime()
            keepFresh()
            progressBarManager.hide()
            // Trending's catalogue searches share the API dispatcher; start them after home.
            showTrending(Trending.titles(requireContext()))
        }
    }

    private suspend fun showHome(home: JSONObject) {
        val context = requireContext()
        suspend fun cards(section: String) = withContext(Dispatchers.Default) {
            home.optJSONObject(section)?.optJSONArray("posts").objects().map { t -> t.toCard(context) { context.openDetails(t) } }
        }
        // As in the official app, a catalogue row ends in a card that opens the whole of it.
        suspend fun catalogue(section: String, @StringRes name: Int) = cards(section).let { posts ->
            if (posts.isEmpty()) posts
            else posts + seeAll(posts) { startActivity(GridActivity.archive(context, section, getString(name))) }
        }
        // 30nama's promoted titles are the hero, not a row: the same nine, one press away.
        hero?.show(home.optJSONObject("hero_header")?.optJSONArray("posts").objects())
        setRow(SUGGESTED, R.string.suggested, catalogue("suggested", R.string.suggested))
        setRow(MOVIES, R.string.movies, catalogue("movies", R.string.movies))
        setRow(SERIES, R.string.series, catalogue("series", R.string.series))
        setRow(ANIME, R.string.anime, catalogue("anime", R.string.anime))
    }

    /** TMDB's week as 30nama titles. No TMDB token, no answer or no match: no row. */
    private fun showTrending(titles: List<JSONObject>) {
        val context = context ?: return
        if (titles.isEmpty()) return
        setRow(TRENDING, R.string.trending_week, titles.map { t -> t.toCard(context) { context.openDetails(t) } })
        keepFirstRow()
    }

    /** A late row landing first must not take the selection: the hero keeps the first row under it. */
    private fun keepFirstRow() {
        if (hero?.active == true) setSelectedPosition(0, false)
    }

    /**
     * The watchlist's latest additions as My List, the official mobile app's row, ending in the
     * whole list. Guests and an empty list get no row; a failed fetch keeps what is there (null).
     */
    private suspend fun watchlistCards(): List<Card>? {
        if (!Api.loggedIn) return null
        val at = Library.changes
        val list = runCatching { Api.userList("watchlist", 1, "add_list") }.getOrNull() ?: return null
        val context = context ?: return null
        watchlistAt = at
        val cards = withContext(Dispatchers.Default) {
            list.optJSONArray("posts").objects().map { t -> t.toCard(context) { context.openDetails(t) } }
        }
        if (cards.isEmpty()) return cards
        val name = getString(R.string.my_watchlist)
        return cards + seeAll(cards) { startActivity(GridActivity.list(context, "watchlist", name)) }
    }

    /** The card ending a row of [posts] that opens all of it, its art four of their posters (SeeAll.kt). */
    private fun seeAll(posts: List<Card>, onClick: () -> Unit) =
        Card(getString(R.string.see_all), icon = R.drawable.ic_more, mosaic = posts.mapNotNull { it.image }.take(4), onClick = onClick)

    private fun showWatchlist(cards: List<Card>) {
        setRow(WATCHLIST, R.string.my_watchlist, cards)
        keepFirstRow()
    }

    private suspend fun loadWatchlist() {
        watchlistCards()?.let(::showWatchlist)
    }

    private fun showMenu(menu: JSONObject) {
        val context = requireContext()
        fun cards(key: String) = menu.optJSONArray(key).objects().map { it.toMenuCard(context) }
        setRow(CATEGORIES, R.string.categories, cards("categories"), tiles)
        val genres = menu.optJSONArray("genres") ?: JSONArray()
        val all = cards("genres")
        setRow(GENRES, R.string.genres, if (all.isEmpty()) all else all + seeAll(all) {
            startActivity(GridActivity.genres(context, genres))
        }, tiles)
    }

    private suspend fun loadUser() {
        val response = runCatching { Api.user() }.getOrNull() ?: return
        // A refused session means it expired or was revoked elsewhere; a network error does not.
        if (response.optBoolean("success")) Api.user = response.optJSONObject("result")
        else forgetSession(requireContext())
    }

    private suspend fun loadContinueWatching() {
        continueWatchingCards()?.let { setRow(CONTINUE, R.string.continue_watching, it) }
    }

    private suspend fun continueWatchingCards(): List<Card>? {
        val result = runCatching { Api.continueWatching() }.getOrNull() ?: return null
        val context = context ?: return null
        val posts = ((result as? JSONObject)?.optJSONArray("posts") ?: result as? org.json.JSONArray).objects()
        // The launcher's Play Next row mirrors this one. Api.scope, so leaving home mid-sync can't cut it short.
        Api.scope.launch { WatchNext.sync(context.applicationContext, posts) }
        val cards = withContext(Dispatchers.Default) {
            posts.map { t ->
                // data: {episode_title: "فصل 1 - قسمت 4", display_time: "00:33:00", progress, ...}. The
                // red bar already says how far in, so the line names only the episode: «فصل ۱، قسمت ۴».
                val where = t.str("data", "episode_title")?.episodeLabel()
                val progress = t.str("data", "progress")?.toFloatOrNull()?.div(100)
                t.toCard(context, lead = where, progress = progress, left = t.timeLeft(context), caption = t.shortTimeLeft(context), onLongClick = { confirmDismiss(t) }) {
                    startActivity(PlayerActivity.intent(context, t.titleId, t.isFree))
                }
            }
        }
        hero?.resumable = posts.mapTo(HashSet()) { it.titleId }
        return cards
    }

    private fun confirmDismiss(title: JSONObject) {
        requireActivity().confirm(title.englishName, getString(R.string.continue_remove_question), getString(R.string.continue_remove), art = title.poster) {
            lifecycleScope.launch {
                runCatching { Api.dismissContinueWatching(title.titleId) }
                loadContinueWatching()
            }
        }
    }

    private fun showLists() {
        if (!Api.loggedIn) return setRow(LISTS, R.string.my_lists, emptyList())
        val context = requireContext()
        val names = mapOf("favorite" to R.string.list_favorite, "watchlist" to R.string.list_watchlist, "notify" to R.string.list_notify)
        val icons = mapOf("favorite" to R.drawable.ic_favorite, "watchlist" to R.drawable.ic_watchlist, "notify" to R.drawable.ic_notify)
        val cards = Library.GROUPS.map { group ->
            val name = getString(names.getValue(group))
            Card(name, icon = icons.getValue(group)) { startActivity(GridActivity.list(context, group, name)) }
        }
        setRow(LISTS, R.string.my_lists, cards, lists)
    }

    private fun showOffline() {
        val context = requireContext()
        val error = ErrorSupportFragment().apply {
            message = context.getString(R.string.no_internet)
            imageDrawable = ContextCompat.getDrawable(context, androidx.leanback.R.drawable.lb_ic_sad_cloud)
            buttonText = context.getString(R.string.retry)
            setDefaultBackground(true)
        }
        val fragments = requireActivity().supportFragmentManager
        error.buttonClickListener = View.OnClickListener {
            fragments.popBackStack()
            load()
        }
        fragments.beginTransaction().add(android.R.id.content, error).addToBackStack(null).commit()
    }

    /**
     * Rows live at fixed slots ([key]) so a late answer lands in its place and empty ones vanish.
     * A row already there takes its new cards in place, rebinding only what changed (see
     * [SAME_CARD]): the focused card keeps the remote, a moved one takes it along, and the row
     * stays where it was scrolled.
     */
    private fun setRow(key: Int, @StringRes name: Int, cards: List<Card>, presenter: PresenterSelector = titles) {
        val old = rowsByKey[key]
        if (cards.isEmpty()) {
            if (old != null) rows.remove(old)
            rowsByKey.remove(key)
            return
        }
        if (old != null) return (old.adapter as ArrayObjectAdapter).setItems(cards, SAME_CARD)
        val items = ArrayObjectAdapter(presenter).apply { setItems(cards, null) }
        val row = ListRow(HeaderItem(key.toLong(), getString(name)), items)
        rows.add(rowsByKey.headMap(key).size, row)
        rowsByKey[key] = row
    }

    companion object {
        /** How old home's rows may get before they are fetched again: the official app's half hour. */
        private const val STALE = 30 * 60_000L

        /** Cards are rebuilt on every load; one showing the same title the same way is the same card. */
        private val SAME_CARD = object : DiffCallback<Card>() {
            override fun areItemsTheSame(old: Card, new: Card) = old.title == new.title && old.image == new.image && old.icon == new.icon
            @SuppressLint("DiffUtilEquals") // Kotlin lists compare the mosaic URLs by value and order.
            override fun areContentsTheSame(old: Card, new: Card) =
                old.subtitle.toString() == new.subtitle.toString() && old.facts.toString() == new.facts.toString() &&
                    old.progress == new.progress && old.free == new.free && old.plot == new.plot && old.backdrop == new.backdrop &&
                    old.mosaic == new.mosaic
        }

        const val TOP = -1
        const val CONTINUE = 0
        const val WATCHLIST = 1
        const val TRENDING = 2
        const val SUGGESTED = 3
        const val MOVIES = 4
        const val SERIES = 5
        const val ANIME = 6
        const val LISTS = 7
        const val CATEGORIES = 8
        const val GENRES = 9
    }
}

/** The session is over (signed out here, or refused by the server): forget it and what came with it. */
fun forgetSession(context: android.content.Context) {
    Api.token = null
    Api.user = null
    Library.clear()
    Api.scope.launch { WatchNext.clear(context.applicationContext) }
}
