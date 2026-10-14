package com.doxigo.tv30

import android.animation.LayoutTransition
import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.LocaleList
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import coil3.load
import coil3.request.crossfade
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale

// ---- Sheets: the app's own modal surfaces, in place of the system's grey AlertDialog. ----
//
// Call them on the activity (from a fragment, requireActivity()):
//
//   Activity.confirm(title, question, action, art: String? = null, secondary: CharSequence? = null,
//                    onSecondary: (() -> Unit)? = null, actionIcon: Int = 0, secondaryIcon: Int = 0,
//                    onConfirm: () -> Unit)
//     requireActivity().confirm(title.englishName, getString(R.string.continue_remove_question),
//         getString(R.string.continue_remove), art = title.poster,
//         secondary = getString(R.string.continue_info), onSecondary = { openDetails(title) },
//         actionIcon = R.drawable.settings_ic_clear, secondaryIcon = R.drawable.promo_info) { remove(title) }
//     Asks before a step the viewer can't take back, as the Apple TV app does: the page dims and
//     a card rises in the middle with the poster ([art], a URL; left out when null), the name
//     ([title], its face by its script), the [question] and two stacked buttons. [action] names
//     the step by its verb («حذف از ادامهٔ تماشا», never «تأیید»); under it, Cancel, or the safe
//     [secondary] step in its place (Back already cancels), holds the remote at first, so a stray
//     OK changes nothing. Back and Esc cancel. onConfirm runs only for the action, onSecondary
//     only for [secondary]. With icons, the two read as quickActions' buttons: icon then label.
//
//   Activity.choose(title, options: List<CharSequence>, current: Int, onChoose: (Int) -> Unit)
//     requireActivity().choose(getString(R.string.sort_by), SORTS.map { getString(it.second) }, sort) { picked ->
//         if (picked != sort) { sort = picked; pager.reset() }
//     }
//     One of a short list (up to about nine), from a panel at the end edge as the player's
//     settings come in: the current one checked and under the remote. OK applies and closes;
//     onChoose gets the index of any pick, the current one too. Back and Esc close without it.
//
//   Activity.ask(title, value: String, link: String,
//                onSave: (key: String, answer: (note: CharSequence?, works: Boolean?) -> Unit) -> Unit)
//     ask(getString(s.name), Settings.savedKey(s.id), s.link) { key, answer -> save(s, key, answer) }
//     An API key, the viewer's from [link], three ways in, on confirm's card. A QR code and its
//     address open a page this TV serves its home network while the sheet is open (KeyForm): it
//     links to [link] and sends the key back, saved as Save saves it; with no home network, the
//     code is [link] itself. OK on the field, under the remote with [value] in it, asks for the
//     TV's keyboard, which the Google TV app on a phone can type or paste into. Paste, beside it,
//     takes the TV's clipboard. Save (or the keyboard's Done) hands onSave the text as it stands;
//     Clear, there only when [value] is not empty, hands it "". The sheet stays, «در حال بررسی…»
//     and a spinner under the field, until onSave calls answer once: with a null note (nothing
//     to try) it closes; with works true it says the note and closes 1.2 s later; false (refused,
//     the note red) or null (out of reach) say it and leave the remote on the field to fix or
//     send again. Back and Esc close without either, the check going on behind.
//
//   FragmentActivity.quickActions(title: JSONObject)
//     (context as? FragmentActivity)?.quickActions(t) // toCard's default hold, Cards.kt
//     What a title's poster can do without its page, as Apple TV's held select offers: confirm's
//     card with the poster at its start, and beside it the name, the billboard's facts, then
//     «اطلاعات بیشتر», a step in or out of each list, and «تریلر» once Trailer finds one (it
//     fades in under the rest). A list flips in place, through the title page's own path
//     (Library.toggle), and the card stays for the next; the others close it and go. A guest,
//     who has no lists, gets one step to sign in in their place. Back and Esc close.
//
// All four are views laid over the page in android.R.id.content, not Dialogs, so the remote, focus,
// RTL and the faces behave as everywhere else. While one is open it holds the remote: nothing
// beneath can take focus or TalkBack's attention, the window's keys reach it before the
// activity's own handling, and on closing the remote goes back to whatever opened it.

fun Activity.confirm(
    title: CharSequence, question: CharSequence, action: CharSequence, art: String? = null,
    secondary: CharSequence? = null, onSecondary: (() -> Unit)? = null,
    @DrawableRes actionIcon: Int = 0, @DrawableRes secondaryIcon: Int = 0, onConfirm: () -> Unit,
) {
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
        clipToPadding = false // the focused button's lift reaches into the padding; clipped, its ends went flat
    }
    // The poster the viewer just held OK on, so there is no doubt which title this is.
    if (art != null) column.addView(poster(art), linear(px(96), px(144)).apply { bottomMargin = px(24) })
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
    // With icons, the buttons read as quickActions' do: icon and label from the start edge.
    val act = if (actionIcon != 0) action(action, actionIcon) else button(action)
    val safe = (secondary ?: getString(R.string.sheet_cancel)).let { if (secondaryIcon != 0) action(it, secondaryIcon) else button(it) }
    column.addView(act, linear(MATCH, WRAP).apply { topMargin = px(32) })
    // 8dp between the 48dp targets is 12dp between the pills they draw, the buttons' own gap.
    column.addView(safe, linear(MATCH, WRAP).apply { topMargin = px(8) })

    val sheet = Sheet(this, column, FrameLayout.LayoutParams(px(360 + 2 * CARD_PAD), WRAP, Gravity.CENTER), SCRIM_ALERT, dy = 16 * dp)
    act.setOnClickListener { sheet.close(onConfirm) }
    safe.setOnClickListener { sheet.close(onSecondary) }
    sheet.open(safe, dotted(title.toString(), question.toString()))
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

fun Activity.ask(title: CharSequence, value: String, link: String, onSave: (key: String, answer: (note: CharSequence?, works: Boolean?) -> Unit) -> Unit) {
    val dp = resources.displayMetrics.density
    // A key from the phone goes in as a typed one would and is saved by Save; set once Save is.
    var send: (String) -> Unit = {}
    val phone = KeyForm.open(this, title, link) { send(it) }
    // The form down the start side, as confirm's column but read from its start edge.
    val form = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false // a focused button lifts past the form's edges
    }
    form.addView(text(title, R.style.TextAppearance_App_DialogTitle).apply {
        textSize = 26f
        typeface = font(if (title.isLatin) R.font.inter_bold else R.font.peyda_bold)
        start()
    }, linear(MATCH, WRAP))
    val question = getString(if (phone != null) R.string.sheet_key_phone else R.string.sheet_key_page)
    form.addView(text(latin(this, question, R.font.inter_regular), R.style.TextAppearance_App_Body).apply {
        maxLines = 3
        start()
    }, linear(MATCH, WRAP).apply { topMargin = px(8) })
    // A key is Latin whatever the UI's language: left to right, in Inter with its slashed zero and
    // tailed l, so a pasted key can be read back against the page it came from. No suggestions,
    // no capitals, and an English keyboard where the IME takes the hint.
    val field = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        if (Build.VERSION.SDK_INT >= 24) imeHintLocales = LocaleList(Locale.ENGLISH)
        background = ContextCompat.getDrawable(context, R.drawable.search_field)
        typeface = font(R.font.inter_regular)
        fontFeatureSettings = "'zero', 'cv05'"
        textSize = 18f
        setTextColor(getColor(R.color.text))
        textDirection = View.TEXT_DIRECTION_LTR
        textAlignment = View.TEXT_ALIGNMENT_TEXT_START
        gravity = Gravity.CENTER_VERTICAL
        setPaddingRelative(px(20), 0, px(20), 0)
        setText(value)
        setSelection(value.length)
    }
    // Paste beside the field, both the 44dp pill of the app's controls (the button's 48dp target
    // holds its pill 2dp in), so they line up.
    val paste = button(getString(R.string.sheet_paste))
    val row = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
        addView(field, linear(0, px(44), 1f))
        addView(paste, linear(WRAP, WRAP).apply { marginStart = px(12) })
    }
    form.addView(row, linear(MATCH, WRAP).apply { topMargin = px(24) })
    val note = text(latin(this, getString(R.string.sheet_keyboard_hint), R.font.inter_regular), R.style.TextAppearance_App_Caption).apply {
        textSize = 14f
        // Two lines held whatever it says, so Save stays put while a check comes and goes.
        minLines = 2
        maxLines = 2
        balanceLines()
        start()
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE // TalkBack reads a check as it lands
    }
    val quiet = note.textColors
    // The player panel's spinner, on the note's first line while a key is tried.
    val busy = ProgressBar(this).apply {
        indeterminateTintList = ContextCompat.getColorStateList(context, R.color.brand)
        visibility = View.GONE
    }
    form.addView(LinearLayout(this).apply {
        addView(busy, linear(px(16), px(16)).apply {
            marginEnd = px(10)
            topMargin = (note.lineHeight - px(16)) / 2
        })
        addView(note, linear(0, WRAP, 1f))
    }, linear(MATCH, WRAP).apply { topMargin = px(10) })
    val save = button(getString(R.string.sheet_save))
    form.addView(save, linear(MATCH, WRAP).apply { topMargin = px(24) })
    val clear = if (value.isEmpty()) null else button(getString(R.string.sheet_clear))
    clear?.let { form.addView(it, linear(MATCH, WRAP).apply { topMargin = px(8) }) }

    // The phone's page (else the key's own) on the white plate the sign-in code wears, its address
    // under it for a phone that can't scan: on one line at the caption's size, to be typed from
    // the sofa, the column as wide as it needs ("192.168.100.123:45678/…" is 20 em).
    val target = phone?.url ?: link
    val page = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        minimumWidth = px(QR + 56)
    }
    page.addView(ImageView(this).apply {
        setBackgroundResource(R.drawable.qr_plate)
        val pad = px(12)
        setPadding(pad, pad, pad, pad)
        setImageBitmap(qr(target, px(QR)))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }, linear(px(QR + 24), px(QR + 24)))
    page.addView(text(target.substringAfter("://").removePrefix("www."), R.style.TextAppearance_App_Caption).apply {
        typeface = font(R.font.inter_medium)
        textDirection = View.TEXT_DIRECTION_LTR
        isSingleLine = true
        ellipsize = null
    }, linear(WRAP, WRAP).apply { topMargin = px(10) })

    val card = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = GradientDrawable().apply {
            cornerRadius = px(20).toFloat()
            setColor(getColor(R.color.surface))
        }
        val pad = px(CARD_PAD)
        setPadding(pad, pad, pad, pad)
        clipToPadding = false // a focused button lifts into it, past the form's edge
        // Getting a key on the phone takes minutes; the screensaver would stop the page and the form with it.
        keepScreenOn = phone != null
    }
    card.addView(form, linear(px(360), WRAP))
    card.addView(page, linear(WRAP, WRAP).apply { marginStart = px(32) })

    val sheet = Sheet(this, card, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER), SCRIM_ALERT, dy = 16 * dp)
    val keys = getSystemService(InputMethodManager::class.java)
    /** The note line: [words] in the caption's grey, or in [ink] once they are a check's answer; the spinner while [trying]. */
    fun say(words: CharSequence, ink: Int = 0, trying: Boolean = false) {
        note.text = latin(this, words, R.font.inter_regular)
        if (ink == 0) note.setTextColor(quiet) else note.setTextColor(getColor(ink))
        busy.visibility = if (trying) View.VISIBLE else View.GONE
    }
    // A key being tried, or one that worked and is on its way out: Save, Clear and a phone's key wait.
    var waiting = false
    fun done(text: String) {
        if (waiting) return
        waiting = true
        // The keyboard would otherwise stay up over the note, and over the page the sheet hands back.
        keys.hideSoftInputFromWindow(field.windowToken, 0)
        say(getString(R.string.settings_key_checking), trying = true)
        onSave(text) { said, works ->
            when {
                said == null -> sheet.close()
                works == true -> {
                    say(said, R.color.text)
                    card.postDelayed({ sheet.close() }, KEY_WORKED)
                }
                else -> {
                    waiting = false
                    say(said, if (works == false) R.color.settings_danger else R.color.text)
                    field.requestFocus()
                }
            }
        }
    }
    save.setOnClickListener { done(field.text.toString()) }
    clear?.setOnClickListener { done("") }
    send = { key ->
        if (!waiting) {
            field.setText(key)
            save.performClick()
        }
    }
    paste.setOnClickListener {
        val copied = getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
        if (copied.isEmpty()) {
            say(getString(R.string.sheet_paste_empty))
            return@setOnClickListener
        }
        field.setText(copied)
        field.setSelection(copied.length)
        say(getString(R.string.sheet_keyboard_hint))
        save.requestFocus() // one more OK saves it
    }
    // OK on the field asks for the keyboard outright. TextView's own answer to OK, a plain request,
    // brought nothing up on a Sony on Android 9: forced, and if the keyboard wasn't serving the
    // field yet, its input started and asked again.
    @Suppress("DEPRECATION") // SHOW_FORCED, deprecated from Android 13; this is for the TVs before it
    fun keyboard() {
        field.requestFocus()
        if (!keys.showSoftInput(field, InputMethodManager.SHOW_FORCED)) {
            keys.restartInput(field)
            field.post { keys.showSoftInput(field, InputMethodManager.SHOW_FORCED) }
        }
    }
    field.setOnClickListener { keyboard() }
    // A remote whose OK sends Enter would reach the Done below and save; a keyboard's Enter still does.
    field.setOnKeyListener { _, code, event ->
        val ok = (code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) &&
            event.device?.keyboardType != InputDevice.KEYBOARD_TYPE_ALPHABETIC
        if (ok && event.action == KeyEvent.ACTION_UP) keyboard()
        ok
    }
    // Done, or Enter on a keyboard; closing twice is nothing (Sheet.close), so the key's down and up both may.
    field.setOnEditorActionListener { _, action, event ->
        if (action == EditorInfo.IME_ACTION_DONE || event?.keyCode == KeyEvent.KEYCODE_ENTER) save.performClick()
        true
    }
    // The phone's form lives only while the sheet does: it goes with it, and the sheet goes when the
    // page stops (Home), the keyboard first, which forced would stay up over whatever comes next.
    val life = (this as? LifecycleOwner)?.lifecycle
    val stop = LifecycleEventObserver { _, event ->
        if (event != Lifecycle.Event.ON_STOP) return@LifecycleEventObserver
        phone?.close()
        keys.hideSoftInputFromWindow(field.windowToken, 0)
        sheet.close()
    }
    life?.addObserver(stop)
    card.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) {
            phone?.close()
            life?.removeObserver(stop)
        }
    })
    sheet.open(field, dotted(title.toString(), question))
}

fun FragmentActivity.quickActions(title: JSONObject) {
    val dp = resources.displayMetrics.density
    val name = title.englishName
    // The name, its facts and the actions read down from the start edge, as ask's form.
    val column = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false // a focused action lifts past the column's edges
    }
    column.addView(text(name, R.style.TextAppearance_App_DialogTitle).apply {
        // As confirm sets the name: its face by its script.
        textSize = 26f
        typeface = font(if (name.isLatin) R.font.inter_bold else R.font.peyda_bold)
        letterSpacing = if (name.isLatin) -0.01f else 0f
        maxLines = 2
        balanceLines()
        start()
    }, linear(MATCH, WRAP))
    column.addView(text(title.meta(this), R.style.TextAppearance_App_Meta).apply {
        maxLines = 1 // as the billboard's
        start()
    }, linear(MATCH, WRAP).apply { topMargin = px(6) })

    // Up to five actions in a column: a pill's gap of 8dp rather than confirm's 12, so they hold
    // together as one list, the card fitting a 540dp screen with a two-line name.
    fun put(action: Button, gap: Int = 4) = action.also { column.addView(it, linear(MATCH, WRAP).apply { topMargin = px(gap) }) }
    val more = put(action(getString(R.string.promo_more), R.drawable.promo_info), gap = 24)
    // Each list as the title page's toggle draws it, the icon filled while the title is in it,
    // and named by the step a press takes. TalkBack hears the list's name, checked or not.
    val lists = if (!Api.loggedIn) emptyMap() else LISTS.mapValues { (group, words) ->
        put(action("")).apply {
            contentDescription = getString(words[0])
            ViewCompat.setAccessibilityDelegate(this, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.isCheckable = true
                    info.isChecked = Library.has(group, title.titleId)
                }
            })
        }
    }
    fun mark() = lists.forEach { (group, toggle) ->
        val (_, into, outOf, off, on) = LISTS.getValue(group)
        val has = Library.has(group, title.titleId)
        toggle.text = getString(if (has) outOf else into)
        toggle.icon(if (has) on else off)
    }
    mark()
    // A guest's lists need an account: the one step that gets them, where the lists would be.
    val signIn = if (Api.loggedIn) null else put(action(getString(R.string.quick_sign_in), R.drawable.nav_lists))
    val trailer = put(action(getString(R.string.trailer), R.drawable.act_trailer)).apply { visibility = View.GONE }
    // Trailer.find takes seconds for a title not looked up yet: the action fades in when it is
    // found, once the card has grown to it, rather than the card jumping.
    column.layoutTransition = LayoutTransition().apply {
        setDuration(LayoutTransition.CHANGE_APPEARING, Motion.MOVE)
        setInterpolator(LayoutTransition.CHANGE_APPEARING, Motion.easeOut)
        setStartDelay(LayoutTransition.APPEARING, Motion.MOVE)
        setDuration(LayoutTransition.APPEARING, Motion.ENTER)
    }

    val card = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = GradientDrawable().apply {
            cornerRadius = px(20).toFloat()
            setColor(getColor(R.color.surface))
        }
        val pad = px(CARD_PAD)
        setPadding(pad, pad, pad, pad)
        clipToPadding = false // the column's lifted actions reach into it
    }
    // The poster the viewer just held OK on, its billboard size: the card is about the title.
    card.addView(poster(title.poster), linear(px(160), px(240)))
    card.addView(column, linear(px(360), WRAP).apply { marginStart = px(32) })

    val sheet = Sheet(this, card, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER), SCRIM_ALERT, dy = 16 * dp)
    more.setOnClickListener { sheet.close { openDetails(title) } }
    for ((group, toggle) in lists) toggle.setOnClickListener {
        // Library flips its state before it suspends, so the label and icon change at once (and
        // TalkBack's click reads the new state). Api.scope: leaving the page straight after must
        // not cut the write short.
        Api.scope.launch(Dispatchers.Main.immediate) {
            runCatching { Library.toggle(group, title.titleId) }
                .onSuccess { (this@quickActions as? MainActivity)?.listsChanged() } // home's My List row
                .onFailure { toast(it) }
        }
        mark()
    }
    signIn?.setOnClickListener { sheet.close { startActivity(Intent(this, LoginActivity::class.java)) } }
    lifecycleScope.launch {
        val sources = Trailer.find(this@quickActions, title) ?: return@launch
        trailer.setOnClickListener { sheet.close { Trailer.play(this@quickActions, sources) } }
        trailer.visibility = View.VISIBLE // on a closed sheet, a view nobody sees
    }
    sheet.open(more, name)
}

/** Each list's name, the steps a press takes into and out of it, and its icon out and in: the title page's toggles. */
private val LISTS = mapOf(
    "favorite" to listOf(R.string.list_favorite, R.string.quick_favorite_add, R.string.quick_favorite_remove, R.drawable.act_favorite, R.drawable.act_favorite_on),
    "watchlist" to listOf(R.string.list_watchlist, R.string.quick_watchlist_add, R.string.quick_watchlist_remove, R.drawable.act_watchlist, R.drawable.act_watchlist_on),
    "notify" to listOf(R.string.list_notify, R.string.quick_notify_add, R.string.quick_notify_remove, R.drawable.act_notify, R.drawable.act_notify_on),
)

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

/** An action in a list of them: the app's icon button, its icon and label read from the start edge. */
private fun Activity.action(label: CharSequence, @DrawableRes icon: Int = 0) = Button(this, null, 0, R.style.Widget_App_Button_Icon).apply {
    text = label
    gravity = Gravity.START or Gravity.CENTER_VERTICAL
    widenSpaces()
    if (icon != 0) icon(icon)
}

/** [id] at the button's icon size and in its ink, whatever size and colour the drawable was drawn in (nav_lists is the rail's). */
private fun Button.icon(@DrawableRes id: Int) {
    val size = resources.getDimensionPixelSize(R.dimen.button_icon)
    setCompoundDrawablesRelative(ContextCompat.getDrawable(context, id)?.apply { setBounds(0, 0, size, size) }, null, null, null)
    TextViewCompat.setCompoundDrawableTintList(this, ContextCompat.getColorStateList(context, R.color.on_button))
}

/** A poster on a sheet's card, rounded as artwork is. */
private fun Activity.poster(art: String?) = ImageView(this).apply {
    scaleType = ImageView.ScaleType.CENTER_CROP
    background = GradientDrawable().apply {
        cornerRadius = resources.getDimension(R.dimen.corner)
        setColor(getColor(R.color.surface_high)) // a step up from the card while it loads
    }
    clipToOutline = true // to the background's corners
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    load(art) { crossfade(Motion.IMAGE) }
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

/** [text] as a QR code, ink on the white plate it sits on, as LoginActivity draws the sign-in code. */
private fun Activity.qr(text: String, size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 0))
    val ink = getColor(R.color.ink)
    val plate = getColor(R.color.text)
    return Bitmap.createBitmap(IntArray(size * size) { if (matrix[it % size, it / size]) ink else plate }, size, size, Bitmap.Config.RGB_565)
}

/** Read from the start edge, as a form is, rather than centred as a question is. */
private fun TextView.start() {
    gravity = Gravity.START
    textAlignment = View.TEXT_ALIGNMENT_VIEW_START
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
/** How long a key that works keeps its sheet, ms: «کلید کار می‌کند.» read, then the row's «•••• abcd». */
private const val KEY_WORKED = 1200L
/** The QR code's side, dp: phones read it from the sofa; its plate adds 12dp round it. */
private const val QR = 136
/** Ink at 70% beside a panel: the page stays legible as what the choice is about. */
private const val SCRIM_PANEL = 0xB3141414.toInt()
