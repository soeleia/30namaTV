package com.doxigo.tv30

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import java.util.Collections
import java.util.Locale

/**
 * TMDB's title logos on the billboard and the title page, and what TMDB adds to the credit line,
 * in Persian and English. TMDB answers from assets/logos/tmdb.json (recorded from TMDB in the API's
 * shape), 1.5 s late as over a slow VPN, so the billboard is first seen with its words and must not
 * move when the logo lands; 30nama answers with its guest home (home-fa/en.json). The art itself
 * comes off the network.
 */
object LogoChecks {
    private const val TMDB_LATE = 1_500L
    private const val SERIES = "tt12590266" // Cyberpunk: Edgerunners: Returning Series on Netflix, no Persian tagline
    private const val FILM = "tt33764258" // The Odyssey: "Defy the gods.", a Persian overview, no Persian tagline

    fun run(instrumentation: Instrumentation) = with(instrumentation) {
        val result = Bundle()
        var code = Activity.RESULT_OK
        val tmdb = JSONObject(context.assets.open("logos/tmdb.json").bufferedReader().use { it.readText() })
        val homes = listOf("fa", "en").associateWith { lang ->
            JSONObject(context.assets.open("logos/home-$lang.json").bufferedReader().use { it.readText() })
        }
        val asked = Collections.synchronizedList(mutableListOf<String>())
        /** 30nama's `single` answer for a title the check has changed, by id. */
        val singles = Collections.synchronizedMap(mutableMapOf<String, JSONObject>())
        val originalClient = Api.http
        val originalToken = Api.token
        val originalUser = Api.user
        val originalLanguage = Api.language
        val originalLocale = Locale.getDefault()
        val originalIntro = Intro.shown
        val originalKey = Settings.savedKey(Tmdb.NAME)
        val clientField = Api::class.java.getDeclaredField("http").apply { isAccessible = true }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val url = request.url
            when {
                url.host == "api.themoviedb.org" -> {
                    val path = url.encodedPath.removePrefix("/3/")
                    asked += path
                    SystemClock.sleep(TMDB_LATE)
                    answer(request, (tmdb.optJSONObject(path) ?: JSONObject()).toString())
                }
                "/action/" in url.encodedPath -> {
                    val home = checkNotNull(homes[Api.language])
                    val body = when (url.pathSegments.last()) {
                        "home_stream" -> home.toString()
                        "single" -> (request.body as FormBody).value("post_id").let { id -> singles[id] ?: posts(home).first { it.titleId.toString() == id } }.toString()
                        "menu" -> """{"categories":[]}"""
                        "user_list", "continue_watching", "archive", "search", "advanced_search" -> """{"posts":[]}"""
                        else -> "{}"
                    }
                    answer(request, """{"success":true,"result":$body}""")
                }
                else -> chain.proceed(request) // the art, Aparat, Cloudflare's trace
            }
        }.build()
        var activity: Activity? = null
        try {
            for (language in listOf("fa", "en")) {
                runOnMainSync {
                    clientField.set(Api, client)
                    Api.token = null
                    Api.user = null
                    Api.language = language
                    Intro.shown = true
                    Settings.saveKey(Tmdb.NAME, "logo-check")
                    targetContext.filesDir.resolve("home").deleteRecursively()
                }
                val home = checkNotNull(homes[language])

                // Home: the hero's first title, its logo looked up while its words show.
                activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_GUEST, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                val main = checkNotNull(activity)
                lateinit var name: TextView
                lateinit var words: List<View>
                var before = emptyList<Int>()
                await("The hero did not show Cyberpunk: Edgerunners") {
                    name = main.findViewById(R.id.bb_title) ?: return@await false
                    words = listOf(R.id.bb_title, R.id.bb_local, R.id.bb_meta, R.id.bb_plot).map { main.findViewById(it) }
                    name.text.toString() == "Cyberpunk: Edgerunners" && name.isShown && name.height > 0
                }
                SystemClock.sleep(500) // the hero's own layout settles; TMDB is a second off yet
                runOnMainSync {
                    check(name.foreground == null && Color.alpha(name.currentTextColor) == 255) { "A logo was there before TMDB answered" }
                    before = words.flatMap { listOf(it.top, it.height) }
                }
                val first = posts(home).first { it.imdbId == SERIES }
                await("The logo never replaced the words", timeoutMs = 12_000, detail = {
                    "asked=$asked enabled=${Tmdb.enabled} backingOff=${Tmdb.backingOff} cached=${Logos.cached(first)} " +
                        "foreground=${name.foreground} words=${Color.alpha(name.currentTextColor)}"
                }) { logoShown(name) }
                runOnMainSync {
                    check(words.flatMap { listOf(it.top, it.height) } == before) { "The words moved when the logo landed: $before" }
                }
                check("find/$SERIES" in asked && "tv/105248/images" in asked) { "TMDB was not asked for the logo: $asked" }
                SystemClock.sleep(400)
                capture("logos-home-$language")

                // The next page's logo was fetched with its art: the name never shows its words first.
                runOnMainSync { main.findViewById<View>(R.id.promo_more).requestFocus() }
                SystemClock.sleep(300)
                sendKeyDownUpSync(if (language == "fa") KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT)
                await("The hero did not turn to The Odyssey", detail = { "showing ${name.text}, focus on ${main.currentFocus}" }) { name.text.toString() == "The Odyssey" }
                runOnMainSync { check(Color.alpha(name.currentTextColor) == 0) { "The Odyssey's words showed before its prefetched logo" } }
                await("The Odyssey's logo did not come in with the page", timeoutMs = 3_000) { logoShown(name) }
                SystemClock.sleep(900)
                capture("logos-home-$language-next")
                runOnMainSync { main.finish() }
                await("Home did not close") { main.isDestroyed }
                activity = null

                // Title pages: the logo, and the credit line in the interface's language.
                val series = posts(home).first { it.imdbId == SERIES }
                val film = posts(home).first { it.imdbId == FILM }
                for ((post, label) in listOf(series to "series", film to "film")) {
                    activity = startActivitySync(DetailsActivity.intent(targetContext, post).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    val page = checkNotNull(activity)
                    val title = page.findViewById<TextView>(R.id.bb_title)
                    val credits = page.findViewById<TextView>(R.id.credits)
                    await("$label: the logo never replaced the words", timeoutMs = 12_000) { logoShown(title) }
                    val expected = when {
                        label == "series" && language == "fa" -> "در حال پخش، Netflix"
                        label == "series" -> "Returning Series, Netflix"
                        language == "en" -> "Defy the gods."
                        else -> null // no Persian tagline, and never an English one
                    }
                    // The extras bind with the same TMDB answer the logo came in.
                    SystemClock.sleep(400)
                    fun line() = credits.text.toString().filter { it != '‎' && it != '‏' } // fence()'s marks
                    if (expected != null) await("$label credit line lacks «$expected»: ${line()}") { credits.isShown && expected in line() }
                    else runOnMainSync { check("Defy" !in line()) { "An English tagline in Persian: ${line()}" } }
                    if (label == "film" && language == "fa") runOnMainSync {
                        val plot = page.findViewById<TextView>(R.id.bb_plot).text.toString()
                        check(plot.startsWith(post.str("plot", "local")!!.take(20))) { "30nama's own synopsis gave way to TMDB's: $plot" }
                    }
                    SystemClock.sleep(600) // laid out and drawn with its new line
                    capture("logos-title-$label-$language")
                    runOnMainSync { page.finish() }
                    await("$label page did not close") { page.isDestroyed }
                    activity = null
                }

                // Only where 30nama has no synopsis at all does TMDB's Persian one stand in.
                if (language == "fa") {
                    val bare = JSONObject(film.toString()).apply { put("plot", JSONObject().put("english", film.str("plot", "english"))) }
                    singles[bare.titleId.toString()] = bare
                    activity = startActivitySync(DetailsActivity.intent(targetContext, bare).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    val page = checkNotNull(activity)
                    val overview = checkNotNull(tmdb.getJSONObject("movie/1368337").translated("overview", "fa")).inPersian().trim()
                    await("TMDB's Persian synopsis did not stand in for a missing one", timeoutMs = 12_000) {
                        page.findViewById<TextView>(R.id.bb_plot).text.toString().startsWith(overview.take(20))
                    }
                    runOnMainSync { page.finish() }
                    await("Bare page did not close") { page.isDestroyed }
                    activity = null
                    singles.clear()
                }
            }
            result.putString("stream", "PASS: TMDB logos replace the English name on home's hero and the title page without moving the words, a prefetched page shows its logo at once, and the credit line takes the series' state and network and the tagline by the interface's language, in Persian and English\n")
        } catch (error: Throwable) {
            code = Activity.RESULT_CANCELED
            result.putString("stream", error.stackTraceToString())
        } finally {
            runOnMainSync { activity?.finish() }
            runOnMainSync {
                clientField.set(Api, originalClient)
                Api.token = originalToken
                Api.user = originalUser
                Api.language = originalLanguage
                Locale.setDefault(originalLocale)
                Intro.shown = originalIntro
                Settings.saveKey(Tmdb.NAME, originalKey)
                for (file in listOf("logos.json", "persian-names.json", "trending-fa.json", "trending-en.json")) targetContext.cacheDir.resolve(file).delete()
                targetContext.filesDir.resolve("home").deleteRecursively()
            }
            client.dispatcher.cancelAll()
        }
        finish(code, result)
    }

    /** The name wears its logo, whole, and its words are gone. */
    private fun logoShown(name: TextView) = name.foreground?.alpha == 255 && Color.alpha(name.currentTextColor) == 0

    private fun posts(home: JSONObject) = home.keys().asSequence().flatMap { home.optJSONObject(it)?.optJSONArray("posts").objects() }.toList()

    private fun FormBody.value(name: String) = (0 until size).firstOrNull { name(it) == name }?.let(::value)

    private fun answer(request: Request, body: String): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody()).build()

    private fun Instrumentation.await(message: String, timeoutMs: Long = 7_000, detail: (() -> String)? = null, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        do {
            var passed = false
            runOnMainSync { passed = condition() }
            if (passed) return
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        var more = ""
        if (detail != null) runOnMainSync { more = ": " + detail() }
        error(message + more)
    }
}
