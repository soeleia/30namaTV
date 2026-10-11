package com.doxigo.tv30

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.ListRow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.io.File

/** Run with adb shell am instrument -w com.doxigo.tv30.test/com.doxigo.tv30.PerformanceChecks. */
class PerformanceChecks : Instrumentation() {
    private var requestedCheck: String? = null
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        requestedCheck = arguments?.getString("check")
        start()
    }

    override fun onStart() {
        waitForIdleSync()
        when (requestedCheck) {
            "intro" -> return IntroChecks.run(this)
            "controls" -> return ControlsChecks.run(this)
            "alignment" -> return AlignmentChecks.run(this)
            "trailer" -> return TrailerChecks.run(this)
            "subtitles" -> return SubtitleChecks.run(this)
            "skip" -> return SkipChecks.run(this)
            "title" -> return TitleChecks.run(this)
            "lists" -> return ListsChecks.run(this)
            "search" -> return SearchGridChecks.run(this)
            "genres" -> return GenresChecks.run(this)
            "updates" -> return UpdatesChecks.run(this)
        }
        val result = Bundle()
        try {
            runBlocking {
                pagerCancelsReplacedRequests()
                homeDoesNotWaitForExtras(signedIn = false)
                homeDoesNotWaitForExtras(signedIn = true)
            }
            result.putString("stream", "PASS: pager reset/cancel, guest and signed-in startup with blocked extras, late row order\n")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", error.stackTraceToString())
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private suspend fun pagerCancelsReplacedRequests() = withContext(Dispatchers.Main) {
        val scope = CoroutineScope(Job() + Dispatchers.Main.immediate)
        val cancelled = mutableListOf<Int>()
        var requests = 0
        var errors = 0
        val pager = Pager(scope, ArrayObjectAdapter(), { error("No response expected") }, onError = { errors++ }) {
            val request = ++requests
            try { awaitCancellation() } finally { cancelled += request }
        }
        try {
            pager.reset()
            check(requests == 1)
            pager.reset()
            check(requests == 2 && cancelled == listOf(1)) { "Reset left the old request running" }
            pager.cancel()
            check(cancelled == listOf(1, 2) && errors == 0) { "Search cancellation leaked work or showed an error" }
            pager.reset()
            check(requests == 3) { "Pager did not restart after cancellation" }
        } finally {
            scope.cancel()
        }
    }

    private suspend fun homeDoesNotWaitForExtras(signedIn: Boolean) {
        val releaseMenu = CountDownLatch(1)
        val menuStarted = CompletableDeferred<Unit>()
        val originalClient = Api.http
        val originalToken = Api.token
        val originalUser = Api.user
        val originalIntro = Intro.shown
        val clientField = Api::class.java.getDeclaredField("http").apply { isAccessible = true }
        var activity: MainActivity? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val action = chain.request().url.pathSegments.last()
            val body = when (action) {
                "test" -> "{}"
                "home_stream" -> """{"movies":{"posts":[{"id":1,"title":{"english":"Performance test"},"options":{"title_type":"movie"}}]}}"""
                else -> {
                    if (action == "menu") menuStarted.complete(Unit)
                    check(releaseMenu.await(15, TimeUnit.SECONDS)) { "Menu was never released" }
                    when (action) {
                        "menu" -> """{"categories":[{"name":"Test category","slug":"movies"}]}"""
                        "user", "user_mylist" -> "{}"
                        "user_list" -> """{"posts":[]}"""
                        "continue_watching" -> """{"posts":[{"id":2,"title":{"english":"Continue test"},"options":{"title_type":"movie"}}]}"""
                        else -> error("Unexpected request: $action")
                    }
                }
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"success":true,"result":$body}""".toResponseBody()).build()
        }.build()
        try {
            withContext(Dispatchers.Main) {
                clientField.set(Api, client)
                Api.token = if (signedIn) "performance-test" else null
                Intro.shown = true
            }
            activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_GUEST, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            withTimeout(5_000) { menuStarted.await() }
            val started = SystemClock.elapsedRealtime()
            withTimeout(5_000) {
                while (!withContext(Dispatchers.Main) {
                    val home = activity.supportFragmentManager.findFragmentById(R.id.browse_host) as? HomeFragment
                    home?.adapter?.size() == (if (signedIn) 2 else 1) && home.view?.findViewById<View>(androidx.leanback.R.id.browse_container_dock)?.alpha == 1f
                }) delay(20)
            }
            check(releaseMenu.count == 1L) { "Menu completed before the catalogue was visible" }
            sendStatus(0, Bundle().apply { putString("stream", "Home visible, signedIn=$signedIn, extras blocked: ${SystemClock.elapsedRealtime() - started} ms\n") })
            releaseMenu.countDown()
            withTimeout(5_000) {
                while (!withContext(Dispatchers.Main) {
                    val home = activity.supportFragmentManager.findFragmentById(R.id.browse_host) as? HomeFragment
                    home?.adapter?.size() == (if (signedIn) 4 else 2)
                }) delay(20)
            }
            withContext(Dispatchers.Main) {
                val home = activity.supportFragmentManager.findFragmentById(R.id.browse_host) as HomeFragment
                val ids = (0 until home.adapter.size()).map { (home.adapter[it] as ListRow).id }
                check(ids == ids.sorted()) { "Late rows changed the catalogue order" }
            }
        } finally {
            releaseMenu.countDown()
            withContext(Dispatchers.Main) {
                activity?.finish()
                Api.token = originalToken
                Api.user = originalUser
                Intro.shown = originalIntro
                clientField.set(Api, originalClient)
            }
            client.dispatcher.cancelAll()
        }
    }
}

internal fun Instrumentation.capture(name: String) {
    val screenshot = checkNotNull(uiAutomation.takeScreenshot())
    File(targetContext.getExternalFilesDir(null), "$name.png").outputStream().use {
        screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
    }
    screenshot.recycle()
}
