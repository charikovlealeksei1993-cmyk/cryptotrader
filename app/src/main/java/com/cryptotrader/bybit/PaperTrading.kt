package com.cryptotrader.bybit

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.random.Random

object PaperTrading {

    private val lock = Any()
    private var _trades: MutableList<PaperTrade> = mutableListOf()
    private var _balance: Double = 10_000.0
    private var _loaded: Boolean = false

    fun load(context: Context) {
        synchronized(lock) {
            if (_loaded) return

            val newState = Prefs.paperState(context)
            if (newState != null) {
                try {
                    val json = JSONObject(newState)
                    _balance = json.optDouble("balance", 10_000.0)
                    val tradesArr = json.optJSONArray("trades") ?: JSONArray()
                    _trades = PaperTrade.listFromJson(tradesArr.toString()).toMutableList()
                } catch (_: Exception) {
                    _balance = 10_000.0
                    _trades = mutableListOf()
                }
            } else {
                _trades = PaperTrade.listFromJson(Prefs.paperTradesJson(context)).toMutableList()
                _balance = Prefs.paperBalance(context)
                save(context)
            }
            _loaded = true
        }
    }

    fun reload(context: Context) {
        synchronized(lock) { _loaded = false }
        load(context)
    }

    private fun save(context: Context) {
        try {
            val json = JSONObject().apply {
                put("balance", _balance)
                put("trades", JSONArray(PaperTrade.listToJson(_trades)))
            }
            Prefs.setPaperState(context, json.toString())
        } catch (_: Exception) {}
    }

    fun balance(): Double = synchronized(lock) { _balance }
    fun trades(): List<PaperTrade> = synchronized(lock) { _trades.toList() }
    fun openTrades(): List<PaperTrade> = synchronized(lock) { _trades.filter { it.isOpen } }
    fun closedTrades(): List<PaperTrade> = synchronized(lock) {
        _trades.filter { !it.isOpen }.sortedByDescending { it.exitTime }
    }

    fun hasOpenFor(symbol: String): Boolean = synchronized(lock) {
        _trades.any { it.isOpen && it.symbol == symbol }
    }

    fun openTrade(
        context: Context,
        symbol: String,
        isLong: Boolean,
        price: Double,
        sizeUsd: Double,
        ctx: ContextSnapshot,
        isAuto: Boolean = false
    ): PaperTrade? {
        if (price <= 0 || sizeUsd <= 0) return null
        synchronized(lock) {
            if (_balance < sizeUsd) return null
            val now = System.currentTimeMillis()
            val id = now * 1000 + Random.nextInt(0, 1000)

            val slPct = Prefs.paperSlPercent(context)
            val tpPct = Prefs.paperTpPercent(context)
            val slPrice = if (isLong) price * (1 - slPct / 100.0)
                          else price * (1 + slPct / 100.0)
            val tpPrice = if (isLong) price * (1 + tpPct / 100.0)
                          else price * (1 - tpPct / 100.0)

            val trade = PaperTrade(
                id = id,
                symbol = symbol,
                isLong = isLong,
                entryPrice = price,
                sizeUsd = sizeUsd,
                entryTime = now,
                slPrice = slPrice,
                tpPrice = tpPrice,
                ctxLiqTotal15m = ctx.liqTotal15m,
                ctxLiqLongPct = ctx.liqLongPct,
                ctxLiqShortPct = ctx.liqShortPct,
                ctxLiqTrend = ctx.liqTrend,
                ctxHasCascade = ctx.hasCascade,
                ctxOiValue = ctx.oiValue,
                ctxOiChange = ctx.oiChange,
                ctxOiTrend = ctx.oiTrend,
                ctxDeltaPct1m = ctx.deltaPct1m,
                ctxRealDeltaUsd = ctx.realDeltaUsd,
                ctxFlowStatus = ctx.flowStatus,
                ctxSpeedNow = ctx.speedNow,
                ctxSpeedAccelerating = ctx.speedAccelerating,
                ctxSpeedDying = ctx.speedDying,
                ctxVolRatio = ctx.volRatio,
                ctxTurnover1m = ctx.turnover1m,
                ctxChange15m = ctx.change15m,
                ctxLongRatio = ctx.longRatio,
                ctxShortRatio = ctx.shortRatio,
                ctxObImbalance = ctx.obImbalance,
                ctxSignalType = ctx.signalType,
                ctxSignalStrength = ctx.signalStrength,
                ctxSignalPattern = ctx.signalPattern,
                isAuto = isAuto
            )
            _trades.add(trade)
            _balance -= sizeUsd
            save(context)
            return trade
        }
    }

    fun closeTrade(context: Context, tradeId: Long, exitPrice: Double, reason: String = "manual"): PaperTrade? {
        if (exitPrice <= 0) return null
        synchronized(lock) {
            val idx = _trades.indexOfFirst { it.id == tradeId && it.isOpen }
            if (idx < 0) return null
            val t = _trades[idx]
            val closed = t.copy(exitPrice = exitPrice, exitTime = System.currentTimeMillis(), closeReason = reason)
            _trades[idx] = closed
            _balance += t.sizeUsd + closed.realizedPnl()
            save(context)
            return closed
        }
    }

    fun closeAll(context: Context, pricesProvider: (String) -> Double, reason: String = "manual"): Int {
        var closed = 0
        val openCopy = synchronized(lock) { _trades.filter { it.isOpen } }
        for (t in openCopy) {
            val p = pricesProvider(t.symbol)
            if (p > 0) { closeTrade(context, t.id, p, reason); closed++ }
        }
        return closed
    }

    fun checkAutoClose(context: Context, pricesProvider: (String) -> Double): Int {
        val now = System.currentTimeMillis()
        val timeoutMs = Prefs.paperTimeoutMinutes(context) * 60_000L
        var closedCount = 0

        val openCopy = synchronized(lock) { _trades.filter { it.isOpen } }

        for (t in openCopy) {
            val price = pricesProvider(t.symbol)
            if (price <= 0) continue

            var exitPrice = 0.0
            var reason = ""

            if (t.slPrice > 0 && t.tpPrice > 0) {
                if (t.isLong) {
                    if (price <= t.slPrice) { exitPrice = t.slPrice; reason = "sl" }
                    else if (price >= t.tpPrice) { exitPrice = t.tpPrice; reason = "tp" }
                } else {
                    if (price >= t.slPrice) { exitPrice = t.slPrice; reason = "sl" }
                    else if (price <= t.tpPrice) { exitPrice = t.tpPrice; reason = "tp" }
                }
            }

            if (exitPrice <= 0 && now - t.entryTime > timeoutMs) {
                exitPrice = price
                reason = "timeout"
            }

            if (exitPrice > 0) {
                val closed = closeTrade(context, t.id, exitPrice, reason)
                if (closed != null) closedCount++
            }
        }

        return closedCount
    }

    fun reset(context: Context, startBalance: Double = 10_000.0) {
        synchronized(lock) { _trades.clear(); _balance = startBalance; save(context) }
    }

    data class ContextSnapshot(
        val liqTotal15m: Double = 0.0,
        val liqLongPct: Double = 0.0,
        val liqShortPct: Double = 0.0,
        val liqTrend: String = "",
        val hasCascade: Boolean = false,
        val oiValue: Double = 0.0,
        val oiChange: Double = 0.0,
        val oiTrend: String = "",
        val deltaPct1m: Double = 0.0,
        val realDeltaUsd: Double = 0.0,
        val flowStatus: String = "",
        val speedNow: Double = 0.0,
        val speedAccelerating: Boolean = false,
        val speedDying: Boolean = false,
        val volRatio: Double = 0.0,
        val turnover1m: Double = 0.0,
        val change15m: Double = 0.0,
        val longRatio: Double = 0.0,
        val shortRatio: Double = 0.0,
        val obImbalance: Double = 0.0,
        val signalType: String = "",
        val signalStrength: String = "",
        val signalPattern: String = ""
    )

    data class Stats(
        val totalTrades: Int, val wins: Int, val losses: Int, val breakeven: Int,
        val totalPnl: Double, val avgWin: Double, val avgLoss: Double,
        val bestTrade: Double, val worstTrade: Double, val avgDurationSec: Long,
        val longWins: Int, val longLosses: Int, val shortWins: Int, val shortLosses: Int
    ) {
        val winRate: Double get() = if (totalTrades > 0) wins.toDouble() / totalTrades * 100 else 0.0
        val profitFactor: Double get() = if (avgLoss != 0.0) abs(avgWin / avgLoss) else 0.0
    }

    fun computeStats(): Stats {
        val closed = synchronized(lock) { _trades.filter { !it.isOpen } }
        if (closed.isEmpty()) return Stats(0,0,0,0,0.0,0.0,0.0,0.0,0.0,0,0,0,0,0)
        var wins = 0; var losses = 0; var be = 0
        var totalPnl = 0.0; var sumWin = 0.0; var sumLoss = 0.0
        var best = Double.NEGATIVE_INFINITY; var worst = Double.POSITIVE_INFINITY
        var totalDur = 0L
        var longWins = 0; var longLosses = 0; var shortWins = 0; var shortLosses = 0
        for (t in closed) {
            val pnl = t.realizedPnl()
            totalPnl += pnl
            totalDur += (t.exitTime - t.entryTime) / 1000
            if (pnl > best) best = pnl
            if (pnl < worst) worst = pnl
            when {
                pnl > 0.01 -> { wins++; sumWin += pnl; if (t.isLong) longWins++ else shortWins++ }
                pnl < -0.01 -> { losses++; sumLoss += pnl; if (t.isLong) longLosses++ else shortLosses++ }
                else -> be++
            }
        }
        return Stats(closed.size, wins, losses, be, totalPnl,
            if (wins > 0) sumWin / wins else 0.0,
            if (losses > 0) sumLoss / losses else 0.0,
            if (best > Double.NEGATIVE_INFINITY) best else 0.0,
            if (worst < Double.POSITIVE_INFINITY) worst else 0.0,
            if (closed.isNotEmpty()) totalDur / closed.size else 0,
            longWins, longLosses, shortWins, shortLosses)
    }

    data class GroupStats(
        val label: String,
        val count: Int,
        val wins: Int,
        val losses: Int,
        val winRate: Double,
        val avgPnl: Double,
        val totalPnl: Double
    )

    fun groupStats(label: String, filter: (PaperTrade) -> Boolean): GroupStats {
        val closed = synchronized(lock) { _trades.filter { !it.isOpen && filter(it) } }
        if (closed.isEmpty()) return GroupStats(label, 0, 0, 0, 0.0, 0.0, 0.0)
        var wins = 0; var losses = 0
        var sumPnl = 0.0
        for (t in closed) {
            val pnl = t.realizedPnl()
            sumPnl += pnl
            if (pnl > 0.01) wins++ else if (pnl < -0.01) losses++
        }
        return GroupStats(label, closed.size, wins, losses,
            if (closed.isNotEmpty()) wins.toDouble() / closed.size * 100 else 0.0,
            sumPnl / closed.size, sumPnl)
    }

    data class ExitReasonStats(val tp: Int, val sl: Int, val timeout: Int, val manual: Int, val closeAll: Int)

    fun exitReasonStats(): ExitReasonStats {
        val closed = synchronized(lock) { _trades.filter { !it.isOpen } }
        var tp = 0; var sl = 0; var timeout = 0; var manual = 0; var closeAll = 0
        for (t in closed) {
            when (t.closeReason) {
                "tp" -> tp++
                "sl" -> sl++
                "timeout" -> timeout++
                "close_all" -> closeAll++
                else -> manual++
            }
        }
        return ExitReasonStats(tp, sl, timeout, manual, closeAll)
    }

    data class PatternGroup(
        val pattern: String,
        val count: Int,
        val wins: Int,
        val losses: Int,
        val winRate: Double,
        val avgPnl: Double,
        val totalPnl: Double
    )

    fun statsByPattern(): List<PatternGroup> {
        val closed = synchronized(lock) { _trades.filter { !it.isOpen } }
        if (closed.isEmpty()) return emptyList()

        val grouped = closed.groupBy { it.ctxSignalPattern.ifEmpty { "(без сигнала)" } }
        val out = ArrayList<PatternGroup>()
        for ((pattern, trades) in grouped) {
            var wins = 0; var losses = 0
            var sumPnl = 0.0
            for (t in trades) {
                val pnl = t.realizedPnl()
                sumPnl += pnl
                if (pnl > 0.01) wins++ else if (pnl < -0.01) losses++
            }
            out.add(PatternGroup(
                pattern = pattern,
                count = trades.size,
                wins = wins,
                losses = losses,
                winRate = if (trades.isNotEmpty()) wins.toDouble() / trades.size * 100 else 0.0,
                avgPnl = sumPnl / trades.size,
                totalPnl = sumPnl
            ))
        }
        return out.sortedByDescending { it.winRate }
    }
}