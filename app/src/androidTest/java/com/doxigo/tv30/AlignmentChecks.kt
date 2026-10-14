package com.doxigo.tv30

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.Layout
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.math.abs

object AlignmentChecks {
    fun run(instrumentation: Instrumentation) = with(instrumentation) {
        val result = Bundle()
        var resultCode = Activity.RESULT_OK
        var activity: Activity? = null
        val language = Api.language
        val locale = Locale.getDefault()
        try {
            for (code in listOf("fa", "en")) {
                runOnMainSync { Api.language = code }
                activity = startActivitySync(Intent(targetContext, SettingsActivity::class.java)
                    .putExtra("row", 0).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                waitForIdleSync()
                val host = checkNotNull(activity)
                if (code == "fa") linesFit(host)
                runOnMainSync {
                    playerColumns(host)
                    val choices = host.findViewById<LinearLayout>(R.id.settings_choices)
                    check(choices.childCount == Settings.QUALITIES.size) { "Settings opened with ${choices.childCount} choices, focus on ${host.currentFocus}, touch mode ${host.window.decorView.isInTouchMode}" }
                    val values = (0 until choices.childCount).map { choices.getChildAt(it) as TextView }
                    values.forEach(::aligned)
                    check(values.map { it.compoundPaddingStart }.distinct().size == 1) { "The selected value shifted past its check" }
                    listOf(R.id.settings_detail_title, R.id.settings_detail_body, R.id.settings_label).forEach {
                        aligned(host.findViewById(it))
                    }
                    val options = host.findViewById<ViewGroup>(R.id.settings_options)
                    val preload = (0 until options.childCount).map(options::getChildAt).first {
                        it.findViewById<TextView>(R.id.settings_label)?.text?.toString() == host.getString(R.string.settings_buffer)
                    }
                    check(preload.requestFocus())
                }
                waitForIdleSync()
                runOnMainSync {
                    aligned(host.findViewById(R.id.settings_note))
                    host.finish()
                }
                activity = null
            }
            result.putString("stream", "PASS: English/Persian player columns, headings, check gutters and Settings choices/notes in RTL and LTR, widened Persian lines and their ink within their width, ellipsized synopses cut at whole words\n")
        } catch (error: Throwable) {
            result.putString("stream", error.stackTraceToString())
            resultCode = Activity.RESULT_CANCELED
        } finally {
            runOnMainSync {
                activity?.finish()
                Api.language = language
                Locale.setDefault(locale)
            }
        }
        finish(resultCode, result)
    }

    private fun playerColumns(host: Activity) {
        val root = FrameLayout(host).apply { layoutDirection = host.resources.configuration.layoutDirection }
        val under = View(host)
        root.addView(under)
        PlayerPanel(root, under) { }.open {
            Page("Column test", listOf(listOf(Group("Choices", listOf(
                Choice("خودکار", true) { },
                Choice("1080p", false) { },
                Choice("audio", false) { },
                Choice("ظاهر زیرنویس", null) { },
            )))))
        }
        root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1920, 1080)
        fun texts(view: View): List<TextView> = when (view) {
            is TextView -> listOf(view)
            is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
            else -> emptyList()
        }
        val values = texts(root)
        values.forEach(::aligned)
        val header = values.single { it.text.toString() == "Choices" }
        val start = start(root, header)
        values.filter { it.text.toString() !in listOf("Choices", "Column test") }.forEach {
            check(abs(start(root, it) - start) <= 1) { "${it.text} left its column's text edge" }
        }
        check(values.single { it.text.toString() == "1080p" }.layout.getParagraphDirection(0) == 1)
        check(values.single { it.text.toString() == "خودکار" }.layout.getParagraphDirection(0) == -1)
    }

    /**
     * Persian with its widened word spaces (TextSpacing.kt) breaks at the width it is drawn at: no
     * line's words run past its view's end edge. Before, Android 10 and up broke lines for the
     * font's narrow space and drew the wide one, the last word cut off. Nor is any ink drawn past
     * that edge, where TextView clips it: from Android 15 a line-final ر's tail (Fit gives the word
     * to the next line), and on every version the ellipsized synopsis's «…» line, which StaticLayout
     * cut mid-word, the cut letter then drawn in its wider final form (Fit ends it at a whole word).
     * At every width a synopsis might have, each laid out and redone on screen as it would be.
     */
    private fun Instrumentation.linesFit(host: Activity) {
        val texts = listOf(
            "این متن آزمایشی چند خط فارسی است که فاصله‌های میان واژه‌هایش پهن‌تر از فاصلهٔ خود قلم " +
                "کشیده می‌شوند، تا هیچ واژه‌ای در پایان خط از لبهٔ کادر بیرون نزند؛ خلاصهٔ داستان، راهنمای تنظیمات " +
                "و برگهٔ کلید همه همین‌گونه چیده می‌شوند.",
            "در این سفر دور و دراز مردی از شهر نور به کنار دریا می‌رود تا راز پدر را باز کند و هر بار که دیر " +
                "شود باز هم کار را از سر بگیرد تا روز آخر در کنار یار و دیار خود به خانه برگردد و دیگر هرگز از آن در دور نشود.",
        )
        val views = ArrayList<TextView>()
        lateinit var column: LinearLayout
        runOnMainSync {
            column = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
            for (words in texts) for (ellipsized in listOf(false, true)) for (width in 420..1100 step 8) {
                val view = TextView(host).apply {
                    setTextAppearance(R.style.TextAppearance_App_Synopsis)
                    widenSpaces()
                    if (ellipsized) {
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                        ellipsizeAtWords()
                    }
                    text = words
                }
                column.addView(view, LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT))
                views += view
            }
            host.findViewById<ViewGroup>(android.R.id.content).addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        // Fit lays a line out again before it is drawn, a frame or two each: until none changes.
        var layouts = emptyList<Layout?>()
        var still = 0
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (still < 3 && SystemClock.uptimeMillis() < deadline) {
            waitForIdleSync()
            SystemClock.sleep(32)
            var now = emptyList<Layout?>()
            runOnMainSync { now = views.map { it.layout } }
            if (now.indices.all { now[it] != null && layouts.getOrNull(it) === now[it] }) still++ else still = 0
            layouts = now
        }
        check(still >= 3) { "The synopses kept being laid out again" }
        runOnMainSync {
            try {
                for (view in views) {
                    val layout = checkNotNull(view.layout)
                    val ellipsized = view.maxLines == 2
                    val last = layout.lineCount - 1
                    for (line in 0 until layout.lineCount) {
                        // Its words' advances as drawn, not getLineMax: from Android 15 that takes glyph bounds.
                        val drawn = Layout.getDesiredWidth(layout.text, layout.getLineStart(line), layout.getLineVisibleEnd(line), view.paint)
                        check(drawn <= layout.width + 0.5f) { "Line $line of ${layout.lineCount} runs ${drawn - layout.width} px past its ${view.width} px view" }
                        if (Build.VERSION.SDK_INT < 35 && !(ellipsized && line == last)) continue
                        val past = inkPastEnd(layout, line)
                        check(past <= 0) { "Line $line of ${layout.lineCount}${if (ellipsized) ", ellipsized at two," else ""} draws $past px past the end of its ${view.width} px view" }
                    }
                }
            } finally {
                (column.parent as ViewGroup).removeView(column)
            }
        }
    }

    /** How far [line]'s ink reaches past the end edge of [layout] (its left: the text is Persian), in px. */
    private fun inkPastEnd(layout: Layout, line: Int): Int {
        val margin = 100
        val top = layout.getLineTop(line)
        val bitmap = Bitmap.createBitmap(layout.width + 2 * margin, layout.getLineBottom(line) - top, Bitmap.Config.ALPHA_8)
        try {
            val canvas = Canvas(bitmap)
            canvas.translate(margin.toFloat(), -top.toFloat())
            canvas.clipRect(-margin, layout.getLineTop(line), layout.width + margin, layout.getLineBottom(line))
            layout.draw(canvas)
            val pixels = ByteBuffer.allocate(bitmap.rowBytes * bitmap.height)
            bitmap.copyPixelsToBuffer(pixels)
            for (x in 0 until margin) for (y in 0 until bitmap.height) {
                if (pixels.get(y * bitmap.rowBytes + x).toInt() and 0xFF > 40) return margin - x
            }
            return 0
        } finally {
            bitmap.recycle()
        }
    }

    private fun start(root: ViewGroup, text: TextView): Int {
        val bounds = Rect(0, 0, text.width, text.height)
        root.offsetDescendantRectToMyCoords(text, bounds)
        return if (text.layoutDirection == View.LAYOUT_DIRECTION_RTL) bounds.right - text.compoundPaddingRight
        else bounds.left + text.compoundPaddingLeft
    }

    private fun aligned(text: TextView) {
        check(text.textAlignment == View.TEXT_ALIGNMENT_VIEW_START) { "${text.text} follows its text instead of its column" }
        val layout = checkNotNull(text.layout) { "${text.text} was not laid out" }
        for (line in 0 until layout.lineCount) {
            val offset = if (text.layoutDirection == View.LAYOUT_DIRECTION_RTL) layout.width - layout.getLineRight(line)
            else layout.getLineLeft(line)
            check(abs(offset) <= 1.5f) { "${text.text} is offset from its column by $offset px" }
        }
    }
}
