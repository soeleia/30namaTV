package com.doxigo.tv30

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.leanback.widget.VerticalGridView
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Run the existing instrumentation runner with -e check gridnote. A catalogue whose first page
 * fails, or whose plain archive comes back empty, says so in the grid's place with why and Retry on
 * the remote; OK there brings the skeleton back with the remote waiting on the grid, and once
 * 30nama answers, the posters with the remote on the first. Persian fails, English comes back empty.
 */
object GridNoteChecks {
    @Volatile private var answer = "fail"
    /** Holds the answer Retry asks for, so the skeleton can be seen in the note's place meanwhile. */
    @Volatile private var hold = CountDownLatch(0)

    fun run(instrumentation: Instrumentation) = with(instrumentation) {
        val result = Bundle()
        var code = Activity.RESULT_OK
        var activity: GridActivity? = null
        val originalClient = Api.http
        val originalToken = Api.token
        val originalUser = Api.user
        val originalLanguage = Api.language
        val originalLocale = Locale.getDefault()
        val clientField = Api::class.java.getDeclaredField("http").apply { isAccessible = true }
        val posts = (1..8).joinToString(",") {
            """{"id":${900 + it},"title":{"english":"Grid Note $it"},"options":{"title_type":"movie"},"info":{"year":"2025"},"score":{"imdb":{"score":7.$it}}}"""
        }
        // Only 30nama's actions are answered; anything else (art) is refused, so nothing leaves the emulator.
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val reply = { status: Int, body: String ->
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("-").body(body.toResponseBody()).build()
            }
            if ("/action/" !in request.url.encodedPath) return@addInterceptor reply(404, "")
            reply(200, when (request.url.pathSegments.last()) {
                "archive" -> when (answer) {
                    "fail" -> """{"success":false,"msg":"internal error"}"""
                    "empty" -> """{"success":true,"result":{"posts":[],"page":1,"pages":1}}"""
                    else -> {
                        hold.await(10, TimeUnit.SECONDS)
                        """{"success":true,"result":{"posts":[$posts],"page":1,"pages":1}}"""
                    }
                }
                else -> """{"success":true,"result":{}}"""
            })
        }.build()
        try {
            for ((language, broken) in listOf("fa" to "fail", "en" to "empty")) {
                answer = broken
                runOnMainSync {
                    clientField.set(Api, client)
                    Api.language = language
                    Api.token = null
                    Api.user = null
                }
                // A genre: the plain archive, with no filters to load beside it.
                activity = startActivitySync(GridActivity.archive(targetContext, "drama", if (language == "fa") "درام" else "Drama")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as GridActivity
                val host = checkNotNull(activity)
                val grid = host.findViewById<VerticalGridView>(androidx.leanback.R.id.browse_grid)
                val title = host.getString(if (broken == "fail") R.string.grid_failed else R.string.grid_empty_title)
                val why = host.getString(if (broken == "fail") R.string.error_server else R.string.grid_empty_hint)
                fun text(id: Int) = host.findViewById<TextView>(id)?.text?.toString()
                fun cards() = (host.supportFragmentManager.findFragmentById(R.id.browse_host) as GridFragment).adapter!!
                var seen = "nothing"
                await({ "No note in the grid's place with the remote on Retry ($language): $seen" }) {
                    seen = "«${text(R.id.list_empty_title)}», the remote on ${host.currentFocus?.javaClass?.simpleName}"
                    text(R.id.list_empty_title) == title && host.currentFocus?.id == R.id.list_empty_action && grid.visibility == View.GONE
                }
                var said = emptyList<String?>()
                runOnMainSync { said = listOf(text(R.id.list_empty_hint), text(R.id.list_empty_action)) }
                check(said == listOf(why, host.getString(R.string.retry))) { "The note says $said ($language)" }
                SystemClock.sleep(600) // the note rises, the pill turns white
                capture("grid-note-$language")
                answer = "ok"
                hold = CountDownLatch(1)
                sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                await("Retry did not bring the skeleton back with the remote on the grid ($language)") {
                    host.findViewById<View>(R.id.list_empty) == null && grid.visibility == View.VISIBLE && grid.hasFocus() &&
                        cards().let { it.size() > 0 && it[0] is Skeleton }
                }
                hold.countDown()
                await({ "Retry did not fill the grid with the remote on the first poster ($language): $seen" }) {
                    val first = cards().takeIf { it.size() > 0 }?.get(0) as? Card
                    seen = "${cards().size()} cards from «${first?.title}», the remote on ${host.currentFocus?.javaClass?.simpleName} at ${grid.selectedPosition}"
                    first?.title == "Grid Note 1" && cards().size() == 8 && grid.selectedPosition == 0 &&
                        grid.findViewHolderForAdapterPosition(0)?.itemView?.hasFocus() == true
                }
                SystemClock.sleep(600) // the posters settle, the first one lifts
                capture("grid-note-back-$language")
                runOnMainSync { host.finish() }
                await("Grid did not close") { host.isDestroyed }
                activity = null
            }
            result.putString("stream", "PASS: a failed (Persian) or empty (English) first page notes the grid's place with why and Retry on the remote; OK brings the skeleton back, then the posters with the remote on the first\n")
        } catch (error: Throwable) {
            code = Activity.RESULT_CANCELED
            result.putString("stream", error.stackTraceToString())
        } finally {
            hold.countDown()
            runOnMainSync { activity?.finish() }
            await("Grid did not close") { activity?.isDestroyed != false }
            runOnMainSync {
                clientField.set(Api, originalClient)
                Api.token = originalToken
                Api.user = originalUser
                Api.language = originalLanguage
                Locale.setDefault(originalLocale)
            }
            client.dispatcher.cancelAll()
        }
        finish(code, result)
    }

    private fun Instrumentation.await(message: String, condition: () -> Boolean) = await({ message }, condition)

    private fun Instrumentation.await(message: () -> String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 7_000
        do {
            var passed = false
            runOnMainSync { passed = condition() }
            if (passed) return
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        error(message())
    }
}
