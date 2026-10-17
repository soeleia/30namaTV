package com.doxigo.tv30

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.SubtitleParser
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.Locale
import kotlin.math.abs

object SubtitleChecks {
    fun run(instrumentation: Instrumentation) = with(instrumentation) {
        val result = Bundle()
        var resultCode = Activity.RESULT_OK
        var activity: PlayerActivity? = null
        val originalClient = Api.http
        val originalUser = Api.user
        val originalLanguage = Api.language
        val originalLocale = Locale.getDefault()
        val prefs = targetContext.getSharedPreferences("player", android.content.Context.MODE_PRIVATE)
        val originalSubtitle = prefs.getString("subtitle", null)
        val clientField = Api::class.java.getDeclaredField("http").apply { isAccessible = true }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            check(chain.request().url.pathSegments.last() == "stream")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"success":true,"result":{"info":{"title":{"english":"A title that must never push the subtitles sideways"}},"hls":[{"info":{"file_id":"test"},"file":{}}]}}""".toResponseBody()).build()
        }.build()
        try {
            for (language in listOf("fa", "en")) {
                runOnMainSync {
                    clientField.set(Api, client)
                    Api.user = null
                    Api.language = language
                    prefs.edit().putString("subtitle", "fa").apply()
                }
                activity = startActivitySync(PlayerActivity.intent(targetContext, 1, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PlayerActivity
                val host = checkNotNull(activity)
                lateinit var fragment: PlayerFragment
                lateinit var cues: CueView
                await("Player subtitles did not load") {
                    val found = host.supportFragmentManager.findFragmentById(android.R.id.content) as? PlayerFragment
                    val root = found?.view as? ViewGroup
                    if (root?.findViewById<View>(androidx.leanback.R.id.transport_row)?.height?.let { it > 0 } != true) false else {
                        fragment = checkNotNull(found)
                        cues = field(fragment, "cues") as CueView
                        true
                    }
                }
                val text = "سلام دنیا!\nNASA در سال ۲۰۲۶ برگشت!\nنسخه \u20661080p 123\u2069 آماده است؟"
                val styled = SpannableString(text).apply { setSpan(StyleSpan(Typeface.BOLD), 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                runOnMainSync {
                    show(fragment, listOf(styled))
                    fragment.hideControlsOverlay(false)
                }
                await("Resting subtitles did not stay centered") { !fragment.isControlsOverlayVisible && centered(fragment, cues) }
                capture("subtitles-rest-$language")
                runOnMainSync {
                    check(cues.text.toString() == text) { "Cue joining lost text or bidi controls" }
                    check(cues.textDirection == View.TEXT_DIRECTION_RTL) { "Persian subtitles inherited the UI language" }
                    for (line in 0 until cues.layout.lineCount) check(cues.layout.getParagraphDirection(line) == -1)
                    val exclamation = text.indexOf('!')
                    check(cues.layout.getPrimaryHorizontal(exclamation) < cues.layout.getPrimaryHorizontal(0)) { "Trailing Persian punctuation moved to the sentence's start" }
                    check((cues.layoutParams as FrameLayout.LayoutParams).bottomMargin > 0)
                }
                // Both kinds of Persian file in circulation, through the parsers ExoPlayer is given.
                for ((kind, file, shown) in listOf(Triple("ltr", LTR_TYPED, LTR_SHOWN), Triple("rtl", RTL_TYPED, RTL_SHOWN))) {
                    val parsed = parse(file)
                    check(parsed.map { it.joinToString("\n") { cue -> cue.text.toString() } } == shown) { "A $kind-typed file came out as $parsed" }
                    runOnMainSync { show(fragment, parsed.last().mapNotNull { it.text }) }
                    SystemClock.sleep(300) // on screen, for the screenshot: both kinds must look alike
                    runOnMainSync { check(closesAtEnd(cues)) { "A $kind-typed file's closing marks left the end of their lines" } }
                    capture("subtitles-$kind-typed-$language")
                }
                runOnMainSync {
                    show(fragment, listOf(styled))
                    fragment.showControlsOverlay(false)
                }
                await("Controls pushed subtitles sideways") {
                    fragment.isControlsOverlayVisible && centered(fragment, cues) && aboveControls(fragment, cues)
                }
                capture("subtitles-controls-$language")
                runOnMainSync {
                    prefs.edit().putString("subtitle", "en").apply()
                    fragment.javaClass.getDeclaredMethod("applySubtitleStyle").apply { isAccessible = true }.invoke(fragment)
                    show(fragment, listOf("Hello, world!", "NASA 1080p, 2026."))
                }
                waitForIdleSync()
                runOnMainSync {
                    check(cues.text.toString() == "Hello, world!\nNASA 1080p, 2026.")
                    check(cues.textDirection == View.TEXT_DIRECTION_LTR)
                    for (line in 0 until cues.layout.lineCount) check(cues.layout.getParagraphDirection(line) == 1)
                    check(cues.layout.getPrimaryHorizontal(12) > cues.layout.getPrimaryHorizontal(0)) { "English punctuation changed direction" }
                    host.finish()
                }
                await("Player did not close") { host.isDestroyed }
                activity = null
            }
            result.putString("stream", "PASS: centered resting/visible-controls subtitles, RTL/LTR punctuation, Latin names, numbers, bidi controls, stacked cues, and LTR- and RTL-typed Persian files' letters and closing marks, Latin lines' included\n")
        } catch (error: Throwable) {
            result.putString("stream", error.stackTraceToString())
            resultCode = Activity.RESULT_CANCELED
        } finally {
            runOnMainSync { activity?.finish() }
            await("Player did not close") { activity?.isDestroyed != false }
            runOnMainSync {
                prefs.edit().putString("subtitle", originalSubtitle).apply()
                Api.user = originalUser
                Api.language = originalLanguage
                Locale.setDefault(originalLocale)
                clientField.set(Api, originalClient)
            }
            client.dispatcher.cancelAll()
        }
        finish(resultCode, result)
    }

    /**
     * Typed for players that lay every line out left to right, closing marks first, on an Arabic
     * keyboard (ي, ك). Both files end on a line typed the other way, as a second hand leaves one,
     * and a Latin-only line, as a song's name or a sign is given.
     */
    private const val LTR_TYPED = "1\n00:00:01,000 --> 00:00:03,000\n.سلام، حالت چطوره\n\n2\n00:00:03,000 --> 00:00:05,000\n!يه لحظه صبر كن\n...پس كجا رفتي\nباشه.\nHello, world.\n"
    private val LTR_SHOWN = listOf("\u2066.سلام، حالت چطوره\u2069", "\u2066!یه لحظه صبر کن\u2069\n\u2066...پس کجا رفتی\u2069\nباشه.\n\u2066Hello, world.\u2069")
    /** Written right: each closing mark closes its sentence. */
    private const val RTL_TYPED = "1\n00:00:01,000 --> 00:00:03,000\nسلام، حالت چطوره.\n\n2\n00:00:03,000 --> 00:00:05,000\nیه لحظه صبر کن!\nپس کجا رفتی...\n.باشه\nHello, world.\n"
    private val RTL_SHOWN = listOf("سلام، حالت چطوره.", "یه لحظه صبر کن!\nپس کجا رفتی...\n\u2066.باشه\u2069\n\u2066Hello, world.\u2069")

    /** [srt]'s cues as the player gets them, a list per cue. */
    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    private fun parse(srt: String): List<List<Cue>> = buildList {
        Subtitles.Parsers().create(Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_SUBRIP).build())
            .parse(srt.toByteArray(), SubtitleParser.OutputOptions.allCues()) { add(it.cues) }
    }

    /**
     * Whether each line's first closing mark is drawn where its sentence ends: in its left half for
     * a Persian line, its right half for a Latin one. For a Persian line the secondary caret: in an
     * isolate it is the mark's own edge, where the primary one is the isolate's opening mark at the
     * line's far end. For a Latin line both carets: a mark cut off from its text, on its own run,
     * has one of them at that text's end.
     */
    private fun closesAtEnd(cues: CueView): Boolean {
        val layout = cues.layout ?: return false
        var start = 0
        return cues.text.toString().split('\n').all { line ->
            val mark = start + line.indexOfFirst { it in ".!" }
            start += line.length + 1
            val row = layout.getLineForOffset(mark)
            val middle = (layout.getLineLeft(row) + layout.getLineRight(row)) / 2
            if (line.any { it in '\u0600'..'\u06FF' }) layout.getSecondaryHorizontal(mark) < middle
            else minOf(layout.getPrimaryHorizontal(mark), layout.getSecondaryHorizontal(mark)) > middle
        }
    }

    private fun show(fragment: PlayerFragment, text: List<CharSequence>) {
        val method = fragment.javaClass.getDeclaredMethod("showCues", List::class.java).apply { isAccessible = true }
        method.invoke(fragment, text.map { Cue.Builder().setText(it).build() })
    }

    private fun centered(fragment: PlayerFragment, cues: CueView): Boolean {
        val root = fragment.view ?: return false
        if (cues.layout == null || cues.width == 0) return false
        return abs(cues.left + cues.width / 2f + cues.translationX - root.width / 2f) <= 1f &&
            (0 until cues.layout.lineCount).all {
                abs((cues.layout.getLineLeft(it) + cues.layout.getLineRight(it) - cues.layout.width) / 2f) <= 1f
            }
    }

    private fun aboveControls(fragment: PlayerFragment, cues: CueView): Boolean {
        val root = fragment.view ?: return false
        val position = IntArray(2)
        cues.getLocationOnScreen(position)
        val bottom = position[1] + cues.height
        return listOf(androidx.leanback.R.id.transport_row, androidx.leanback.R.id.lb_details_description_title,
            androidx.leanback.R.id.lb_details_description_subtitle).all { id ->
            val control = root.findViewById<View>(id)
            if (control == null || !control.isShown || control.height == 0) true else {
                control.getLocationOnScreen(position)
                bottom <= position[1] - (9 * root.resources.displayMetrics.density).toInt()
            }
        }
    }

    private fun field(instance: Any, name: String): Any? =
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance)

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
