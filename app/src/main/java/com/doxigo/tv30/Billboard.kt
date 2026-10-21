package com.doxigo.tv30

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextPaint
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.widget.TextViewCompat
import coil3.BitmapImage
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.size.Scale
import coil3.transition.CrossfadeDrawable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * The billboard behind home and details: the focused title's art, darkened toward the text side
 * and the foot, under its name, meta line and synopsis. A new title's words swap as the Tizen
 * client's do: the old ones fade out, then the new ones rise into place one after another.
 * With a TMDB key the English name gives way to the title's logo, once TMDB has one (Logos).
 *
 * [entry]: where the title page's TMDB entry comes from, which it fetches anyway; its logo is read
 * from it rather than asked for again.
 */
class Billboard(
    private val root: View,
    private val scope: CoroutineScope,
    private val entry: (suspend (JSONObject) -> JSONObject?)? = null,
) {
    private val backdrop: ImageView = root.findViewById(R.id.backdrop)
    /** Home's only (billboard.xml): what a page turn of the promoted hero carries off, see [turn]. */
    private val leaving: ImageView? = root.findViewById(R.id.backdrop_leaving)
    private val text: View = root.findViewById(R.id.billboard)
    private val title: TextView = root.findViewById(R.id.bb_title)
    private val local: TextView = root.findViewById(R.id.bb_local)
    private val meta: TextView = root.findViewById(R.id.bb_meta)
    private val plot: TextView = root.findViewById(R.id.bb_plot)
    /**
     * Only the words move, never the block around them: the promoted hero slides the block and a
     * tile quiets it, and those must not fight the swap. The Persian name rises with the English.
     */
    private val words = listOf(title, local, meta, plot)
    private val drop = 6 * root.resources.displayMetrics.density
    /** The name's box as the layout gives it, one line high (see [fit]). */
    private val oneLine = title.layoutParams.height
    private val twoLines = (60 * root.resources.displayMetrics.density).toInt()
    private val logoRise = root.resources.getDimensionPixelSize(R.dimen.billboard_logo_rise)
    private val logoWidth = root.resources.getDimensionPixelSize(R.dimen.billboard_logo_width)
    /** The art last asked for, and how strongly it shows: whole under a title, dim under a tile's own picture, not at all under a tile without one. */
    private var art: String? = null
    private var strength = 1f
    private var pending: Job? = null
    /** The fetch under way for [art], dropped the moment other art is asked for. */
    private var loading: Disposable? = null
    /** TMDB's answer for the title on screen, when 30nama has no Persian name (see [askName]). */
    private var naming: Job? = null
    /** The title whose words are written, and the card waiting for them to finish fading out. */
    private var shown: String? = null
    private var next: Card? = null
    /** The name's own white: its words fade under a logo by their colour, as the swap moves the view itself. */
    private val ink = title.currentTextColor
    /** The logo the name wears or is fetching ([wear]), that fetch, and TMDB's answer for the title on screen ([askLogo]). */
    private var logo: String? = null
    private var logoLoad: Disposable? = null
    private var logoAsk: Job? = null
    /** How much of its words and of its logo the name shows, 0..1, and the fade between them ([mix]). */
    private var nameWords = 1f
    private var nameLogo = 0f
    private var mixing: ValueAnimator? = null
    /** A known logo slow to come: the words stand in until it lands. */
    private val logoLate = Runnable { mix(1f, 0f) }
    private val swap = Runnable {
        next?.let {
            next = null
            write(it)
            enter(drop)
        }
    }

    init {
        plot.ellipsizeAtWords()
        // A logo stands taller than the words, rising out of the name's box (LogoArt).
        (text as ViewGroup).clipChildren = false
        val c = root.context
        val solid = c.getColor(R.color.scrim)
        val clear = c.getColor(R.color.scrim_clear)
        val rtl = c.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val side = if (rtl) GradientDrawable.Orientation.RIGHT_LEFT else GradientDrawable.Orientation.LEFT_RIGHT
        root.findViewById<View>(R.id.scrim_side).background =
            GradientDrawable(side, intArrayOf(solid, c.getColor(R.color.scrim_mid), clear, clear))
        root.findViewById<View>(R.id.scrim_bottom).background =
            GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(solid, solid, clear, clear))
    }

    /**
     * [turn]: the promoted hero turning a page, px toward where the next art comes in from (0 under
     * reduced motion). Its art turns with the words, without the rest focus waits for (see [stage]).
     */
    fun show(card: Card, turn: Float? = null) {
        // Only titles speak here. A tile (a list, a genre, "see all") quiets the words rather than
        // posing as a title, and the last title's art steps back with them: a category or genre
        // shows its own picture, dimmed, and a list or "see all" leaves bare ink.
        if (card.backdrop == null && card.plot == null) {
            text.animate().alpha(0f).setInterpolator(Motion.easeOut).setDuration(Motion.EXIT).start()
            stage(card.image, if (card.image == null) 0f else TILE_ART)
            return
        }
        // After a tile quieted the words (or the hero faded them for its own swap) they come back
        // up from wherever they are.
        val hidden = text.alpha == 0f
        if (text.alpha < 1f) text.animate().alpha(1f).setInterpolator(Motion.easeOut).setDuration(Motion.ENTER).start()
        swapWords(card, hidden)
        askName(card)
        askLogo(card)
        // A title without art leaves bare ink, never the last title's.
        stage(card.backdrop, if (card.backdrop == null) 0f else 1f, turn)
    }

    /**
     * Puts [url] behind at [level] (null keeps the art and only sets its level). New art follows
     * focus only once it rests (no decoding a 1280 px image per key press); until then the art on
     * screen stays, and then crossfades into it. A fetch still under way for other art is dropped
     * at once: on a slow connection it used to land under the new title's words. New art not
     * already in memory (home fetches the covers beside the focused card) has the old fade out
     * while it downloads, so it never stays under the new words; it rises from ink when it lands.
     *
     * A page turn of the promoted hero ([turn]) starts at once, with its words: the hero lands a
     * turn only once paging rests (Promo.refresh), and its neighbours' art is in memory. It
     * crossfades too, the art moving with the words: see [turn]. The art is wider than the screen
     * by that much (PromoHero), so the drift never bares an edge.
     */
    private fun stage(url: String?, level: Float, turn: Float? = null) {
        strength = level
        if (url == null || url == art) return light()
        art = url
        loading?.dispose()
        pending?.cancel()
        val from = turn ?: 0f
        pending = scope.launch {
            if (turn == null) delay(REST)
            var landed = false
            // Coil's own ImageView target would blank the art as the fetch starts and skip the crossfade for a memory hit.
            val request = ImageRequest.Builder(root.context).data(url).size(1280, 720).scale(Scale.FILL).target(onSuccess = { image ->
                landed = true
                val new = image.asDrawable(root.resources)
                val old = backdrop.drawable?.takeIf { backdrop.alpha > 0.05f }
                when {
                    old == null -> {
                        backdrop.setImageDrawable(new)
                        backdrop.translationX = from // out of ink a page's art still settles in from its side
                    }
                    from != 0f && leaving != null -> turn(leaving, old, new, from)
                    else -> {
                        backdrop.setImageDrawable(CrossfadeDrawable(old, new, Scale.FILL, Motion.BACKDROP))
                        (backdrop.drawable as CrossfadeDrawable).start()
                    }
                }
                light()
            }).build()
            // A memory hit lands inside enqueue; anything slower fades the old art out meanwhile. A
            // page turn first gives it the rest it skipped: one from disk turns as cleanly, and
            // pages pressed through quickly don't each dip on their way past.
            loading = root.context.imageLoader.enqueue(request)
            if (turn != null && !landed) delay(REST)
            if (!landed) dim()
        }
    }

    /** Fades the art on screen out while new art downloads. */
    private fun dim() {
        backdrop.animate().alpha(0f).setStartDelay(0).setInterpolator(Motion.easeOut)
            .setDuration(if (Motion.reduced) Motion.FADE else REST).start()
    }

    /**
     * A page turn's crossfade: the old art moves to [leaving], over the backdrop, and drifts away
     * from [from] as it fades, while the new art settles in from [from] beneath it, the two
     * travelling together. Nothing dips: the new art is whole under the old as it goes. A turn
     * landing while the last one still fades takes its place, so at most two pictures draw.
     */
    private fun turn(leaving: ImageView, old: Drawable, new: Drawable, from: Float) {
        leaving.animate().cancel()
        // The backdrop lets go of the old art first: handing it on afterwards would unhook it from its new view.
        backdrop.setImageDrawable(new)
        leaving.setImageDrawable(old)
        leaving.alpha = backdrop.alpha
        leaving.translationX = backdrop.translationX
        backdrop.translationX = from
        leaving.animate().alpha(0f).translationX(leaving.translationX - from).setStartDelay(0).setInterpolator(Motion.easeOut)
            .setDuration(Motion.BACKDROP.toLong()).withEndAction { leaving.setImageDrawable(null) }.start()
    }

    /** Takes the art to [strength] and home from wherever it is, overriding a fade still under way. */
    private fun light() {
        backdrop.animate().alpha(strength).translationX(0f).setStartDelay(0).setInterpolator(Motion.easeOut)
            .setDuration(Motion.BACKDROP.toLong()).start()
    }

    /**
     * Another title fades the words out, then writes and raises the new ones. A show() mid-way
     * retargets from where things are: while they fade, the latest card is the one written;
     * while they rise, they fade out again from their current alpha. The first title, the same
     * one again (details filling in its facts) and a held D-pad are written at once.
     */
    private fun swapWords(card: Card, hidden: Boolean) {
        when {
            shown == null || card.title == shown && next == null -> write(card)
            Motion.fast -> {
                cancelSwap()
                write(card)
                words.forEach {
                    it.animate().cancel()
                    it.alpha = 1f
                    it.translationY = 0f
                }
            }
            // Back to the title still fading out: it comes back up from there.
            card.title == shown -> {
                cancelSwap()
                write(card)
                enter(null)
            }
            // Nothing on screen to fade out.
            hidden -> {
                cancelSwap()
                write(card)
                enter(drop)
            }
            next != null -> next = card
            else -> {
                next = card
                words.forEach {
                    it.animate().alpha(0f).setStartDelay(0).setInterpolator(Motion.easeOut).setDuration(OUT).withLayer().start()
                }
                text.postDelayed(swap, OUT)
            }
        }
    }

    private fun cancelSwap() {
        next = null
        text.removeCallbacks(swap)
    }

    /** The words fade up from [from] px below, [STEP] ms apart; with no [from], from wherever they are. */
    private fun enter(from: Float?) {
        words.forEachIndexed { i, view ->
            from?.let {
                view.alpha = 0f
                view.translationY = Motion.travel(it)
            }
            view.animate().alpha(1f).translationY(0f).setStartDelay(if (Motion.reduced) 0L else STEP * maxOf(0, i - 1))
                .setInterpolator(Motion.emph).setDuration(if (Motion.reduced) Motion.FADE else IN).withLayer().start()
        }
    }

    /**
     * The Persian name 30nama lacks, from TMDB (PersianNames): write() keeps its line, and once the
     * name comes it rises into it as the words do, if its title is still the one written. Mid-swap,
     * the write that follows finds it in the cache instead.
     */
    private fun askName(card: Card) {
        naming?.cancel()
        val source = card.source?.takeIf(PersianNames::pending) ?: return
        naming = scope.launch {
            val found = PersianNames.lookup(root.context, source) ?: return@launch
            if (card.title != shown || next != null || local.visibility == View.VISIBLE) return@launch
            local.animate().cancel()
            local.text = found
            local.alpha = 0f
            local.translationY = Motion.travel(drop)
            local.visibility = View.VISIBLE
            // The title page steps the names aside under its risen rows: there it waits, in place, to come back with them.
            if (title.alpha == 0f) local.translationY = 0f
            else local.animate().alpha(1f).translationY(0f).setStartDelay(0).setInterpolator(Motion.emph)
                .setDuration(if (Motion.reduced) Motion.FADE else IN).withLayer().start()
        }
    }

    /**
     * TMDB's logo for the title on screen, when it isn't known yet: once found it fades in over
     * the words, if its title is still the one written. Mid-swap, the write that follows finds it
     * in the cache instead.
     */
    private fun askLogo(card: Card) {
        logoAsk?.cancel()
        val source = card.source?.takeIf(Logos::pending) ?: return
        logoAsk = scope.launch {
            val url = Logos.lookup(root.context, source, entry) ?: return@launch
            if (card.title == shown && next == null) wear(url, source, fresh = false)
        }
    }

    /**
     * Puts the logo at [url] on the name in place of its words, or (null) takes one off. A
     * [fresh] title's logo already in memory is there at once, rising with the words; one still
     * coming leaves the name bare for [LOGO_WAIT], then shows the words, and fades in over them
     * when it lands. A logo that fails, or would not read over the ink, leaves the words.
     */
    private fun wear(url: String?, source: JSONObject?, fresh: Boolean) {
        if (url == logo) return
        logo = url
        logoLoad?.dispose()
        title.removeCallbacks(logoLate)
        title.foreground = null
        if (url == null) return mix(1f, 0f, fade = false)
        val c = root.context
        // A memory hit lands inside enqueue.
        var enqueuing = true
        var landed = false
        val words = { _: Any? ->
            landed = true
            title.removeCallbacks(logoLate)
            mix(1f, 0f, fade = nameWords < 1f)
        }
        logoLoad = c.imageLoader.enqueue(Logos.request(c, url).target(onError = words, onSuccess = { image ->
            val art = (image as? BitmapImage)?.bitmap
            if (art == null || !Logos.legible(art)) {
                source?.let { scope.launch { Logos.refuse(c, it) } }
                return@target words(null)
            }
            landed = true
            title.removeCallbacks(logoLate)
            title.foreground = LogoArt(image.asDrawable(c.resources), logoRise, logoWidth)
            mix(0f, 1f, fade = !(fresh && enqueuing))
        }).build())
        enqueuing = false
        if (fresh && !landed) {
            mix(0f, 0f, fade = false)
            title.postDelayed(logoLate, LOGO_WAIT)
        }
    }

    /** Takes the name to [words] of its words and [logo] of its logo, fading from wherever they are, or at once. */
    private fun mix(words: Float, logo: Float, fade: Boolean = true) {
        mixing?.cancel()
        if (!fade) return paint(words, logo)
        val (fromWords, fromLogo) = nameWords to nameLogo
        mixing = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = Motion.IMAGE.toLong()
            interpolator = Motion.easeOut
            addUpdateListener {
                val f = it.animatedValue as Float
                paint(fromWords + (words - fromWords) * f, fromLogo + (logo - fromLogo) * f)
            }
            start()
        }
    }

    private fun paint(words: Float, logo: Float) {
        nameWords = words
        nameLogo = logo
        title.setTextColor(ColorUtils.setAlphaComponent(ink, (Color.alpha(ink) * words).roundToInt()))
        title.foreground?.alpha = (255 * logo).roundToInt()
    }

    private fun write(card: Card) {
        shown = card.title
        val c = root.context
        // An English name in Inter, drawn a touch tight as display type is; one 30nama only
        // has in Persian keeps Peyda, untracked.
        val latinName = card.title.isLatin
        title.typeface = c.font(if (latinName) R.font.inter_bold else R.font.peyda_bold)
        title.letterSpacing = if (latinName) -0.01f else 0f
        fit(card.title, latinName)
        title.text = card.title
        // TMDB's logo in place of the words once known; the words stay under it, unseen, for TalkBack and to fall back to.
        wear(card.source?.let(Logos::cached), card.source, fresh = true)
        // 30nama's Persian name, else TMDB's once known. While TMDB may still have one its line is
        // kept, so nothing below moves when the name rises in (see askName).
        val persian = card.source?.let(PersianNames::cached) ?: card.localTitle
        local.text = persian
        local.visibility = when {
            !persian.isNullOrBlank() -> View.VISIBLE
            card.source?.let(PersianNames::pending) == true -> View.INVISIBLE
            else -> View.GONE
        }
        meta.text = card.subtitle
        // An English synopsis (the English UI, or a title with no Persian one) is set whole in
        // Inter at Latin leading; a Persian one keeps Yekan Bakh, its Latin names in Inter.
        // Yekan Bakh's own line is 1.50 em (setLineHeight also counts its 0.05 em gap), so 1.65 lands lines 1.60 em apart: a hair more to breathe.
        val latinPlot = card.plot?.isLatin == true
        plot.typeface = c.font(if (latinPlot) R.font.inter_regular else R.font.vazirmatn_regular)
        TextViewCompat.setLineHeight(plot, (plot.textSize * if (latinPlot) 1.45f else 1.65f).roundToInt())
        plot.text = card.plot?.let { latin(c, it, R.font.inter_regular) }
        plot.visibility = if (card.plot.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    /**
     * A name that fits one line at 24 sp keeps the one-line box and shrinks from 38 sp to fit it.
     * A longer one ("The Angel Next Door Spoils Me Rotten (Otonari…)") takes two lines, up to
     * 28 sp, set tight as display type is (Latin only: Persian's marks need their leading), in a
     * box only 10 dp taller, so the words below barely move.
     */
    private fun fit(name: String, latinName: Boolean) {
        val room = text.layoutParams.width.takeIf { it > 0 } ?: text.width
        val probe = TextPaint(title.paint).apply { textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 24f, title.resources.displayMetrics) }
        // Before API 26 nothing shrinks the name to its box, so it stays on one line there.
        val one = Build.VERSION.SDK_INT < 26 || room <= 0 || probe.measureText(name) <= room
        title.maxLines = if (one) 1 else 2
        title.setLineSpacing(0f, if (one || !latinName) 1f else 0.86f)
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(title, if (one) 24 else 20, if (one) 38 else 28, 1, TypedValue.COMPLEX_UNIT_SP)
        val box = if (one) oneLine else twoLines
        if (title.layoutParams.height != box) title.layoutParams = title.layoutParams.apply { height = box }
    }

    /**
     * A logo as the name's foreground: fit to the name's box and [rise] px above it, at most
     * [widest] px wide, standing on the box's foot at its start edge, as the words do.
     */
    private class LogoArt(private val art: Drawable, private val rise: Int, private val widest: Int) : Drawable() {
        override fun draw(canvas: Canvas) {
            val box = bounds
            val w = art.intrinsicWidth.toFloat()
            val h = art.intrinsicHeight.toFloat()
            if (w <= 0f || h <= 0f) return
            val scale = minOf(minOf(box.width(), widest) / w, (box.height() + rise) / h)
            val width = (w * scale).roundToInt()
            val left = if (layoutDirection == View.LAYOUT_DIRECTION_RTL) box.right - width else box.left
            art.setBounds(left, box.bottom - (h * scale).roundToInt(), left + width, box.bottom)
            art.draw(canvas)
        }

        override fun setAlpha(alpha: Int) {
            art.alpha = alpha
            invalidateSelf()
        }

        override fun getAlpha() = art.alpha
        override fun setColorFilter(filter: ColorFilter?) { art.colorFilter = filter }
        @Deprecated("Unused since API 29", ReplaceWith("PixelFormat.TRANSLUCENT"))
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private companion object {
        /** How strongly a category's or genre's own picture shows behind its row: ambient, never a title's art. */
        const val TILE_ART = 0.3f
        /** How long focus rests before new art is fetched, and how long the old art takes to go. */
        const val REST = 350L
        /** The Tizen client's text swap: out in 120 ms, then each line in over 260 ms, 40 ms after the one above. */
        const val OUT = 120L
        const val IN = 260L
        const val STEP = 40L
        /** How long a known logo may take before the words stand in for it: a disk hit is well within it. */
        const val LOGO_WAIT = 600L
    }
}
