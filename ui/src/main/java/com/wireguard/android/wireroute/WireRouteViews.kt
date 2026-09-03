/* SPDX-License-Identifier: Apache-2.0 */
package com.wireguard.android.wireroute

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import com.google.android.material.color.MaterialColors
import kotlin.math.max

private fun rect(left: Number, top: Number, right: Number, bottom: Number) =
    RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())

private fun Path.moveTo(x: Number, y: Number) = moveTo(x.toFloat(), y.toFloat())
private fun Path.lineTo(x: Number, y: Number) = lineTo(x.toFloat(), y.toFloat())
private fun Path.cubicTo(x1: Number, y1: Number, x2: Number, y2: Number, x3: Number, y3: Number) =
    cubicTo(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), x3.toFloat(), y3.toFloat())
private fun Canvas.drawCircle(cx: Number, cy: Number, radius: Number, paint: Paint) =
    drawCircle(cx.toFloat(), cy.toFloat(), radius.toFloat(), paint)
private fun Canvas.drawLine(startX: Number, startY: Number, stopX: Number, stopY: Number, paint: Paint) =
    drawLine(startX.toFloat(), startY.toFloat(), stopX.toFloat(), stopY.toFloat(), paint)
private fun Canvas.drawRoundRect(rect: RectF, rx: Number, ry: Number, paint: Paint) =
    drawRoundRect(rect, rx.toFloat(), ry.toFloat(), paint)

data class WireRoutePalette(
    val background: Int,
    val sidebar: Int,
    val inset: Int,
    val card: Int,
    val raised: Int,
    val border: Int,
    val signalBlue: Int,
    val liveTeal: Int,
    val warningAmber: Int,
    val label: Int,
    val secondaryLabel: Int,
    val tertiaryLabel: Int
) {
    companion object {
        fun resolve(context: Context, appearance: String): WireRoutePalette {
            if (appearance == WireRouteStore.APPEARANCE_NORDIC) return nordic()
            val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            return system(context, if (dark) systemDarkFallback() else systemLightFallback())
        }

        fun nordic() = WireRoutePalette(
            background = Color.rgb(0x11, 0x1B, 0x2A),
            sidebar = Color.rgb(0x10, 0x1A, 0x28),
            inset = Color.rgb(0x14, 0x22, 0x35),
            card = Color.rgb(0x18, 0x26, 0x38),
            raised = Color.rgb(0x21, 0x32, 0x48),
            border = Color.rgb(0x35, 0x4A, 0x62),
            signalBlue = Color.rgb(0x4C, 0x83, 0xF3),
            liveTeal = Color.rgb(0x2A, 0x9D, 0x8F),
            warningAmber = Color.rgb(0xD6, 0x8B, 0x29),
            label = Color.rgb(0xF4, 0xF7, 0xFB),
            secondaryLabel = Color.rgb(0x98, 0xA3, 0xB5),
            tertiaryLabel = Color.rgb(0x65, 0x74, 0x88)
        )

        private fun system(context: Context, fallback: WireRoutePalette) = WireRoutePalette(
            background = MaterialColors.getColor(context, android.R.attr.colorBackground, fallback.background),
            sidebar = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurfaceContainerLow, fallback.sidebar),
            inset = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurfaceContainerLowest, fallback.inset),
            card = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurfaceContainer, fallback.card),
            raised = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurfaceContainerHigh, fallback.raised),
            border = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOutline, fallback.border),
            signalBlue = MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, fallback.signalBlue),
            liveTeal = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSecondary, fallback.liveTeal),
            warningAmber = MaterialColors.getColor(context, com.google.android.material.R.attr.colorTertiary, fallback.warningAmber),
            label = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, fallback.label),
            secondaryLabel = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, fallback.secondaryLabel),
            tertiaryLabel = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOutline, fallback.tertiaryLabel)
        )

        private fun systemDarkFallback() = nordic().copy(
            background = Color.rgb(0x00, 0x00, 0x00),
            sidebar = Color.rgb(0x1C, 0x1C, 0x1E),
            inset = Color.rgb(0x1C, 0x1C, 0x1E),
            card = Color.rgb(0x2C, 0x2C, 0x2E),
            raised = Color.rgb(0x3A, 0x3A, 0x3C),
            border = Color.rgb(0x54, 0x54, 0x58),
            signalBlue = Color.rgb(0x0A, 0x84, 0xFF)
        )

        private fun systemLightFallback() = nordic().copy(
            background = Color.rgb(0xF2, 0xF2, 0xF7),
            sidebar = Color.WHITE,
            inset = Color.rgb(0xE9, 0xE9, 0xEF),
            card = Color.WHITE,
            raised = Color.rgb(0xF2, 0xF2, 0xF7),
            border = Color.rgb(0xC7, 0xC7, 0xCC),
            label = Color.rgb(0x1C, 0x1C, 0x1E),
            secondaryLabel = Color.rgb(0x63, 0x63, 0x6A),
            tertiaryLabel = Color.rgb(0x8E, 0x8E, 0x93),
            signalBlue = Color.rgb(0x00, 0x7A, 0xFF)
        )
    }
}

fun roundedBackground(color: Int, radius: Float, strokeColor: Int? = null, strokeWidth: Int = 1): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = radius
        if (strokeColor != null) setStroke(strokeWidth, strokeColor)
    }

fun alphaColor(color: Int, alpha: Float): Int = Color.argb(
    (alpha.coerceIn(0f, 1f) * 255).toInt(),
    Color.red(color), Color.green(color), Color.blue(color)
)

fun roundedTypeface(style: Int = Typeface.NORMAL): Typeface = Typeface.create("sans-serif-rounded", style)

enum class WireRouteIcon {
    HOME, PROFILES, ACTIVITY, SETTINGS, PLUS, POWER, BACK, SLIDERS, LAYERS, LOCATION,
    INFO, MORE, SHIELD, APP, ENGINE, PALETTE, HISTORY, EXPORT, LOG, CHEVRON_RIGHT,
    CHEVRON_DOWN, LOCK_OPEN, LOCKED, DNS, GLOBE, ROUTE
}

class WireRouteIconView(context: Context) : View(context) {
    var icon: WireRouteIcon = WireRouteIcon.HOME
        set(value) { field = value; invalidate() }
    var tint: Int = Color.WHITE
        set(value) { field = value; invalidate() }
    var lineWidthDp: Float = 2.2f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val side = minOf(width, height).toFloat()
        val left = (width - side) / 2f
        val top = (height - side) / 2f
        canvas.save()
        canvas.translate(left, top)
        canvas.scale(side / 24f, side / 24f)
        paint.color = tint
        fill.color = tint
        paint.strokeWidth = lineWidthDp * resources.displayMetrics.density * 24f / side.coerceAtLeast(1f)
        path.reset()
        when (icon) {
            WireRouteIcon.HOME -> {
                path.moveTo(3, 11); path.lineTo(12, 3); path.lineTo(21, 11)
                path.moveTo(5.5f, 9); path.lineTo(5.5f, 21); path.lineTo(10, 21); path.lineTo(10, 15); path.lineTo(14, 15); path.lineTo(14, 21); path.lineTo(18.5f, 21); path.lineTo(18.5f, 9)
                canvas.drawPath(path, paint)
            }
            WireRouteIcon.PROFILES -> {
                canvas.drawRoundRect(rect(4, 7, 20, 21), 2, 2, paint)
                path.moveTo(6, 4); path.lineTo(18, 4); path.moveTo(8, 1.5f); path.lineTo(16, 1.5f)
                canvas.drawPath(path, paint)
            }
            WireRouteIcon.ACTIVITY -> {
                path.moveTo(4, 3); path.lineTo(4, 20); path.lineTo(21, 20)
                path.moveTo(7, 15); path.lineTo(11, 11); path.lineTo(14, 13); path.lineTo(20, 7)
                canvas.drawPath(path, paint)
                listOf(7f to 15f, 11f to 11f, 14f to 13f, 20f to 7f).forEach { canvas.drawCircle(it.first, it.second, 1.2f, fill) }
            }
            WireRouteIcon.SETTINGS -> {
                canvas.drawCircle(12, 12, 3.2f, paint)
                for (i in 0 until 8) {
                    val angle = Math.toRadians((i * 45).toDouble())
                    val x1 = 12f + (7.0 * kotlin.math.cos(angle)).toFloat()
                    val y1 = 12f + (7.0 * kotlin.math.sin(angle)).toFloat()
                    val x2 = 12f + (10.0 * kotlin.math.cos(angle)).toFloat()
                    val y2 = 12f + (10.0 * kotlin.math.sin(angle)).toFloat()
                    canvas.drawLine(x1, y1, x2, y2, paint)
                }
                canvas.drawCircle(12, 12, 7, paint)
            }
            WireRouteIcon.PLUS -> { canvas.drawLine(12, 4, 12, 20, paint); canvas.drawLine(4, 12, 20, 12, paint) }
            WireRouteIcon.POWER -> {
                canvas.drawLine(12, 3, 12, 12, paint)
                path.moveTo(7.5f, 6); path.cubicTo(1, 10, 4, 21, 12, 21); path.cubicTo(20, 21, 23, 10, 16.5f, 6)
                canvas.drawPath(path, paint)
            }
            WireRouteIcon.BACK -> { path.moveTo(15.5f, 4); path.lineTo(7.5f, 12); path.lineTo(15.5f, 20); canvas.drawPath(path, paint) }
            WireRouteIcon.SLIDERS -> {
                canvas.drawLine(3, 6, 21, 6, paint); canvas.drawLine(3, 12, 21, 12, paint); canvas.drawLine(3, 18, 21, 18, paint)
                canvas.drawCircle(15, 6, 1.8f, paint); canvas.drawCircle(8, 12, 1.8f, paint); canvas.drawCircle(17, 18, 1.8f, paint)
            }
            WireRouteIcon.LAYERS -> {
                path.moveTo(12, 3); path.lineTo(21, 8); path.lineTo(12, 13); path.lineTo(3, 8); path.close(); canvas.drawPath(path, paint)
                path.reset(); path.moveTo(4, 12); path.lineTo(12, 16.5f); path.lineTo(20, 12); path.moveTo(4, 16); path.lineTo(12, 20.5f); path.lineTo(20, 16); canvas.drawPath(path, paint)
            }
            WireRouteIcon.LOCATION -> {
                canvas.drawCircle(10, 10, 6, paint); canvas.drawLine(14.5f, 14.5f, 21, 21, paint)
                path.moveTo(10, 5.5f); path.lineTo(8.2f, 11.8f); path.lineTo(14.5f, 10); path.close(); canvas.drawPath(path, paint)
            }
            WireRouteIcon.INFO -> { canvas.drawCircle(12, 12, 9, paint); canvas.drawCircle(12, 7, 0.9f, fill); canvas.drawLine(12, 11, 12, 17, paint) }
            WireRouteIcon.MORE -> { canvas.drawCircle(5, 12, 1.4f, fill); canvas.drawCircle(12, 12, 1.4f, fill); canvas.drawCircle(19, 12, 1.4f, fill) }
            WireRouteIcon.SHIELD, WireRouteIcon.DNS -> {
                path.moveTo(12, 2.5f); path.lineTo(20, 5.5f); path.lineTo(19, 14); path.cubicTo(18, 18, 15, 20.5f, 12, 22); path.cubicTo(9, 20.5f, 6, 18, 5, 14); path.lineTo(4, 5.5f); path.close(); canvas.drawPath(path, paint)
                if (icon == WireRouteIcon.DNS) { canvas.drawRoundRect(rect(9, 10, 15, 16), 1, 1, paint); canvas.drawArc(rect(10, 7, 14, 12), 180f, 180f, false, paint) }
            }
            WireRouteIcon.APP -> { canvas.drawRoundRect(rect(5, 5, 19, 20), 3, 3, paint); canvas.drawCircle(18, 5, 2, fill) }
            WireRouteIcon.ENGINE -> { canvas.drawCircle(12, 12, 8, paint); path.moveTo(6, 13); path.lineTo(10, 9); path.lineTo(14, 12); path.lineTo(18, 8); canvas.drawPath(path, paint) }
            WireRouteIcon.PALETTE -> { canvas.drawOval(rect(3, 5, 21, 19), paint); canvas.drawCircle(8, 10, 1, fill); canvas.drawCircle(12, 8, 1, fill); canvas.drawCircle(16, 10, 1, fill); canvas.drawCircle(10, 15, 1, fill) }
            WireRouteIcon.HISTORY -> { canvas.drawCircle(12, 12, 8, paint); canvas.drawLine(12, 12, 12, 7, paint); canvas.drawLine(12, 12, 8, 12, paint); path.moveTo(4, 5); path.lineTo(4, 10); path.lineTo(9, 10); canvas.drawPath(path, paint) }
            WireRouteIcon.EXPORT -> { canvas.drawRoundRect(rect(5, 9, 19, 21), 2, 2, paint); canvas.drawLine(12, 3, 12, 15, paint); path.moveTo(8, 7); path.lineTo(12, 3); path.lineTo(16, 7); canvas.drawPath(path, paint) }
            WireRouteIcon.LOG -> { canvas.drawRoundRect(rect(5, 3, 18, 20), 2, 2, paint); canvas.drawLine(8, 8, 15, 8, paint); canvas.drawLine(8, 12, 14, 12, paint); canvas.drawCircle(17, 18, 3, paint); canvas.drawLine(19, 20, 22, 23, paint) }
            WireRouteIcon.CHEVRON_RIGHT -> { path.moveTo(9, 5); path.lineTo(16, 12); path.lineTo(9, 19); canvas.drawPath(path, paint) }
            WireRouteIcon.CHEVRON_DOWN -> { path.moveTo(5, 9); path.lineTo(12, 16); path.lineTo(19, 9); canvas.drawPath(path, paint) }
            WireRouteIcon.LOCK_OPEN, WireRouteIcon.LOCKED -> {
                canvas.drawRoundRect(rect(6, 10, 18, 20), 2, 2, paint)
                if (icon == WireRouteIcon.LOCKED) canvas.drawArc(rect(8, 3, 16, 13), 180f, -180f, false, paint)
                else canvas.drawArc(rect(11, 3, 19, 13), 180f, -120f, false, paint)
            }
            WireRouteIcon.GLOBE -> { canvas.drawCircle(12, 12, 9, paint); canvas.drawOval(rect(8, 3, 16, 21), paint); canvas.drawLine(3, 12, 21, 12, paint) }
            WireRouteIcon.ROUTE -> { path.moveTo(4, 12); path.lineTo(11, 12); path.lineTo(20, 5); path.moveTo(11, 12); path.lineTo(20, 19); canvas.drawPath(path, paint); canvas.drawCircle(4, 12, 1.7f, fill); canvas.drawCircle(20, 5, 1.7f, fill); canvas.drawCircle(20, 19, 1.7f, fill) }
        }
        canvas.restore()
    }
}

class WireRouteGlyphView(context: Context) : View(context) {
    var fullTunnel: Boolean = false
        set(value) { field = value; invalidate() }
    var stateColor: Int = Color.BLUE
        set(value) { field = value; invalidate() }
    var inactive: Boolean = true
        set(value) { field = value; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val node = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = stateColor
        node.color = stateColor
        paint.strokeWidth = resources.displayMetrics.density * 2.6f
        paint.pathEffect = if (inactive) android.graphics.DashPathEffect(floatArrayOf(7f, 7f), 0f) else null
        val startX = width * 0.18f
        val junctionX = width * 0.5f
        val endX = width * 0.82f
        val middle = height * 0.5f
        canvas.drawLine(startX, middle, junctionX, middle, paint)
        if (fullTunnel) {
            canvas.drawLine(junctionX, middle, endX, middle, paint)
            canvas.drawCircle(endX, middle, width * 0.055f, node)
        } else {
            canvas.drawLine(junctionX, middle, endX, height * 0.24f, paint)
            canvas.drawLine(junctionX, middle, endX, height * 0.76f, paint)
            canvas.drawCircle(endX, height * 0.24f, width * 0.055f, node)
            canvas.drawCircle(endX, height * 0.76f, width * 0.055f, node)
        }
        canvas.drawCircle(startX, middle, width * 0.055f, node)
    }
}

class WireRouteTrafficChartView(context: Context) : View(context) {
    var points: List<WireRouteActivityPoint> = emptyList()
        set(value) { field = value; invalidate() }
    var palette: WireRoutePalette = WireRoutePalette.nordic()
        set(value) { field = value; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = roundedTypeface() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = alphaColor(palette.border, 0.5f)
        paint.strokeWidth = resources.displayMetrics.density
        for (i in 0..3) {
            val y = paddingTop + (height - paddingTop - paddingBottom) * i / 3f
            canvas.drawLine(paddingLeft.toFloat(), y, (width - paddingRight).toFloat(), y, paint)
        }
        if (points.size < 2 || points.none { it.receivedBytesPerSecond > 0 || it.sentBytesPerSecond > 0 }) {
            textPaint.color = palette.secondaryLabel
            textPaint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics)
            val metrics = textPaint.fontMetrics
            canvas.drawText("Connect this profile to begin recording traffic.", width / 2f, height / 2f - (metrics.ascent + metrics.descent) / 2f, textPaint)
            return
        }
        val maxValue = max(1.0, points.maxOf { max(it.receivedBytesPerSecond, it.sentBytesPerSecond) })
        drawSeries(canvas, maxValue, palette.signalBlue) { it.receivedBytesPerSecond }
        drawSeries(canvas, maxValue, palette.liveTeal) { it.sentBytesPerSecond }
    }

    private fun drawSeries(canvas: Canvas, maxValue: Double, color: Int, value: (WireRouteActivityPoint) -> Double) {
        val graphWidth = width - paddingLeft - paddingRight
        val graphHeight = height - paddingTop - paddingBottom
        val path = Path()
        points.forEachIndexed { index, point ->
            val x = paddingLeft + graphWidth * index / (points.size - 1f)
            val y = paddingTop + graphHeight * (1f - (value(point) / maxValue).toFloat())
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        paint.color = color
        paint.strokeWidth = resources.displayMetrics.density * 2.2f
        paint.pathEffect = null
        canvas.drawPath(path, paint)
    }
}
