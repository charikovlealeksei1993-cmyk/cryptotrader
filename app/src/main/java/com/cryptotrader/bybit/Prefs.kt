package com.cryptotrader.bybit

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.net.Uri

object Prefs {
    private const val NAME = "crypto_trader_prefs"

    val NOTIF_TYPES = listOf("liquidation", "speed", "volume")

    const val LIQ_THRESHOLD_MIN = 10_000.0
    const val LIQ_THRESHOLD_MAX = 200_000.0
    const val LIQ_THRESHOLD_STEP = 5_000.0
    const val LARGE_TRADE_MIN = 10_000.0
    const val LARGE_TRADE_MAX = 100_000.0
    const val LARGE_TRADE_STEP = 5_000.0

    const val SCAN_LIMIT_MIN = 5
    const val SCAN_LIMIT_MAX = 20

    @Volatile private var cachedSp: SharedPreferences? = null
    @Volatile private var cachedTheme: String? = null

    private fun sp(c: Context): SharedPreferences {
        cachedSp?.let { return it }
        synchronized(this) {
            cachedSp?.let { return it }
            val s = c.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            cachedSp = s
            return s
        }
    }

    fun theme(c: Context): String {
        cachedTheme?.let { return it }
        synchronized(this) {
            cachedTheme?.let { return it }
            val t = sp(c).getString("theme", "dark") ?: "dark"
            cachedTheme = t
            return t
        }
    }
    fun setTheme(c: Context, v: String) {
        cachedTheme = v
        sp(c).edit().putString("theme", v).apply()
    }

    fun futureLiquidationsEnabled(c: Context): Boolean =
        sp(c).getBoolean("futureLiquidationsEnabled", true)
    fun setFutureLiquidationsEnabled(c: Context, enabled: Boolean) =
        sp(c).edit().putBoolean("futureLiquidationsEnabled", enabled).apply()

    fun chartType(c: Context): String = sp(c).getString("chartType", "candles") ?: "candles"
    fun setChartType(c: Context, v: String) = sp(c).edit().putString("chartType", v).apply()

    fun volumeBarUsdt(c: Context): Double = sp(c).getLong("volumeBarUsdt", 500_000L).toDouble()
    fun setVolumeBarUsdt(c: Context, v: Double) = sp(c).edit()
        .putLong("volumeBarUsdt", v.toLong().coerceIn(10_000L, 100_000_000L)).apply()

    fun history(c: Context): Int = sp(c).getInt("history", 200)
    fun setHistory(c: Context, v: Int) {
        sp(c).edit().putInt("history", v.coerceIn(100, 1000)).apply()
    }

    fun historyForTf(c: Context, tf: String): Int {
        val base = history(c)
        return when (tf) {
            "1", "5", "15" -> base.coerceIn(100, 1000)
            "60" -> base.coerceIn(100, 500)
            else -> base
        }
    }

    fun scanLimit(c: Context): Int =
        sp(c).getInt("scanLimit", 20).coerceIn(SCAN_LIMIT_MIN, SCAN_LIMIT_MAX)

    fun setScanLimit(c: Context, v: Int) {
        sp(c).edit().putInt("scanLimit", v.coerceIn(SCAN_LIMIT_MIN, SCAN_LIMIT_MAX)).apply()
    }

    fun liqThreshold(c: Context): Double =
        sp(c).getFloat("liqThreshold", LIQ_THRESHOLD_MIN.toFloat()).toDouble()
            .coerceIn(LIQ_THRESHOLD_MIN, LIQ_THRESHOLD_MAX)

    fun setLiqThreshold(c: Context, v: Double) {
        val clamped = v.coerceIn(LIQ_THRESHOLD_MIN, LIQ_THRESHOLD_MAX)
        sp(c).edit().putFloat("liqThreshold", clamped.toFloat()).apply()
    }

    fun largeTradeThreshold(c: Context): Double =
        sp(c).getFloat("largeTradeThreshold", LARGE_TRADE_MIN.toFloat()).toDouble()
            .coerceIn(LARGE_TRADE_MIN, LARGE_TRADE_MAX)

    fun setLargeTradeThreshold(c: Context, v: Double) {
        val clamped = v.coerceIn(LARGE_TRADE_MIN, LARGE_TRADE_MAX)
        sp(c).edit().putFloat("largeTradeThreshold", clamped.toFloat()).apply()
    }

    fun soundUri(c: Context): Uri? {
        val s = sp(c).getString("soundUri", null) ?: return null
        return try { Uri.parse(s) } catch (_: Exception) { null }
    }
    fun setSoundUri(c: Context, u: Uri?) {
        val e = sp(c).edit()
        if (u == null) e.remove("soundUri") else e.putString("soundUri", u.toString())
        e.apply()
    }

    fun soundUriFor(c: Context, type: String): Uri? {
        val s = sp(c).getString("sound_$type", null) ?: return null
        return try { Uri.parse(s) } catch (_: Exception) { null }
    }
    fun setSoundUriFor(c: Context, type: String, u: Uri?) {
        val e = sp(c).edit()
        if (u == null) e.remove("sound_$type") else e.putString("sound_$type", u.toString())
        e.apply()
    }

    fun soundDisplayName(c: Context, uri: Uri?): String {
        if (uri == null) return "не выбран"
        return try {
            c.contentResolver.query(uri, null, null, null, null)?.use { cur ->
                val idx = cur.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cur.moveToFirst()) cur.getString(idx) else null
            } ?: uri.lastPathSegment ?: "выбран"
        } catch (_: Exception) {
            uri.lastPathSegment ?: "выбран"
        }
    }

    fun pinned(c: Context): Set<String> =
        sp(c).getStringSet("pinned", emptySet()) ?: emptySet()

    fun setPinned(c: Context, v: Set<String>) =
        sp(c).edit().putStringSet("pinned", v.toSet()).apply()

    fun notifEnabled(c: Context, type: String): Boolean =
        sp(c).getBoolean("notif_$type", true)

    fun setNotifEnabled(c: Context, type: String, v: Boolean) =
        sp(c).edit().putBoolean("notif_$type", v).apply()

    fun signalNotificationEnabled(c: Context, direction: String, tier: String): Boolean =
        sp(c).getBoolean("signal_notif_${direction}_${tier}", true)

    fun setSignalNotificationEnabled(c: Context, direction: String, tier: String, enabled: Boolean) =
        sp(c).edit().putBoolean("signal_notif_${direction}_${tier}", enabled).apply()

    fun signalSoundEnabled(c: Context, tier: String): Boolean =
        sp(c).getBoolean("signal_sound_$tier", true)

    fun setSignalSoundEnabled(c: Context, tier: String, enabled: Boolean) =
        sp(c).edit().putBoolean("signal_sound_$tier", enabled).apply()

    fun clearAll(c: Context) {
        cachedTheme = null
        sp(c).edit().clear().apply()
    }

    private inline fun themed(c: Context, dark: Int, light: Int): Int =
        if (theme(c) == "dark") dark else light

    fun bg(c: Context) = themed(c, Color.rgb(14, 14, 20), Color.rgb(250, 250, 252))
    fun row(c: Context) = themed(c, Color.rgb(20, 20, 28), Color.rgb(235, 235, 240))
    fun hotRow(c: Context) = themed(c, Color.rgb(80, 55, 20), Color.rgb(255, 230, 180))
    fun text(c: Context) = themed(c, Color.WHITE, Color.rgb(20, 20, 30))
    fun textDim(c: Context) = themed(c, Color.LTGRAY, Color.rgb(80, 80, 90))
    fun buttonOff(c: Context) = themed(c, Color.rgb(45, 45, 55), Color.rgb(215, 215, 225))
    fun buttonPressed(c: Context) = themed(c, Color.rgb(70, 70, 90), Color.rgb(190, 190, 205))

    // ================== Paper Trading ==================

    fun paperBalance(c: Context): Double =
        sp(c).getString("paperBalance", null)?.toDoubleOrNull() ?: 10_000.0

    fun setPaperBalance(c: Context, v: Double) {
        sp(c).edit().putString("paperBalance", v.toString()).apply()
    }

    fun paperTradeSize(c: Context): Double =
        sp(c).getString("paperTradeSize", null)?.toDoubleOrNull() ?: 100.0

    fun setPaperTradeSize(c: Context, v: Double) {
        sp(c).edit().putString("paperTradeSize", v.toString()).apply()
    }

    fun paperTradesJson(c: Context): String =
        sp(c).getString("paperTrades", "[]") ?: "[]"

    fun setPaperTradesJson(c: Context, json: String) {
        sp(c).edit().putString("paperTrades", json).apply()
    }

    fun paperState(c: Context): String? =
        sp(c).getString("paperState", null)

    fun setPaperState(c: Context, json: String) {
        sp(c).edit().putString("paperState", json).commit()
    }

    // Авто-выход SL/TP/Timeout
    fun paperSlPercent(c: Context): Double =
        sp(c).getString("paperSlPercent", null)?.toDoubleOrNull() ?: 2.0

    fun setPaperSlPercent(c: Context, v: Double) {
        sp(c).edit().putString("paperSlPercent", v.toString()).apply()
    }

    fun paperTpPercent(c: Context): Double =
        sp(c).getString("paperTpPercent", null)?.toDoubleOrNull() ?: 3.0

    fun setPaperTpPercent(c: Context, v: Double) {
        sp(c).edit().putString("paperTpPercent", v.toString()).apply()
    }

    fun paperTimeoutMinutes(c: Context): Int =
        sp(c).getString("paperTimeoutMinutes", null)?.toIntOrNull() ?: 240

    fun setPaperTimeoutMinutes(c: Context, v: Int) {
        sp(c).edit().putString("paperTimeoutMinutes", v.toString()).apply()
    }

    // ================== Backtest ==================

    fun btTf(c: Context): String = sp(c).getString("btTf", "1") ?: "1"
    fun setBtTf(c: Context, v: String) = sp(c).edit().putString("btTf", v).apply()

    fun btDays(c: Context): Int = sp(c).getInt("btDays", 7).coerceIn(1, 14)
    fun setBtDays(c: Context, v: Int) = sp(c).edit().putInt("btDays", v.coerceIn(1, 14)).apply()

    fun btSymbol(c: Context): String = sp(c).getString("btSymbol", "BTCUSDT") ?: "BTCUSDT"
    fun setBtSymbol(c: Context, v: String) = sp(c).edit().putString("btSymbol", v).apply()
        // ================== Signal TF ==================

    fun signalTf(c: Context): String = sp(c).getString("signalTf", "1") ?: "1"
    fun setSignalTf(c: Context, v: String) = sp(c).edit().putString("signalTf", v).apply()

    // ================== Авто-торговля ==================

    fun autoTradingEnabled(c: Context): Boolean =
        sp(c).getBoolean("autoTradingEnabled", false)

    fun setAutoTradingEnabled(c: Context, v: Boolean) =
        sp(c).edit().putBoolean("autoTradingEnabled", v).apply()

    // ================== Накопления ==================
    fun accMinutes(c: Context): Int = sp(c).getInt("accMinutes", 45)
    fun setAccMinutes(c: Context, v: Int) = sp(c).edit().putInt("accMinutes", v.coerceIn(5, 120)).apply()

    fun deltaBurstThreshold(c: Context): Double = sp(c).getFloat("deltaBurstThreshold", 50_000f).toDouble()
    fun setDeltaBurstThreshold(c: Context, v: Double) = sp(c).edit().putFloat("deltaBurstThreshold", v.toFloat()).apply()

    fun accHours(c: Context): Int = sp(c).getInt("accHours", 4)
    fun setAccHours(c: Context, v: Int) = sp(c).edit().putInt("accHours", v.coerceIn(1, 120)).apply()
}
