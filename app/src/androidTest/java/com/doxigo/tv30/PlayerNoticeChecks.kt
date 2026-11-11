package com.doxigo.tv30

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import java.util.Locale

/**
 * Run the existing instrumentation runner with -e check playernotice. Play on a film whose `stream`
 * answer fails, or comes back with no file, closes the player, and the title page it leaves the
 * viewer on says why in the app's own notice: the player's own closed with it. A film that loads and
 * then cannot play keeps the player, a note over the video in the toast's place: on the CDN's 403
 * `stream` is asked for again by itself, once, and by Retry after; a server error gets its usual few
 * tries, not the 403's ten, and no new answer. Persian, then English.
 */
object PlayerNoticeChecks {
    @Volatile private var answer = "fail"
    /** How often `stream` was asked for. */
    @Volatile private var asked = 0

    fun run(instrumentation: Instrumentation) = with(instrumentation) {
        val result = Bundle()
        var code = Activity.RESULT_OK
        var activity: DetailsActivity? = null
        var player: Activity? = null
        val originalClient = Api.http
        val originalToken = Api.token
        val originalUser = Api.user
        val originalLanguage = Api.language
        val originalLocale = Locale.getDefault()
        val clientField = Api::class.java.getDeclaredField("http").apply { isAccessible = true }
        // Free, so it plays for a guest; a film's page asks nothing of `stream`, only the player does.
        val film = """{"id":42,"title":{"english":"Player Notice Check","local":"آزمون پیام پخش"},"options":{"title_type":"movie","free_stream":true},
            "info":{"year":"2025"},"plot":{"local":"فیلمی برای آزمودن پیام پخش وقتی ۳۰نما ویدیو را نمی‌دهد."}}"""
        // Only 30nama's actions are answered; anything else (art, TMDB) is refused, so nothing leaves the emulator.
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val reply = { status: Int, body: String ->
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("-").body(body.toResponseBody()).build()
            }
            // The film's playlist, as the CDN would refuse it: the player's own client is built from this one.
            if (request.url.encodedPath.endsWith(".m3u8")) return@addInterceptor reply(if (answer == "forbidden") 403 else 500, "")
            if ("/action/" !in request.url.encodedPath) return@addInterceptor reply(404, "")
            if (request.url.pathSegments.last() == "stream") asked++
            reply(200, when (request.url.pathSegments.last()) {
                "single" -> """{"success":true,"result":$film}"""
                "stream" -> when (answer) {
                    "fail" -> """{"success":false,"msg":"internal error"}"""
                    "empty" -> """{"success":true,"result":{"hls":[]}}"""
                    else -> """{"success":true,"result":{"hls":[{"info":{"file_id":"film"},"file":{"m3u8":"https://cdn.invalid/film.m3u8"}}]}}"""
                }
                else -> """{"success":true,"result":{}}"""
            })
        }.build()
        try {
            for (language in listOf("fa", "en")) {
                runOnMainSync {
                    clientField.set(Api, client)
                    Api.language = language
                    Api.token = null
                    Api.user = null
                }
                activity = startActivitySync(DetailsActivity.intent(targetContext, JSONObject(film))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as DetailsActivity
                val host = checkNotNull(activity)
                for ((broken, words) in listOf("fail" to R.string.error_server, "empty" to R.string.player_no_files)) {
                    answer = broken
                    val said = host.getString(words)
                    await({ "Play does not have the remote ($language, $broken): it is on ${host.currentFocus.let { (it as? TextView)?.text ?: it }}" }) {
                        (host.currentFocus as? TextView)?.text?.toString() == host.getString(R.string.watch)
                    }
                    val opened = addMonitor(PlayerActivity::class.java.name, null, false)
                    sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                    player = waitForMonitorWithTimeout(opened, 7_000)
                    removeMonitor(opened)
                    val shut = checkNotNull(player) { "OK on Play did not open the player ($language, $broken)" }
                    // Finishing, not destroyed: that waits on the page beneath going idle, which its motion can put off.
                    await("The player did not close ($language, $broken)") { shut.isFinishing || shut.isDestroyed }
                    await("The title page is not back in front ($language, $broken)") { host.lifecycle.currentState == Lifecycle.State.RESUMED }
                    var shown = "nothing"
                    await({ "The title page says «$shown», not «$said» ($language, $broken)" }) {
                        val notice = host.window.decorView.findViewWithTag<TextView>("notice")
                        shown = notice?.text?.toString() ?: "nothing"
                        shown == said && (notice?.alpha ?: 0f) > 0f
                    }
                    SystemClock.sleep(400) // the notice rises in
                    capture("player-notice-$broken-$language")
                    player = null
                }
                // ---- A film that loads and then cannot play: the player stays, under its note. ----
                for (broken in listOf("forbidden", "down")) {
                    answer = broken
                    asked = 0
                    await({ "Play does not have the remote ($language, $broken): it is on ${host.currentFocus.let { (it as? TextView)?.text ?: it }}" }) {
                        (host.currentFocus as? TextView)?.text?.toString() == host.getString(R.string.watch)
                    }
                    val opened = addMonitor(PlayerActivity::class.java.name, null, false)
                    sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                    player = waitForMonitorWithTimeout(opened, 7_000)
                    removeMonitor(opened)
                    val stuck = checkNotNull(player) { "OK on Play did not open the player ($language, $broken)" }
                    fun noted(): Boolean {
                        val note = stuck.findViewById<View>(R.id.player_note) ?: return false
                        return note.visibility == View.VISIBLE && note.alpha == 1f && stuck.findViewById<View>(R.id.player_note_action).isFocused &&
                            stuck.findViewById<TextView>(R.id.player_note_why).text.toString() == stuck.getString(R.string.player_error_server)
                    }
                    // A 403 has its ten quick tries twice over, either side of the new answer; a 500
                    // its usual three, a second apart and more: ten of those took over half a minute.
                    await("No note with Retry on the remote ($language, $broken)", 15_000) { noted() }
                    runOnMainSync {
                        check(!stuck.isFinishing) { "The player closed on a film that would not play ($language, $broken)" }
                        val wanted = if (broken == "forbidden") 2 else 1
                        check(asked == wanted) { "`stream` was asked for $asked times before the note, not $wanted ($language, $broken)" }
                    }
                    if (broken == "forbidden") {
                        capture("player-note-$language")
                        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                        await("Retry did not ask for `stream` again ($language)") { asked == 3 }
                        await("The note did not come back when Retry failed too ($language)", 15_000) { noted() }
                        runOnMainSync { check(asked == 3) { "Retry's failure asked for `stream` again by itself: $asked times ($language)" } }
                    }
                    sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                    await("Back on the note did not leave the player ($language, $broken)") { stuck.isFinishing || stuck.isDestroyed }
                    await("The title page is not back in front ($language, $broken)") { host.lifecycle.currentState == Lifecycle.State.RESUMED }
                    player = null
                }
                runOnMainSync { host.finish() }
                await("Details did not close") { host.isDestroyed }
                activity = null
            }
            result.putString("stream", "PASS: a failed or empty stream answer closes the player and the title page beneath says why in its own notice; a film that cannot play keeps the player under its note (a 403 asks for stream again once, then Retry does; a 500 is said within its usual tries), Back leaving it; in Persian and English\n")
        } catch (error: Throwable) {
            code = Activity.RESULT_CANCELED
            result.putString("stream", error.stackTraceToString())
        } finally {
            runOnMainSync {
                player?.finish()
                activity?.finish()
            }
            await("Details did not close") { activity?.isDestroyed != false }
            runOnMainSync {
                clientField.set(Api, originalClient)
                Api.token = originalToken
                Api.user = originalUser
                Api.language = originalLanguage
                Locale.setDefault(originalLocale)
                parting = null
            }
            client.dispatcher.cancelAll()
        }
        finish(code, result)
    }

    private fun Instrumentation.await(message: String, timeoutMs: Long = 7_000, condition: () -> Boolean) = await({ message }, timeoutMs, condition)

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
