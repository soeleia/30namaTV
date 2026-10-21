package com.doxigo.tv30

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.inspector.frame.FrameExtractor
import org.json.JSONObject
import java.util.Locale

// ---- Seek preview: the frame the bar is scrubbed to, over the bar. ----

/**
 * While the bar is scrubbed, the frame at its dot rides above it with its time: Apple TV's preview.
 * 30nama has no thumbnail sprites, so the frames are the lowest quality's keyframes (EpisodeFrames
 * reads the same), through Media3's FrameExtractor: one per playing file, opened a few seconds after
 * it starts playing (or at the first scrub before then), so the playlist is in and the decoder tried
 * before the remote asks. Only the newest position waits for its turn, the ones scrubbed past are
 * never asked, and the last [FRAMES] stay in memory for the file.
 *
 * The extractor decodes in software only ([EpisodeFrames.SOFTWARE]): a second hardware decoder beside the film's
 * can fail or stall it on an older TV, and a keyframe of the lowest quality is a few milliseconds of
 * CPU. Whatever goes wrong (no software decoder for the codec, the CDN) ends the previews for that
 * file and nothing else: the bar scrubs on without them.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class SeekPreview(private val root: ViewGroup, private val player: ExoPlayer, private val file: () -> JSONObject?) {
    private val context = root.context
    private val dp = context.resources.displayMetrics.density
    private val wide = (WIDTH * dp).toInt()
    private val image = ImageView(context).apply {
        adjustViewBounds = true // the film's own shape under a fixed width
        scaleType = ImageView.ScaleType.FIT_CENTER
        background = ContextCompat.getDrawable(context, R.drawable.player_poster) // artwork's corners, clipped to
        clipToOutline = true
    }
    private val time = TextView(context).apply {
        setTextAppearance(R.style.TextAppearance_Leanback_PlaybackControlsTime) // the clock's own
    }
    /** The frame over its time; it fades in and out (View.reveal) inside [box], which follows the dot. */
    private val card = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS // the bar's clock says the time
        visibility = View.GONE
        addView(image, LinearLayout.LayoutParams(wide, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(time, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (8 * dp).toInt() })
    }
    private val box = FrameLayout(context).apply { addView(card) }

    /** The lowest quality's URL the extractor reads, and one whose frames failed: none from it again. */
    private var url: String? = null
    private var broken: String? = null
    /** Network failures on [missed] since its last frame: a 403 from the wrong exit is routine here, three in a row is not. */
    private var misses = 0
    private var missed: String? = null
    private var extractor: FrameExtractor? = null
    private val frames = LruCache<Long, Bitmap>(FRAMES)
    /** The frame on its way (null: none) and the newest one scrubbed to since, asked next. */
    private var asking: Long? = null
    private var wanted: Long? = null
    /** Where the bar is scrubbed to, null while it isn't. */
    private var at: Long? = null
    private val opener = Runnable { open() }

    init {
        // Above everything, at the foot's left: [place] moves it over the dot, the bar running left to right in either language.
        root.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.LEFT))
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                root.removeCallbacks(opener)
                if (isPlaying && extractor == null) root.postDelayed(opener, OPEN_AFTER_MS)
            }
            // Another file; a change of quality or audio keeps the same lowest quality, and its frames.
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (file()?.let(EpisodeFrames::lowest) != url) close()
            }
        })
    }

    /** The bar scrubbed to [positionMs]: its frame over the dot, or the last one shown until it comes. */
    fun show(positionMs: Long) {
        at = positionMs
        open()
        val key = positionMs / STEP_MS
        val frame = frames[key]
        if (frame == null) ask(key) else {
            image.setImageBitmap(frame)
            wanted = null // one scrubbed past since
        }
        time.text = clock(positionMs)
        if (place(positionMs) && image.drawable != null) showCard(true)
    }

    /** The scrub is over (landed or cancelled): the frame on its way is kept, the one after it dropped. */
    fun hide() {
        at = null
        wanted = null
        showCard(false)
    }

    /** Lets go of the file's extractor and frames: another file, a failure, or the screen stopping (onStop). */
    fun close() {
        root.removeCallbacks(opener)
        extractor?.close()
        extractor = null
        url = null
        asking = null
        wanted = null
        frames.evictAll()
        image.setImageDrawable(null)
        hide()
    }

    private fun open() {
        if (extractor != null) return
        val url = file()?.let(EpisodeFrames::lowest)?.takeIf { it != broken } ?: return
        if (url != missed) { // close() forgets [url]; this outlives it
            misses = 0
            missed = url
        }
        this.url = url
        val item = MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8).build()
        extractor = FrameExtractor.Builder(context.applicationContext, item)
            .setMediaSourceFactory(DefaultMediaSourceFactory(Buffering.dataSource(context))
                .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(1))) // a preview is worth one retry, not ten
            .setSeekParameters(SeekParameters.CLOSEST_SYNC) // the keyframe nearest: no decoding up to an exact one
            .setMediaCodecSelector(EpisodeFrames.SOFTWARE)
            .setEffects(listOf(Presentation.createForHeight(HEIGHT_PX)))
            .build()
        ask(player.currentPosition / STEP_MS) // where the scrub will start from
    }

    /**
     * Asks for [key]'s frame, or, while another is on its way, keeps it to ask next: FrameExtractor
     * runs one task at a time and can't drop one half-way, so only the newest waits. While one loads,
     * the read-ahead yields the connection to it (Buffering.priority), as it does to the player.
     */
    private fun ask(key: Long) {
        val extractor = extractor ?: return
        if (asking != null) {
            wanted = key.takeIf { it != asking }
            return
        }
        asking = key
        Buffering.priority.add(PRIORITY)
        val last = (player.duration - STEP_MS).coerceAtLeast(0) // a keyframe past the end would fail the file
        val future = extractor.getFrame((key * STEP_MS).coerceAtMost(last))
        future.addListener({
            Buffering.priority.remove(PRIORITY)
            if (this.extractor !== extractor) return@addListener // closed meanwhile
            asking = null
            val frame = try {
                future.get().bitmap
            } catch (e: Exception) {
                // A network failure closes the extractor and the next scrub opens a fresh one on a new
                // connection; a third in a row, or a frame that won't decode, ends previews for this file.
                val network = (e.cause as? PlaybackException)?.errorCode in 2000..2999
                if (!network || ++misses >= 3) broken = url
                close()
                return@addListener
            }
            misses = 0
            frames.put(key, frame)
            val now = at
            if (now != null && now / STEP_MS == key) {
                image.setImageBitmap(frame)
                if (place(now)) showCard(true)
            }
            wanted?.let {
                wanted = null
                if (frames[it] == null) ask(it)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Brings the card in or out. While it shows, the controls' poster steps aside: Leanback hides the
     * names beside it as a scrub starts, and the frame passes over it at that end of the bar.
     */
    private fun showCard(shown: Boolean) {
        card.reveal(shown)
        root.findViewById<View>(androidx.leanback.R.id.image)?.animate()?.alpha(if (shown) 0f else 1f)?.setStartDelay(0)
            ?.setInterpolator(Motion.easeOut)?.setDuration(if (shown) Motion.EXIT else Motion.ENTER)?.start()
    }

    /**
     * Centres the card over the dot at [positionMs], kept within the bar's ends, its foot [GAP] over
     * the bar. Layout positions, not the screen's: the controls may still be sliding in. False
     * before the bar is laid out.
     */
    // ponytail: the dot's own inset from the bar's ends (its radius) is left out; a few dp at the very ends, where the clamp takes over.
    private fun place(positionMs: Long): Boolean {
        val bar = root.findViewById<View>(androidx.leanback.R.id.playback_progress) ?: return false
        val duration = player.duration.takeIf { it > 0 } ?: return false
        if (bar.width == 0) return false
        val edges = Rect().also { bar.getDrawingRect(it) }
        root.offsetDescendantRectToMyCoords(bar, edges)
        val dot = edges.left + edges.width() * positionMs.coerceIn(0, duration) / duration
        box.translationX = (dot - wide / 2f).coerceIn(edges.left.toFloat(), (edges.right - wide).toFloat())
        box.translationY = -(root.height - edges.top + GAP * dp)
        return true
    }

    private fun clock(ms: Long): String {
        val s = ms / 1000
        val text = if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
        return text.localDigits() // as the bar's own clock writes it
    }

    private companion object {
        /** The card's width (dp), a quarter of the screen; its height is the film's shape. */
        const val WIDTH = 224
        /** The frames' height in pixels: the lowest quality's own, scaled up on screen. 40 of them at 16:9 are under 6 MB. */
        const val HEIGHT_PX = 144
        const val FRAMES = 40
        /** Positions within this of each other share a frame (the bar's smallest step: PlayerFragment.SEEK_STEP_MS). */
        const val STEP_MS = 5_000L
        /** The card's foot over the top of the bar's view, in dp. */
        const val GAP = 4
        const val OPEN_AFTER_MS = 3_000L
        /** Over the read-ahead (C.PRIORITY_DOWNLOAD), under the player. */
        const val PRIORITY = C.PRIORITY_DOWNLOAD + 1
    }
}
