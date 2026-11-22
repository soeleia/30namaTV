package com.doxigo.tv30

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.widget.TextView
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.net.UnknownHostException

/**
 * Run the existing instrumentation runner with -e check offline. Mux's public HLS stream behind a
 * stubbed `stream` answer, its connections cut in-process as a TV with no network has them
 * (UnknownHostException). Cut for a few seconds while the player still loads, the film plays on
 * past what it held and loads again once the connection is back, with no note: it used to give up
 * for good after three tries and stop at the end of its memory buffer, the read-ahead's minutes on
 * disk unread. Cut for good, it plays out everything it holds, and only then says why in the note,
 * within seconds of running dry.
 */
object OfflineChecks {
    private const val HLS = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
    @Volatile private var offline = false

    fun run(instrumentation: Instrumentation) = with(instrumentation) {
        val result = Bundle()
        var code = Activity.RESULT_OK
        var player: PlayerActivity? = null
        val originalClient = Api.http
        val originalUser = Api.user
        val clientField = Api::class.java.getDeclaredField("http").apply { isAccessible = true }
        val postId = (System.currentTimeMillis() % 1_000_000).toInt() // a title this TV never left: it starts at 0
        // The video's client is built from this one (Buffering.cdn), so the switch cuts its connections too.
        val client = originalClient.newBuilder().addInterceptor { chain ->
            val request = chain.request()
            if (offline) throw UnknownHostException("Unable to resolve host \"${request.url.host}\": No address associated with hostname")
            val reply = { status: Int, body: String ->
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("-").body(body.toResponseBody()).build()
            }
            when {
                request.url.pathSegments.last() == "stream" ->
                    reply(200, """{"success":true,"result":{"info":{"title":{"english":"Offline check"}},"hls":[{"info":{"file_id":"offline"},"file":{"m3u8":"$HLS"},"options":{}}]}}""")
                request.url.host == "test-streams.mux.dev" -> chain.proceed(request)
                else -> reply(404, "") // nothing else leaves the emulator
            }
        }.build()
        try {
            offline = false
            Buffering.clearCache(targetContext) // nothing on disk from an earlier run: what plays first comes over the connection
            runOnMainSync {
                clientField.set(Api, client)
                Buffering.cdn
                Api.user = null
            }
            val activity = startActivitySync(PlayerActivity.intent(targetContext, postId, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PlayerActivity
            player = activity
            lateinit var fragment: PlayerFragment
            lateinit var exo: ExoPlayer
            await({ "Player did not load" }, 15_000) {
                val found = activity.supportFragmentManager.findFragmentById(android.R.id.content) as? PlayerFragment
                if (found == null || field(found, "glue") == null) false else {
                    fragment = found
                    exo = field(found, "player") as ExoPlayer
                    exo.volume = 0f
                    true
                }
            }
            fun noted() = field(fragment, "failed") == true
            // Main thread only, as the player is: the waits keep the last one for their messages.
            var seen = ""
            fun state() = "at ${exo.currentPosition} ms, ${exo.bufferedPosition} ms held, state ${exo.playbackState}, error ${exo.playerError?.errorCodeName}, note ${noted()}"
            // On to the end of what the player holds, by seeks inside it, which leave its loads as they are.
            fun skim() {
                if (exo.isPlaying && exo.bufferedPosition - exo.currentPosition > 8_000) exo.seekTo(exo.bufferedPosition - 5_000)
            }

            // ---- Cut for a few seconds while the player still loads, then back. ----
            await({ "Playback did not start loading ahead: $seen" }, 30_000) {
                seen = state()
                exo.isPlaying && exo.isLoading && exo.bufferedPosition - exo.currentPosition >= 20_000
            }
            offline = true
            SystemClock.sleep(6_000) // the loader's tries at 0, 1 and 2 s and the one after: it gave up for good there
            var held = 0L
            runOnMainSync { held = exo.bufferedPosition }
            offline = false
            var noteSeen = false
            await({ "Did not play on past the $held ms it held once the connection was back: $seen" }, 60_000) {
                seen = state()
                noteSeen = noteSeen || noted()
                skim()
                noteSeen || exo.currentPosition > held + 30_000
            }
            check(!noteSeen) { "The note came up over a film the connection came back for: $seen" }

            // ---- Cut for good: everything held plays out, then the note, quickly. ----
            offline = true
            var furthest = 0L
            var dryAt = 0L
            await({ "No note after the film ran dry with the connection gone: $seen" }, 90_000) {
                seen = state()
                if (!noted()) {
                    furthest = maxOf(furthest, exo.bufferedPosition)
                    skim()
                    if (exo.playbackState == Player.STATE_READY) dryAt = 0L else if (dryAt == 0L) dryAt = SystemClock.elapsedRealtime()
                }
                noted()
            }
            // Never seen buffering: it failed the moment it ran dry.
            val waited = if (dryAt == 0L) 0L else SystemClock.elapsedRealtime() - dryAt
            var failure: Int? = null
            var at = 0L
            var why = ""
            runOnMainSync {
                failure = exo.playerError?.errorCode
                at = exo.currentPosition
                why = activity.findViewById<TextView>(R.id.player_note_why).text.toString()
            }
            check(failure == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED) { "The note was not the connection's: $seen" }
            check(at >= furthest - Buffering.START_MS) { "The note came with ${furthest - at} ms still held: $seen" }
            check(why == activity.getString(R.string.player_error_network)) { "The note said «$why»" }
            check(waited < 15_000) { "The note took $waited ms after the film ran dry" }
            SystemClock.sleep(400) // the note fades in
            capture("offline-note")
            result.putString("stream", "PASS: a connection cut for seconds mid-play is ridden out on the buffer and loading resumes with no note; cut for good, everything held plays out and the network note comes ${waited / 1000} s after it runs dry\n")
        } catch (error: Throwable) {
            code = Activity.RESULT_CANCELED
            result.putString("stream", error.stackTraceToString())
        } finally {
            offline = false
            runOnMainSync { player?.finish() }
            await({ "Player did not close" }) { player?.isDestroyed != false }
            runOnMainSync {
                Api.user = originalUser
                clientField.set(Api, originalClient)
            }
            client.dispatcher.cancelAll()
        }
        finish(code, result)
    }

    private fun field(instance: Any, name: String): Any? =
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance)

    private fun Instrumentation.await(message: () -> String, timeoutMs: Long = 7_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        do {
            var passed = false
            runOnMainSync { passed = condition() }
            if (passed) return
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        error(message())
    }
}
