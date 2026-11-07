package com.cryptotrader.bybit

import java.util.concurrent.ConcurrentHashMap

/**
 * Централизованное состояние рынка с лок-объектами.
 * Все коллекции, которые раньше жили в MainActivity, теперь здесь.
 */
object MarketState {

    // ===== Закреплённые символы =====
    val pinnedSymbols = HashSet<String>()
    val pinnedLock = Any()

    // ===== Live данные (сделки, скорости) =====
    val liveBuffer = HashMap<String, MutableList<Trade>>()
    val liveSpeeds = HashMap<String, SpeedReading>()
    val liveLock = Any()

    // ===== Стакан =====
    val obInfos = HashMap<String, OrderbookInfo>()
    val obHistory = HashMap<String, MutableList<Double>>()
    val obLock = Any()

    // ===== Ликвидации =====
    val liqBuffer = HashMap<String, MutableList<LiqEvent>>()
    val liqLock = Any()
    val liqStatsCache = ConcurrentHashMap<String, LiqStats>()
    val liqTrendHistory = HashMap<String, MutableList<String>>()
    val liqTrendLock = Any()

    // ===== Сигналы =====
    val signalHistory = HashMap<String, SymbolSignal>()
    val signalHistoryLock = Any()
    val signalMarkers = HashMap<String, MutableList<SignalMarker>>()
    val signalMarkersLock = Any()
    val signalCooldown = HashMap<String, Long>()

    // ===== Open Interest =====
    val openInterestHistory = HashMap<String, Double>()
    val openInterestTimestamp = HashMap<String, Long>()
    val oiLock = Any()

    // ===== Long/Short =====
    val longRatioHistory = HashMap<String, MutableList<Pair<Long, Double>>>()
    val longRatioLock = Any()

    // ===== OI change =====
    val oiChangeHistory = HashMap<String, MutableList<Double>>()
    val oiChangeLock = Any()

    // ===== Уведомления =====
    val notifiedCooldown = HashMap<String, Long>()
    val notifiedType = HashMap<String, String>()
    val notifiedTime = HashMap<String, Long>()
    val notifiedSignalKey = HashMap<String, String>()
    val notifiedSignalTime = HashMap<String, Long>()

    // ===== Ликвидационные уведомления =====
    val liquidationActive = HashSet<String>()
    val liquidationActiveLock = Any()
}