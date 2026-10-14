package com.doxigo.tv30

import android.app.Activity
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/**
 * The original 30nama motion logo; ready home content never waits for it.
 *
 * It plays on a SurfaceView, which the system composites straight from the decoder: home builds
 * its rows on the main thread underneath (100–200 ms frames on a slow TV), and a TextureView
 * froze with every one of them. A surface takes no view alpha, so what fades is laid over it,
 * in its frame: [dim] for the breathing, and at the exit a copy of the frame on screen ([still]).
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class Intro(activity: Activity) {
    private val view = activity.layoutInflater.inflate(R.layout.intro, activity.findViewById(android.R.id.content), false)
    private val video: PlayerView = view.findViewById(R.id.intro_video)
    private val logo: View = view.findViewById(R.id.intro_logo)
    private val loading: TextView = view.findViewById(R.id.intro_loading)
    private val still = ImageView(activity)
    private val dim = View(activity).apply {
        setBackgroundColor(Color.BLACK)
        alpha = 0f
    }
    private var player: ExoPlayer? = null
    private val lifecycle = (activity as? LifecycleOwner)?.lifecycle
    private val stopped = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) = dispose()
    }
    // Black at 0–60% over the video, on black: the video breathing between full and 40%.
    private val breathing = ObjectAnimator.ofFloat(dim, View.ALPHA, 0f, .6f).apply {
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
        video.findViewById<ViewGroup>(androidx.media3.ui.R.id.exo_content_frame).run {
            addView(still)
            addView(dim)
        }
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
        // The surface is under the window, out of the fade's reach: the frame it shows now is
        // copied over it and fades instead. Without a copy (Android 6, no frame yet) the fade
        // starts from black, the shutter's or the hole's.
        val surface = video.videoSurfaceView as? SurfaceView
        if (Build.VERSION.SDK_INT < 24 || surface == null || surface.width == 0 || !surface.holder.surface.isValid) return fadeOut()
        // ponytail: half size, which nobody sees in a 150 ms fade; full size if it ever shows.
        val frame = Bitmap.createBitmap(surface.width / 2, surface.height / 2, Bitmap.Config.ARGB_8888)
        PixelCopy.request(surface, frame, { result ->
            if (result == PixelCopy.SUCCESS) still.setImageBitmap(frame)
            fadeOut()
        }, view.handler ?: return fadeOut())
    }

    private fun fadeOut() {
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
