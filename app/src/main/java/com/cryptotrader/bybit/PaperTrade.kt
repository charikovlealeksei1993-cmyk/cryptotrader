package com.cryptotrader.bybit

import org.json.JSONArray
import org.json.JSONObject

data class PaperTrade(
    val id: Long,
    val symbol: String,
    val isLong: Boolean,
    val entryPrice: Double,
    val sizeUsd: Double,
    val entryTime: Long,
    val exitPrice: Double = 0.0,
    val exitTime: Long = 0L,
    val closeReason: String = "",

    // Авто-выход
    val slPrice: Double = 0.0,
    val tpPrice: Double = 0.0,

    // Контекст на момент входа
    val ctxLiqTotal15m: Double = 0.0,
    val ctxLiqLongPct: Double = 0.0,
    val ctxLiqShortPct: Double = 0.0,
    val ctxLiqTrend: String = "",
    val ctxHasCascade: Boolean = false,
    val ctxOiValue: Double = 0.0,
    val ctxOiChange: Double = 0.0,
    val ctxOiTrend: String = "",
    val ctxDeltaPct1m: Double = 0.0,
    val ctxRealDeltaUsd: Double = 0.0,
    val ctxFlowStatus: String = "",
    val ctxSpeedNow: Double = 0.0,
    val ctxSpeedAccelerating: Boolean = false,
    val ctxSpeedDying: Boolean = false,
    val ctxVolRatio: Double = 0.0,
    val ctxTurnover1m: Double = 0.0,
    val ctxChange15m: Double = 0.0,
    val ctxLongRatio: Double = 0.0,
    val ctxShortRatio: Double = 0.0,
    val ctxObImbalance: Double = 0.0,
    val ctxSignalType: String = "",
    val ctxSignalStrength: String = "",
    val ctxSignalPattern: String = ""
) {
    val isOpen: Boolean get() = exitPrice <= 0.0 && exitTime <= 0L
    val quantity: Double get() = if (entryPrice > 0) sizeUsd / entryPrice else 0.0

    fun unrealizedPnl(currentPrice: Double): Double {
        if (!isOpen || currentPrice <= 0) return 0.0
        val diff = if (isLong) currentPrice - entryPrice else entryPrice - currentPrice
        return diff * quantity
    }

    fun unrealizedPnlPct(currentPrice: Double): Double {
        if (sizeUsd <= 0) return 0.0
        return unrealizedPnl(currentPrice) / sizeUsd * 100
    }

    fun realizedPnl(): Double {
        if (isOpen || exitPrice <= 0) return 0.0
        val diff = if (isLong) exitPrice - entryPrice else entryPrice - exitPrice
        return diff * quantity
    }

    fun realizedPnlPct(): Double {
        if (sizeUsd <= 0) return 0.0
        return realizedPnl() / sizeUsd * 100
    }

    fun durationSec(nowMs: Long = System.currentTimeMillis()): Long {
        val end = if (isOpen) nowMs else exitTime
        return (end - entryTime) / 1000
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("symbol", symbol)
        put("isLong", isLong)
        put("entryPrice", entryPrice)
        put("sizeUsd", sizeUsd)
        put("entryTime", entryTime)
        put("exitPrice", exitPrice)
        put("exitTime", exitTime)
        put("closeReason", closeReason)
        put("slPrice", slPrice)
        put("tpPrice", tpPrice)

        put("ctxLiqTotal15m", ctxLiqTotal15m)
        put("ctxLiqLongPct", ctxLiqLongPct)
        put("ctxLiqShortPct", ctxLiqShortPct)
        put("ctxLiqTrend", ctxLiqTrend)
        put("ctxHasCascade", ctxHasCascade)
        put("ctxOiValue", ctxOiValue)
        put("ctxOiChange", ctxOiChange)
        put("ctxOiTrend", ctxOiTrend)
        put("ctxDeltaPct1m", ctxDeltaPct1m)
        put("ctxRealDeltaUsd", ctxRealDeltaUsd)
        put("ctxFlowStatus", ctxFlowStatus)
        put("ctxSpeedNow", ctxSpeedNow)
        put("ctxSpeedAccelerating", ctxSpeedAccelerating)
        put("ctxSpeedDying", ctxSpeedDying)
        put("ctxVolRatio", ctxVolRatio)
        put("ctxTurnover1m", ctxTurnover1m)
        put("ctxChange15m", ctxChange15m)
        put("ctxLongRatio", ctxLongRatio)
        put("ctxShortRatio", ctxShortRatio)
        put("ctxObImbalance", ctxObImbalance)
        put("ctxSignalType", ctxSignalType)
        put("ctxSignalStrength", ctxSignalStrength)
        put("ctxSignalPattern", ctxSignalPattern)
    }

    companion object {
        fun fromJson(o: JSONObject): PaperTrade = PaperTrade(
            id = o.optLong("id", 0),
            symbol = o.optString("symbol", ""),
            isLong = o.optBoolean("isLong", true),
            entryPrice = o.optDouble("entryPrice", 0.0),
            sizeUsd = o.optDouble("sizeUsd", 0.0),
            entryTime = o.optLong("entryTime", 0L),
            exitPrice = o.optDouble("exitPrice", 0.0),
            exitTime = o.optLong("exitTime", 0L),
            closeReason = o.optString("closeReason", ""),
            slPrice = o.optDouble("slPrice", 0.0),
            tpPrice = o.optDouble("tpPrice", 0.0),

            ctxLiqTotal15m = o.optDouble("ctxLiqTotal15m", 0.0),
            ctxLiqLongPct = o.optDouble("ctxLiqLongPct", 0.0),
            ctxLiqShortPct = o.optDouble("ctxLiqShortPct", 0.0),
            ctxLiqTrend = o.optString("ctxLiqTrend", ""),
            ctxHasCascade = o.optBoolean("ctxHasCascade", false),
            ctxOiValue = o.optDouble("ctxOiValue", 0.0),
            ctxOiChange = o.optDouble("ctxOiChange", 0.0),
            ctxOiTrend = o.optString("ctxOiTrend", ""),
            ctxDeltaPct1m = o.optDouble("ctxDeltaPct1m", 0.0),
            ctxRealDeltaUsd = o.optDouble("ctxRealDeltaUsd", 0.0),
            ctxFlowStatus = o.optString("ctxFlowStatus", ""),
            ctxSpeedNow = o.optDouble("ctxSpeedNow", 0.0),
            ctxSpeedAccelerating = o.optBoolean("ctxSpeedAccelerating", false),
            ctxSpeedDying = o.optBoolean("ctxSpeedDying", false),
            ctxVolRatio = o.optDouble("ctxVolRatio", 0.0),
            ctxTurnover1m = o.optDouble("ctxTurnover1m", 0.0),
            ctxChange15m = o.optDouble("ctxChange15m", 0.0),
            ctxLongRatio = o.optDouble("ctxLongRatio", 0.0),
            ctxShortRatio = o.optDouble("ctxShortRatio", 0.0),
            ctxObImbalance = o.optDouble("ctxObImbalance", 0.0),
            ctxSignalType = o.optString("ctxSignalType", ""),
            ctxSignalStrength = o.optString("ctxSignalStrength", ""),
            ctxSignalPattern = o.optString("ctxSignalPattern", "")
        )

        fun listToJson(trades: List<PaperTrade>): String {
            val arr = JSONArray()
            trades.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }

        fun listFromJson(json: String): List<PaperTrade> {
            return try {
                val arr = JSONArray(json)
                val out = ArrayList<PaperTrade>(arr.length())
                for (i in 0 until arr.length()) out.add(fromJson(arr.getJSONObject(i)))
                out
            } catch (_: Exception) { emptyList() }
        }
    }
}