package com.cryptotrader.bybit

import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * WebSocket на publicTrade.* для списка символов.
 * Слушает сделки и отдаёт (symbol, time, price, size, isBuy) в callback.
 */
class TradeDeltaSocket(
    private val symbols: List<String>,
    private val onTrade: (symbol: String, time: Long, price: Double, size: Double, isBuy: Boolean) -> Unit
) {
    private val client = BybitNetwork.client
    private val scheduler = BybitNetwork.scheduler

    @Volatile private var ws: WebSocket? = null
    @Volatile private var closedByUser = false
    @Volatile private var reconnectJob: ScheduledFuture<*>? = null
    private val attempts = AtomicInteger(0)

    fun connect() {
        closedByUser = false
        reconnectJob?.cancel(false)
        reconnectJob = null

        val req = Request.Builder()
            .url("wss://stream.bybit.com/v5/public/linear")
            .build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                attempts.set(0)
                // Bybit: до 10 args за раз
                symbols.chunked(10).forEach { chunk ->
                    val args = chunk.joinToString(",") { "\"publicTrade.$it\"" }
                    webSocket.send("""{"op":"subscribe","args":[$args]}""")
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    if (json.has("success") && !json.optBoolean("success", true)) return
                    if (!json.has("topic")) return
                    val topic = json.getString("topic")
                    if (!topic.startsWith("publicTrade.")) return
                    val data = json.getJSONArray("data")
                    for (i in 0 until data.length()) {
                        val t = data.getJSONObject(i)
                        val sym = t.getString("s")
                        val ts = t.getString("T").toLong()
                        val price = t.getString("p").toDoubleOrNull() ?: continue
                        val size = t.getString("v").toDoubleOrNull() ?: continue
                        val side = t.optString("S", "")
                        val isBuy = side == "Buy"
                        onTrade(sym, ts, price, size, isBuy)
                    }
                } catch (_: Exception) {}
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (closedByUser) return
        val n = attempts.incrementAndGet()
        val delay = when {
            n <= 1 -> 2L
            n == 2 -> 4L
            n == 3 -> 8L
            n == 4 -> 16L
            n == 5 -> 30L
            else -> 60L
        }
        reconnectJob = scheduler.schedule({
            if (!closedByUser) connect()
        }, delay, TimeUnit.SECONDS)
    }

    fun close() {
        closedByUser = true
        reconnectJob?.cancel(false)
        reconnectJob = null
        try { ws?.close(1000, "bye") } catch (_: Exception) {}
        ws = null
    }
}
