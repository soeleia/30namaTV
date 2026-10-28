package com.doxigo.tv30

import android.content.Context
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
import coil3.imageLoader
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.size.Scale
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import org.json.JSONTokener

class MainActivity : TvActivity() {
    private var rail: Rail? = null
    private var intro: Intro? = null
    private var introAt = 0L
    private val home get() = supportFragmentManager.findFragmentById(R.id.browse_host) as? HomeFragment

    /**
     * How long home drawn from the last run waits before it arrives. It is ready a few hundred ms
     * in, and the motion logo dissolving that soon only flashes, so it plays its first [INTRO_HOLD]
     * ms. The whole arrival waits, not just the logo, so the rows still rise as it dissolves.
     * Home fetched fresh never waits.
     */
    val introLeft get() = if (intro == null) 0L else introAt + INTRO_HOLD - SystemClock.elapsedRealtime()

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
        // From the launcher's Play Next row (WatchNext): straight into the player, on the row's
        // episode if it names one, or a followed series' page, with home loading behind it for
        // Back. The intro would only play unseen under them.
        val play = if (savedInstanceState == null) intent.getIntExtra(WatchNext.EXTRA_PLAY, 0) else 0
        val details = if (savedInstanceState == null) intent.getStringExtra(WatchNext.EXTRA_DETAILS) else null
        if (play != 0) startActivity(PlayerActivity.intent(this, play, intent.getBooleanExtra(WatchNext.EXTRA_FREE, false),
            intent.getStringExtra(WatchNext.EXTRA_FILE), intent.getStringExtra(WatchNext.EXTRA_TITLE)?.let(::JSONObject)))
        else details?.let { openDetails(JSONObject(it)) }
        if (play != 0 || details != null) Intro.shown = true
        // The first home of the process opens on the intro, which plays while home loads.
        if (!Intro.shown) {
            Intro.shown = true
            intro = Intro(this)
            introAt = SystemClock.elapsedRealtime()
        }
    }

    /**
     * Home coming to the front (back from the player, out of standby) looks for a new release, at
     * most every six hours (AppUpdates.checkDue): a process the TV keeps for days still finds it the
     * same day. Five seconds on: a launch's first seconds belong to home's own requests.
     */
    override fun onStart() {
        super.onStart()
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

    /** A list toggled from a quick-actions sheet over home (Sheets.kt), which never stops it. */
    fun listsChanged() = home?.listsChanged()

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
        (supportFragmentManager.backStackEntryCount == 0 &&
            (home?.noticeBack(event) == true || rail?.openOnBack(event) == true || home?.upToHero(event) == true)) ||
            super.dispatchKeyEvent(event)

    companion object {
        const val EXTRA_GUEST = "guest"
        private const val EXTRA_JUMP = "jump"
        private const val INTRO_HOLD = 1_500L

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
    /** A downloaded release, in the top end corner above the hero (UpdateNotice). */
    private var notice: UpdateNotice? = null
    private var loaded = false
    /** The rows came in while the rail had another section up, home's view gone: they rise when it is back. */
    private var riseOnView = false
    /** The TMDB key the trending row was fetched with: one saved in Settings since brings the row in on return. */
    private var trendingKey: String? = null
    /** The card focus rests on while a held D-pad races past, and its row: the billboard catches up on release. */
    private var waiting: Pair<Card, ArrayObjectAdapter?>? = null
    /** Where in which row the remote last rested, which says the way it is heading (see [follow]). */
    private var restRow: ArrayObjectAdapter? = null
    private var restAt = -1
    /** The covers either side of it on their way into the image cache, by URL, and the wait before they start. */
    private var nearby = emptyMap<String, Disposable>()
    private var nearing: Job? = null
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
        onItemViewSelectedListener = OnItemViewSelectedListener { _, item, _, row ->
            (item as? Card)?.let { feature(it, (row as? ListRow)?.adapter as? ArrayObjectAdapter) }
            fadePast()
        }
        load()
    }

    /** The billboard follows focus, but not through a held D-pad: rewriting it per card would make the row stutter. */
    private fun feature(card: Card, row: ArrayObjectAdapter?) {
        nearing?.cancel()
        if (Motion.fast) waiting = card to row else follow(card, row)
    }

    /** The D-pad was let go (see TvActivity.onSettled). */
    fun settled() {
        waiting?.let { (card, row) -> follow(card, row) }
        waiting = null
    }

    /**
     * The billboard takes [card]. Once the remote has rested on it as long as the billboard waits,
     * the covers either side start downloading too: two ahead the way it is heading, one behind.
     * A cover the CDN no longer holds comes from 30nama's origin in Iran, seconds away, and every
     * press used to wait for its own; now the next one is usually here already. Billboard's
     * request shape (1280x720, centre-crop) makes it a memory hit. Covers the remote has left
     * behind are dropped; the focused one never is, since the billboard's load may be riding on
     * it (App's DeDupe). Their TMDB logos (Logos) start at once and finish even once the remote
     * has moved on, as a lookup cut off at the next press used to leave the billboard asking afresh.
     * None of it with Settings.saveData.
     */
    // ponytail: neighbours in the row only; the rows above and below wait for their own rest.
    private fun follow(card: Card, row: ArrayObjectAdapter?) {
        hero?.follow(card)
        val at = row?.indexOf(card) ?: -1
        val step = if (row === restRow && at < restAt) -1 else 1
        restRow = row
        restAt = at
        if (row == null || at < 0 || Settings.saveData) return
        val nearCards = listOf(at + step, at + 2 * step, at - step).mapNotNull { i -> if (i in 0 until row.size()) row.get(i) as? Card else null }
        val near = nearCards.mapNotNull { it.backdrop }
        context?.let { context -> nearCards.mapNotNull { it.source }.forEach { lifecycleScope.launch { Logos.prefetch(context, it) } } }
        nearing = lifecycleScope.launch {
            delay(REST)
            val context = context ?: return@launch
            val keep = near + listOfNotNull(card.backdrop)
            nearby.forEach { (url, load) -> if (url !in keep) load.dispose() }
            nearby = near.associateWith { url ->
                nearby[url] ?: context.imageLoader.enqueue(ImageRequest.Builder(context).data(url).size(1280, 720).scale(Scale.FILL).build())
            }
        }
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
            // The row below the screen is attached too, so its posters are on their way before Down shows it.
            rowsGrid.prefetchAttachedRows()
            rowsGrid.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    view.animate().cancel()
                    view.alpha = if (rowsGrid.getChildAdapterPosition(view) < rowsGrid.selectedPosition) 0f else 1f
                }

                override fun onChildViewDetachedFromWindow(view: View) = Unit
            })
        }
        for (i in 0 until rowsGrid.childCount) {
            // Selection can land mid-layout (focus restored while rows attach and detach), when the
            // grid's child list has a gap; a row skipped here takes its alpha as it attaches.
            val row = rowsGrid.getChildAt(i) ?: continue
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
     * Up from the first row: only the hero is above it, or with no hero the update notice. A key
     * hook, because Leanback answers Up from the first row with its own empty title view and the
     * press goes nowhere.
     */
    fun upToHero(event: KeyEvent): Boolean =
        event.keyCode == KeyEvent.KEYCODE_DPAD_UP && event.action == KeyEvent.ACTION_DOWN && rowsGrid?.selectedPosition == 0 &&
            mainFragment?.view?.hasFocus() == true && (hero?.focus() == true || notice?.focus() == true)

    /** Back on the update notice puts it away rather than opening the rail. */
    fun noticeBack(event: KeyEvent) = notice?.back(event) == true

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
        hero = PromoHero(root, scope, Billboard(root, scope), browse).apply { onUp = { notice?.focus() } }
        // Back to the hero's button, or to the rows with no hero.
        notice = UpdateNotice(root, requireActivity(), viewLifecycleOwner) { jumpTo(TOP) }.apply { arrived = loaded }
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (riseOnView) view.post { rise() }
    }

    override fun onDestroyView() {
        hero = null
        notice = null
        super.onDestroyView()
    }

    override fun onStart() {
        val onHero = hero?.active == true
        super.onStart()
        // Leanback hands the rows the remote on every start; back from the title page or the player, it returns to the hero.
        if (onHero) hero?.focus()
        if (!loaded) return
        // Back from the player: progress moved, and the continue row with it; back from a title
        // page whose toggle changed the watchlist, My List too. A stale home reloads them with the rest.
        if (Api.loggedIn && !stale) {
            lifecycleScope.launch { loadContinueWatching() }
            listsChanged()
        }
        if (!stale && Tmdb.key != trendingKey) lifecycleScope.launch { loadTrending() }
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
            trendingKey = Tmdb.key
            val trending = async { Trending.titles(context) }
            coroutineScope {
                if (Api.loggedIn) {
                    launch { loadUser() }
                    launch { runCatching { Library.load() } }
                    launch { if (loadContinueWatching()) lookForEpisodes() }
                    launch { loadWatchlist() }
                }
                launch { runCatching { Api.home() }.onSuccess { keep(HomeCache.HOME, it); showHome(it) } }
                launch { loadMenu() }
            }
            showLists()
            showTrending(trending.await())
        }
    }

    /**
     * With home from the last run on disk (see [drawKept]) it is drawn at once and arrives as the
     * intro allows; the fetch below then lands on it as a refresh does, unchanged cards untouched.
     * Without one, home arrives when the catalogue answers, as it always did.
     */
    private fun load() {
        lifecycleScope.launch {
            val arrival = if (!drawKept()) null else {
                // The fetch under way counts as fresh: a return to home meanwhile reloads only what moves (onStart).
                loadedAt = SystemClock.elapsedRealtime()
                launch {
                    delay((activity as? MainActivity)?.introLeft ?: 0)
                    arrive()
                }
            }
            if (arrival == null) progressBarManager.show()
            if (!Api.initialize()) {
                if (arrival == null) {
                    progressBarManager.hide()
                    (activity as? MainActivity)?.homeReady(rows = false)
                    showOffline()
                    return@launch
                }
                // Home from the last run stays up and says so quietly, once it is in; it fetches as
                // soon as a server answers again.
                arrival.join()
                context?.toast(getString(R.string.no_internet))
                do delay(RETRY) while (!Api.initialize())
            }
            // Account and menu requests must not hold ready catalogue rows behind the intro.
            val home = async { runCatching { Api.home() } }
            launch { loadMenu() }
            if (Api.loggedIn) {
                launch { loadUser(); showLists() }
                launch { runCatching { Library.load() } }
                launch {
                    val loaded = loadContinueWatching()
                    keepFirstRow()
                    if (loaded) lookForEpisodes()
                }
                launch { loadWatchlist() }
            }
            home.await().onSuccess { keep(HomeCache.HOME, it); showHome(it) }.onFailure { context?.toast(it) }
            showLists()
            if (arrival == null) arrive()
            loadedAt = SystemClock.elapsedRealtime()
            keepFresh()
            // Trending's catalogue searches share the API dispatcher; start them after home.
            loadTrending()
        }
    }

    /**
     * Home's first appearance. Once the rows are laid out, the remote starts on the hero (or the
     * first poster, with nothing promoted). The rows and the hero's button rise in as the launch
     * intro (if any) starts to dissolve, so it all reads as one move.
     */
    private fun arrive() {
        (activity as? MainActivity)?.homeReady(rows = true)
        rise()
    }

    /** The rows and the hero's button rising in, and the remote on the hero; put off while home's view is gone. */
    private fun rise() {
        riseOnView = view == null
        if (riseOnView) return
        setSelectedPosition(0, false)
        view?.post { if (hero?.focus() != true) mainFragment?.view?.requestFocus() }
        view?.findViewById<View>(androidx.leanback.R.id.browse_container_dock)?.animate()
            ?.alpha(1f)?.translationY(0f)?.setStartDelay(0)?.setInterpolator(Motion.emph)?.setDuration(Motion.PAGE)?.start()
        hero?.arrive(0)
        loaded = true
        notice?.arrived = true
        progressBarManager.hide()
    }

    /** Where this account's home is kept, in this language; null when it is no one's (HomeCache.key). */
    private fun keptKey() = HomeCache.key(Api.loggedIn, Api.user?.str("user_id"), Api.language)

    /** A fresh answer home is drawn from, kept for the next launch. Written in order, off the main thread. */
    private fun keep(part: String, json: Any) {
        val dir = context?.filesDir?.resolve(KEPT) ?: return
        val key = keptKey() ?: return
        Api.scope.launch(keeping) { HomeCache.write(dir, key, part, json.toString()) }
    }

    /**
     * Home as the last run left it for this account and language, drawn whole: rows, hero and
     * billboard, their art from the image cache. False when there is none, or no catalogue in it.
     */
    private suspend fun drawKept(): Boolean {
        val key = keptKey() ?: return false
        val dir = requireContext().filesDir.resolve(KEPT)
        // Read and parsed off the main thread: home alone is ~300 KB of JSON. A file cut short or garbled is a miss.
        val (home, menu, watching, watchlist) = withContext(Dispatchers.IO) {
            listOf(HomeCache.HOME, HomeCache.MENU, HomeCache.WATCHING, HomeCache.WATCHLIST).map { part ->
                HomeCache.read(dir, key, part)?.let { runCatching { JSONTokener(it).nextValue() }.getOrNull() }
            }
        }
        showHome(home as? JSONObject ?: return false)
        (menu as? JSONObject)?.let(::showMenu)
        if (Api.loggedIn) {
            (watching as? JSONArray)?.let { showContinueWatching(it) }
            (watchlist as? JSONObject)?.let { showWatchlist(it) }
        }
        showLists()
        return true
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
    private suspend fun loadTrending() {
        trendingKey = Tmdb.key
        showTrending(Trending.titles(requireContext()))
    }

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

    /** The watchlist's latest additions. Guests get no row; a failed fetch keeps what is there. */
    /** My List again, if a toggle changed it since it was fetched: on return to home, or under a quick-actions sheet. */
    fun listsChanged() {
        if (Api.loggedIn && Library.changes != watchlistAt) lifecycleScope.launch { loadWatchlist() }
    }

    private suspend fun loadWatchlist() {
        if (!Api.loggedIn) return
        val at = Library.changes
        val list = runCatching { Api.userList("watchlist", 1, "add_list") }.getOrNull() ?: return
        watchlistAt = at
        keep(HomeCache.WATCHLIST, list)
        showWatchlist(list)
    }

    /** [list] as My List, the official mobile app's row, ending in the whole list. An empty list gets no row. */
    private suspend fun showWatchlist(list: JSONObject) {
        val context = context ?: return
        val cards = withContext(Dispatchers.Default) {
            list.optJSONArray("posts").objects().map { t -> t.toCard(context) { context.openDetails(t) } }
        }
        val name = getString(R.string.my_watchlist)
        setRow(WATCHLIST, R.string.my_watchlist, if (cards.isEmpty()) cards else cards + seeAll(cards) {
            startActivity(GridActivity.list(context, "watchlist", name))
        })
        keepFirstRow()
    }

    /** The card ending a row of [posts] that opens all of it, its art four of their posters (SeeAll.kt). */
    private fun seeAll(posts: List<Card>, onClick: () -> Unit) =
        Card(getString(R.string.see_all), icon = R.drawable.ic_more, mosaic = posts.mapNotNull { it.image }.take(4), onClick = onClick)

    private suspend fun loadMenu() {
        runCatching { Api.menu() }.onSuccess { keep(HomeCache.MENU, it); showMenu(it) }
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

    /** The continue row's last answer, as the server sent it; what shows leaves out [dismissed] titles. */
    private var watching = JSONArray()

    /** A failed fetch keeps what is there, and says so (false); one from the last run is replaced as soon as this answers, as progress moves. */
    private suspend fun loadContinueWatching(): Boolean {
        val result = runCatching { Api.continueWatching() }.getOrNull() ?: return false
        val context = context ?: return false
        val posts = (result as? JSONObject)?.optJSONArray("posts") ?: result as? JSONArray ?: JSONArray()
        // Gone from the server's list too: watched again later, a title may come back.
        dismissed.retainAll(posts.objects().map { it.titleId }.toSet())
        showContinueWatching(posts)
        mirror(context)
        return true
    }

    /**
     * The notify list's series as last fetched: with the row's, what NewEpisodes looks at, and the
     * launcher's news of them (WatchNext). Null until it is in, so the launcher's news stays as it is
     * meanwhile rather than going and coming back.
     */
    private var following: List<JSONObject>? = null

    /**
     * Whether the followed series have an episode out (NewEpisodes), at launch and with every
     * refresh: the notify list fetched again and looked at with the row, which then says so, and
     * the launcher's row with it. A failed fetch looks again next time.
     */
    // ponytail: the notify list's first page, its latest additions; page on if someone follows more series than that.
    private suspend fun lookForEpisodes() {
        val list = runCatching { Api.userList("notify", 1, "add_list") }.getOrNull() ?: return
        val context = context ?: return
        val posts = list.optJSONArray("posts").objects()
        following = posts
        NewEpisodes.look(context, shown() + posts)
        showContinueWatching(watching)
        mirror(context)
    }

    private fun shown() = watching.objects().filter { it.titleId !in dismissed }

    /** The row as it shows, kept for the next launch and mirrored on the launcher's Play Next row (WatchNext). */
    private fun mirror(context: Context) {
        val shown = shown()
        keep(HomeCache.WATCHING, JSONArray(shown))
        // The launcher's words in the app's language, which the activity's configuration carries and the application's does not.
        val app = context.applicationContext.createConfigurationContext(context.resources.configuration)
        // Api.scope, so leaving home mid-sync can't cut it short.
        Api.scope.launch { WatchNext.sync(app, shown, following) }
    }

    private suspend fun showContinueWatching(posts: JSONArray) {
        val context = context ?: return
        watching = posts
        // Here, not below: NewEpisodes is the main thread's.
        val news = posts.objects().filter { NewEpisodes.of(context, it) != null }.mapTo(HashSet()) { it.titleId }
        val cards = withContext(Dispatchers.Default) {
            posts.objects().filter { it.titleId !in dismissed }.map { t ->
                // data: {episode_title: "فصل 1 - قسمت 4", display_time: "00:33:00", progress, ...}. The
                // red bar already says how far in, so the line names only the episode: «فصل ۱، قسمت ۴».
                // A series with an episode out says that instead of the time left.
                val where = t.str("data", "episode_title")?.episodeLabel()
                val progress = t.str("data", "progress")?.toFloatOrNull()?.div(100)
                val caption = if (t.titleId in news) newEpisodeCaption(context) else t.shortTimeLeft(context)
                t.toCard(context, lead = where, progress = progress, left = t.timeLeft(context), caption = caption, onLongClick = { confirmDismiss(t) }) {
                    startActivity(PlayerActivity.intent(context, t.titleId, t.isFree, title = t))
                }
            }
        }
        setRow(CONTINUE, R.string.continue_watching, cards)
    }

    /** OK held on a continue card: take it off the row, or go to its page instead of Cancel (Back cancels). */
    private fun confirmDismiss(title: JSONObject) {
        val activity = requireActivity()
        activity.confirm(title.englishName, getString(R.string.continue_remove_question), getString(R.string.continue_remove), art = title.poster,
            secondary = getString(R.string.continue_info), onSecondary = { activity.openDetails(title) },
            actionIcon = R.drawable.settings_ic_clear, secondaryIcon = R.drawable.promo_info) {
            // Off the row at once: the server takes a while to answer, and its list longer to drop the title.
            dismissed += title.titleId
            lifecycleScope.launch {
                showContinueWatching(watching)
                mirror(activity)
                runCatching { Api.dismissContinueWatching(title.titleId) }.onFailure {
                    dismissed -= title.titleId // the server kept it: it comes back, and says why
                    activity.toast(it)
                }
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
        /** How long the remote rests on a card before its neighbours' covers are fetched: Billboard's own wait for its art. */
        private const val REST = 350L
        /** How often home from the last run asks for a server again while none answers: Api's own wait between host searches. */
        private const val RETRY = 30_000L
        /** Home's answers for the next launch: a folder in filesDir (HomeCache), written one at a time so the last fetched lands last. */
        private const val KEPT = "home"
        private val keeping = Dispatchers.IO.limitedParallelism(1)

        /** Cards are rebuilt on every load; one showing the same title the same way is the same card. */
        private val SAME_CARD = object : DiffCallback<Card>() {
            override fun areItemsTheSame(old: Card, new: Card) = old.title == new.title && old.image == new.image && old.icon == new.icon
            @SuppressLint("DiffUtilEquals") // Kotlin lists compare the mosaic URLs by value and order.
            override fun areContentsTheSame(old: Card, new: Card) =
                old.subtitle.toString() == new.subtitle.toString() && old.facts.toString() == new.facts.toString() &&
                    old.progress == new.progress && old.free == new.free && old.plot == new.plot && old.backdrop == new.backdrop &&
                    old.mosaic == new.mosaic
        }

        /**
         * Titles taken off Continue Watching on this TV that the server may still send for a while;
         * each is forgotten once its list stops sending it. Main thread only.
         */
        private val dismissed = HashSet<Int>()

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
    // The home kept for the next launch (HomeFragment.KEPT), the watched episodes, the followed
    // series' news and each title's speed and subtitle timing are this account's: they go with it.
    context.applicationContext.filesDir.resolve("home").deleteRecursively()
    Watched.clear(context.applicationContext)
    NewEpisodes.clear(context.applicationContext)
    val player = context.getSharedPreferences("player", android.content.Context.MODE_PRIVATE)
    player.edit().apply { player.all.keys.filter { it.startsWith("speed.") || it.startsWith("timing.") }.forEach(::remove) }.apply()
}
