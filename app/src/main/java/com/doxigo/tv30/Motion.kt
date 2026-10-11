package com.doxigo.tv30

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.os.Build
import android.view.View
import android.view.animation.PathInterpolator

// ---- Motion: one set of curves and durations for the whole app. ----

/**
 * A strong ease-out for anything entering or leaving (it moves at once, then settles), a longer
 * emphasized ease-out for big travel (the rail's panel, pages), a firm ease-in-out for things
 * crossing the screen, and the drawer curve for blocks that slide. Exits run quicker than
 * entrances: the app answers fast, then gets out of the way. Only alpha and transforms animate.
 */
object Motion {
    val easeOut: TimeInterpolator = PathInterpolator(0.23f, 1f, 0.32f, 1f)
    val emph: TimeInterpolator = PathInterpolator(0.16f, 1f, 0.3f, 1f)
    val easeInOut: TimeInterpolator = PathInterpolator(0.77f, 0f, 0.175f, 1f)
    val drawer: TimeInterpolator = PathInterpolator(0.32f, 0.72f, 0f, 1f)

    /**
     * Reduce motion: Settings' «کاهش انیمیشن», or [systemReduced]. Nothing travels or scales over
     * time (the durations below that only move things drop to 0, the helpers start from where things
     * end), every fade takes [FADE] so a change of state still reads, and the launch intro is
     * skipped. Read at each animation, so the switch holds from the next one on.
     */
    val reduced get() = Settings.reduceMotion || systemReduced
    /** The system's own "remove animations" (animator scale 0, or battery saver), which Settings can't overrule. */
    // ponytail: API 26+ only (areAnimatorsEnabled needs no context); a 7.x TV follows the in-app switch alone.
    val systemReduced get() = Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled()
    /** Every fade under [reduced]: short, but still a fade rather than a cut. */
    const val FADE = 120L

    val ENTER get() = if (reduced) FADE else 220L
    val EXIT get() = if (reduced) FADE else 150L
    /** Pure travel (a drawer, the promo's dot): none under [reduced]. */
    val MOVE get() = if (reduced) 0L else 300L
    val FOCUS_IN get() = if (reduced) FADE else 180L
    const val FOCUS_OUT = 120L
    /** A page or a block of content arriving (window transitions, home's rows, the login column). */
    val PAGE get() = if (reduced) FADE else 420L
    /** The rail's panel and labels opening, the labels following one another by [STAGGER]. */
    val EXPAND get() = if (reduced) FADE else 240L
    val STAGGER get() = if (reduced) 0L else 20L
    /** The rail's one white pill gliding to the next item. */
    val GLIDE get() = if (reduced) 0L else 200L
    /** Artwork fading in over its placeholder (only when it comes off the network or disk). */
    val IMAGE get() = if (reduced) FADE.toInt() else 240
    /** A full-screen backdrop crossfading to the next: slow enough to read as light, not a cut. */
    val BACKDROP get() = if (reduced) FADE.toInt() else 700
    /** How far a focused card comes forward; the white ring says the rest. */
    const val LIFT = 1.08f

    /** D-pad presses closer together than this are a held key ... */
    const val REPEAT_GAP = 160L
    /** ... and the hold is over this long after the last one. */
    const val SETTLE = 220L

    /**
     * True while the D-pad is held (see TvActivity): focus answers twice as fast so the remote
     * never waits on it, and heavy swaps (the billboard) hold off until TvActivity.onSettled.
     */
    var fast = false

    /** How far something travels in as it arrives: [px], or none under [reduced], where only its fade is left. */
    fun travel(px: Float) = if (reduced) 0f else px
}

/** Brings a card forward on focus and settles it on blur, picking up from wherever it is. */
fun View.lift(focused: Boolean) {
    val scale = if (focused) Motion.LIFT else 1f
    val duration = when {
        Motion.reduced -> 0L // still comes forward, at once
        focused -> Motion.FOCUS_IN
        else -> Motion.FOCUS_OUT
    }
    animate().scaleX(scale).scaleY(scale).setStartDelay(0).setInterpolator(Motion.easeOut)
        .setDuration(if (Motion.fast) duration / 2 else duration).start()
}

/** A recycled card arrives at rest: whatever lift or ring fade it was mid-way through stays with its old title. */
fun View.settle() {
    animate().cancel()
    val scale = if (isFocused) Motion.LIFT else 1f
    scaleX = scale
    scaleY = scale
    foreground?.jumpToCurrentState()
}

/** Shows a view with a short rise and fade, or sinks and fades it, then GONE. */
fun View.reveal(show: Boolean) {
    val rise = Motion.travel(12 * resources.displayMetrics.density)
    if (show) {
        if (visibility == View.VISIBLE && alpha == 1f) return
        if (visibility != View.VISIBLE) {
            alpha = 0f
            translationY = rise
            visibility = View.VISIBLE
        }
        animate().alpha(1f).translationY(0f).setStartDelay(0).setInterpolator(Motion.easeOut).setDuration(Motion.ENTER).start()
    } else if (visibility == View.VISIBLE) {
        animate().alpha(0f).translationY(rise).setStartDelay(0).setInterpolator(Motion.easeOut).setDuration(Motion.EXIT)
            .withEndAction { visibility = View.GONE }.start()
    }
}

/** Content arriving: up from a little below as it fades in, each view [step] ms after the last. */
fun rise(views: List<View>, step: Long = 40L, delay: Long = 0L) {
    val stagger = if (Motion.reduced) 0L else step // reduced, it all fades up together
    views.forEachIndexed { i, view ->
        view.alpha = 0f
        view.translationY = Motion.travel(16 * view.resources.displayMetrics.density)
        view.animate().alpha(1f).translationY(0f).setStartDelay(delay + i * stagger).setInterpolator(Motion.emph)
            .setDuration(Motion.PAGE).start()
    }
}
