package com.cryptotrader.bybit

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class SignalMarker(
    val time: Long,
    val type: String,
    val pattern: String = "",
    val sourceTf: String = ""
)


class ChartView(context: Context) : View(context) {
    private var candles: List<Candle> = emptyList()
    private var offset = 0
    private var visible = 100
    private var chartType: String = "candles"
    private var theme: String = "dark"
    private var lsPoints: List<LsPoint> = emptyList()
    private var signalMarkers: List<SignalMarker> = emptyList()
    private data class CandleMarker(val candleIndex: Int, val type: String, val level: Int)
    private var volumeMarkers: List<CandleMarker> = emptyList()
    private var accelMarkers: List<CandleMarker> = emptyList()
    private var liqMarkers: List<CandleMarker> = emptyList()
    private var chartEventsRaw: List<ChartEvent> = emptyList()

    private var bidWallPrice: Double = 0.0
    private var bidWallUsd: Double = 0.0
    private var askWallPrice: Double = 0.0
    private var askWallUsd: Double = 0.0
    private var liqClusters: List<LiqCluster> = emptyList()
    private var accLow: Double = 0.0
    private var accHigh: Double = 0.0

    private var yMin: Double = 0.0
    private var yMax: Double = 0.0
    private var manualZoomY: Boolean = false

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val volPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var scaleDetector: ScaleGestureDetector? = null
    private var gestureDetector: GestureDetector? = null
    private var heavyBitmap: android.graphics.Bitmap? = null

    private var lastAvgY: Float = 0f
    private var twoFingerActive: Boolean = false

    var onViewportChanged: ((Int, Int) -> Unit)? = null

    init {
        textPaint.color = Color.LTGRAY
        textPaint.textSize = 22f
        volPaint.color = Color.rgb(60, 60, 80)

        scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (candles.isEmpty()) return true

                val dxSpan = abs(detector.currentSpanX - detector.previousSpanX)
                val dySpan = abs(detector.currentSpanY - detector.previousSpanY)

                val factor = detector.scaleFactor
                if (factor <= 0f) return true

                if (dySpan > dxSpan) {
                    if (yMax <= yMin) return true
                    val rng = yMax - yMin
                    val newRng = rng / factor
                    if (newRng <= 0) return true

                    val totalPlot = height - 30f - 40f
                    val candleH = totalPlot * 0.64f
                    val candleTop = 30f

                    val focusRelY = ((detector.focusY - candleTop) / candleH).coerceIn(0f, 1f)
                    val focusPrice = yMax - focusRelY * rng

                    yMax = focusPrice + focusRelY * newRng
                    yMin = focusPrice - (1f - focusRelY) * newRng

                    manualZoomY = true
                    heavyBitmap = null
                    invalidate()
                } else {
                    val nv = (visible / factor).toInt().coerceIn(30, 300)
                    if (nv != visible) {
                        val re = offset + visible
                        visible = nv
                        val rp2 = visible / 5
                        val mo = max(0, candles.size - visible + rp2)
                        offset = (re - visible).coerceIn(0, mo)
                        heavyBitmap = null
                        onViewportChanged?.invoke(offset, visible)
                        invalidate()
                    }
                }
                return true
            }
        })

        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (candles.isEmpty() || width == 0) return true
                if (twoFingerActive) return true

                val cpp = visible.toFloat() / width.toFloat()
                val sh = (dx * cpp).toInt()
                if (sh != 0) {
                    val rp = visible / 5
                    offset = (offset + sh).coerceIn(0, max(0, candles.size - visible + rp))
                    heavyBitmap = null
                    onViewportChanged?.invoke(offset, visible)
                    invalidate()
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                manualZoomY = false
                recomputeYRange()
                heavyBitmap = null
                invalidate()
                return true
            }
        })
    }

    private fun averageY(event: MotionEvent): Float {
        var sum = 0f
        for (i in 0 until event.pointerCount) sum += event.getY(i)
        return sum / event.pointerCount
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                twoFingerActive = true
                lastAvgY = averageY(event)
            }
            MotionEvent.ACTION_MOVE -> {
                if (twoFingerActive && event.pointerCount >= 2) {
                    if (scaleDetector?.isInProgress == true) {
                        lastAvgY = averageY(event)
                    } else {
                        val y = averageY(event)
                        val dy = y - lastAvgY
                        if (abs(dy) > 1f && yMax > yMin && height > 0) {
                            val totalPlot = height - 30f - 40f
                            val candleH = totalPlot * 0.64f
                            val rng = yMax - yMin
                            val shift = -(dy / candleH) * rng
                            yMin += shift
                            yMax += shift
                            manualZoomY = true
                            heavyBitmap = null
                            invalidate()
                            lastAvgY = y
                        }
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount <= 2) {
                    twoFingerActive = false
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                twoFingerActive = false
            }
        }

        val s = scaleDetector?.onTouchEvent(event) ?: false
        val g = gestureDetector?.onTouchEvent(event) ?: false
        return s || g || super.onTouchEvent(event)
    }

    private fun recomputeYRange() {
        if (manualZoomY) return
        if (candles.isEmpty()) return
        val end = (offset + visible).coerceAtMost(candles.size)
        val start = offset.coerceAtMost(end - 1).coerceAtLeast(0)
        val slice = candles.subList(start, end)
        if (slice.isEmpty()) return

        var minP = slice.minOf { it.low }
        var maxP = slice.maxOf { it.high }
        val limit = maxP * 0.05

        if (bidWallPrice > 0 && bidWallPrice < minP && (minP - bidWallPrice) <= limit) {
            minP = bidWallPrice
        }
        if (askWallPrice > 0 && askWallPrice > maxP && (askWallPrice - maxP) <= limit) {
            maxP = askWallPrice
        }

        yMin = minP
        yMax = maxP
    }

    fun setData(c: List<Candle>, off: Int, vis: Int, type: String, th: String) {
        candles = c
        offset = off
        visible = vis
        chartType = type
        theme = th
        recomputeAllMarkers()
        recomputeYRange()
        heavyBitmap = null
        invalidate()
    }

    fun resetZoomY() {
        manualZoomY = false
        recomputeYRange()
        heavyBitmap = null
        invalidate()
    }

    fun setLsPoints(points: List<LsPoint>) {
        lsPoints = points
        heavyBitmap = null
        invalidate()
    }

    fun setSignalMarkers(markers: List<SignalMarker>) {
        signalMarkers = markers
        heavyBitmap = null
        invalidate()
    }

    fun setWalls(bidPrice: Double, bidUsd: Double, askPrice: Double, askUsd: Double) {
        bidWallPrice = bidPrice
        bidWallUsd = bidUsd
        askWallPrice = askPrice
        askWallUsd = askUsd
        recomputeYRange()
        heavyBitmap = null
        invalidate()
    }

    fun setAccumulationZone(low: Double, high: Double) {
        accLow = low
        accHigh = high
        heavyBitmap = null
        invalidate()
    }

    fun setLiqClusters(clusters: List<LiqCluster>) {
        liqClusters = clusters
        recomputeYRange()
        heavyBitmap = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val dark = theme == "dark"
        val bgColor = if (dark) Color.rgb(18, 18, 24) else Color.rgb(245, 245, 250)

        if (candles.isEmpty() || width == 0 || height == 0) {
            canvas.drawColor(bgColor)
            return
        }

        if (heavyBitmap == null || heavyBitmap!!.width != width || heavyBitmap!!.height != height) {
            heavyBitmap = android.graphics.Bitmap.createBitmap(
                width, height, android.graphics.Bitmap.Config.ARGB_8888
            )
            drawHeavy(Canvas(heavyBitmap!!))
        }

        canvas.drawBitmap(heavyBitmap!!, 0f, 0f, null)
        drawVolumeMarkers(canvas)
        drawAccelMarkers(canvas)
        drawLiqMarkers(canvas)
        drawSignalMarkers(canvas)
    }

    fun exportBitmap(): android.graphics.Bitmap? {
        if (width <= 0 || height <= 0) return null
        return try {
            val bmp = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            draw(android.graphics.Canvas(bmp))
            bmp
        } catch (_: Exception) { null }
    }

    private fun drawHeavy(canvas: Canvas) {
        val dark = theme == "dark"
        val bgColor = if (dark) Color.rgb(18, 18, 24) else Color.rgb(245, 245, 250)
        val gridColor = if (dark) Color.rgb(40, 40, 50) else Color.rgb(210, 210, 220)
        val labelColor = if (dark) Color.LTGRAY else Color.rgb(60, 60, 70)
        val upColor = Color.rgb(38, 166, 154)
        val downColor = Color.rgb(239, 83, 80)

        canvas.drawColor(bgColor)
        textPaint.color = labelColor

        val end = (offset + visible).coerceAtMost(candles.size)
        val start = offset.coerceAtMost(end - 1).coerceAtLeast(0)
        val slice = candles.subList(start, end)
        if (slice.isEmpty()) return

        val pL = 16f
        val pR = 110f
        val pT = 30f
        val pB = 40f
        val w = width.toFloat()
        val h = height.toFloat()

        val totalPlot = h - pT - pB
        val candleH = totalPlot * 0.64f
        val volH = totalPlot * 0.13f
        val lsH = totalPlot * 0.13f

        val candleTop = pT
        val volTop = candleTop + candleH + totalPlot * 0.02f
        val lsTop = volTop + volH + totalPlot * 0.02f

        val minPAdj = if (yMax > yMin) yMin else slice.minOf { it.low }
        val maxPAdj = if (yMax > yMin) yMax else slice.maxOf { it.high }
        val rng = (maxPAdj - minPAdj).takeIf { it > 0 } ?: 1.0

        val maxVol = slice.maxOf { it.volume }.takeIf { it > 0 } ?: 1.0
        val plotW = w - pL - pR
        val step = plotW / visible
        val bodyW = max(2f, step * 0.6f)

        paint.color = gridColor
        paint.strokeWidth = 1f
        for (g in 0..5) {
            val yy = candleTop + candleH * g / 5
            canvas.drawLine(pL, yy, w - pR, yy, paint)
            val pa = maxPAdj - rng * g / 5
            val label = "%.4f".format(pa)
            canvas.drawText(label, w - pR + 8, yy + 6, textPaint)
        }

        canvas.save()
        canvas.clipRect(pL, candleTop, w - pR, candleTop + candleH)

        // Отрисовка зоны накопления
        if (accLow > 0.0 && accHigh > accLow) {
            val yLow = candleTop + ((maxPAdj - accLow) / rng * candleH).toFloat()
            val yHigh = candleTop + ((maxPAdj - accHigh) / rng * candleH).toFloat()
            val zonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(40, 255, 255, 255) // Полупрозрачный белый
                style = Paint.Style.FILL
            }
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(120, 255, 255, 255)
                strokeWidth = 1f
                style = Paint.Style.STROKE
            }
            canvas.drawRect(pL, yHigh, w - pR, yLow, zonePaint)
            canvas.drawLine(pL, yHigh, w - pR, yHigh, borderPaint)
            canvas.drawLine(pL, yLow, w - pR, yLow, borderPaint)
        }

        if (chartType == "line") {
            paint.color = upColor
            paint.strokeWidth = 3f
            paint.style = Paint.Style.STROKE
            val path = android.graphics.Path()
            slice.forEachIndexed { i, c ->
                val x = pL + i * step + step / 2
                val y = candleTop + ((maxPAdj - c.close) / rng * candleH).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, paint)
            paint.style = Paint.Style.FILL
        } else {
            slice.forEachIndexed { i, c ->
                val x = pL + i * step + step / 2
                val green = c.close >= c.open
                paint.color = if (green) upColor else downColor
                paint.strokeWidth = max(1f, step * 0.15f)
                val yH = candleTop + ((maxPAdj - c.high) / rng * candleH).toFloat()
                val yL = candleTop + ((maxPAdj - c.low) / rng * candleH).toFloat()
                canvas.drawLine(x, yH, x, yL, paint)
                val t1 = candleTop + ((maxPAdj - max(c.open, c.close)) / rng * candleH).toFloat()
                val b1 = candleTop + ((maxPAdj - min(c.open, c.close)) / rng * candleH).toFloat()
                canvas.drawRect(x - bodyW / 2, t1, x + bodyW / 2, max(b1, t1 + 2f), paint)
            }
        }

        if (liqClusters.isNotEmpty()) {
            for (c in liqClusters) {
                if (c.priceHigh < minPAdj || c.priceLow > maxPAdj) continue
                val yHigh = candleTop + ((maxPAdj - c.priceHigh) / rng * candleH).toFloat()
                val yLow = candleTop + ((maxPAdj - c.priceLow) / rng * candleH).toFloat()
                val alpha = ((c.usd / 500_000.0).coerceIn(0.15, 0.6) * 255).toInt()
                val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (c.isLong) Color.argb(alpha, 255, 80, 80)
                            else Color.argb(alpha, 80, 255, 80)
                    style = Paint.Style.FILL
                }
                canvas.drawRect(pL, yHigh, w - pR, yLow, fillPaint)
                val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (c.isLong) Color.rgb(255, 100, 100)
                            else Color.rgb(100, 255, 100)
                    strokeWidth = 2f
                    style = Paint.Style.STROKE
                }
                canvas.drawLine(pL, yHigh, w - pR, yHigh, borderPaint)
                canvas.drawLine(pL, yLow, w - pR, yLow, borderPaint)
                val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (c.isLong) Color.rgb(255, 150, 150)
                            else Color.rgb(150, 255, 150)
                    textSize = 28f
                    typeface = Typeface.DEFAULT_BOLD
                }
                val mark = if (c.isLong) "🐂" else "🐻"
                canvas.drawText("$mark ${formatUsd(c.usd)}", pL + 4, yHigh + 32, labelPaint)
            }
        }
        if (bidWallPrice > 0 && bidWallPrice in minPAdj..maxPAdj) {
            val yBid = candleTop + ((maxPAdj - bidWallPrice) / rng * candleH).toFloat()
            val bidWidth = when {
                bidWallUsd >= 500_000 -> 8f
                bidWallUsd >= 200_000 -> 5f
                bidWallUsd >= 50_000 -> 3f
                else -> 1.5f
            }
            val bidColor = when {
                bidWallUsd >= 500_000 -> Color.rgb(0, 255, 100)   // Неоновый зеленый
                bidWallUsd >= 200_000 -> Color.rgb(60, 200, 100)  // Яркий
                else -> Color.rgb(60, 120, 80)                    // Тусклый
            }
            val bidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = bidColor
                strokeWidth = bidWidth
                style = Paint.Style.STROKE
            }
            canvas.drawLine(pL, yBid, w - pR, yBid, bidPaint)
        }
        if (askWallPrice > 0 && askWallPrice in minPAdj..maxPAdj) {
            val yAsk = candleTop + ((maxPAdj - askWallPrice) / rng * candleH).toFloat()
            val askWidth = when {
                askWallUsd >= 500_000 -> 8f
                askWallUsd >= 200_000 -> 5f
                askWallUsd >= 50_000 -> 3f
                else -> 1.5f
            }
            val askColor = when {
                askWallUsd >= 500_000 -> Color.rgb(255, 0, 80)    // Неоновый красный
                askWallUsd >= 200_000 -> Color.rgb(220, 80, 80)   // Яркий
                else -> Color.rgb(120, 60, 60)                    // Тусклый
            }
            val askPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = askColor
                strokeWidth = askWidth
                style = Paint.Style.STROKE
            }
            canvas.drawLine(pL, yAsk, w - pR, yAsk, askPaint)
        }
        canvas.restore()

        if (bidWallPrice > 0 && bidWallPrice in minPAdj..maxPAdj) {
            val yBid = candleTop + ((maxPAdj - bidWallPrice) / rng * candleH).toFloat()
            val wallTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(60, 200, 100)
                textSize = 36f
                typeface = Typeface.DEFAULT_BOLD
            }
            canvas.drawText("BID ${formatUsd(bidWallUsd)}", pL + 4, yBid - 6, wallTextPaint)
        } else if (bidWallPrice > 0 && bidWallPrice < minPAdj) {
            val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(60, 200, 100)
                textSize = 30f
                typeface = Typeface.DEFAULT_BOLD
            }
            canvas.drawText("▼ BID ${"%.4f".format(bidWallPrice)} (${formatUsd(bidWallUsd)})",
                pL + 4, candleTop + candleH + 26, edgePaint)
        }

        if (askWallPrice > 0 && askWallPrice in minPAdj..maxPAdj) {
            val yAsk = candleTop + ((maxPAdj - askWallPrice) / rng * candleH).toFloat()
            val wallTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(240, 100, 100)
                textSize = 36f
                typeface = Typeface.DEFAULT_BOLD
            }
            canvas.drawText("ASK ${formatUsd(askWallUsd)}", pL + 4, yAsk - 6, wallTextPaint)
        } else if (askWallPrice > 0 && askWallPrice > maxPAdj) {
            val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(240, 100, 100)
                textSize = 30f
                typeface = Typeface.DEFAULT_BOLD
            }
            canvas.drawText("▲ ASK ${"%.4f".format(askWallPrice)} (${formatUsd(askWallUsd)})",
                pL + 4, candleTop - 8, edgePaint)
        }
canvas.save()
        canvas.clipRect(pL, volTop, w - pR, volTop + volH)

        slice.forEachIndexed { i, c ->
            val x = pL + i * step + step / 2
            val green = c.close >= c.open
            volPaint.color = if (green) upColor else downColor
            val vh = (c.volume / maxVol * volH).toFloat()
            canvas.drawRect(
                x - bodyW / 2,
                volTop + volH - vh,
                x + bodyW / 2,
                volTop + volH,
                volPaint
            )
        }

        canvas.restore()

        textPaint.color = labelColor
        canvas.drawText("VOL", pL + 4, volTop + volH - 4, textPaint)

        if (lsPoints.size >= 2) {
            val gridP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (dark) Color.rgb(40, 40, 50) else Color.rgb(210, 210, 220)
                strokeWidth = 1f
            }
            val lineP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                strokeWidth = 2.5f
                style = Paint.Style.STROKE
            }
            val zeroP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (dark) Color.rgb(90, 90, 110) else Color.rgb(150, 150, 170)
                strokeWidth = 1.5f
            }

            var maxAbsDelta = 0f
            for (p in lsPoints) {
                val d = abs(p.longPct - p.shortPct).toFloat()
                if (d > maxAbsDelta) maxAbsDelta = d
            }
            val scaleMax = when {
                maxAbsDelta <= 20f -> 30f
                maxAbsDelta <= 40f -> 50f
                maxAbsDelta <= 60f -> 70f
                maxAbsDelta <= 80f -> 90f
                else -> 100f
            }
            val midY = lsTop + lsH / 2

            canvas.save()
            canvas.clipRect(pL, lsTop, w - pR, lsTop + lsH)

            val halfScale = (scaleMax / 2f).toInt()
            val gridSteps = listOf(-scaleMax.toInt(), -halfScale, 0, halfScale, scaleMax.toInt())
            gridSteps.forEach { pct ->
                val yy = midY - (pct / scaleMax) * (lsH / 2)
                if (pct == 0) {
                    canvas.drawLine(pL, yy, w - pR, yy, zeroP)
                } else {
                    canvas.drawLine(pL, yy, w - pR, yy, gridP)
                }
                val labelColorLocal = if (pct > 0) Color.rgb(120, 200, 140)
                                     else if (pct < 0) Color.rgb(220, 120, 120)
                                     else labelColor
                textPaint.color = labelColorLocal
                val prefix = if (pct > 0) "+" else ""
                canvas.drawText("$prefix$pct%", w - pR + 8, yy + 5, textPaint)
            }

            val minT = lsPoints.first().time
            val maxT = lsPoints.last().time
            val timeRange = (maxT - minT).coerceAtLeast(1000L).toFloat()

            var i = 0
            while (i < lsPoints.size - 1) {
                val a = lsPoints[i]
                val b = lsPoints[i + 1]
                val deltaA = a.longPct - a.shortPct
                val deltaB = b.longPct - b.shortPct

                val xA = pL + ((a.time - minT) / timeRange) * plotW
                val xB = pL + ((b.time - minT) / timeRange) * plotW
                val yA = midY - ((deltaA / scaleMax).toFloat() * (lsH / 2))
                val yB = midY - ((deltaB / scaleMax).toFloat() * (lsH / 2))

                lineP.color = if ((deltaA + deltaB) / 2f >= 0) Color.rgb(120, 200, 140)
                              else Color.rgb(220, 120, 120)
                canvas.drawLine(xA, yA, xB, yB, lineP)
                i++
            }

            canvas.restore()

            val last = lsPoints.last()
            val deltaLast = last.longPct - last.shortPct
            textPaint.color = if (deltaLast >= 0) Color.rgb(120, 200, 140)
                              else Color.rgb(220, 120, 120)
            val sign = if (deltaLast >= 0) "+" else ""
            val oldSize = textPaint.textSize
            val oldTypeface = textPaint.typeface
            textPaint.textSize = 32f
            textPaint.typeface = Typeface.DEFAULT_BOLD
            canvas.drawText(
                "L/S: $sign${"%.1f".format(deltaLast)}%  (L ${"%.0f".format(last.longPct)}% / S ${"%.0f".format(last.shortPct)}%)",
                pL + 4, lsTop + 24, textPaint
            )
            textPaint.textSize = oldSize
            textPaint.typeface = oldTypeface
            textPaint.color = labelColor
        }
    }

    private fun formatUsd(v: Double): String = when {
        v >= 1_000_000 -> "${"%.1f".format(v / 1_000_000)}M"
        v >= 1_000 -> "${"%.0f".format(v / 1_000)}K"
        else -> "${"%.0f".format(v)}"
    }

    /**
     * Бинарный поиск: первая свеча с time >= markerTime.
     * Свечи отсортированы по time по возрастанию (гарантирует Bybit API).
     * Возвращает -1, если такой свечи нет в диапазоне [start, endExclusive).
     */
    private fun findCandleIndexByTime(markerTime: Long, start: Int, endExclusive: Int): Int {
        var lo = start
        var hi = endExclusive - 1
        var result = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (candles[mid].time >= markerTime) {
                result = mid
                hi = mid - 1
            } else {
                lo = mid + 1
            }
        }
        return result
    }

    private fun drawSignalMarkers(canvas: Canvas) {
        if (signalMarkers.isEmpty() || candles.isEmpty()) return

        val sliceSize = visible.coerceAtMost(candles.size - offset)
        if (sliceSize <= 0) return

        val pL = 16f
        val pR = 110f
        val pT = 30f
        val pB = 40f
        val w = width.toFloat()
        val h = height.toFloat()
        val totalPlot = h - pT - pB
        val candleH = totalPlot * 0.64f
        val candleTop = pT
        val plotW = w - pL - pR
        val step = plotW / visible

        val slice = candles.subList(offset, offset + sliceSize)
        val minP = if (yMax > yMin) yMin else slice.minOf { it.low }
        val maxP = if (yMax > yMin) yMax else slice.maxOf { it.high }
        val rng = (maxP - minP).takeIf { it > 0 } ?: 1.0

        val startTime = candles[offset].time
        val endTime = candles[(offset + sliceSize - 1).coerceAtMost(candles.size - 1)].time

        canvas.save()
        canvas.clipRect(pL, candleTop - 60f, w - pR, candleTop + candleH + 60f)

        for (marker in signalMarkers) {
            // Быстрая проверка — маркер в видимом временном диапазоне?
            if (marker.time < startTime || marker.time > endTime + 60_000L) continue

            // Бинарный поиск нужной свечи (O(log n) вместо O(n))
            val candleIdx = findCandleIndexByTime(marker.time, offset, offset + sliceSize)
            if (candleIdx < 0) continue

            val localIdx = candleIdx - offset
            if (localIdx < 0 || localIdx >= sliceSize) continue

            val x = pL + localIdx * step + step / 2
            val c = candles[candleIdx]

            val isShort = marker.type == "short"
            val markerColor = if (isShort) Color.rgb(255, 120, 140) else Color.rgb(120, 255, 150)

            val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = markerColor
                strokeWidth = 3f
                style = Paint.Style.STROKE
            }

            val yH = candleTop + ((maxP - c.high) / rng * candleH).toFloat()
            val yL = candleTop + ((maxP - c.low) / rng * candleH).toFloat()
            canvas.drawRect(
                x - step * 0.45f,
                yH - 4f,
                x + step * 0.45f,
                yL + 4f,
                framePaint
            )

            val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = markerColor
                textSize = 40f
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
            }
            if (isShort) {
                canvas.drawText("⬇", x, yH - 10f, arrowPaint)
            } else {
                canvas.drawText("⬆", x, yL + 45f, arrowPaint)
            }

            if (marker.pattern.isNotEmpty()) {
                val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = markerColor
                    textSize = 26f
                    typeface = Typeface.DEFAULT_BOLD
                    textAlign = Paint.Align.CENTER
                }

                val labelY = if (isShort) yH - 55f else yL + 85f
                val tfLabel = when (marker.sourceTf) {
                    "1" -> "1м"
                    "5" -> "5м"
                    "15" -> "15м"
                    "60" -> "1ч"
                    else -> ""
                }
                val label = if (tfLabel.isNotEmpty()) "${marker.pattern} ($tfLabel)" else marker.pattern

                val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(180, 0, 20, 10)
                }
                val textWidth = labelPaint.measureText(label)
                val padding = 6f
                val textHeight = labelPaint.textSize

                canvas.drawRect(
                    x - textWidth / 2 - padding,
                    labelY - textHeight,
                    x + textWidth / 2 + padding,
                    labelY + 6f,
                    bgPaint
                )

                canvas.drawText(label, x, labelY - 4f, labelPaint)
            }
        }

        canvas.restore()
    }
    fun setChartEvents(events: List<ChartEvent>) {
        chartEventsRaw = events
        recomputeAllMarkers()
        invalidate()
    }

    private fun recomputeAllMarkers() {
        val vols = ArrayList<CandleMarker>()
        val accels = ArrayList<CandleMarker>()
        val liqs = ArrayList<CandleMarker>()
        if (candles.isNotEmpty()) {
            val stepMs = if (candles.size >= 2) candles[1].time - candles[0].time else 60_000L
            for (e in chartEventsRaw) {
                var idx = -1
                var lo = 0
                var hi = candles.size - 1
                while (lo <= hi) {
                    val mid = (lo + hi) ushr 1
                    val ct = candles[mid].time
                    if (ct <= e.time) {
                        if (e.time < ct + stepMs) { idx = mid; break }
                        lo = mid + 1
                    } else {
                        hi = mid - 1
                    }
                }
                if (idx < 0) continue
                val cm = CandleMarker(idx, e.type, e.level)
                when (e.type) {
                    "vol" -> vols.add(cm)
                    "accel" -> accels.add(cm)
                    "liq" -> liqs.add(cm)
                }
            }
        }
        volumeMarkers = vols
        accelMarkers = accels
        liqMarkers = liqs
    }

    private fun drawVolumeMarkers(canvas: Canvas) {
        drawMarkerList(canvas, volumeMarkers, "🚀", 22f)
    }

    private fun drawAccelMarkers(canvas: Canvas) {
        drawMarkerList(canvas, accelMarkers, "⚡", 26f)
    }

    private fun drawLiqMarkers(canvas: Canvas) {
        drawMarkerList(canvas, liqMarkers, "💥", 26f)
    }

    private fun drawMarkerList(canvas: Canvas, markers: List<CandleMarker>, emoji: String, baseSize: Float) {
        if (markers.isEmpty() || candles.isEmpty()) return
        val sliceSize = visible.coerceAtMost(candles.size - offset)
        if (sliceSize <= 0) return
        val pL = 16f
        val pR = 110f
        val pT = 30f
        val pB = 40f
        val w = width.toFloat()
        val h = height.toFloat()
        val totalPlot = h - pT - pB
        val candleH = totalPlot * 0.64f
        val candleTop = pT
        val plotW = w - pL - pR
        val step = plotW / visible
        val slice = candles.subList(offset, offset + sliceSize)
        val minP = if (yMax > yMin) yMin else slice.minOf { it.low }
        val maxP = if (yMax > yMin) yMax else slice.maxOf { it.high }
        val rng = (maxP - minP).takeIf { it > 0 } ?: 1.0
        canvas.save()
        canvas.clipRect(pL, candleTop - 50f, w - pR, candleTop + candleH)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        for (m in markers) {
            if (m.candleIndex < offset || m.candleIndex >= offset + sliceSize) continue
            val localIdx = m.candleIndex - offset
            val x = pL + localIdx * step + step / 2
            val c = candles[m.candleIndex]
            val y = candleTop + ((maxP - c.high) / rng * candleH).toFloat()
            paint.textSize = baseSize + (m.level - 1) * 6f
            canvas.drawText(emoji, x, y - 8f, paint)
        }
        canvas.restore()
    }
}
