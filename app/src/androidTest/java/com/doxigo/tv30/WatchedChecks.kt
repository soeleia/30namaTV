package com.doxigo.tv30

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.SilenceMediaSource
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import java.io.File

/**
 * Run the existing instrumentation runner with -e check watched. A four-episode series: episode 1
 * played past its credits mark and on to episode 2 is recorded seen, and the player's episodes and
 * the title page both mark it, beside a started one (3) and the one Play resumes (2, the server's point).
 */
object WatchedChecks {
    fun run(instrumentation: Instrumentation) = with(instrumentation) {
        val result = Bundle()
        var resultCode = Activity.RESULT_OK
        var player: PlayerActivity? = null
        var details: DetailsActivity? = null
        val originalClient = Api.http
        val originalUser = Api.user
        val originalAutoSkip = Settings.autoSkip
        val originalAutoplay = Settings.autoplay
        val originalLanguage = Api.language
        val clientField = Api::class.java.getDeclaredField("http").apply { isAccessible = true }
        // Fresh file ids each run, so nothing a run before left in the history counts.
        val ids = (1..4).map { "watched-${System.currentTimeMillis()}-$it" }
        val hls = ids.mapIndexed { i, id ->
            """{"info":{"file_id":"$id","season":1,"episode_number":${i + 1}},"file":{},"options":{"end":"00:05:00"}}"""
        }.joinToString(",")
        val title = """{"id":1,"title":{"english":"Watched check"},"options":{"title_type":"series","free_stream":true}}"""
        var saved = "null"
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val body = when (chain.request().url.pathSegments.last()) {
                "stream" -> """{"success":true,"result":{"info":{"title":{"english":"Watched check"}},"continue_watching":$saved,"hls":[$hls]}}"""
                "single" -> """{"success":true,"result":$title}"""
                else -> null
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(if (body == null) 404 else 200)
                .message("OK").body((body ?: "").toResponseBody()).build()
        }.build()
        fun file(n: Int) = JSONObject("""{"info":{"file_id":"${ids[n - 1]}"},"options":{"end":"00:05:00"}}""")
        // A frame on disk for each, as EpisodeFrames keeps them, so the tiles show pictures to dim.
        val frames = (1..4).mapNotNull { EpisodeFrames.file(targetContext, file(it)) }
        try {
            runOnMainSync {
                clientField.set(Api, client)
                Api.user = null
                Settings.autoSkip = false
                Settings.autoplay = false
                Watched.record(targetContext, file(3), 200, 600) // started elsewhere: a third in
            }
            frames.forEachIndexed { i, out -> scene(out, i) }

            // ---- The player: episode 1 past its credits, then on to episode 2. ----
            player = startActivitySync(PlayerActivity.intent(targetContext, 1, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PlayerActivity
            val host = player
            lateinit var fragment: PlayerFragment
            lateinit var exo: ExoPlayer
            lateinit var skip: Button
            await("Player did not load") {
                val found = host.supportFragmentManager.findFragmentById(android.R.id.content) as? PlayerFragment
                if (found == null || field(found, "glue") == null) false else {
                    fragment = found
                    exo = field(found, "player") as ExoPlayer
                    skip = field(found, "skip") as Button
                    true
                }
            }
            fun silence() = SilenceMediaSource.Factory().setDurationUs(600_000_000).createMediaSource()
            runOnMainSync {
                exo.volume = 0f
                exo.setMediaSource(silence())
                exo.seekTo(120_000)
                exo.prepare()
                exo.play()
            }
            await("Playback did not start") { exo.isPlaying }
            runOnMainSync { exo.pause() } // a pause reports
            await("Two minutes in was not recorded as started") {
                Watched.of(host, file(1))?.let { !it.done && (it.progress ?: 0f) in 0.19f..0.22f } == true
            }
            runOnMainSync {
                exo.seekTo(301_000)
                exo.play()
            }
            await("Next episode was not offered past the credits mark") {
                skip.visibility == View.VISIBLE && skip.text.startsWith(host.getString(R.string.next_episode))
            }
            runOnMainSync {
                skip.performClick()
                // Episode 2 has no stream of its own here: silence from its start, held still.
                exo.setMediaSource(silence())
                exo.prepare()
                exo.pause()
            }
            await("Episode 2 did not start") { (field(fragment, "file") as JSONObject).str("info", "file_id") == ids[1] }
            runOnMainSync { check(Watched.of(host, file(1))?.done == true) { "Episode 1 past its credits was not recorded seen" } }

            runOnMainSync { fragment.javaClass.getDeclaredMethod("openEpisodes").apply { isAccessible = true }.invoke(fragment) }
            lateinit var panel: List<View>
            await("The episodes panel did not show four tiles") {
                panel = tiles(host.window.decorView)
                panel.size == 4 && panel.all { it.width > 0 }
            }
            runOnMainSync { states(host, panel, watching = 2) }
            SystemClock.sleep(600)
            capture("watched-panel")
            runOnMainSync { host.finish() }
            await("Player did not close") { host.isDestroyed }

            // ---- The title page: the server's point is episode 2, two minutes in. English first: the Persian run moves episode 4 on. ----
            saved = """{"file_id":"${ids[1]}","time":120,"progress":20}"""
            for (language in listOf("en", "fa")) {
                runOnMainSync { Api.language = language }
                details = startActivitySync(DetailsActivity.intent(targetContext, JSONObject(title)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as DetailsActivity
                val page = checkNotNull(details)
                lateinit var row: List<View>
                await("The title page did not show four tiles", 15_000) {
                    row = tiles(page.window.decorView)
                    row.size == 4 && row.all { it.width > 0 } && (field(page, "watch") as Button).isFocused
                }
                runOnMainSync {
                    states(page, row, watching = 2)
                    val play = field(page, "watch") as Button
                    check(play.text.toString() == page.getString(R.string.resume) + "  " + page.getString(R.string.season_episode, 1, 2)) {
                        "Play does not resume episode 2: ${play.text}"
                    }
                }
                sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
                await("Down did not land on the episode Play resumes") { tiles(page.window.decorView).firstOrNull { it.isFocused }?.let(::number) == 2 }
                SystemClock.sleep(700)
                capture("watched-title-$language")
                if (language == "fa") {
                    // Back on the page (from the player, say) with episode 4 seen since: it catches up in place, focus kept.
                    runOnMainSync {
                        Watched.record(page, file(4), 590, 600)
                        callActivityOnRestart(page)
                    }
                    await("Episode 4 did not catch up on return") {
                        tiles(page.window.decorView).firstOrNull { number(it) == 4 }?.contentDescription?.endsWith(page.getString(R.string.episode_watched)) == true
                    }
                    runOnMainSync { check(tiles(page.window.decorView).firstOrNull { it.isFocused }?.let(::number) == 2) { "The refresh moved focus off episode 2" } }
                    SystemClock.sleep(400)
                    capture("watched-title-refreshed")
                }
                runOnMainSync { page.finish() }
                await("Title page did not close") { page.isDestroyed }
            }
            result.putString("stream", "PASS: started and seen recorded from playback, panel and title page marks, TalkBack words, Play and Down on the resumed episode, refresh in place\n")
        } catch (error: Throwable) {
            result.putString("stream", error.stackTraceToString())
            resultCode = Activity.RESULT_CANCELED
        } finally {
            runOnMainSync {
                player?.finish()
                details?.finish()
            }
            await("Activities did not close") { player?.isDestroyed != false && details?.isDestroyed != false }
            runOnMainSync {
                Settings.autoSkip = originalAutoSkip
                Settings.autoplay = originalAutoplay
                Api.user = originalUser
                Api.language = originalLanguage
                clientField.set(Api, originalClient)
                frames.forEach { it.delete() }
            }
            client.dispatcher.cancelAll()
        }
        finish(resultCode, result)
    }

    /** Episode 1 seen (its check, dimmed, the word), [watching] says so, 3 its minutes left, 4 as it was. */
    private fun states(activity: Activity, tiles: List<View>, watching: Int) {
        val seenWord = activity.getString(R.string.episode_watched)
        for (tile in tiles) {
            val n = number(tile)
            val described = tile.contentDescription.toString()
            val check = field(tile, "check") as View
            val caption = (field(tile, "caption") as TextView).text.toString()
            when (n) {
                1 -> check(check.isShown && described.endsWith(seenWord)) { "Episode 1 is not marked seen: $described" }
                watching -> check(!check.isShown && caption == activity.getString(R.string.episode_watching)) { "Episode $n does not say it is being watched: $described" }
                3 -> check(!check.isShown && caption == activity.getString(R.string.time_left, activity.getString(R.string.runtime_minutes, 7))) {
                    "Episode 3 does not say its minutes left: $described"
                }
                else -> check(!check.isShown && !described.contains(seenWord)) { "Episode $n is marked: $described" }
            }
        }
    }

    /** A made-up frame: dusk over a hill, in a different light for each episode. */
    private fun scene(out: File, i: Int) {
        val bitmap = Bitmap.createBitmap(448, 252, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val skies = intArrayOf(0xFF2B4C7E.toInt(), 0xFF7E3B2B.toInt(), 0xFF2B7E5C.toInt(), 0xFF5C2B7E.toInt())
        canvas.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 0f, 252f, skies[i], 0xFFE8B15A.toInt(), Shader.TileMode.CLAMP) })
        canvas.drawCircle(120f + 60 * i, 120f, 34f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFF1C9.toInt() })
        canvas.drawOval(-120f, 170f, 600f, 420f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1B2416.toInt() })
        out.parentFile?.mkdirs()
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
    }

    /** The tile's episode, from its first words («قسمت ۲»). */
    private fun number(tile: View) = tile.contentDescription.toString().substringBefore('،').substringBefore(',')
        .mapNotNull(Char::digitToIntOrNull).fold(0) { n, digit -> n * 10 + digit }

    private fun tiles(root: View): List<View> = when {
        root.javaClass.simpleName == "EpisodeView" -> if (root.isShown && root.contentDescription != null) listOf(root) else emptyList()
        root is ViewGroup -> (0 until root.childCount).flatMap { tiles(root.getChildAt(it)) }.distinctBy(::number)
        else -> emptyList()
    }

    private fun field(instance: Any, name: String): Any? =
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance)

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
