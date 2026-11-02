package com.doxigo.tv30

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.media.MediaCodecList
import android.os.Build
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
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.inspector.frame.FrameExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.TreeMap
import kotlin.math.abs

// ---- Seek preview: a frame near where the bar is scrubbed to, over the bar. ----

/**
 * While the bar is scrubbed, a frame of the film at its dot rides above it with its time: YouTube's
 * storyboard in Apple TV's dress. 30nama has no thumbnail sprites, so the frames are the lowest
 * quality's own keyframes, one per segment, at its start, through Media3's FrameExtractor.
 *
 * A few seconds into playing, once the film has buffer to spare, a storyboard of up to [BOARD] frames
 * across the whole file is taken in the background, coarse to fine (the start and middle, then the
 * quarters, the eighths…), so a scrub anywhere finds one at once. The segment the bar is scrubbed to
 * goes ahead of the rest, so where it stops the exact frame follows. A frame more than one storyboard
 * step from the dot is never shown, nor one left from an earlier stop: no frame beats the wrong one.
 *
 * Each frame reads only its segment's opening bytes, where the keyframe is ([Heads]): a second and a
 * half's worth, where each used to cost a whole segment, and it is kept as a JPEG of a few KB.
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
    /** [url]'s playlist on its way. */
    private var reading: Job? = null
    /** Each segment's start in [url]'s playlist (ms), and how many segments a storyboard frame stands for. */
    private var starts: LongArray? = null
    private var step = 1
    private var heads: Heads? = null
    private var media: MediaItem? = null
    private var extractor: FrameExtractor? = null
    /** The frames taken, by segment, as JPEG. */
    private val frames = TreeMap<Int, ByteArray>()
    /** The storyboard's segments still to take, coarse to fine. */
    private val board = ArrayDeque<Int>()
    /** The segment whose frame is on its way, and the newest one scrubbed to since, taken next. */
    private var asking: Int? = null
    private var wanted: Int? = null
    /** The segment whose frame [image] holds. */
    private var shown: Int? = null
    /** Where the bar is scrubbed to, null while it isn't. */
    private var at: Long? = null
    private val opener = Runnable { open() }
    private val pump = Runnable { next() }
    /**
     * The film itself decodes in software, a device or emulator with no hardware decoder for it: a
     * preview's decoder would then take the film's CPU, so there are none (see [softwareOnly]).
     */
    private var filmInSoftware = false

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
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                filmInSoftware = softwareOnly(decoderName)
                if (filmInSoftware) close()
            }
        })
    }

    /** The bar scrubbed to [positionMs]: the frame nearest it over the dot, its own asked for if it isn't in. */
    fun show(positionMs: Long) {
        at = positionMs
        time.text = clock(positionMs)
        open()
        val s = starts?.let { segmentAt(it, positionMs) }
        if (s != null && s !in frames && s != asking) {
            wanted = s
            next()
        }
        draw()
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
        root.removeCallbacks(pump)
        reading?.cancel()
        reading = null
        release()
        url = null
        starts = null
        heads = null
        media = null
        frames.clear()
        board.clear()
        wanted = null
        shown = null
        image.setImageDrawable(null)
        hide()
    }

    /** Reads the lowest quality's playlist, then opens an extractor on it and starts taking frames. */
    private fun open() {
        if (extractor != null || reading != null || filmInSoftware || Settings.saveData) return
        val url = file()?.let(EpisodeFrames::lowest)?.takeIf { it != broken } ?: return
        if (url != missed) { // close() forgets [url]; this outlives it
            misses = 0
            missed = url
        }
        this.url = url
        val heads = heads
        val media = media
        if (heads == null || media == null) {
            reading = Api.scope.launch {
                val playlist = withContext(Dispatchers.IO) { runCatching { Buffering.mediaPlaylist(url) }.getOrNull() }
                reading = null
                if (this@SeekPreview.url != url) return@launch // closed meanwhile
                if (playlist == null || playlist.segments.isEmpty()) {
                    if (++misses >= 3) broken = url // the next scrub tries again until then
                    return@launch
                }
                read(playlist)
                open()
            }
            return
        }
        extractor = FrameExtractor.Builder(context.applicationContext, media)
            .setMediaSourceFactory(DefaultMediaSourceFactory(heads)
                .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(1))) // a preview is worth one retry, not ten
            .setSeekParameters(SeekParameters.CLOSEST_SYNC) // a segment's start is its keyframe: no decoding up to an exact one
            .setMediaCodecSelector(EpisodeFrames.SOFTWARE)
            .setEffects(listOf(Presentation.createForHeight(HEIGHT_PX)))
            .build()
        next()
    }

    /** Where each segment starts, the storyboard's order, and the playhead's segment first: where the scrub will start from. */
    private fun read(playlist: HlsMediaPlaylist) {
        val segments = playlist.segments
        val starts = LongArray(segments.size) { segments[it].relativeStartTimeUs / 1000 }
        this.starts = starts
        step = (segments.size + BOARD - 1) / BOARD
        coarseToFine((segments.size + step - 1) / step).mapTo(board) { it * step }
        // An encrypted segment cut short won't decrypt, and an fMP4 one won't parse: those come whole.
        val cut = segments.first().url.substringBefore('?').endsWith(".ts", ignoreCase = true) &&
            segments.none { it.fullSegmentEncryptionKeyUri != null }
        heads = Heads(playlist.targetDurationUs, cut)
        media = MediaItem.Builder().setUri(playlist.baseUri).setMimeType(MimeTypes.APPLICATION_M3U8).build()
        wanted = segmentAt(starts, player.currentPosition)
    }

    /**
     * Takes the next frame, one at a time (FrameExtractor runs one task and can't drop one half-way):
     * the segment scrubbed to, else the storyboard's next while the film has [SPARE_MS] buffered or
     * isn't loading. Segments that come whole (see [Heads]) are only taken where scrubbed to: a
     * storyboard of them would cost a fifth of the film.
     */
    private fun next() {
        root.removeCallbacks(pump)
        if (asking != null) return
        if (extractor == null) return open() // after a network failure: a fresh one, on a fresh connection
        val scrubbed = wanted?.takeIf { it !in frames }
        wanted = null
        if (scrubbed != null) return take(scrubbed)
        if (heads?.cut != true) board.clear()
        while (board.isNotEmpty() && board.first() in frames) board.removeFirst()
        if (board.isEmpty()) return
        if (player.isLoading && player.totalBufferedDuration < SPARE_MS) root.postDelayed(pump, WAIT_MS)
        else take(board.first())
    }

    /** Asks for segment [s]'s frame. While it loads, the read-ahead yields the connection to it (Buffering.priority), as it does to the player. */
    private fun take(s: Int) {
        val extractor = extractor ?: return
        val starts = starts ?: return
        asking = s
        Buffering.priority.add(PRIORITY)
        val future = extractor.getFrame(starts[s])
        future.addListener({
            val frame = runCatching { jpeg(future.get().bitmap) } // off the main thread: a few ms
            ContextCompat.getMainExecutor(context).execute { took(extractor, s, frame) }
        }, Dispatchers.Default.asExecutor())
    }

    private fun took(extractor: FrameExtractor, s: Int, frame: Result<ByteArray>) {
        Buffering.priority.remove(PRIORITY)
        if (this.extractor !== extractor) return // closed meanwhile
        asking = null
        frame.onSuccess {
            misses = 0
            frames[s] = it
            draw()
            next()
        }.onFailure { e ->
            // A network failure lets the extractor go and the next one dials a fresh connection, as the
            // read-ahead does; a third in a row, or a frame that won't decode, ends previews for this file.
            // The frames already taken still show.
            val network = (e.cause as? PlaybackException)?.errorCode in 2000..2999
            if (!network || ++misses >= 3) broken = url
            Api.scope.launch(Dispatchers.IO) { Buffering.cdn.connectionPool.evictAll() } // closing a TLS socket writes to it: on the main thread an older Android kills the app
            release()
            if (broken == null) root.postDelayed(pump, RETRY_MS)
        }
    }

    /** Lets go of the extractor (its player and decoder); the frames it took stay. */
    private fun release() {
        extractor?.close()
        extractor = null
        asking = null
    }

    /** The frame nearest the dot over it, if one is within a storyboard step of it; none otherwise. */
    private fun draw() {
        val at = at ?: return
        val s = starts?.let { segmentAt(it, at) }
        val near = s?.let { listOfNotNull(frames.floorKey(it), frames.ceilingKey(it)).minByOrNull { k -> abs(k - it) }?.takeIf { k -> abs(k - it) <= step } }
        if (near == null) return showCard(false)
        if (near != shown) {
            val jpeg = frames.getValue(near)
            image.setImageBitmap(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size))
            shown = near
        }
        if (place(at)) showCard(true)
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

    /**
     * The extractor's reads. A TS segment is read only as far as the keyframe it opens with: the
     * file's first [HEAD_MS] of bytes, at its rate as the replies' Content-Range shows it (a whole
     * segment over the longest one's length), [FIRST] before any has, never under [LEAST]. Without
     * [cut], or once a server ignores the range (200, not 206), segments come whole.
     */
    // ponytail: [HEAD_MS] is the knob; a frame whose keyframe outgrows it shows grey below the cut. Raise it if 30nama's ever do.
    private class Heads(private val longestUs: Long, @Volatile var cut: Boolean) : DataSource.Factory {
        /** The file's bytes a second, the most any reply has shown; 0 before the first. */
        @Volatile private var rate = 0L

        override fun createDataSource(): DataSource {
            val source = Buffering.upstream.createDataSource()
            return object : DataSource by source {
                override fun open(dataSpec: DataSpec): Long {
                    if (!cut || dataSpec.uri.lastPathSegment?.endsWith(".ts", ignoreCase = true) != true) return source.open(dataSpec)
                    val head = if (rate == 0L) FIRST else maxOf(LEAST, rate * HEAD_MS / 1000)
                    val length = source.open(dataSpec.subrange(0, if (dataSpec.length == C.LENGTH_UNSET.toLong()) head else minOf(head, dataSpec.length)))
                    if (source.responseCode == 200) cut = false
                    // "bytes 0-65535/271096"
                    val whole = source.responseHeaders["content-range"]?.firstOrNull()?.substringAfterLast('/')?.toLongOrNull()
                    if (whole != null && longestUs > 0) rate = maxOf(rate, whole * 1_000_000 / longestUs)
                    return length
                }
            }
        }
    }

    private companion object {
        /**
         * Whether the decoder named [name] works in software: Google's own (OMX.google.*, c2.android.*)
         * always do, though an emulator's codec list may not say so; others by that list from Android 10.
         */
        fun softwareOnly(name: String) = name.startsWith("OMX.google.") || name.startsWith("c2.android.") ||
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.firstOrNull { it.name == name || it.canonicalName == name }?.isSoftwareOnly == true

        fun jpeg(frame: Bitmap): ByteArray = ByteArrayOutputStream().also { frame.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()

        /** The card's width (dp), a quarter of the screen; its height is the film's shape. */
        const val WIDTH = 224
        /** The frames' height in pixels, scaled up on screen: some 8 KB each as JPEG. */
        const val HEIGHT_PX = 144
        /** At most this many frames in the storyboard: one a minute of a two-hour film, about 20 MB of 480p's openings. */
        const val BOARD = 120
        /** A segment's opening read for its keyframe, in ms of the file's bytes; the first reply's size, and its floor. */
        const val HEAD_MS = 1_500L
        const val FIRST = 256L shl 10
        const val LEAST = 64L shl 10
        /** The storyboard waits while the film loads with less than this buffered, checking every [WAIT_MS]. */
        const val SPARE_MS = 30_000L
        const val WAIT_MS = 1_000L
        const val RETRY_MS = 2_000L
        /** The card's foot over the top of the bar's view, in dp. */
        const val GAP = 4
        const val OPEN_AFTER_MS = 3_000L
        /** Over the read-ahead (C.PRIORITY_DOWNLOAD), under the player. */
        const val PRIORITY = C.PRIORITY_DOWNLOAD + 1
    }
}

/** The segment that [positionMs] falls in, by each one's start in [starts]. */
internal fun segmentAt(starts: LongArray, positionMs: Long): Int =
    starts.binarySearch(positionMs).let { if (it >= 0) it else (-it - 2).coerceAtLeast(0) }

/**
 * 0 until [n], coarse to fine: 0 and the middle, then the quarters, the eighths and so on, so the
 * first few cover the whole bar and the rest only make it denser.
 */
internal fun coarseToFine(n: Int): List<Int> {
    val order = ArrayList<Int>(n)
    val taken = BooleanArray(n)
    var stride = Integer.highestOneBit(maxOf(n - 1, 1))
    while (stride > 0) {
        for (i in 0 until n step stride) if (!taken[i]) {
            taken[i] = true
            order += i
        }
        stride /= 2
    }
    return order
}
