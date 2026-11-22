package com.cryptotrader.bybit

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Independent market monitor. Does not depend on MainActivity's Handler or lifecycle.
 * Android may still stop dataSync foreground services (including the 6-hour quota on Android 15+).
 */
class KeepAliveService : Service() {
    private val channelId = "crypto_keepalive"
    private val worker = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var running = false
    private var liquidationSocket: LiquidationSocket? = null
    private var monitoredSymbols: List<String> = emptyList()
    private val status by lazy { getSharedPreferences("background_scanner_status", MODE_PRIVATE) }
    private val alerts by lazy { getSharedPreferences("background_scanner_alerts", MODE_PRIVATE) }
    private var checked = 0
    private var failures = 0
    private var lastError = ""

    override fun onCreate() {
        super.onCreate()
        createChannel()
        status.edit().putBoolean("running", true).putString("state", "Запуск").apply()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("CryptoTrader: мониторинг рынка")
            .setContentText("Проверка монет, объёмов и ликвидаций")
            .setContentIntent(openApp)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(1, notification)
            }
        } catch (e: Exception) {
            Log.e("CryptoBackground", "Foreground start rejected", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) {
            running = true
            worker.scheduleWithFixedDelay({ scanSafely() }, 0, 60, TimeUnit.SECONDS)
        }
        return START_STICKY
    }

    private fun scanSafely() {
        checked = 0
        failures = 0
        lastError = ""
        status.edit().putString("state", "Сканирование")
            .putLong("cycle_start", System.currentTimeMillis()).apply()
        try {
            val limit = Prefs.scanLimit(this).coerceIn(1, 20)
            val top = BybitApi.getTickers()
                .filter { it.first.endsWith("USDT") && it.second > 500_000.0 }
                .sortedByDescending { it.second }
                .take(limit)
                .map { it.first }
            val symbols = (top + Prefs.pinned(this)).distinct().take(25)
            if (symbols.isEmpty()) {
                status.edit().putString("state", "Нет монет для сканирования").apply()
                return
            }
            ensureLiquidationSocket(symbols)

            val threshold = Prefs.liqThreshold(this)
            for (symbol in symbols) {
                if (Thread.currentThread().isInterrupted) return
                try {
                    val candles = BybitApi.fetchKlines(symbol, Prefs.signalTf(this), 90).reversed()
                    if (candles.size < 25) continue
                    checked++
                    val recent = candles[candles.size - 2]
                    val averageVolume = candles.subList(candles.size - 22, candles.size - 2)
                        .map { it.volume }.average()
                    val volumeRatio = if (averageVolume > 0.0) recent.volume / averageVolume else 0.0
                    val liq = calculateLiquidations(symbol)
                    if (liq != null) MarketState.liqStatsCache[symbol] = liq

                    if (liq != null && liq.total15m >= threshold &&
                        Prefs.notifEnabled(this, "liquidation")) {
                        notifyOnce("liq:$symbol", 30 * 60_000L,
                            "💥 Ликвидации $symbol",
                            "За 15 минут: ${UiUtils.formatMoney(liq.total15m)}; " +
                            if (liq.dominantIsLong) "ликвидированы лонги" else "ликвидированы шорты")
                    }

                    if (volumeRatio >= 5.0 && Prefs.notifEnabled(this, "volume")) {
                        notifyOnce("vol:$symbol", 30 * 60_000L,
                            "📈 Объём $symbol", "Объём свечи выше среднего в ${"%.1f".format(volumeRatio)} раза")
                    }

                    val signal = SignalDetector.detectReversalSignal(candles, liq, threshold)
                    if (signal.type.isNotEmpty() && signal.strength == "strong") {
                        notifyOnce("signal:$symbol:${signal.candleTime}", 24 * 60 * 60_000L,
                            "🎯 Сигнал $symbol",
                            "${if (signal.type == "long") "LONG" else "SHORT"} · ${signal.pattern}")
                    }

                    // Keep order book data available to the UI when it resumes.
                    BybitApi.fetchOrderbookInfo(symbol)?.let { info ->
                        synchronized(MarketState.obLock) { MarketState.obInfos[symbol] = info }
                    }
                } catch (e: Exception) {
                    failures++
                    lastError = e.message ?: e.javaClass.simpleName
                    Log.w("CryptoBackground", "Failed to scan $symbol", e)
                }
            }
            status.edit()
                .putInt("checked", checked)
                .putInt("failures", failures)
                .putLong("last_success", if (checked > 0) System.currentTimeMillis() else status.getLong("last_success", 0L))
                .putString("state", if (checked > 0) "Работает" else "Нет свежих данных")
                .putString("error", lastError)
                .putBoolean("socket_connected", liquidationSocket?.isConnected == true)
                .apply()
        } catch (e: Exception) {
            status.edit().putString("state", "Ошибка сканирования")
                .putString("error", e.message ?: e.javaClass.simpleName)
                .putBoolean("socket_connected", liquidationSocket?.isConnected == true).apply()
            Log.w("CryptoBackground", "Scan cycle failed; retrying", e)
        }
    }

    private fun ensureLiquidationSocket(symbols: List<String>) {
        if (symbols == monitoredSymbols && liquidationSocket != null) return
        liquidationSocket?.close()
        monitoredSymbols = symbols
        liquidationSocket = LiquidationSocket(symbols) { symbol, time, usd, isLong, price ->
            synchronized(MarketState.liqLock) {
                val buffer = MarketState.liqBuffer.getOrPut(symbol) { ArrayList() }
                buffer.add(LiqEvent(time, usd, isLong, price))
                val cutoff = System.currentTimeMillis() - 20 * 60_000L
                buffer.removeAll { it.time < cutoff }
            }
        }.also { it.connect() }
    }

    private fun calculateLiquidations(symbol: String): LiqStats? {
        val events = synchronized(MarketState.liqLock) {
            MarketState.liqBuffer[symbol]?.toList() ?: emptyList()
        }
        val now = System.currentTimeMillis()
        val recent = events.filter { it.time >= now - 15 * 60_000L }
        if (recent.isEmpty()) return null
        val longs = recent.filter { it.isLong }.sumOf { it.usd }
        val shorts = recent.filter { !it.isLong }.sumOf { it.usd }
        val total = longs + shorts
        if (total <= 0.0) return null
        val five = recent.filter { it.time >= now - 5 * 60_000L }.sumOf { it.usd }
        val trend = when {
            five * 3 > total * 1.5 -> "rising"
            five * 3 < total * 0.5 -> "falling"
            else -> "stable"
        }
        return LiqStats(total, longs, shorts, trend)
    }

    private fun notifyOnce(key: String, cooldownMs: Long, title: String, body: String) {
        val now = System.currentTimeMillis()
        if (now - alerts.getLong(key, 0L) < cooldownMs) return
        alerts.edit().putLong(key, now).apply()
        val channel = NotificationHelper.CHANNEL_ID
        NotificationHelper.createNotificationChannel(this)
        val intent = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, channel)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(intent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(key.hashCode(), notification)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(channelId, "Фоновый мониторинг", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onDestroy() {
        running = false
        status.edit().putBoolean("running", false).putString("state", "Остановлен")
            .putBoolean("socket_connected", false).apply()
        worker.shutdownNow()
        liquidationSocket?.close()
        liquidationSocket = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
