package com.doxigo.tv30

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.leanback.widget.ListRow
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Run the existing instrumentation runner with -e check homenote. Home with nothing kept from the
 * last run and a home_stream that fails says so in its rows' place, «صفحهٔ اصلی بارگیری نشد» over
 * why and Retry, which has the remote with the rail a press away; OK there, once 30nama answers,
 * brings home up as a first launch does, with the remote on the hero. In Persian and English.
 */
object HomeNoteChecks {
    @Volatile private var fail = true

    fun run(instrumentation: Instrumentation) = with(instrumentation) {
        val result = Bundle()
        var code = Activity.RESULT_OK
        var activity: MainActivity? = null
        val originalClient = Api.http
        val originalToken = Api.token
        val originalUser = Api.user
        val originalLanguage = Api.language
        val originalLocale = Locale.getDefault()
        val originalIntro = Intro.shown
        val clientField = Api::class.java.getDeclaredField("http").apply { isAccessible = true }
        fun posts(name: String, count: Int) = JSONArray().apply {
            for (i in 0 until count) put(JSONObject().put("id", name.hashCode() + i).put("title", JSONObject().put("english", "$name $i"))
                .put("options", JSONObject().put("title_type", "movie")).put("info", JSONObject().put("year", "${2010 + i}"))
                .put("plot", JSONObject().put("english", "A fixture title for home's first answer."))
                .put("score", JSONObject().put("imdb", JSONObject().put("score", 7.0 + i / 10.0))))
        }
        // Two promoted titles over Suggested and Movies, as UpdatesChecks' home.
        val home = JSONObject()
            .put("hero_header", JSONObject().put("posts", posts("Promoted", 2)))
            .put("suggested", JSONObject().put("posts", posts("Suggested", 6)))
            .put("movies", JSONObject().put("posts", posts("Movie", 6)))
        // Only 30nama's actions are answered; anything else (art, TMDB) is refused, so nothing leaves the emulator.
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val reply = { status: Int, body: String ->
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("-").body(body.toResponseBody()).build()
            }
            if ("/action/" !in request.url.encodedPath) return@addInterceptor reply(404, "")
            reply(200, when (request.url.pathSegments.last()) {
                "home_stream" -> if (fail) """{"success":false}""" else """{"success":true,"result":$home}"""
                else -> """{"success":true,"result":{}}"""
            })
        }.build()
        try {
            for (language in listOf("fa", "en")) {
                fail = true
                runOnMainSync {
                    clientField.set(Api, client)
                    Api.language = language
                    Api.token = null
                    Api.user = null
                    Intro.shown = true
                    // Home kept from an earlier run would be drawn instead, its failure only a notice.
                    targetContext.filesDir.resolve("home").deleteRecursively()
                }
                activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_GUEST, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
                val host = checkNotNull(activity)
                val retry = { host.findViewById<View>(R.id.home_note_retry) }
                await("No note in home's rows' place ($language)") {
                    host.findViewById<View>(R.id.home_note)?.alpha == 1f && retry()?.hasFocus() == true
                }
                runOnMainSync {
                    val title = host.findViewById<TextView>(R.id.home_note_title).text.toString()
                    val why = host.findViewById<TextView>(R.id.home_note_hint).text.toString()
                    check(title == host.getString(R.string.home_failed) && why == host.getString(R.string.error_server)) { "The note says «$title» «$why» ($language)" }
                    check(host.findViewById<View>(androidx.leanback.R.id.browse_container_dock).visibility == View.GONE) { "The rows' dock stayed under the note ($language)" }
                }
                // Up and Down have nowhere to go; toward the start edge is the rail, and back out of it Retry.
                for (key in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) {
                    sendKeyDownUpSync(key)
                    runOnMainSync { check(retry().hasFocus()) { "Key $key took the remote off Retry to ${host.currentFocus} ($language)" } }
                }
                sendKeyDownUpSync(if (language == "fa") KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT)
                await("The rail was out of reach from Retry ($language)") { host.findViewById<View>(R.id.rail).hasFocus() }
                sendKeyDownUpSync(if (language == "fa") KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT)
                await("The rail did not hand the remote back to Retry ($language)") { retry().hasFocus() }
                SystemClock.sleep(500) // the rail shuts, the pill settles white
                capture("home-note-$language")

                fail = false
                sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                await("Retry did not bring home up on the hero ($language)", timeout = 10_000) {
                    val fragment = host.supportFragmentManager.findFragmentById(R.id.browse_host) as? HomeFragment
                    val rows = fragment?.adapter?.let { adapter -> (0 until adapter.size()).map { (adapter[it] as ListRow).id } }.orEmpty()
                    host.findViewById<View>(R.id.home_note) == null && HomeFragment.SUGGESTED.toLong() in rows &&
                        host.findViewById<View>(R.id.promo_more)?.hasFocus() == true &&
                        host.findViewById<View>(androidx.leanback.R.id.browse_container_dock)?.alpha == 1f
                }
                SystemClock.sleep(600) // the hero's button and dots finish rising
                capture("home-note-retried-$language")
                runOnMainSync { host.finish() }
                await("Home did not close") { host.isDestroyed }
                activity = null
            }
            result.putString("stream", "PASS: with nothing kept, a failed home_stream (Persian, English) says so in the rows' place with Retry focused, Up/Down stay on it, the rail is a press away and hands it back, and OK brings home up on the hero\n")
        } catch (error: Throwable) {
            code = Activity.RESULT_CANCELED
            runCatching { capture("home-note-failure") }
            result.putString("stream", error.stackTraceToString())
        } finally {
            runOnMainSync { activity?.finish() }
            await("Home did not close") { activity?.isDestroyed != false }
            runOnMainSync {
                clientField.set(Api, originalClient)
                Api.token = originalToken
                Api.user = originalUser
                Api.language = originalLanguage
                Locale.setDefault(originalLocale)
                Intro.shown = originalIntro
                targetContext.filesDir.resolve("home").deleteRecursively()
            }
            client.dispatcher.cancelAll()
        }
        finish(code, result)
    }

    private fun Instrumentation.await(message: String, timeout: Long = 7_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        do {
            var passed = false
            runOnMainSync { passed = condition() }
            if (passed) return
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        error(message)
    }
}
