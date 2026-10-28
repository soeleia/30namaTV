package com.doxigo.tv30

import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * A trailer in the app's own player: Aparat's public stream, without depending on the TV's aging
 * WebView to run its web player, or a YouTube video through YouTube's embed ([YoutubePlayer]).
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class TrailerActivity : TvActivity() {
    private lateinit var player: Player
    private lateinit var video: PlayerView
    private lateinit var playPause: View
    private lateinit var loading: ProgressBar
    private lateinit var error: LinearLayout
    private lateinit var retry: Button
    private var loadJob: Job? = null
    private var resumePlayback = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_ID).orEmpty()
        player = if (intent.getBooleanExtra(EXTRA_YOUTUBE, false)) {
            preloaded?.takeIf { it.key == id }?.also { preloaded = null; it.moveTo(this) } ?: YoutubePlayer(this, id)
        } else ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(Api.http)
                .setDefaultRequestProperties(mapOf("Referer" to "https://www.aparat.com/"))))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        video = (layoutInflater.inflate(R.layout.trailer_player, null) as PlayerView).apply {
            player = this@TrailerActivity.player
            controllerShowTimeoutMs = 5_000
            controllerAutoShow = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            isFocusable = true
        }
        playPause = video.findViewById(androidx.media3.ui.R.id.exo_play_pause)
        // Above PlayerView's blank video surface, under its controls.
        (player as? YoutubePlayer)?.let { video.overlayFrameLayout?.addView(it.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) }
        loading = ProgressBar(this)
        retry = Button(this, null, 0, R.style.Widget_App_Button).apply {
            text = getString(R.string.retry)
            setOnClickListener { resumePlayback = true; load() }
        }
        error = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            addView(TextView(this@TrailerActivity).apply {
                setTextAppearance(R.style.TextAppearance_App_Body)
                setText(R.string.trailer_error)
                gravity = Gravity.CENTER
            })
            addView(retry)
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(video, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(loading, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            addView(error, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        })
        player.addListener(object : Player.Listener {
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK && newPosition.positionMs > oldPosition.positionMs) player.play()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) loading.visibility = View.GONE
                if (playbackState == Player.STATE_ENDED) finish() // back to the title, as Netflix does
            }

            // Lit while it plays: a paused trailer lets the screensaver come.
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                video.keepScreenOn = isPlaying
            }

            override fun onPlayerError(exception: PlaybackException) = showError()
        })
        video.requestFocus()
    }

    private fun load() {
        loadJob?.cancel()
        player.stop()
        player.playWhenReady = resumePlayback && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        error.visibility = View.GONE
        loading.visibility = View.VISIBLE
        video.requestFocus()
        if (player is YoutubePlayer) return player.prepare()
        loadJob = lifecycleScope.launch {
            try {
                val url = Trailer.aparatStream(intent.getStringExtra(EXTRA_ID).orEmpty())
                val type = if (url.toHttpUrl().encodedPath.endsWith(".m3u8", ignoreCase = true)) MimeTypes.APPLICATION_M3U8 else MimeTypes.VIDEO_MP4
                player.setMediaItem(MediaItem.Builder().setUri(url).setMimeType(type).build())
                player.prepare()
                player.playWhenReady = player.playWhenReady && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                showError()
            }
        }
    }

    private fun showError() {
        loading.visibility = View.GONE
        video.hideController()
        error.visibility = View.VISIBLE
        retry.takeFocus() // an id refused at once fails in onStart, before the window is up
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (error.visibility == View.VISIBLE) return super.dispatchKeyEvent(event)
        val action = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (video.isControllerFullyVisible) return super.dispatchKeyEvent(event) else ({ togglePlayback() })
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> ({ togglePlayback() })
            KeyEvent.KEYCODE_MEDIA_PLAY -> ({ player.play() })
            KeyEvent.KEYCODE_MEDIA_PAUSE -> ({ player.pause() })
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (video.isControllerFullyVisible) return super.dispatchKeyEvent(event) else ({ player.seekForward() })
            KeyEvent.KEYCODE_DPAD_LEFT -> if (video.isControllerFullyVisible) return super.dispatchKeyEvent(event) else ({ player.seekBack() })
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> ({ player.seekForward() })
            KeyEvent.KEYCODE_MEDIA_REWIND -> ({ player.seekBack() })
            else -> return super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val shown = video.isControllerFullyVisible
            action()
            video.showController()
            // media3 focuses play / pause only when its controls were gone. For 2 s after they fade
            // out they are only invisible, the focus on the video itself, and fading back in from
            // there Android hands it to their first button, back 10 s.
            if (!shown) playPause.requestFocus()
        }
        return true
    }

    private fun togglePlayback() {
        if (player.playWhenReady) player.pause() else player.play()
    }

    override fun onStart() {
        super.onStart()
        if (player.mediaItemCount == 0 && error.visibility != View.VISIBLE) load()
        else if (player.playbackState == Player.STATE_IDLE && error.visibility != View.VISIBLE) player.prepare()
    }

    override fun onResume() {
        super.onResume()
        player.playWhenReady = resumePlayback
    }

    override fun onPause() {
        resumePlayback = player.playWhenReady
        player.pause()
        video.hideController()
        super.onPause()
    }

    override fun onStop() {
        loadJob?.cancel()
        player.stop()
        super.onStop()
    }

    override fun onDestroy() {
        video.player = null
        player.release()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_ID = "aparat"
        private const val EXTRA_YOUTUBE = "youtube"

        /** Aparat's video [id], or with [youtube] a YouTube video key. */
        fun intent(context: Context, id: String, youtube: Boolean = false): Intent =
            Intent(context, TrailerActivity::class.java).putExtra(EXTRA_ID, id).putExtra(EXTRA_YOUTUBE, youtube)

        /** A YouTube trailer loaded ahead, paused, waiting for its [intent]; one at a time. */
        private var preloaded: YoutubePlayer? = null

        /**
         * Loads YouTube video [key] ahead while [page] is started: YouTube's page and player take
         * seconds on a TV, so a press then only starts the video. Not with Data saver.
         */
        fun preload(page: TvActivity, key: String) {
            if (Settings.saveData) return
            var mine: YoutubePlayer? = null
            page.lifecycle.addObserver(LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_START && preloaded?.key != key) {
                    preloaded?.release()
                    mine = YoutubePlayer(page, key).apply { prepare() }
                    preloaded = mine
                }
                // A page opened over this one has loaded its own by now, and the trailer took its.
                if (event == Lifecycle.Event.ON_STOP && preloaded === mine) {
                    preloaded?.release()
                    preloaded = null
                }
            })
        }
    }
}

/**
 * A YouTube video as a media3 [Player], so the trailer's PlayerView, keys and lifecycle drive it as
 * they drive Aparat's: YouTube's IFrame API in a [view] with its own controls off. Its streams
 * can't be had otherwise without breaking YouTube's terms. Stop blanks the page (no loading or
 * sound in the background); prepare loads it again at the second it stopped. Prepared paused on
 * the title page (TrailerActivity.preload), it [moveTo]s the trailer's window ready to play.
 */
@UnstableApi
private class YoutubePlayer(context: Context, val key: String) : SimpleBasePlayer(Looper.getMainLooper()) {
    val view = WebView(MutableContextWrapper(context)).apply {
        setBackgroundColor(Color.BLACK)
        isFocusable = false // the keys stay with the activity and the controls
        settings.javaScriptEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.domStorageEnabled = true // YouTube keeps its estimate of the connection's speed there
        addJavascriptInterface(Bridge(), "app")
    }
    private val main = Handler(Looper.getMainLooper())
    private val item = MediaItem.Builder().setMediaId(key).build()
    private var state = Player.STATE_IDLE
    private var playWhenReady = false
    private var ready = false
    private var playing = false
    private var positionMs = 0L
    private var reportedAt = 0L
    private var seekedAt = 0L
    private var durationMs = C.TIME_UNSET
    private var error: PlaybackException? = null

    override fun getState(): State =
        State.Builder()
            .setAvailableCommands(COMMANDS)
            .setPlaylist(listOf(MediaItemData.Builder(key).setMediaItem(item).setIsSeekable(true)
                .setDurationUs(if (durationMs == C.TIME_UNSET) C.TIME_UNSET else durationMs * 1000).build()))
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(state)
            .setPlayerError(error)
            // Between the page's twice-a-second reports, the bar moves on by the clock.
            .setContentPositionMs(PositionSupplier {
                val at = positionMs + if (playing) SystemClock.elapsedRealtime() - reportedAt else 0
                if (durationMs == C.TIME_UNSET) at else at.coerceAtMost(durationMs)
            })
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()

    override fun handlePrepare(): ListenableFuture<*> {
        require(key.matches(Regex("[\\w-]+")))
        error = null
        ready = false
        state = Player.STATE_BUFFERING
        // YouTube refuses embeds that don't say whose they are (error 152/153): the app's id is the page's origin.
        val origin = "https://${view.context.packageName}"
        view.loadDataWithBaseURL(origin, page(origin, positionMs / 1000), "text/html", "utf-8", null)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        this.playWhenReady = playWhenReady
        if (ready) js(if (playWhenReady) "playVideo()" else "pauseVideo()")
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        this.positionMs = positionMs
        reportedAt = SystemClock.elapsedRealtime()
        seekedAt = reportedAt
        if (ready) js("seekTo(${positionMs / 1000.0}, true)")
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        blank()
        error = null
        return Futures.immediateVoidFuture()
    }

    /** No page, so nothing loads or sounds; the position stays for the next prepare. */
    private fun blank() {
        ready = false
        playing = false
        state = Player.STATE_IDLE
        view.loadUrl("about:blank")
    }

    override fun handleRelease(): ListenableFuture<*> {
        ready = false
        view.destroy()
        return Futures.immediateVoidFuture()
    }

    /** From the page that preloaded it to the trailer's own, which the WebView then answers to. */
    fun moveTo(context: Context) {
        (view.context as MutableContextWrapper).baseContext = context
    }

    private fun js(call: String) = view.evaluateJavascript("p && p.$call", null)

    /** The page's word, on a WebView thread: brought to the main one, and dropped once stopped. */
    private inner class Bridge {
        @JavascriptInterface
        fun ready() = main.post {
            if (state == Player.STATE_IDLE) return@post
            ready = true
            if (playWhenReady) js("playVideo()")
        }

        /** YouTube's state (-1 unstarted, 0 ended, 1 playing, 2 paused, 3 buffering, 5 cued), where and how long. */
        @JavascriptInterface
        fun state(youtube: Int, position: Double, duration: Double) = main.post {
            if (state == Player.STATE_IDLE || !ready) return@post
            state = when (youtube) {
                0 -> Player.STATE_ENDED
                3 -> Player.STATE_BUFFERING
                // Not started yet: still loading while it is to play, so the spinner stays.
                -1, 5 -> if (playWhenReady) Player.STATE_BUFFERING else Player.STATE_READY
                else -> Player.STATE_READY
            }
            playing = youtube == 1
            // The page answers from its last word from YouTube's frame, the time before a seek for a
            // moment after it: a second press would step on from there. ponytail: a fixed 1.5 s.
            if (SystemClock.elapsedRealtime() - seekedAt > 1_500) {
                positionMs = (position * 1000).toLong()
                reportedAt = SystemClock.elapsedRealtime()
            }
            if (duration > 0) durationMs = (duration * 1000).toLong()
            invalidateState()
        }

        /** YouTube's error code (100 gone, 101/150 not embeddable, 152/153 refused); -1 when its script didn't load, -2 for a WebView too old for it. */
        @JavascriptInterface
        fun error(code: Int) = main.post {
            if (state == Player.STATE_IDLE) return@post
            blank()
            error = PlaybackException("YouTube $code", null, PlaybackException.ERROR_CODE_REMOTE_ERROR)
            invalidateState()
        }
    }

    private fun page(origin: String, start: Long) = """<!doctype html>
        <html><head><meta name="viewport" content="width=device-width, initial-scale=1">
        <style>html, body, #p { margin: 0; width: 100%; height: 100%; background: #000; overflow: hidden }</style></head>
        <body><div id="p"></div><script>
        if (!window.queueMicrotask) app.error(-2);
        var p;
        function report() { if (p && p.getPlayerState) app.state(p.getPlayerState(), p.getCurrentTime(), p.getDuration()) }
        function onYouTubeIframeAPIReady() {
            p = new YT.Player('p', {
                videoId: '$key',
                playerVars: { controls: 0, disablekb: 1, fs: 0, rel: 0, iv_load_policy: 3, playsinline: 1, start: $start, origin: '$origin' },
                events: { onReady: function () { app.ready(); report() }, onStateChange: report, onError: function (e) { app.error(e.data) } }
            });
            setInterval(report, 500);
        }
        </script><script src="https://www.youtube.com/iframe_api" onerror="app.error(-1)"></script></body></html>"""

    companion object {
        private val COMMANDS = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP, Player.COMMAND_RELEASE,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE,
        ).build()
    }
}
