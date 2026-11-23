package com.cryptotrader.bybit

import java.util.TreeMap

/**
 * Хранит:
 * 1) Сырые сделки за последние 2 минуты — для computeDelta (1-мин дельта в карточке).
 * 2) Агрегацию по 5-минутным бакетам за последние 50 минут — для getDeltaBuckets
 *    (нужно для сигнала «Накопление»: сумма buy-sell за последние 9 бакетов = 45 минут).
 * Потокобезопасен.
 */
class TradeDeltaAnalyzer {

    // ===== Для 1-минутной дельты (существующая логика) =====
    private data class TradeEntry(val time: Long, val usd: Double, val isBuy: Boolean)
    private val _rawLock = Any()
    private val _rawBuffers = HashMap<String, MutableList<TradeEntry>>()

    // ===== Для 45-минутной агрегации (новое) =====
    private class Bucket(
        val bucketStart: Long,
        var buyUsd: Double = 0.0,
        var sellUsd: Double = 0.0
    )
    private val _bucketLock = Any()
    private val _buckets = HashMap<String, TreeMap<Long, Bucket>>()

    private val BUCKET_MS = 5 * 60 * 1000L
    private val BUCKET_KEEP_MS = 65 * 60 * 1000L

    fun addTrade(symbol: String, time: Long, usd: Double, isBuy: Boolean) {
        // --- Сырой буфер для 1-мин дельты ---
        synchronized(_rawLock) {
            val buf = _rawBuffers.getOrPut(symbol) { ArrayList() }
            buf.add(TradeEntry(time, usd, isBuy))
            val cutoff = System.currentTimeMillis() - 2 * 60 * 1000L
            buf.removeAll { it.time < cutoff }
            while (buf.size > 5000) buf.removeAt(0)
        }

        // --- Бакеты для 45-мин агрегации ---
        val bucketStart = (time / BUCKET_MS) * BUCKET_MS
        synchronized(_bucketLock) {
            val map = _buckets.getOrPut(symbol) { TreeMap() }
            val bucket = map.getOrPut(bucketStart) { Bucket(bucketStart) }
            if (isBuy) bucket.buyUsd += usd else bucket.sellUsd += usd

            val cutoff = System.currentTimeMillis() - BUCKET_KEEP_MS
            while (map.isNotEmpty() && map.firstKey() < cutoff) {
                map.remove(map.firstKey())
            }
        }
    }

    /**
     * (buyUsd − sellUsd) за последние windowMs миллисекунд.
     * Положительное = покупатели перевесили, отрицательное = продавцы.
     * Для 1-мин дельты используется сырой буфер (точность).
     */
    fun computeDelta(symbol: String, windowMs: Long = 60_000L): Double {
        val now = System.currentTimeMillis()
        val cutoff = now - windowMs
        val snapshot = synchronized(_rawLock) {
            _rawBuffers[symbol]?.filter { it.time >= cutoff } ?: return 0.0
        }
        if (snapshot.isEmpty()) return 0.0

        var buyUsd = 0.0
        var sellUsd = 0.0
        for (t in snapshot) {
            if (t.isBuy) buyUsd += t.usd else sellUsd += t.usd
        }
        return buyUsd - sellUsd
    }

    /**
     * Последние N *закрытых* 5-минутных бакетов (buy - sell), от старых к новым.
     * Текущий (незакрытый) бакет НЕ включается — только полностью сформировавшиеся.
     * Отсутствующие бакеты = 0.0.
     */
    fun getDeltaBuckets(symbol: String, count: Int = 9): List<Double> {
        val now = System.currentTimeMillis()
        val currentBucket = (now / BUCKET_MS) * BUCKET_MS
        val out = ArrayList<Double>(count)
        synchronized(_bucketLock) {
            val map = _buckets[symbol]
            for (i in count - 1 downTo 0) {
                val start = currentBucket - (i + 1) * BUCKET_MS
                val b = map?.get(start)
                out.add(if (b != null) b.buyUsd - b.sellUsd else 0.0)
            }
        }
        return out
    }

    /**
     * Кумулятивная дельта за последние N минут (кратно 5).
     * Сумма (buy - sell) по бакетам.
     */
    fun getCvd(symbol: String, minutes: Int = 60): Double {
        val count = (minutes / 5).coerceAtLeast(1)
        return getDeltaBuckets(symbol, count).sum()
    }

    /**
     * Счётчик толчков: сколько бакетов за N минут превысили порог.
     * Возвращает Pair(вверх, вниз).
     */
    fun getBursts(symbol: String, minutes: Int = 60, threshold: Double): Pair<Int, Int> {
        val count = (minutes / 5).coerceAtLeast(1)
        val buckets = getDeltaBuckets(symbol, count)
        var up = 0
        var down = 0
        for (b in buckets) {
            if (b >= threshold) up++
            else if (b <= -threshold) down++
        }
        return up to down
    }

    /**
     * Closed M1 trade-flow snapshot aligned to the signal confirmation candle.
     * Never uses trades from after the signal was formed.
     * Null means insufficient live trade history (e.g. immediately after startup).
     */
    fun closedMinuteFlow(symbol: String, candleStartMs: Long): Pair<Double, Double>? {
        val previousStart = candleStartMs - 60_000L
        val end = candleStartMs + 60_000L
        val now = System.currentTimeMillis()
        if (now < end || now - previousStart > 120_000L) return null
        val entries = synchronized(_rawLock) { _rawBuffers[symbol]?.toList() ?: return null }
        val previous = entries.filter { it.time >= previousStart && it.time < candleStartMs }
        val current = entries.filter { it.time >= candleStartMs && it.time < end }
        if (previous.isEmpty() || current.isEmpty()) return null
        fun delta(list: List<TradeEntry>): Double =
            list.sumOf { if (it.isBuy) it.usd else -it.usd }
        return delta(current) to (delta(current) - delta(previous))
    }

    fun clear() {
        synchronized(_rawLock) { _rawBuffers.clear() }
        synchronized(_bucketLock) { _buckets.clear() }
    }
}