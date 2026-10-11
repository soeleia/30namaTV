package com.doxigo.tv30

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import coil3.load
import coil3.request.crossfade

// ---- Sheets: the app's own modal surfaces, in place of the system's grey AlertDialog. ----
//
// Call them on the activity (from a fragment, requireActivity()):
//
//   Activity.confirm(title, question, action, art: String? = null, onConfirm: () -> Unit)
//     requireActivity().confirm(title.englishName, getString(R.string.continue_remove_question),
//         getString(R.string.continue_remove), art = title.poster) { remove(title) }
//     Asks before a step the viewer can't take back, as the Apple TV app does: the page dims and
//     a card rises in the middle with the poster ([art], a URL; left out when null), the name
//     ([title], its face by its script), the [question] and two stacked buttons. [action] names
//     the step by its verb («حذف از ادامهٔ تماشا», never «تأیید»); Cancel is added and holds the
//     remote at first, so a stray OK changes nothing. Back and Esc cancel. onConfirm runs only
//     for the action.
//
//   Activity.choose(title, options: List<CharSequence>, current: Int, onChoose: (Int) -> Unit)
//     requireActivity().choose(getString(R.string.sort_by), SORTS.map { getString(it.second) }, sort) { picked ->
//         if (picked != sort) { sort = picked; pager.reset() }
//     }
//     One of a short list (up to about nine), from a panel at the end edge as the player's
//     settings come in: the current one checked and under the remote. OK applies and closes;
//     onChoose gets the index of any pick, the current one too. Back and Esc close without it.
//
// Both are views laid over the page in android.R.id.content, not Dialogs, so the remote, focus,
// RTL and the faces behave as everywhere else. While one is open it holds the remote: nothing
// beneath can take focus or TalkBack's attention, the window's keys reach it before the
// activity's own handling, and on closing the remote goes back to whatever opened it.

fun Activity.confirm(title: CharSequence, question: CharSequence, action: CharSequence, art: String? = null, onConfirm: () -> Unit) {
    val dp = resources.displayMetrics.density
    // The question on its own card, the contract's first surface step: solid, so no poster or
    // red row of the page can show through a button or run into the question. 32dp inside its
    // edge is room for a focused button's 5% lift too (9dp a side): the entrance draws the card
    // as one layer, which would cut off whatever reached past it.
    val column = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        background = GradientDrawable().apply {
            cornerRadius = px(20).toFloat() // as Settings' panel, the app's other raised surface
            setColor(getColor(R.color.surface))
        }
        val pad = px(CARD_PAD)
        setPadding(pad, pad, pad, pad)
    }
    // The poster the viewer just held OK on, so there is no doubt which title this is.
    if (art != null) column.addView(ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = GradientDrawable().apply {
            cornerRadius = resources.getDimension(R.dimen.corner)
            setColor(getColor(R.color.surface_high)) // a step up from the card while it loads
        }
        clipToOutline = true // to the background's corners
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        load(art) { crossfade(Motion.IMAGE) }
    }, linear(px(96), px(144)).apply { bottomMargin = px(24) })
    column.addView(text(title, R.style.TextAppearance_App_DialogTitle).apply {
        // A step above a panel's title: it stands alone in the middle of the screen. The name is
        // the server's, so its face follows its script, not the UI's language.
        textSize = 26f
        typeface = font(if (title.isLatin) R.font.inter_bold else R.font.peyda_bold)
        letterSpacing = if (title.isLatin) -0.01f else 0f
        maxLines = 2
        balanceLines()
    }, linear(MATCH, WRAP))
    column.addView(text(question, R.style.TextAppearance_App_Body).apply {
        textSize = 18f
        maxLines = 3
    }, linear(MATCH, WRAP).apply { topMargin = px(8) })
    val act = button(action)
    val cancel = button(getString(R.string.sheet_cancel))
    column.addView(act, linear(MATCH, WRAP).apply { topMargin = px(32) })
    // 8dp between the 48dp targets is 12dp between the pills they draw, the buttons' own gap.
    column.addView(cancel, linear(MATCH, WRAP).apply { topMargin = px(8) })

    val sheet = Sheet(this, column, FrameLayout.LayoutParams(px(360 + 2 * CARD_PAD), WRAP, Gravity.CENTER), SCRIM_ALERT, dy = 16 * dp)
    act.setOnClickListener { sheet.close(onConfirm) }
    cancel.setOnClickListener { sheet.close() }
    sheet.open(cancel, dotted(title.toString(), question.toString()))
}

fun Activity.choose(title: CharSequence, options: List<CharSequence>, current: Int, onChoose: (Int) -> Unit) {
    val dp = resources.displayMetrics.density
    val rtl = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
    val panel = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        // Ink at 97%: posters must not show through the labels. Wider at the screen's edge, for
        // overscan, as the player's panel is.
        setBackgroundColor(0xF7141414.toInt())
        setPaddingRelative(px(40), px(48), px(48), px(32))
        clipToPadding = false // a focused row's lift reaches into the padding
        clipChildren = false
    }
    panel.addView(text(title, R.style.TextAppearance_App_DialogTitle).apply { textAlignment = View.TEXT_ALIGNMENT_VIEW_START },
        linear(MATCH, WRAP).apply { bottomMargin = px(20) })
    // ponytail: no scrolling; the panel holds about nine rows on a 540dp screen. Wrap the rows
    // in a ScrollView if a longer list ever comes.
    val rows = options.mapIndexed { i, label -> choice(label, i == current) }
    rows.forEach { panel.addView(it, linear(MATCH, px(44)).apply { bottomMargin = px(4) }) }

    // In from the end edge, where the player's settings come from too.
    val sheet = Sheet(this, panel, FrameLayout.LayoutParams(px(400), MATCH, Gravity.END), SCRIM_PANEL, dx = 48 * dp * if (rtl) -1 else 1)
    rows.forEachIndexed { i, row ->
        row.setOnClickListener {
            // The check moves at once, so it shows on the way out.
            rows.forEach { mark(it, it === row) }
            sheet.close { onChoose(i) }
        }
    }
    sheet.open(rows.getOrElse(current) { rows.first() }, title)
}

/**
 * The page dimmed under [content], holding the remote until [close]. The activity's own keys (the
 * rail's Back, home's Up to the hero) would act on the page beneath, so while open the window's
 * keys come here first, as a Dialog's own window would take them.
 */
private class Sheet(
    private val activity: Activity,
    private val content: View,
    params: FrameLayout.LayoutParams,
    scrim: Int,
    private val dx: Float = 0f,
    private val dy: Float = 0f,
) {
    private val root: ViewGroup = activity.findViewById(android.R.id.content)
    private val opener: View? = activity.currentFocus
    private val keys: Window.Callback = activity.window.callback
    /** One flat colour: no offscreen pass while its alpha animates. */
    private val shade = object : View(activity) {
        override fun hasOverlappingRendering() = false
    }
    private val overlay = FrameLayout(activity)
    /** The page's views and the focus and TalkBack settings they had before the sheet held them. */
    private val page = ArrayList<Triple<View, Int, Int>>()
    private var open = false

    init {
        shade.setBackgroundColor(scrim)
        overlay.tag = SHEET
        overlay.isClickable = true // a pointer can't reach the page either
        // Focused buttons lift 5% past their column's edges; let the lift show instead of cutting it.
        overlay.clipChildren = false
        (content as? ViewGroup)?.clipChildren = false
        overlay.addView(shade, FrameLayout.LayoutParams(MATCH, MATCH))
        overlay.addView(content, params)
    }

    fun open(focus: View, pane: CharSequence) {
        open = true
        ViewCompat.setAccessibilityPaneTitle(overlay, pane)
        root.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))
        // Focus comes in before the page is shut, so nothing is left focused behind the sheet.
        focus.requestFocus()
        // Shut, the page can't take focus even when asked (Leanback re-focuses its rows as they
        // load), and the remote's searches find only the sheet.
        for (i in 0 until root.childCount) {
            val view = root.getChildAt(i)
            if (view.tag == SHEET) continue // this one, or one still on its way out
            page += Triple(view, (view as? ViewGroup)?.descendantFocusability ?: 0, view.importantForAccessibility)
            (view as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        val window = activity.window
        window.callback = object : Window.Callback by keys {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                // Esc is a keyboard's Back (TvActivity does the same for the page).
                if (event.keyCode != KeyEvent.KEYCODE_BACK && event.keyCode != KeyEvent.KEYCODE_ESCAPE) return window.superDispatchKeyEvent(event)
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) close()
                return true
            }
        }
        shade.alpha = 0f
        content.alpha = 0f
        content.translationX = dx
        content.translationY = dy
        shade.animate().alpha(1f).setInterpolator(Motion.easeOut).setDuration(Motion.ENTER).start()
        content.animate().alpha(1f).translationX(0f).translationY(0f).setInterpolator(Motion.easeOut)
            .setDuration(Motion.ENTER).withLayer().start()
    }

    /** Hands the page back at once, then runs [then] while the sheet fades out. */
    fun close(then: (() -> Unit)? = null) {
        if (!open) return
        open = false
        activity.window.callback = keys
        for ((view, focus, access) in page) {
            (view as? ViewGroup)?.descendantFocusability = focus
            view.importantForAccessibility = access
        }
        overlay.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        // Back to what opened the sheet; if that is gone, to the page's first focusable.
        if (opener?.isAttachedToWindow != true || !opener.requestFocus()) overlay.findFocus()?.clearFocus()
        content.animate().alpha(0f).translationX(dx).translationY(dy).setInterpolator(Motion.easeOut)
            .setDuration(Motion.EXIT).withLayer().start()
        shade.animate().alpha(0f).setInterpolator(Motion.easeOut).setDuration(Motion.EXIT)
            .withEndAction { root.removeView(overlay) }.start()
        then?.invoke()
    }
}

/** The app's button, by its style name, so a change to the button system reaches the sheets too. */
private fun Activity.button(label: CharSequence) = Button(this, null, 0, R.style.Widget_App_Button).apply {
    text = label
    widenSpaces()
}

/** A choice as the player's panel draws one: its check, then its label; the white pill under focus. */
private fun Activity.choice(label: CharSequence, checked: Boolean): View = LinearLayout(this).apply {
    isFocusable = true
    gravity = Gravity.CENTER_VERTICAL
    setBackgroundResource(R.drawable.player_row)
    setPaddingRelative(px(12), 0, px(16), 0)
    contentDescription = label
    val ink = ContextCompat.getColorStateList(context, R.color.player_ink)
    addView(ImageView(context).apply {
        setImageResource(R.drawable.player_check)
        imageTintList = ink
        isDuplicateParentStateEnabled = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }, linear(px(18), px(18)).apply { marginEnd = px(10) })
    addView(text(latin(context, label, R.font.inter_regular), R.style.TextAppearance_App_Body).apply {
        gravity = Gravity.START
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        maxLines = 1
        setTextColor(ink)
        isDuplicateParentStateEnabled = true
    }, linear(0, WRAP, 1f))
    mark(this, checked)
}

/** The current choice: white, with its check (and "selected" to TalkBack). */
private fun mark(row: View, checked: Boolean) {
    row.isSelected = checked
    (row as ViewGroup).getChildAt(0).visibility = if (checked) View.VISIBLE else View.INVISIBLE
}

private fun Activity.text(value: CharSequence, style: Int) = TextView(this).apply {
    setTextAppearance(style)
    text = value
    gravity = Gravity.CENTER_HORIZONTAL
    ellipsize = TextUtils.TruncateAt.END
    widenSpaces()
}

private fun Activity.px(value: Int) = (value * resources.displayMetrics.density).toInt()

private fun linear(width: Int, height: Int, weight: Float = 0f) = LinearLayout.LayoutParams(width, height, weight)

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
private const val SHEET = "sheet"
/** Ink at 95% around a question's card: the page all but gone, and none of it brighter than the card. */
private const val SCRIM_ALERT = 0xF2141414.toInt()
/** A question card's inset, dp: its text and buttons 360dp across inside it. */
private const val CARD_PAD = 32
/** Ink at 70% beside a panel: the page stays legible as what the choice is about. */
private const val SCRIM_PANEL = 0xB3141414.toInt()
