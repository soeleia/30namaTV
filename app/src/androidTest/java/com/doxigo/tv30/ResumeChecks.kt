package com.doxigo.tv30

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import androidx.media3.exoplayer.ExoPlayer
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import java.io.File

/**
 * Run the existing instrumentation runner with -e check resume. An episode saved five minutes in, on
 * a public HLS stream: the player starts there, as from Continue Watching, offering its start on the
 * pill, the poster beside the title's names and facts. Left at seven minutes while the server's point
 * stays at 0 (as 30nama keeps some), opened again it picks up where this TV left it, and the pill
 * takes it back to the start. Last, the controls in English.
 */
object ResumeChecks {
    private const val HLS = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"

    fun run(instrumentation: Instrumentation) = with(instrumentation) {
        val result = Bundle()
        var resultCode = Activity.RESULT_OK
        var player: PlayerActivity? = null
        val originalClient = Api.http
        val originalUser = Api.user
        val clientField = Api::class.java.getDeclaredField("http").apply { isAccessible = true }
        val id = "resume-${System.currentTimeMillis()}"
        val postId = (System.currentTimeMillis() % 1_000_000).toInt() // a title this TV never left
        // The server's point, as tv-checks' read-only switch keeps `observer` from moving it.
        var saved = """{"file_id":"$id","time":300,"progress":50}"""
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = when (request.url.pathSegments.last()) {
                "stream" -> """{"success":true,"result":{"info":{"title":{"english":"Ted Lasso 2020","local":"تد لاسو"}},"continue_watching":$saved,
                    "hls":[{"info":{"file_id":"$id-9","season":2,"episode_number":9},"file":{"m3u8":"$HLS"},"options":{}},
                    {"info":{"file_id":"$id","season":2,"episode_number":10},"file":{"m3u8":"$HLS"},"options":{}}]}}"""
                else -> null
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (body == null) 404 else 200)
                .message("OK").body((body ?: "").toResponseBody()).build()
        }.build()
        val poster = File(targetContext.cacheDir, "resume-poster.jpg").also(::poster)
        // The title as Continue Watching hands it over (Titles.playerFacts).
        val title = JSONObject("""{"image":{"poster":{"jpg":{"large":"${poster.absolutePath}"}}},"info":{"year":2020,"age":"TV-MA"},"score":{"imdb":{"score":8.8}}}""")
        val originalLanguage = Api.language

        class Opened(val activity: PlayerActivity, val fragment: PlayerFragment, val exo: ExoPlayer, val skip: Button)

        fun open(): Opened {
            val activity = startActivitySync(
                PlayerActivity.intent(targetContext, postId, false, title = title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as PlayerActivity
            player = activity
            lateinit var opened: Opened
            await("Player did not load", 15_000) {
                val fragment = activity.supportFragmentManager.findFragmentById(android.R.id.content) as? PlayerFragment
                if (fragment == null || field(fragment, "glue") == null) false else {
                    opened = Opened(activity, fragment, field(fragment, "player") as ExoPlayer, field(fragment, "skip") as Button)
                    true
                }
            }
            runOnMainSync { opened.exo.volume = 0f }
            await("Playback did not start", 30_000) { opened.exo.isPlaying }
            return opened
        }

        fun Opened.offersStart() = skip.isShown && skip.text.toString() == activity.getString(R.string.player_start_over)

        fun Opened.close() {
            runOnMainSync { activity.finish() }
            await("Player did not close") { activity.isDestroyed }
        }

        try {
            // As a TV with only a remote is: the controls then open on play/pause, as on the TV.
            setInTouchMode(false)
            runOnMainSync {
                Buffering.cdn // built from the real client first: the stream itself comes from the CDN
                clientField.set(Api, client)
                Api.user = JSONObject("""{"user_id":1,"user_allowed_to_stream":true}""")
                Api.language = "fa" // Persian first, whatever a run before left
            }
            // ---- From Continue Watching: five minutes in, the start on offer, the poster up. ----
            var opened = open()
            runOnMainSync { check(opened.exo.currentPosition in 299_000..305_000) { "Started at ${opened.exo.currentPosition} ms, not the saved 300 s" } }
            await("The start was not offered") { opened.offersStart() }
            await("The poster did not show") { opened.activity.findViewById<View>(androidx.leanback.R.id.image)?.let { it.isShown && it.width > 0 } == true }
            runOnMainSync { opened.fragment.tickle() } // the controls up, whatever their timer says
            SystemClock.sleep(1_500) // the pill settled over them
            capture("resume-start-over")
            // The seek bar with the remote: its dot.
            runOnMainSync { opened.fragment.tickle() }
            SystemClock.sleep(600) // Leanback's fade-in hands the remote to play/pause first
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP) // play/pause, under the bar, up to it
            await("The seek bar did not take the remote") { opened.activity.findViewById<View>(androidx.leanback.R.id.playback_progress).isFocused }
            SystemClock.sleep(300)
            capture("resume-seek-dot")

            // ---- Left at seven minutes; the server still says 0. ----
            runOnMainSync { opened.exo.seekTo(420_000) }
            await("Did not play on from 420 s", 30_000) { opened.exo.isPlaying && opened.exo.currentPosition >= 421_000 }
            await("The start was still offered past its seconds") { !opened.offersStart() }
            opened.close()
            saved = """{"file_id":"$id","time":0,"progress":0}"""
            opened = open()
            runOnMainSync { check(opened.exo.currentPosition in 421_000..430_000) { "Opened again at ${opened.exo.currentPosition} ms, not where it was left (421 s on)" } }

            // ---- The pill: back to the start. ----
            await("The start was not offered again") { opened.offersStart() }
            runOnMainSync { opened.skip.performClick() }
            await("The pill did not go back to the start", 15_000) { opened.exo.currentPosition < 5_000 && !opened.offersStart() }
            opened.close()

            // ---- The controls in English, left to right. ----
            runOnMainSync { Api.language = "en" }
            saved = """{"file_id":"$id","time":300,"progress":50}"""
            opened = open()
            await("The start was not offered in English") { opened.offersStart() }
            runOnMainSync { opened.fragment.tickle() }
            await("The poster did not show in English") { opened.activity.findViewById<View>(androidx.leanback.R.id.image)?.let { it.isShown && it.width > 0 } == true }
            SystemClock.sleep(1_500)
            capture("resume-start-over-en")
            // One row: Right from play/pause runs past next into the panels' buttons.
            repeat(3) { sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT) }
            runOnMainSync {
                val panels = opened.activity.findViewById<View>(androidx.leanback.R.id.secondary_controls_dock)
                val focused = opened.activity.currentFocus
                check(focused != null && generateSequence(focused) { it.parent as? View }.any { it === panels }) { "Right did not cross into the panels' buttons: $focused" }
            }
            opened.close()
            result.putString("stream", "PASS: starts at the saved point offering the start, poster shown; again where this TV left it past a lagging server; the pill restarts\n")
        } catch (error: Throwable) {
            result.putString("stream", error.stackTraceToString())
            resultCode = Activity.RESULT_CANCELED
        } finally {
            runOnMainSync { player?.finish() }
            await("Player did not close") { player?.isDestroyed != false }
            runOnMainSync {
                Api.user = originalUser
                Api.language = originalLanguage
                clientField.set(Api, originalClient)
            }
            client.dispatcher.cancelAll()
            poster.delete()
        }
        finish(resultCode, result)
    }

    /** A made-up poster: night over a road, 2:3. */
    private fun poster(out: File) {
        val bitmap = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 0f, 600f, 0xFF14213D.toInt(), 0xFFB5543A.toInt(), Shader.TileMode.CLAMP) })
        canvas.drawCircle(280f, 170f, 60f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF4E3C1.toInt() })
        canvas.drawRect(0f, 470f, 400f, 600f, Paint().apply { color = 0xFF0B0F17.toInt() })
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
    }

    private fun field(instance: Any, name: String): Any? =
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance)

    private fun Instrumentation.await(message: String, timeoutMs: Long = 7_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        do {
            var passed = false
            runOnMainSync { passed = condition() }
            if (passed) return
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        error(message)
    }
}
