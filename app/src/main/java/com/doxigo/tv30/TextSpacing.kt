package com.doxigo.tv30

import android.content.Context
import android.annotation.SuppressLint
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.os.Build
import android.text.InputFilter
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.SpannedString
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.text.style.ReplacementSpan
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewTreeObserver
import android.widget.EditText
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
// ---- But Minikin adds wordSpacing only as it draws: the MeasuredText that StaticLayout breaks lines
// ---- and ellipsizes on copies the shaped words' advances without it (StyleRun::getMetrics, Android
// ---- 10 to 16). Every full line was broken for the font's space and drawn up to 50 px wider than
// ---- the view, its last word cut off at the end edge. So in a TextView the width is the text's: each
// ---- space carries a Space, which the line breaker measures widened by textScaleX (shaped into those
// ---- advances) and TextLine draws at that same advance. The paint keeps its own wordSpacing for what
// ---- measures or draws one line with it (Billboard's fit, the lists' labels, Chip).

/** The word space every narrow face is brought to, in ems: Vazir's and Samim's. */
private const val SPACE = 0.27f

/** A face whose own space is under this many ems is widened. */
private const val NARROW = 0.2f

// ponytail: main thread only, as layout is. Keyed by Typeface; the framework caches those, so a handful.
private val spaces = HashMap<Typeface?, Float>()

/** [face]'s own word space, in ems. */
private fun space(face: Typeface?): Float = spaces.getOrPut(face) {
    Paint().apply { typeface = face; textSize = 1000f }.measureText(" ") / 1000f
}

/** The ems [face]'s word space needs added. */
private fun extra(face: Typeface?): Float = space(face).let { if (it < NARROW) SPACE - it else 0f }

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
 * Readable word spaces in this view, now and whenever its face or size changes. Every inflated
 * TextView gets it from [widenSpaces] on the activity's inflater; one built in code calls it once
 * its text appearance and size are set. Every text it is given after passes through [Spaces], as
 * setText passes text through a view's filters; the one it has already, and its hint, go now.
 * An EditText keeps the paint's wordSpacing alone: its line never breaks, and a copy of what the
 * keyboard sends would lose the keyboard's composing spans.
 */
fun TextView.widenSpaces() {
    if (Build.VERSION.SDK_INT < 29 || Spaces in filters) return
    paint.fitSpaces()
    addOnLayoutChangeListener(Refit(paint.typeface))
    if (this is EditText) return
    filters += Spaces
    if (text.any { it.isWordSpace }) text = text
    hint?.let { if (it.any { c -> c.isWordSpace }) hint = Spaces.filter(it, 0, it.length, SpannedString(""), 0, 0) }
}

private val Char.isWordSpace get() = this == ' ' || this == '\u00A0'

/**
 * Gives each word space of a text a [Space], leaving the text and its own spans as they are. Not
 * one a ReplacementSpan draws (a Chip's «زیرنویس فارسی», a Gap): a Space would cut it in three.
 */
@RequiresApi(29)
private object Spaces : InputFilter {
    override fun filter(source: CharSequence, start: Int, end: Int, dest: Spanned, dstart: Int, dend: Int): CharSequence? {
        if ((start until end).none { source[it].isWordSpace }) return null
        return SpannableString(source.subSequence(start, end)).apply {
            getSpans(0, length, Space::class.java).forEach(::removeSpan) // a text given again keeps one each
            for (i in indices) {
                if (this[i].isWordSpace && getSpans(i, i + 1, ReplacementSpan::class.java).isEmpty()) setSpan(Space(), i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }
}

/**
 * One word space at its readable width for the face its run has by then (a Face span, set before
 * it, makes it Inter's), in ems, so a size or face set later needs nothing but the new layout that
 * setting it brings. The line breaker measures it scaled. TextLine, which draws it (and measures
 * lines for getLineMax and wrap_content), takes the draw state, where the same advance is
 * wordSpacing: Minikin adds that to U+0020 and U+00A0 only, so the ellipsis StaticLayout writes
 * over a space at the cut keeps its own width rather than being stretched past the end edge.
 */
@RequiresApi(29)
private class Space : MetricAffectingSpan() {
    // The face and size [scale] and [extra] are for: a run's paint is fresh each time, and these change rarely.
    private var face: Typeface? = null
    private var size = 0f
    private var scale = 1f
    private var extra = 0f

    override fun updateMeasureState(paint: TextPaint) {
        fit(paint)
        paint.textScaleX = scale
        paint.wordSpacing = 0f // the measures made in Java (TextUtils.ellipsize's) would add it on top
    }

    override fun updateDrawState(paint: TextPaint) {
        fit(paint)
        paint.wordSpacing = extra // in place of the view's, so the two agree to the pixel's fraction
    }

    private fun fit(paint: TextPaint) {
        if (paint.typeface === face && paint.textSize == size) return
        face = paint.typeface
        size = paint.textSize
        val space = space(face)
        scale = if (space < NARROW) SPACE / space else 1f
        extra = if (scale == 1f) 0f else advance(paint, scale) - advance(paint, 1f)
    }
}

// ponytail: main thread only, as layout is.
private val probe = TextPaint()

/** One space's advance in [paint] at [scale], as Minikin shapes and rounds it for both measures. */
@RequiresApi(29)
private fun advance(paint: TextPaint, scale: Float): Float {
    probe.set(paint)
    probe.textScaleX = scale
    probe.wordSpacing = 0f
    return probe.getRunAdvance(" ", 0, 1, 0, 1, false, 1)
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
