package com.doxigo.tv30

import android.animation.TimeAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Bundle
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
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
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
import androidx.leanback.widget.PlaybackSeekUi
import androidx.leanback.widget.PlaybackTransportRowPresenter
import androidx.leanback.widget.RowPresenter
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
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
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.datasource.HttpDataSource
import androidx.media3.common.text.Cue
import androidx.media3.ui.leanback.LeanbackPlayerAdapter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class PlayerActivity : TvActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // no screensaver mid-film
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(android.R.id.content, PlayerFragment()).commit()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val player = supportFragmentManager.findFragmentById(android.R.id.content) as? PlayerFragment
        return player?.onKey(event) == true || super.dispatchKeyEvent(event)
    }

    companion object {
        fun intent(context: Context, id: Int, free: Boolean, fileId: String? = null): Intent =
            Intent(context, PlayerActivity::class.java).putExtra("id", id).putExtra("free", free).putExtra("file", fileId)
    }
}

/**
 * Plays one title's `stream` answer: an `hls` list with one entry per file (a film has one,
 * a series one per episode). Each entry carries its qualities, subtitles and the intro/recap
 * marks; the server tracks progress through `observer` every 15 s, as the official player does.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlayerFragment : VideoSupportFragment() {
    private lateinit var player: ExoPlayer
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

    private var postId = 0
    private var stream: JSONObject? = null
    private var files: List<JSONObject> = emptyList()
    private var file: JSONObject? = null
    private var qualities: List<JSONObject> = emptyList()
    private var quality = 0
    /** Quality and audio picked in the panel: they hold for this viewing, episodes included, over Settings' defaults. */
    private var chosenQuality: String? = null
    private var chosenAudio: String? = null
    private var reportedAt = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val context = requireContext()
        Settings.init(context) // a player restored after process death never passed through home
        player = ExoPlayer.Builder(context, DefaultRenderersFactory(context).setEnableDecoderFallback(true))
            // A heap-sized buffer in memory, the disk read-ahead behind it, and the player first on
            // the connection whenever it loads (see Buffering).
            .setLoadControl(Buffering.loadControl())
            .setPriorityTaskManager(Buffering.priority)
            .setAudioAttributes(AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).setUsage(C.USAGE_MEDIA).build(), true)
            .setMediaSourceFactory(DefaultMediaSourceFactory(Buffering.dataSource(context)).setLoadErrorHandlingPolicy(Retry403))
            .build()
        player.setPlaybackSpeed(Settings.speed) // the panel's speed then holds for this viewing
        applyTrackPreferences()
        session = PlayerSession(context, player, ::previousEpisode, ::nextEpisode)
        player.addListener(object : Player.Listener {
            override fun onCues(cueGroup: CueGroup) = showCues(cueGroup.cues)
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && autoplays) autoplayNext()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
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
            repeatOnLifecycle(Lifecycle.State.STARTED) { Buffering.prefetch(context, player) }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val root = view as ViewGroup
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
        // the brand's lilac, as the intro's is; the medium ring at 32dp draws the intro's 2.7dp line.
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
     * play state change, a tickle), so that is refused here rather than undone after.
     */
    override fun showControlsOverlay(runAnimation: Boolean) {
        if (!::panel.isInitialized || !panel.isOpen) super.showControlsOverlay(runAnimation)
    }

    override fun setPlaybackSeekUiClient(client: PlaybackSeekUi.Client?) {
        super.setPlaybackSeekUiClient(client?.let { delegate ->
            object : PlaybackSeekUi.Client() {
                private var startedAt = 0L
                private var requestedAt = 0L
                override fun isSeekEnabled() = delegate.isSeekEnabled
                override fun getPlaybackSeekDataProvider() = delegate.playbackSeekDataProvider
                override fun onSeekStarted() {
                    startedAt = player.currentPosition
                    requestedAt = startedAt
                    delegate.onSeekStarted()
                }
                override fun onSeekPositionChanged(positionMs: Long) {
                    requestedAt = positionMs
                    delegate.onSeekPositionChanged(positionMs)
                }
                override fun onSeekFinished(cancelled: Boolean) {
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

    // A paused ExoPlayer keeps buffering; off screen it must not hold a connection to the CDN.
    override fun onStop() {
        super.onStop()
        player.stop()
    }

    override fun onStart() {
        super.onStart()
        if (file != null && player.playbackState == Player.STATE_IDLE) player.prepare()
    }

    override fun onDestroy() {
        super.onDestroy()
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
        return skipKey(event) || session.onKey(event).also { if (it && event.action == KeyEvent.ACTION_DOWN) tickle() }
    }

    private suspend fun load() {
        val args = requireActivity().intent
        postId = args.getIntExtra("id", 0)
        val loaded = runCatching { Api.stream(postId, args.getBooleanExtra("free", false)) }
        val context = context ?: return // left while loading
        val stream = loaded.getOrElse {
            context.toast(it)
            requireActivity().finish()
            return
        }
        this.stream = stream
        files = stream.optJSONArray("hls").objects()
        val resume = stream.optJSONObject("continue_watching")
        val chosen = args.getStringExtra("file")
        val wanted = chosen ?: resume?.str("file_id")
        val start = files.firstOrNull { it.str("info", "file_id") == wanted } ?: files.firstOrNull() ?: run {
            requireActivity().finish()
            return
        }
        // A film has one file, so its resume point applies whatever file id it was saved with.
        val resumes = resume != null && (resume.str("file_id") == start.str("info", "file_id") || files.size == 1)
        val saved = files.indexOf(start) to if (resumes) resume.optLong("time") else 0L
        // Saved in the credits, Play goes on to what comes next (UpNext.kt); an episode tile means
        // exactly that episode, credits and all.
        val (index, at) = if (chosen == null) upNext(files.map { it.endMark }, saved.first, saved.second) else saved
        glue = Glue(context, LeanbackPlayerAdapter(context, player, 500), series = files.size > 1).apply {
            host = VideoSupportFragmentGlueHost(this@PlayerFragment)
            isSeekEnabled = true
        }
        progressBarManager.hide()
        play(files[index], at * 1000)
    }

    private fun play(next: JSONObject, positionMs: Long) {
        report()
        file = next
        reportedAt = -1
        val m3u8 = next.obj("file")?.opt("m3u8")
        qualities = (m3u8 as? org.json.JSONArray).objects()
        quality = Settings.pickQuality(qualities.map { it.str("label") }, chosenQuality ?: Settings.quality)
        val info = next.optJSONObject("info")
        val season = info?.optInt("season") ?: 0
        val episode = info?.optInt("episode_number", info.optInt("episode")) ?: 0
        glue?.setTitle(stream?.optJSONObject("info")?.englishName)
        // The server's episode_name reads "فصل 1 - قسمت 4"; shown the way the rest of the app writes it.
        episodeLine = if (files.size > 1) info?.str("episode_name")?.episodeLabel() ?: getString(R.string.season_episode, season, episode) else null
        // Title, episode and cover for the system and Assistant; next and previous follow the series.
        val titleInfo = stream?.optJSONObject("info")
        val index = files.indexOf(next)
        session.nowPlaying(titleInfo?.englishName, episodeLine, titleInfo?.cover ?: titleInfo?.poster, hasPrevious = index > 0, hasNext = index < files.lastIndex)
        setSource(positionMs)
        showEndTime() // after the new item is set: the old one's duration would give a wrong time
    }

    /** The controls' line under the title: the episode (a series), and when it ends ([showEndTime]). */
    private var episodeLine: String? = null
    private var subtitle: String? = "" // "" so the first call sets it, null included
    /** The subtitle changed width since the cues were placed beside it (see [arrange]). */
    private var retitled = false

    /**
     * Netflix's «پایان ساعت ۲۲:۴۵»: now plus what is left at the current speed, once the duration
     * is known. Checked every tick, so a seek, a pause or a speed change shows within a second; the
     * glue hears of it only when the minute changes.
     */
    private fun showEndTime() {
        val left = player.duration.takeIf { it > 0 }?.let { (it - player.currentPosition).coerceAtLeast(0) }
        val endsAt = left?.let { getString(R.string.player_ends_at, clock(System.currentTimeMillis() + (it / player.playbackParameters.speed).toLong())) }
        val line = dotted(episodeLine, endsAt).ifEmpty { null }
        if (line == subtitle) return
        subtitle = line
        glue?.setSubtitle(line)
        retitled = true
    }

    /** 22:45 in Persian digits; in English the device's own 12- or 24-hour clock ("10:45 PM"). */
    private fun clock(ms: Long): String =
        if (Api.language == "fa") SimpleDateFormat("HH:mm", Locale.US).format(Date(ms)).localDigits()
        else DateFormat.getTimeFormat(requireContext()).format(Date(ms))

    private fun setSource(positionMs: Long) {
        val f = file ?: return
        val q = qualities.getOrNull(quality)
        val audio = chosenAudio ?: Settings.audio
        val url = q?.str(audio) ?: q?.str("default") ?: f.str("file", "m3u8") ?: return
        val subtitles = f.obj("file", "subtitle")?.let { subs ->
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
        player.setMediaItem(
            MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8).setSubtitleConfigurations(subtitles)
                .setMediaMetadata(session.metadata).build(),
            positionMs,
        )
        player.prepare()
        player.playWhenReady = true
    }

    private fun tick() {
        val f = file ?: return
        val seconds = player.currentPosition / 1000
        updateSkip(f, seconds)
        showEndTime()
        if (player.isPlaying && abs(seconds - reportedAt) >= 15) report()
    }

    /** What the server keeps as "continue watching"; the official player sends the same six fields. */
    private fun report() {
        val f = file ?: return
        val seconds = player.currentPosition / 1000
        val duration = player.duration.takeIf { it > 0 }?.div(1000) ?: return
        if (seconds <= 0 || Api.user?.optBoolean("user_allowed_to_stream") != true) return
        reportedAt = seconds
        Api.scope.launch {
            runCatching {
                Api.observer(postId, f.obj("info")?.opt("file_id"), Api.user?.opt("user_id"), seconds, duration, f.obj("options")?.opt("token"))
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
    /** Episodes autoplayed in a row with no key pressed in between. */
    private var unattended = 0
    /** Where the pill is: [HIDDEN], [REST] at its corner or [LIFTED] over the controls. */
    private var skipSpot = HIDDEN
    /** How far in from the end edge the subtitles keep clear of the pill (0 while it is hidden). */
    private var skipBerth = 0

    /**
     * Netflix's autoplay, as Settings has it, until three episodes in a row have played with no key
     * pressed: then the next one waits for the remote, so a sleeping viewer doesn't run through a
     * series, its "continue watching" and a metered connection.
     */
    private val autoplays get() = Settings.autoplay && unattended < 3

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
            // Up from the controls reaches it and Down goes back; nothing else leads off it.
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
        val end = f.endMark
        val next = nextFile()
        val (label, action) = when {
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
            R.string.skip_intro -> (mark("intro_start") ?: 0) * 1000
            R.string.player_skip_recap -> (recapStart ?: 0) * 1000
            else -> 0
        }
        skipEndMs = when (label) {
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
            val glyph = ContextCompat.getDrawable(context, if (label == R.string.next_episode) R.drawable.player_skip_next else R.drawable.player_skip_forward)!!
            val size = resources.getDimensionPixelSize(R.dimen.button_icon)
            glyph.setBounds(0, 0, size, size)
            skip.setCompoundDrawablesRelative(glyph, null, null, null)
            when {
                label == R.string.next_episode -> if (autoplays) countdown(NEXT_MS, ::autoplayNext)
                action != null && Settings.autoSkip && autoSkipped.add(label) -> countdown(AUTO_SKIP_MS, action)
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

    /** The remote back to the transport row (its play button), before the pill goes or loses the controls around it. */
    private fun focusControls() {
        view?.findViewById<View>(androidx.leanback.R.id.transport_row)?.requestFocus()
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
        val language = Locale.forLanguageTag(prefs.getString("subtitle", "fa")!!)
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

    private fun applyTrackPreferences() {
        val subtitle = prefs.getString("subtitle", "fa")
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, subtitle == "off")
            .setPreferredTextLanguage(subtitle.takeIf { it != "off" })
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
        if (opener?.requestFocus() != true) view?.findViewById<View>(androidx.leanback.R.id.playback_progress)?.requestFocus()
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
        // A saved language this file has no subtitles in shows as off, which is what plays.
        val languages = file?.obj("file", "subtitle")?.keys()?.asSequence()?.toList().orEmpty()
        val current = prefs.getString("subtitle", "fa").takeIf { it in languages } ?: "off"
        val subtitles = listOf(
            Group(getString(R.string.subtitles), listOf(Choice(getString(R.string.subtitle_off), current == "off") { applySubtitle("off") }) +
                languages.map { Choice(languageName(it), it == current) { applySubtitle(it) } }),
            Group(null, listOf(Choice(getString(R.string.subtitle_style), null) { panel.push(::appearance) })),
        )
        val columns = listOfNotNull(audio.ifEmpty { null }, subtitles)
        return Page(getString(R.string.player_audio_subtitles), columns, landing = columns.lastIndex)
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

    /** Not saved: ExoPlayer keeps the speed across episodes, and a fresh player starts at 1×. */
    private fun applySpeed(speed: Float) = player.setPlaybackSpeed(speed)

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
        prefs.edit().putString("subtitle", language).apply()
        applyTrackPreferences()
        applySubtitleStyle() // the new language's direction
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
                context.episodeCard(f, watching.takeIf { f === playing }, art = stream?.optJSONObject("info")?.cover) {
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
        const val REST = 0
        const val LIFTED = 1
        const val BESIDE = 2
        const val HIDDEN = -1
        /** The next episode's countdown, and auto-skip's sweep before it takes an intro or recap. */
        const val NEXT_MS = 10_000L
        const val AUTO_SKIP_MS = 1_500L
        /** Keys that leave a countdown running. */
        val PASSIVE_KEYS = intArrayOf(
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
        )
        /** Lifted, the pill's bottom stands this far (dp) over the transport row: level with the title's foot. */
        const val LIFT_GAP = 20
        /** In step with the player_speeds labels. */
        val SPEEDS = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        /** The title and episode line, which lifted subtitles clear vertically. */
        val TITLE = intArrayOf(androidx.leanback.R.id.lb_details_description_title, androidx.leanback.R.id.lb_details_description_subtitle)
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

        /** The glue's row and description, including its key listener and title layout. */
        override fun onCreateRowPresenter(): PlaybackRowPresenter = object : PlaybackTransportRowPresenter() {
            override fun createRowViewHolder(parent: ViewGroup): RowPresenter.ViewHolder = super.createRowViewHolder(parent).also { dress(it.view) }

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
                    vh.subtitle.text = subtitle?.let { latin(context, it, R.font.inter_regular) }
                }
            })
        }

        /**
         * Leanback sets the transport left to right in either language, which in Persian left it
         * apart from the title at the right. The play cluster is centred, belonging to neither side
         * (its order stays left to right, as the bar fills); in Persian the row under the bar mirrors,
         * its buttons at the right under the title, the times at the left in Persian digits.
         */
        private fun dress(row: View) {
            val dock = row.findViewById<ViewGroup>(androidx.leanback.R.id.controls_dock)
            // Its -12dp lined the first glyph up with the bar's start; centred, it would only skew it.
            (dock.layoutParams as ViewGroup.MarginLayoutParams).leftMargin = 0
            (dock.getChildAt(0).layoutParams as FrameLayout.LayoutParams).gravity = Gravity.CENTER_HORIZONTAL
            if (row.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
                val under = row.findViewById<View>(androidx.leanback.R.id.secondary_controls_dock).parent as ViewGroup
                under.layoutDirection = View.LAYOUT_DIRECTION_RTL
                (under.layoutParams as ViewGroup.MarginLayoutParams).run {
                    rightMargin = leftMargin
                    leftMargin = 0
                }
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

        override fun onCreateSecondaryActions(adapter: ArrayObjectAdapter) {
            adapter.add(captions)
            adapter.add(hq)
            adapter.add(speed)
            if (series) adapter.add(episodes)
        }

        override fun onActionClicked(action: Action) {
            when (action) {
                rewind -> player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0))
                forward -> {
                    player.seekTo(player.currentPosition + 10_000)
                    player.play()
                }
                previous -> previousEpisode()
                next -> nextEpisode()
                captions -> openPanel(::audioAndSubtitles)
                hq -> openPanel { qualityAndSpeed(onSpeed = false) }
                speed -> openPanel { qualityAndSpeed(onSpeed = true) }
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
private object Quiet : CharacterStyle() {
    override fun updateDrawState(paint: TextPaint) {
        paint.alpha = paint.alpha * 7 / 10
    }
}
