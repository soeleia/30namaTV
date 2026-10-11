package com.doxigo.tv30

import android.content.Context
import android.annotation.SuppressLint
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.os.Build
import android.text.Layout
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewTreeObserver
import android.widget.TextView
import androidx.annotation.RequiresApi

@SuppressLint("WrongConstant") // Layout's API 23 constant is the same value as LineBreaker's API 29 alias.
internal fun TextView.balanceLines() {
    breakStrategy = if (Build.VERSION.SDK_INT >= 29) LineBreaker.BREAK_STRATEGY_BALANCED else Layout.BREAK_STRATEGY_BALANCED
}

// ---- Persian word spaces at a readable width. Yekan Bakh's space is 0.153 em (Bold 0.140) and
// ---- Peyda's 0.101–0.121 em, against 0.26–0.28 em in Vazir, Sahel, Samim and Shabnam, so «در نقش»
// ---- read «درنقش». Both licences forbid editing the fonts, so the width is added where text is laid
// ---- out: Paint.wordSpacing (API 29), which Minikin adds to every U+0020 and U+00A0 (never to the
// ---- ZWNJ), brings a narrow face's space to SPACE. Faces whose space is already wide (Inter's
// ---- 0.24–0.28 em, the system's) are left alone. Never letterSpacing: it pulls Persian's joins apart.
// ---- Below API 29 there is no wordSpacing, and the spaces stay as the fonts have them.

/** The word space every narrow face is brought to, in ems: Vazir's and Samim's. */
private const val SPACE = 0.27f

/** A face whose own space is under this many ems is widened. */
private const val NARROW = 0.2f

// ponytail: main thread only, as layout is. Keyed by Typeface; the framework caches those, so a handful.
private val extras = HashMap<Typeface?, Float>()

/** The ems [face]'s word space needs added. */
private fun extra(face: Typeface?): Float = extras.getOrPut(face) {
    val space = Paint().apply { typeface = face; textSize = 1000f }.measureText(" ") / 1000f
    if (space < NARROW) SPACE - space else 0f
}

/**
 * Gives this paint's word space its readable width for the face and size it has now; true if that
 * changed it. A span that switches face (latin()'s Face, Chip's label) calls it after, so a Inter
 * run keeps its own space rather than the widened one of the view's Persian face.
 */
fun Paint.fitSpaces(): Boolean {
    if (Build.VERSION.SDK_INT < 29) return false
    val wanted = extra(typeface) * textSize
    if (wordSpacing == wanted) return false
    wordSpacing = wanted
    return true
}

/**
 * Readable word spaces in this view, now and whenever its face changes. Every inflated TextView
 * gets it from [widenSpaces] on the activity's inflater; one built in code calls it once its text
 * appearance and size are set.
 */
fun TextView.widenSpaces() {
    if (Build.VERSION.SDK_INT < 29) return
    paint.fitSpaces()
    addOnLayoutChangeListener(Refit(paint.typeface))
}

/**
 * The face changed after [widenSpaces] (a presenter switching faces by script, a
 * setTextAppearance): refit for it at the size the view has then. A size change alone keeps the
 * old width: ResizingTextView and autosizing change size while measuring, and refitting them could
 * flip their choice back and forth. The layout just built has the old spaces, and a new one asked
 * for inside a layout pass can be lost (a RecyclerView mid-scroll swallows it), so the request
 * waits for the frame's pre-draw; that frame draws the text afresh at the view's width already.
 */
private class Refit(private var face: Typeface?) : View.OnLayoutChangeListener {
    override fun onLayoutChange(v: View, left: Int, top: Int, right: Int, bottom: Int, oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int) {
        val view = v as TextView
        if (view.paint.typeface === face) return
        face = view.paint.typeface
        if (!view.paint.fitSpaces()) return
        val observer = view.viewTreeObserver
        observer.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            private var done = false

            override fun onPreDraw(): Boolean {
                if (done) return true
                done = true
                // From the observer it is on now, should the view have changed windows since.
                (if (observer.isAlive) observer else view.viewTreeObserver).removeOnPreDrawListener(this)
                // The one public setter that drops the layout and asks for a new one whatever the value.
                view.hyphenationFrequency = view.hyphenationFrequency
                return true
            }
        })
    }
}

/**
 * Passes every TextView this inflater (and each clone of it: fragments', themed contexts', Leanback's
 * presenters') inflates through [widenSpaces]: the app's layouts and Leanback's row headers, card
 * captions, search and details. Other tags are left to the inflater. Call before the first inflation.
 */
fun LayoutInflater.widenSpaces() {
    if (Build.VERSION.SDK_INT >= 29 && factory2 == null) factory2 = SpacedViews(this)
}

@RequiresApi(29)
private class SpacedViews(private val inflater: LayoutInflater) : LayoutInflater.Factory2 {
    // Tag → whether it names a TextView. ponytail: main thread only, as inflation is here.
    private val texts = HashMap<String, Boolean>()

    override fun onCreateView(parent: View?, name: String, context: Context, attrs: AttributeSet): View? {
        // A bare tag is a platform widget, as the inflater resolves it ("TextView", "Button").
        val type = if ('.' in name) name else "android.widget.$name"
        val text = texts.getOrPut(name) {
            runCatching { TextView::class.java.isAssignableFrom(Class.forName(type, false, context.classLoader)) }.getOrDefault(false)
        }
        if (!text) return null
        return (inflater.createView(context, type, null, attrs) as? TextView)?.apply { widenSpaces() }
    }

    override fun onCreateView(name: String, context: Context, attrs: AttributeSet): View? = onCreateView(null, name, context, attrs)
}
