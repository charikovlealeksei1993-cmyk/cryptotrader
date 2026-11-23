package com.cryptotrader.bybit

import kotlin.math.min

/** Builds constant-USDT-volume candles from individual Bybit public trades. */
data class VolumeBar(
    val startTime: Long,
    val endTime: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volumeUsdt: Double,
    val buyUsdt: Double,
    val sellUsdt: Double,
    val complete: Boolean
) {
    val deltaUsdt: Double get() = buyUsdt - sellUsdt
    val durationMs: Long get() = endTime - startTime
}

class VolumeBarAggregator(thresholdUsdt: Double, private val maxBars: Int = 1000) {
    private var threshold = thresholdUsdt.coerceAtLeast(1.0)
    private val finished = ArrayDeque<VolumeBar>()
    private var current: VolumeBar? = null
    private var lastTradeTime = Long.MIN_VALUE

    @Synchronized
    fun reset(newThresholdUsdt: Double = threshold) {
        threshold = newThresholdUsdt.coerceAtLeast(1.0)
        finished.clear()
        current = null
        lastTradeTime = Long.MIN_VALUE
    }

    /** A single large trade can close several bars. Its notional is split exactly. */
    @Synchronized
    fun addTrade(time: Long, price: Double, quantity: Double, isBuy: Boolean) {
        if (time <= 0L || !price.isFinite() || price <= 0.0 ||
            !quantity.isFinite() || quantity <= 0.0) return
        if (time < lastTradeTime) return // Preserve execution order for OHLC.
        lastTradeTime = time
        var remaining = price * quantity
        if (!remaining.isFinite() || remaining <= 0.0) return
        while (remaining > 1e-8) {
            val old = current
            val used = old?.volumeUsdt ?: 0.0
            val part = min(remaining, (threshold - used).coerceAtLeast(0.0))
            if (part <= 1e-8) {
                if (old != null) finish(old)
                else break
                continue
            }
            val bar = if (old == null) {
                VolumeBar(time, time, price, price, price, price, part,
                    if (isBuy) part else 0.0, if (isBuy) 0.0 else part, false)
            } else {
                old.copy(
                    endTime = time,
                    high = maxOf(old.high, price),
                    low = minOf(old.low, price),
                    close = price,
                    volumeUsdt = old.volumeUsdt + part,
                    buyUsdt = old.buyUsdt + if (isBuy) part else 0.0,
                    sellUsdt = old.sellUsdt + if (isBuy) 0.0 else part
                )
            }
            current = bar
            remaining -= part
            if (bar.volumeUsdt >= threshold - 1e-7) finish(bar)
        }
    }

    private fun finish(bar: VolumeBar) {
        finished.addLast(bar.copy(volumeUsdt = threshold, complete = true))
        while (finished.size > maxBars) finished.removeFirst()
        current = null
    }

    @Synchronized
    fun snapshot(): List<VolumeBar> =
        finished.toList() + listOfNotNull(current)
}
