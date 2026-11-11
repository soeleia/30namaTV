package com.doxigo.tv30

import android.app.Activity
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.content.res.TypedArray
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StyleRes
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.io.InterruptedIOException
import java.lang.reflect.Constructor
import java.net.SocketException
import java.net.UnknownHostException
import java.util.Locale
import javax.net.ssl.SSLException

fun Context.wordmark(): CharSequence = getString(R.string.app_name)

// ---- Activities follow the in-app language (فارسی by default, like the official app). ----

open class TvActivity : FragmentActivity() {
    override fun attachBaseContext(base: Context) {
        val locale = Locale.forLanguageTag(Api.language)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration).apply { setLocale(locale) }
        super.attachBaseContext(base.createConfigurationContext(config))
    }

    /** Reduce motion: pages change with a plain fade, the opening one no longer rising into place. */
    override fun onCreate(savedInstanceState: Bundle?) {
        layoutInflater.widenSpaces() // before anything is inflated: see TextSpacing.kt
        layoutInflater.styleFonts() // Android 6 to 7.1 only, where widenSpaces sets no factory: see below
        super.onCreate(savedInstanceState)
        if (Motion.reduced) window.setWindowAnimations(R.style.Animation_App_Screen_Fade)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val settle = Runnable {
        Motion.fast = false
        onSettled()
    }
    private var lastMove = 0L

    /**
     * Esc is a keyboard's Back (and the emulator's): hand it on as Back, through any override. The
     * on-screen keyboard only closes on a real Back it receives itself, so Esc closes it first.
     */
    @SuppressLint("RestrictedApi") // FragmentActivity inherits AndroidX's restricted key dispatcher.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
            val keyboard = Build.VERSION.SDK_INT >= 30 && window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
            if (!keyboard) return dispatchKeyEvent(event.asBack())
            // A real Back reaches the field before the keyboard (onKeyPreIme), which is how Leanback's
            // search learns to move focus to its results; give Esc the same path, then hide.
            if (event.action == KeyEvent.ACTION_UP) {
                currentFocus?.dispatchKeyEventPreIme(event.asBack())
                getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(window.decorView.windowToken, 0)
            }
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT) held(event)
        return super.dispatchKeyEvent(event)
    }

    /**
     * A held D-pad (repeats, or presses closer than Motion.REPEAT_GAP) switches on fast mode: the
     * single biggest thing for keeping up with the finger on a slow TV. It ends Motion.SETTLE
     * after the last press, and the page catches up once in [onSettled].
     */
    private fun held(event: KeyEvent) {
        if (event.repeatCount > 0 || event.eventTime - lastMove < Motion.REPEAT_GAP) Motion.fast = true
        lastMove = event.eventTime
        handler.removeCallbacks(settle)
        handler.postDelayed(settle, Motion.SETTLE)
    }

    /** The remote stopped racing: show what fast mode held back. */
    open fun onSettled() {}

    /** A closed screen's last words ([parting]), said by the page it left the viewer on. */
    override fun onResume() {
        super.onResume()
        if (this !is PlayerActivity) parting?.let { parting = null; toast(it) }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(settle)
        if (Motion.fast) settle.run()
    }
}

// ---- The pinned faces on Android 6.0 to 7.1 (API 23 to 25). type.xml and a few layouts name them
// ---- as android:fontFamily="@font/…", which the platform's TextView reads as a font only from
// ---- Android 8: before it the value is a family *name*, the font's file path names no family, and
// ---- every text styled that way fell back to the system's face (only faces set in code showed).
// ---- AppCompat's TextView is what usually fills this in, and the app has none. So below API 26
// ---- every inflated TextView is given the font its own attributes, its style or its text appearance
// ---- names ([styleFonts]), and [appearance] does the same for a text appearance set in code;
// ---- ResourcesCompat loads font files and font-family XML there. From API 26 none of this runs.

/** android:textAppearance and android:fontFamily, in the ascending order obtainStyledAttributes needs. */
private val TEXT_FONT = intArrayOf(android.R.attr.textAppearance, android.R.attr.fontFamily)
private val FONT = intArrayOf(android.R.attr.fontFamily)

/** The font [array] names at [index], if it is one of res/font's: a family name ("sans-serif") is the platform's own business. */
private fun Context.fontAt(array: TypedArray, index: Int): Typeface? {
    val id = array.getResourceId(index, 0)
    if (id == 0 || resources.getResourceTypeName(id) != "font") return null
    return runCatching { font(id) }.getOrNull()
}

/** The font the text appearance [style] names, if any. */
private fun Context.appearanceFont(style: Int): Typeface? {
    if (style == 0) return null
    val array = obtainStyledAttributes(style, FONT)
    try { return fontAt(array, 0) } finally { array.recycle() }
}

/** Sets [font], keeping the bold or italic the view worked out from its own textStyle. */
private fun TextView.styled(font: Typeface) = setTypeface(font, typeface?.style ?: Typeface.NORMAL)

/**
 * setTextAppearance for every Android the app runs on: below Android 8 the platform's own drops the
 * style's font (see above), and this puts it back. Use it wherever a view built in code takes one of
 * type.xml's appearances.
 */
fun TextView.appearance(@StyleRes style: Int) {
    setTextAppearance(style)
    if (Build.VERSION.SDK_INT < 26) context.appearanceFont(style)?.let { styled(it) }
}

/**
 * Below Android 8, gives every TextView this inflater (and each clone of it) inflates the font its
 * XML asks for. Call before the first inflation. TextSpacing's factory is for API 29 and up, so the
 * two are never on one inflater.
 */
fun LayoutInflater.styleFonts() {
    if (Build.VERSION.SDK_INT < 26 && factory2 == null) factory2 = StyledFonts()
}

private class StyledFonts : LayoutInflater.Factory2 {
    // Tag → its (Context, AttributeSet) constructor if it names a TextView, else null. Main thread only, as inflation is here.
    private val texts = HashMap<String, Constructor<out View>?>()

    /**
     * The view is made here rather than by the inflater: LayoutInflater.createView with a context came
     * with API 29, and before it a factory cannot ask the inflater for a view in the right (themed) one.
     */
    @SuppressLint("ResourceType") // TEXT_FONT is this file's own attribute array: its indexes are no styleable's.
    override fun onCreateView(parent: View?, name: String, context: Context, attrs: AttributeSet): View? {
        if (name !in texts) texts[name] = runCatching {
            // A bare tag is a platform widget, as the inflater resolves it ("TextView", "Button").
            Class.forName(if ('.' in name) name else "android.widget.$name", false, context.classLoader)
                .takeIf { TextView::class.java.isAssignableFrom(it) }?.asSubclass(View::class.java)
                ?.getConstructor(Context::class.java, AttributeSet::class.java)
        }.getOrNull()
        // Anything else, or a view that would not be made, is left to the inflater (and its own error).
        val view = runCatching { texts[name]?.newInstance(context, attrs) as? TextView }.getOrNull() ?: return null
        // The widget's default style is read as its constructor reads it; a subclass with a default of its own
        // (Leanback's row header) names that style in its layout, which is read first.
        val array = context.obtainStyledAttributes(attrs, TEXT_FONT, when (view) {
            is EditText -> android.R.attr.editTextStyle
            is Button -> android.R.attr.buttonStyle
            else -> android.R.attr.textViewStyle
        }, 0)
        // The view's own fontFamily wins over its text appearance's, as in TextView.
        val font = try {
            if (array.hasValue(1)) context.fontAt(array, 1) else context.appearanceFont(array.getResourceId(0, 0))
        } finally {
            array.recycle()
        }
        font?.let { view.styled(it) }
        return view
    }

    override fun onCreateView(name: String, context: Context, attrs: AttributeSet): View? = onCreateView(null, name, context, attrs)
}

/**
 * Focus for a page still being built, and the page's default whenever its window looks for one.
 * Until the window is attached, a view takes the device's default touch mode (Android 16 here; 9
 * asked the window), and where that is touch (a phone, a tablet, the emulator) the request is
 * refused: the window then left touch mode for the remote and focused the page's first view
 * instead, Settings opening on Playback rather than the row it was sent to. As the default, this
 * is the view it picks. A TV's default is the remote's, and it takes the request at once.
 */
fun View.takeFocus() {
    if (Build.VERSION.SDK_INT >= 26) isFocusedByDefault = true
    requestFocus()
}

private fun KeyEvent.asBack() =
    KeyEvent(downTime, eventTime, action, KeyEvent.KEYCODE_BACK, repeatCount, metaState, deviceId, scanCode, flags, source)

/** Transient messages in the app's own surface, rather than the system's grey toast. */
fun Context.toast(message: CharSequence?) {
    if (message.isNullOrBlank()) return
    val activity = this as? Activity ?: return Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    val root = activity.window.decorView as ViewGroup
    val dp = resources.displayMetrics.density
    val notice = root.findViewWithTag(NOTICE) ?: TextView(activity).apply {
        tag = NOTICE
        appearance(R.style.TextAppearance_App_Body)
        widenSpaces()
        background = ContextCompat.getDrawable(activity, R.drawable.notice_bg)
        setPadding((24 * dp).toInt(), (12 * dp).toInt(), (24 * dp).toInt(), (12 * dp).toInt())
        val params = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        params.bottomMargin = (56 * dp).toInt()
        root.addView(this, params)
    }
    notice.text = message
    notice.animate().cancel()
    notice.alpha = 0f
    notice.translationY = Motion.travel(8 * notice.resources.displayMetrics.density)
    notice.animate().alpha(1f).translationY(0f).setStartDelay(0).setInterpolator(Motion.easeOut).setDuration(Motion.ENTER).withEndAction {
        notice.animate().alpha(0f).setStartDelay(3_500).setInterpolator(Motion.easeOut).setDuration(Motion.EXIT).start()
    }.start()
}

private const val NOTICE = "notice"

/**
 * Words for whichever of the app's pages comes to the front next, from a screen that closes as it
 * says them, its own notice closing with it: the player with nothing to play. Every way into the
 * player has one of the app's pages beneath it.
 */
var parting: CharSequence? = null

fun Context.toast(error: Throwable) = toast(errorText(error))

/**
 * A failure in words: the server's refusal (Api words it), an unreachable network, or a server that
 * answered with something other than an answer. Never an exception's own text.
 */
fun Context.errorText(error: Throwable): String = when (error) {
    is Api.ApiError -> error.message ?: getString(R.string.error_server)
    is SocketException, is UnknownHostException, is InterruptedIOException, is SSLException -> getString(R.string.no_internet)
    else -> getString(R.string.error_server)
}
