package com.doxigo.tv30

import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextPaint
import android.util.TypedValue
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import coil3.load
import coil3.request.crossfade
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The billboard behind home and details: the focused title's art, darkened toward the text side
 * and the foot, under its name, meta line and synopsis. A new title's words swap as the Tizen
 * client's do: the old ones fade out, then the new ones rise into place one after another.
 */
class Billboard(private val root: View, private val scope: CoroutineScope) {
    private val backdrop: ImageView = root.findViewById(R.id.backdrop)
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
    /** The art last asked for, and how strongly it shows: whole under a title, dim under a tile's own picture, not at all under a tile without one. */
    private var art: String? = null
    private var strength = 1f
    private var pending: Job? = null
    /** TMDB's answer for the title on screen, when 30nama has no Persian name (see [askName]). */
    private var naming: Job? = null
    /** The title whose words are written, and the card waiting for them to finish fading out. */
    private var shown: String? = null
    private var next: Card? = null
    private val swap = Runnable {
        next?.let {
            next = null
            write(it)
            enter(drop)
        }
    }

    init {
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

    fun show(card: Card) {
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
        card.backdrop?.let { stage(it, 1f) }
    }

    /**
     * Puts [url] behind at [level] (null keeps the art and only sets its level). Dimming starts
     * at once; brightening for new art waits until it has arrived, so the dim picture it replaces
     * never flashes up whole. The art follows focus only once it rests: no decoding a 1280 px
     * image per key press.
     */
    private fun stage(url: String?, level: Float) {
        strength = level
        if (url == null || url == art) return light()
        art = url
        if (backdrop.alpha > level) light()
        pending?.cancel()
        pending = scope.launch {
            delay(350)
            // Out of bare ink there is nothing to crossfade from: the art rises in with the level.
            val fade = if (backdrop.alpha < 0.05f) 0 else Motion.BACKDROP
            backdrop.load(url) { crossfade(fade); size(1280, 720); listener(onSuccess = { _, _ -> light() }) }
        }
    }

    /** Takes the art to [strength] from wherever it is, overriding a fade still under way. */
    private fun light() {
        backdrop.animate().alpha(strength).setStartDelay(0).setInterpolator(Motion.easeOut).setDuration(Motion.BACKDROP.toLong()).start()
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

    private companion object {
        /** How strongly a category's or genre's own picture shows behind its row: ambient, never a title's art. */
        const val TILE_ART = 0.3f
        /** The Tizen client's text swap: out in 120 ms, then each line in over 260 ms, 40 ms after the one above. */
        const val OUT = 120L
        const val IN = 260L
        const val STEP = 40L
    }
}
