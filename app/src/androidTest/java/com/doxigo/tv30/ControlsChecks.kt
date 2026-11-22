package com.doxigo.tv30

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.leanback.media.PlaybackTransportControlGlue
import androidx.leanback.widget.Action
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.SilenceMediaSource
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/** Run the existing instrumentation runner with -e check controls. */
object ControlsChecks {
    fun run(instrumentation: Instrumentation) = with(instrumentation) {
        val result = Bundle()
        var resultCode = Activity.RESULT_OK
        var activity: PlayerActivity? = null
        val originalClient = Api.http
        val originalUser = Api.user
        val clientField = Api::class.java.getDeclaredField("http").apply { isAccessible = true }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            check(chain.request().url.pathSegments.last() == "stream")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"success":true,"result":{"info":{"title":{"english":"Controls check"}},"hls":[{"info":{"file_id":"test"},"file":{}}]}}""".toResponseBody()).build()
        }.build()
        try {
            runOnMainSync {
                clientField.set(Api, client)
                Api.user = null
            }
            activity = startActivitySync(PlayerActivity.intent(targetContext, 1, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PlayerActivity
            val host = activity
            lateinit var fragment: PlayerFragment
            lateinit var player: ExoPlayer
            lateinit var glue: PlaybackTransportControlGlue<*>
            await("Player controls did not load") {
                val found = host.supportFragmentManager.findFragmentById(android.R.id.content) as? PlayerFragment
                val loaded = found?.let { field(it, "glue") } as? PlaybackTransportControlGlue<*>
                if (found == null || loaded == null) false else {
                    fragment = found
                    glue = loaded
                    player = field(found, "player") as ExoPlayer
                    true
                }
            }
            runOnMainSync {
                val timeout = TypedValue()
                check(host.theme.resolveAttribute(androidx.leanback.R.attr.playbackControlsAutoHideTickleTimeout, timeout, true))
                check(timeout.data == 5_000) { "Remote interaction disables auto-hide: ${timeout.data}" }
                player.volume = 0f
                player.setMediaItem(MediaItem.fromUri("android.resource://${host.packageName}/${R.raw.nama_motion}"))
                player.repeatMode = Player.REPEAT_MODE_ONE
                player.prepare()
                player.play()
            }
            await("Playback did not enable auto-hide") { player.isPlaying && fragment.isControlsOverlayAutoHideEnabled }
            await("Initial controls stayed visible") { !fragment.isControlsOverlayVisible }

            press(KeyEvent.KEYCODE_DPAD_DOWN)
            runOnMainSync {
                check(fragment.isControlsOverlayVisible) { "Remote did not reveal controls" }
                val actions = checkNotNull(fragment.view).findViewById<View>(androidx.leanback.R.id.controls_dock)
                    .findViewById<ViewGroup>(androidx.leanback.R.id.control_bar)
                check(actions.getChildAt(actions.childCount / 2).hasFocus()) { "Controls did not open on play/pause: ${fragment.view?.findFocus()}" }
            }
            SystemClock.sleep(3_000)
            press(KeyEvent.KEYCODE_DPAD_UP)
            SystemClock.sleep(3_000)
            runOnMainSync { check(fragment.isControlsOverlayVisible) { "Auto-hide ignored recent remote input" } }
            await("Controls did not hide after remote inactivity") { !fragment.isControlsOverlayVisible }

            runOnMainSync {
                fragment.tickle()
                glue.onActionClicked(field(glue, "hq") as Action)
                check((field(fragment, "panel") as PlayerPanel).isOpen)
                check(!fragment.isControlsOverlayVisible)
                val matches = ArrayList<View>()
                checkNotNull(fragment.view).findViewsWithText(matches, host.getString(R.string.playback_diagnostics), View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
                matches.single().performClick()
            }
            await("Playback diagnostics did not display all five values") {
                val note = fragment.view?.findViewWithTag<TextView>("panelNote")
                val decoder = (field(fragment, "preview") as SeekPreview).decoderName
                note != null && note.isShown && note.lineCount >= 5 && note.text.lines().size == 5 &&
                    "1920 × 1080".localDigits() in note.text && decoder != null && decoder in note.text
            }
            press(KeyEvent.KEYCODE_BACK)
            runOnMainSync { check((field(fragment, "panel") as PlayerPanel).isOpen) }
            press(KeyEvent.KEYCODE_BACK)
            runOnMainSync {
                check(!(field(fragment, "panel") as PlayerPanel).isOpen)
                check(fragment.isControlsOverlayVisible) { "Panel did not restore controls" }
                player.repeatMode = Player.REPEAT_MODE_OFF
                player.setMediaSource(SilenceMediaSource.Factory().setDurationUs(300_000_000).createMediaSource())
                player.prepare()
            }
            await("Closing a panel lost the auto-hide timer") { !fragment.isControlsOverlayVisible }

            press(KeyEvent.KEYCODE_MEDIA_PAUSE)
            await("Pause did not keep controls visible") {
                !player.isPlaying && !fragment.isControlsOverlayAutoHideEnabled && fragment.isControlsOverlayVisible
            }
            SystemClock.sleep(6_000)
            runOnMainSync { check(fragment.isControlsOverlayVisible) { "Paused controls auto-hid" } }
            press(KeyEvent.KEYCODE_MEDIA_PLAY)
            await("Resume did not restore auto-hide") { player.isPlaying && fragment.isControlsOverlayAutoHideEnabled }
            await("Controls stayed visible after resuming") { !fragment.isControlsOverlayVisible }

            var beforeSeek = 0L
            runOnMainSync {
                player.pause()
                beforeSeek = player.currentPosition
            }
            press(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
            await("Remote forward seek did not resume") { player.isPlaying && player.currentPosition >= beforeSeek + 9_000 }
            runOnMainSync {
                player.pause()
                beforeSeek = player.currentPosition
            }
            press(KeyEvent.KEYCODE_MEDIA_REWIND)
            await("Remote rewind did not seek") { player.currentPosition <= beforeSeek - 9_000 }
            runOnMainSync {
                check(!player.playWhenReady) { "Remote rewind changed paused playback" }
                beforeSeek = player.currentPosition
                glue.onActionClicked(field(glue, "forward") as Action)
            }
            await("Forward button did not resume") { player.isPlaying && player.currentPosition >= beforeSeek + 9_000 }

            runOnMainSync {
                player.pause()
                player.seekTo(60_000)
            }
            await("Seek bar position did not update") { (glue.controlsRow?.currentPosition ?: 0) >= 60_000 && !player.isPlaying }
            runOnMainSync {
                fragment.tickle()
                check(checkNotNull(fragment.view).findViewById<View>(androidx.leanback.R.id.playback_progress).requestFocus())
                beforeSeek = player.currentPosition
            }
            press(KeyEvent.KEYCODE_DPAD_RIGHT)
            runOnMainSync {
                check(scrubbed(glue) - beforeSeek in 4_900L..5_100L) { "Seek bar did not step five seconds: ${scrubbed(glue) - beforeSeek}" }
                check(player.currentPosition == beforeSeek) { "Scrubbing seeked the player before it landed" }
                check(!player.playWhenReady) { "Seek bar resumed before confirmation" }
            }
            press(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Confirmed forward seek did not land and resume") { player.isPlaying && player.currentPosition >= beforeSeek + 5_000 }

            // Held, the step grows: two seconds in, a repeat steps ten seconds.
            runOnMainSync {
                player.pause()
                player.seekTo(60_000)
            }
            await("Playback did not pause before a held seek") { !player.isPlaying && (glue.controlsRow?.currentPosition ?: 0L) == 60_000L }
            runOnMainSync {
                fragment.tickle()
                check(checkNotNull(fragment.view).findViewById<View>(androidx.leanback.R.id.playback_progress).requestFocus())
            }
            val down = SystemClock.uptimeMillis() - 2_000
            sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, 20))
            sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT, 0))
            waitForIdleSync()
            runOnMainSync { check(scrubbed(glue) - 60_000 in 9_900L..10_100L) { "A held key did not speed up: ${scrubbed(glue) - 60_000}" } }
            // A second after the last press the seek lands without OK; forward, playback goes on.
            await("Scrub did not land by itself") { player.isPlaying && player.currentPosition >= 70_000 }

            // From hidden controls, right brings up the bar already seeking.
            runOnMainSync {
                player.pause()
                player.seekTo(60_000)
            }
            await("Playback did not pause before a seek from hidden controls") { !player.isPlaying && (glue.controlsRow?.currentPosition ?: 0L) == 60_000L }
            runOnMainSync {
                fragment.hideControlsOverlay(false)
                check(!fragment.isControlsOverlayVisible)
            }
            press(KeyEvent.KEYCODE_DPAD_RIGHT)
            runOnMainSync {
                check(fragment.isControlsOverlayVisible) { "Right did not reveal controls" }
                check(checkNotNull(fragment.view).findViewById<View>(androidx.leanback.R.id.playback_progress).isFocused) { "Right from hidden controls did not focus the seek bar" }
                check(scrubbed(glue) - 60_000 in 4_900L..5_100L) { "Right from hidden controls did not seek: ${scrubbed(glue) - 60_000}" }
            }
            await("Seek from hidden controls did not land") { player.isPlaying && player.currentPosition >= 65_000 }

            runOnMainSync { player.pause() }
            await("Playback did not pause before seek cancellation") { !player.isPlaying }
            runOnMainSync {
                fragment.tickle()
                check(checkNotNull(fragment.view).findViewById<View>(androidx.leanback.R.id.playback_progress).requestFocus())
                beforeSeek = player.currentPosition
            }
            press(KeyEvent.KEYCODE_DPAD_RIGHT)
            press(KeyEvent.KEYCODE_BACK)
            runOnMainSync {
                check(!player.playWhenReady) { "Cancelled forward seek resumed playback" }
                check(player.currentPosition == beforeSeek) { "Cancelled seek did not restore its position" }
            }
            press(KeyEvent.KEYCODE_DPAD_LEFT)
            runOnMainSync { check(beforeSeek - scrubbed(glue) in 4_900L..5_100L) { "Seek bar did not step back five seconds" } }
            press(KeyEvent.KEYCODE_DPAD_CENTER)
            runOnMainSync {
                check(beforeSeek - player.currentPosition in 4_900L..5_100L) { "Backward seek did not land" }
                check(!player.playWhenReady) { "Backward seek changed paused playback" }
            }

            // A bar drawn behind the paused film: a forward scrub left to land goes on from the film, playing.
            runOnMainSync {
                beforeSeek = player.currentPosition
                glue.controlsRow?.currentPosition = 0
                fragment.hideControlsOverlay(false)
            }
            await("Controls did not hide before the stale-bar seek") { !fragment.isControlsOverlayVisible }
            press(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Forward seek from a stale bar did not land ahead and play") { player.isPlaying && player.currentPosition >= beforeSeek + 5_000 }

            // Idle while a 403's stream answer is asked for again: the bar keeps the film's place, and a
            // scrub meanwhile plays on once it is prepared again.
            runOnMainSync {
                player.stop()
                beforeSeek = player.currentPosition
            }
            await("Idle bar lost the film's place") { glue.controlsRow?.currentPosition == beforeSeek && (glue.controlsRow?.duration ?: 0L) > 0 }
            runOnMainSync {
                fragment.tickle()
                check(checkNotNull(fragment.view).findViewById<View>(androidx.leanback.R.id.playback_progress).requestFocus())
            }
            press(KeyEvent.KEYCODE_DPAD_RIGHT)
            SystemClock.sleep(1_500) // landed
            runOnMainSync { player.prepare() }
            await("Seek while idle did not play on") { player.isPlaying && player.currentPosition >= beforeSeek + 5_000 }

            // A note going up mid-scrub takes the key's release, which the seek no longer waits on to land.
            runOnMainSync {
                beforeSeek = player.currentPosition
                fragment.hideControlsOverlay(false)
            }
            await("Controls did not hide before the interrupted seek") { !fragment.isControlsOverlayVisible }
            sendKeySync(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
            runOnMainSync {
                PlayerFragment::class.java.getDeclaredMethod("showNote", CharSequence::class.java, Int::class.javaPrimitiveType, Function0::class.java)
                    .apply { isAccessible = true }.invoke(fragment, "Controls check", R.string.retry, {})
            }
            sendKeySync(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT))
            await("Seek under a note did not land and play") { player.isPlaying && player.currentPosition >= beforeSeek + 5_000 }
            runOnMainSync { PlayerFragment::class.java.getDeclaredMethod("hideNote").apply { isAccessible = true }.invoke(fragment) }
            result.putString("stream", "PASS: five-second remote timeout, inactivity reset, play/pause focus, panel return, pause/resume, remote/button/bar forward resume, 5 s bar steps, held acceleration, self-landing and hidden-controls seeks, seek cancellation, paused rewind, stale-bar, idle and note-interrupted seeks\n")
        } catch (error: Throwable) {
            result.putString("stream", error.stackTraceToString())
            resultCode = Activity.RESULT_CANCELED
        } finally {
            runOnMainSync { activity?.finish() }
            await("Player activity did not close") { activity?.isDestroyed != false }
            runOnMainSync {
                Api.user = originalUser
                clientField.set(Api, originalClient)
            }
            client.dispatcher.cancelAll()
        }
        finish(resultCode, result)
    }

    /** Where the seek bar points mid-scrub: the glue moves the row, the player waits for the landing. */
    private fun scrubbed(glue: PlaybackTransportControlGlue<*>) = checkNotNull(glue.controlsRow).currentPosition

    private fun field(instance: Any, name: String): Any? =
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance)

    private fun Instrumentation.press(code: Int) {
        sendKeySync(KeyEvent(KeyEvent.ACTION_DOWN, code))
        sendKeySync(KeyEvent(KeyEvent.ACTION_UP, code))
        waitForIdleSync()
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
