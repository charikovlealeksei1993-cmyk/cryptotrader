package com.cryptotrader.bybit

import android.content.Context
import java.io.File

/**
 * Append-only local research snapshots. A row records what the scanner knew
 * when it ran; capture_time is NOT the candle close time.
 * No network upload and no live trading.
 */
object MarketSnapshotRecorder {
    private val lock = Any()
    private val header = "symbol,time,open,high,low,close,volume,liq_total15m,liq_long15m,liq_short15m,oi_previous,oi_current,aggressive_delta_ratio,captured_at"

    fun append(
        context: Context,
        symbol: String,
        closed: Candle,
        liq: LiqStats?,
        oiHistory: List<Double>?,
        delta: Double?
    ) {
        val oiPrevious = oiHistory?.takeIf { it.size >= 2 }?.get(oiHistory.size - 2)
        val oiCurrent = oiHistory?.lastOrNull()
        val file = File(context.filesDir, "trap_snapshots.csv")
        synchronized(lock) {
            if (!file.exists() || file.length() == 0L) file.appendText(header + "\n")
            // Missing evidence is deliberately left blank, never replaced with zero.
            val fields = listOf(
                symbol, closed.time.toString(), closed.open.toString(),
                closed.high.toString(), closed.low.toString(), closed.close.toString(),
                closed.volume.toString(), liq?.total15m?.toString() ?: "",
                liq?.long15m?.toString() ?: "", liq?.short15m?.toString() ?: "",
                oiPrevious?.toString() ?: "", oiCurrent?.toString() ?: "",
                delta?.toString() ?: "", System.currentTimeMillis().toString()
            )
            file.appendText(fields.joinToString(",") + "\n")
        }
    }
}
