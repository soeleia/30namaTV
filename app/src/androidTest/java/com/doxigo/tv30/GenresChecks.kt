package com.doxigo.tv30

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import androidx.leanback.app.RowsSupportFragment
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.VerticalGridView
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

object GenresChecks {
    fun run(instrumentation: Instrumentation) = with(instrumentation) {
        val result = Bundle()
        var resultCode = Activity.RESULT_OK
        var homeActivity: MainActivity? = null
        var genresActivity: GridActivity? = null
        var archiveActivity: GridActivity? = null
        val originalClient = Api.http
        val originalToken = Api.token
        val originalUser = Api.user
        val originalLanguage = Api.language
        val originalLocale = Locale.getDefault()
        val originalIntro = Intro.shown
        val clientField = Api::class.java.getDeclaredField("http").apply { isAccessible = true }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val action = chain.request().url.pathSegments.last()
            val menu = JSONObject().put("categories", JSONArray().put(JSONObject().put("name", "Category").put("slug", "movies")))
                .put("genres", JSONArray().apply {
                    for (i in 0 until 20) put(JSONObject().put("name", if (Api.language == "fa") "ژانر $i" else "Genre $i").put("slug", "genre-$i").put("count", i + 10))
                })
            val posts = JSONArray().put(JSONObject().put("id", 1).put("title", JSONObject().put("english", "Genre check"))
                .put("options", JSONObject().put("title_type", "movie")))
            val data = when (action) {
                "home_stream" -> JSONObject().put("movies", JSONObject().put("posts", posts))
                "menu" -> menu
                "archive" -> JSONObject().put("posts", posts).put("page", 1).put("pages", 1)
                else -> JSONObject()
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
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
                }
                homeActivity = startActivitySync(Intent(targetContext, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_GUEST, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
                val homeHost = checkNotNull(homeActivity)
                lateinit var home: HomeFragment
                var genreRow = -1
                await("Home genre entry did not load") {
                    val found = homeHost.supportFragmentManager.findFragmentById(R.id.browse_host) as? HomeFragment
                    genreRow = found?.adapter?.let { adapter -> (0 until adapter.size()).indexOfFirst { (adapter[it] as ListRow).id == HomeFragment.GENRES.toLong() } } ?: -1
                    if (genreRow < 0 || found?.view?.findViewById<View>(androidx.leanback.R.id.browse_container_dock)?.alpha != 1f) false else {
                        home = checkNotNull(found)
                        val items = (home.adapter[genreRow] as ListRow).adapter
                        check(items.size() == 3) { "Home still has the long horizontal genre list" }
                        check((items[2] as Card).icon == R.drawable.nav_categories)
                        true
                    }
                }
                runOnMainSync { homeHost.jumpTo(HomeFragment.GENRES) }
                lateinit var row: ListRowPresenter.ViewHolder
                await("Home genre row did not take focus") {
                    val fragment = home.mainFragment as? RowsSupportFragment
                    val rows = fragment?.verticalGridView
                    val found = fragment?.getRowViewHolder(genreRow) as? ListRowPresenter.ViewHolder
                    if (found == null || rows == null || rows.selectedPosition != genreRow || rows.scrollState != 0) false else {
                        row = found
                        true
                    }
                }
                runOnMainSync { row.gridView.selectedPosition = 2; row.gridView.requestFocus() }
                await("Home More entry did not take focus") { row.gridView.hasFocus() && row.gridView.selectedPosition == 2 && row.gridView.scrollState == 0 }
                val openGenres = addMonitor(GridActivity::class.java.name, null, false)
                press(KeyEvent.KEYCODE_DPAD_CENTER)
                genresActivity = waitForMonitorWithTimeout(openGenres, 5_000) as? GridActivity
                removeMonitor(openGenres)
                val genreHost = checkNotNull(genresActivity) { "More did not open the genres page" }
                lateinit var grid: VerticalGridView
                await("Full genre grid did not load") {
                    val fragment = genreHost.supportFragmentManager.findFragmentById(R.id.browse_host) as? GridFragment
                    val found = fragment?.view?.findViewById<VerticalGridView>(androidx.leanback.R.id.browse_grid)
                    if (fragment?.adapter?.size() != 20 || found?.findViewHolderForAdapterPosition(4) == null) false else {
                        grid = found
                        check(genreHost.findViewById<View>(R.id.grid_sort).visibility == View.GONE)
                        check(grid.verticalSpacing == (POSTER_GRID_ROW_SPACING_DP * genreHost.resources.displayMetrics.density).toInt())
                        true
                    }
                }
                runOnMainSync { grid.selectedPosition = 0; grid.requestFocus() }
                await("First genre did not take focus") { grid.hasFocus() && grid.selectedPosition == 0 && grid.scrollState == 0 }
                if (language == "fa") capture("genres")
                press(KeyEvent.KEYCODE_DPAD_DOWN)
                await("Genres do not navigate as a four-column grid") { grid.selectedPosition == 4 }
                press(KeyEvent.KEYCODE_DPAD_UP)
                await("Genre Up did not return to the first row") { grid.selectedPosition == 0 && grid.scrollState == 0 }
                press(if (language == "fa") KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT)
                await("Genre edge did not enter the rail") { genreHost.findViewById<View>(R.id.rail).hasFocus() }
                press(if (language == "fa") KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT)
                await("Rail did not restore genre focus") { grid.hasFocus() && grid.selectedPosition == 0 }
                runOnMainSync { grid.selectedPosition = 19 }
                await("Last genre was not reachable") { grid.hasFocus() && grid.selectedPosition == 19 && grid.scrollState == 0 }
                val openArchive = addMonitor(GridActivity::class.java.name, null, false)
                press(KeyEvent.KEYCODE_DPAD_CENTER)
                archiveActivity = waitForMonitorWithTimeout(openArchive, 5_000) as? GridActivity
                removeMonitor(openArchive)
                val archive = checkNotNull(archiveActivity) { "Genre did not open its catalogue" }
                runOnMainSync { check(archive.intent.getStringExtra("slug") == "genre-19" && !archive.intent.hasExtra("genres")) }
                await("Genre catalogue did not take the window") { archive.hasWindowFocus() }
                press(KeyEvent.KEYCODE_BACK)
                await("Back did not restore the selected genre") { archive.isDestroyed && grid.hasFocus() && grid.selectedPosition == 19 }
                archiveActivity = null
                press(KeyEvent.KEYCODE_BACK)
                await("Back did not restore home's genre entry") { genreHost.isDestroyed && row.gridView.hasFocus() && row.gridView.selectedPosition == 2 }
                genresActivity = null
                runOnMainSync { homeHost.finish() }
                await("Home did not close") { homeHost.isDestroyed }
                homeActivity = null
            }
            result.putString("stream", "PASS: short home genre row, complete cached genre grid, RTL/LTR four-column D-pad/rail, catalogue links and Back focus\n")
        } catch (error: Throwable) {
            capture("genres-failure")
            result.putString("stream", error.stackTraceToString())
            resultCode = Activity.RESULT_CANCELED
        } finally {
            runOnMainSync { archiveActivity?.finish(); genresActivity?.finish(); homeActivity?.finish() }
            await("Genre activities did not close") { listOf(archiveActivity, genresActivity, homeActivity).all { it?.isDestroyed != false } }
            runOnMainSync {
                Api.token = originalToken
                Api.user = originalUser
                Api.language = originalLanguage
                Locale.setDefault(originalLocale)
                Intro.shown = originalIntro
                clientField.set(Api, originalClient)
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
