package com.doxigo.tv30

import android.content.Context
import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat

/** A single rounded subtitle box around centered lines, with an outline when the box is off. */
@SuppressLint("AppCompatCustomView") // The Leanback theme uses native TextView attributes.
internal class CueView(context: Context) : TextView(context) {
    var boxColor = Color.TRANSPARENT
        set(value) {
            field = value
            invalidate()
        }
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()

    init {
        gravity = Gravity.CENTER_HORIZONTAL
        textAlignment = View.TEXT_ALIGNMENT_CENTER
        includeFontPadding = false
        setLineSpacing(0f, 0.9f)
        balanceLines()
        hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
        typeface = ResourcesCompat.getFont(context, R.font.vazirmatn_medium)
        widenSpaces()
    }

    override fun onDraw(canvas: Canvas) {
        val layout = layout
        val em = textSize
        canvas.save()
        canvas.translate(totalPaddingLeft.toFloat(), totalPaddingTop.toFloat())
        if (Color.alpha(boxColor) > 0) {
            box.set(layout.width.toFloat(), TRIM * em, 0f, layout.height.toFloat())
            for (i in 0 until layout.lineCount) {
                box.left = minOf(box.left, layout.getLineLeft(i))
                box.right = maxOf(box.right, layout.getLineRight(i))
            }
            box.inset(-PAD * em, 0f)
            boxPaint.color = boxColor
            canvas.drawRoundRect(box, RADIUS * em, RADIUS * em, boxPaint)
        } else {
            val paint = paint
            paint.style = Paint.Style.STROKE
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeWidth = 0.12f * em
            paint.color = Color.BLACK
            paint.setShadowLayer(0.1f * em, 0f, 0.03f * em, Color.BLACK)
            layout.draw(canvas)
            paint.clearShadowLayer()
            paint.style = Paint.Style.FILL
        }
        canvas.restore()
        super.onDraw(canvas)
    }

    companion object {
        const val PAD = 0.35f
        const val RADIUS = 0.12f
        const val TRIM = 0.06f
    }
}
