package com.doxigo.tv30

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HorizontalGridView
import androidx.leanback.widget.ItemBridgeAdapter
import java.io.File
import java.util.Locale
import kotlin.math.abs

/** Run the existing instrumentation runner with -e check lists. */
object ListsChecks {
    fun run(instrumentation: Instrumentation) = with(instrumentation) {
        val result = Bundle()
        var resultCode = Activity.RESULT_OK
        var activity: Activity? = null
        try {
            activity = startActivitySync(Intent(targetContext, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val host = activity
            for (language in listOf("fa", "en")) for (scale in listOf(1f, 1.3f)) {
                lateinit var grid: HorizontalGridView
                val clicked = IntArray(3)
                onMain {
                    val config = Configuration(host.resources.configuration).apply {
                        setLocale(Locale.forLanguageTag(language))
                        fontScale = scale
                    }
                    val context = host.createConfigurationContext(config)
                    val dp = context.resources.displayMetrics.density
                    val names = listOf(R.string.list_favorite, R.string.list_watchlist, R.string.list_notify)
                    val icons = listOf(R.drawable.ic_favorite, R.drawable.ic_watchlist, R.drawable.ic_notify)
                    val items = ArrayObjectAdapter(ListsPresenter()).apply {
                        names.forEachIndexed { i, name -> add(Card(context.getString(name), icon = icons[i]) { clicked[i]++ }) }
                    }
                    val root = FrameLayout(context).apply {
                        layoutDirection = config.layoutDirection
                        setBackgroundResource(R.color.ink)
                    }
                    grid = HorizontalGridView(context).apply {
                        adapter = ItemBridgeAdapter(items).apply {
                            setAdapterListener(object : ItemBridgeAdapter.AdapterListener() {
                                override fun onCreate(viewHolder: ItemBridgeAdapter.ViewHolder) {
                                    viewHolder.itemView.setOnClickListener { (viewHolder.item as Card).onClick() }
                                }
                            })
                        }
                        horizontalSpacing = (16 * dp).toInt()
                        setPadding((96 * dp).toInt(), (12 * dp).toInt(), (96 * dp).toInt(), (12 * dp).toInt())
                        clipToPadding = false
                    }
                    root.addView(grid, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (160 * dp).toInt(), android.view.Gravity.CENTER_VERTICAL))
                    host.setContentView(root)
                    grid.requestFocus()
                }
                waitForIdleSync()
                SystemClock.sleep(250)
                onMain {
                    val links = (0..2).map { checkNotNull(grid.findViewHolderForAdapterPosition(it)).itemView as ViewGroup }
                    check(links[1].width > links[0].width && links[0].width == links[2].width) { "Watchlist lost its hierarchy" }
                    val labels = links.map { link -> (0 until link.childCount).map(link::getChildAt).filterIsInstance<TextView>().single() }
                    labels.forEach { label ->
                        check(label.layout.getEllipsisCount(0) == 0) { "${label.text} clipped at font scale $scale" }
                        val minimum = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 18f, label.resources.displayMetrics)
                        check(label.textSize >= minimum - 0.5f) { "${label.text} shrank below the readable minimum" }
                        check(label.textAlignment == View.TEXT_ALIGNMENT_VIEW_START)
                    }
                    val baselines = labels.map { it.top + it.baseline }
                    check(baselines.all { abs(it - baselines[0]) <= 2 }) { "List labels lost their baseline: $baselines" }
                    if (language == "fa") check(links[0].left > links[1].left && links[1].left > links[2].left)
                    else check(links[0].left < links[1].left && links[1].left < links[2].left)
                }
                val forward = if (language == "fa") KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
                for (i in 0..2) {
                    onMain {
                        check(grid.selectedPosition == i) { "Remote missed list $i" }
                        val link = checkNotNull(grid.findViewHolderForAdapterPosition(i)).itemView as ViewGroup
                        check(link.hasFocus())
                        val label = (0 until link.childCount).map(link::getChildAt).filterIsInstance<TextView>().single()
                        check(label.currentTextColor == host.getColor(R.color.ink)) { "Focused label disappeared on white" }
                        check((link.background.current as GradientDrawable).color?.defaultColor == host.getColor(R.color.text))
                    }
                    val screenshot = checkNotNull(uiAutomation.takeScreenshot())
                    File(targetContext.filesDir, "lists-$language-$scale-$i.png").outputStream().use {
                        screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    screenshot.recycle()
                    press(KeyEvent.KEYCODE_DPAD_CENTER)
                    onMain { check(clicked[i] == 1) { "List $i did not open" } }
                    if (i < 2) press(forward)
                }
            }
            result.putString("stream", "PASS: My Lists hierarchy, three actions, D-pad focus, white/ink states, RTL/LTR and 1.3x text; screenshots saved\n")
        } catch (error: Throwable) {
            result.putString("stream", error.stackTraceToString())
            resultCode = Activity.RESULT_CANCELED
        } finally {
            onMain { activity?.finish() }
        }
        finish(resultCode, result)
    }

    private fun Instrumentation.press(code: Int) {
        sendKeySync(KeyEvent(KeyEvent.ACTION_DOWN, code))
        sendKeySync(KeyEvent(KeyEvent.ACTION_UP, code))
        waitForIdleSync()
        SystemClock.sleep(250)
    }

    private fun Instrumentation.onMain(action: () -> Unit) {
        var failure: Throwable? = null
        runOnMainSync {
            try { action() } catch (error: Throwable) { failure = error }
        }
        failure?.let { throw it }
    }
}
