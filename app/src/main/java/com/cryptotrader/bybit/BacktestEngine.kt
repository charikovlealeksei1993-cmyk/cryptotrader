package com.cryptotrader.bybit

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class BacktestEngine(
    private val onProgress: (String) -> Unit = {}
) {

    private val slPercent = 2.0
    private val tpPercent = 5.0
    private val maxHoldMinutes = 15
    private val pseudoMoveThreshold = 0.4
    private val pseudoVolThreshold = 2.5

    fun run(symbol: String, tf: String, days: Int, onlyPattern: String? = null): BacktestReport {
        try {
            onProgress(if (onlyPattern.isNullOrEmpty()) "Загружаю свечи..." else "Загружаю свечи (фильтр: $onlyPattern)...")
            val tfMs = tfToMs(tf)
            if (tfMs <= 0) {
                return BacktestReport(symbol, tf, days, 0, emptyList(), "Неверный TF")
            }
            val totalCandles = ((days * 24L * 60L * 60L * 1000L) / tfMs).toInt().coerceIn(100, 25000)
            val endMs = System.currentTimeMillis()

            val candles = fetchKlinesRange(symbol, tf, totalCandles, endMs, onProgress)
            if (candles.size < 60) {
                return BacktestReport(symbol, tf, days, candles.size, emptyList(),
                    "Мало данных: ${candles.size} свечей")
            }

            onProgress("Анализирую ${candles.size} свечей...")

            val bars15m = (15L * 60_000L / tfMs).toInt().coerceAtLeast(1)
            val bars1h = (60L * 60_000L / tfMs).toInt().coerceAtLeast(1)
            val bars4h = (4L * 60L * 60_000L / tfMs).toInt().coerceAtLeast(1)
            val maxHoldBars = (maxHoldMinutes.toLong() * 60_000L / tfMs).toInt().coerceAtLeast(1)
            val maxHold = maxOf(bars15m, maxOf(bars1h, bars4h))

            val signals = ArrayList<BacktestSignal>()
            val lastSignalBar = candles.size - maxHold - 1

            var scanned = 0
            val scanStep = 500

            for (i in 21..lastSignalBar) {
                if (i < 21) continue
                val sig = checkSignalAt(candles, i, tfMs) ?: continue
                if (!onlyPattern.isNullOrEmpty() && sig.pattern != onlyPattern) continue

                val type = sig.type
                val strength = sig.strength
                val hasCascade = sig.hasCascade

                val entry = candles[i].close
                val isLong = type == "long"

                val pnl15 = if (i + bars15m < candles.size)
                    pnlPct(entry, candles[i + bars15m].close, isLong) else null
                val pnl1h = if (i + bars1h < candles.size)
                    pnlPct(entry, candles[i + bars1h].close, isLong) else null
                val pnl4h = if (i + bars4h < candles.size)
                    pnlPct(entry, candles[i + bars4h].close, isLong) else null

                val slTp = simulateSlTp(candles, i, isLong, entry, maxHoldBars, tfMs)

                signals.add(BacktestSignal(
                    time = candles[i].time,
                    type = type,
                    strength = strength,
                    entryPrice = entry,
                    pattern = sig.pattern,
                    pnl15m = pnl15,
                    pnl1h = pnl1h,
                    pnl4h = pnl4h,
                    hasPseudoCascade = hasCascade,
                    pnlWithSlTp = slTp?.first,
                    exitReason = slTp?.second ?: "",
                    exitBars = slTp?.third ?: 0
                ))

                scanned++
                if (scanned % scanStep == 0) {
                    onProgress("Найдено сигналов: ${signals.size}...")
                }
            }

            onProgress("Готово: ${signals.size} сигналов")
            return BacktestReport(symbol, tf, days, candles.size, signals, null)

        } catch (e: Exception) {
            return BacktestReport(symbol, tf, days, 0, emptyList(), "Ошибка: ${e.message?.take(120)}")
        }
    }

    private data class SignalCheck(
        val type: String,
        val strength: String,
        val hasCascade: Boolean,
        val pattern: String
    )

    private fun checkSignalAt(candles: List<Candle>, i: Int, tfMs: Long): SignalCheck? {
        if (i < 21 || i >= candles.size) return null
        val c = candles[i]
        val p1 = if (i >= 1) candles[i - 1] else null
        val p2 = if (i >= 2) candles[i - 2] else null

        val body = abs(c.close - c.open)
        val upperWick = c.high - max(c.open, c.close)
        val lowerWick = min(c.open, c.close) - c.low
        val totalHeight = c.high - c.low
        if (totalHeight <= 0.0 || body <= 0.0) return null

        val isRed = c.close < c.open
        val isGreen = c.close > c.open

        // Одиночные
        val isShootingStar = isRed && upperWick >= body * 2.0 && upperWick >= totalHeight * 0.5
        val isHammer = isGreen && lowerWick >= body * 2.0 && lowerWick >= totalHeight * 0.5
        val isInvertedHammer = isGreen && upperWick >= body * 2.0 && upperWick >= totalHeight * 0.5
        val isHangingMan = isRed && lowerWick >= body * 2.0 && lowerWick >= totalHeight * 0.5
        val isDragonflyDoji = body <= totalHeight * 0.1 && lowerWick >= totalHeight * 0.6 && upperWick < totalHeight * 0.2
        val isGravestoneDoji = body <= totalHeight * 0.1 && upperWick >= totalHeight * 0.6 && lowerWick < totalHeight * 0.2

        // Двойные
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

        // Тройные
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

        // Приоритет: тройные > двойные > одиночные
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

        val baseType = when {
            bearishPattern.isNotEmpty() -> "short"
            bullishPattern.isNotEmpty() -> "long"
            else -> return null
        }

        val pattern = if (baseType == "short") bearishPattern else bullishPattern

        val recent = candles.subList(i - 19, i + 1)
        val rangeHigh = recent.maxOf { it.high }
        val rangeLow = recent.minOf { it.low }
        val range = rangeHigh - rangeLow
        if (range <= 0.0) return null

        val position = (c.close - rangeLow) / range
        val posOk = if (baseType == "short") position >= 0.70 else position <= 0.30

        val avgVol = candles.subList(i - 20, i).map { it.volume }.average()
        val volOk = avgVol > 0.0 && (c.volume / avgVol) >= 1.5

        val hasCascade = detectPseudoCascade(candles, i, tfMs)

        val strength = if (volOk && posOk) "strong" else "weak"
        return SignalCheck(baseType, strength, hasCascade, pattern)
    }

    private fun detectPseudoCascade(candles: List<Candle>, i: Int, tfMs: Long): Boolean {
        val bars15m = (15L * 60_000L / tfMs).toInt().coerceAtLeast(3)
        val from = max(21, i - bars15m)
        if (from >= i) return false

        var count = 0
        for (j in from until i) {
            val c = candles[j]
            val movePct = if (c.open > 0) abs((c.close - c.open) / c.open * 100.0) else 0.0
            if (movePct < pseudoMoveThreshold) continue

            val avgVol = candles.subList(max(0, j - 20), j).map { it.volume }.average()
            val volRatio = if (avgVol > 0) c.volume / avgVol else 0.0
            if (volRatio >= pseudoVolThreshold) count++
        }
        return count >= 2
    }

    private fun simulateSlTp(
        candles: List<Candle>,
        entryBar: Int,
        isLong: Boolean,
        entryPrice: Double,
        maxHoldBars: Int,
        tfMs: Long
    ): Triple<Double, String, Int>? {
        if (entryPrice <= 0) return null
        val slPrice = if (isLong) entryPrice * (1 - slPercent / 100)
                      else entryPrice * (1 + slPercent / 100)
        val tpPrice = if (isLong) entryPrice * (1 + tpPercent / 100)
                      else entryPrice * (1 - tpPercent / 100)

        val endBar = min(entryBar + maxHoldBars, candles.size - 1)
        for (j in (entryBar + 1)..endBar) {
            val c = candles[j]
            if (isLong) {
                val slHit = c.low <= slPrice
                val tpHit = c.high >= tpPrice
                if (slHit && tpHit) return Triple(-slPercent, "sl", j - entryBar)
                if (slHit) return Triple(-slPercent, "sl", j - entryBar)
                if (tpHit) return Triple(tpPercent, "tp", j - entryBar)
            } else {
                val slHit = c.high >= slPrice
                val tpHit = c.low <= tpPrice
                if (slHit && tpHit) return Triple(-slPercent, "sl", j - entryBar)
                if (slHit) return Triple(-slPercent, "sl", j - entryBar)
                if (tpHit) return Triple(tpPercent, "tp", j - entryBar)
            }
        }

        val lastBar = candles[endBar]
        val pnl = pnlPct(entryPrice, lastBar.close, isLong)
        return Triple(pnl, "timeout", endBar - entryBar)
    }

    private fun pnlPct(entry: Double, exit: Double, isLong: Boolean): Double {
        if (entry <= 0) return 0.0
        val diff = if (isLong) exit - entry else entry - exit
        return diff / entry * 100
    }

    private fun fetchKlinesRange(
        symbol: String,
        tf: String,
        total: Int,
        endMs: Long,
        progress: (String) -> Unit
    ): List<Candle> {
        val out = ArrayList<Candle>(total)
        var end = endMs
        var iter = 0
        val maxIter = (total / 1000) + 3
        while (out.size < total && iter < maxIter) {
            iter++
            val limit = min(1000, total - out.size)
            progress("Загружаю: ${out.size}/$total...")

            val url = "https://api.bybit.com/v5/market/kline?category=linear&symbol=$symbol&interval=$tf&limit=$limit&end=$end"
            val body = httpGet(url)
            val json = JSONObject(body)
            val list = json.getJSONObject("result").getJSONArray("list")
            if (list.length() == 0) break

            val chunk = ArrayList<Candle>(list.length())
            for (i in 0 until list.length()) {
                val a = list.getJSONArray(i)
                chunk.add(Candle(
                    a.getString(0).toLong(),
                    a.getString(1).toDouble(),
                    a.getString(2).toDouble(),
                    a.getString(3).toDouble(),
                    a.getString(4).toDouble(),
                    a.getString(5).toDouble()
                ))
            }
            chunk.reverse()
            out.addAll(0, chunk)

            val oldest = chunk.firstOrNull()?.time ?: break
            if (oldest <= 0) break
            end = oldest - 1

            if (chunk.size < limit) break
        }
        return out
    }

    private fun httpGet(urlStr: String, connectMs: Int = 10000, readMs: Int = 20000): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = connectMs
        conn.readTimeout = readMs
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        conn.setRequestProperty("Referer", "https://www.bybit.com")
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                throw RuntimeException("HTTP $code: ${err.take(200)}")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun tfToMs(tf: String): Long = when (tf) {
        "1" -> 60_000L
        "5" -> 5 * 60_000L
        "15" -> 15 * 60_000L
        "60" -> 60 * 60_000L
        else -> 0L
    }
}