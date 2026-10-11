package com.doxigo.tv30

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
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
                runOnMainSync {
                    playerColumns(host)
                    val choices = host.findViewById<LinearLayout>(R.id.settings_choices)
                    check(choices.childCount == Settings.QUALITIES.size)
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
            result.putString("stream", "PASS: English/Persian player columns, headings, check gutters and Settings choices/notes in RTL and LTR\n")
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
