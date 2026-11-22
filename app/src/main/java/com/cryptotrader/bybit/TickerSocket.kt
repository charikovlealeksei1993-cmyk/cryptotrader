package com.cryptotrader.bybit

import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TickerSocket(
    private val symbols: List<String>,
    private val onPrice: (symbol: String, price: Double) -> Unit
) {
    private val client = BybitNetwork.client
    private val scheduler = BybitNetwork.scheduler

    @Volatile private var ws: WebSocket? = null
    @Volatile private var closedByUser = false
    @Volatile private var reconnectJob: ScheduledFuture<*>? = null
    private val attempts = AtomicInteger(0)

    @Synchronized
    fun connect() {
        if (closedByUser && ws != null) return
        ws?.cancel()
        ws = null
        closedByUser = false
        reconnectJob?.cancel(false)
        reconnectJob = null

        val req = Request.Builder()
            .url("wss://stream.bybit.com/v5/public/linear")
            .build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                attempts.set(0)
                symbols.chunked(10).forEach { chunk ->
                    val args = chunk.joinToString(",") { "\"tickers.$it\"" }
                    webSocket.send("""{"op":"subscribe","args":[$args]}""")
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    if (json.has("success") && !json.optBoolean("success", true)) return
                    if (!json.has("topic")) return
                    val topic = json.getString("topic")
                    if (!topic.startsWith("tickers.")) return
                    val sym = topic.substring("tickers.".length)
                    if (!json.has("data")) return
                    val data = json.getJSONObject("data")
                    val lp = data.optString("lastPrice", "")
                    val price = lp.toDoubleOrNull() ?: return
                    if (price > 0) onPrice(sym, price)
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

    @Synchronized
    private fun scheduleReconnect() {
        if (closedByUser) return
        val n = attempts.incrementAndGet()
        val delay = when {
            n <= 1 -> 2L; n == 2 -> 4L; n == 3 -> 8L; n == 4 -> 16L; n == 5 -> 30L; else -> 60L
        }
        reconnectJob = scheduler.schedule({ if (!closedByUser) connect() }, delay, TimeUnit.SECONDS)
    }

    @Synchronized
    fun close() {
        closedByUser = true
        reconnectJob?.cancel(false)
        reconnectJob = null
        try { ws?.close(1000, "bye") } catch (_: Exception) {}
        ws = null
    }
}