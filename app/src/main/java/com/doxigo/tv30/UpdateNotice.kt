package com.doxigo.tv30

import android.app.Activity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

/**
 * A new release downloaded and verified (AppUpdates' Ready), said on home where every friend
 * passes, not only in Settings: one pill in the top end corner (layout/update_notice), «نسخهٔ ۱.۴.۱
 * آماده است | نصب». It never takes the remote: Up from the hero's button (or from the first row,
 * with no hero) reaches it, OK installs, Down goes back. Back puts it away for that version
 * (AppUpdates.dismiss); the rail's dot on Settings and the Settings row still offer it.
 *
 * It lives in home's own layout ([root], the billboard under the rows), so it is never over the
 * player. It changes only while home is in front ([owner] started) and after home's entrance
 * ([arrived]), so a release found under the player or the intro waits to be seen arriving: it
 * comes down from the top a moment after home settles, the one move it makes.
 */
class UpdateNotice(private val root: ViewGroup, private val activity: Activity, private val owner: LifecycleOwner, private val down: () -> Unit) {
    private val view = LayoutInflater.from(root.context).inflate(R.layout.update_notice, root, false)
    private val text: TextView = view.findViewById(R.id.update_notice_text)
    private val dp = root.resources.displayMetrics.density
    /** The version on screen; null while there is none. */
    private var shown: String? = null

    /** Home has made its entrance: until then nothing shows. */
    var arrived = false
        set(value) {
            field = value
            if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) sync()
        }

    init {
        root.addView(view) // over the rows, which never reach the corner
        view.findViewById<TextView>(R.id.update_notice_action).let {
            it.typeface = activity.font(if (it.text.isLatin) R.font.inter_bold else R.font.vazirmatn_bold)
        }
        view.setOnClickListener { AppUpdates.openInstall(activity) }
        // The corner has nothing further up or to either side: those stay here, and only Down leaves.
        view.setOnKeyListener { _, code, event ->
            when (code) {
                KeyEvent.KEYCODE_DPAD_DOWN -> true.also { if (event.action == KeyEvent.ACTION_DOWN) down() }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> true
                else -> false
            }
        }
        owner.lifecycleScope.launch { owner.repeatOnLifecycle(Lifecycle.State.STARTED) { AppUpdates.state.collect { sync() } } }
    }

    /** Shows, hides or rewrites the notice for AppUpdates' state now. */
    private fun sync() {
        val version = if (arrived) AppUpdates.notice() else null
        if (version == shown) return
        val was = shown
        shown = version
        if (version == null) return leave()
        text.text = activity.getString(R.string.update_notice, version.localDigits())
        view.contentDescription = activity.getString(R.string.update_notice_spoken, version.localDigits())
        if (was != null) return // a newer version while one was up: the words change, the pill stays
        view.isFocusable = true
        view.animate().cancel()
        view.visibility = View.VISIBLE
        view.alpha = 0f
        view.translationY = -Motion.travel(12 * dp)
        view.animate().alpha(1f).translationY(0f).setStartDelay(SETTLE).setInterpolator(Motion.emph)
            .setDuration(Motion.PAGE).start()
    }

    /** Up from below: the remote comes here, if there is a notice. */
    fun focus(): Boolean = shown != null && view.requestFocus()

    /** Back on the notice: away for this version. Both halves of the press are kept from the rail. */
    fun back(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_BACK || !view.hasFocus()) return false
        if (event.action == KeyEvent.ACTION_UP) shown?.let {
            AppUpdates.dismiss(it)
            sync()
        }
        return true
    }

    private fun leave() {
        if (view.hasFocus()) down()
        view.isFocusable = false
        view.animate().cancel()
        view.animate().alpha(0f).translationY(-Motion.travel(8 * dp)).setStartDelay(0).setInterpolator(Motion.easeOut)
            .setDuration(Motion.EXIT).withEndAction { view.visibility = View.GONE }.start()
    }

    private companion object {
        /** After home's rows have risen (Motion.PAGE), so the two moves read one after the other. */
        const val SETTLE = 800L
    }
}
