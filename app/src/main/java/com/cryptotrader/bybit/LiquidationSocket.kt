package com.cryptotrader.bybit

import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LiquidationSocket(
    private val symbols: List<String>,
    private val onLiquidation: (symbol: String, time: Long, usd: Double, isLong: Boolean, price: Double) -> Unit
) {
    private val client = BybitNetwork.client
    private val scheduler = BybitNetwork.scheduler

    @Volatile private var ws: WebSocket? = null
    @Volatile var isConnected: Boolean = false
        private set
    @Volatile private var closedByUser = false
    @Volatile private var reconnectJob: ScheduledFuture<*>? = null
    private val attempts = AtomicInteger(0)

    @Synchronized
    fun connect() {
        if (closedByUser && ws != null) return
        ws?.cancel()
        ws = null
        isConnected = false
        closedByUser = false
        reconnectJob?.cancel(false)
        reconnectJob = null

        val req = Request.Builder()
            .url("wss://stream.bybit.com/v5/public/linear")
            .build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
                attempts.set(0)
                symbols.chunked(10).forEach { chunk ->
                    val args = chunk.joinToString(",") { "\"allLiquidation.$it\"" }
                    webSocket.send("""{"op":"subscribe","args":[$args]}""")
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    if (json.has("success") && !json.optBoolean("success", true)) return
                    if (!json.has("topic")) return
                    val topic = json.getString("topic")
                    if (!topic.startsWith("allLiquidation.")) return
                    val data = json.getJSONArray("data")
                    for (i in 0 until data.length()) {
                        val t = data.getJSONObject(i)
                        val sym = t.getString("s")
                        val ts = t.getString("T").toLong()
                        val side = t.getString("S")
                        val vol = t.getString("v").toDoubleOrNull() ?: 0.0
                        val price = t.getString("p").toDoubleOrNull() ?: 0.0
                        val usd = vol * price
                        // Bybit S is the execution side: Sell closes a liquidated LONG; Buy closes a SHORT.
                        val isLong = side == "Sell"
                        onLiquidation(sym, ts, usd, isLong, price)
                    }
                } catch (_: Exception) {}
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (ws === webSocket) isConnected = false
                if (ws === webSocket && !closedByUser) scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (ws === webSocket && !closedByUser) scheduleReconnect()
            }
        })
    }

    @Synchronized
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
        reconnectJob?.cancel(false)
        reconnectJob = scheduler.schedule({
            if (!closedByUser) connect()
        }, delay, TimeUnit.SECONDS)
    }

    @Synchronized
    fun close() {
        closedByUser = true
        isConnected = false
        reconnectJob?.cancel(false)
        reconnectJob = null
        try { ws?.close(1000, "bye") } catch (_: Exception) {}
        ws = null
    }
}