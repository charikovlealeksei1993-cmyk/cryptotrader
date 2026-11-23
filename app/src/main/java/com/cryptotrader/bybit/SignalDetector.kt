package com.cryptotrader.bybit

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object SignalDetector {

    // M1 patterns: evaluate only closed bars; the last bar is assumed to be forming.
    // A pattern is published after the NEXT candle has closed.
    fun detectReversalSignal(
        candles: List<Candle>,
        liq: LiqStats?,
        liqThreshold: Double,
        flowDeltaUsd: Double? = null,
        cvdTrendUsd: Double? = null,
        turnoverAcceleration: Double? = null,
        adaptiveVolumeRatio: Double? = null,
        timeframeMinutes: Int = 1
    ): SignalResult {
        if (candles.size < 8) return SignalResult("", 0L, "")
        val confirmation = candles[candles.size - 2]
        if (confirmation.time + timeframeMinutes.coerceAtLeast(1) * 60_000L > System.currentTimeMillis())
            return SignalResult("", 0L, "")
        val p = candles[candles.size - 3] // pattern candle
        val c = candles[candles.size - 2] // confirmation candle
        val prev = candles[candles.size - 4]
        val older = candles[candles.size - 5]
        val range = p.high - p.low
        if (range <= 0.0 || p.close <= 0.0) return SignalResult("", 0L, "")
        val body = abs(p.close - p.open)
        val upper = p.high - max(p.open, p.close)
        val lower = min(p.open, p.close) - p.low
        val down = prev.close < older.close
        val up = prev.close > older.close
        val bullEngulf = prev.close < prev.open && p.close > p.open &&
            p.open <= prev.close && p.close >= prev.open
        val bearEngulf = prev.close > prev.open && p.close < p.open &&
            p.open >= prev.close && p.close <= prev.open
        val hammer = lower >= body * 2 && upper <= max(body, range * 0.12)
        val star = upper >= body * 2 && lower <= max(body, range * 0.12)
        val morning = older.close < older.open &&
            abs(prev.close - prev.open) < abs(older.close - older.open) * 0.5 &&
            p.close > p.open && p.close > (older.open + older.close) / 2
        val evening = older.close > older.open &&
            abs(prev.close - prev.open) < abs(older.close - older.open) * 0.5 &&
            p.close < p.open && p.close < (older.open + older.close) / 2
        val inside = p.high < prev.high && p.low > prev.low
        val outside = p.high > prev.high && p.low < prev.low
        val bullHarami = prev.close < prev.open && p.close > p.open &&
            p.open > prev.close && p.close < prev.open
        val bearHarami = prev.close > prev.open && p.close < p.open &&
            p.open < prev.close && p.close > prev.open
        val tweezerBottom = down && p.close > p.open &&
            abs(p.low - prev.low) <= range * 0.1
        val tweezerTop = up && p.close < p.open &&
            abs(p.high - prev.high) <= range * 0.1
        val dragonfly = down && body <= range * 0.1 && lower > range * 0.6
        val gravestone = up && body <= range * 0.1 && upper > range * 0.6
        val longName = when {
            bullEngulf -> "Бычье поглощение"
            bullHarami -> "Бычье харами"
            tweezerBottom -> "Пинцет: дно"
            dragonfly -> "Доджи-стрекоза"
            down && inside && p.close > p.open -> "Бычий внутренний бар"
            down && outside && p.close > p.open -> "Бычий внешний бар"
            morning -> "Утренняя звезда"
            down && hammer -> "Молот"
            down && p.close > p.open && lower > body && p.close > (prev.open + prev.close) / 2 -> "Просвет в облаках"
            else -> ""
        }
        val shortName = when {
            bearEngulf -> "Медвежье поглощение"
            bearHarami -> "Медвежье харами"
            tweezerTop -> "Пинцет: вершина"
            gravestone -> "Доджи-надгробие"
            up && inside && p.close < p.open -> "Медвежий внутренний бар"
            up && outside && p.close < p.open -> "Медвежий внешний бар"
            evening -> "Вечерняя звезда"
            up && star -> "Падающая звезда"
            up && p.close < p.open && upper > body && p.close < (prev.open + prev.close) / 2 -> "Завеса из тёмных облаков"
            else -> ""
        }
        val type = when {
            longName.isNotEmpty() -> "long"
            shortName.isNotEmpty() -> "short"
            else -> return SignalResult("", 0L, "")
        }
        val confirmed = if (type == "long") c.close > c.open && c.close > p.close
                        else c.close < c.open && c.close < p.close
        val avgVolume = candles.subList(candles.size - 8, candles.size - 3)
            .map { it.volume }.average().coerceAtLeast(0.000001)
        var score = 30
        if (confirmed) score += 25
        if (p.volume >= avgVolume * 1.5) score += 15
        if (c.volume >= avgVolume * 1.3) score += 10
        val directionalDelta = if (type == "long") flowDeltaUsd else flowDeltaUsd?.let { -it }
        val directionalCvd = if (type == "long") cvdTrendUsd else cvdTrendUsd?.let { -it }
        // Delta and CVD derive from the same trade stream: one combined flow vote.
        if ((directionalDelta ?: 0.0) > 0.0 && (directionalCvd ?: 0.0) > 0.0) score += 10
        else if ((directionalDelta ?: 0.0) > 0.0 || (directionalCvd ?: 0.0) > 0.0) score += 5
        // Activity speed and adaptive notional volume are correlated; cap their vote.
        if ((turnoverAcceleration ?: 0.0) >= 2.0 ||
            (adaptiveVolumeRatio ?: 0.0) >= 1.8) score += 10
        if (liq != null && liq.total15m >= liqThreshold &&
            ((type == "long" && liq.dominantIsLong) ||
             (type == "short" && !liq.dominantIsLong))) score += 20
        score = score.coerceIn(0, 100)
        val strength = when {
            score >= 85 -> "strong"
            score >= 70 -> "medium"
            else -> "weak"
        }
        val name = if (type == "long") longName else shortName
        return SignalResult(type, c.time, strength,
            "$name · $score/100 · " + (if (confirmed) "подтверждён" else "без подтверждения"))
    }

    // Kept for compatibility with existing call sites; a candle pattern does not
    // require liquidation, OI, or aggressive delta to be displayed.
    fun confirmTrap(
        candidate: SignalResult,
        oiHistory: List<Double>?,
        aggressiveDeltaRatio: Double?
    ): SignalResult = candidate

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