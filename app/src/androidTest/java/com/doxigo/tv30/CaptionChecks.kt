package com.doxigo.tv30

import android.app.Activity
import android.app.Instrumentation
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
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Run the existing instrumentation runner with -e check captions. Home's 108dp posters keep their
 * whole caption, score and year, in Persian and English: Inter's zeros run wide, and «IMDb 7.0 2000»
 * once lost its year to «…».
 */
object CaptionChecks {
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
        fun posts(name: String, count: Int) = JSONArray().apply {
            for (i in 0 until count) put(JSONObject().put("id", name.hashCode() + i).put("title", JSONObject().put("english", "$name $i"))
                .put("options", JSONObject().put("title_type", "movie")).put("info", JSONObject().put("year", "${2000 + i}"))
                .put("score", JSONObject().put("imdb", JSONObject().put("score", 7.0 + i / 10.0))))
        }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val data = when (request.url.pathSegments.last()) {
                "home_stream" -> JSONObject().put("suggested", JSONObject().put("posts", posts("Suggested", 6)))
                    .put("movies", JSONObject().put("posts", posts("Movie", 6)))
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
                homeActivity = startActivitySync(Intent(targetContext, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_GUEST, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
                val host = checkNotNull(homeActivity)
                lateinit var home: HomeFragment
                await("Home rows did not load") {
                    val found = host.supportFragmentManager.findFragmentById(R.id.browse_host) as? HomeFragment ?: return@await false
                    val loaded = (0 until found.adapter.size()).any { (found.adapter[it] as ListRow).id == HomeFragment.SUGGESTED.toLong() }
                    if (!loaded || found.view?.findViewById<View>(androidx.leanback.R.id.browse_container_dock)?.alpha != 1f) false else {
                        home = found
                        true
                    }
                }
                val at = (0 until home.adapter.size()).indexOfFirst { (home.adapter[it] as ListRow).id == HomeFragment.SUGGESTED.toLong() }
                runOnMainSync { host.jumpTo(HomeFragment.SUGGESTED) }
                lateinit var row: ListRowPresenter.ViewHolder
                await("Suggested did not take the remote") {
                    val rows = (home.mainFragment as? RowsSupportFragment)
                    val found = rows?.getRowViewHolder(at) as? ListRowPresenter.ViewHolder
                    if (found == null || rows.verticalGridView.scrollState != 0 || !found.gridView.hasFocus() || found.gridView.childCount == 0) false else {
                        row = found
                        true
                    }
                }
                runOnMainSync {
                    for (i in 0 until row.gridView.childCount) {
                        val facts = row.gridView.getChildAt(i).findViewById<TextView>(androidx.leanback.R.id.content_text) ?: continue
                        check(facts.layout?.getEllipsisCount(0) == 0) { "A poster's score and year are cut ($language): ${facts.text}" }
                    }
                }
                SystemClock.sleep(400)
                capture("captions-$language")
                runOnMainSync { host.finish() }
                await("Home did not close") { host.isDestroyed }
                homeActivity = null
            }
            result.putString("stream", "PASS: home posters keep their whole score and year under 108dp art in Persian and English\n")
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
