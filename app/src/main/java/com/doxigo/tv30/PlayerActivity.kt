package com.doxigo.tv30

import android.animation.TimeAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.TextUtils
import android.text.format.DateFormat
import android.text.method.ReplacementTransformationMethod
import android.text.style.CharacterStyle
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.leanback.app.VideoSupportFragment
import androidx.leanback.app.VideoSupportFragmentGlueHost
import androidx.leanback.media.PlaybackTransportControlGlue
import androidx.leanback.widget.AbstractDetailsDescriptionPresenter
import androidx.leanback.widget.Action
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.PlaybackControlsRow
import androidx.leanback.widget.PlaybackRowPresenter
import androidx.leanback.widget.PlaybackSeekDataProvider
import androidx.leanback.widget.PlaybackSeekUi
import androidx.leanback.widget.PlaybackTransportRowPresenter
import androidx.leanback.widget.RowPresenter
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.common.text.Cue
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.ui.leanback.LeanbackPlayerAdapter
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.ImageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class PlayerActivity : TvActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Leanback fits the video surface to the film's shape; the letterbox bars around it are
        // the window, which must be true black there, not the app's ink.
        window.setBackgroundDrawableResource(android.R.color.black)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(android.R.id.content, PlayerFragment()).commit()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val player = supportFragmentManager.findFragmentById(android.R.id.content) as? PlayerFragment
        return player?.onKey(event) == true || super.dispatchKeyEvent(event)
    }

    companion object {
        /** [title]: what the controls show beside its name (poster, score, year); the `stream` answer carries none of it. */
        fun intent(context: Context, id: Int, free: Boolean, fileId: String? = null, title: JSONObject? = null): Intent =
            Intent(context, PlayerActivity::class.java).putExtra("id", id).putExtra("free", free).putExtra("file", fileId)
                .putExtra("title", title?.playerFacts?.toString())
    }
}

/**
 * Plays one title's `stream` answer: an `hls` list with one entry per file (a film has one,
 * a series one per episode). Each entry carries its qualities, subtitles and the intro/recap
 * marks; the server tracks progress through `observer` at every 15 s mark, as the official player does.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlayerFragment : VideoSupportFragment() {
    private lateinit var player: ExoPlayer
    /** The shape the video surface was last fitted to (see [onVideoSizeChanged]); null before the first picture. */
    private var shape: Pair<Int, Int>? = null
    private lateinit var cues: CueView
    /** Where the subtitles sit now: [REST], [LIFTED] over the controls, or [BESIDE] the panel. */
    private var spot = REST
    private val card = Rect()
    private val words = Rect()
    private lateinit var panel: PlayerPanel
    /** The action that opened the panel, to hand the remote back to. */
    private var opener: View? = null
    private var glue: Glue? = null
    /** What the system and Assistant see and steer; the remote's media keys go through it too (see [onKey]). */
    private lateinit var session: PlayerSession
    private val prefs by lazy { requireContext().getSharedPreferences("player", Context.MODE_PRIVATE) }
    /**
     * The connection's speed, where Auto's first rung comes from until segments measure it: what the
     * last viewing measured, else [INITIAL_ESTIMATE]. ExoPlayer's own guess, 1 Mbps (860 kbps for
     * Iran), started Mux's test film at 480p and held it 30 s on a 20 Mbps link; a fixed 6 Mbps
     * instead stalled 10 s at the start on a 1.5 Mbps one. Disk reads don't count (not network).
     */
    private val bandwidth by lazy {
        DefaultBandwidthMeter.Builder(requireContext()).setInitialBitrateEstimate(prefs.getLong("bandwidth", INITIAL_ESTIMATE)).build()
    }

    private var postId = 0
    /** The title as the screen that opened this one had it (Titles.playerFacts): its poster, score and year. */
    private var facts = JSONObject()
    private var stream: JSONObject? = null
    private var files: List<JSONObject> = emptyList()
    private var file: JSONObject? = null
    private var qualities: List<JSONObject> = emptyList()
    private var quality = 0
    /** Quality and audio picked in the panel: they hold for this viewing, episodes included, over Settings' defaults. */
    private var chosenQuality: String? = null
    private var chosenAudio: String? = null
    private var reportedAt = -1L
    /** The title's Persian name, under the English one on the controls. */
    private var persianName: String? = null
    /** Leanback is scrubbing the seek bar; [seekHeld]: how long the left or right key has been held. */
    private var seeking = false
    private var seekHeld = 0L
    /** The frame the bar is scrubbed to, over it (SeekPreview.kt). */
    private lateinit var preview: SeekPreview

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val context = requireContext()
        Settings.init(context) // a player restored after process death never passed through home
        player = ExoPlayer.Builder(context, DefaultRenderersFactory(context).setEnableDecoderFallback(true))
            // A heap-sized buffer in memory, the disk read-ahead behind it, and the player first on
            // the connection whenever it loads (see Buffering).
            .setLoadControl(Buffering.loadControl())
            .setPriorityTaskManager(Buffering.priority)
            .setBandwidthMeter(bandwidth)
            .setAudioAttributes(AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).setUsage(C.USAGE_MEDIA).build(), true)
            // DefaultDataSource reads the subtitles found on the subtitle sites from disk (Subtitles.kt)
            // and hands everything over http to Buffering's.
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, Buffering.dataSource(context))).setLoadErrorHandlingPolicy(Retry403)
                // Each subtitle's Persian put right as it is parsed (see Subtitles.cleaned).
                .setSubtitleParserFactory(Subtitles.Parsers()))
            .build()
        player.setPlaybackSpeed(Settings.speed) // a series' own, once known (load), and the panel's then hold
        applyTrackPreferences()
        session = PlayerSession(context, player, ::previousEpisode, ::nextEpisode)
        player.addListener(object : Player.Listener {
            // Not while the subtitle is moved off its times: its cues are drawn from here then (see retime).
            override fun onCues(cueGroup: CueGroup) {
                if (!retimed) showCues(cueGroup.cues)
            }
            // A found subtitle is picked by its track, which exists only once the item has loaded.
            override fun onTracksChanged(tracks: Tracks) {
                if (finds.picked != null) applyTrackPreferences()
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state != Player.STATE_ENDED) return
                if (sleep == SLEEP_END) sleep = 0 // the sleep timer's end: nothing plays on
                else if (autoplays) autoplayNext()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // No screensaver mid-film, but a paused or finished one lets it come, and the TV sleep.
                view?.keepScreenOn = isPlaying
                if (!isPlaying) report()
                syncCountdown()
            }
            override fun onPlayerError(error: PlaybackException) = requireContext().toast(getString(errorMessage(error.errorCode)))
        })
        progressBarManager.show()
        lifecycleScope.launch { load() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    delay(1_000)
                    tick()
                }
            }
        }
        // The disk read-ahead, while the screen shows: it follows the playhead (seeks included) on
        // its own and stops with the screen, as the player does in onStop.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { Buffering.prefetch(context, player, ::upcoming) }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val root = view as ViewGroup
        // Leanback fits the surface to the screen as it is when the picture comes; a TV that switches
        // mode for the film can change the screen's size after, so it is fitted again then.
        root.addOnLayoutChangeListener { v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) v.post { shape?.let { (w, h) -> onVideoSizeChanged(w, h) } }
        }
        val context = requireContext()
        // Over the dimming scrim (index 1, after the video surface) and under the controls dock, so
        // subtitles stay bright while the controls show. At most 80% of the width, centred.
        cues = CueView(context)
        root.addView(cues, 2, FrameLayout.LayoutParams((resources.displayMetrics.widthPixels * 0.8f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        applySubtitleStyle()
        addSkip(root)
        // Buffering shows in the top start corner, where nothing else ever sits (the subtitles, the
        // controls' title, the panel and the skip pill all keep below or to the end): Leanback's
        // spinner, mid-screen, crossed the subtitles once they rose over the controls. Small and in
        // 30nama's red, as the intro's is; the medium ring at 32dp draws the intro's 2.7dp line.
        val dp = resources.displayMetrics.density
        val spinner = ProgressBar(context).apply {
            indeterminateTintList = ContextCompat.getColorStateList(context, R.color.brand)
        }
        root.addView(spinner, FrameLayout.LayoutParams((32 * dp).toInt(), (32 * dp).toInt(), Gravity.TOP or Gravity.START).apply {
            marginStart = (48 * dp).toInt()
            topMargin = (48 * dp).toInt()
        })
        progressBarManager.setProgressBarView(spinner)
        panel = PlayerPanel(root, cues, ::uncover)
        preview = SeekPreview(root, player) { file }
        // The overlay shows and hides from many places (keys, auto-hide, pause), and so does the
        // panel; the overlay's flag flips as its fade starts, and the fade draws, so a pre-draw
        // check catches every change on time.
        root.viewTreeObserver.addOnPreDrawListener {
            arrange()
            true
        }
    }

    /** The subtitles and the skip pill, wherever the controls or the panel leave room. */
    private fun arrange() {
        val want = when {
            panel.covers -> BESIDE
            isControlsOverlayVisible -> LIFTED
            else -> REST
        }
        val berthChanged = placeSkip(want)
        if (want != spot || berthChanged || retitled && want == LIFTED) placeCues(want)
        retitled = false
    }

    /**
     * The panel stands in for the controls while it shows. Leanback brings them back on its own (a
     * play state change, a tickle), so that is refused here rather than undone after. Each time the
     * controls come up, the remote starts on play/pause.
     */
    override fun showControlsOverlay(runAnimation: Boolean) {
        if (::panel.isInitialized && panel.isOpen) return
        if (!isControlsOverlayVisible) focusControls()
        super.showControlsOverlay(runAnimation)
    }

    override fun setPlaybackSeekUiClient(client: PlaybackSeekUi.Client?) {
        super.setPlaybackSeekUiClient(client?.let { delegate ->
            object : PlaybackSeekUi.Client() {
                private var startedAt = 0L
                private var requestedAt = 0L
                override fun isSeekEnabled() = delegate.isSeekEnabled
                override fun getPlaybackSeekDataProvider() = delegate.playbackSeekDataProvider
                override fun onSeekStarted() {
                    seeking = true
                    startedAt = player.currentPosition
                    requestedAt = startedAt
                    delegate.onSeekStarted()
                }
                override fun onSeekPositionChanged(positionMs: Long) {
                    requestedAt = positionMs
                    delegate.onSeekPositionChanged(positionMs)
                    preview.show(positionMs)
                }
                override fun onSeekFinished(cancelled: Boolean) {
                    seeking = false
                    preview.hide()
                    delegate.onSeekFinished(cancelled)
                    if (!cancelled && requestedAt > startedAt) player.play()
                }
            }
        })
    }

    // onPause, not onStop: the home screen re-reads "continue watching" as soon as it is back in
    // front, which is before this screen stops.
    override fun onPause() {
        super.onPause()
        report()
    }

    /** Leanback fits the surface to the size the decoder reports; [filmShape] may put the stream's in its place. */
    override fun onVideoSizeChanged(width: Int, height: Int) {
        val stream = player.videoFormat?.let { f -> (f.width * f.pixelWidthHeightRatio).roundToInt() to f.height }
        val fitted = filmShape(width to height, stream)
        shape = fitted
        super.onVideoSizeChanged(fitted.first, fitted.second)
    }

    // A paused ExoPlayer keeps buffering; off screen it must not hold a connection to the CDN.
    override fun onStop() {
        super.onStop()
        player.stop()
        preview.close()
        // The timer is for this sitting: one left running would pause the film an hour later, on return.
        sleep = 0
        sleepAt = 0
    }

    override fun onStart() {
        super.onStart()
        if (file != null && player.playbackState == Player.STATE_IDLE) player.prepare()
    }

    override fun onDestroy() {
        super.onDestroy()
        prefs.edit().putLong("bandwidth", bandwidth.bitrateEstimate).apply()
        session.release()
        player.release()
    }

    /**
     * Back closes the panel a page at a time (its other keys move through it as focus does). Without
     * it, the skip pill has its say first (see [skipKey]). Media keys go to [session] either way,
     * wherever focus is; Leanback never sees them (it would swallow next, previous and the seek keys).
     */
    fun onKey(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) unattended = 0
        if (panel.isOpen) {
            if (event.keyCode != KeyEvent.KEYCODE_BACK) return session.onKey(event)
            if (event.action == KeyEvent.ACTION_UP) panel.back()
            return true
        }
        // After the pill (a seek or a skip stops its countdown); the controls come up to show what the key did.
        return skipKey(event) || seekKey(event) || session.onKey(event).also { if (it && event.action == KeyEvent.ACTION_DOWN) tickle() }
    }

    /**
     * Left and right scrub the seek bar, [seekStep] at a time: from hidden controls they bring it up
     * already seeking. A second after the last press the seek lands and playback goes on, where
     * Leanback would wait for OK (OK and Back still land or cancel it at once).
     */
    private fun seekKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) return false
        val bar = view?.findViewById<View>(androidx.leanback.R.id.playback_progress) ?: return false
        bar.removeCallbacks(landSeek)
        if (event.action == KeyEvent.ACTION_UP) {
            if (seeking) bar.postDelayed(landSeek, SEEK_LAND_MS)
            return false
        }
        seekHeld = event.eventTime - event.downTime
        if (isControlsOverlayVisible || glue == null) return false
        tickle()
        bar.requestFocus()
        return bar.dispatchKeyEvent(event)
    }

    /** OK on the bar, its only public way out of Leanback's seek. */
    private val landSeek = Runnable {
        view?.findViewById<View>(androidx.leanback.R.id.playback_progress)?.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
    }

    /** 5 s a press; held, the step doubles every 1.5 s up to 80 s, so a held key crosses a film in seconds. */
    // ponytail: paced for a remote repeating ~20 times a second; a slower one moves proportionally slower.
    private fun seekStep() = SEEK_STEP_MS shl (seekHeld / 1_500).coerceAtMost(4).toInt()

    private suspend fun load() {
        val args = requireActivity().intent
        postId = args.getIntExtra("id", 0)
        facts = args.getStringExtra("title")?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        val loaded = runCatching { Api.stream(postId, args.getBooleanExtra("free", false)) }
        val context = context ?: return // left while loading
        val stream = loaded.getOrElse {
            context.toast(it)
            requireActivity().finish()
            return
        }
        this.stream = stream
        // 30nama's Persian name, else TMDB's (PersianNames), which may come after the controls.
        stream.optJSONObject("info")?.let { info ->
            persianName = PersianNames.cached(info)
            if (PersianNames.pending(info)) lifecycleScope.launch {
                persianName = PersianNames.lookup(context, info) ?: return@launch
                glue?.host?.notifyPlaybackRowChanged()
            }
        }
        files = stream.optJSONArray("hls").objects()
        if (files.size > 1) player.setPlaybackSpeed(prefs.getFloat("speed.$postId", Settings.speed))
        val resume = stream.optJSONObject("continue_watching")
        val chosen = args.getStringExtra("file")
        // The server's point or where this TV left the title, whichever is further on (Watched).
        val point = Watched.resumePoint(context, postId, files, resume)
        val start = chosen?.let { id -> files.firstOrNull { it.str("info", "file_id") == id } } ?: files.getOrNull(point?.first ?: 0) ?: run {
            requireActivity().finish()
            return
        }
        val saved = files.indexOf(start) to if (point?.first == files.indexOf(start)) point.second else 0L
        // Saved in the credits, Play goes on to what comes next not yet seen (UpNext.kt, Watched), as
        // the title page's Play says; an episode tile means exactly that episode, credits and all.
        Watched.seed(context, files, resume)
        val (index, at) = if (chosen == null) Watched.upNext(context, files, saved.first, saved.second) else saved
        glue = Glue(context, LeanbackPlayerAdapter(context, player, 500), series = files.size > 1).apply {
            host = VideoSupportFragmentGlueHost(this@PlayerFragment)
            isSeekEnabled = true
            // No Leanback thumbnails (SeekPreview has its own frame): with a provider the bar and clock
            // move while scrubbing and the player seeks once, where it lands, instead of loading every
            // step on the way.
            seekProvider = PlaybackSeekDataProvider()
        }
        progressBarManager.hide()
        play(files[index], at * 1000)
        showPoster(context, facts.poster)
    }

    /** The title's poster beside its name on the controls; without one the name has the row to itself. */
    private fun showPoster(context: Context, url: String?) = lifecycleScope.launch {
        val image = context.imageLoader.execute(ImageRequest.Builder(context).data(url ?: return@launch).build()).image ?: return@launch
        glue?.art = image.asDrawable(resources)
    }

    private fun play(next: JSONObject, positionMs: Long) {
        report()
        // Subtitles found for the last file were for it alone, and so was its search page.
        finds.cancel()
        val picked = finds.picked != null
        finds = Finds()
        if (picked) {
            applyTrackPreferences()
            applySubtitleStyle()
        }
        file = next
        // Picked up part-way (a minute in, as Watched counts a start), the pill offers the start for its first seconds.
        startOverMs = positionMs.takeIf { it >= Watched.STARTED * 1000 } ?: 0L
        if (::panel.isInitialized && panel.shows(findPage)) panel.back()
        reportedAt = -1
        qualities = qualities(next)
        quality = pickQuality(qualities)
        // 30nama's own subtitles keep the title's timing: the next episode, or the next evening, starts shifted as this one was.
        for (own in ownSubtitles(next)) prefs.getLong(timingPref(own), 0L).takeIf { it != 0L }?.let { offsets.getOrPut(timingKey(own)) { it } }
        val info = next.optJSONObject("info")
        val season = info?.optInt("season") ?: 0
        val episode = info?.optInt("episode_number", info.optInt("episode")) ?: 0
        glue?.setTitle(stream?.optJSONObject("info")?.englishName)
        // 30nama's episode_name is nearly always "فصل 1 - قسمت 4", which the numbers already say: a real one follows them.
        episodeLine = if (files.size > 1) getString(R.string.season_episode, season, episode) else null
        val episodeName = info?.str("episode_name")?.takeUnless { it.isGenericEpisode }?.unescaped()?.localDigits()
        // Title, episode and cover for the system and Assistant; next and previous follow the series.
        val titleInfo = stream?.optJSONObject("info")
        val index = files.indexOf(next)
        session.nowPlaying(titleInfo?.englishName, episodeLine, stream?.cover, hasPrevious = index > 0, hasNext = index < files.lastIndex)
        setSource(positionMs)
        // The line under the names: what plays, the score, the year (Meta.playerMeta).
        glue?.subtitle = facts.playerMeta(requireContext(), episodeLine, episodeName, year()?.localDigits())
        retitled = true
        showEndTime() // after the new item is set: the old one's duration would give a wrong time
    }

    /** What plays, for a series: «فصل ۲، قسمت ۱۰». */
    private var episodeLine: String? = null
    /** The line under the names changed width since the cues were placed beside it (see [arrange]). */
    private var retitled = false

    /**
     * Netflix's «پایان ساعت ۲۲:۴۵», beside the length at the bar's end: now plus what is left at the
     * current speed, once the duration is known. Checked every tick, so a seek, a pause or a speed
     * change shows within a second; the text changes only when the minute does.
     */
    private fun showEndTime() {
        val left = player.duration.takeIf { it > 0 }?.let { (it - player.currentPosition).coerceAtLeast(0) }
        val endsAt = left?.let { getString(R.string.player_ends_at, clock(System.currentTimeMillis() + (it / player.playbackParameters.speed).toLong())) }.orEmpty()
        val shown = view?.findViewById<TextView>(R.id.player_ends) ?: return
        if (shown.text.toString() != endsAt) shown.text = endsAt
    }

    /** 22:45 in Persian digits; in English the device's own 12- or 24-hour clock ("10:45 PM"). */
    private fun clock(ms: Long): String =
        if (Api.language == "fa") SimpleDateFormat("HH:mm", Locale.US).format(Date(ms)).localDigits()
        else DateFormat.getTimeFormat(requireContext()).format(Date(ms))

    /** A file's qualities: each a label, and a playlist per audio variant. */
    private fun qualities(f: JSONObject) = (f.obj("file")?.opt("m3u8") as? org.json.JSONArray).objects()

    /** Among [qualities]' own labels, the one the panel picked for this viewing, else Settings'. */
    private fun pickQuality(qualities: List<JSONObject>) = Settings.pickQuality(qualities.map { it.str("label") }, chosenQuality ?: Settings.quality)

    /** [f]'s playlist in quality [q] and the audio picked; the file's own when it lists no qualities. */
    private fun playlist(f: JSONObject, q: JSONObject?) = q?.str(chosenAudio ?: Settings.audio) ?: q?.str("default") ?: f.str("file", "m3u8")

    /** [f]'s own subtitles, an srt or vtt URL per language, each known by its language. */
    private fun ownSubtitles(f: JSONObject) = f.obj("file", "subtitle")?.let { subs ->
        subs.keys().asSequence().mapNotNull { lang ->
            val src = subs.str(lang) ?: return@mapNotNull null
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(src))
                .setMimeType(if (src.substringBefore('?').endsWith(".srt")) MimeTypes.APPLICATION_SUBRIP else MimeTypes.TEXT_VTT)
                .setLanguage(lang)
                .setLabel(languageName(lang))
                .setId(lang)
                .build()
        }.toList()
    }.orEmpty()

    private fun setSource(positionMs: Long) {
        val f = file ?: return
        val url = playlist(f, qualities.getOrNull(quality)) ?: return
        val subtitles = ownSubtitles(f) + finds.added.values
        player.setMediaItem(
            MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8).setSubtitleConfigurations(subtitles)
                .setMediaMetadata(session.metadata).build(),
            positionMs,
        )
        player.prepare()
        player.playWhenReady = true
        retime() // another file, or a subtitle just found, starts at its own offset
    }

    private fun tick() {
        if (sleepAt > 0 && SystemClock.elapsedRealtime() >= sleepAt) fallAsleep()
        val f = file ?: return
        val seconds = player.currentPosition / 1000
        updateSkip(f, seconds)
        showEndTime()
        // The bar's loaded band (Glue): a pause to let the read-ahead load is when it grows most.
        if (!player.isPlaying) glue?.controlsRow?.bufferedPosition = Buffering.bufferedPosition(player)
        if (player.isPlaying && Api.progressMark(seconds) != Api.progressMark(reportedAt)) report()
    }

    /**
     * What the server keeps as "continue watching"; the official player sends the same six fields.
     * This TV's history of episodes seen (Watched) hears it first, a guest's free episodes too. At
     * every 15 s mark, a pause, leaving and before the next file plays, so a viewing past the
     * credits, or to the end where autoplay moves on, is marked seen. The server hears the mark.
     */
    private fun report() {
        val f = file ?: return
        val seconds = player.currentPosition / 1000
        val duration = player.duration.takeIf { it > 0 }?.div(1000) ?: return
        if (seconds <= 0) return
        reportedAt = seconds
        context?.let {
            Watched.record(it, f, seconds, duration)
            Watched.leave(it, postId, f, seconds)
        }
        val mark = Api.progressMark(seconds)
        if (mark <= 0 || Api.user?.optBoolean("user_allowed_to_stream") != true) return
        Api.scope.launch {
            runCatching {
                Api.observer(postId, f.obj("info")?.opt("file_id"), Api.user?.opt("user_id"), mark, duration, f.obj("options")?.opt("token"))
            }
        }
    }

    // ---- Skip intro / recap / to the next episode: a pill at the bottom end corner, offered by the
    // ---- "HH:MM:SS" marks on each file, Netflix's and Apple TV's way. ----

    /** The pill's fill shows remaining marked time or an automatic action's countdown. */
    private lateinit var skip: Button
    /** The label on offer now (0: none) and what pressing it does. */
    private var offered = 0
    private var skipAction: (() -> Unit)? = null
    /** The file the offer belongs to, and the marks auto-skip has already taken in it. */
    private var skipFile: JSONObject? = null
    private val autoSkipped = HashSet<Int>()
    /** A countdown on the pill: [due] ms of playing time (0: none), [counted] so far, then [onDue]. */
    private val countdown = TimeAnimator()
    private var skipStartMs = 0L
    private var skipEndMs = 0L
    private var due = 0L
    private var counted = 0L
    private var onDue: (() -> Unit)? = null
    /** Where the file was picked up part-way, in ms (0: from its start); the pill offers the start from there for [START_OVER_MS]. */
    private var startOverMs = 0L
    /** Episodes autoplayed in a row with no key pressed in between. */
    private var unattended = 0
    /** Where the pill is: [HIDDEN], [REST] at its corner or [LIFTED] over the controls. */
    private var skipSpot = HIDDEN
    /** How far in from the end edge the subtitles keep clear of the pill (0 while it is hidden). */
    private var skipBerth = 0

    /**
     * Netflix's autoplay, as Settings has it, until three episodes in a row have played with no key
     * pressed: then the next one waits for the remote, so a sleeping viewer doesn't run through a
     * series, its "continue watching" and a metered connection. A sleep timer set to the end of this
     * episode stops it too.
     */
    private val autoplays get() = Settings.autoplay && unattended < 3 && sleep != SLEEP_END

    private fun autoplayNext() {
        unattended++
        nextEpisode()
    }

    /**
     * 48dp from the end edge (5% of the width, the controls' own edge) and 11% of the height up,
     * inside the title-safe area: bottom left in Persian, bottom right in English.
     */
    private fun addSkip(root: ViewGroup) {
        val context = root.context
        val dp = resources.displayMetrics.density
        skip = Button(context, null, 0, R.style.Widget_App_Button).apply {
            id = View.generateViewId()
            background = ContextCompat.getDrawable(context, R.drawable.player_skip_bg)
            val ink = ContextCompat.getColorStateList(context, R.color.player_skip_ink)
            setTextColor(ink)
            compoundDrawableTintList = ink
            minHeight = resources.getDimensionPixelSize(R.dimen.player_skip_height)
            // The glyph's side sits a little tighter: its ink stops short of its box.
            setPaddingRelative(resources.getDimensionPixelSize(R.dimen.button_pad_icon), 0, resources.getDimensionPixelSize(R.dimen.button_pad), 0)
            maxLines = 1
            // TalkBack reads each new offer as it comes, the controls hidden or not.
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            // Up from the bar reaches it and Down goes back; nothing else leads off it.
            nextFocusUpId = id
            nextFocusLeftId = id
            nextFocusRightId = id
            visibility = View.GONE
            setOnClickListener { skipAction?.let(::take) }
            widenSpaces()
        }
        root.addView(skip, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END)
            .apply { marginEnd = (48 * dp).toInt() })
        countdown.setTimeListener { _, _, delta -> count(delta) }
    }

    /**
     * Once a second: the intro and recap are offered from a second into their mark (a seek that lands
     * on its edge doesn't flash the pill) until it ends, the next episode through the credits.
     */
    private fun updateSkip(f: JSONObject, t: Long) {
        if (f !== skipFile) {
            skipFile = f
            autoSkipped.clear()
            offer(0, null)
        }
        val options = f.optJSONObject("options")
        fun mark(key: String) = options?.str(key)?.let(::seconds)
        val introEnd = mark("intro_end")
        val recapStart = mark("previously_start")
        val recapEnd = mark("previously_end")
        // No credits mark: the last [NO_MARK_LEAD_S] stand in for them, so the next episode is still
        // announced with its countdown, and Back still keeps this one to its end.
        val end = f.endMark ?: player.duration.takeIf { it > 0 }?.let { it / 1000 - NO_MARK_LEAD_S }?.takeIf { it > 0 }
        val next = nextFile()
        // Seeking out of its seconds, or past them, ends the offer for good.
        if (startOverMs > 0 && t * 1000 !in startOverMs - 1_000 until startOverMs + START_OVER_MS) startOverMs = 0
        val (label, action) = when {
            startOverMs > 0 -> R.string.player_start_over to {
                startOverMs = 0
                player.seekTo(0)
                player.play()
            }
            introEnd != null && t >= (mark("intro_start") ?: 0) + 1 && t < introEnd -> R.string.skip_intro to {
                player.seekTo(introEnd * 1000)
                player.play()
            }
            recapStart != null && recapEnd != null && t >= recapStart + 1 && t < recapEnd -> R.string.player_skip_recap to {
                player.seekTo(recapEnd * 1000)
                player.play()
            }
            end != null && t >= end && next != null -> R.string.next_episode to { nextEpisode() }
            else -> 0 to null
        }
        skipStartMs = when (label) {
            R.string.player_start_over -> startOverMs
            R.string.skip_intro -> (mark("intro_start") ?: 0) * 1000
            R.string.player_skip_recap -> (recapStart ?: 0) * 1000
            else -> 0
        }
        skipEndMs = when (label) {
            R.string.player_start_over -> startOverMs + START_OVER_MS
            R.string.skip_intro -> (introEnd ?: 0) * 1000
            R.string.player_skip_recap -> (recapEnd ?: 0) * 1000
            else -> 0
        }
        if (label != offered) offer(label, action, next)
        syncCountdown()
    }

    /**
     * Puts [label] on the pill, its glyph beside it (0 takes the pill away). The next episode says
     * which one it is and, with autoplay, counts down 10 s to it; with Settings' auto-skip, an intro
     * or recap takes itself after a 1.5 s sweep that says so (Back keeps it), once per mark and file,
     * so seeking back into one on purpose offers the pill instead.
     */
    private fun offer(label: Int, action: (() -> Unit)?, next: JSONObject? = null) {
        if (label == 0) {
            skipStartMs = 0
            skipEndMs = 0
        }
        stopCountdown()
        offered = label
        skipAction = action
        if (label != 0) {
            val context = requireContext()
            val text = SpannableStringBuilder(getString(label))
            if (label == R.string.next_episode && next != null) {
                val which = getString(R.string.season_episode, next.obj("info")?.optInt("season") ?: 0, next.episodeNumber)
                text.append("\u2003")
                val from = text.length
                text.append(which)
                for (span in listOf(RelativeSizeSpan(0.82f), Face(context.font(if (which.isLatin) R.font.inter_medium else R.font.vazirmatn_medium)), Quiet))
                    text.setSpan(span, from, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            skip.text = text
            val glyph = ContextCompat.getDrawable(context, when (label) {
                R.string.next_episode -> R.drawable.player_skip_next
                R.string.player_start_over -> R.drawable.player_start_over
                else -> R.drawable.player_skip_forward
            })!!
            val size = resources.getDimensionPixelSize(R.dimen.button_icon)
            glyph.setBounds(0, 0, size, size)
            skip.setCompoundDrawablesRelative(glyph, null, null, null)
            when {
                label == R.string.next_episode -> if (autoplays) countdown(NEXT_MS, ::autoplayNext)
                label != R.string.player_start_over && action != null && Settings.autoSkip && autoSkipped.add(label) -> countdown(AUTO_SKIP_MS, action)
            }
        }
        if (view != null) arrange()
    }

    /** The pill's action, at once: the remote goes back to the controls first if it was on the pill. */
    private fun take(action: () -> Unit) {
        if (skip.isFocused) focusControls()
        offer(0, null)
        action()
    }

    private fun countdown(ms: Long, then: () -> Unit) {
        due = ms
        counted = 0
        onDue = then
        syncCountdown()
    }

    private fun stopCountdown() {
        due = 0
        onDue = null
        if (::skip.isInitialized) fill().level = 0
        syncCountdown()
    }

    /** The countdown runs on playing time only: paused, buffering or under the panel, it waits. */
    private fun syncCountdown() {
        if (due == 0L && ::skip.isInitialized) remainingMark()
        val run = (due > 0 || skipEndMs > skipStartMs) && skipSpot != HIDDEN && player.isPlaying
        if (run && !countdown.isStarted) countdown.start() else if (!run && countdown.isStarted) countdown.cancel()
    }

    /** Update the remaining mark or the automatic action's countdown. */
    private fun count(delta: Long) {
        if (due == 0L) {
            remainingMark()
            return
        }
        counted += delta.coerceAtMost(100) // a stalled frame doesn't leap ahead
        fill().level = (counted * 10_000 / due).toInt().coerceAtMost(10_000)
        if (counted >= due) onDue?.let(::take)
    }

    private fun remainingMark() {
        val duration = skipEndMs - skipStartMs
        if (duration > 0) fill().level = ((skipEndMs - player.currentPosition).coerceIn(0, duration) * 10_000 / duration).toInt()
    }

    private fun fill() = (skip.background as LayerDrawable).findDrawableByLayerId(R.id.player_skip_fill)

    /**
     * The remote and the pill. While the controls are hidden, OK presses the pill (white for as long
     * as it is held). Any other key stops a countdown, Back doing nothing else; volume doesn't, and
     * play/pause only pauses it with the video. On the pill the remote is outside Leanback's rows,
     * which then hear nothing: a key keeps the controls up, and Back hides them as it would anywhere else.
     */
    private fun skipKey(event: KeyEvent): Boolean {
        if (offered == 0) return false
        val down = event.action == KeyEvent.ACTION_DOWN
        val code = event.keyCode
        val ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
        if (ok && !isControlsOverlayVisible) {
            skip.isPressed = down
            if (!down) skip.performClick()
            return true
        }
        if (ok && skip.isFocused) return false // the pill's own click
        if (due > 0 && code !in PASSIVE_KEYS) {
            if (code == KeyEvent.KEYCODE_BACK) {
                if (!down) stopCountdown()
                return true
            }
            stopCountdown()
        }
        if (!skip.isFocused) return false
        if (down) tickle()
        if (code != KeyEvent.KEYCODE_BACK) return false
        if (!down) {
            focusControls()
            hideControlsOverlay(true)
        }
        return true
    }

    /** The remote to play/pause, the middle of the primary actions (Leanback's own pick is the seek bar). */
    private fun focusControls() {
        val actions = view?.findViewById<View>(androidx.leanback.R.id.controls_dock)?.findViewById<ViewGroup>(androidx.leanback.R.id.control_bar) ?: return
        actions.getChildAt(actions.childCount / 2)?.requestFocus()
    }

    /**
     * Shows, hides and moves the pill with the controls: at its corner while they are hidden, over
     * them while they show (beside their title, where Up from the row finds it), gone under the panel
     * or the episodes. It moves by its margin, so the remote's search finds it where it looks to be,
     * and a translation from where it was animates the move. Runs every frame, but does nothing
     * unless something changed; true when the subtitles' berth did.
     */
    private fun placeSkip(want: Int): Boolean {
        val root = view as? ViewGroup ?: return false
        val now = if (offered == 0 || panel.isOpen) HIDDEN else want
        val berth = if (now == HIDDEN) 0 else (skip.layoutParams as FrameLayout.LayoutParams).marginEnd + skip.width + (24 * resources.displayMetrics.density).toInt()
        val berthChanged = berth != skipBerth
        skipBerth = berth
        if (now == skipSpot) return berthChanged
        if (now != LIFTED && skip.isFocused) focusControls()
        if (now != HIDDEN) {
            val params = skip.layoutParams as FrameLayout.LayoutParams
            val margin = if (now == LIFTED) {
                // Not laid out yet: the next frame asks again.
                val row = root.findViewById<View>(androidx.leanback.R.id.transport_row)?.takeIf { it.height > 0 } ?: return berthChanged
                root.height - bounds(root, row).top + (LIFT_GAP * resources.displayMetrics.density).toInt()
            } else {
                (root.height * 0.11f).toInt()
            }
            if (skipSpot != HIDDEN && margin != params.bottomMargin) {
                if (!Motion.reduced) skip.translationY += margin - params.bottomMargin
                skip.animate().translationY(0f).setStartDelay(0).setInterpolator(Motion.easeOut)
                    .setDuration(if (now == LIFTED) Motion.ENTER else Motion.EXIT).start()
            }
            params.bottomMargin = margin
            skip.requestLayout()
        }
        if ((now == HIDDEN) != (skipSpot == HIDDEN)) skip.reveal(now != HIDDEN)
        skipSpot = now
        syncCountdown()
        return true
    }

    private fun nextFile() = files.getOrNull(files.indexOf(file) + 1)

    /**
     * The next file's playlist, as [play] would pick it, once this one reaches its credits or its
     * last [NEAR_END_MS] with autoplay on: the read-ahead then loads its opening (Buffering), so
     * the next episode starts without a wait. Read from the same `stream` answer, so it is signed.
     */
    private fun upcoming(): String? {
        val next = nextFile()?.takeIf { autoplays } ?: return null
        val duration = player.duration.takeIf { it > 0 } ?: return null
        val near = minOf(file?.endMark?.times(1000) ?: duration, duration - NEAR_END_MS)
        if (player.currentPosition < near) return null
        val qualities = qualities(next)
        return playlist(next, qualities.getOrNull(pickQuality(qualities)))
    }

    private fun nextEpisode() {
        nextFile()?.let { play(it, 0) }
    }

    private fun previousEpisode() {
        files.getOrNull(files.indexOf(file) - 1)?.let { play(it, 0) }
    }

    // ---- Subtitles: CueView draws them; they move wherever the controls or the panel leave room. ----

    private fun styleIndex(key: String) = prefs.getInt(key, if (key == "sub_bg") 1 else if (key == "sub_size") 2 else 0)

    /** The saved size, colour and box, on the subtitles and on the appearance page's preview alike. */
    private fun style(view: CueView) {
        // The numbers the Settings page's sample uses too.
        view.setTextColor(Settings.subtitleTextColor)
        view.boxColor = Settings.subtitleBoxColor
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.displayMetrics.heightPixels * Settings.subtitleHeightShare)
        val pad = (CueView.PAD * view.textSize).toInt() + 1
        view.setPadding(pad, 0, pad, 0)
    }

    private fun applySubtitleStyle() {
        style(cues)
        // The direction of the chosen language, not of each line's first letter: a Persian line
        // that opens with an English name must still read right to left.
        val picked = finds.added.values.firstOrNull { it.id == finds.picked }
        val language = Locale.forLanguageTag(picked?.language ?: prefs.getString("subtitle", "fa")!!)
        cues.textDirection = if (TextUtils.getLayoutDirectionFromLocale(language) == View.LAYOUT_DIRECTION_RTL) View.TEXT_DIRECTION_RTL else View.TEXT_DIRECTION_LTR
        // The last line's baseline 9% of the screen above its bottom edge: the broadcast and
        // streaming spot, inside the title-safe area.
        (cues.layoutParams as FrameLayout.LayoutParams).bottomMargin = (resources.displayMetrics.heightPixels * 0.09f).toInt() - cues.paint.fontMetricsInt.descent
        cues.requestLayout()
        placeCues(spot)
    }

    /**
     * 30nama's files carry plain cues only (WebVTT's default line, centred), so every cue takes the
     * standard spot, and cues that overlap stack in the order they came. Plain text: embedded
     * styles were off before too.
     * ponytail: explicit `line:`/`position:` settings are ignored; honour top-half ones if a file ever uses them.
     */
    private fun showCues(list: List<Cue>) {
        val text = list.joinToString("\n") { it.text ?: "" }
        cues.text = text
        cues.visibility = if (text.isBlank()) View.INVISIBLE else View.VISIBLE
    }

    /**
     * Keeps the subtitles clear of whatever covers their spot: 10dp over the transport row while the
     * controls show, centered above their title; in the free side of the screen while
     * the panel covers the rest. Runs when one of those changes, never per frame.
     */
    private fun placeCues(want: Int) {
        val root = view as? ViewGroup ?: return
        val params = cues.layoutParams as FrameLayout.LayoutParams
        val width = root.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val dp = resources.displayMetrics.density
        // The span the cue is centred in: the middle 80% at rest, less the skip pill's berth on both
        // sides while it shows, so a long cue wraps short of the pill and keeps its centre.
        var start = width * 0.1f
        var end = width * 0.9f
        var y = 0f
        when (want) {
            REST -> if (skipBerth > 0) {
                start = maxOf(start, skipBerth.toFloat())
                end = minOf(end, width - skipBerth.toFloat())
            }
            LIFTED -> {
                // Not laid out yet (the glue comes with the stream): the next frame asks again.
                val row = root.findViewById<View>(androidx.leanback.R.id.transport_row)?.takeIf { it.height > 0 } ?: return
                val bottom = root.height - params.bottomMargin
                // Where the controls rest, not where their fade has them now.
                val rowTop = bounds(root, row).top
                words.setEmpty()
                for (id in TITLE) root.findViewById<View>(id)?.takeIf { it.isShown && it.width > 0 }?.let { words.union(bounds(root, it)) }
                var top = rowTop.toFloat()
                if (!words.isEmpty) top = minOf(top, words.top.toFloat())
                if (skipBerth > 0) top = minOf(top, rowTop - LIFT_GAP * dp - skip.height)
                y = minOf(0f, top - 10 * dp - bottom)
            }
            // The panel takes the end edge: left in Persian, right in English.
            BESIDE -> if (resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
                start = panel.width + 32 * dp
                end = width - 32 * dp
            } else {
                start = 32 * dp
                end = width - panel.width - 32 * dp
            }
        }
        spot = want
        val span = (end - start).toInt()
        if (params.width != span) {
            params.width = span
            cues.requestLayout()
        }
        cues.animate().translationX((start + end - width) / 2).translationY(y).setInterpolator(Motion.easeOut)
            .setDuration(if (want == REST) Motion.EXIT else Motion.ENTER).start()
    }

    /** [view]'s rectangle in [root]'s coordinates, translations aside. One Rect, reused. */
    private fun bounds(root: ViewGroup, view: View): Rect {
        card.set(0, 0, view.width, view.height)
        root.offsetDescendantRectToMyCoords(view, card)
        return card
    }

    /**
     * The saved subtitle language (or none) and dub. A subtitle found for this file overrides the
     * language with its own track once that has loaded: MergingMediaPeriod prefixes each source's
     * format ids ("2:found0"), so it is known by the id after the colon.
     */
    private fun applyTrackPreferences() {
        val subtitle = prefs.getString("subtitle", "fa")
        val found = finds.picked?.let { id ->
            player.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_TEXT && it.getTrackFormat(0).id?.substringAfterLast(':') == id }
        }
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, retimed || subtitle == "off" && finds.picked == null)
            .setPreferredTextLanguage(subtitle.takeIf { it != "off" })
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .apply { found?.let { setOverrideForType(TrackSelectionOverride(it.mediaTrackGroup, 0)) } }
            .setPreferredAudioLanguage(prefs.getString("dub", null))
            .build()
    }

    // ---- Settings: the panel's pages, and one small function per setting they apply. ----

    /** Hides the controls under the panel, remembering the action that opened it. */
    private fun cover() {
        opener = view?.findFocus()
        hideControlsOverlay(true)
    }

    private fun openPanel(page: () -> Page) {
        cover()
        panel.open(page)
    }

    /** The panel closed: the remote goes back to the action that opened it, the controls with it unless an episode just started. */
    private fun uncover(controls: Boolean) {
        if (controls) tickle()
        if (opener?.requestFocus() != true) focusControls()
        opener = null
    }

    /** Netflix's Audio & Subtitles: the audio and its channels beside the subtitles, where CC lands. */
    private fun audioAndSubtitles(): Page {
        val tracks = audioLanguages()
        val keys = channelKeys()
        val channels = (chosenAudio ?: Settings.audio).takeIf { it in keys } ?: "default"
        val audio = listOfNotNull(
            Group(getString(R.string.audio_language), tracks.mapIndexed { i, group ->
                val format = group.getTrackFormat(0)
                Choice(format.label ?: format.language?.let(::languageName) ?: getString(R.string.player_track, i + 1), group.isSelected) { applyDub(group) }
            }).takeIf { tracks.isNotEmpty() },
            Group(getString(R.string.audio_channels), keys.map { key ->
                val name = if (key == "default") getString(R.string.auto) else getString(R.string.audio_channel_n, key.removeSuffix("ch")).localDigits()
                Choice(name, key == channels) { applyAudio(key) }
            }).takeIf { keys.size > 1 },
        )
        val languages = file?.obj("file", "subtitle")?.keys()?.asSequence()?.toList().orEmpty()
        val current = currentSubtitle()
        val subtitles = listOf(
            Group(getString(R.string.subtitles), listOf(Choice(getString(R.string.subtitle_off), current == "off") { applySubtitle("off") }) +
                languages.map { Choice(languageName(it), it == current) { applySubtitle(it) } } +
                finds.added.values.map { Choice(it.label.orEmpty(), it.id == current) { pickFound(it.id) } }),
            Group(null, listOfNotNull(
                Choice(getString(R.string.subtitle_find), null) { findSubtitles() }.takeIf { wanted() != null },
                Translator.model?.takeIf { canTranslate() }?.let { model ->
                    Choice(getString(R.string.subtitle_translate), null, translateDetail(model)) {
                        translate()
                        panel.push(translatePage)
                    }
                },
                Choice(getString(R.string.subtitle_timing), null, timingText(offset())) { panel.push(::subtitleTiming) }.takeIf { current != "off" },
                Choice(getString(R.string.subtitle_style), null) { panel.push(::appearance) },
            )),
        )
        val columns = listOfNotNull(audio.ifEmpty { null }, subtitles)
        return Page(getString(R.string.player_audio_subtitles), columns, landing = columns.lastIndex)
    }

    /**
     * What shows: a subtitle found for this file while it does, standing in for the saved language;
     * else the saved language, or "off" when this file has no subtitles in it, which is what plays.
     */
    private fun currentSubtitle(): String {
        val languages = file?.obj("file", "subtitle")?.keys()?.asSequence()?.toList().orEmpty()
        return finds.picked ?: prefs.getString("subtitle", "fa").takeIf { it in languages } ?: "off"
    }

    /** Quality beside speed: HQ lands on the quality, the gauge on the speed. */
    private fun qualityAndSpeed(onSpeed: Boolean): Page {
        val video = listOf(Group(getString(R.string.quality), qualities.mapIndexed { i, q ->
            val label = q.str("label")
            Choice(if (label == null || label == "Auto") getString(R.string.auto) else label, i == quality) { applyQuality(label) }
        })).takeIf { qualities.size > 1 }
        val names = resources.getStringArray(R.array.player_speeds)
        val now = player.playbackParameters.speed
        // Alone, the page's title already names the speed.
        val speed = listOf(Group(getString(R.string.player_speed).takeIf { video != null }, SPEEDS.mapIndexed { i, s ->
            Choice(names[i], s == now) { applySpeed(s) }
        }))
        val columns = listOfNotNull(video, speed)
        return Page(getString(if (video != null) R.string.player_quality_speed else R.string.player_speed), columns, landing = if (onSpeed) columns.lastIndex else 0)
    }

    /**
     * Size, colour and box side by side under a live preview: the subtitles' own drawing at their
     * real size over a stand-in for a scene, so each choice shows before the panel closes (and on
     * the real subtitles beside it).
     */
    private fun appearance(): Page {
        val context = requireContext()
        val sample = CueView(context).apply {
            text = getString(R.string.player_sample)
            style(this)
        }
        val scene = FrameLayout(context).apply {
            // Dusk into sand: dark enough for the box to read on, bright enough to test the outline alone.
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF2E3B4E.toInt(), 0xFF6B6A68.toInt(), 0xFFCDB894.toInt()))
                .apply { cornerRadius = resources.getDimension(R.dimen.corner) }
            addView(sample, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        fun column(title: Int, values: Int, key: String) = listOf(Group(getString(title), resources.getStringArray(values).mapIndexed { i, name ->
            Choice(name, i == styleIndex(key)) {
                prefs.edit().putInt(key, i).apply()
                applySubtitleStyle()
                style(sample)
            }
        }))
        return Page(
            getString(R.string.subtitle_style),
            listOf(
                column(R.string.subtitle_size, R.array.subtitle_sizes, "sub_size"),
                column(R.string.subtitle_color, R.array.subtitle_colors, "sub_color"),
                column(R.string.subtitle_background, R.array.subtitle_backgrounds, "sub_bg"),
            ),
            preview = scene,
        )
    }

    /** The HLS variant labelled [label] ("Auto", "720p"…) for this viewing; mid-file the stream reloads where it was. Later titles start from Settings. */
    private fun applyQuality(label: String?) {
        chosenQuality = label
        val index = qualities.indexOfFirst { it.str("label") == label }
        if (index < 0 || index == quality) return
        quality = index
        setSource(player.currentPosition)
    }

    /** A series keeps it, over Settings' (load); a film's holds for this viewing. ExoPlayer keeps it across episodes. */
    private fun applySpeed(speed: Float) {
        player.setPlaybackSpeed(speed)
        if (files.size > 1) prefs.edit().putFloat("speed.$postId", speed).apply()
    }

    /** The audio variant [channels] ("default", "6ch", "2ch"), each its own m3u8, for this viewing. Later titles start from Settings. */
    private fun applyAudio(channels: String) {
        if (channels == (chosenAudio ?: Settings.audio)) return
        chosenAudio = channels
        if (file != null) setSource(player.currentPosition)
    }

    /** One of the stream's audio tracks; its language is the one to prefer from then on. */
    private fun applyDub(group: Tracks.Group) {
        prefs.edit().putString("dub", group.getTrackFormat(0).language).apply()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
            .build()
    }

    /** A subtitle language ("fa", "en"…) or "off", saved for later titles. */
    private fun applySubtitle(language: String) {
        finds.picked = null
        prefs.edit().putString("subtitle", language).apply()
        applyTrackPreferences()
        applySubtitleStyle() // the new language's direction
        retime()
    }

    // ---- Find subtitles: the subtitle sites (Subtitles.kt) searched for exactly what plays, when
    // ---- 30nama's own subtitles are off or missing. What they add holds for this file only. ----

    /** This file's search, its answer (null until it lands), the download under way and the ones that failed, and what each added. */
    private class Finds {
        var search: Job? = null
        var results: Result<List<Subtitles.Found>>? = null
        var download: Job? = null
        var downloading: Subtitles.Found? = null
        val failed = HashMap<Subtitles.Found, Throwable>()
        val added = LinkedHashMap<Subtitles.Found, MediaItem.SubtitleConfiguration>()
        /** The added subtitle showing now, by its configuration's id; null leaves it to the saved language. */
        var picked: String? = null
        /** Persian being made ([translate]): the job, what it is doing while it runs, and why it stopped. */
        var translation: Job? = null
        var translating: String? = null
        var untranslated: Throwable? = null

        fun cancel() {
            search?.cancel()
            download?.cancel()
            translation?.cancel()
        }
    }

    private var finds = Finds()

    /** The search page as one value, so the panel can tell whether it still shows it ([PlayerPanel.shows]). */
    private val findPage: () -> Page = { foundSubtitles() }
    private val subtitlesPage: () -> Page = { audioAndSubtitles() }
    private val translatePage: () -> Page = { translation() }

    /**
     * What plays, as the subtitle sites know it: by the IMDb id 30nama links (a series' own, with
     * this file's season and episode), else by the English name and year. The year comes from the
     * title's info, else from the end of its English name ("Black Clover 2017"), as 30nama writes it.
     * What is known of the file itself ranks what is found: its name, when it has one, and the
     * quality it plays in (on Auto, the best it has, which a TV soon reaches). 30nama's links to
     * SubSource come from the title page (Titles.playerFacts).
     */
    private fun wanted(): Subtitles.Wanted? {
        val title = stream?.optJSONObject("info") ?: return null
        val name = title.englishName.ifEmpty { return null }
        val info = file?.optJSONObject("info")
        val season = if (title.isMovie) 0 else info?.optInt("season") ?: 0
        val sizes = qualities.mapNotNull { q -> q.str("label")?.takeIf { it.any(Char::isDigit) } }
        val size = qualities.getOrNull(quality)?.str("label")?.takeIf { it in sizes } ?: sizes.maxByOrNull { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val release = listOfNotNull(info?.str("title", "english"), size).joinToString(" ")
        return Subtitles.Wanted(title.imdbId, name, year()?.toIntOrNull(), season, file?.episodeNumber ?: 0, release, facts.optJSONArray("subtitle"))
    }

    /** The title's year, wherever the stream's answer keeps it, else the one its English name ends in ("Dune 2021"). */
    private fun year(): String? {
        val title = stream?.optJSONObject("info") ?: return null
        return listOf(facts.str("info", "year"), title.str("info", "year"), title.str("year"), title.str("title", "english")?.let { Regex("\\d{4}$").find(it)?.value })
            .firstOrNull { (it?.toIntOrNull() ?: 0) > 0 }
    }

    /** The search page, searching unless this file's answer is in hand or on its way (a failed one searches again). */
    private fun findSubtitles() {
        if (finds.search?.isActive != true && finds.results?.isSuccess != true) search()
        panel.push(findPage)
    }

    private fun search() {
        val wanted = wanted() ?: return
        val finds = finds
        finds.results = null
        finds.search = lifecycleScope.launch {
            finds.results = runCatching { Subtitles.search(wanted) }.onFailure { if (it is CancellationException) throw it }
            if (panel.shows(findPage)) panel.refresh()
        }
    }

    /**
     * What the subtitle sites have: Persian, then English, each row a release's name (what tells
     * whether it is timed for this file) over its source and, when known, its downloads and whether
     * it is for the hearing impaired. Searching, nothing found and a failure each say so in place;
     * a failure offers to search again.
     */
    private fun foundSubtitles(): Page {
        val title = getString(R.string.subtitle_find)
        val answer = finds.results ?: return Page(title, emptyList(), note = getString(R.string.subtitle_searching), busy = true)
        val results = answer.getOrElse { error ->
            val again = Choice(getString(R.string.subtitle_search_again), false) {
                search()
                panel.refresh()
            }
            return Page(title, listOf(listOf(Group(null, listOf(again)))), note = findError(error))
        }
        if (results.isEmpty()) {
            return Page(title, emptyList(), note = getString(if (wanted()?.isEpisode == true) R.string.subtitle_none_episode else R.string.subtitle_none_film))
        }
        val groups = results.groupBy { it.language }.map { (language, list) ->
            Group(languageName(language), list.map { found ->
                // Dots break nowhere: a zero-width space after each lets a long release name wrap at them.
                Choice(found.release.replace(".", ".\u200B"), finds.added[found]?.let { it.id == finds.picked } == true, detail(found)) { fetch(found) }
            })
        }
        return Page(title, listOf(groups))
    }

    /** A result's quiet line: its source and what else it says of itself, or how its download is going. */
    private fun detail(found: Subtitles.Found): String {
        if (found === finds.downloading) return getString(R.string.subtitle_downloading)
        finds.failed[found]?.let { return findError(it) }
        return dotted(
            found.source,
            found.downloads.takeIf { it > 0 }?.let { resources.getQuantityString(R.plurals.subtitle_downloads, it, it.grouped()) },
            getString(R.string.subtitle_hearing_impaired).takeIf { found.hearingImpaired },
        )
    }

    private fun findError(error: Throwable) = when (error) {
        is Subtitles.Refused -> getString(if (error.quota) R.string.subtitle_error_quota else R.string.subtitle_error_down, error.source)
        is Subtitles.Unreadable -> getString(R.string.subtitle_error_file)
        is IOException -> getString(R.string.subtitle_error_network)
        else -> getString(R.string.subtitle_error_file)
    }

    /**
     * Downloads [found] (one at a time: a second pick replaces the first) and plays it from where the
     * video is. The panel then steps back to Audio & Subtitles, where it shows as the current
     * choice, the remote on it. A failure says why on its row.
     */
    private fun fetch(found: Subtitles.Found) {
        val finds = finds
        finds.added[found]?.let {
            pickFound(it.id)
            panel.back()
            return
        }
        val episode = wanted()?.episode ?: 0
        val dir = File(requireContext().cacheDir, "subtitles")
        finds.download?.cancel()
        finds.failed.remove(found)
        finds.downloading = found
        panel.refresh()
        finds.download = lifecycleScope.launch {
            // ponytail: the files stay in the cache dir for Android to clear; delete them per viewing if it ever fills.
            val got = runCatching { Subtitles.download(found, episode, dir) }.onFailure { if (it is CancellationException) throw it }
            finds.downloading = null
            got.onSuccess { add(found, it) }.onFailure { finds.failed[found] = it }
            if (!panel.shows(findPage)) return@launch
            if (got.isSuccess) panel.back() else panel.refresh()
        }
    }

    /**
     * [file] beside 30nama's subtitles, labelled with its language and site («فارسی از SubDL», a
     * number after a second one), the item re-set where it plays (setSource), and showing, moved by
     * [offset] as the subtitle it was made from was.
     */
    private fun add(found: Subtitles.Found, file: File, offset: Long = 0L) {
        val name = getString(R.string.subtitle_found, languageName(found.language), found.source)
        val same = finds.added.values.count { it.label?.startsWith(name) == true }
        val config = MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(file))
            .setMimeType(Subtitles.mimeType(file))
            .setLanguage(found.language)
            .setLabel(if (same == 0) name else "$name ${(same + 1).toString().localDigits()}")
            .setId("found${finds.added.size}")
            .build()
        finds.added[found] = config
        finds.picked = config.id
        if (offset != 0L) offsets[timingKey(config)] = offset
        setSource(player.currentPosition)
        applySubtitleStyle() // its language's direction
    }

    /** A subtitle found for this file, from the Subtitles column. Not saved: the next file starts from the saved language. */
    private fun pickFound(id: String?) {
        finds.picked = id
        applyTrackPreferences()
        applySubtitleStyle()
        retime()
    }

    // ---- Make Persian subtitles: an English one, 30nama's or the subtitle sites', translated by the
    // ---- model the viewer keyed (Translator.kt). Kept on disk, so making it again costs nothing. ----

    /** While a model has a key, there is English to start from, and this file has no Persian made yet. */
    private fun canTranslate() = finds.added.keys.none { it.ref == TRANSLATED } && (englishSource() != null || wanted() != null)

    /** The row's quiet line: which model translates, how far it has got, or why it stopped. */
    private fun translateDetail(model: Translator.Model) =
        finds.untranslated?.let { translateError(it, model) } ?: finds.translating ?: getString(R.string.subtitle_translate_by, model.name)

    /**
     * Where the making stands, beside a spinner, over Keep watching (under the remote, saying it comes
     * on by itself); a failure says why and offers to try again.
     */
    private fun translation(): Page {
        val title = getString(R.string.subtitle_translate)
        val model = Translator.model ?: return Page(title, emptyList())
        finds.untranslated?.let { error ->
            val again = Choice(getString(R.string.subtitle_translate_again), false) {
                translate()
                panel.refresh()
            }
            return Page(title, listOf(listOf(Group(null, listOf(again)))), note = translateError(error, model))
        }
        val watch = Choice(getString(R.string.subtitle_translate_watch), false, getString(R.string.subtitle_translate_wait)) { panel.close(controls = false) }
        return Page(title, listOf(listOf(Group(null, listOf(watch)))), note = finds.translating, busy = true)
    }

    /**
     * Makes this file's Persian, unless it is on its way: the English found, parsed and sent in parts
     * ([Translator]), the Persian written beside the other found files and played from where the video
     * is, as a found one is. The page steps back to Audio & Subtitles, where it shows checked; a viewer
     * who went back to the film is told in a notice either way.
     */
    private fun translate() {
        val model = Translator.model ?: return
        val finds = finds
        if (finds.translation?.isActive == true) return
        finds.untranslated = null
        finds.translating = getString(R.string.subtitle_translate_finding)
        finds.translation = lifecycleScope.launch {
            val made = runCatching {
                val source = englishSource() ?: englishFromSites()
                val lines = Translator.lines(cuesOf(source))
                if (lines.isEmpty()) throw Subtitles.Unreadable()
                val dir = File(requireContext().cacheDir, "subtitles").apply { mkdirs() }
                // The same English by the same model is the same Persian: a second viewing, or a retry, is free.
                val made = File(dir, "${model.provider.id}-${(model.id + Translator.srt(lines, lines.map { it.text })).hashCode().toUInt()}.srt")
                if (!made.exists()) {
                    showTranslating(finds, 0f)
                    // ponytail: a failure loses the parts already done, and a retry pays for them again; keep them per file if failures prove common.
                    val persian = Translator.translate(model, lines, about()) { showTranslating(finds, it) }
                    // Whole or not at all: a film cut off mid-write must not pass for made next time.
                    withContext(Dispatchers.IO) { File(dir, "${made.name}.part").apply { writeText(Translator.srt(lines, persian)) }.renameTo(made) }
                }
                source to made
            }.onFailure { if (it is CancellationException) throw it }
            finds.translating = null
            made.onSuccess { (source, file) -> add(Subtitles.Found(model.provider.id, "fa", "", 0, false, TRANSLATED), file, offsets[timingKey(source)] ?: 0L) }
                .onFailure { finds.untranslated = it }
            when {
                panel.shows(translatePage) -> if (made.isSuccess) panel.back() else panel.refresh()
                else -> {
                    if (panel.shows(subtitlesPage)) panel.refresh()
                    requireContext().toast(made.fold({ getString(R.string.subtitle_translated) }, { translateError(it, model) }))
                }
            }
        }
    }

    private fun showTranslating(finds: Finds, done: Float) {
        finds.translating = getString(R.string.subtitle_translating, (done * 100).toInt().toString().localDigits())
        if (panel.shows(translatePage) || panel.shows(subtitlesPage)) panel.refresh()
    }

    /** The English to translate: the one showing, else 30nama's, else one found for this file; null with none. */
    private fun englishSource(): MediaItem.SubtitleConfiguration? {
        val all = file?.let(::ownSubtitles).orEmpty() + finds.added.values
        return shownSubtitle()?.takeIf { it.language == "en" } ?: all.firstOrNull { it.language == "en" }
    }

    /**
     * English from the subtitle sites, from this file's search if it has one: SubDL's first, one not
     * for the hearing impaired ahead (their sound labels are lines to read), the next if one won't come.
     */
    private suspend fun englishFromSites(): MediaItem.SubtitleConfiguration {
        val wanted = wanted() ?: throw Translator.NoSource()
        val english = (finds.results?.getOrNull() ?: Subtitles.search(wanted)).filter { it.language == "en" }.sortedBy { it.hearingImpaired }
        var failure: Throwable = Translator.NoSource()
        for (found in english.take(3)) {
            val file = runCatching { Subtitles.download(found, wanted.episode, File(requireContext().cacheDir, "subtitles")) }
                .onFailure { if (it is CancellationException) throw it else failure = it }
                .getOrNull() ?: continue
            return MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(file)).setMimeType(Subtitles.mimeType(file)).setLanguage("en").build()
        }
        throw failure
    }

    /** What plays, for the model: the English name and year, the Persian one, the episode, the story. */
    private fun about(): Translator.About {
        val title = stream?.optJSONObject("info")
        val wanted = wanted()
        return Translator.About(
            wanted?.name ?: title?.englishName.orEmpty(),
            persianName,
            wanted?.year,
            wanted?.takeIf { it.isEpisode }?.let { "season ${it.season}, episode ${it.episode}" },
            title?.let { it.str("plot", "english") ?: it.str("plot", "local") }?.unescaped(),
        )
    }

    private fun translateError(error: Throwable, model: Translator.Model): String = when (error) {
        is Translator.Refused -> getString(when (error.why) {
            Translator.Why.KEY -> R.string.subtitle_translate_key
            Translator.Why.QUOTA -> R.string.subtitle_translate_quota
            Translator.Why.REGION -> R.string.subtitle_translate_region
            Translator.Why.BLOCKED -> R.string.subtitle_translate_blocked
            Translator.Why.DOWN -> R.string.subtitle_translate_down
        }, error.provider)
        is Translator.NoSource -> getString(R.string.subtitle_translate_none)
        is Subtitles.Refused, is Subtitles.Unreadable -> findError(error)
        is IOException -> getString(R.string.subtitle_translate_network, model.provider.id)
        else -> getString(R.string.subtitle_error_file)
    }

    // ---- Subtitle timing: the shown subtitle moved earlier or later, for a file timed to another
    // ---- cut. Media3 has no offset, and its renderer shows no cue before its time, so a moved
    // ---- subtitle is drawn from here instead, against the playhead. ----

    /** Offsets (ms, later is positive) for this viewing, by [timingKey]; none leaves the subtitle to ExoPlayer. 30nama's own are also kept per title ([timingPref]). */
    private val offsets = HashMap<String, Long>()
    /** The subtitle [timing] is loading or following, by [timingKey]. */
    private var timingFor: String? = null
    private var timing: Job? = null
    /** ExoPlayer's text track is off and [timing] draws the cues. */
    private var retimed = false

    /** An offset belongs to a subtitle on one file: the same season's zip found for two episodes is timed for each. */
    private fun timingKey(subtitle: MediaItem.SubtitleConfiguration) = "${file?.str("info", "file_id")} ${subtitle.uri}"

    /**
     * Where a title keeps the offset of one of 30nama's own subtitles, by language: its files' subtitles
     * come from one source, so one shift holds across them. A found one is for its file alone.
     */
    // ponytail: a key per title ever shifted, a few bytes each, as Watched.leave keeps.
    private fun timingPref(own: MediaItem.SubtitleConfiguration) = "timing.$postId.${own.id}"

    /** The subtitle showing, as ExoPlayer was given it; null with none. */
    private fun shownSubtitle(): MediaItem.SubtitleConfiguration? {
        val f = file ?: return null
        val id = currentSubtitle()
        return (ownSubtitles(f) + finds.added.values).firstOrNull { it.id == id }
    }

    private fun offset() = shownSubtitle()?.let { offsets[timingKey(it)] } ?: 0L

    /**
     * The shown subtitle at its offset. At none ExoPlayer draws it, exactly as before there was an
     * offset. Otherwise its file is read and parsed once ([cuesOf]), ExoPlayer's text track goes off,
     * and its cues follow the playhead from here, the offset read afresh each tick, so a step shows
     * at once: no reload, no re-buffer. Called whenever the subtitle or its offset may have changed.
     */
    private fun retime() {
        val subtitle = shownSubtitle() ?: return untime()
        val key = timingKey(subtitle)
        if ((offsets[key] ?: 0L) == 0L) return untime()
        if (key == timingFor) return // loading or following it already
        untime()
        timingFor = key
        timing = lifecycleScope.launch {
            val timed = runCatching { cuesOf(subtitle) }.getOrElse {
                if (it is CancellationException) throw it
                // It stays where ExoPlayer draws it, and the panel shows it unmoved.
                offsets.remove(key)
                timingFor = null
                requireContext().toast(getString(R.string.subtitle_timing_error))
                panel.refresh()
                return@launch
            }
            retimed = true
            applyTrackPreferences()
            // ponytail: a 50 ms tick shows a cue up to 50 ms late; follow frames (postOnAnimation) if anyone ever sees it.
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var shown: List<Cue>? = null
                while (true) {
                    val now = cuesAt(timed, player.currentPosition * 1000, (offsets[key] ?: 0L) * 1000)
                    if (now != shown) showCues(now)
                    shown = now
                    delay(50)
                }
            }
        }
    }

    /** Back to ExoPlayer's drawing, which brings the current cue back with the track. */
    private fun untime() {
        timing?.cancel()
        timing = null
        timingFor = null
        if (!retimed) return
        retimed = false
        showCues(emptyList())
        applyTrackPreferences()
    }

    /**
     * [subtitle]'s cues on its own clock, parsed as ExoPlayer parses them (Subtitles.Parsers): a
     * found one from disk, 30nama's from its URL as ExoPlayer fetched it. Both through Subtitles'
     * decode, so a Persian file in Windows-1256 reads right here too.
     */
    private suspend fun cuesOf(subtitle: MediaItem.SubtitleConfiguration): List<CuesWithTiming> {
        val uri = subtitle.uri
        val bytes = if (uri.scheme == "file") withContext(Dispatchers.IO) { File(uri.path!!).readBytes() }
        else Api.run { http.newCall(Request.Builder().url(uri.toString()).header("User-Agent", userAgent).build()).await() }.use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            withContext(Dispatchers.IO) { response.body!!.bytes() }
        }
        return withContext(Dispatchers.Default) {
            val text = Subtitles.decode(bytes, subtitle.language.orEmpty())
            val parser = Subtitles.Parsers().create(Format.Builder().setSampleMimeType(subtitle.mimeType).build())
            buildList { parser.parse(text.toByteArray(), SubtitleParser.OutputOptions.allCues()) { add(it) } }
        }
    }

    /**
     * Earlier and Later move the shown subtitle a quarter second a press (OK again for the next),
     * each saying when it is the one to press; the subtitles beside the panel show every step as it
     * lands, and the note what it adds up to. Once moved, a third row puts it back. Its own page, not
     * Left and Right on the Subtitles row: those move between the panel's columns.
     */
    private fun subtitleTiming(): Page {
        val offset = offset()
        val steps = Group(null, listOf(
            Choice(getString(R.string.subtitle_earlier), false, getString(R.string.subtitle_earlier_hint)) { nudge(-TIMING_STEP_MS) },
            Choice(getString(R.string.subtitle_later), false, getString(R.string.subtitle_later_hint)) { nudge(TIMING_STEP_MS) },
        ))
        val reset = Group(null, listOf(Choice(getString(R.string.subtitle_timing_none), false) { nudge(0) })).takeIf { offset != 0L }
        return Page(getString(R.string.subtitle_timing), listOf(listOfNotNull(steps, reset)), note = timingText(offset))
    }

    /** Moves the shown subtitle [by] ms, up to [TIMING_MAX_MS] either way; 0 puts it back on its own times. */
    private fun nudge(by: Long) {
        shownSubtitle()?.let { subtitle ->
            val key = timingKey(subtitle)
            val offset = if (by == 0L) 0L else ((offsets[key] ?: 0L) + by).coerceIn(-TIMING_MAX_MS, TIMING_MAX_MS)
            if (offset == 0L) offsets.remove(key) else offsets[key] = offset
            // Not picked from the sites, it is one of 30nama's own (currentSubtitle).
            if (finds.picked == null) prefs.edit().apply { if (offset == 0L) remove(timingPref(subtitle)) else putLong(timingPref(subtitle), offset) }.apply()
            retime()
        }
        panel.refresh()
    }

    /** «۰٫۵ ثانیه زودتر», "0.25 s later", or no shift: words for the direction, never a sign (a Persian line would carry it to the wrong end). */
    private fun timingText(offset: Long): String {
        if (offset == 0L) return getString(R.string.subtitle_timing_none)
        val seconds = "%.2f".format(Locale.US, abs(offset) / 1000.0).trimEnd('0').trimEnd('.')
        val number = if (Api.language == "fa") seconds.replace('.', '٫').persianDigits() else seconds
        return getString(if (offset < 0) R.string.subtitle_timing_earlier else R.string.subtitle_timing_later, number)
    }

    // ---- Sleep timer: playback pauses at the end of this episode or film, or after a set time,
    // ---- and the screensaver may come (see onIsPlayingChanged). For this viewing only. ----

    /** [SLEEP_END], the minutes it was set for, or 0 for none; [sleepAt]: when a timed one pauses (elapsedRealtime). */
    private var sleep = 0
    private var sleepAt = 0L

    /** Off, the end of what plays, then 15 to 60 minutes; a running one says how long it has left. */
    private fun sleepTimer(): Page {
        val left = ((sleepAt - SystemClock.elapsedRealtime() + 59_999) / 60_000).toInt().coerceAtLeast(1)
        val choices = listOf(
            Choice(getString(R.string.sleep_off), sleep == 0) { setSleep(0) },
            Choice(getString(if (files.size > 1) R.string.sleep_end_episode else R.string.sleep_end_film), sleep == SLEEP_END) { setSleep(SLEEP_END) },
        ) + SLEEP_MINUTES.map { minutes ->
            val running = sleep == minutes && sleepAt > 0
            Choice(resources.getQuantityString(R.plurals.sleep_minutes, minutes, minutes), sleep == minutes,
                getString(R.string.time_left, getString(R.string.runtime_minutes, left)).takeIf { running }) { setSleep(minutes) }
        }
        return Page(getString(R.string.sleep_timer), listOf(listOf(Group(null, choices))))
    }

    /** Picking a time again starts it over; the page shows what it has left. */
    private fun setSleep(choice: Int) {
        sleep = choice
        sleepAt = if (choice > 0) SystemClock.elapsedRealtime() + choice * 60_000L else 0L
        // The end of this episode holds autoplay back, its countdown on the pill included.
        if (offered == R.string.next_episode) offer(0, null)
        panel.refresh()
    }

    /** The time is up: playback pauses, and says why to anyone still awake. Already paused, it just ends. */
    private fun fallAsleep() {
        sleep = 0
        sleepAt = 0
        if (player.playWhenReady) {
            player.pause()
            requireContext().toast(getString(R.string.sleep_paused))
        }
        panel.refresh()
    }

    /** Besides "label", each quality maps audio variants ("default", channel counts) to URLs. */
    private fun channelKeys() = qualities.getOrNull(quality)?.keys()?.asSequence()?.filter { it != "label" }?.toList().orEmpty()

    private fun audioLanguages() = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }

    /**
     * The series' episodes along the bottom, on the current season with the current episode focused.
     * Playback goes on behind them, as Netflix's does.
     */
    private fun openEpisodes() {
        val playing = file ?: return
        val context = requireContext()
        val seasons = files.groupBy { it.obj("info")?.optInt("season") ?: 0 }.toSortedMap()
        // The tile being watched says so, its red bar where playback is now.
        val watching = JSONObject().put("progress", player.duration.takeIf { it > 0 }?.let { player.currentPosition * 100 / it } ?: 0L)
        val shelves = seasons.map { (season, list) ->
            getString(R.string.season_n, season) to list.map { f ->
                context.episodeCard(f, watching.takeIf { f === playing }, art = stream?.cover) {
                    panel.close(controls = f === playing)
                    if (f !== playing) play(f, 0)
                }
            }
        }
        val season = seasons.keys.indexOf(playing.obj("info")?.optInt("season") ?: 0)
        cover()
        panel.episodes(getString(R.string.episodes), shelves, season, seasons.values.elementAt(season).indexOf(playing))
    }

    private fun languageName(code: String) =
        Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault()).ifEmpty { code }

    /**
     * What stopped playback, and what to do about it, in place of ExoPlayer's own (English, for
     * developers) message. Play re-prepares a failed player (LeanbackPlayerAdapter, PlayerSession),
     * so most of them end there; a file that won't parse or decode may in another quality.
     */
    private fun errorMessage(code: Int) = when (code) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> R.string.player_error_network
        // A 403 still there after Retry403's ten tries, or a file gone from the CDN.
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> R.string.player_error_server
        in 3000..4999 -> if (qualities.size > 1) R.string.player_error_format else R.string.player_error // parsing (3xxx), decoding (4xxx)
        else -> R.string.player_error
    }

    private companion object {
        /** A made Persian subtitle's [Subtitles.Found.ref]: one per file. */
        const val TRANSLATED = "translated"
        const val REST = 0
        const val LIFTED = 1
        const val BESIDE = 2
        const val HIDDEN = -1
        /** The next episode's countdown, and auto-skip's sweep before it takes an intro or recap. */
        const val NEXT_MS = 10_000L
        const val AUTO_SKIP_MS = 1_500L
        /** How long a title picked up part-way offers its start, in playing time. */
        const val START_OVER_MS = 10_000L
        /** The connection's speed (bps) a TV's first viewing assumes until its segments measure it. */
        const val INITIAL_ESTIMATE = 6_000_000L
        /** Without a credits mark, how long before the end (s) the next episode is offered and counted down to. */
        const val NO_MARK_LEAD_S = 25L
        /** The sleep timer's choices past off and the end of this episode, in minutes; [SLEEP_END] is that end. */
        val SLEEP_MINUTES = intArrayOf(15, 30, 45, 60)
        const val SLEEP_END = -1
        /** How long before the end the next episode's opening starts loading, without a credits mark or before a late one. */
        const val NEAR_END_MS = 120_000L
        /** The seek bar's step for a press, and the pause after the last one before the seek lands. */
        const val SEEK_STEP_MS = 5_000L
        const val SEEK_LAND_MS = 1_000L
        /** Keys that leave a countdown running. */
        val PASSIVE_KEYS = intArrayOf(
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
        )
        /** Lifted, the pill's bottom stands this far (dp) over the transport row: level with the title's foot. */
        const val LIFT_GAP = 20
        /** Subtitle timing's step, and its reach either way. */
        const val TIMING_STEP_MS = 250L
        const val TIMING_MAX_MS = 10_000L
        /** In step with the player_speeds labels. */
        val SPEEDS = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        /** The title, its Persian name, the episode line and the poster beside them, which lifted subtitles clear vertically. */
        val TITLE = intArrayOf(androidx.leanback.R.id.lb_details_description_title, androidx.leanback.R.id.lb_details_description_subtitle, androidx.leanback.R.id.lb_details_description_body, androidx.leanback.R.id.image)
    }

    private object Retry403 : DefaultLoadErrorHandlingPolicy() {
        private fun LoadErrorHandlingPolicy.LoadErrorInfo.is403() =
            (exception as? HttpDataSource.InvalidResponseCodeException)?.responseCode == 403

        override fun getFallbackSelectionFor(options: LoadErrorHandlingPolicy.FallbackOptions, info: LoadErrorHandlingPolicy.LoadErrorInfo) =
            if (info.is403()) null else super.getFallbackSelectionFor(options, info)

        override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            if (!info.is403()) return super.getRetryDelayMsFor(info)
            Buffering.cdn.connectionPool.evictAll() // the failed connection is idle again by now
            return 200L
        }

        // ponytail: 10 tries makes a 50/50 egress all but certain to land; raise it if a network is worse.
        override fun getMinimumLoadableRetryCount(dataType: Int) = 10
    }

    private inner class Glue(context: Context, adapter: LeanbackPlayerAdapter, private val series: Boolean) :
        PlaybackTransportControlGlue<LeanbackPlayerAdapter>(context, adapter) {
        private val rewind = PlaybackControlsRow.RewindAction(context)
        private val forward = PlaybackControlsRow.FastForwardAction(context)
        private val previous = PlaybackControlsRow.SkipPreviousAction(context)
        private val next = PlaybackControlsRow.SkipNextAction(context)
        // CC opens Audio & Subtitles; HQ and the gauge open Quality & Speed on their own column. All
        // four in the player's one outline (player_*), not Leanback's filled CC and HQ badges.
        private val captions = Action(2, context.getString(R.string.player_audio_subtitles), null, ContextCompat.getDrawable(context, R.drawable.player_subtitles))
        private val hq = Action(3, context.getString(R.string.quality), null, ContextCompat.getDrawable(context, R.drawable.player_quality))
        private val speed = Action(0, context.getString(R.string.player_speed), null, ContextCompat.getDrawable(context, R.drawable.player_speed))
        private val episodes = Action(1, context.getString(R.string.episodes), null, ContextCompat.getDrawable(context, R.drawable.player_episodes))
        private val sleeper = Action(4, context.getString(R.string.sleep_timer), null, ContextCompat.getDrawable(context, R.drawable.player_sleep))

        /** The glue's row and description, including its key listener and title layout. */
        override fun onCreateRowPresenter(): PlaybackRowPresenter = object : PlaybackTransportRowPresenter() {
            override fun createRowViewHolder(parent: ViewGroup): RowPresenter.ViewHolder = super.createRowViewHolder(parent).also {
                dress(it.view)
                it.view.post(::focusControls) // once attached: the first controls start on play/pause too
            }

            override fun getDefaultSeekIncrement() = player.duration.takeIf { it > 0 }?.let { seekStep().toFloat() / it } ?: super.getDefaultSeekIncrement()

            override fun onBindRowViewHolder(vh: RowPresenter.ViewHolder, item: Any) {
                super.onBindRowViewHolder(vh, item)
                val title = vh.view.findViewById<TextView>(androidx.leanback.R.id.lb_details_description_title)
                (title.layoutParams as ViewGroup.MarginLayoutParams).takeIf { it.topMargin < 0 }?.let {
                    it.topMargin = 0
                    title.layoutParams = it
                }
                vh.onKeyListener = this@Glue
            }

            override fun onUnbindRowViewHolder(vh: RowPresenter.ViewHolder) {
                super.onUnbindRowViewHolder(vh)
                vh.onKeyListener = null
            }
        }.apply {
            setDescriptionPresenter(object : AbstractDetailsDescriptionPresenter() {
                override fun onBindDescription(vh: AbstractDetailsDescriptionPresenter.ViewHolder, item: Any) {
                    vh.title.typeface = context.font(if (title?.isLatin != false) R.font.inter_bold else R.font.peyda_bold)
                    vh.title.includeFontPadding = true
                    vh.title.text = title?.let { latin(context, it, R.font.inter_bold) }
                    vh.subtitle.text = persianName
                    vh.body.text = subtitle // Meta.metaLine's own faces
                }
            })
        }

        /**
         * The row is laid out in lb_playback_transport_controls_row: the clock under the bar's ends,
         * left to right as the bar fills, and every button in one row under it, YouTube's. In Persian
         * the clock takes Persian digits.
         */
        private fun dress(row: View) {
            // Leanback's bars widen themselves for their gaps only after measuring, so packed from the
            // right one ran past its edge. Packed from the left each fills its width exactly, the
            // panels' still in the language's order.
            for (id in intArrayOf(androidx.leanback.R.id.controls_dock, androidx.leanback.R.id.secondary_controls_dock)) {
                val bar = row.findViewById<ViewGroup>(id).getChildAt(0)
                (bar.layoutParams as FrameLayout.LayoutParams).gravity = Gravity.LEFT
                (bar as? LinearLayout)?.gravity = Gravity.LEFT
            }
            if (Api.language == "fa") {
                for (id in intArrayOf(androidx.leanback.R.id.current_time, androidx.leanback.R.id.total_time))
                    row.findViewById<TextView>(id).transformationMethod = PersianDigits
            }
        }

        override fun onCreatePrimaryActions(adapter: ArrayObjectAdapter) {
            if (series) adapter.add(previous)
            adapter.add(rewind)
            super.onCreatePrimaryActions(adapter) // play / pause
            adapter.add(forward)
            if (series) adapter.add(next)
        }

        /**
         * YouTube's loaded band: past the red, as far as playback could go on without the connection,
         * the player's memory or the read-ahead's run on disk (Buffering). Twice a second while it
         * plays; paused, Leanback stops asking and [tick] keeps it filling.
         */
        override fun onUpdateBufferedProgress() {
            super.onUpdateBufferedProgress() // the player's own, which the run on disk may then extend
            controlsRow?.bufferedPosition = Buffering.bufferedPosition(player)
        }

        override fun onCreateSecondaryActions(adapter: ArrayObjectAdapter) {
            adapter.add(captions)
            adapter.add(hq)
            adapter.add(speed)
            adapter.add(sleeper)
            if (series) adapter.add(episodes)
        }

        override fun onActionClicked(action: Action) {
            when (action) {
                rewind -> player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0))
                forward -> {
                    player.seekTo(player.currentPosition + 10_000)
                    player.play()
                }
                previous -> session.seekToPrevious() // the start first, as the remote's previous
                next -> nextEpisode()
                captions -> openPanel(subtitlesPage)
                hq -> openPanel { qualityAndSpeed(onSpeed = false) }
                speed -> openPanel { qualityAndSpeed(onSpeed = true) }
                sleeper -> openPanel(::sleepTimer)
                episodes -> openEpisodes()
                else -> super.onActionClicked(action)
            }
        }
    }
}

/**
 * Leanback's clock in Persian digits, as the rest of the Persian UI writes numbers. It formats the
 * times itself (and privately), so they change on screen only; Yekan Bakh's tnum covers these too.
 */
private object PersianDigits : ReplacementTransformationMethod() {
    private val latin = "0123456789".toCharArray()
    private val persian = "۰۱۲۳۴۵۶۷۸۹".toCharArray()
    override fun getOriginal() = latin
    override fun getReplacement() = persian
}

/** The next episode's number, a step back from «قسمت بعد» in whatever colour the pill's label has. */
/**
 * The shape to fit the video surface to, from the [decoder]'s picture and the [stream]'s own (HLS
 * takes it from the playlist). Some TV decoders report a padded or their largest picture, so a
 * 2.39:1 film comes as 16:9 and fills the screen stretched (issue #2, a Hisense): where the decoder
 * says 16:9 and the stream another shape, the stream's wins. Otherwise the decoder's, as before: a
 * playlist that says 16:9 for every film is the likelier mistake the other way.
 */
internal fun filmShape(decoder: Pair<Int, Int>, stream: Pair<Int, Int>?): Pair<Int, Int> {
    fun sixteenNine(s: Pair<Int, Int>) = abs(s.first * 9 - s.second * 16) <= s.second * 16 / 50 // within 2%
    return if (stream != null && stream.first > 0 && stream.second > 0 && sixteenNine(decoder) && !sixteenNine(stream)) stream else decoder
}

private object Quiet : CharacterStyle() {
    override fun updateDrawState(paint: TextPaint) {
        paint.alpha = paint.alpha * 7 / 10
    }
}
