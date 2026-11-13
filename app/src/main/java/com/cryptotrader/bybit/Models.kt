package com.cryptotrader.bybit

data class Candle(
    val time: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double
)

data class Signal(
    val type: String,
    val emoji: String,
    val name: String,
    val detail: String,
    val movePct: Double = 0.0
)

data class Trade(
    val time: Long,
    val price: Double,
    val size: Double
)

data class LargeTrade(
    val time: Long,
    val usd: Double,
    val isBuy: Boolean,
    val price: Double
)

data class LsPoint(
    val time: Long,
    val longPct: Double,
    val shortPct: Double
)

data class SpeedReading(
    val speedAt15: Double,
    val speedAt30: Double,
    val speedAt45: Double,
    val speedNow: Double,
    val accelerating: Boolean,
    val dying: Boolean,
    val dyingUp: Boolean,
    val dyingDown: Boolean,
    val points: List<Pair<Long, Double>>
)

data class SignalResult(
    val type: String,
    val candleTime: Long,
    val strength: String,
    val pattern: String = ""
)

data class PeakForecast(
    val mid: Double,
    val low: Double,
    val high: Double,
    val remainPct: Double,
    val confident: Boolean
) {
    companion object {
        fun empty(): PeakForecast = PeakForecast(0.0, 0.0, 0.0, 0.0, false)
    }
}

data class AccumulationInfo(
    val type: String = "",          // "long" / "short" / ""
    val level: String = "",         // "weak" / "medium" / "strong" / ""
    val oiChangePct: Double = 0.0,  // общий рост OI за 45 мин, %
    val oiBarsPositive: Int = 0,    // сколько баров из 9 были +
    val rangePct: Double = 0.0,     // диапазон цены за 45 мин, %
    val deltaUsd: Double = 0.0      // buy - sell за 45 мин, USD
)

data class ScanResult(
    val symbol: String,
    val price: Double,
    val change15m: Double,
    val turnover24h: Double,
    val turnover1h: Double,
    val longRatio: Double,
    val shortRatio: Double,
    val volRatio: Double,
    val turnover1m: Double,
    val signals: List<Signal>,
    val lastSignalTime: Long = System.currentTimeMillis(),
    val openInterest: Double = 0.0,
    val openInterestChange: Double = 0.0,
    val longRatioChange: Double = 0.0,
    val oiTrend: String = "stable",
    val signalType: String = "",
    val signalStrength: String = "",
    val signalCandleTime: Long = 0L,
    val signalPattern: String = "",
    val peakForecast: Double = 0.0,
    val peakLow: Double = 0.0,
    val peakHigh: Double = 0.0,
    val peakRemainPct: Double = 0.0,
    val peakConfident: Boolean = false,
    val bidWallPrice: Double = 0.0,
    val bidWallUsd: Double = 0.0,
    val askWallPrice: Double = 0.0,
    val askWallUsd: Double = 0.0,
    val deltaPct1m: Double = 0.0,
    val deltaUsd1m: Double = 0.0,
    val realDeltaUsd: Double = 0.0,
    val flowStatus: String = "",
    val priceAt15mAgo: Double = 0.0,
    val accumulation: AccumulationInfo? = null
) {
    val score: Int get() = signals.size
}

data class LiqEvent(
    val time: Long,
    val usd: Double,
    val isLong: Boolean,
    val price: Double
)

data class LiqStats(
    val total15m: Double,
    val long15m: Double,
    val short15m: Double,
    val trend: String
) {
    val dominantIsLong: Boolean get() = long15m > short15m
    val longPct: Double get() = if (total15m > 0) long15m / total15m * 100 else 0.0
    val shortPct: Double get() = if (total15m > 0) short15m / total15m * 100 else 0.0
}

data class OrderbookInfo(
    val imbalance: Double,
    val bidWallPrice: Double,
    val bidWallUsd: Double,
    val askWallPrice: Double,
    val askWallUsd: Double
)

data class AppState(
    val savedAt: Long,
    val scanResults: List<ScanResult>,
    val liqBuffer: Map<String, List<LiqEvent>>,
    val obInfos: Map<String, OrderbookInfo>,
    val liveSpeeds: Map<String, SpeedReading>,
    val longRatioHistory: Map<String, List<Pair<Long, Double>>>,
    val oiChangeHistory: Map<String, List<Double>>,
    val signalHistory: Map<String, SymbolSignal>,
    val signalMarkers: Map<String, List<SignalMarker>> = emptyMap(),
    val chartEvents: Map<String, List<ChartEvent>> = emptyMap(),
    val cascadePeaks: Map<String, List<CascadePeak>> = emptyMap(),
    val cascadeTrackers: Map<String, CascadeTracker> = emptyMap()
)
data class TradeSpeedReading(
    val pctPerMin: Double,
    val tradeCount: Int,
    val volumeUsd: Double,
    val seconds: Int
)

data class CascadePeak(
    val sum: Double,
    val time: Long
)

data class CascadeTracker(
    val maxSum: Double = 0.0,
    val lastGrowthTime: Long = 0L
)

data class ChartEvent(
    val time: Long,
    val type: String,
    val level: Int
)

