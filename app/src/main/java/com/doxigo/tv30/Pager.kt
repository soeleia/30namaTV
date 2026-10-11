package com.doxigo.tv30

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.PresenterSelector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Paging: list endpoints answer {posts, page, pages}, 24 posts a page. The next page loads while
 * focus is within [AHEAD] items of the end, and a page that lands with focus still that close
 * chains the one after, so a held D-pad keeps finding rows instead of stalling at the last one.
 * With a [skeleton], placeholder posters stand in from [reset] until the first page lands.
 */
class Pager(
    private val scope: CoroutineScope,
    val adapter: ArrayObjectAdapter,
    private val toCard: (JSONObject) -> Card,
    private val onEmpty: () -> Unit = {},
    /** A page that failed; the next focus move retries it. */
    private val onError: (Throwable) -> Unit = {},
    private val skeleton: Skeleton? = null,
    /** The first cards landed: how many titles there are in all, when 30nama says or one page holds them all. */
    private val onFirst: (total: Int?) -> Unit = {},
    private val fetch: suspend (page: Int) -> JSONObject,
) {
    private var page = 0
    private var pages = 1
    private var loading = false
    private var generation = 0
    private var pending: Job? = null
    /** The focused item's index; -1 until the user reaches the list, so an unvisited one stops at a page. */
    private var focus = -1
    private val seen = HashSet<Int>()
    /** The adapter holds the skeleton, not cards. */
    private var skeletal = false
    /** Real cards in the adapter: none while the skeleton stands in. */
    private val count get() = if (skeletal) 0 else adapter.size()

    init {
        // The skeleton's bones draw with their own presenter, beside the cards'.
        if (skeleton != null) {
            val cards = adapter.presenterSelector
            adapter.presenterSelector = object : PresenterSelector() {
                override fun getPresenter(item: Any?) = if (item is Skeleton) item.presenter else cards.getPresenter(item)
                override fun getPresenters() = arrayOf(*cards.presenters.orEmpty(), skeleton.presenter)
            }
        }
    }

    fun reset() {
        cancel()
        page = 0; pages = 1; loading = false; focus = -1; seen.clear(); adapter.clear()
        skeleton?.let { adapter.addAll(0, List(it.count) { _ -> it }); skeletal = true }
        loadMore()
    }

    fun cancel() {
        generation++
        pending?.cancel()
        pending = null
        loading = false
    }

    fun onSelected(item: Any?) {
        focus = if (item !is Card) -1 else adapter.indexOf(item)
        loadIfNear()
    }

    // An empty list has nothing to focus, so it keeps going until a page brings something or pages run out.
    private fun loadIfNear() {
        if (count == 0 || focus >= 0 && focus >= count - AHEAD) loadMore()
    }

    // ponytail: one page in flight, so a held D-pad that outruns the runway waits a round trip per
    // page; fetch two at once (appending in order) if that still stalls on the TV.
    private fun loadMore() {
        if (loading || page >= pages) return
        loading = true
        val mine = generation
        pending = scope.launch {
            val result = runCatching { fetch(page + 1) }
            if (mine != generation) return@launch // reset (new sort, new query) while this page was in flight
            result.onSuccess { r ->
                page = r.optInt("page", page + 1)
                pages = r.optInt("pages", page)
                // Pages overlap when the list changes underneath; the official app dedupes too.
                val fresh = r.optJSONArray("posts").objects().filter { seen.add(it.titleId) }
                // Spans, fonts and unescaping for a page of cards: off the main thread, so a page
                // landing mid-scroll doesn't cost frames.
                val cards = withContext(Dispatchers.Default) { fresh.map(toCard) }
                if (mine != generation) return@launch
                if (cards.isNotEmpty()) {
                    val first = count == 0
                    unskeleton()
                    adapter.addAll(adapter.size(), cards)
                    if (first) onFirst(r.optInt("total").takeIf { it > 0 } ?: cards.size.takeIf { pages <= 1 })
                }
            }
            loading = false
            // A skeleton that never resolves would be a lie: it goes with an error or the last page.
            if (result.isFailure || page >= pages) unskeleton()
            result.onFailure { if (it is CancellationException) throw it else onError(it) }
            if (count == 0 && page >= pages) onEmpty() else if (result.isSuccess) loadIfNear()
        }
    }

    private fun unskeleton() {
        if (!skeletal) return
        skeletal = false
        adapter.clear()
    }

    private companion object {
        /** Six rows of the 6-column grid, a page and a half: runway for a page's round trip over a VPN. */
        const val AHEAD = 36
    }
}

/**
 * [count] stand-ins for posters [width] × [height] dp (the cards' own size, so nothing moves when
 * they arrive): the artwork's block and its two caption lines in the placeholder grey, breathing
 * slowly. Never focusable, so the remote waits for real cards.
 */
class Skeleton(val count: Int, width: Int, height: Int) {
    val presenter: Presenter = object : Presenter() {
        override fun onCreateViewHolder(parent: ViewGroup) = ViewHolder(Bones(parent.context, width, height))
        override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) = Unit
        override fun onUnbindViewHolder(viewHolder: ViewHolder) = Unit
    }
}

/**
 * One placeholder poster. The pulse runs on an inner layer, since the grid's item animator fades
 * the outer one when the cards replace it; every pulse keeps the clock's phase, so a row of them
 * breathes as one rather than rippling. Still under reduced motion.
 */
private class Bones(context: Context, width: Int, height: Int) : FrameLayout(context) {
    private val body = LinearLayout(context)
    private val pulse = ObjectAnimator.ofFloat(body, View.ALPHA, 1f, 0.5f).apply {
        duration = PULSE
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        interpolator = AccelerateDecelerateInterpolator()
    }

    init {
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        val dp = resources.displayMetrics.density
        val w = (width * dp).toInt()
        body.orientation = LinearLayout.VERTICAL
        body.gravity = Gravity.START
        // A card's caption takes 48dp under its art (title, then the facts): two lines' worth here.
        body.setPadding(0, 0, 0, (10 * dp).toInt())
        body.addView(block(resources.getDimension(R.dimen.corner)), LinearLayout.LayoutParams(w, (height * dp).toInt()))
        body.addView(block(2 * dp), LinearLayout.LayoutParams(w * 7 / 10, (10 * dp).toInt()).apply { topMargin = (12 * dp).toInt() })
        body.addView(block(2 * dp), LinearLayout.LayoutParams(w * 9 / 20, (8 * dp).toInt()).apply { topMargin = (8 * dp).toInt() })
        addView(body)
    }

    private fun block(radius: Float) = View(context).apply {
        background = GradientDrawable().apply {
            setColor(ContextCompat.getColor(context, R.color.surface_high)) // the cards' own placeholder
            cornerRadius = radius
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Motion.reduced) return
        pulse.start()
        pulse.currentPlayTime = SystemClock.uptimeMillis() % (2 * PULSE)
    }

    override fun onDetachedFromWindow() {
        pulse.cancel()
        body.alpha = 1f
        super.onDetachedFromWindow()
    }

    private companion object {
        /** Each way: a slow breath, not a shimmer. */
        const val PULSE = 900L
    }
}
