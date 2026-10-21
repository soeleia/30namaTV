package com.doxigo.tv30

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import coil3.load
import coil3.request.crossfade
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The official TV sign-in: the server hands out a code and a 30nama.com/auth URL, the user approves
 * it on their phone, and the TV polls until the code turns into a session token. No password ever
 * touches the TV.
 */
class LoginActivity : TvActivity() {
    /** The code on the plate, while it is alive; null once it is dead, asks for a fresh one. */
    private var code: String? = null
    /** When [code] came, on the uptime clock. */
    private var issued = 0L
    /**
     * The code [code] took the place of, still asked about (see [QrCode.asked]): our [QrCode.LIFETIME]
     * is a guess, and a phone that scanned it late may approve it after it left the plate.
     */
    private var previous: String? = null
    /** The white plate is up, with a code on it, live or not. */
    private var shown = false
    private var backdrop: Job? = null
    private var checking = false
    private var polling: Job? = null
    /** When the page last appeared or heard a key, on the uptime clock: the asking slows, then rests, from it. */
    private var heard = 0L
    /** Waiting out a slow pause, which a key cuts short. */
    private var slow = false
    /** Asking no more, the code down, until a key (see [rest]). */
    private var resting = false
    private lateinit var status: View
    private lateinit var check: Button
    private lateinit var retry: Button
    private lateinit var spinner: View
    private lateinit var failure: View
    /** The status light breathing while the TV waits for the phone. */
    private var breathing: ValueAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        findViewById<TextView>(R.id.wordmark).apply {
            text = wordmark()
            typeface = font(R.font.inter_bold)
        }
        // Latin headlines take the app's Latin face; Persian keeps the theme's.
        findViewById<TextView>(R.id.title).let { if (it.text.isLatin) it.typeface = font(R.font.inter_bold) }
        status = findViewById(R.id.status)
        check = findViewById(R.id.check)
        check.setOnClickListener { poll(now = true) }
        retry = findViewById(R.id.retry)
        retry.setOnClickListener { poll(now = true) }
        spinner = findViewById(R.id.qr_loading)
        failure = findViewById(R.id.qr_error)
        findViewById<Button>(R.id.guest).setOnClickListener {
            MainActivity.restart(this, guest = true)
            finish()
        }
        val steps = findViewById<LinearLayout>(R.id.steps)
        listOf(R.string.login_step_camera, R.string.login_step_scan, R.string.login_step_confirm).forEachIndexed { i, text ->
            val step = layoutInflater.inflate(R.layout.login_step, steps, false)
            step.findViewById<TextView>(R.id.step_number).text = (i + 1).toString().localDigits()
            step.findViewById<TextView>(R.id.step_text).setText(text)
            steps.addView(step)
        }
        // The art darkens toward the start edge, where the words are, whichever side that is.
        val rtl = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        findViewById<View>(R.id.shade).background = GradientDrawable(
            if (rtl) GradientDrawable.Orientation.RIGHT_LEFT else GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(getColor(R.color.scrim), getColor(R.color.scrim_mid), getColor(R.color.scrim_clear)),
        )
        // The column rises in line by line, the code a beat after it.
        val column = findViewById<ViewGroup>(R.id.column)
        rise(List(column.childCount) { column.getChildAt(it) } + findViewById<View>(R.id.code))
        check.requestFocus()
    }

    // The page asks only while it is seen; back in front, a code that may have died meanwhile
    // makes way for a fresh one. A sign-in that has landed (the page only opens without a
    // session) sees its welcome through either way.
    override fun onStart() {
        super.onStart()
        if (!Api.loggedIn) wake()
    }

    override fun onStop() {
        if (!Api.loggedIn) {
            polling?.cancel()
            code = null
            showChecking(false)
        }
        super.onStop()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val leaving = event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE
        if (event.action != KeyEvent.ACTION_DOWN || leaving || Api.loggedIn) return super.dispatchKeyEvent(event)
        // At rest a key's one job is a fresh code: it neither presses nor moves anything.
        if (resting) {
            wake()
            return true
        }
        heard = SystemClock.elapsedRealtime()
        if (slow) poll(now = false) // the quick asks again, from now
        return super.dispatchKeyEvent(event)
    }

    /** In front again, or a key at rest: the quick asks from now, on a fresh code if the last one went. */
    private fun wake() {
        resting = false
        heard = SystemClock.elapsedRealtime()
        findViewById<View>(R.id.qr_rest).reveal(false)
        poll(now = true)
    }

    /**
     * Asks on [QrCode.pause]'s schedule: as often as the official app for a while after the page
     * appears or hears a key, then less often, then not at all (see [rest]), so a TV left on the
     * sign-in never keeps asking. A dead code gives way to a fresh one first, so neither the
     * asking nor «بررسی دوباره» ever spends a turn on one. The official app stops after a minute
     * and leaves it to the button; its codes then die under a TV that still shows them.
     */
    private fun poll(now: Boolean) {
        if (Api.loggedIn) return // a press during the welcome would cancel the way home
        polling?.cancel()
        slow = false
        polling = lifecycleScope.launch {
            var wait = !now
            while (true) {
                if (wait) {
                    val pause = QrCode.pause(SystemClock.elapsedRealtime() - heard) ?: return@launch rest()
                    slow = pause > QrCode.FAST
                    delay(QrCode.within(pause, code, issued, SystemClock.elapsedRealtime()))
                    slow = false
                }
                wait = true
                if (!QrCode.alive(code, issued, SystemClock.elapsedRealtime()) && !loadCode()) return@launch
                showChecking(true)
                if (signIn()) return@launch
            }
        }
    }

    /**
     * The code onto its plate: a spinner on the dark plate while the first comes, why not if it
     * doesn't. A fresh code takes a dead one's place on the white plate, the old fading as it
     * is asked for and the new one rising in.
     */
    private suspend fun loadCode(): Boolean {
        previous = code // a dead one is null already
        code = null
        if (retry.hasFocus()) check.requestFocus() // the retry is about to go
        failure.visibility = View.GONE
        val qr = findViewById<ImageView>(R.id.qr)
        val url = findViewById<TextView>(R.id.url)
        if (shown) for (v in listOf(qr, url)) v.animate().alpha(0f).setInterpolator(Motion.easeOut).setDuration(Motion.EXIT).start()
        else spinner.visibility = View.VISIBLE
        if (!shown && !Api.initialize()) return failed(getString(R.string.no_internet))
        if (backdrop == null) backdrop = lifecycleScope.launch { showBackdrop() }
        val answer = runCatching { Api.qrCode() as JSONObject }.getOrElse { return failed(errorText(it)) }
        val code = answer.str("code") ?: return failed(getString(R.string.error_server))
        // The Tizen client's fallback, should the server leave the address out.
        val address = answer.str("url") ?: "https://30nama.com/auth/$code"
        this.code = code
        issued = SystemClock.elapsedRealtime()
        url.text = address.removePrefix("https://")
        qr.setImageBitmap(qrBitmap(address, resources.getDimensionPixelSize(R.dimen.login_qr)))
        if (shown) {
            val from = if (Motion.reduced) 1f else .96f
            qr.scaleX = from
            qr.scaleY = from
            for (v in listOf(qr, url)) v.animate().alpha(1f).scaleX(1f).scaleY(1f).setInterpolator(Motion.easeOut).setDuration(Motion.ENTER).start()
            return true
        }
        // The white plate comes up over the dark one, the address with it (whole again, should a
        // failed swap have left them faded).
        shown = true
        for (v in listOf(qr, url)) v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(0).start()
        spinner.visibility = View.GONE
        findViewById<View>(R.id.qr_frame).animate().alpha(1f).setInterpolator(Motion.easeOut).setDuration(Motion.ENTER).start()
        findViewById<View>(R.id.address).reveal(true)
        return true
    }

    /** Where the code would be: what went wrong, in words, and a retry under the remote. */
    private suspend fun failed(reason: String): Boolean {
        // A superseded attempt (another press, or the page closing) reads as a failure to the
        // runCatching under it; it leaves the plate to the attempt that replaced it.
        currentCoroutineContext().ensureActive()
        showChecking(false)
        spinner.visibility = View.GONE
        plateDown()
        findViewById<TextView>(R.id.qr_error_text).text = reason
        failure.reveal(true)
        retry.requestFocus()
        return false
    }

    /** Long without a key, the code steps down and the asking stops, the dark plate saying a key brings a fresh one. */
    private fun rest() {
        resting = true
        code = null
        showChecking(false)
        plateDown()
        findViewById<View>(R.id.qr_rest).reveal(true)
    }

    /** A dead code never stays up to be scanned: the white plate fades to the dark one, the address with it (its place held). */
    private fun plateDown() {
        if (!shown) return
        shown = false
        findViewById<View>(R.id.qr_frame).animate().alpha(0f).setInterpolator(Motion.easeOut).setDuration(Motion.EXIT).start()
        findViewById<View>(R.id.address).run {
            animate().alpha(0f).setInterpolator(Motion.easeOut).setDuration(Motion.EXIT).withEndAction { visibility = View.INVISIBLE }.start()
        }
    }

    /** As on the Tizen client, the wall behind the sign-in is what 30nama features right now. */
    private suspend fun showBackdrop() {
        val art = runCatching { Api.home() }.getOrNull()?.obj("hero_header")?.optJSONArray("posts").objects().firstNotNullOfOrNull { it.cover }
        findViewById<ImageView>(R.id.backdrop).load(art ?: return) { crossfade(Motion.BACKDROP); size(1280, 720) }
    }

    private suspend fun signIn(): Boolean {
        // The code on the plate, then the one it replaced: an approval of either signs in.
        val token = QrCode.asked(code, previous, issued, SystemClock.elapsedRealtime()).firstNotNullOfOrNull { asked ->
            val response = runCatching { Api.qrLogin(asked) }.getOrNull() ?: return@firstNotNullOfOrNull null
            if (QrCode.expired(response)) {
                if (asked == code) code = null // the next turn asks for a fresh one
                else previous = null // it has had its say
            }
            response.takeIf { it.optBoolean("success") }?.str("result", "token")
        } ?: return false
        Api.token = token
        runCatching { Api.user() }.getOrNull()?.takeIf { it.optBoolean("success") }?.let { Api.user = it.optJSONObject("result") }
        // The code turns into a check where the phone was pointed, held a moment, then home.
        showChecking(false)
        findViewById<View>(R.id.qr).animate().alpha(0f).setInterpolator(Motion.easeOut).setDuration(Motion.EXIT).start()
        findViewById<View>(R.id.done).apply {
            scaleX = if (Motion.reduced) 1f else .9f
            scaleY = if (Motion.reduced) 1f else .9f
            animate().alpha(1f).scaleX(1f).scaleY(1f).setInterpolator(Motion.easeOut).setDuration(Motion.ENTER).start()
        }
        delay(Motion.ENTER + WELCOME)
        MainActivity.restart(this)
        finish()
        return true
    }

    private fun showChecking(checking: Boolean) {
        if (checking == this.checking) return // the light keeps its breath
        this.checking = checking
        status.animate().alpha(if (checking) 1f else 0f).setInterpolator(Motion.easeOut)
            .setDuration(if (checking) Motion.ENTER else Motion.EXIT).start()
        breathing?.cancel()
        breathing = if (!checking) null else ObjectAnimator.ofFloat(findViewById<View>(R.id.status_dot), View.ALPHA, 1f, .3f).apply {
            duration = BREATH
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    override fun onDestroy() {
        breathing?.cancel() // an endless animator would outlive the page
        super.onDestroy()
    }

    private fun qrBitmap(text: String, size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 0))
        // Ink on the white plate it sits on: dark on light, so any phone camera reads it.
        val ink = getColor(R.color.ink)
        val plate = getColor(R.color.text)
        val pixels = IntArray(size * size) { if (matrix[it % size, it / size]) ink else plate }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565)
    }

    private companion object {
        /** Long enough to see the check before home takes over. */
        const val WELCOME = 400L
        /** One breath of the status light, in or out. */
        const val BREATH = 1100L
    }
}

/** A sign-in code's life on 30nama: how long it lasts, the answer that says it is over, and how often to ask after it. */
internal object QrCode {
    /** Between asks while the page is fresh, as the official app spaces them ... */
    const val FAST = 5_000L
    /** ... for this long after it appears or hears a key ... */
    const val FAST_FOR = 2 * 60_000L
    /** ... then this, ... */
    const val SLOW = 25_000L
    /** ... until this long without a key, when the page rests. */
    const val REST_AFTER = 10 * 60_000L

    /** The wait before the next ask, [idle] ms after the page appeared or last heard a key; null: rest. */
    fun pause(idle: Long): Long? = when {
        idle < FAST_FOR -> FAST
        idle < REST_AFTER -> SLOW
        else -> null
    }

    /** Unverified: rival clients' figure, not yet measured against 30nama. */
    const val LIFETIME = 60_000L

    /** Whether [code], given at [issued], can still be asked about at [now] (both on the uptime clock). */
    fun alive(code: String?, issued: Long, now: Long) = code != null && now - issued < LIFETIME

    /**
     * The codes to ask about at [now]: [code], then [previous], the one it replaced at [issued],
     * for one more [LIFETIME] after it left the plate (or until 30nama calls it [expired]).
     */
    fun asked(code: String?, previous: String?, issued: Long, now: Long) =
        listOfNotNull(code, previous.takeIf { alive(it, issued, now) })

    /** [pause], cut short where [code], given at [issued], dies first: a dead code goes when it dies, not a slow pause later. */
    fun within(pause: Long, code: String?, issued: Long, now: Long) =
        if (code == null) pause else minOf(pause, (issued + LIFETIME - now).coerceAtLeast(0))

    /** 30nama's answer to a code it no longer knows. Unverified: a guess at its wording, never yet seen. */
    fun expired(answer: JSONObject) = !answer.optBoolean("success") && answer.str("msg")?.contains("expire", ignoreCase = true) == true
}
