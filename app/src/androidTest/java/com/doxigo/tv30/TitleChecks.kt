package com.doxigo.tv30

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.Locale

object TitleChecks {
    fun run(instrumentation: Instrumentation) = with(instrumentation) {
        val result = Bundle()
        var code = Activity.RESULT_OK
        var activity: PlayerActivity? = null
        val originalClient = Api.http
        val originalUser = Api.user
        val originalLanguage = Api.language
        val originalLocale = Locale.getDefault()
        val clientField = Api::class.java.getDeclaredField("http").apply { isAccessible = true }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            check(chain.request().url.pathSegments.last() == "stream")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"success":true,"result":{"info":{"title":{"english":"Illicit Friends","local":"دوستان ممنوعه"}},"hls":[{"info":{"file_id":"test"},"file":{}}]}}""".toResponseBody()).build()
        }.build()
        try {
            for (language in listOf("fa", "en")) {
                runOnMainSync {
                    clientField.set(Api, client)
                    Api.user = null
                    Api.language = language
                }
                activity = startActivitySync(PlayerActivity.intent(targetContext, 1, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PlayerActivity
                val host = checkNotNull(activity)
                lateinit var root: ViewGroup
                lateinit var title: TextView
                await("Player title did not lay out") {
                    val fragment = host.supportFragmentManager.findFragmentById(android.R.id.content) as? PlayerFragment
                    val view = fragment?.view as? ViewGroup
                    val text = view?.findViewById<TextView>(androidx.leanback.R.id.lb_details_description_title)
                    if (text?.layout == null || text.height == 0 || text.text.toString() != "Illicit Friends") false else {
                        checkNotNull(fragment).showControlsOverlay(false)
                        root = checkNotNull(view)
                        title = text
                        true
                    }
                }
                waitForIdleSync()
                runOnMainSync {
                    check(title.includeFontPadding)
                    check(title.typeface == host.font(R.font.inter_bold)) { "Player title does not use Inter" }
                    val glyphs = Rect()
                    title.paint.getTextBounds(title.text.toString(), 0, title.text.length, glyphs)
                    val inkTop = title.baseline + glyphs.top
                    val inkBottom = title.baseline + glyphs.bottom
                    check(inkTop >= 0 && inkBottom <= title.height) { "Title glyphs exceed their text box: $inkTop..$inkBottom/${title.height}" }
                    val position = IntArray(2)
                    title.getLocationOnScreen(position)
                    val visible = Rect()
                    check(title.getGlobalVisibleRect(visible))
                    check(visible.top <= position[1] + inkTop && visible.bottom >= position[1] + inkBottom) { "A parent clips the title glyphs: $visible" }
                    check((title.layoutParams as ViewGroup.MarginLayoutParams).topMargin >= 0) { "Leanback restored a negative title margin" }
                    val persian = root.findViewById<TextView>(androidx.leanback.R.id.lb_details_description_subtitle)
                    check(persian.isShown && persian.text.toString() == "دوستان ممنوعه") { "The Persian name is not under the title: ${persian.text}" }
                    val persianAt = IntArray(2)
                    persian.getLocationOnScreen(persianAt)
                    check(persianAt[1] >= position[1] + inkBottom) { "The Persian name is not below the English one" }
                }
                SystemClock.sleep(300)
                capture("title-$language")
                runOnMainSync { host.finish() }
                await("Player did not close") { host.isDestroyed }
                activity = null
            }
            result.putString("stream", "PASS: Inter player titles with their Persian names under them, complete I/F/d glyph bounds and ancestor clipping in Persian/English layouts\n")
        } catch (error: Throwable) {
            code = Activity.RESULT_CANCELED
            result.putString("stream", error.stackTraceToString())
        } finally {
            runOnMainSync { activity?.finish() }
            await("Player did not close") { activity?.isDestroyed != false }
            runOnMainSync {
                clientField.set(Api, originalClient)
                Api.user = originalUser
                Api.language = originalLanguage
                Locale.setDefault(originalLocale)
            }
            client.dispatcher.cancelAll()
        }
        finish(code, result)
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
