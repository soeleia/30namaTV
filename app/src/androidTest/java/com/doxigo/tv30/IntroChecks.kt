package com.doxigo.tv30

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import java.io.File
import kotlin.math.abs

/** Run the existing instrumentation runner with -e check intro. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
object IntroChecks {
    fun run(instrumentation: Instrumentation) = with(instrumentation) {
        waitForIdleSync()
        onMain { Settings.init(targetContext) }
        val result = Bundle()
        var resultCode = Activity.RESULT_OK
        var activity: Activity? = null
        var intro: Intro? = null
        val reduced = Settings.reduceMotion
        try {
            onMain { Settings.reduceMotion = false }
            activity = startActivitySync(Intent(targetContext, SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val host = activity
            lateinit var video: PlayerView
            onMain {
                check(host.getString(R.string.app_name) == "30namaTV")
                check(host.wordmark().toString() == "30namaTV")
                intro = Intro(host)
                video = host.findViewById(R.id.intro_video)
                check(host.findViewById<View>(R.id.intro_loading).visibility == View.INVISIBLE)
                check(video.player?.currentMediaItem?.localConfiguration?.uri.toString().endsWith("/${R.raw.nama_motion}"))
            }
            await("Original motion logo did not play") {
                video.player?.isPlaying == true && (video.player?.currentPosition ?: 0) >= 1_000
            }
            onMain { check(host.findViewById<View>(R.id.intro_loading).visibility == View.INVISIBLE) }
            screenshot("30namatv-motion-test.png")
            await("Slow-loading phase did not appear") {
                val loading = host.findViewById<View>(R.id.intro_loading)
                loading.visibility == View.VISIBLE && loading.alpha == 1f && video.player?.playbackState == Player.STATE_ENDED
            }
            onMain {
                val overlay = host.findViewById<View>(R.id.intro_overlay)
                val shift = (200 * host.resources.displayMetrics.density).coerceAtMost(overlay.height * .2f)
                check(abs(video.scaleX - .7f) < .001f && abs(video.scaleY - .7f) < .001f)
                check(abs(video.translationY + shift) < 1f) { "Loading logo exceeded its safe upward shift" }
            }
            verifyLoadingPixels(host)
            onMain { intro?.leave(); intro?.leave() }
            SystemClock.sleep(300)
            onMain {
                check(host.findViewById<View>(R.id.intro_overlay) == null)
                check(video.player == null) { "Motion decoder survived exit" }
            }

            onMain { intro = Intro(host); intro?.leave() }
            SystemClock.sleep(300)
            onMain {
                check(host.findViewById<View>(R.id.intro_overlay) == null) { "Ready content waited for the intro" }
            }

            lateinit var loading: View
            onMain {
                intro = Intro(host)
                loading = host.findViewById(R.id.intro_loading)
                intro?.dispose()
                check(host.findViewById<View>(R.id.intro_overlay) == null)
            }
            SystemClock.sleep(6_700)
            onMain {
                check(loading.visibility == View.INVISIBLE) { "A loading callback survived disposal" }
                Intro.shown = false
                Settings.reduceMotion = true
                check(Intro.shown) { "Reduced motion must skip the intro" }
                intro = Intro(host)
                check(host.findViewById<PlayerView>(R.id.intro_video).player == null) { "Reduced motion created a decoder" }
                check(host.findViewById<View>(R.id.intro_logo).visibility == View.VISIBLE)
                intro?.dispose()
            }
            onMain { Settings.reduceMotion = false; intro = Intro(host) }
            onMain { host.finish() }
            await("Stopping home left its motion decoder running") { host.isDestroyed && host.findViewById<View>(R.id.intro_overlay) == null }
            result.putString("stream", "PASS: 30namaTV identity, original motion playback/loading phase, immediate/repeated exit, decoder disposal, cancelled callback, reduced motion and lifecycle stop\n")
        } catch (error: Throwable) {
            result.putString("stream", error.stackTraceToString())
            resultCode = Activity.RESULT_CANCELED
        } finally {
            onMain {
                intro?.dispose()
                Settings.reduceMotion = reduced
                activity?.finish()
            }
        }
        finish(resultCode, result)
    }

    private fun Instrumentation.verifyLoadingPixels(host: Activity) {
        val viewport = Rect()
        val loading = Rect()
        val visible = Rect()
        var bars = Insets.NONE
        onMain {
            host.findViewById<View>(R.id.intro_overlay).screenBounds(viewport)
            host.findViewById<View>(R.id.intro_loading).screenBounds(loading)
            host.window.decorView.getWindowVisibleDisplayFrame(visible)
            bars = ViewCompat.getRootWindowInsets(host.window.decorView)?.getInsets(WindowInsetsCompat.Type.systemBars()) ?: Insets.NONE
        }
        val pixels = checkNotNull(uiAutomation.takeScreenshot())
        try {
            save(pixels, "30namatv-loading-test.png")
            check(viewport.intersect(visible)) { "Intro did not intersect the visible window: $viewport / $visible" }
            check(viewport.intersect(bars.left, bars.top, pixels.width - bars.right, pixels.height - bars.bottom)) { "Intro did not fit outside system bars: $viewport / $bars" }
            val logo = Rect(viewport.right, viewport.bottom, viewport.left, viewport.top)
            val loadingInk = Rect(loading.right, loading.bottom, loading.left, loading.top)
            val gap = (16 * host.resources.displayMetrics.density).toInt()
            var symbolBottom = viewport.top
            for (y in viewport.top until viewport.bottom step 2) {
                for (x in viewport.left until viewport.right step 2) {
                    val color = pixels.getPixel(x, y)
                    val red = color shr 16 and 255
                    val green = color shr 8 and 255
                    val blue = color and 255
                    if (red > 60 && red > green * 1.7f && red > blue * 1.4f) symbolBottom = maxOf(symbolBottom, y + 1)
                    if (loading.contains(x, y) && minOf(red, green, blue) > 120) {
                        loadingInk.left = minOf(loadingInk.left, x)
                        loadingInk.top = minOf(loadingInk.top, y)
                        loadingInk.right = maxOf(loadingInk.right, x + 1)
                        loadingInk.bottom = maxOf(loadingInk.bottom, y + 1)
                    }
                    if (y >= loading.top - gap || maxOf(red, green, blue) < 60) continue
                    logo.left = minOf(logo.left, x)
                    logo.top = minOf(logo.top, y)
                    logo.right = maxOf(logo.right, x + 1)
                    logo.bottom = maxOf(logo.bottom, y + 1)
                }
            }
            check(logo.width() > viewport.width() * .5f && logo.height() > viewport.height() * .2f) { "Final loading logo was not rendered: $logo" }
            check(logo.top >= viewport.top + viewport.height() * .05f) { "Loading logo was cropped at the top: $logo / viewport=$viewport, visible=$visible, bars=$bars" }
            check(logo.left >= viewport.left + viewport.width() * .05f && logo.right <= viewport.right - viewport.width() * .05f) { "Loading logo exceeded the viewport: $logo" }
            check(loadingInk.width() > loading.width() * .4f && loadingInk.height() > loading.height() * .2f) { "Loading text did not render: $loadingInk / $loading" }
            check(symbolBottom > viewport.top && symbolBottom + gap <= loadingInk.top) { "Loading text overlapped the logo: bottom=$symbolBottom / ink=$loadingInk / view=$loading" }
        } finally {
            pixels.recycle()
        }
    }

    private fun View.screenBounds(bounds: Rect) {
        val position = IntArray(2)
        getLocationOnScreen(position)
        bounds.set(position[0], position[1], position[0] + width, position[1] + height)
    }

    private fun Instrumentation.screenshot(name: String) {
        val pixels = checkNotNull(uiAutomation.takeScreenshot())
        try { save(pixels, name) } finally { pixels.recycle() }
    }

    private fun Instrumentation.save(pixels: Bitmap, name: String) {
        File(targetContext.filesDir, name).outputStream().use {
            pixels.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun Instrumentation.await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 8_000
        do {
            var passed = false
            onMain { passed = condition() }
            if (passed) return
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        error(message)
    }

    private fun Instrumentation.onMain(action: () -> Unit) {
        var failure: Throwable? = null
        runOnMainSync { try { action() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }
}
