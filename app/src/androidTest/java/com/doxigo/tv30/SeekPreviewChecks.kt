package com.doxigo.tv30

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.util.LruCache
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import androidx.media3.exoplayer.ExoPlayer
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import java.io.File

/**
 * Run with -e check preview. A film on a public HLS stream, played at 480p with a 240p quality
 * beside it: scrubbing the bar shows that quality's frame over the dot (SeekPreview), Persian then
 * English. In Persian the same rounds of scrubbing, landing and playing on run with the previews
 * off, then on, and the result reports the player's dropped frames for each.
 */
object SeekPreviewChecks {
    private const val BASE = "https://test-streams.mux.dev/x36xhzz"

    fun run(instrumentation: Instrumentation) = with(instrumentation) {
        val result = Bundle()
        var resultCode = Activity.RESULT_OK
        var player: PlayerActivity? = null
        val originalClient = Api.http
        val originalUser = Api.user
        val originalLanguage = Api.language
        val clientField = Api::class.java.getDeclaredField("http").apply { isAccessible = true }
        val report = StringBuilder()
        val id = "preview-${System.currentTimeMillis()}"
        val postId = (System.currentTimeMillis() % 1_000_000).toInt()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = when (request.url.pathSegments.last()) {
                "stream" -> """{"success":true,"result":{"info":{"title":{"english":"Big Buck Bunny 2008","local":"باک باکس بانی"}},
                    "hls":[{"info":{"file_id":"$id"},"file":{"m3u8":[{"label":"480p","default":"$BASE/url_6/193039199_mp4_h264_aac_hq_7.m3u8"},
                    {"label":"240p","default":"$BASE/url_2/193039199_mp4_h264_aac_ld_7.m3u8"}]},"options":{}}]}}"""
                else -> null
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (body == null) 404 else 200)
                .message("OK").body((body ?: "").toResponseBody()).build()
        }.build()
        // The controls' poster at the start edge, which the frame passes over at that end of the bar.
        val poster = File(targetContext.cacheDir, "preview-poster.jpg").also { out ->
            val bitmap = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF14213D.toInt()) }
            out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        }
        val title = JSONObject("""{"image":{"poster":{"jpg":{"large":"${poster.absolutePath}"}}},"info":{"year":2008},"score":{"imdb":{"score":7.6}}}""")
        try {
            setInTouchMode(false)
            runOnMainSync {
                Buffering.cdn
                clientField.set(Api, client)
                Api.user = JSONObject("""{"user_id":1,"user_allowed_to_stream":true}""")
                Api.language = "fa"
            }
            for (language in listOf("fa", "en")) {
                runOnMainSync { Api.language = language }
                val activity = startActivitySync(PlayerActivity.intent(targetContext, postId, false, title = title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PlayerActivity
                player = activity
                lateinit var fragment: PlayerFragment
                await("Player did not load", 15_000) {
                    val f = activity.supportFragmentManager.findFragmentById(android.R.id.content) as? PlayerFragment
                    f != null && field(f, "glue") != null && field(f, "preview") != null && run { fragment = f; true }
                }
                val exo = field(fragment, "player") as ExoPlayer
                val preview = field(fragment, "preview") as SeekPreview
                val image = field(preview, "image") as ImageView
                val card = field(preview, "card") as View
                val lowest = { EpisodeFrames.lowest(field(fragment, "file") as JSONObject) }
                // In Persian, off from the start (as a file whose frames failed): the film with no extractor ever opened.
                runOnMainSync {
                    exo.volume = 0f
                    if (language == "fa") setField(preview, "broken", lowest())
                }
                await("Playback did not start", 30_000) { exo.isPlaying }

                fun dropped(): Pair<Int, Int> {
                    var counts = 0 to 0
                    runOnMainSync { exo.videoDecoderCounters?.let { it.ensureUpdated(); counts = it.droppedBufferCount to it.renderedOutputBufferCount } }
                    return counts
                }

                /** Rounds of four presses [key], the seek landing a second on, then a few seconds' play; [shot] the first preview. */
                fun scrub(rounds: Int, shot: String? = null, key: Int = KeyEvent.KEYCODE_DPAD_RIGHT): String {
                    val (dropped0, rendered0) = dropped()
                    var shown = 0
                    repeat(rounds) {
                        repeat(4) {
                            sendKeyDownUpSync(key)
                            SystemClock.sleep(250)
                        }
                        var showing = false
                        val deadline = SystemClock.elapsedRealtime() + 800
                        while (!showing && SystemClock.elapsedRealtime() < deadline) {
                            runOnMainSync { showing = card.isShown && card.alpha > 0.9f && image.drawable != null && image.width > 0 && image.height > 0 }
                            if (!showing) SystemClock.sleep(50)
                        }
                        if (showing && shown++ == 0 && shot != null) capture(shot)
                        await("The seek did not land", 30_000) { exo.isPlaying }
                        SystemClock.sleep(2_500)
                    }
                    val (dropped1, rendered1) = dropped()
                    return "dropped ${dropped1 - dropped0} of ${dropped1 - dropped0 + rendered1 - rendered0} frames, preview shown in $shown of $rounds rounds"
                }

                if (language == "fa") {
                    // Off and on in turn, so a host's swings in load fall on both.
                    for (on in listOf(false, true, false, true)) {
                        if (on) {
                            runOnMainSync {
                                setField(preview, "broken", null)
                                exo.pause() // playing again opens it, a few seconds on
                                exo.play()
                            }
                            // Opened a few seconds into playing, the frame where the playhead is already decoded.
                            await("The extractor did not open and warm", 30_000) { (field(preview, "frames") as LruCache<*, *>).size() > 0 }
                        } else runOnMainSync {
                            preview.close()
                            setField(preview, "broken", lowest())
                        }
                        val rounds = scrub(3, "preview-seek-fa".takeIf { on })
                        report.append("${if (on) "on" else "off"}: $rounds\n")
                        check(rounds.contains("shown in 0 of") != on) { "Previews ${if (on) "missing" else "shown while off"}" }
                    }
                } else {
                    await("The extractor did not open and warm", 30_000) { (field(preview, "frames") as LruCache<*, *>).size() > 0 }
                    // Forward, then back over the frames that came meanwhile: these show at once.
                    scrub(1)
                    await("No frame came for the scrub", 30_000) { (field(preview, "frames") as LruCache<*, *>).size() > 1 }
                    val back = scrub(1, "preview-seek-en", KeyEvent.KEYCODE_DPAD_LEFT)
                    report.append("on (en): $back\n")
                    check(!back.contains("shown in 0 of")) { "No preview showed while scrubbing in English" }
                }
                runOnMainSync { activity.finish() }
                await("Player did not close") { activity.isDestroyed }
            }
            result.putString("stream", "PASS: previews over the dot in Persian and English\n$report")
        } catch (error: Throwable) {
            result.putString("stream", "$report${error.stackTraceToString()}")
            resultCode = Activity.RESULT_CANCELED
        } finally {
            runOnMainSync { player?.finish() }
            runCatching { await("Player did not close") { player?.isDestroyed != false } } // the result above says what failed
            runOnMainSync {
                Api.user = originalUser
                Api.language = originalLanguage
                clientField.set(Api, originalClient)
            }
            client.dispatcher.cancelAll()
            poster.delete()
        }
        finish(resultCode, result)
    }

    private fun field(instance: Any, name: String): Any? =
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance)

    private fun setField(instance: Any, name: String, value: Any?) =
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(instance, value)

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
