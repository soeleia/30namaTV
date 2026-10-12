package com.doxigo.tv30

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.Button
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage
import androidx.media3.exoplayer.source.SilenceMediaSource
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import kotlin.math.abs

/** Run the existing instrumentation runner with -e check skip. */
object SkipChecks {
    fun run(instrumentation: Instrumentation) = with(instrumentation) {
        val result = Bundle()
        var resultCode = Activity.RESULT_OK
        var activity: PlayerActivity? = null
        val originalClient = Api.http
        val originalUser = Api.user
        val originalAutoSkip = Settings.autoSkip
        val originalAutoplay = Settings.autoplay
        val clientField = Api::class.java.getDeclaredField("http").apply { isAccessible = true }
        val fixture = """{"success":true,"result":{"info":{"title":{"english":"Skip check"}},"hls":[
            {"info":{"file_id":"first","season":1,"episode_number":1},"file":{},"options":{"intro_start":"00:00:00","intro_end":"00:00:20","previously_start":"00:00:30","previously_end":"00:00:40","end":"00:00:50"}},
            {"info":{"file_id":"next","season":1,"episode_number":2},"file":{}}
        ]}}"""
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            check(chain.request().url.pathSegments.last() == "stream")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(fixture.toResponseBody()).build()
        }.build()
        try {
            runOnMainSync {
                clientField.set(Api, client)
                Api.user = null
                Settings.autoSkip = false
                Settings.autoplay = false
            }
            activity = startActivitySync(PlayerActivity.intent(targetContext, 1, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PlayerActivity
            val host = activity
            lateinit var fragment: PlayerFragment
            lateinit var player: ExoPlayer
            lateinit var skip: Button
            await("Player did not load") {
                val found = host.supportFragmentManager.findFragmentById(android.R.id.content) as? PlayerFragment
                if (found == null || field(found, "glue") == null) false else {
                    fragment = found
                    player = field(found, "player") as ExoPlayer
                    skip = field(found, "skip") as Button
                    true
                }
            }
            fun level() = (skip.background as LayerDrawable).findDrawableByLayerId(R.id.player_skip_fill).level
            fun offered(label: Int) = skip.visibility == View.VISIBLE && skip.text.startsWith(host.getString(label))
            runOnMainSync {
                player.volume = 0f
                player.setPlaybackSpeed(1f)
                player.setMediaSource(SilenceMediaSource.Factory().setDurationUs(300_000_000).createMediaSource())
                player.seekTo(2_000)
                player.prepare()
                player.play()
            }
            await("Intro did not show remaining time") { player.isPlaying && offered(R.string.skip_intro) && level() in 7_000..9_500 }
            var before = 0
            runOnMainSync { before = level() }
            SystemClock.sleep(500)
            runOnMainSync { check(level() < before - 100) { "Intro fill did not shrink during playback" } }

            lateinit var paused: PlayerMessage
            runOnMainSync {
                player.pause()
                paused = player.createMessage { _, _ -> }.send()
            }
            check(paused.blockUntilDelivered(7_000)) { "Playback thread did not complete pause" }
            waitForIdleSync()
            var pausedAt = 0L
            runOnMainSync { pausedAt = player.currentPosition }
            val pausedFill = ((20_000L - pausedAt).coerceIn(0, 20_000) * 10_000 / 20_000).toInt()
            await("Paused intro fill did not settle at $pausedAt ms (expected level $pausedFill)") {
                !player.isPlaying && offered(R.string.skip_intro) && abs(level() - pausedFill) <= 30
            }
            runOnMainSync { before = level(); pausedAt = player.currentPosition }
            SystemClock.sleep(600)
            var after = 0
            var afterAt = 0L
            var stillPlaying = false
            runOnMainSync { after = level(); afterAt = player.currentPosition; stillPlaying = player.isPlaying }
            check(abs(after - before) <= 30) {
                "Intro fill moved while paused: position=$pausedAt->$afterAt ms, level=$before->$after, isPlaying=$stillPlaying"
            }
            runOnMainSync { player.seekTo(10_000) }
            await("Paused forward seek did not update remaining time") { offered(R.string.skip_intro) && level() in 4_800..5_200 }
            runOnMainSync { player.seekTo(5_000) }
            await("Paused backward seek did not restore remaining time") { offered(R.string.skip_intro) && level() in 7_300..7_700 }
            runOnMainSync {
                before = level()
                player.play()
            }
            await("Playback did not resume") { player.isPlaying }
            SystemClock.sleep(500)
            runOnMainSync { check(level() < before - 100) { "Intro fill did not resume" } }
            runOnMainSync { player.seekTo(21_000) }
            await("Intro pill stayed after its end") { skip.visibility == View.GONE }

            runOnMainSync {
                player.pause()
                player.seekTo(32_000)
            }
            await("Recap did not show its own remaining interval") { offered(R.string.player_skip_recap) && level() in 7_800..8_200 }
            runOnMainSync {
                before = level()
                player.play()
            }
            await("Recap did not play") { player.isPlaying }
            SystemClock.sleep(500)
            runOnMainSync {
                check(level() < before - 200) { "Recap fill did not shrink" }
                skip.performClick()
            }
            await("Skip recap did not reach its end") { player.currentPosition >= 40_000 && skip.visibility == View.GONE }

            runOnMainSync {
                Settings.autoSkip = true
                player.pause()
                player.seekTo(2_000)
            }
            await("Auto-skip intro was not offered") { offered(R.string.skip_intro) }
            SystemClock.sleep(600)
            runOnMainSync {
                check(player.currentPosition == 2_000L) { "Auto-skip ran while paused" }
                player.play()
            }
            await("Existing automatic intro skip stopped working") { player.currentPosition >= 20_000 && skip.visibility == View.GONE }

            runOnMainSync {
                Settings.autoSkip = false
                Settings.autoplay = true
                player.pause()
                player.seekTo(51_000)
            }
            await("Next-episode countdown was not offered") { offered(R.string.next_episode) }
            runOnMainSync {
                check(level() == 0) { "Next-episode countdown inherited remaining-intro fill" }
                player.play()
            }
            await("Next-episode countdown did not grow") { level() >= 500 }
            runOnMainSync {
                player.pause()
                before = level()
            }
            SystemClock.sleep(600)
            runOnMainSync { check(abs(level() - before) <= 30) { "Next-episode countdown moved while paused" } }
            runOnMainSync { player.play() }
            await("Next-episode countdown did not advance the episode", 15_000) {
                (field(fragment, "file") as JSONObject).str("info", "file_id") == "next"
            }
            result.putString("stream", "PASS: intro/recap remaining fill, paused freeze, seek recalculation, skip action, automatic intro skip and next-episode countdown\n")
        } catch (error: Throwable) {
            result.putString("stream", error.stackTraceToString())
            resultCode = Activity.RESULT_CANCELED
        } finally {
            runOnMainSync { activity?.finish() }
            await("Player activity did not close") { activity?.isDestroyed != false }
            runOnMainSync {
                Settings.autoSkip = originalAutoSkip
                Settings.autoplay = originalAutoplay
                Api.user = originalUser
                clientField.set(Api, originalClient)
            }
            client.dispatcher.cancelAll()
        }
        finish(resultCode, result)
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
