package com.doxigo.tv30

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Aparat's public stream, without depending on the TV's aging WebView to run its web player. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class TrailerActivity : TvActivity() {
    private lateinit var player: ExoPlayer
    private lateinit var video: PlayerView
    private lateinit var loading: ProgressBar
    private lateinit var error: LinearLayout
    private lateinit var retry: Button
    private var loadJob: Job? = null
    private var resumePlayback = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(Api.http)
                .setDefaultRequestProperties(mapOf("Referer" to "https://www.aparat.com/"))))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        video = PlayerView(this).apply {
            player = this@TrailerActivity.player
            controllerShowTimeoutMs = 5_000
            controllerAutoShow = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            isFocusable = true
        }
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
            action()
            video.showController()
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

        fun intent(context: Context, id: String): Intent = Intent(context, TrailerActivity::class.java).putExtra(EXTRA_ID, id)
    }
}
