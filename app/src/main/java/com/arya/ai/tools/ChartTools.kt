package com.arya.ai.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import java.io.File
import java.io.FileOutputStream

/**
 * Simple on-device data visualization — Arya's equivalent of a chart-display tool, drawn by
 * hand with plain [android.graphics.Canvas] so it needs no new charting library dependency.
 * Deliberately basic (bar/line only, one series) — the point is Arya being able to turn a
 * handful of numbers into a picture on request ("mera weekly expense chart bana do"), not a
 * full analytics dashboard.
 */
object ChartTools {

    private const val WIDTH = 1080
    private const val HEIGHT = 720
    private const val PADDING = 100f

    /**
     * Renders [values] (aligned 1:1 with [labels]) as a bar or line chart PNG, saved into the
     * app's Pictures/Arya folder (same convention as [ImageGenTools.saveToGallery]) so it
     * shows up in the same place as generated images. Returns null on any failure (bad
     * input, draw error) — caller should show a clear error, same pattern as [ImageGenTools].
     */
    fun generateChart(
        context: Context,
        title: String,
        labels: List<String>,
        values: List<Double>,
        type: String
    ): File? {
        if (labels.isEmpty() || values.isEmpty() || labels.size != values.size) return null
        return try {
            val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)

            val titlePaint = Paint().apply {
                color = Color.BLACK
                textSize = 42f
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(title.ifBlank { "Chart" }, WIDTH / 2f, 60f, titlePaint)

            val chartTop = 100f
            val chartBottom = HEIGHT - PADDING
            val chartLeft = PADDING
            val chartRight = WIDTH - 40f
            val chartHeight = chartBottom - chartTop
            val chartWidth = chartRight - chartLeft

            val maxVal = (values.maxOrNull() ?: 1.0).coerceAtLeast(0.0001)
            val minVal = (values.minOrNull() ?: 0.0).coerceAtMost(0.0)
            val range = (maxVal - minVal).coerceAtLeast(0.0001)

            val axisPaint = Paint().apply { color = Color.DKGRAY; strokeWidth = 3f }
            canvas.drawLine(chartLeft, chartTop, chartLeft, chartBottom, axisPaint)
            canvas.drawLine(chartLeft, chartBottom, chartRight, chartBottom, axisPaint)

            val labelPaint = Paint().apply {
                color = Color.DKGRAY
                textSize = 26f
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
            }
            val valuePaint = Paint().apply {
                color = Color.BLACK
                textSize = 24f
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
            }

            val barColors = intArrayOf(
                Color.parseColor("#5B8DEF"), Color.parseColor("#FF7D7D"), Color.parseColor("#5EDCA6"),
                Color.parseColor("#FFB86B"), Color.parseColor("#B98EFF"), Color.parseColor("#4EC5D9")
            )

            if (type.equals("line", ignoreCase = true)) {
                val linePaint = Paint().apply {
                    color = Color.parseColor("#5B8DEF")
                    strokeWidth = 6f
                    isAntiAlias = true
                    style = Paint.Style.STROKE
                }
                val pointPaint = Paint().apply { color = Color.parseColor("#3A6BC9"); isAntiAlias = true }
                val stepX = chartWidth / (values.size - 1).coerceAtLeast(1)
                var prevX = 0f
                var prevY = 0f
                values.forEachIndexed { i, v ->
                    val x = chartLeft + stepX * i
                    val y = chartBottom - ((v - minVal) / range * chartHeight).toFloat()
                    if (i > 0) canvas.drawLine(prevX, prevY, x, y, linePaint)
                    canvas.drawCircle(x, y, 8f, pointPaint)
                    canvas.drawText(labels[i], x, chartBottom + 40f, labelPaint)
                    canvas.drawText(formatVal(v), x, y - 16f, valuePaint)
                    prevX = x; prevY = y
                }
            } else {
                val slot = chartWidth / values.size
                val barWidth = slot * 0.6f
                values.forEachIndexed { i, v ->
                    val barHeight = ((v - minVal) / range * chartHeight).toFloat()
                    val left = chartLeft + slot * i + (slot - barWidth) / 2
                    val top = chartBottom - barHeight
                    val barPaint = Paint().apply {
                        color = barColors[i % barColors.size]
                        isAntiAlias = true
                    }
                    canvas.drawRoundRect(RectF(left, top, left + barWidth, chartBottom), 8f, 8f, barPaint)
                    canvas.drawText(labels[i], left + barWidth / 2, chartBottom + 40f, labelPaint)
                    canvas.drawText(formatVal(v), left + barWidth / 2, top - 16f, valuePaint)
                }
            }

            val dir = File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES), "Arya")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "arya_chart_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
            file
        } catch (e: Exception) {
            null
        }
    }

    /** Loads a chart PNG back into a [Bitmap] so it can go through the same
     *  [AryaToolRegistry.lastGeneratedImage] side-channel [ImageGenTools] uses, showing it
     *  inline in chat rather than only as a saved-file path string. */
    fun loadBitmap(file: File): Bitmap? = try {
        android.graphics.BitmapFactory.decodeFile(file.absolutePath)
    } catch (e: Exception) {
        null
    }

    private fun formatVal(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else "%.1f".format(v)
}
