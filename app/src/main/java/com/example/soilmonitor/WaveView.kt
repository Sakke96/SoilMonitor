package com.example.soilmonitor

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.sin

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

    private val wavePaint1 = Paint().apply {
        color = Color.parseColor("#0097A7")
        style = Paint.Style.FILL
        isAntiAlias = true
        alpha = 180
    }

    private val wavePaint2 = Paint().apply {
        color = Color.parseColor("#00ACC1")
        style = Paint.Style.FILL
        isAntiAlias = true
        alpha = 100
    }

    private var phase = 0f
    private var phase2 = 0f

    private val waveSpeed = 0.03f      // slower = calmer
    private val waveSpeed2 = 0.015f    // second wave different speed
    private val waveHeight = 10f       // lower height = calmer
    private val waveFrequency = 2f     // higher frequency = smaller ripples
    private val waveFrequency2 = 1.2f  // second wave lower freq for randomness

    init {
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val width = width.toFloat()
        val height = height.toFloat()
        val waveY = height * (1f - progress)

        // First wave
        val path1 = Path().apply {
            moveTo(0f, height)
            lineTo(0f, waveY)

            var x = 0f
            while (x <= width) {
                val y = waveY + sin((x / width * 2 * Math.PI * waveFrequency + phase).toFloat()) * waveHeight
                lineTo(x, y)
                x += 1f
            }

            lineTo(width, height)
            close()
        }

        // Second overlapping wave
        val path2 = Path().apply {
            moveTo(0f, height)
            lineTo(0f, waveY)

            var x = 0f
            while (x <= width) {
                val y = waveY + sin((x / width * 2 * Math.PI * waveFrequency2 + phase2).toFloat()) * (waveHeight * 0.8f)
                lineTo(x, y)
                x += 1f
            }

            lineTo(width, height)
            close()
        }

        canvas.drawPath(path1, wavePaint1)
        canvas.drawPath(path2, wavePaint2)

        // Update animation phases
        phase += waveSpeed
        phase2 += waveSpeed2
        postInvalidateOnAnimation()
    }
}
