package com.doxigo.tv30

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.leanback.widget.ListRow
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject

/**
 * Run the existing instrumentation runner with -e check dismiss. A title taken off Continue Watching
 * leaves the row as the sheet's button is pressed, and stays off while 30nama's list still sends it.
 */
object DismissChecks {
    fun run(instrumentation: Instrumentation) = with(instrumentation) {
        val result = Bundle()
        var resultCode = Activity.RESULT_OK
        var homeActivity: MainActivity? = null
        val originalClient = Api.http
        val originalToken = Api.token
        val originalUser = Api.user
        val originalIntro = Intro.shown
        val clientField = Api::class.java.getDeclaredField("http").apply { isAccessible = true }
        val kept = """{"id":21,"title":{"english":"Kept on"},"options":{"title_type":"movie"},"data":{"time":600,"progress":"20"}}"""
        val removed = """{"id":22,"title":{"english":"Taken off"},"options":{"title_type":"movie"},"data":{"time":900,"progress":"40"}}"""
        var listed = 0
        // 30nama's list lags: it still sends the title after the dismiss went through.
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val body = when (chain.request().url.pathSegments.last()) {
                "home_stream" -> """{"movies":{"posts":[{"id":1,"title":{"english":"Dismiss check"},"options":{"title_type":"movie"}}]}}"""
                "menu" -> """{"categories":[]}"""
                "user_list" -> """{"posts":[]}"""
                "continue_watching" -> {
                    listed++
                    """{"posts":[$kept,$removed]}"""
                }
                else -> "{}"
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"success":true,"result":$body}""".toResponseBody()).build()
        }.build()

        fun titles(home: HomeFragment) = (0 until home.adapter.size()).map { home.adapter[it] as ListRow }
            .firstOrNull { it.id == HomeFragment.CONTINUE.toLong() }?.adapter?.let { row -> (0 until row.size()).map { (row[it] as Card).title } }

        try {
            runOnMainSync {
                clientField.set(Api, client)
                Api.token = "dismiss-test"
                Api.user = null
                Intro.shown = true
                targetContext.filesDir.resolve("home").deleteRecursively()
            }
            homeActivity = startActivitySync(Intent(targetContext, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_GUEST, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val host = checkNotNull(homeActivity)
            lateinit var home: HomeFragment
            await("Continue Watching did not load") {
                val found = host.supportFragmentManager.findFragmentById(R.id.browse_host) as? HomeFragment ?: return@await false
                (titles(found) == listOf("Kept on", "Taken off")).also { if (it) home = found }
            }
            runOnMainSync {
                HomeFragment::class.java.getDeclaredMethod("confirmDismiss", JSONObject::class.java).apply { isAccessible = true }
                    .invoke(home, JSONObject(removed))
            }
            val remove = host.getString(R.string.continue_remove)
            lateinit var button: View
            await("The sheet did not offer to remove the title") {
                texts(host.window.decorView).firstOrNull { it.isShown && it.text.toString() == remove }?.let { label ->
                    var view: View? = label
                    while (view != null && !view.isClickable) view = view.parent as? View
                    view?.also { button = it }
                } != null
            }
            val pressed = SystemClock.elapsedRealtime()
            val before = listed
            runOnMainSync { button.performClick() }
            await("The title did not leave the row at once", 1_000) { titles(home) == listOf("Kept on") }
            val took = SystemClock.elapsedRealtime() - pressed
            await("The row did not ask 30nama again") { listed > before }
            SystemClock.sleep(800) // the answer that still sends it lands
            runOnMainSync { check(titles(home) == listOf("Kept on")) { "30nama's lagging list brought it back: ${titles(home)}" } }
            capture("dismiss")
            result.putString("stream", "PASS: off the row in $took ms, kept off while 30nama still sends it\n")
        } catch (error: Throwable) {
            result.putString("stream", error.stackTraceToString())
            resultCode = Activity.RESULT_CANCELED
        } finally {
            runOnMainSync { homeActivity?.finish() }
            await("Home did not close") { homeActivity?.isDestroyed != false }
            runOnMainSync {
                Api.token = originalToken
                Api.user = originalUser
                Intro.shown = originalIntro
                clientField.set(Api, originalClient)
                targetContext.filesDir.resolve("home").deleteRecursively()
            }
            client.dispatcher.cancelAll()
        }
        finish(resultCode, result)
    }

    private fun texts(view: View?): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun Instrumentation.await(message: String, timeoutMs: Long = 7_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        do {
            var passed = false
            runOnMainSync { passed = condition() }
            if (passed) return
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        error(message)
    }
}
