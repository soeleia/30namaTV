package com.doxigo.tv30

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.size.Scale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 30nama's own promoted titles (home_stream's hero_header) at the top of home, as the official
 * app opens: one at a time on the billboard, Play and More under it, page dots below. It pages
 * on by itself every [ADVANCE] ms while the remote rests on its buttons, and on by hand past More
 * toward the end edge, wrapping round; the dots show the order, so there is no paging back. Past
 * Play toward the start edge opens the rail, as from a row's first card, and the paging waits
 * while it is open. Down hands the remote to the rows, which rise to their usual place while the
 * billboard goes back to following focus; [focus] (Up from the first row, Home in the rail)
 * brings the hero back. Back opens the rail, as anywhere on home.
 *
 * It borrows the billboard instead of drawing its own: names, meta line, synopsis and art are
 * Billboard.show()'s. While the hero is up the synopsis runs to three lines, and the words end
 * just above the buttons, which never move between titles.
 *
 * [root] is home's billboard layout (layout/billboard.xml); [rows] the browse frame over it,
 * which the hero lowers while it is up and hands the remote to on Down.
 */
class PromoHero(
    private val root: ViewGroup,
    private val scope: CoroutineScope,
    private val billboard: Billboard,
    private val rows: View,
) {
    /** Play and More. By default what the title page's Play does, and the title page. */
    var onWatch: (JSONObject) -> Unit = { root.context.play(it) }
    var onDetails: (JSONObject) -> Unit = { root.context.openDetails(it) }

    /** Titles in Continue Watching: their Play reads «ادامهٔ تماشا». */
    var resumable: Set<Int> = emptySet()
        set(value) {
            field = value
            label()
        }

    /** While true the hero holds the billboard and the rows sit low, their selections kept for later. */
    var active = false
        private set

    private val context = root.context
    private val rtl = context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
    private val text: ViewGroup = root.findViewById(R.id.billboard)
    private val plot: TextView = root.findViewById(R.id.bb_plot)
    private val plotLines = plot.maxLines
    private val controls = LayoutInflater.from(context).inflate(R.layout.promo_hero, root, false)
    private val watch: Button = controls.findViewById(R.id.promo_watch)
    private val more: Button = controls.findViewById(R.id.promo_more)
    private val dots: View = controls.findViewById(R.id.promo_dots)
    private val track: LinearLayout = controls.findViewById(R.id.promo_track)
    private val dot: View = controls.findViewById(R.id.promo_dot)
    private val drop = context.resources.getDimension(R.dimen.promo_rows_drop)
    private val wordsBottom = context.resources.getDimensionPixelSize(R.dimen.promo_text_bottom)
    private val slot = context.resources.getDimension(R.dimen.promo_dot_slot) * if (rtl) -1 else 1
    // Toward the end edge pages on, whichever side that is; toward the start is left to the rail.
    private val toEnd = if (rtl) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT

    private var titles = emptyList<JSONObject>()
    private var cards = emptyList<Card>()
    private var index = 0
    /** The card the rows last selected, shown once the hero hands the billboard back. */
    private var focused: Card? = null
    /** The words are fading out for a swap: nothing else writes them until they are back. */
    private var swapping = false
    private var timer: Job? = null
    private var preloaded: String? = null
    /** Home has made its entrance ([arrive]): promos landing only after it wait above the rows for Up. */
    private var arrived = false

    /**
     * Down to the rows, nothing above, and past More to the next promo. Past Play toward the start
     * edge goes unanswered here, so focus search finds nothing that way and the rail opens (Rail.kt).
     */
    private val keys = View.OnKeyListener { view, code, event ->
        if (event.action != KeyEvent.ACTION_DOWN) return@OnKeyListener false
        idle()
        when (code) {
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                rows.requestFocus()
                true
            }
            KeyEvent.KEYCODE_DPAD_UP -> true
            toEnd -> view === more && step(1)
            else -> false
        }
    }

    /**
     * Where the remote ends up decides who holds the billboard, read a frame later so that a
     * hand-off undone at once never shows: Leanback gives the rows the remote on every onStart,
     * and home hands it straight back to Play (see HomeFragment.onStart).
     */
    private val onFocus = ViewTreeObserver.OnGlobalFocusChangeListener { _, _ ->
        root.removeCallbacks(react)
        root.post(react)
    }

    private val react = Runnable {
        when {
            controls.hasFocus() -> {
                if (!active) hold(true)
                idle()
            }
            rows.hasFocus() -> if (active) hold(false)
            else -> timer?.cancel() // the rail opened over the hero, which waits as it is
        }
    }

    /** Another page or a dialog in front: the hero waits, and starts its count again on return. */
    private val onWindowFocus = ViewTreeObserver.OnWindowFocusChangeListener { has -> if (has) idle() else timer?.cancel() }

    init {
        controls.visibility = View.GONE
        root.addView(controls, root.indexOfChild(text) + 1) // under the rows, which never cover it
        root.clipChildren = false // Play's focus lift reaches past the controls' start edge
        // While the hero is up the words end just above the buttons, however many lines they take.
        text.addOnLayoutChangeListener { view, _, _, _, bottom, _, _, _, _ ->
            view.translationY = if (active) maxOf(0, root.height - wordsBottom - bottom).toFloat() else 0f
        }
        watch.setOnClickListener { titles.getOrNull(index)?.let(onWatch) }
        more.setOnClickListener { titles.getOrNull(index)?.let(onDetails) }
        watch.setOnKeyListener(keys)
        more.setOnKeyListener(keys)
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = listen(true)
            override fun onViewDetachedFromWindow(view: View) = listen(false)
        })
        if (root.isAttachedToWindow) listen(true)
    }

    /**
     * Takes the promoted titles and puts the first up at once, with no fade: the rows are still
     * on their way in. Once the hero is up, fresh ones (home's refresh) go to [update] instead.
     * The first ones landing after home arrived (its first fetch failed) don't pull the rows down
     * under the remote: they wait for Up from the first row, which brings the hero up whole.
     */
    fun show(posts: List<JSONObject>) {
        if (posts.isEmpty()) return
        if (cards.isNotEmpty()) return update(posts)
        titles = posts
        cards = posts.map { it.toCard(context) {} }
        index = 0
        preloaded = null
        lay(0)
        if (arrived) return
        active = true
        controls.animate().cancel()
        controls.alpha = 0f // until [arrive]: the buttons come in with the rows
        controls.visibility = View.VISIBLE
        rows.animate().cancel()
        rows.translationY = drop
        arrange()
        billboard.show(cards[0])
        preload()
        idle()
    }

    /**
     * The promotions fetched again, swapped in without a move: the title on show stays if it is
     * still promoted (else the first takes its place), and who holds the billboard, the focus and
     * the rows stay as they are.
     */
    private fun update(posts: List<JSONObject>) {
        val showing = titles.getOrNull(index)?.titleId
        titles = posts
        cards = posts.map { it.toCard(context) {} }
        index = posts.indexOfFirst { it.titleId == showing }.coerceAtLeast(0)
        preloaded = null
        lay(index)
        // The same title is rewritten at once; a new one swaps as paging does. Mid-swap, [refresh] shows it.
        if (active && !swapping) {
            billboard.show(cards[index])
            preload()
        }
        label()
        idle()
    }

    /** One dot per promo, the pill on [at]; the row keeps its room with one promo, so the buttons never move. */
    private fun lay(at: Int) {
        val size = context.resources.getDimensionPixelSize(R.dimen.promo_dot)
        val gap = (context.resources.getDimensionPixelSize(R.dimen.promo_dot_slot) - size) / 2
        track.removeAllViews()
        repeat(titles.size) {
            val params = LinearLayout.LayoutParams(size, size).apply { marginStart = gap; marginEnd = gap }
            track.addView(View(context).apply { setBackgroundResource(R.drawable.promo_dot) }, params)
        }
        dot.animate().cancel()
        dot.translationX = at * slot
        dots.visibility = if (titles.size > 1) View.VISIBLE else View.INVISIBLE
    }

    /** Home's first appearance: the buttons and dots rise in with the rows' dock, [delay] ms from now. */
    fun arrive(delay: Long) {
        arrived = true
        if (active) rise(listOf(controls), delay = delay)
    }

    /**
     * Puts the remote on Play, bringing the hero back if the rows had it. False when there is
     * nothing to promote. It takes the billboard at once, so a re-selection right after (the
     * rail's Home) only reaches [follow].
     */
    fun focus(): Boolean {
        if (cards.isEmpty()) return false
        if (!active) hold(true)
        return watch.requestFocus()
    }

    /** Every card the rows select comes here: onto the billboard, or kept until the hero hands it back. */
    fun follow(card: Card) {
        focused = card
        if (!active && !swapping) billboard.show(card)
    }

    private fun listen(on: Boolean) {
        val observer = root.viewTreeObserver
        if (on) {
            observer.addOnGlobalFocusChangeListener(onFocus)
            observer.addOnWindowFocusChangeListener(onWindowFocus)
        } else {
            observer.removeOnGlobalFocusChangeListener(onFocus)
            observer.removeOnWindowFocusChangeListener(onWindowFocus)
            root.removeCallbacks(react)
            timer?.cancel()
        }
    }

    /** The hero takes the billboard back ([up]) or hands it to the rows: the rows slide, the buttons fade, the words swap. */
    private fun hold(up: Boolean) {
        active = up
        if (!up) timer?.cancel()
        controls.reveal(up)
        rows.animate().translationY(if (up) drop else 0f).setStartDelay(0).setInterpolator(Motion.drawer)
            .setDuration(Motion.MOVE).start()
        refresh()
    }

    /** Pages [by] promos, wrapping round. The pill glides at once; the words follow once paging stops. */
    private fun step(by: Int): Boolean {
        if (cards.size < 2) return false
        index = (index + by).mod(cards.size)
        dot.animate().translationX(index * slot).setStartDelay(0).setInterpolator(Motion.easeOut).setDuration(Motion.MOVE).start()
        refresh()
        idle()
        return true
    }

    /**
     * The words fade out, are set for whoever holds the billboard now, and fade back in. Paging
     * again before they are out restarts the fade, so a held key swaps them once, at the end.
     */
    private fun refresh() {
        swapping = true
        text.animate().alpha(0f).setStartDelay(0).setInterpolator(Motion.easeOut).setDuration(Motion.EXIT).withEndAction {
            swapping = false
            arrange()
            val card = if (active) cards[index] else focused
            card?.let(billboard::show)
            // Back to the rows, Billboard brings a title's words up itself and keeps a tile's down.
            if (active || card == null) text.animate().alpha(1f).setInterpolator(Motion.easeOut).setDuration(Motion.ENTER).start()
            if (active) preload()
        }.start()
    }

    /** Sets the words out for whoever holds the billboard: the hero's third line, or Billboard's own. */
    private fun arrange() {
        plot.maxLines = if (active) 3 else plotLines
        text.requestLayout() // re-anchors the words (see init)
        label()
    }

    private fun label() {
        val title = titles.getOrNull(index) ?: return
        watch.setText(
            when {
                !Api.loggedIn && !title.isFree -> R.string.login_and_watch
                title.titleId in resumable -> R.string.resume
                else -> R.string.watch
            }
        )
    }

    /** Counts [ADVANCE] from the last key, only while the remote rests on the hero and home is in front. */
    private fun idle() {
        timer?.cancel()
        if (cards.size < 2 || !controls.hasFocus() || !root.hasWindowFocus()) return
        timer = scope.launch {
            delay(ADVANCE)
            step(1)
        }
    }

    /**
     * The next promo's art, fetched while this one shows. Same size and scale as Billboard's
     * request (1280x720, centre-crop), so the swap comes out of the memory cache.
     */
    private fun preload() {
        val url = cards.getOrNull((index + 1) % cards.size)?.backdrop ?: return
        if (url == preloaded) return
        preloaded = url
        // Its Persian name too, when TMDB has to supply it, so that page is written whole.
        titles.getOrNull((index + 1) % titles.size)?.takeIf(PersianNames::pending)?.let { scope.launch { PersianNames.lookup(context, it) } }
        context.imageLoader.enqueue(ImageRequest.Builder(context).data(url).size(1280, 720).scale(Scale.FILL).build())
    }

    private companion object {
        const val ADVANCE = 8_000L
    }
}
