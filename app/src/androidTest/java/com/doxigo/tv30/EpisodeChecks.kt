package com.doxigo.tv30

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.leanback.app.RowsSupportFragment
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.Locale

/**
 * Run the existing instrumentation runner with -e check episodes. A series in Continue Watching with
 * an episode out that its viewer has not gone back to (NewEpisodes) says «● قسمت تازه» under its
 * poster instead of the time left, whole under 108dp art in Persian and English; one gone back to
 * since keeps its time left. No TvProvider on a phone image: the launcher's row is not checked here.
 */
object EpisodeChecks {
    fun run(instrumentation: Instrumentation) = with(instrumentation) {
        val result = Bundle()
        var resultCode = Activity.RESULT_OK
        var homeActivity: MainActivity? = null
        val originalClient = Api.http
        val originalToken = Api.token
        val originalUser = Api.user
        val originalLanguage = Api.language
        val originalLocale = Locale.getDefault()
        val originalIntro = Intro.shown
        val clientField = Api::class.java.getDeclaredField("http").apply { isAccessible = true }
        val stateField = NewEpisodes::class.java.getDeclaredField("state").apply { isAccessible = true }
        fun series(id: Int, name: String, poster: String, season: Int, episode: Int, time: Int, progress: Int) =
            """{"id":$id,"title":{"english":"$name"},"options":{"title_type":"series"},"info":{"year":"2024"},
               "image":{"poster":{"jpg":{"large":"https://cdn-world.hana-api.com/poster/$poster-l_30NAMA.jpg?3"}}},
               "data":{"season":$season,"episode":$episode,"episode_title":"فصل $season - قسمت $episode","time":$time,"progress":"$progress"}}"""
        val news = series(31, "Dark Matter", "30641191", 2, 8, 2580, 98)
        val back = series(32, "Gone Back To", "30685969", 1, 3, 600, 30)
        // Only 30nama's actions are answered here; the posters come from its CDN, for the screenshot.
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if ("/action/" !in request.url.encodedPath) return@addInterceptor chain.proceed(request)
            val body = when (request.url.pathSegments.last()) {
                "home_stream" -> """{"movies":{"posts":[{"id":1,"title":{"english":"Episode check"},"options":{"title_type":"movie"}}]}}"""
                "menu" -> """{"categories":[]}"""
                "user_list" -> """{"posts":[]}"""
                "continue_watching" -> """{"posts":[$news,$back]}"""
                else -> "{}"
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"success":true,"result":$body}""".toResponseBody()).build()
        }.build()
        // Both episodes came out yesterday; 32 has been watched on since (its point moved from S1E2).
        val day = System.currentTimeMillis() - 24 * 3_600_000L
        val planted = NewEpisodes.State(out = mapOf(31 to NewEpisodes.Alert(day, "2 8 2580"), 32 to NewEpisodes.Alert(day, "1 2 2400")))
        try {
            for (language in listOf("fa", "en")) {
                runOnMainSync {
                    clientField.set(Api, client)
                    Api.language = language
                    Api.token = "episodes-test"
                    Api.user = null
                    Intro.shown = true
                    targetContext.filesDir.resolve("home").deleteRecursively()
                    targetContext.getSharedPreferences("episodes", Context.MODE_PRIVATE).edit().putString("state", NewEpisodes.encode(planted)).commit()
                    stateField.set(NewEpisodes, null)
                }
                homeActivity = startActivitySync(Intent(targetContext, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_GUEST, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
                val host = checkNotNull(homeActivity)
                val mark = host.getString(R.string.new_episode)
                lateinit var row: ListRowPresenter.ViewHolder
                await("Continue Watching did not load with its posters") {
                    val home = host.supportFragmentManager.findFragmentById(R.id.browse_host) as? HomeFragment ?: return@await false
                    val at = (0 until home.adapter.size()).indexOfFirst { (home.adapter[it] as ListRow).id == HomeFragment.CONTINUE.toLong() }
                    val found = (home.mainFragment as? RowsSupportFragment)?.getRowViewHolder(at) as? ListRowPresenter.ViewHolder
                    if (at < 0 || found == null || found.gridView.childCount < 2 || home.view?.findViewById<View>(androidx.leanback.R.id.browse_container_dock)?.alpha != 1f) false
                    else true.also { row = found }
                }
                runOnMainSync {
                    val captions = (0 until 2).map { row.gridView.getChildAt(it).findViewById<TextView>(androidx.leanback.R.id.content_text) }
                    check(mark in captions[0].text.toString()) { "The new episode is not marked ($language): ${captions[0].text}" }
                    check(captions[0].layout?.getEllipsisCount(0) == 0) { "The mark is cut ($language): ${captions[0].text}" }
                    check(mark !in captions[1].text.toString()) { "A series gone back to is still marked ($language): ${captions[1].text}" }
                }
                SystemClock.sleep(1_500) // the posters, off the network
                capture("episodes-$language")
                runOnMainSync { host.finish() }
                await("Home did not close") { host.isDestroyed }
                homeActivity = null
            }
            result.putString("stream", "PASS: a new episode marks its continue poster whole in Persian and English, a series gone back to keeps its time left\n")
        } catch (error: Throwable) {
            result.putString("stream", error.stackTraceToString())
            resultCode = Activity.RESULT_CANCELED
        } finally {
            runOnMainSync { homeActivity?.finish() }
            await("Home did not close") { homeActivity?.isDestroyed != false }
            runOnMainSync {
                Api.token = originalToken
                Api.user = originalUser
                Api.language = originalLanguage
                Locale.setDefault(originalLocale)
                Intro.shown = originalIntro
                clientField.set(Api, originalClient)
                NewEpisodes.clear(targetContext)
                targetContext.filesDir.resolve("home").deleteRecursively()
            }
            client.dispatcher.cancelAll()
        }
        finish(resultCode, result)
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
