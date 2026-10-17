package com.doxigo.tv30

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.leanback.app.RowsSupportFragment
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.Locale

/** Home's ranked most-popular rows: place, order, captions, D-pad and the whole catalogue in that order. */
object RankChecks {
    fun run(instrumentation: Instrumentation) = with(instrumentation) {
        val result = Bundle()
        var resultCode = Activity.RESULT_OK
        var homeActivity: MainActivity? = null
        var gridActivity: GridActivity? = null
        val originalClient = Api.http
        val originalToken = Api.token
        val originalUser = Api.user
        val originalLanguage = Api.language
        val originalLocale = Locale.getDefault()
        val originalIntro = Intro.shown
        val clientField = Api::class.java.getDeclaredField("http").apply { isAccessible = true }
        val orders = Collections.synchronizedList(mutableListOf<String>())
        fun posts(name: String, count: Int) = JSONArray().apply {
            for (i in 0 until count) put(JSONObject().put("id", name.hashCode() + i).put("title", JSONObject().put("english", "$name $i"))
                .put("options", JSONObject().put("title_type", "movie")).put("info", JSONObject().put("year", "${2000 + i}"))
                .put("score", JSONObject().put("imdb", JSONObject().put("score", 7.0 + i / 10.0))))
        }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val form = request.body as? FormBody
            val sent = (0 until (form?.size ?: 0)).associate { form!!.name(it) to form.value(it) }
            val data = when (request.url.pathSegments.last()) {
                "home_stream" -> JSONObject().put("suggested", JSONObject().put("posts", posts("Suggested", 4)))
                    .put("movies", JSONObject().put("posts", posts("Movie", 4)))
                "archive" -> {
                    orders += "${sent["slugs"]} ${sent["orderby"]}"
                    // A full page, as the archive answers: the rows keep its first ten.
                    JSONObject().put("posts", posts(if (sent["slugs"] == "series") "Series" else "Film", 24)).put("page", 1).put("pages", 3)
                }
                else -> JSONObject()
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(JSONObject().put("success", true).put("result", data).toString().toResponseBody()).build()
        }.build()
        try {
            for (language in listOf("fa", "en")) {
                runOnMainSync {
                    Api.language = language
                    Api.token = null
                    Api.user = null
                    Intro.shown = true
                    clientField.set(Api, client)
                    targetContext.filesDir.resolve("home").deleteRecursively()
                }
                orders.clear()
                homeActivity = startActivitySync(Intent(targetContext, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_GUEST, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
                val homeHost = checkNotNull(homeActivity)
                lateinit var home: HomeFragment
                val places = (1..10).map { it.toString().localDigits() }
                await("Ranked rows did not load") {
                    val found = homeHost.supportFragmentManager.findFragmentById(R.id.browse_host) as? HomeFragment ?: return@await false
                    val rows = (0 until found.adapter.size()).map { found.adapter[it] as ListRow }
                    val ranked = rows.filter { it.id == HomeFragment.LOVED_MOVIES.toLong() || it.id == HomeFragment.LOVED_SERIES.toLong() }
                    if (ranked.size < 2 || found.view?.findViewById<View>(androidx.leanback.R.id.browse_container_dock)?.alpha != 1f) false else {
                        home = found
                        val order = listOf(HomeFragment.SUGGESTED, HomeFragment.LOVED_MOVIES, HomeFragment.LOVED_SERIES, HomeFragment.MOVIES).map(Int::toLong)
                        check(rows.map { it.id }.filter { it in order } == order) { "Ranked rows are not between Suggested and Movies: ${rows.map { it.id }}" }
                        check(ranked.map { it.headerItem.name } == listOf(homeHost.getString(R.string.loved_movies), homeHost.getString(R.string.loved_series)))
                        for (row in ranked) {
                            val cards = (0 until row.adapter.size()).map { row.adapter[it] as Card }
                            check(cards.size == 11 && cards.last().icon == R.drawable.ic_more) { "A ranked row is not ten titles and More" }
                            check(cards.take(10).map { it.numeral } == places) { "Ranks are not ${places.joinToString()}: ${cards.map { it.numeral }}" }
                            check(cards.take(10).all { !it.facts.isNullOrBlank() }) { "A ranked poster lost its score and year" }
                        }
                        true
                    }
                }
                check(orders.sorted() == listOf("movies favorite", "series favorite")) { "Ranked rows asked for $orders" }
                await("Ranked rows were not kept for the next launch") {
                    listOf("movies", "series").all { targetContext.filesDir.resolve("home/${HomeCache.loved(it)}.json").exists() }
                }
                val rowAt = { key: Int -> (0 until home.adapter.size()).indexOfFirst { (home.adapter[it] as ListRow).id == key.toLong() } }
                fun rowHolder(key: Int) = (home.mainFragment as? RowsSupportFragment)?.getRowViewHolder(rowAt(key)) as? ListRowPresenter.ViewHolder
                runOnMainSync { homeHost.jumpTo(HomeFragment.LOVED_MOVIES) }
                lateinit var row: ListRowPresenter.ViewHolder
                await("The ranked row did not take the remote") {
                    val rows = (home.mainFragment as? RowsSupportFragment)?.verticalGridView
                    val found = rowHolder(HomeFragment.LOVED_MOVIES)
                    if (found == null || rows?.selectedPosition != rowAt(HomeFragment.LOVED_MOVIES) || rows.scrollState != 0 || !found.gridView.hasFocus()) false else {
                        row = found
                        true
                    }
                }
                runOnMainSync {
                    val card = row.gridView.findViewHolderForAdapterPosition(0)!!.itemView as ImageCardView
                    val dp = card.resources.displayMetrics.density
                    val image = card.mainImageView!!
                    val caption = card.findViewById<View>(androidx.leanback.R.id.title_text)
                    check(card.width == (108 * dp).toInt() + (72 * dp).toInt()) { "A ranked card is ${card.width}px, not the poster and its place" }
                    // Leanback's measureChild once took the place's padding off the caption.
                    check(caption.width >= image.width - (16 * dp).toInt()) { "The caption is ${caption.width}px under a ${image.width}px poster" }
                    check(card.contentDescription.startsWith(places[0])) { "The first poster does not say its place: ${card.contentDescription}" }
                    // Inter's zeros run wide: «IMDb 7.0 2000» once lost its year to «…» at 108dp.
                    for (i in 0 until row.gridView.childCount) {
                        val facts = row.gridView.getChildAt(i).findViewById<TextView>(androidx.leanback.R.id.content_text) ?: continue
                        check(facts.layout?.getEllipsisCount(0) == 0) { "A poster's score and year are cut: ${facts.text}" }
                    }
                }
                SystemClock.sleep(400) // the billboard's art settles before the picture
                capture("ranks-$language")
                val forward = if (language == "fa") KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
                press(forward)
                // Each press waits for the last one's scroll to settle, as a viewer's would on CI's slow TV.
                await("The ranked row does not move along in reading order") { row.gridView.selectedPosition == 1 && row.gridView.hasFocus() && row.gridView.scrollState == 0 }
                val rows = checkNotNull((home.mainFragment as? RowsSupportFragment)?.verticalGridView)
                press(KeyEvent.KEYCODE_DPAD_DOWN)
                await("Down did not reach the ranked series") { rowHolder(HomeFragment.LOVED_SERIES)?.gridView?.hasFocus() == true && rows.scrollState == 0 }
                press(KeyEvent.KEYCODE_DPAD_UP)
                await("Up did not return to the same ranked film") { row.gridView.hasFocus() && row.gridView.selectedPosition == 1 && rows.scrollState == 0 }
                runOnMainSync { row.gridView.selectedPosition = 10; row.gridView.requestFocus() }
                await("More was not reachable") { row.gridView.findViewHolderForAdapterPosition(10)?.itemView?.hasFocus() == true && row.gridView.scrollState == 0 }
                SystemClock.sleep(400)
                capture("ranks-end-$language")
                val opened = addMonitor(GridActivity::class.java.name, null, false)
                press(KeyEvent.KEYCODE_DPAD_CENTER)
                gridActivity = waitForMonitorWithTimeout(opened, 5_000) as? GridActivity
                removeMonitor(opened)
                val grid = checkNotNull(gridActivity) { "More did not open the catalogue" }
                await("The catalogue did not open in its most-popular order") {
                    // The button names the order the pages are asked for (GridFragment's sorts).
                    grid.intent.getStringExtra("slug") == "movies" && grid.findViewById<Button>(R.id.grid_sort)?.text?.toString() == grid.getString(R.string.sort_favorite) &&
                        grid.hasWindowFocus() // Back before then goes nowhere on a slow TV
                }
                press(KeyEvent.KEYCODE_BACK)
                await("Back did not return to More") { grid.isDestroyed && row.gridView.hasFocus() && row.gridView.selectedPosition == 10 }
                gridActivity = null
                runOnMainSync { homeHost.finish() }
                await("Home did not close") { homeHost.isDestroyed }
                homeActivity = null
            }
            result.putString("stream", "PASS: ranked most-popular rows between Suggested and Movies, ten places with captions in Persian and English, kept, D-pad along and between rows, More in that order and Back\n")
        } catch (error: Throwable) {
            capture("ranks-failure")
            result.putString("stream", error.stackTraceToString())
            resultCode = Activity.RESULT_CANCELED
        } finally {
            runOnMainSync { gridActivity?.finish(); homeActivity?.finish() }
            await("Ranked check activities did not close") { listOf(gridActivity, homeActivity).all { it?.isDestroyed != false } }
            runOnMainSync {
                Api.token = originalToken
                Api.user = originalUser
                Api.language = originalLanguage
                Locale.setDefault(originalLocale)
                Intro.shown = originalIntro
                clientField.set(Api, originalClient)
                targetContext.filesDir.resolve("home").deleteRecursively()
            }
            client.dispatcher.cancelAll()
        }
        finish(resultCode, result)
    }

    private fun Instrumentation.press(code: Int) {
        sendKeySync(KeyEvent(KeyEvent.ACTION_DOWN, code).apply { source = InputDevice.SOURCE_DPAD })
        sendKeySync(KeyEvent(KeyEvent.ACTION_UP, code).apply { source = InputDevice.SOURCE_DPAD })
        waitForIdleSync()
    }

    private fun Instrumentation.await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 7_000
        do {
            var passed = false
            runOnMainSync { passed = condition() }
            if (passed) return
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        error(message)
    }
}
