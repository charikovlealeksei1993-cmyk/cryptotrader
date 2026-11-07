package com.cryptotrader.bybit

/**
 * Хранит буферы сделок per-symbol (с полем isBuy) и считает реальную дельту в $.
 * Потокобезопасен.
 */
class TradeDeltaAnalyzer {
    private data class TradeEntry(val time: Long, val usd: Double, val isBuy: Boolean)

    private val _lock = Any()
    private val _buffers = HashMap<String, MutableList<TradeEntry>>()

    fun addTrade(symbol: String, time: Long, usd: Double, isBuy: Boolean) {
        synchronized(_lock) {
            val buf = _buffers.getOrPut(symbol) { ArrayList() }
            buf.add(TradeEntry(time, usd, isBuy))
            val cutoff = System.currentTimeMillis() - 2 * 60 * 1000L
            buf.removeAll { it.time < cutoff }
            while (buf.size > 5000) buf.removeAt(0)
        }
    }

    /**
     * Возвращает (buyUsd − sellUsd) за последние windowMs миллисекунд.
     * Положительное = покупатели перевесили, отрицательное = продавцы.
     */
    fun computeDelta(symbol: String, windowMs: Long = 60_000L): Double {
        val now = System.currentTimeMillis()
        val cutoff = now - windowMs
        val snapshot = synchronized(_lock) {
            _buffers[symbol]?.filter { it.time >= cutoff } ?: return 0.0
        }
        if (snapshot.isEmpty()) return 0.0

        var buyUsd = 0.0
        var sellUsd = 0.0
        for (t in snapshot) {
            if (t.isBuy) buyUsd += t.usd else sellUsd += t.usd
        }
        return buyUsd - sellUsd
    }

    fun clear() {
        synchronized(_lock) { _buffers.clear() }
    }
}