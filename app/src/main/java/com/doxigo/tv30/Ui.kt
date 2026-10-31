package com.doxigo.tv30

import android.app.Activity
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.io.InterruptedIOException
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
        setTextAppearance(R.style.TextAppearance_App_Body)
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
