package com.example.soilmonitor

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import kotlin.math.sin
import kotlin.random.Random

class WaveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    var progress: Float = 0.5f  // from 0f to 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    private val wavePaint1 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0097A7")
        style = Paint.Style.FILL
        alpha = 180
    }

    private val wavePaint2 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00ACC1")
        style = Paint.Style.FILL
        alpha = 100
    }

    // Unique initial phases for each instance
    private var phase = Random.nextFloat() * (2 * Math.PI).toFloat()
    private var phase2 = Random.nextFloat() * (2 * Math.PI).toFloat()

    // Base wave parameters
    private val baseSpeed1 = 0.03f
    private val baseSpeed2 = 0.015f
    private val baseHeight = 20f       // base amplitude
    private val baseFrequency1 = 2f
    private val baseFrequency2 = 1.2f

    // Instance random multipliers for speed and amplitude
    private val speedFactor1 = 0.8f + Random.nextFloat() * 0.4f
    private val speedFactor2 = 0.8f + Random.nextFloat() * 0.4f
    private val heightFactor1 = 0.8f + Random.nextFloat() * 0.4f
    private val heightFactor2 = 0.8f + Random.nextFloat() * 0.4f

    init {
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val baseY = h * (1f - progress)

        // Modulate amplitude over time, then apply instance height factor
        val dynamicHeight1 = baseHeight * heightFactor1 * (0.8f + 0.4f * sin(phase * 0.5f))
        val dynamicHeight2 = baseHeight * heightFactor2 * (0.8f + 0.4f * sin(phase2 * 0.7f))

        // First wave path
        val path1 = Path().apply {
            moveTo(0f, h)
            lineTo(0f, baseY)
            var x = 0f
            while (x <= w) {
                val y = baseY + sin((x / w * Math.PI * 2 * baseFrequency1 + phase).toFloat()) * dynamicHeight1
                lineTo(x, y)
                x += 1f
            }
            lineTo(w, h)
            close()
        }

        // Second overlapping wave path
        val path2 = Path().apply {
            moveTo(0f, h)
            lineTo(0f, baseY)
            var x = 0f
            while (x <= w) {
                val y = baseY + sin((x / w * Math.PI * 2 * baseFrequency2 + phase2).toFloat()) * dynamicHeight2
                lineTo(x, y)
                x += 1f
            }
            lineTo(w, h)
            close()
        }

        // Draw waves
        canvas.drawPath(path1, wavePaint1)
        canvas.drawPath(path2, wavePaint2)

        // Advance phases by speed * instance factor
        phase += baseSpeed1 * speedFactor1
        phase2 += baseSpeed2 * speedFactor2
        postInvalidateOnAnimation()
    }

    /** Tint both waves from a single base color. */
    fun setWaveColor(baseColor: Int) {
        wavePaint1.color = baseColor
        wavePaint2.color = ColorUtils.setAlphaComponent(baseColor, wavePaint2.alpha)
        invalidate()
    }

    /** Full control over both wave layer colors. */
    fun setWaveColors(primary: Int, secondary: Int) {
        wavePaint1.color = primary
        wavePaint2.color = secondary
        invalidate()
    }
}
