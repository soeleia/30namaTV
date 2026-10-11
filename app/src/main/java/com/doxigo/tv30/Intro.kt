package com.doxigo.tv30

import android.app.Activity
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/** The original 30nama motion logo; ready home content never waits for it. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class Intro(activity: Activity) {
    private val view = activity.layoutInflater.inflate(R.layout.intro, activity.findViewById(android.R.id.content), false)
    private val video: PlayerView = view.findViewById(R.id.intro_video)
    private val logo: View = view.findViewById(R.id.intro_logo)
    private val loading: TextView = view.findViewById(R.id.intro_loading)
    private var player: ExoPlayer? = null
    private val lifecycle = (activity as? LifecycleOwner)?.lifecycle
    private val stopped = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) = dispose()
    }
    private val breathing = ObjectAnimator.ofFloat(video, View.ALPHA, 1f, .4f).apply {
        duration = 1_500
        startDelay = 200
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        interpolator = Motion.easeInOut
    }
    private val showLoading = Runnable {
        val shift = (200 * view.resources.displayMetrics.density).coerceAtMost(view.height * .2f)
        video.animate().scaleX(.7f).scaleY(.7f).translationY(-shift)
            .setDuration(400).setInterpolator(Motion.easeOut).start()
        loading.translationY = view.height * .15f
        loading.visibility = View.VISIBLE
        loading.animate().alpha(1f).setDuration(400).setInterpolator(Motion.easeOut).start()
        breathing.start()
    }
    private val timeout = Runnable { leave() }
    private var gone = false

    init {
        loading.typeface = activity.font(if (loading.text.isLatin) R.font.inter_bold else R.font.peyda_bold)
        activity.findViewById<ViewGroup>(android.R.id.content).addView(view)
        lifecycle?.addObserver(stopped)
        if (Motion.reduced) {
            showLogo()
        } else {
            player = ExoPlayer.Builder(activity).build().also { motion ->
                video.player = motion
                motion.volume = 0f
                motion.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) = showLogo()
                })
                motion.setMediaItem(MediaItem.fromUri("android.resource://${activity.packageName}/${R.raw.nama_motion}"))
                motion.prepare()
                motion.play()
            }
            view.postDelayed(showLoading, 6_500)
        }
        view.postDelayed(timeout, 10_000)
    }

    private fun showLogo() {
        video.visibility = View.GONE
        logo.visibility = View.VISIBLE
    }

    fun leave() {
        if (gone) return
        gone = true
        stopEntrance()
        view.animate().alpha(0f).setDuration(Motion.EXIT).setInterpolator(Motion.easeOut)
            .withEndAction { dispose() }.start()
    }

    fun dispose() {
        gone = true
        stopEntrance()
        view.animate().cancel()
        video.player = null
        player?.release()
        player = null
        lifecycle?.removeObserver(stopped)
        (view.parent as? ViewGroup)?.removeView(view)
    }

    private fun stopEntrance() {
        view.removeCallbacks(showLoading)
        view.removeCallbacks(timeout)
        player?.pause()
        breathing.cancel()
        video.animate().cancel()
        loading.animate().cancel()
    }

    companion object {
        /** Once per process, skipped when reduced motion is enabled. */
        var shown = false
            get() = field || Motion.reduced
    }
}
