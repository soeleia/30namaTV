package com.doxigo.tv30

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
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
    private var code: String? = null
    private var polling: Job? = null
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
        poll(now = true)
    }

    private fun poll(now: Boolean) {
        polling?.cancel()
        polling = lifecycleScope.launch {
            if (code == null && !loadCode()) return@launch
            showChecking(true)
            // The official app checks every 5 s for a minute, then waits for "check now".
            repeat(12) { i ->
                if (i > 0 || !now) delay(5_000)
                if (signIn()) return@launch
            }
            showChecking(false)
        }
    }

    /** The code onto its plate: a spinner on the dark plate while it comes, why not if it doesn't. */
    private suspend fun loadCode(): Boolean {
        if (retry.hasFocus()) check.requestFocus() // the retry is about to go
        failure.visibility = View.GONE
        spinner.visibility = View.VISIBLE
        if (!Api.initialize()) return failed(getString(R.string.no_internet))
        lifecycleScope.launch { showBackdrop() }
        val qr = runCatching { Api.qrCode() as JSONObject }.getOrElse { return failed(errorText(it)) }
        val code = qr.str("code") ?: return failed(getString(R.string.error_server))
        // The Tizen client's fallback, should the server leave the address out.
        val url = qr.str("url") ?: "https://30nama.com/auth/$code"
        this.code = code
        findViewById<TextView>(R.id.url).text = url.removePrefix("https://")
        findViewById<ImageView>(R.id.qr).setImageBitmap(qrBitmap(url, resources.getDimensionPixelSize(R.dimen.login_qr)))
        // The white plate comes up over the dark one, the address with it.
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
        spinner.visibility = View.GONE
        findViewById<TextView>(R.id.qr_error_text).text = reason
        failure.reveal(true)
        retry.requestFocus()
        return false
    }

    /** As on the Tizen client, the wall behind the sign-in is what 30nama features right now. */
    private suspend fun showBackdrop() {
        val art = runCatching { Api.home() }.getOrNull()?.obj("hero_header")?.optJSONArray("posts").objects().firstNotNullOfOrNull { it.cover }
        findViewById<ImageView>(R.id.backdrop).load(art ?: return) { crossfade(Motion.BACKDROP); size(1280, 720) }
    }

    private suspend fun signIn(): Boolean {
        val response = runCatching { Api.qrLogin(code!!) }.getOrNull() ?: return false
        val token = response.takeIf { it.optBoolean("success") }?.str("result", "token") ?: return false
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
