package com.cryptotrader.bybit

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Общий сетевой движок для всех сокетов приложения. */
object BybitNetwork {
    val client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(2)
}

class TradeSocket(
    private val symbol: String,
    private val onTrade: (time: Long, price: Double, size: Double, isBuy: Boolean) -> Unit
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
                webSocket.send("""{"op":"subscribe","args":["publicTrade.$symbol"]}""")
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
                        val ts = t.getString("T").toLong()
                        val price = t.getString("p").toDouble()
                        val size = t.getString("v").toDouble()
                        val isBuy = t.optString("S", "") == "Buy"
                        onTrade(ts, price, size, isBuy)
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