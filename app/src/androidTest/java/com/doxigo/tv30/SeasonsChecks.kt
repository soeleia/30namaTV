package com.doxigo.tv30

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.TextView
import androidx.leanback.app.RowsSupportFragment
import androidx.leanback.widget.ListRow
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import java.util.Locale

/**
 * Run the existing instrumentation runner with -e check seasons. A series whose `stream` answer
 * fails, or comes back with no episodes, keeps «قسمت‌ها بارگیری نشد» and why in its seasons' place;
 * Down from Play lands on its Retry, and OK there, once 30nama answers, puts the seasons in its
 * place with the remote on the first episode. Persian fails, English comes back empty. A season
 * out while the page is open then gets its own row on coming back, under its own header. Last, a
 * film left part-way: Play resumes it and says how long is left, and follows the point on coming back.
 */
object SeasonsChecks {
    @Volatile private var answer = "fail"

    fun run(instrumentation: Instrumentation) = with(instrumentation) {
        val result = Bundle()
        var code = Activity.RESULT_OK
        var activity: DetailsActivity? = null
        val originalClient = Api.http
        val originalToken = Api.token
        val originalUser = Api.user
        val originalLanguage = Api.language
        val originalLocale = Locale.getDefault()
        val clientField = Api::class.java.getDeclaredField("http").apply { isAccessible = true }
        // Free, so it streams for a guest: the page asks `stream` for its seasons.
        val series = """{"id":41,"title":{"english":"Seasons Check","local":"آزمون فصل‌ها"},"options":{"title_type":"series","free_stream":true},
            "info":{"year":"2025"},"plot":{"local":"سریالی برای آزمودن جای فصل‌ها وقتی ۳۰نما قسمت‌ها را نمی‌دهد."}}"""
        val files = (1..6).joinToString(",") { """{"info":{"file_id":"s1e$it","season":1,"episode_number":$it},"file":{}}""" }
        val later = (1..2).joinToString(",") { """{"info":{"file_id":"s2e$it","season":2,"episode_number":$it},"file":{}}""" }
        val film = """{"id":43,"title":{"english":"Film Check","local":"آزمون فیلم"},"options":{"title_type":"movie","free_stream":true},"info":{"year":"2025"}}"""
        // A fresh file each run, so its length is the one this run's point implies (Watched keeps the last).
        val reel = "film-${System.currentTimeMillis()}"
        // 20 minutes into 50, then 40: 30 minutes left, then 10.
        fun filmAt(seconds: Int, percent: Int) =
            """{"success":true,"result":{"continue_watching":{"file_id":"$reel","time":$seconds,"progress":$percent},"hls":[{"info":{"file_id":"$reel"},"file":{}}]}}"""
        // Only 30nama's actions are answered; anything else (art, TMDB) is refused, so nothing leaves the emulator.
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val reply = { status: Int, body: String ->
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("-").body(body.toResponseBody()).build()
            }
            if ("/action/" !in request.url.encodedPath) return@addInterceptor reply(404, "")
            reply(200, when (request.url.pathSegments.last()) {
                "single" -> """{"success":true,"result":${if (answer.startsWith("film")) film else series}}"""
                "stream" -> when (answer) {
                    "fail" -> """{"success":false,"msg":"internal error"}"""
                    "empty" -> """{"success":true,"result":{"hls":[]}}"""
                    "more" -> """{"success":true,"result":{"hls":[$files,$later]}}"""
                    "film" -> filmAt(1200, 40)
                    "film-later" -> filmAt(2400, 80)
                    else -> """{"success":true,"result":{"hls":[$files]}}"""
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
                activity = startActivitySync(DetailsActivity.intent(targetContext, JSONObject(series))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as DetailsActivity
                val host = checkNotNull(activity)
                val failed = host.getString(R.string.episodes_failed)
                val why = host.getString(if (broken == "fail") R.string.error_server else R.string.episodes_none)
                val rows = { (host.supportFragmentManager.findFragmentById(R.id.rows) as RowsSupportFragment) }
                fun header(at: Int) = (rows().adapter.takeIf { it.size() > at }?.get(at) as? ListRow)?.headerItem?.name
                await("No note in the seasons' place ($language): ${header(0)}") { header(0) == failed }
                SystemClock.sleep(800) // the page settles: actions, scrims
                capture("seasons-note-$language")
                sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
                var focused = "nothing"
                await({ "Down from Play did not reach the note's Retry ($language): $focused" }) {
                    val note = host.currentFocus
                    focused = "${note?.javaClass?.simpleName} «${note?.contentDescription}»"
                    note?.contentDescription?.toString()?.startsWith(why) == true
                }
                lateinit var lines: List<TextView>
                var whole = false
                runOnMainSync {
                    val note = host.currentFocus as android.view.ViewGroup
                    lines = (0 until note.childCount).map { note.getChildAt(it) as TextView }
                    // Why whole: as many lines as its text needs, none cut short by the pill's width.
                    whole = lines[0].lineCount <= 2 && lines[0].height >= lines[0].layout.height
                }
                check(lines.map { it.text.toString() } == listOf(why, host.getString(R.string.retry))) { "The note says ${lines.map { it.text }} ($language)" }
                check(whole) { "Why is cut ($language): ${lines[0].lineCount} lines in ${lines[0].width}x${lines[0].height}" }
                SystemClock.sleep(600) // the rows rise, the pill turns white
                capture("seasons-retry-$language")
                answer = "ok"
                sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                val season = host.getString(R.string.season_n, 1)
                await("Retry did not bring the seasons ($language): ${header(0)}") { header(0) == season }
                // The note's view goes with it and focus falls back for a layout: the rows must not drop and rise again.
                var drop = 0f
                var actions = 0f
                runOnMainSync {
                    drop = host.findViewById<android.view.View>(R.id.rows).translationY
                    actions = host.findViewById<android.view.View>(R.id.actions).alpha
                }
                check(drop == 0f && actions == 0f) { "The rows bounced ($language): $drop, actions $actions" }
                await("The remote did not stay on the first episode ($language)") {
                    rows().selectedPosition == 0 && rows().verticalGridView.findFocus()?.contentDescription?.startsWith(host.getString(R.string.episode_numbered, "1".localDigits())) == true
                }
                SystemClock.sleep(600)
                capture("seasons-back-$language")
                // A season out while the page was open: back on it, the rows are made again, each
                // under its own header, with the remote still among them and the rows risen.
                answer = "more"
                runOnMainSync { callActivityOnRestart(host) }
                fun tiles(at: Int) = (rows().adapter.takeIf { it.size() > at }?.get(at) as? ListRow)?.adapter?.size()
                var made = ""
                await({ "A season out since has no row of its own ($language): $made" }) {
                    made = "${header(0)} ${tiles(0)}, ${header(1)} ${tiles(1)}"
                    header(0) == season && tiles(0) == 6 && header(1) == host.getString(R.string.season_n, 2) && tiles(1) == 2
                }
                await("The remote left the episodes as their rows were made again ($language)") {
                    host.findViewById<android.view.View>(R.id.rows).translationY == 0f && host.findViewById<android.view.View>(R.id.actions).alpha == 0f &&
                        rows().verticalGridView.findFocus() != null
                }
                runOnMainSync { host.finish() }
                await("Details did not close") { host.isDestroyed }

                // ---- A film left part-way. ----
                answer = "film"
                activity = startActivitySync(DetailsActivity.intent(targetContext, JSONObject(film))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as DetailsActivity
                val page = checkNotNull(activity)
                fun resumes(minutes: Int) = page.getString(R.string.resume) + "  " + page.getString(R.string.time_left, page.getString(R.string.runtime_minutes, minutes))
                var says = ""
                fun play(): String = (page.findViewById<android.view.ViewGroup>(R.id.actions).getChildAt(0) as TextView).text.toString().also { says = it }
                await({ "A film left part-way has «$says» on Play, not «${resumes(30)}» ($language)" }) { play() == resumes(30) }
                SystemClock.sleep(800) // the page settles: actions, scrims
                capture("film-resume-$language")
                answer = "film-later"
                runOnMainSync { callActivityOnRestart(page) }
                await({ "Back on the page, Play still says «$says», not «${resumes(10)}» ($language)" }) { play() == resumes(10) }
                runOnMainSync { page.finish() }
                await("Details did not close") { page.isDestroyed }
                activity = null
            }
            result.putString("stream", "PASS: a failed (Persian) or empty (English) episode answer notes the seasons' place, Down reaches Retry, OK brings the seasons in place, risen, with the remote on episode 1; a season out since gets its own row on coming back; a film left part-way resumes on Play with its time left, which follows the point\n")
        } catch (error: Throwable) {
            code = Activity.RESULT_CANCELED
            result.putString("stream", error.stackTraceToString())
        } finally {
            runOnMainSync { activity?.finish() }
            await("Details did not close") { activity?.isDestroyed != false }
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
