package com.cryptotrader.bybit

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object SignalDetector {

    // ================== Reversal detection ==================

    fun detectReversalSignal(
        candles: List<Candle>,
        liq: LiqStats?,
        liqThreshold: Double
    ): SignalResult {
        if (candles.size < 22 || liq == null) return SignalResult("", 0L, "")
        if (liq.total15m < liqThreshold) return SignalResult("", 0L, "")
        if (liq.trend != "falling") return SignalResult("", 0L, "")

        val c = candles[candles.size - 2]
        val p1 = if (candles.size >= 3) candles[candles.size - 3] else null
        val p2 = if (candles.size >= 4) candles[candles.size - 4] else null

        val body = abs(c.close - c.open)
        val upperWick = c.high - max(c.open, c.close)
        val lowerWick = min(c.open, c.close) - c.low
        val totalHeight = c.high - c.low
        if (totalHeight <= 0.0 || body <= 0.0) return SignalResult("", 0L, "")

        val isRed = c.close < c.open
        val isGreen = c.close > c.open

        val isShootingStar = isRed && upperWick >= body * 2.0 && upperWick >= totalHeight * 0.5
        val isHammer = isGreen && lowerWick >= body * 2.0 && lowerWick >= totalHeight * 0.5
        val isInvertedHammer = isGreen && upperWick >= body * 2.0 && upperWick >= totalHeight * 0.5
        val isHangingMan = isRed && lowerWick >= body * 2.0 && lowerWick >= totalHeight * 0.5
        val isDragonflyDoji = body <= totalHeight * 0.1 && lowerWick >= totalHeight * 0.6 && upperWick < totalHeight * 0.2
        val isGravestoneDoji = body <= totalHeight * 0.1 && upperWick >= totalHeight * 0.6 && lowerWick < totalHeight * 0.2

        var isBullishEngulfing = false
        var isBearishEngulfing = false
        var isPiercingLine = false
        var isDarkCloudCover = false
        var isTweezerBottom = false
        var isTweezerTop = false
        var isBullishHarami = false
        var isBearishHarami = false

        if (p1 != null) {
            val p1Body = abs(p1.close - p1.open)
            val p1Red = p1.close < p1.open
            val p1Green = p1.close > p1.open

            isBullishEngulfing = p1Red && isGreen && c.open <= p1.close && c.close >= p1.open && body > p1Body
            isBearishEngulfing = p1Green && isRed && c.open >= p1.close && c.close <= p1.open && body > p1Body

            if (p1Red && isGreen) {
                val p1Mid = (p1.open + p1.close) / 2
                isPiercingLine = c.open < p1.low && c.close > p1Mid && c.close < p1.open
                isTweezerBottom = abs(c.low - p1.low) <= p1.low * 0.002
            }
            if (p1Green && isRed) {
                val p1Mid = (p1.open + p1.close) / 2
                isDarkCloudCover = c.open > p1.high && c.close < p1Mid && c.close > p1.open
                isTweezerTop = abs(c.high - p1.high) <= p1.high * 0.002
            }
            if (p1Red && isGreen && p1Body > body * 1.5) {
                isBullishHarami = c.open > p1.close && c.close < p1.open
            }
            if (p1Green && isRed && p1Body > body * 1.5) {
                isBearishHarami = c.open < p1.close && c.close > p1.open
            }
        }

        var isMorningStar = false
        var isEveningStar = false
        var isThreeWhiteSoldiers = false
        var isThreeBlackCrows = false
        var isThreeInsideUp = false
        var isThreeInsideDown = false
        var isThreeOutsideUp = false
        var isThreeOutsideDown = false

        if (p1 != null && p2 != null) {
            val p1Body = abs(p1.close - p1.open)
            val p1Red = p1.close < p1.open
            val p1Green = p1.close > p1.open
            val p2Body = abs(p2.close - p2.open)
            val p2Red = p2.close < p2.open
            val p2Green = p2.close > p2.open

            if (p2Red && p2Body > 0 && p1Body < p2Body * 0.5 && isGreen) {
                val p2Mid = (p2.open + p2.close) / 2
                if (c.close > p2Mid) isMorningStar = true
            }
            if (p2Green && p2Body > 0 && p1Body < p2Body * 0.5 && isRed) {
                val p2Mid = (p2.open + p2.close) / 2
                if (c.close < p2Mid) isEveningStar = true
            }
            if (p2Green && p1Green && isGreen && c.close > p1.close && p1.close > p2.close) {
                isThreeWhiteSoldiers = true
            }
            if (p2Red && p1Red && isRed && c.close < p1.close && p1.close < p2.close) {
                isThreeBlackCrows = true
            }
            if (p2Red && p2Body > p1Body * 1.5 && p1Green && isGreen && c.close > p2.open) {
                isThreeInsideUp = true
            }
            if (p2Green && p2Body > p1Body * 1.5 && p1Red && isRed && c.close < p2.open) {
                isThreeInsideDown = true
            }
            if (p2Red && p1Green && p1Body > p2Body && p1.close >= p2.open && p1.open <= p2.close && isGreen && c.close > p1.close) {
                isThreeOutsideUp = true
            }
            if (p2Green && p1Red && p1Body > p2Body && p1.close <= p2.open && p1.open >= p2.close && isRed && c.close < p1.close) {
                isThreeOutsideDown = true
            }
        }

        val bearishPattern = when {
            isEveningStar -> "Evening Star"
            isThreeBlackCrows -> "Three Black Crows"
            isThreeInsideDown -> "Three Inside Down"
            isThreeOutsideDown -> "Three Outside Down"
            isBearishEngulfing -> "Bearish Engulfing"
            isDarkCloudCover -> "Dark Cloud Cover"
            isTweezerTop -> "Tweezer Top"
            isBearishHarami -> "Bearish Harami"
            isShootingStar -> "Shooting Star"
            isHangingMan -> "Hanging Man"
            isGravestoneDoji -> "Gravestone Doji"
            else -> ""
        }

        val bullishPattern = when {
            isMorningStar -> "Morning Star"
            isThreeWhiteSoldiers -> "Three White Soldiers"
            isThreeInsideUp -> "Three Inside Up"
            isThreeOutsideUp -> "Three Outside Up"
            isBullishEngulfing -> "Bullish Engulfing"
            isPiercingLine -> "Piercing Line"
            isTweezerBottom -> "Tweezer Bottom"
            isBullishHarami -> "Bullish Harami"
            isHammer -> "Hammer"
            isInvertedHammer -> "Inverted Hammer"
            isDragonflyDoji -> "Dragonfly Doji"
            else -> ""
        }

        val shortsBurned = !liq.dominantIsLong && liq.shortPct >= 70.0
        val longsBurned = liq.dominantIsLong && liq.longPct >= 70.0

        val baseType = when {
            shortsBurned && bearishPattern.isNotEmpty() -> "short"
            longsBurned && bullishPattern.isNotEmpty() -> "long"
            else -> return SignalResult("", 0L, "")
        }

        val pattern = if (baseType == "short") bearishPattern else bullishPattern

        val avgVol20 = candles.subList(candles.size - 22, candles.size - 2)
            .map { it.volume }
            .average()
        val volOk = avgVol20 > 0.0 && (c.volume / avgVol20) >= 2.0

        val recent = candles.subList(candles.size - 20, candles.size)
        val rangeHigh = recent.maxOf { it.high }
        val rangeLow = recent.minOf { it.low }
        val range = rangeHigh - rangeLow
        val posOk: Boolean
        if (range <= 0.0) {
            posOk = false
        } else {
            val position = (candles.last().close - rangeLow) / range
            posOk = if (baseType == "short") position >= 0.70 else position <= 0.30
        }

        val strength = if (volOk && posOk) "strong" else "weak"
        return SignalResult(baseType, c.time, strength, pattern)
    }

    // ================== Peak forecast ==================

    fun computePeakForecast(
        currentPrice: Double,
        isShort: Boolean,
        events: List<LiqEvent>
    ): PeakForecast {
        if (currentPrice <= 0) return PeakForecast.empty()
        if (events.isEmpty()) return PeakForecast.empty()

        val relevant = if (isShort) {
            events.filter { !it.isLong && it.price > currentPrice * 0.998 }
        } else {
            events.filter { it.isLong && it.price < currentPrice * 1.002 }
        }
        if (relevant.isEmpty()) return PeakForecast.empty()

        val binSize = currentPrice * 0.001
        if (binSize <= 0.0) return PeakForecast.empty()

        val bins = HashMap<Long, Double>()
        for (e in relevant) {
            val idx = (e.price / binSize).toLong()
            bins[idx] = (bins[idx] ?: 0.0) + e.usd
        }
        val total = bins.values.sum()
        if (total < 1000.0) return PeakForecast.empty()

        val topBin = bins.maxByOrNull { it.value } ?: return PeakForecast.empty()
        val peakMid = (topBin.key + 0.5) * binSize

        val threshold = topBin.value * 0.25
        val allIdx = bins.keys.sorted()
        val topPos = allIdx.indexOf(topBin.key)
        if (topPos < 0) return PeakForecast.empty()

        var lowPos = topPos
        var highPos = topPos
        var i = topPos
        while (i > 0 && (bins[allIdx[i - 1]] ?: 0.0) >= threshold) { i--; lowPos = i }
        i = topPos
        while (i < allIdx.size - 1 && (bins[allIdx[i + 1]] ?: 0.0) >= threshold) { i++; highPos = i }

        val peakLow = allIdx[lowPos] * binSize
        val peakHigh = (allIdx[highPos] + 1) * binSize
        val rangePct = if (peakMid > 0) ((peakHigh - peakLow) / peakMid) * 100 else 100.0
        val confident = rangePct <= 2.0

        val remain = if (isShort) (peakMid - currentPrice) / currentPrice * 100
                     else (currentPrice - peakMid) / currentPrice * 100

        if (remain < 0) return PeakForecast.empty()

        return PeakForecast(peakMid, peakLow, peakHigh, remain, confident)
    }

    // ================== Accumulation detection ==================

    /**
     * Ищет накопление позиции за последние 45 минут (9 × M5-баров, склеенных из M1).
     *
     * @param m1Candles  M1-свечи (нужно ≥ 45)
     * @param oiHistory  OI за 9 закрытых M5-баров (от старых к новым)
     * @param deltaBuckets  buy-sell USD за 9 закрытых M5-баров (от старых к новым)
     * @return AccumulationInfo или null, если нет сигнала
     */
    fun detectAccumulation(
        m1Candles: List<Candle>,
        oiHistory: List<Double>,
        deltaBuckets: List<Double>,
        windowMinutes: Int = 45
    ): AccumulationInfo? {
        val barsNeeded = (windowMinutes / 5).coerceAtLeast(3)
        val totalM1Needed = barsNeeded * 5

        if (m1Candles.size < totalM1Needed) return null
        if (oiHistory.size < barsNeeded) return null
        if (deltaBuckets.size < barsNeeded) return null

        val tf = m1Candles[m1Candles.size - 1].time - m1Candles[m1Candles.size - 2].time
        if (tf !in 55_000..65_000) return null

        val start = m1Candles.size - totalM1Needed
        val m5 = ArrayList<Candle>(barsNeeded)
        for (i in 0 until barsNeeded) {
            val from = start + i * 5
            val to = from + 5
            val chunk = m1Candles.subList(from, to)
            m5.add(Candle(
                time = chunk.first().time,
                open = chunk.first().open,
                high = chunk.maxOf { it.high },
                low = chunk.minOf { it.low },
                close = chunk.last().close,
                volume = chunk.sumOf { it.volume }
            ))
        }

        val maxHigh = m5.maxOf { it.high }
        val minLow = m5.minOf { it.low }
        val lastClose = m5.last().close
        if (lastClose <= 0) return null
        val rangePct = (maxHigh - minLow) / lastClose * 100

        val recentOi = oiHistory.takeLast(barsNeeded)
        val oiStart = recentOi.first()
        val oiEnd = recentOi.last()
        if (oiStart <= 0 || oiEnd <= 0) return null
        val oiChangePct = (oiEnd - oiStart) / oiStart * 100
        if (oiChangePct < 1.0) return null

        var barsPositive = 0
        for (i in 1 until recentOi.size) {
            if (recentOi[i] > recentOi[i - 1]) barsPositive++
        }

        val recentDelta = deltaBuckets.takeLast(barsNeeded)
        val deltaSum = recentDelta.sum()
        val absDelta = abs(deltaSum)

        val mult = windowMinutes / 45.0
        val reqBarsWeak = (barsNeeded * 0.5).toInt().coerceAtLeast(1)
        val reqBarsMed = (barsNeeded * 0.7).toInt().coerceAtLeast(2)
        val reqBarsStrong = (barsNeeded * 0.9).toInt().coerceAtLeast(3)

        val level = when {
            oiChangePct >= 3.5 * mult && barsPositive >= reqBarsStrong && rangePct < 0.8 && absDelta > 300_000 * mult -> "strong"
            oiChangePct >= 2.0 * mult && barsPositive >= reqBarsMed && rangePct < 1.0 && absDelta > 150_000 * mult -> "medium"
            oiChangePct >= 1.0 * mult && barsPositive >= reqBarsWeak && rangePct < 1.2 && absDelta > 50_000 * mult -> "weak"
            else -> return null
        }

        val isLong = deltaSum > 0
        return AccumulationInfo(
            type = if (isLong) "long" else "short",
            level = level,
            oiChangePct = oiChangePct,
            oiBarsPositive = barsPositive,
            rangePct = rangePct,
            deltaUsd = deltaSum,
            priceLow = minLow,
            priceHigh = maxHigh
        )
    }

    fun detectMacroAccumulation(
        m15Candles: List<Candle>,
        oiHistory: List<Double>,
        deltaBuckets: List<Double>,
        windowHours: Int = 4
    ): AccumulationInfo? {
        val barsNeeded = (windowHours * 60 / 15).coerceAtLeast(3)
        if (m15Candles.size < barsNeeded) return null
        if (oiHistory.size < barsNeeded) return null
        if (deltaBuckets.size < barsNeeded) return null

        val recentM15 = m15Candles.takeLast(barsNeeded)

        val maxHigh = recentM15.maxOf { it.high }
        val minLow = recentM15.minOf { it.low }
        val lastClose = recentM15.last().close
        if (lastClose <= 0) return null
        val rangePct = (maxHigh - minLow) / lastClose * 100

        val recentOi = oiHistory.takeLast(barsNeeded)
        val oiStart = recentOi.first()
        val oiEnd = recentOi.last()
        if (oiStart <= 0 || oiEnd <= 0) return null
        val oiChangePct = (oiEnd - oiStart) / oiStart * 100
        if (oiChangePct < 1.0) return null

        var barsPositive = 0
        for (i in 1 until recentOi.size) {
            if (recentOi[i] > recentOi[i - 1]) barsPositive++
        }

        val recentDelta = deltaBuckets.takeLast(barsNeeded)
        val deltaSum = recentDelta.sum()
        val absDelta = abs(deltaSum)

        val mult = windowHours / 4.0
        val reqBarsWeak = (barsNeeded * 0.5).toInt().coerceAtLeast(1)
        val reqBarsMed = (barsNeeded * 0.7).toInt().coerceAtLeast(2)
        val reqBarsStrong = (barsNeeded * 0.9).toInt().coerceAtLeast(3)

        val level = when {
            oiChangePct >= 3.5 * mult && barsPositive >= reqBarsStrong && rangePct < 2.0 && absDelta > 300_000 * mult -> "strong"
            oiChangePct >= 2.0 * mult && barsPositive >= reqBarsMed && rangePct < 3.0 && absDelta > 150_000 * mult -> "medium"
            oiChangePct >= 1.0 * mult && barsPositive >= reqBarsWeak && rangePct < 4.0 && absDelta > 50_000 * mult -> "weak"
            else -> return null
        }

        val isLong = deltaSum > 0
        return AccumulationInfo(
            type = if (isLong) "long" else "short",
            level = level,
            oiChangePct = oiChangePct,
            oiBarsPositive = barsPositive,
            rangePct = rangePct,
            deltaUsd = deltaSum,
            priceLow = minLow,
            priceHigh = maxHigh
        )
    }
}