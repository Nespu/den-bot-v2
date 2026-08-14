package com.denbot

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View

class SelectorView(
    context: Context,
    private val onRegionChanged: (Int, Int, Int, Int) -> Unit
) : View(context) {

    private var left = 100f; private var top = 250f
    private var right = 700f; private var bottom = 850f
    private var dragging = -1
    private var lastX = 0f; private var lastY = 0f
    private val handleR = 40f

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4dde72"); strokeWidth = 3f; style = Paint.Style.STROKE
    }
    private val dimPaint = Paint().apply { color = Color.parseColor("#99000000"); style = Paint.Style.FILL }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4dde72"); style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 34f; textAlign = Paint.Align.CENTER
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#664dde72"); strokeWidth = 1.5f; style = Paint.Style.STROKE
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), top, dimPaint)
        canvas.drawRect(0f, top, left, bottom, dimPaint)
        canvas.drawRect(right, top, width.toFloat(), bottom, dimPaint)
        canvas.drawRect(0f, bottom, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawRect(left, top, right, bottom, borderPaint)

        // Draw 8x8 grid preview so user can align squares precisely
        val sqW = (right - left) / 8f
        val sqH = (bottom - top) / 8f
        for (i in 1..7) {
            canvas.drawLine(left + i * sqW, top, left + i * sqW, bottom, gridPaint)
            canvas.drawLine(left, top + i * sqH, right, top + i * sqH, gridPaint)
        }

        listOf(left to top, right to top, left to bottom, right to bottom).forEach { (x, y) ->
            canvas.drawCircle(x, y, handleR, handlePaint)
        }
        canvas.drawText("Encuadra el tablero — alinea con las casillas", width / 2f, top - 20f, labelPaint)
        onRegionChanged(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x; val y = e.y
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                dragging = when {
                    dist(x, y, left, top) < handleR * 2 -> 0
                    dist(x, y, right, top) < handleR * 2 -> 1
                    dist(x, y, left, bottom) < handleR * 2 -> 2
                    dist(x, y, right, bottom) < handleR * 2 -> 3
                    x in left..right && y in top..bottom -> 4
                    else -> -1
                }
                lastX = x; lastY = y
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = x - lastX; val dy = y - lastY
                when (dragging) {
                    0 -> { left += dx; top += dy }
                    1 -> { right += dx; top += dy }
                    2 -> { left += dx; bottom += dy }
                    3 -> { right += dx; bottom += dy }
                    4 -> { left += dx; top += dy; right += dx; bottom += dy }
                }
                lastX = x; lastY = y
                invalidate()
            }
            MotionEvent.ACTION_UP -> dragging = -1
        }
        return true
    }

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float) =
        Math.sqrt(((x1 - x2) * (x1 - x2) + (y1 - y2) * (y1 - y2)).toDouble()).toFloat()
}
