package com.doxigo.tv30

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import androidx.lifecycle.Lifecycle
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.SilenceMediaSource
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Job
import okhttp3.Dispatcher
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Run the existing instrumentation runner with -e check trailer. */
object TrailerChecks {
    fun run(instrumentation: Instrumentation) = with(instrumentation) {
        val result = Bundle()
        var resultCode = Activity.RESULT_OK
        var activity: TrailerActivity? = null
        var foreground: SettingsActivity? = null
        val originalClient = Api.http
        val clientField = Api::class.java.getDeclaredField("http").apply { isAccessible = true }
        val blocked = AtomicReference<CountDownLatch?>()
        val started = AtomicReference<CountDownLatch?>()
        val offline = AtomicBoolean(false)
        val networkFailure = AtomicReference<String?>()
        val client = originalClient.newBuilder().dispatcher(Dispatcher()).addInterceptor { chain ->
            val request = chain.request()
            val metadata = request.url.encodedPath.startsWith("/api/fa/v1/video/video/show/")
            if (metadata) {
                started.get()?.countDown()
                blocked.get()?.let { check(it.await(10, TimeUnit.SECONDS)) { "Metadata was never released" } }
                if (offline.get()) return@addInterceptor Response.Builder().request(request)
                    .protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("""{"data":{"file_link":"https://www.aparat.com/trailer-check.mp4"}}""".toResponseBody()).build()
            }
            try {
                chain.proceed(request).also {
                    if (!it.isSuccessful) networkFailure.set("HTTP ${it.code}")
                }
            } catch (exception: IOException) {
                networkFailure.set(exception.javaClass.simpleName)
                throw exception
            }
        }.build()
        fun close() {
            runOnMainSync { activity?.finish() }
            await("Trailer activity did not close") { activity?.isDestroyed != false }
            activity = null
        }
        fun launch(id: String): TrailerActivity {
            val host = startActivitySync(TrailerActivity.intent(targetContext, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as TrailerActivity
            activity = host
            runOnMainSync { (field(host, "player") as ExoPlayer).volume = 0f }
            return host
        }
        try {
            runOnMainSync { clientField.set(Api, client) }
            var host = launch("wubu7o2")
            var player = field(host, "player") as ExoPlayer
            var video = field(host, "video") as PlayerView
            fun playbackState() = "state=${player.playbackState}, playWhenReady=${player.playWhenReady}, isPlaying=${player.isPlaying}, position=${player.currentPosition}, bufferedMs=${player.totalBufferedDuration}, suppression=${player.playbackSuppressionReason}, error=${player.playerError?.errorCodeName}, lifecycle=${host.lifecycle.currentState}, windowFocus=${host.hasWindowFocus()}, focused=${host.currentFocus?.javaClass?.simpleName}"
            await("Animals did not load or report an error", 40_000) {
                player.playbackState == Player.STATE_READY && player.isPlaying && player.videoSize.width > 0 || (field(host, "error") as View).visibility == View.VISIBLE
            }
            var publicPlayed = false
            runOnMainSync {
                publicPlayed = player.isPlaying && player.videoSize.width > 0
                if (publicPlayed) check(player.currentMediaItem?.localConfiguration?.mimeType == MimeTypes.APPLICATION_M3U8)
                else {
                    val errorCode = player.playerError?.errorCode ?: 0
                    check(networkFailure.get() != null || errorCode in 2_000..2_008) {
                        "Public trailer failed without a network error: ${player.playerError?.errorCodeName}"
                    }
                }
            }
            if (publicPlayed) {
                var before = 0L
                runOnMainSync { before = player.currentPosition }
                await("Animals was READY but its position did not advance") { player.currentPosition >= before + 500 }
                sendStatus(0, Bundle().apply { putString("stream", "PUBLIC PASS: Animals 2026 HLS decoded and advanced\n") })
            } else {
                offline.set(true)
                sendStatus(0, Bundle().apply { putString("stream", "PUBLIC_UNAVAILABLE: ${networkFailure.get() ?: "CDN IO error"}; remaining checks use a silence fixture\n") })
            }
            close()

            val release = CountDownLatch(1)
            val requested = CountDownLatch(1)
            blocked.set(release)
            started.set(requested)
            host = launch("wubu7o2")
            player = field(host, "player") as ExoPlayer
            video = field(host, "video") as PlayerView
            check(requested.await(5, TimeUnit.SECONDS)) { "Trailer metadata did not enter the held request" }
            press(KeyEvent.KEYCODE_MEDIA_PAUSE)
            runOnMainSync { check(!player.playWhenReady) { "Pause during loading was ignored" } }
            release.countDown()
            blocked.set(null)
            started.set(null)
            await("Released metadata did not set a media item") { player.mediaItemCount == 1 }
            runOnMainSync {
                check(!player.playWhenReady) { "Metadata completion undid the user's Pause" }
                if (offline.get()) {
                    player.setMediaSource(SilenceMediaSource.Factory().setDurationUs(300_000_000).createMediaSource())
                    player.prepare()
                    (field(host, "error") as View).visibility = View.GONE
                    (field(host, "loading") as View).visibility = View.GONE
                }
            }
            await("Paused trailer did not prepare", details = ::playbackState) { player.playbackState == Player.STATE_READY && !player.playWhenReady }
            press(KeyEvent.KEYCODE_MEDIA_PLAY)
            await("Play did not resume the trailer", details = ::playbackState) { player.isPlaying }
            var beforeSeek = 0L
            runOnMainSync {
                player.pause()
                beforeSeek = player.currentPosition
            }
            press(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
            await("Trailer forward seek did not resume", details = ::playbackState) { player.isPlaying && player.currentPosition >= beforeSeek + 9_000 }
            runOnMainSync {
                player.pause()
                beforeSeek = player.currentPosition
            }
            press(KeyEvent.KEYCODE_MEDIA_REWIND)
            await("Trailer rewind did not seek", details = ::playbackState) { player.currentPosition <= beforeSeek - 9_000 }
            runOnMainSync {
                check(!player.playWhenReady) { "Trailer rewind resumed paused playback" }
                player.seekTo(player.currentPosition + 10_000)
            }
            await("Native forward seek did not resume", details = ::playbackState) { player.isPlaying }
            runOnMainSync {
                check(video.controllerShowTimeoutMs == 5_000 && !video.controllerAutoShow)
                video.showController()
            }
            // Fully visible once its show animation ends: the controls the rewind brought up may
            // have timed out already while the seek buffered from the network.
            await("Trailer controls did not show") { video.isControllerFullyVisible }
            val shownAt = SystemClock.elapsedRealtime()
            await("Trailer controls did not hide after inactivity") { !video.isControllerFullyVisible }
            check(SystemClock.elapsedRealtime() - shownAt >= 4_000) { "Trailer controls hid before the configured timeout" }

            foreground = startActivitySync(Intent(targetContext, SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SettingsActivity
            await("Settings did not take foreground focus") { foreground?.hasWindowFocus() == true }
            await("Background trailer kept loading or playing", details = ::playbackState) {
                !host.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && player.playbackState == Player.STATE_IDLE && !player.playWhenReady && !player.isPlaying
            }
            runOnMainSync { foreground?.finish() }
            foreground = null
            // stop()/prepare() refetches public HLS, so allow the initial playback network budget.
            await("Returning to the trailer did not resume", if (publicPlayed) 40_000 else 7_000, details = ::playbackState) {
                host.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && host.hasWindowFocus() &&
                    player.playbackState == Player.STATE_READY && player.isPlaying
            }
            var resumedAt = 0L
            runOnMainSync { resumedAt = player.currentPosition }
            await("Resumed trailer position did not advance", details = ::playbackState) {
                player.isPlaying && player.currentPosition >= resumedAt + 500
            }
            close()

            // YouTube's page loaded ahead on a started page, taken by the trailer, loaded again
            // when the page is back, and let go when it stops.
            val preloadedField = TrailerActivity::class.java.getDeclaredField("preloaded").apply { isAccessible = true }
            fun preloaded() = preloadedField.get(null)
            val page = startActivitySync(Intent(targetContext, SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SettingsActivity
            foreground = page
            var ahead: Any? = null
            runOnMainSync {
                TrailerActivity.preload(page, "Way9Dexny3w")
                ahead = checkNotNull(preloaded()) { "Preload held no player" }
            }
            val readyAhead = runCatching { await("", 20_000) { field(ahead!!, "ready") == true } }.isSuccess
            activity = startActivitySync(TrailerActivity.intent(targetContext, "Way9Dexny3w", youtube = true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as TrailerActivity
            runOnMainSync { check(field(activity!!, "player") === ahead && preloaded() == null) { "The trailer did not take the preloaded page" } }
            close()
            await("Back on the page, the trailer was not loaded again") { preloaded().let { it != null && it !== ahead } }
            runOnMainSync { page.finish() }
            foreground = null
            await("A stopped page kept its preloaded trailer") { preloaded() == null }

            host = launch("invalid!")
            val retry = field(host, "retry") as Button
            await("Invalid trailer did not focus Retry") { (field(host, "error") as View).visibility == View.VISIBLE && retry.hasFocus() }
            var firstLoad: Job? = null
            runOnMainSync {
                firstLoad = field(host, "loadJob") as Job
                retry.performClick()
                check(field(host, "loadJob") !== firstLoad) { "Retry did not start a new load" }
            }
            await("Retry lost its error/focus state") { (field(host, "error") as View).visibility == View.VISIBLE && retry.hasFocus() }
            result.putString("stream", "PASS: ${if (publicPlayed) "real Animals HLS" else "public network unavailable, silence fixture"}; held metadata pause, forward resume, paused rewind, five-second controls, background/return, YouTube preload hand-off (page ready ahead: $readyAhead), invalid UID retry\n")
        } catch (error: Throwable) {
            result.putString("stream", error.stackTraceToString())
            resultCode = Activity.RESULT_CANCELED
        } finally {
            blocked.getAndSet(null)?.countDown()
            runOnMainSync { foreground?.finish() }
            close()
            runOnMainSync { clientField.set(Api, originalClient) }
            client.dispatcher.cancelAll()
        }
        finish(resultCode, result)
    }

    private fun field(instance: Any, name: String): Any? =
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance)

    private fun Instrumentation.press(code: Int) {
        sendKeySync(KeyEvent(KeyEvent.ACTION_DOWN, code))
        sendKeySync(KeyEvent(KeyEvent.ACTION_UP, code))
        waitForIdleSync()
    }

    private fun Instrumentation.await(message: String, timeoutMs: Long = 7_000, details: (() -> String)? = null, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        do {
            var passed = false
            runOnMainSync { passed = condition() }
            if (passed) return
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        var state = ""
        runOnMainSync { state = details?.invoke().orEmpty() }
        error("$message $state")
    }
}
