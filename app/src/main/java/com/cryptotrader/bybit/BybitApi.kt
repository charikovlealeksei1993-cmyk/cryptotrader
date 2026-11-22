package com.cryptotrader.bybit

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object BybitApi {

    private const val TICKERS_TTL_MS = 5 * 60 * 1000L
    @Volatile private var cachedTickers: List<Pair<String, Double>>? = null
    @Volatile private var cachedTickersTime: Long = 0L

    fun httpGet(urlStr: String, connectMs: Int = 10000, readMs: Int = 15000): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = connectMs
        conn.readTimeout = readMs
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        conn.setRequestProperty("Referer", "https://www.bybit.com")
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                throw RuntimeException("HTTP $code: ${err.take(200)}")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    fun fetchTickers(): List<Pair<String, Double>> {
        val body = httpGet("https://api.bybit.com/v5/market/tickers?category=linear")
        val json = JSONObject(body)
        val arr = json.getJSONObject("result").getJSONArray("list")
        val out = ArrayList<Pair<String, Double>>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val sym = o.getString("symbol")
            val turnover = o.optString("turnover24h", "0").toDoubleOrNull() ?: 0.0
            out.add(sym to turnover)
        }
        return out
    }

    fun getTickers(): List<Pair<String, Double>> {
        val now = System.currentTimeMillis()
        val cached = cachedTickers
        if (cached != null && now - cachedTickersTime < TICKERS_TTL_MS) return cached
        val fresh = fetchTickers()
        cachedTickers = fresh
        cachedTickersTime = now
        return fresh
    }

    fun fetchKlines(symbol: String, tf: String, limit: Int): List<Candle> {
        val body = httpGet(
            "https://api.bybit.com/v5/market/kline?category=linear&symbol=$symbol&interval=$tf&limit=$limit",
            15000, 20000
        )
        val json = JSONObject(body)
        val list = json.getJSONObject("result").getJSONArray("list")
        val out = ArrayList<Candle>(list.length())
        for (i in 0 until list.length()) {
            val a = list.getJSONArray(i)
            out.add(Candle(
                a.getString(0).toLong(),
                a.getString(1).toDouble(),
                a.getString(2).toDouble(),
                a.getString(3).toDouble(),
                a.getString(4).toDouble(),
                a.getString(5).toDouble()
            ))
        }
        return out
    }

    fun fetchLongShortRatio(symbol: String): Pair<Double, Double>? {
        return try {
            val body = httpGet("https://api.bybit.com/v5/market/account-ratio?category=linear&symbol=$symbol&period=5min&limit=1")
            val json = JSONObject(body)
            val list = json.getJSONObject("result").getJSONArray("list")
            if (list.length() == 0) return null
            val o = list.getJSONObject(0)
            o.getString("buyRatio").toDouble() to o.getString("sellRatio").toDouble()
        } catch (_: Exception) { null }
    }

    fun fetchLsHistory(symbol: String, period: String, limit: Int): List<LsPoint> {
        return try {
            val body = httpGet(
                "https://api.bybit.com/v5/market/account-ratio?category=linear&symbol=$symbol&period=$period&limit=$limit",
                6000, 8000
            )
            val json = JSONObject(body)
            val list = json.getJSONObject("result").getJSONArray("list")
            val out = ArrayList<LsPoint>(list.length())
            for (i in 0 until list.length()) {
                val o = list.getJSONObject(i)
                val buy = o.getString("buyRatio").toDouble() * 100
                val sell = o.getString("sellRatio").toDouble() * 100
                val t = o.getString("timestamp").toLong()
                out.add(LsPoint(t, buy, sell))
            }
            out.reversed()
        } catch (_: Exception) { emptyList() }
    }

    fun fetchRecentTrades(symbol: String, limit: Int = 200): List<Trade> {
        return try {
            val body = httpGet(
                "https://api.bybit.com/v5/market/recent-trade?category=linear&symbol=$symbol&limit=$limit",
                6000, 8000
            )
            val json = JSONObject(body)
            val arr = json.getJSONObject("result").getJSONArray("list")
            val out = ArrayList<Trade>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Trade(
                    o.getString("time").toLong(),
                    o.getString("price").toDouble(),
                    o.getString("size").toDouble()
                ))
            }
            out.reversed()
        } catch (_: Exception) { emptyList() }
    }


    /**
     * Signed aggressive trade flow from Bybit recent-trade.
     * Buy is buyer-initiated; Sell is seller-initiated.
     * Null means missing or insufficient evidence, never a zero delta.
     */
    fun fetchAggressiveDelta(symbol: String, limit: Int = 500): Double? {
        return try {
            val body = httpGet(
                "https://api.bybit.com/v5/market/recent-trade?category=linear&symbol=$symbol&limit=$limit",
                6000, 8000
            )
            val arr = JSONObject(body).getJSONObject("result").getJSONArray("list")
            if (arr.length() < 20) return null
            var buyUsd = 0.0
            var sellUsd = 0.0
            for (i in 0 until arr.length()) {
                val trade = arr.getJSONObject(i)
                val usd = trade.getString("price").toDouble() * trade.getString("size").toDouble()
                when (trade.optString("side")) {
                    "Buy" -> buyUsd += usd
                    "Sell" -> sellUsd += usd
                }
            }
            val total = buyUsd + sellUsd
            if (total <= 0.0) null else (buyUsd - sellUsd) / total
        } catch (_: Exception) { null }
    }

    fun fetchOrderbookInfo(symbol: String): OrderbookInfo? {
        return try {
            val body = httpGet(
                "https://api.bybit.com/v5/market/orderbook?category=linear&symbol=$symbol&limit=50",
                5000, 6000
            )
            val json = JSONObject(body)
            val result = json.getJSONObject("result")
            val bids = result.getJSONArray("b")
            val asks = result.getJSONArray("a")
            var bidVol = 0.0; var askVol = 0.0
            var bidWallVol = 0.0; var bidWallPrice = 0.0
            var askWallVol = 0.0; var askWallPrice = 0.0
            val top = 20
            val bidCount = minOf(top, bids.length())
            val askCount = minOf(top, asks.length())
            for (i in 0 until bidCount) {
                val row = bids.getJSONArray(i)
                val price = row.getString(0).toDoubleOrNull() ?: continue
                val vol = row.getString(1).toDoubleOrNull() ?: continue
                bidVol += vol
                if (vol > bidWallVol) { bidWallVol = vol; bidWallPrice = price }
            }
            for (i in 0 until askCount) {
                val row = asks.getJSONArray(i)
                val price = row.getString(0).toDoubleOrNull() ?: continue
                val vol = row.getString(1).toDoubleOrNull() ?: continue
                askVol += vol
                if (vol > askWallVol) { askWallVol = vol; askWallPrice = price }
            }
            val total = bidVol + askVol
            val imb = if (total <= 0.0) 0.0 else (bidVol - askVol) / total
            OrderbookInfo(
                imbalance = imb,
                bidWallPrice = bidWallPrice,
                bidWallUsd = bidWallVol * bidWallPrice,
                askWallPrice = askWallPrice,
                askWallUsd = askWallVol * askWallPrice
            )
        } catch (_: Exception) { null }
    }

    /**
     * История OI: последние [bars] закрытых 5-минутных баров.
     * Возвращает от старых к новым (стабильный рост = монотонное увеличение).
     */
    fun fetchOiHistory(symbol: String, bars: Int = 9, interval: String = "5min"): List<Double>? {
        return try {
            val body = httpGet(
                "https://api.bybit.com/v5/market/open-interest?category=linear&symbol=$symbol&intervalTime=$interval&limit=$bars",
                5000, 6000
            )
            val json = JSONObject(body)
            val list = json.getJSONObject("result").getJSONArray("list")
            if (list.length() < 2) return null
            val out = ArrayList<Double>(list.length())
            for (i in list.length() - 1 downTo 0) {
                val o = list.getJSONObject(i)
                val oi = o.getString("openInterest").toDoubleOrNull() ?: return null
                out.add(oi)
            }
            out
        } catch (_: Exception) { null }
    }

    fun fetchFundingInfo(symbol: String): FundingInfo? {
        return try {
            val body = httpGet(
                "https://api.bybit.com/v5/market/tickers?category=linear&symbol=$symbol",
                5000, 6000
            )
            val json = JSONObject(body)
            val list = json.getJSONObject("result").getJSONArray("list")
            if (list.length() == 0) return null
            val o = list.getJSONObject(0)
            val rate = o.optString("fundingRate", "0").toDoubleOrNull() ?: return null
            val nextTime = o.optString("nextFundingTime", "0").toLongOrNull() ?: 0L
            FundingInfo(rate, nextTime)
        } catch (_: Exception) { null }
    }
}