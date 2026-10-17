package com.doxigo.tv30

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.leanback.widget.VerticalGridView
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A catalogue's filters on fixture answers, in Persian and English: the pills from
 * advanced_search_parameters, reached along the bar with the D-pad; a score floor and a sort
 * paging through advanced_search in the shapes the server reads; a long country list scrolling;
 * a page left in flight by a newer filter dropped; page 2 asked with the same filter; clear and an
 * empty result's button back to the archive.
 */
object FiltersChecks {
    private class Call(val action: String, val form: Map<String, String>) {
        val parameters get() = form["parameters"]?.let(::JSONObject)
    }

    fun run(instrumentation: Instrumentation) = with(instrumentation) {
        val result = Bundle()
        var resultCode = Activity.RESULT_OK
        var activity: GridActivity? = null
        val originalClient = Api.http
        val originalToken = Api.token
        val originalUser = Api.user
        val originalLanguage = Api.language
        val originalLocale = Locale.getDefault()
        val clientField = Api::class.java.getDeclaredField("http").apply { isAccessible = true }
        val calls = CopyOnWriteArrayList<Call>()
        var slow = CountDownLatch(1)
        val staleAnswered = java.util.concurrent.atomic.AtomicBoolean(false)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val action = request.url.pathSegments.last()
            val form = (request.body as? FormBody)?.let { body -> (0 until body.size).associate { body.name(it) to body.value(it) } }.orEmpty()
            val call = Call(action, form).also { calls += it }
            val parameters = call.parameters
            val page = form["page"]?.toIntOrNull() ?: 1
            // The slow country, alone, answers only once released: by then a newer filter has replaced it.
            val stale = parameters?.optString("country") == "SLOW" && !parameters.has("min_year")
            if (stale) slow.await(20, TimeUnit.SECONDS)
            val data = when (action) {
                "advanced_search_parameters" -> choices(Api.language == "fa")
                "archive" -> posts("archive ${form["orderby"]}", page)
                "advanced_search" ->
                    if (parameters?.optJSONArray("genre")?.optString(0) == "none") JSONObject().put("posts", JSONArray()).put("page", 1).put("pages", 0)
                    else posts("adv ${form["orderby"]} ${signature(checkNotNull(parameters))}", page)
                else -> JSONObject()
            }
            if (stale) staleAnswered.set(true)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(JSONObject().put("success", true).put("result", data).toString().toResponseBody()).build()
        }.build()
        try {
            for (language in listOf("fa", "en")) {
                val fa = language == "fa"
                val toStart = if (fa) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
                val toEnd = if (fa) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
                calls.clear()
                slow = CountDownLatch(1)
                staleAnswered.set(false)
                runOnMainSync {
                    Api.language = language
                    Api.token = null
                    Api.user = null
                    clientField.set(Api, client)
                }
                val host = startActivitySync(GridActivity.archive(targetContext, "movies", if (fa) "فیلم‌ها" else "Movies")
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) as GridActivity
                activity = host
                fun <T : View> view(id: Int): T = host.findViewById(id)
                val grid = view<VerticalGridView>(androidx.leanback.R.id.browse_grid)
                fun first(at: Int = 0): String? = (host.supportFragmentManager.findFragmentById(R.id.browse_host) as? GridFragment)
                    ?.adapter?.let { if (it.size() > at) (it[at] as? Card)?.title else null }
                fun focused() = host.currentFocus
                fun focusedId() = focused()?.id
                fun advanced() = calls.lastOrNull { it.action == "advanced_search" }
                // A first page's card is selected as it lands, which asks for page 2 at once: what a
                // change asked first is its page 1.
                fun firstSince(mark: Int) = calls.drop(mark).firstOrNull { it.action == "advanced_search" }

                await("Archive or the filters did not load") {
                    first() == "archive relevant p1 0" && view<View>(R.id.grid_filters).visibility == View.VISIBLE &&
                        view<View>(R.id.filter_clear).visibility == View.INVISIBLE
                }
                runOnMainSync {
                    check(view<Button>(R.id.filter_genre).text.toString() == host.getString(R.string.filter_genre))
                    check(view<Button>(R.id.filter_score).text.toString() == "IMDb")
                    check(view<Button>(R.id.filter_decade).text.toString() == host.getString(R.string.filter_decade))
                    check(calls.none { it.action == "advanced_search" }) { "An unfiltered catalogue asked advanced_search" }
                }

                // Up from the first column reaches the first filter; along the row; Up to sort and
                // no further; Down to the grid.
                fun upFromGrid() {
                    runOnMainSync { grid.selectedPosition = 0; grid.requestFocus() }
                    await("Grid did not take focus") { grid.hasFocus() && grid.scrollState == 0 }
                    press(KeyEvent.KEYCODE_DPAD_UP)
                    await("Up from the first column did not reach genre") { focusedId() == R.id.filter_genre }
                }
                upFromGrid()
                press(toEnd)
                await("The filters did not move from genre to country") { focusedId() == R.id.filter_country }
                press(toEnd)
                await("The filters did not move to the score") { focusedId() == R.id.filter_score }
                press(KeyEvent.KEYCODE_DPAD_UP)
                await("Up from a filter did not reach sort") { focusedId() == R.id.grid_sort }
                press(KeyEvent.KEYCODE_DPAD_UP)
                await("Up left the bar") { focusedId() == R.id.grid_sort }
                press(KeyEvent.KEYCODE_DPAD_DOWN)
                await("Down from sort did not reach the grid") { grid.hasFocus() }
                upFromGrid()
                press(toEnd); press(toEnd)
                await("The filters did not move back to the score") { focusedId() == R.id.filter_score }

                // The score picker offers the floors only; IMDb 7+ narrows through advanced_search.
                press(KeyEvent.KEYCODE_DPAD_CENTER)
                await("The score picker did not open on its first row") { focused()?.contentDescription == host.getString(R.string.filter_any_score) }
                repeat(4) { press(KeyEvent.KEYCODE_DPAD_DOWN) } // past the last floor: no "under 5"
                await("The score picker's last row is not IMDb 7+") { focused()?.contentDescription == Filter.scoreLabel(7.0, fa) }
                var mark = calls.size
                press(KeyEvent.KEYCODE_DPAD_CENTER)
                await("IMDb 7+ did not reach advanced_search", detail = { "${firstSince(mark)?.form}" }) {
                    val call = firstSince(mark)
                    call != null && call.form["orderby"] == "relevant" && call.form["page"] == "1" && call.form["order"] == "DESC" &&
                        call.parameters.toString() == """{"stream":"true","type":["movie"],"imdb_rate":7}""" &&
                        first() == "adv relevant s7 p1 0" && focusedId() == R.id.filter_score
                }
                runOnMainSync {
                    check(view<Button>(R.id.filter_score).text.toString() == Filter.scoreLabel(7.0, fa))
                    check(view<View>(R.id.filter_clear).visibility == View.VISIBLE)
                }

                // A sort keeps the filter.
                press(KeyEvent.KEYCODE_DPAD_UP)
                await("Up from the score did not reach sort") { focusedId() == R.id.grid_sort }
                press(KeyEvent.KEYCODE_DPAD_CENTER)
                await("The sort picker did not open") { focused()?.contentDescription == host.getString(R.string.sort_relevant) }
                repeat(4) { press(KeyEvent.KEYCODE_DPAD_DOWN) }
                await("The sort picker did not reach IMDb") { focused()?.contentDescription == host.getString(R.string.sort_imdb) }
                mark = calls.size
                press(KeyEvent.KEYCODE_DPAD_CENTER)
                await("The sort did not keep the filter", detail = { "${firstSince(mark)?.form}" }) {
                    val call = firstSince(mark)
                    call?.form?.get("orderby") == "imdb-rate" && call.form["page"] == "1" && first() == "adv imdb-rate s7 p1 0"
                }

                // The country list scrolls to its last row, which answers slowly; a decade set
                // meanwhile replaces it, and the slow page never lands.
                upFromGrid()
                press(toEnd)
                await("The filters did not reach country") { focusedId() == R.id.filter_country }
                press(KeyEvent.KEYCODE_DPAD_CENTER)
                await("The country picker did not open") { focused()?.contentDescription == host.getString(R.string.filter_all_countries) }
                repeat(COUNTRIES) { press(KeyEvent.KEYCODE_DPAD_DOWN) }
                await("The country list did not scroll to its last row") {
                    val row = focused()
                    val scroll = row?.parent?.parent as? ScrollView
                    val shown = Rect()
                    row?.contentDescription == (if (fa) "کند" else "Slow") && scroll != null && scroll.scrollY > 0 &&
                        row.getGlobalVisibleRect(shown) && shown.height() == row.height
                }
                settle()
                if (fa) capture("filters-country")
                press(KeyEvent.KEYCODE_DPAD_CENTER)
                await("The slow country was not asked") { advanced()?.parameters?.optString("country") == "SLOW" }
                press(toEnd); press(toEnd)
                await("The bar did not reach decade") { focusedId() == R.id.filter_decade }
                press(KeyEvent.KEYCODE_DPAD_CENTER)
                await("The decade picker did not open") { focused()?.contentDescription == host.getString(R.string.filter_any_decade) }
                repeat(2) { press(KeyEvent.KEYCODE_DPAD_DOWN) }
                mark = calls.size
                press(KeyEvent.KEYCODE_DPAD_CENTER)
                val combined = "adv imdb-rate s7 cSLOW d2010 p1 0"
                await("Score, country and decade did not combine", detail = { "${firstSince(mark)?.form} ${first()}" }) {
                    firstSince(mark)?.form?.get("page") == "1" && firstSince(mark)?.parameters.toString() == """{"stream":"true","type":["movie"],"country":"SLOW","imdb_rate":7,"min_year":2010,"max_year":2019}""" &&
                        first() == combined
                }
                runOnMainSync {
                    check(view<Button>(R.id.filter_decade).text.toString() == host.getString(R.string.filter_decade_value, "2010".localDigits()))
                    check(view<Button>(R.id.filter_country).text.toString() == (if (fa) "کند" else "Slow"))
                }
                settle()
                if (fa) capture("filters") else capture("filters-en")
                slow.countDown()
                await("The slow page never answered") { staleAnswered.get() }
                SystemClock.sleep(300) // its coroutine's turn on the main thread
                waitForIdleSync()
                runOnMainSync {
                    val adapter = checkNotNull((host.supportFragmentManager.findFragmentById(R.id.browse_host) as GridFragment).adapter)
                    val titles = (0 until adapter.size()).mapNotNull { (adapter[it] as? Card)?.title }
                    check(titles.none { it.contains("cSLOW p") }) { "A page for a replaced filter landed: $titles" }
                    check(titles.first() == combined)
                }

                // Page 2 asks with the same filter and sort.
                runOnMainSync { grid.selectedPosition = 0; grid.requestFocus() }
                await("Page 2 did not follow with the filter", detail = { "${calls.drop(mark).map { it.form }}" }) {
                    val asked = firstSince(mark)
                    calls.drop(mark).any {
                        it.action == "advanced_search" && it.form["page"] == "2" && it.form["orderby"] == "imdb-rate" &&
                            it.form["parameters"] == asked?.form?.get("parameters")
                    } && first(24) == "adv imdb-rate s7 cSLOW d2010 p2 0"
                }

                // Clear: every filter off, the sort kept, the remote on genre.
                upFromGrid()
                repeat(4) { press(toEnd) }
                await("The filters did not reach clear") { focusedId() == R.id.filter_clear }
                val archives = calls.count { it.action == "archive" }
                press(KeyEvent.KEYCODE_DPAD_CENTER)
                await("Clear did not go back to the archive") {
                    calls.count { it.action == "archive" } > archives && first() == "archive imdb-rate p1 0" &&
                        focusedId() == R.id.filter_genre && view<View>(R.id.filter_clear).visibility == View.INVISIBLE &&
                        view<Button>(R.id.filter_score).text.toString() == "IMDb"
                }

                // A genre with nothing in it says so, and its button clears.
                press(KeyEvent.KEYCODE_DPAD_CENTER)
                await("The genre picker did not open") { focused()?.contentDescription == host.getString(R.string.filter_all_genres) }
                repeat(3) { press(KeyEvent.KEYCODE_DPAD_DOWN) }
                press(KeyEvent.KEYCODE_DPAD_CENTER)
                await("An empty filter did not say so") {
                    host.findViewById<View>(R.id.list_empty) != null && grid.visibility == View.GONE && focusedId() == R.id.filter_genre &&
                        view<TextView>(R.id.list_empty_title).text.toString() == host.getString(R.string.filter_empty_title)
                }
                settle()
                if (fa) capture("filters-empty")
                press(KeyEvent.KEYCODE_DPAD_DOWN)
                await("Down did not reach the empty result's button") { focusedId() == R.id.list_empty_action }
                press(KeyEvent.KEYCODE_DPAD_CENTER)
                await("The empty result's button did not clear") {
                    host.findViewById<View>(R.id.list_empty) == null && grid.visibility == View.VISIBLE &&
                        first() == "archive imdb-rate p1 0" && focusedId() == R.id.filter_genre
                }

                runOnMainSync { host.finish() }
                await("Grid did not close") { host.isDestroyed }
                activity = null
            }
            result.putString("stream", "PASS: RTL/LTR filter bar D-pad, score floors and sort through advanced_search, scrolling country list, stale page dropped, filtered page 2, clear and empty result\n")
        } catch (error: Throwable) {
            capture("filters-failure")
            result.putString("stream", error.stackTraceToString())
            resultCode = Activity.RESULT_CANCELED
        } finally {
            slow.countDown()
            runOnMainSync { activity?.finish() }
            await("Grid activity did not close") { activity?.isDestroyed != false }
            runOnMainSync {
                Api.token = originalToken
                Api.user = originalUser
                Api.language = originalLanguage
                Locale.setDefault(originalLocale)
                clientField.set(Api, originalClient)
            }
            client.dispatcher.cancelAll()
        }
        finish(resultCode, result)
    }

    /** The countries before the slow one, which comes last. */
    private const val COUNTRIES = 30

    private fun choices(fa: Boolean): JSONObject {
        fun list(vararg entries: Pair<String, Any>) = JSONArray().apply { for ((name, value) in entries) put(JSONObject().put("name", name).put("value", value)) }
        val countries = (1 until COUNTRIES).map { (if (fa) "کشور ${it.toString().persianDigits()}" else "Country $it") to "C$it" } +
            ((if (fa) "کند" else "Slow") to "SLOW")
        return JSONObject()
            .put("genre", list((if (fa) "درام" else "Drama") to "drama", (if (fa) "کمدی" else "Comedy") to "comedy", (if (fa) "هیچ" else "Nothing") to "none"))
            .put("country", list(*countries.toTypedArray()))
            .put("imdb_rate", list("9" to 9, "8" to 8, "7" to 7, "-5" to -5))
            .put("min_year", 1990).put("max_year", 2026)
    }

    /** A short name for what a filter asked, so a card says which answer it came from. */
    private fun signature(parameters: JSONObject) = listOfNotNull(
        parameters.optJSONArray("genre")?.let { "g${it.optString(0)}" },
        parameters.opt("imdb_rate")?.let { "s$it" },
        parameters.opt("country")?.let { "c$it" },
        parameters.opt("min_year")?.let { "d$it" },
    ).joinToString(" ")

    private fun posts(label: String, page: Int) = JSONObject().put("page", page).put("pages", 2).put("posts", JSONArray().apply {
        for (i in 0 until 24) put(JSONObject().put("id", page * 100 + i).put("title", JSONObject().put("english", "$label p$page $i"))
            .put("options", JSONObject().put("title_type", "movie")))
    })

    /** Past the sheet's exit and the focus fill's fade, so a capture shows where things rest. */
    private fun Instrumentation.settle() {
        SystemClock.sleep(500)
        waitForIdleSync()
    }

    private fun Instrumentation.press(code: Int) {
        sendKeySync(KeyEvent(KeyEvent.ACTION_DOWN, code).apply { source = InputDevice.SOURCE_DPAD })
        sendKeySync(KeyEvent(KeyEvent.ACTION_UP, code).apply { source = InputDevice.SOURCE_DPAD })
        waitForIdleSync()
    }

    private fun Instrumentation.await(message: String, detail: () -> String = { "" }, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        do {
            var passed = false
            runOnMainSync { passed = condition() }
            if (passed) return
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        var said = ""
        runOnMainSync { said = detail() }
        error(if (said.isEmpty()) message else "$message: $said")
    }
}
