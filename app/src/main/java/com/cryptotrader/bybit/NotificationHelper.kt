package com.cryptotrader.bybit

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlin.math.max

object NotificationHelper {

    val CHANNEL_ID = "crypto_trader_signals_v2"

    private const val NOTIFY_COOLDOWN_MS = 60 * 60 * 1000L
    private const val NOTIFIED_TTL = 60 * 1000L
    private const val SIGNAL_NOTIFY_COOLDOWN_MS = 5 * 60 * 1000L

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.deleteNotificationChannel("crypto_trader_signals")
            } catch (_: Exception) {}
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Сигналы Crypto Trader",
                NotificationManager.IMPORTANCE_HIGH
            )
            channel.description = "Оповещения при резких скачках и входах"
            channel.enableVibration(true)
            channel.enableLights(true)
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    fun requestNotificationPermission(activity: android.app.Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                activity.requestPermissions(
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001
                )
            }
        }
    }

    fun notificationPriority(symbol: String): Int {
        val type = synchronized(MarketState.notifiedCooldown) {
            MarketState.notifiedType[symbol]
        } ?: return 0
        val t = synchronized(MarketState.notifiedCooldown) {
            MarketState.notifiedTime[symbol]
        } ?: 0L
        if (System.currentTimeMillis() - t > NOTIFIED_TTL) return 0
        return when (type) {
            "liquidation" -> 3
            "speed" -> 2
            "volume" -> 1
            else -> 0
        }
    }

    fun checkNotifications(context: Context, scanResults: List<ScanResult>) {
        val now = System.currentTimeMillis()
        MarketState.notifiedCooldown.entries.removeAll { now - it.value > NOTIFY_COOLDOWN_MS }

        val liqThreshold = Prefs.liqThreshold(context)

        for (r in scanResults) {
            val reasons = ArrayList<String>()
            var type = ""
            var isLiquidation = false

            if (Prefs.notifEnabled(context, "liquidation")) {
                val liq = MarketState.liqStatsCache[r.symbol]
                val active = liq != null && liq.total15m >= liqThreshold

                var triggerNow = false
                synchronized(MarketState.liquidationActiveLock) {
                    if (active && r.symbol !in MarketState.liquidationActive) {
                        MarketState.liquidationActive.add(r.symbol)
                        triggerNow = true
                    } else if (!active) {
                        MarketState.liquidationActive.remove(r.symbol)
                    }
                }

                if (triggerNow && liq != null) {
                    type = "liquidation"
                    isLiquidation = true
                    val who = if (liq.dominantIsLong) "🐂 лонги" else "🐻 шорты"
                    val domPct = if (liq.dominantIsLong) liq.longPct else liq.shortPct
                    reasons.add("💥 Ликвидации ${UiUtils.formatMoney(liq.total15m)} · $who ${"%.0f".format(domPct)}%")
                }
            }

            if (!isLiquidation && MarketState.notifiedCooldown.containsKey(r.symbol)) continue

            if (type.isEmpty() && Prefs.notifEnabled(context, "speed")) {
                val sp = synchronized(MarketState.liveLock) { MarketState.liveSpeeds[r.symbol] }
                if (sp != null && sp.accelerating && sp.speedNow >= 0.5) {
                    type = "speed"
                    reasons.add("⚡ Ускорение ×${"%.1f".format(sp.speedNow / max(sp.speedAt15, 0.01))} · ${"%.2f".format(sp.speedNow)}%/мин")
                }
            }

            if (type.isEmpty()) {
                if (Prefs.notifEnabled(context, "volume") && r.volRatio >= 5.0) {
                    type = "volume"
                    reasons.add("📈 Аномальный объём ×${"%.1f".format(r.volRatio)}")
                }
                if (Prefs.notifEnabled(context, "turnover") && r.turnover1m >= 200_000.0) {
                    if (type.isEmpty()) type = "volume"
                    reasons.add("💰 Оборот ${UiUtils.formatMoney(r.turnover1m)} за минуту")
                }
            }

            if (reasons.isNotEmpty() && type.isNotEmpty()) {
                vibrate(context, 300)
                sendNotificationMulti(context, r, reasons, type)
                MarketState.notifiedCooldown[r.symbol] = now
                synchronized(MarketState.notifiedCooldown) {
                    MarketState.notifiedType[r.symbol] = type
                    MarketState.notifiedTime[r.symbol] = now
                }
            }
        }

        for (r in scanResults) {
            if (r.signalType.isEmpty()) continue
            // All detected patterns can notify; suppress repetitions of the same move.
            val key = "${r.symbol}_${r.signalCandleTime}"
            val prevKey = synchronized(MarketState.notifiedCooldown) {
                MarketState.notifiedSignalKey[r.symbol]
            }
            if (prevKey == "$key" + "_" + r.signalType) continue
            val lastT = synchronized(MarketState.notifiedCooldown) {
                MarketState.notifiedSignalTime[r.symbol] ?: 0L
            }
            val previousDirection = synchronized(MarketState.notifiedCooldown) {
                MarketState.notifiedSignalKey[r.symbol]?.substringAfterLast("_")
            }
            if (previousDirection == r.signalType &&
                now - lastT < SIGNAL_NOTIFY_COOLDOWN_MS) continue
            synchronized(MarketState.notifiedCooldown) {
                MarketState.notifiedSignalKey[r.symbol] = "$key" + "_" + r.signalType
                MarketState.notifiedSignalTime[r.symbol] = now
            }
            sendSignalNotification(context, r)
            synchronized(MarketState.signalCooldown) { MarketState.signalCooldown[r.symbol] = now }
        }
    }

    private fun signalTier(r: ScanResult): String {
        val points = Regex("([0-9]+)/100").find(r.signalPattern)
            ?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return when {
            points >= 85 -> "very_strong"
            points >= 70 -> "strong"
            points >= 50 -> "medium"
            else -> "weak"
        }
    }

    fun sendSignalNotification(context: Context, r: ScanResult) {
        if (!Prefs.signalNotificationEnabled(context, r.signalType, signalTier(r))) return
        try {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra("symbol", r.symbol)
            }
            val pending = PendingIntent.getActivity(
                context, r.symbol.hashCode() + 2000, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val patternStr = if (r.signalPattern.isNotEmpty()) " (${r.signalPattern})" else ""
            val label = if (r.signalType == "long") "🟢 LONG$patternStr"
                        else "🔴 SHORT$patternStr"
            val title = "$label — ${r.symbol}"
            val body = buildString {
                appendLine("Цена: ${"%.4f".format(r.price)} · 15м: ${"%+.2f".format(r.change15m)}%")
                val liq = MarketState.liqStatsCache[r.symbol]
                if (liq != null) {
                    val who = if (liq.dominantIsLong) "🐂 лонги" else "🐻 шорты"
                    appendLine("💥 Ликвидации: ${UiUtils.formatMoney(liq.total15m)} · $who")
                }
                if (r.peakForecast > 0) {
                    appendLine("🎯 Зона: ${"%.4f".format(r.peakForecast)} (±${"%.1f".format(r.peakRemainPct)}%)")
                }
            }.trim()

            val liqTotal = MarketState.liqStatsCache[r.symbol]?.total15m ?: 0.0
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(title)
                .setContentText("Цена ${"%.4f".format(r.price)} · Ликв ${UiUtils.formatMoney(liqTotal)}")
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setSilent(true)

            if (Build.VERSION.SDK_INT < 33 ||
                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED) {
                try {
                    NotificationManagerCompat.from(context)
                        .notify(r.symbol.hashCode() + 2000, builder.build())
                } catch (_: SecurityException) {}
                val tier = signalTier(r)
                if (Prefs.signalSoundEnabled(context, tier)) {
                    playSoundFor(context, "signal_${r.signalType}_$tier")
                }
            }
            vibrate(context, 500)
        } catch (_: Exception) {}
    }

    fun sendNotificationMulti(context: Context, r: ScanResult, reasons: List<String>, type: String) {
        try {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra("symbol", r.symbol)
            }
            val pending = PendingIntent.getActivity(
                context, r.symbol.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val typeEmoji = when (type) {
                "liquidation" -> "💥"
                "speed" -> "⚡"
                "volume" -> "📈"
                else -> "🚀"
            }
            val typeLabel = when (type) {
                "liquidation" -> "Ликвидации"
                "speed" -> "Ускорение"
                "volume" -> "Объём"
                else -> "Сигнал"
            }
            val title = "$typeEmoji ${r.symbol} — $typeLabel"
            val body = buildString {
                reasons.forEach { appendLine(it) }
                append("Цена: ${"%.4f".format(r.price)} · 15м: ${"%+.2f".format(r.change15m)}%")
            }
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(title)
                .setContentText(reasons.first())
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setSilent(true)

            if (Build.VERSION.SDK_INT < 33 ||
                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED) {
                try {
                    NotificationManagerCompat.from(context)
                        .notify(r.symbol.hashCode(), builder.build())
                } catch (_: SecurityException) {}
                playSoundFor(context, type)
            }
        } catch (_: Exception) {}
    }

    fun playSoundFor(context: Context, type: String) {
        try {
            val fallback = if (type.startsWith("signal_")) {
                // Different pitches by strength and direction are handled by
                // user-selected sounds; default system tone remains audible.
                Prefs.soundUriFor(context, type.substringAfter("signal_").substringBeforeLast("_").let { "signal_$it" })
                    ?: Prefs.soundUriFor(context, "signal_" + type.substringAfterLast("_"))
                    ?: Prefs.soundUriFor(context, "signal")
            } else null
            val uri = Prefs.soundUriFor(context, type)
                ?: fallback
                ?: Prefs.soundUri(context)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(context.applicationContext, uri)?.play()
        } catch (_: Exception) {}
    }

    fun vibrate(context: Context, ms: Long) {
        try {
            val vib = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(ms)
            }
        } catch (_: Exception) {}
    }
}