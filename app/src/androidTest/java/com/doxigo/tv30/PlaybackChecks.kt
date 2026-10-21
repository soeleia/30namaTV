package com.doxigo.tv30

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.SilenceMediaSource
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject

/**
 * Run the existing instrumentation runner with -e check playback: the screen lit only while video
 * plays, previous restarting first, the channel keys, the next episode offered without a credits
 * mark, and the sleep timer.
 */
object PlaybackChecks {
    fun run(instrumentation: Instrumentation) = with(instrumentation) {
        val result = Bundle()
        var resultCode = Activity.RESULT_OK
        var activity: PlayerActivity? = null
        val originalClient = Api.http
        val originalUser = Api.user
        val originalAutoSkip = Settings.autoSkip
        val originalAutoplay = Settings.autoplay
        val clientField = Api::class.java.getDeclaredField("http").apply { isAccessible = true }
        // Three episodes, none with a credits mark, each with 30nama's Persian subtitle.
        val fixture = """{"success":true,"result":{"info":{"title":{"english":"Playback check"}},"hls":[
            {"info":{"file_id":"one","season":1,"episode_number":1},"file":{"subtitle":{"fa":"https://example.invalid/one.srt"}}},
            {"info":{"file_id":"two","season":1,"episode_number":2},"file":{"subtitle":{"fa":"https://example.invalid/two.srt"}}},
            {"info":{"file_id":"three","season":1,"episode_number":3},"file":{"subtitle":{"fa":"https://example.invalid/three.srt"}}}
        ]}}"""
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val name = chain.request().url.pathSegments.last()
            check(name == "stream" || name.endsWith(".srt"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body((if (name == "stream") fixture else "1\n00:00:01,000 --> 00:00:02,000\nسلام\n").toResponseBody()).build()
        }.build()
        val playerPrefs = targetContext.getSharedPreferences("player", Context.MODE_PRIVATE)
        val originalSubtitle = playerPrefs.getString("subtitle", null)
        try {
            runOnMainSync {
                clientField.set(Api, client)
                Api.user = null
                Settings.autoSkip = false
                Settings.autoplay = false
            }
            // A title of its own, with no point left from an earlier run: it starts on the first episode.
            targetContext.getSharedPreferences("watched", Context.MODE_PRIVATE).edit().remove("left.$POST").commit()
            playerPrefs.edit().remove("speed.$POST").remove("timing.$POST.fa").putString("subtitle", "fa").commit()
            activity = startActivitySync(PlayerActivity.intent(targetContext, POST, true)
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
            fun playing() = (field(fragment, "file") as JSONObject).str("info", "file_id")
            // The fragment's view, whose keepScreenOn the window follows.
            fun lit() = host.findViewById<ViewGroup>(android.R.id.content).getChildAt(0).keepScreenOn
            fun level() = (skip.background as LayerDrawable).findDrawableByLayerId(R.id.player_skip_fill).level
            fun offered(label: Int) = skip.visibility == View.VISIBLE && skip.text.startsWith(host.getString(label))
            // A press as the activity hands it on; true when the player took it.
            fun press(code: Int): Boolean {
                var taken = false
                runOnMainSync {
                    taken = fragment.onKey(KeyEvent(KeyEvent.ACTION_DOWN, code))
                    fragment.onKey(KeyEvent(KeyEvent.ACTION_UP, code))
                }
                return taken
            }
            fun silence(atMs: Long) = runOnMainSync {
                player.volume = 0f
                player.setMediaSource(SilenceMediaSource.Factory().setDurationUs(300_000_000).createMediaSource())
                player.seekTo(atMs)
                player.prepare()
                player.play()
            }
            fun sleep(choice: Int) = runOnMainSync {
                fragment.javaClass.getDeclaredMethod("setSleep", Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(fragment, choice)
            }

            silence(30_000)
            await("Playing video did not keep the screen lit", 15_000) { player.isPlaying && lit() }
            runOnMainSync { player.pause() }
            await("Paused video kept the screen lit") { !lit() }

            // The first episode has none before it: Previous restarts it, and channel down is the TV's.
            runOnMainSync { player.play() }
            check(press(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
            await("Previous did not restart the first episode") { playing() == "one" && player.currentPosition < 5_000 }
            check(!press(KeyEvent.KEYCODE_CHANNEL_DOWN)) { "Channel down was taken with no episode before" }
            check(press(KeyEvent.KEYCODE_CHANNEL_UP))
            await("Channel up did not play the next episode") { playing() == "two" }
            silence(30_000)
            await("Second episode did not play") { player.isPlaying && player.currentPosition >= 30_000 }
            press(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            await("Previous past ten seconds did not restart the episode") { playing() == "two" && player.currentPosition < 5_000 }
            press(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            await("Previous near the start did not go back an episode") { playing() == "one" }
            press(KeyEvent.KEYCODE_CHANNEL_UP)
            await("Channel up did not reach the second episode") { playing() == "two" }
            press(KeyEvent.KEYCODE_CHANNEL_UP)
            await("Channel up did not reach the last episode") { playing() == "three" }
            check(!press(KeyEvent.KEYCODE_CHANNEL_UP)) { "Channel up was taken on the last episode" }
            check(press(KeyEvent.KEYCODE_CHANNEL_DOWN))
            await("Channel down did not go back an episode") { playing() == "two" }

            // No credits mark: the next episode is offered and counted down to over the last 25 s.
            runOnMainSync {
                Settings.autoplay = true
                player.seekTo(260_000)
                player.play()
            }
            SystemClock.sleep(1_500)
            runOnMainSync { check(!offered(R.string.next_episode)) { "Next episode offered before the last 25 s" } }
            runOnMainSync { player.seekTo(276_000) }
            await("Next episode was not offered without a credits mark") { offered(R.string.next_episode) }
            await("Its countdown did not run") { level() >= 500 }
            // Its end is the skip suite's; here the pill itself goes on.
            runOnMainSync { skip.performClick() }
            await("The pill did not play the next episode") { playing() == "three" }

            // The sleep timer's end of this episode: no countdown, nothing after it, and the screen let go.
            press(KeyEvent.KEYCODE_CHANNEL_DOWN)
            await("Channel down did not go back to the second episode") { playing() == "two" }
            sleep(-1)
            runOnMainSync {
                player.seekTo(276_000)
                player.play()
            }
            await("Next episode was not offered under the sleep timer") { offered(R.string.next_episode) }
            SystemClock.sleep(1_200)
            runOnMainSync { check(level() == 0) { "The sleep timer's end still counted down to the next episode" } }
            runOnMainSync { player.seekTo(299_000) }
            await("The episode did not end", 10_000) { player.playbackState == Player.STATE_ENDED }
            SystemClock.sleep(500)
            runOnMainSync {
                check(playing() == "two") { "The sleep timer's end played on to ${playing()}" }
                check(field(fragment, "sleep") == 0) { "The sleep timer stayed set after it ended the episode" }
                check(!lit()) { "A finished episode kept the screen lit" }
            }

            // A timed one pauses when it is up.
            sleep(15)
            runOnMainSync {
                player.seekTo(30_000)
                player.play()
            }
            await("Playback did not resume after the end") { player.isPlaying }
            runOnMainSync { fragment.javaClass.getDeclaredField("sleepAt").apply { isAccessible = true }.setLong(fragment, SystemClock.elapsedRealtime() + 300) }
            await("The sleep timer did not pause playback", 5_000) { !player.playWhenReady && field(fragment, "sleep") == 0 }

            // For the eye: the controls with the moon, and the timer's page with one running.
            runOnMainSync {
                player.play()
                fragment.tickle()
            }
            SystemClock.sleep(800)
            capture("30namatv-player-sleep-button")
            sleep(30)
            runOnMainSync {
                val page = fragment.javaClass.getDeclaredMethod("sleepTimer").apply { isAccessible = true }
                fragment.javaClass.getDeclaredMethod("openPanel", Function0::class.java).apply { isAccessible = true }
                    .invoke(fragment, { page.invoke(fragment) })
            }
            SystemClock.sleep(800)
            capture("30namatv-player-sleep-timer")

            // A series keeps its speed and its own subtitle's timing: across episodes, and into the next viewing.
            runOnMainSync {
                call(fragment, "applySpeed", 1.5f)
                call(fragment, "nudge", 250L)
            }
            press(KeyEvent.KEYCODE_CHANNEL_UP)
            await("The next episode lost the subtitle's timing") { playing() == "three" && call(fragment, "offset") == 250L }
            runOnMainSync { host.finish() }
            await("Player activity did not close", 20_000) { host.isDestroyed }
            val again = startActivitySync(PlayerActivity.intent(targetContext, POST, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PlayerActivity
            activity = again
            await("The next viewing lost the series' speed and timing", 15_000) {
                val found = again.supportFragmentManager.findFragmentById(android.R.id.content) as? PlayerFragment
                found != null && field(found, "glue") != null &&
                    (field(found, "player") as ExoPlayer).playbackParameters.speed == 1.5f && call(found, "offset") == 250L
            }
            result.putString("stream", "PASS: screen lit only while playing, previous restarts first, channel keys, next episode without a credits mark, sleep timer end and time, a series' speed and subtitle timing kept\n")
        } catch (error: Throwable) {
            result.putString("stream", error.stackTraceToString())
            resultCode = Activity.RESULT_CANCELED
        } finally {
            runOnMainSync { activity?.finish() }
            await("Player activity did not close", 20_000) { activity?.isDestroyed != false }
            runOnMainSync {
                Settings.autoSkip = originalAutoSkip
                Settings.autoplay = originalAutoplay
                Api.user = originalUser
                clientField.set(Api, originalClient)
                playerPrefs.edit().remove("speed.$POST").remove("timing.$POST.fa").putString("subtitle", originalSubtitle).commit()
            }
            client.dispatcher.cancelAll()
        }
        finish(resultCode, result)
    }

    private const val POST = 4242

    /** A private method of [instance] by name, with Float and Long arguments unboxed. */
    private fun call(instance: Any, name: String, vararg args: Any): Any? = instance.javaClass.getDeclaredMethod(name, *args.map {
        when (it) {
            is Float -> Float::class.javaPrimitiveType
            is Long -> Long::class.javaPrimitiveType
            else -> it.javaClass
        }
    }.toTypedArray()).apply { isAccessible = true }.invoke(instance, *args)

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
