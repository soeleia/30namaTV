package com.doxigo.tv30

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.leanback.widget.VerticalGridView
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.Locale

/**
 * Run the existing instrumentation runner with -e check searchnote. A search whose first page
 * fails heads its empty grid with what failed rather than «نتایج» over nothing, the grid out of the
 * remote's way as for no results: a query's «جست‌وجو انجام نشد» (Persian), the suggestions' on
 * opening (English). A new query puts its results and their count back, and a later page failing
 * under them keeps that heading.
 */
object SearchNoteChecks {
    /** What 30nama answers the search and the suggestions: "fail", or a first page of posts (a second always fails). */
    @Volatile private var search = "fail"
    @Volatile private var suggested = "ok"
    @Volatile private var secondPage = false

    fun run(instrumentation: Instrumentation) = with(instrumentation) {
        val result = Bundle()
        var code = Activity.RESULT_OK
        var activity: SearchActivity? = null
        val originalClient = Api.http
        val originalLanguage = Api.language
        val originalLocale = Locale.getDefault()
        val originalKeys = SearchKeys.chosen(targetContext)
        val originalRecents = Recents.read(targetContext)
        val clientField = Api::class.java.getDeclaredField("http").apply { isAccessible = true }
        // Only 30nama's actions are answered; anything else (art, TMDB) is refused, so nothing leaves the emulator.
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val reply = { status: Int, body: String ->
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("-").body(body.toResponseBody()).build()
            }
            if ("/action/" !in request.url.encodedPath) return@addInterceptor reply(404, "")
            val form = request.body as? FormBody
            val page = form?.let { f -> (0 until f.size).firstOrNull { f.name(it) == "page" }?.let(f::value)?.toInt() } ?: 1
            val action = request.url.pathSegments.last()
            val answer = when (action) { "search" -> search; "archive" -> suggested; else -> null }
            if (action == "search" && page > 1) secondPage = true
            // A search runs to two pages, the suggestions to one, so only a search asks for a second.
            val pages = if (action == "search") 2 else 1
            val posts = (1..24).joinToString(",") { """{"id":$it,"title":{"english":"Note check $it"},"options":{"title_type":"movie"}}""" }
            reply(200, when {
                answer == null -> """{"success":true,"result":{}}"""
                answer == "fail" || page > 1 -> """{"success":false,"msg":"internal error"}"""
                else -> """{"success":true,"result":{"posts":[$posts],"page":1,"pages":$pages,"total":${24 * pages}}}"""
            })
        }.build()
        try {
            for (language in listOf("fa", "en")) {
                search = if (language == "fa") "fail" else "ok"
                suggested = if (language == "fa") "ok" else "fail"
                secondPage = false
                runOnMainSync {
                    clientField.set(Api, client)
                    Api.language = language
                    Recents.clear(targetContext)
                    SearchKeys.choose(targetContext, false)
                }
                activity = startActivitySync(Intent(targetContext, SearchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SearchActivity
                val host = checkNotNull(activity)
                lateinit var fragment: SearchFragment
                await("Search did not open ($language)") {
                    (host.supportFragmentManager.findFragmentById(R.id.browse_host) as? SearchFragment)?.takeIf { it.view != null }?.also { fragment = it } != null
                }
                val view = checkNotNull(fragment.view)
                val grid = view.findViewById<VerticalGridView>(androidx.leanback.R.id.browse_grid)
                val editor = view.findViewById<TextView>(androidx.leanback.R.id.lb_search_text_editor)
                val heading = { (field(fragment, "heading") as TextView).text.toString() }
                val cards = { (field(fragment, "pager") as Pager).adapter.size() }
                fun failed(note: Int) = await({ "The heading did not say the search failed ($language): «${heading()}», ${cards()} cards, focusable ${grid.isFocusable}" }) {
                    heading() == host.getString(note) && cards() == 0 && !grid.isFocusable
                }
                if (language == "fa") {
                    await("The suggestions did not load") { grid.isFocusable && heading() == host.getString(R.string.suggested) }
                    text(editor, "دریا")
                    failed(R.string.search_failed)
                    keyboardDown(host, editor)
                    capture("search-note-fa")
                    search = "ok"
                    text(editor, "باران")
                    runOnMainSync { fragment.onSearchQuerySubmit("باران") }
                    val count = host.resources.getQuantityString(R.plurals.search_count, 48, 48)
                    await({ "A new query did not bring back the results' count: «${heading()}», ${cards()} cards" }) {
                        heading() == count && cards() == 24 && grid.hasFocus()
                    }
                    // The remote on the results asks for the second page, which fails under them.
                    await("The second page was never asked for") { secondPage }
                    SystemClock.sleep(500)
                    var kept = ""
                    var still = 0
                    runOnMainSync { kept = heading(); still = cards() }
                    check(kept == count && still == 24) { "A later page's failure changed the heading: «$kept», $still cards" }
                    capture("search-note-back-fa")
                } else {
                    failed(R.string.suggestions_failed)
                    keyboardDown(host, editor)
                    capture("search-note-en")
                }
                runOnMainSync { host.finish() }
                await("Search did not close") { host.isDestroyed }
                activity = null
            }
            result.putString("stream", "PASS: a failed first page heads search with «جست‌وجو انجام نشد» (Persian) or \"Couldn't load suggestions\" (English), the grid unfocusable; a new query brings back the count, which a later page's failure keeps\n")
        } catch (error: Throwable) {
            code = Activity.RESULT_CANCELED
            capture("search-note-failure")
            result.putString("stream", error.stackTraceToString())
        } finally {
            runOnMainSync { activity?.finish() }
            await("Search did not close") { activity?.isDestroyed != false }
            runOnMainSync {
                clientField.set(Api, originalClient)
                Api.language = originalLanguage
                Locale.setDefault(originalLocale)
                Recents.clear(targetContext)
                originalRecents.asReversed().forEach { Recents.add(targetContext, it) }
                SearchKeys.choose(targetContext, originalKeys)
            }
            client.dispatcher.cancelAll()
        }
        finish(code, result)
    }

    /** The system keyboard down, so the screenshot shows the heading rather than the keyboard over it. */
    private fun Instrumentation.keyboardDown(host: Activity, editor: TextView) {
        await("Search keyboard did not open") { keyboardVisible(host) }
        runOnMainSync { host.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(editor.windowToken, 0) }
        await("Search keyboard did not hide") { !keyboardVisible(host) }
        SystemClock.sleep(400)
    }

    private fun keyboardVisible(host: Activity) =
        ViewCompat.getRootWindowInsets(host.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true

    private fun field(instance: Any, name: String): Any? =
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance)

    private fun Instrumentation.text(editor: TextView, value: String) {
        runOnMainSync { editor.requestFocus(); editor.text = value }
        waitForIdleSync()
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
