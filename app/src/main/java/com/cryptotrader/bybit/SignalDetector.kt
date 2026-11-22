package com.cryptotrader.bybit

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object SignalDetector {

    // ================== Confirmed liquidity trap ==================
    // Uses CLOSED candles only. The previous candle sweeps a local extreme;
    // the latest closed candle must reclaim it. No candlestick-pattern fallback.
    fun detectReversalSignal(
        candles: List<Candle>,
        liq: LiqStats?,
        liqThreshold: Double
    ): SignalResult {
        if (candles.size < 25 || liq == null || liq.total15m < liqThreshold) {
            return SignalResult("", 0L, "")
        }
        val confirm = candles[candles.size - 2]
        val sweep = candles[candles.size - 3]
        val history = candles.subList(candles.size - 23, candles.size - 3)
        val support = history.minOf { it.low }
        val resistance = history.maxOf { it.high }
        val avgVolume = history.map { it.volume }.average()
        if (support <= 0.0 || resistance <= support || avgVolume <= 0.0) {
            return SignalResult("", 0L, "")
        }

        // Reject tiny, noise-like breaks and weak participation.
        val minBreak = 0.0005
        val sweepVolumeOk = sweep.volume >= avgVolume * 1.5
        val confirmVolumeOk = confirm.volume >= avgVolume * 1.1
        val sweepRange = sweep.high - sweep.low
        if (sweepRange <= 0.0 || !sweepVolumeOk || !confirmVolumeOk) {
            return SignalResult("", 0L, "")
        }

        // Reclaim + follow-through, not just a wick or a doji.
        val longTrap = liq.dominantIsLong && liq.longPct >= 60.0 &&
            sweep.low < support * (1.0 - minBreak) &&
            sweep.close > sweep.low + sweepRange * 0.45 &&
            confirm.close > support && confirm.close > sweep.close &&
            confirm.close > confirm.open

        val shortTrap = !liq.dominantIsLong && liq.shortPct >= 60.0 &&
            sweep.high > resistance * (1.0 + minBreak) &&
            sweep.close < sweep.high - sweepRange * 0.45 &&
            confirm.close < resistance && confirm.close < sweep.close &&
            confirm.close < confirm.open

        val type = when {
            longTrap -> "long"
            shortTrap -> "short"
            else -> return SignalResult("", 0L, "")
        }
        val pattern = if (type == "long") "Liquidity Trap: sell-side sweep & reclaim"
                      else "Liquidity Trap: buy-side sweep & reclaim"
        // A confirmed trap is the only actionable signal; no weak legacy signals.
        return SignalResult(type, confirm.time, "strong", pattern)
    }


    /**
     * Require independent positioning and order-flow evidence before publishing a trap.
     * OI change is calculated from the latest two 5-minute samples.
     * Delta is signed buyer-initiated minus seller-initiated trade notional,
     * normalized by total notional in the recent-trade snapshot.
     */
    fun confirmTrap(
        candidate: SignalResult,
        oiHistory: List<Double>?,
        aggressiveDeltaRatio: Double?
    ): SignalResult {
        if (candidate.type.isEmpty() || oiHistory == null ||
            oiHistory.size < 2 || aggressiveDeltaRatio == null) {
            return SignalResult("", 0L, "")
        }
        val previousOi = oiHistory[oiHistory.size - 2]
        val currentOi = oiHistory.last()
        if (previousOi <= 0.0 || currentOi <= 0.0) return SignalResult("", 0L, "")
        val oiChangePct = (currentOi - previousOi) / previousOi * 100.0
        // Rising OI indicates fresh positioning into the failed break.
        // Opposite-side aggressive flow suggests absorption rather than mere exhaustion.
        if (oiChangePct < 0.15) return SignalResult("", 0L, "")
        val absorbed = when (candidate.type) {
            "long" -> aggressiveDeltaRatio <= -0.10
            "short" -> aggressiveDeltaRatio >= 0.10
            else -> false
        }
        return if (absorbed) candidate.copy(
            pattern = candidate.pattern + " · OI & trade-flow absorption"
        ) else SignalResult("", 0L, "")
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