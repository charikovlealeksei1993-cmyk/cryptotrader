package com.cryptotrader.bybit

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object Persistence {
    private const val FILE_NAME = "state.json"
    private const val VERSION = 1
    const val MAX_AGE_MS = 3 * 60 * 60 * 1000L

    fun save(context: Context, state: AppState) {
        try {
            val file = File(context.filesDir, FILE_NAME)
            file.writeText(encode(state))
        } catch (_: Exception) {}
    }

    fun load(context: Context): AppState? {
        try {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) return null
            val json = JSONObject(file.readText())
            if (json.optInt("version", 0) != VERSION) return null
            val state = decode(json)
            if (System.currentTimeMillis() - state.savedAt > MAX_AGE_MS) return null
            return state
        } catch (_: Exception) { return null }
    }

    private fun encode(state: AppState): String {
        val root = JSONObject()
        root.put("version", VERSION)
        root.put("savedAt", state.savedAt)

        root.put("scanResults", JSONArray().apply {
            state.scanResults.forEach { put(encScanResult(it)) }
        })

        root.put("liqBuffer", JSONObject().apply {
            state.liqBuffer.forEach { (k, v) ->
                put(k, JSONArray().apply { v.forEach { put(encLiqEvent(it)) } })
            }
        })

        root.put("obInfos", JSONObject().apply {
            state.obInfos.forEach { (k, v) -> put(k, encObInfo(v)) }
        })

        root.put("liveSpeeds", JSONObject().apply {
            state.liveSpeeds.forEach { (k, v) -> put(k, encSpeed(v)) }
        })

        root.put("longRatioHistory", JSONObject().apply {
            state.longRatioHistory.forEach { (k, v) ->
                put(k, JSONArray().apply { v.forEach { p ->
                    put(JSONArray().apply { put(p.first); put(p.second) })
                } })
            }
        })

        root.put("oiChangeHistory", JSONObject().apply {
            state.oiChangeHistory.forEach { (k, v) ->
                put(k, JSONArray().apply { v.forEach { put(it) } })
            }
        })

        root.put("signalHistory", JSONObject().apply {
            state.signalHistory.forEach { (k, v) -> put(k, encSymbolSignal(v)) }
        })

        root.put("signalMarkers", JSONObject().apply {
            state.signalMarkers.forEach { (k, v) ->
                put(k, JSONArray().apply { v.forEach { put(encSignalMarker(it)) } })
            }
        })

        return root.toString()
    }

    private fun decode(root: JSONObject): AppState {
        val savedAt = root.getLong("savedAt")

        val scanResults = ArrayList<ScanResult>()
        val srArr = root.optJSONArray("scanResults") ?: JSONArray()
        for (i in 0 until srArr.length()) scanResults.add(decScanResult(srArr.getJSONObject(i)))

        val liqBuffer = HashMap<String, List<LiqEvent>>()
        val lbObj = root.optJSONObject("liqBuffer") ?: JSONObject()
        lbObj.keys().forEach { k ->
            val arr = lbObj.getJSONArray(k)
            val events = ArrayList<LiqEvent>()
            for (i in 0 until arr.length()) events.add(decLiqEvent(arr.getJSONObject(i)))
            liqBuffer[k] = events
        }

        val obInfos = HashMap<String, OrderbookInfo>()
        val obObj = root.optJSONObject("obInfos") ?: JSONObject()
        obObj.keys().forEach { k -> obInfos[k] = decObInfo(obObj.getJSONObject(k)) }

        val liveSpeeds = HashMap<String, SpeedReading>()
        val lsObj = root.optJSONObject("liveSpeeds") ?: JSONObject()
        lsObj.keys().forEach { k -> liveSpeeds[k] = decSpeed(lsObj.getJSONObject(k)) }

        val longRatioHistory = HashMap<String, List<Pair<Long, Double>>>()
        val lrObj = root.optJSONObject("longRatioHistory") ?: JSONObject()
        lrObj.keys().forEach { k ->
            val arr = lrObj.getJSONArray(k)
            val list = ArrayList<Pair<Long, Double>>()
            for (i in 0 until arr.length()) {
                val p = arr.getJSONArray(i)
                list.add(p.getLong(0) to p.getDouble(1))
            }
            longRatioHistory[k] = list
        }

        val oiChangeHistory = HashMap<String, List<Double>>()
        val oiObj = root.optJSONObject("oiChangeHistory") ?: JSONObject()
        oiObj.keys().forEach { k ->
            val arr = oiObj.getJSONArray(k)
            val list = ArrayList<Double>()
            for (i in 0 until arr.length()) list.add(arr.getDouble(i))
            oiChangeHistory[k] = list
        }

        val signalHistory = HashMap<String, SymbolSignal>()
        val sigObj = root.optJSONObject("signalHistory") ?: JSONObject()
        sigObj.keys().forEach { k -> signalHistory[k] = decSymbolSignal(sigObj.getJSONObject(k)) }

        val signalMarkers = HashMap<String, List<SignalMarker>>()
        val smObj = root.optJSONObject("signalMarkers") ?: JSONObject()
        smObj.keys().forEach { k ->
            val arr = smObj.getJSONArray(k)
            val list = ArrayList<SignalMarker>()
            for (i in 0 until arr.length()) list.add(decSignalMarker(arr.getJSONObject(i)))
            signalMarkers[k] = list
        }

        return AppState(savedAt, scanResults, liqBuffer, obInfos, liveSpeeds,
            longRatioHistory, oiChangeHistory, signalHistory, signalMarkers)
    }

    private fun encSignalMarker(v: SignalMarker): JSONObject = JSONObject().apply {
        put("time", v.time)
        put("type", v.type)
        put("pattern", v.pattern)
        put("sourceTf", v.sourceTf)
    }

    private fun decSignalMarker(o: JSONObject): SignalMarker = SignalMarker(
        o.optLong("time", 0L),
        o.optString("type", ""),
        o.optString("pattern", ""),
        o.optString("sourceTf", "")
    )

    private fun encSymbolSignal(v: SymbolSignal): JSONObject = JSONObject().apply {
        put("type", v.type); put("strength", v.strength)
        put("candleTime", v.candleTime); put("detectedAt", v.detectedAt)
        put("price", v.price)
    }

    private fun decSymbolSignal(o: JSONObject): SymbolSignal = SymbolSignal(
        o.optString("type", ""),
        o.optString("strength", ""),
        o.optLong("candleTime", 0L),
        o.optLong("detectedAt", 0L),
        o.optDouble("price", 0.0)
    )

    private fun encScanResult(r: ScanResult): JSONObject = JSONObject().apply {
        put("symbol", r.symbol); put("price", r.price); put("change15m", r.change15m)
        put("turnover24h", r.turnover24h); put("turnover1h", r.turnover1h)
        put("longRatio", r.longRatio); put("shortRatio", r.shortRatio)
        put("volRatio", r.volRatio); put("turnover1m", r.turnover1m)
        put("lastSignalTime", r.lastSignalTime); put("openInterest", r.openInterest)
        put("openInterestChange", r.openInterestChange); put("longRatioChange", r.longRatioChange)
        put("oiTrend", r.oiTrend); put("signalType", r.signalType)
        put("signalStrength", r.signalStrength); put("signalCandleTime", r.signalCandleTime)
        put("signalPattern", r.signalPattern)
        put("peakForecast", r.peakForecast); put("peakLow", r.peakLow)
        put("peakHigh", r.peakHigh); put("peakRemainPct", r.peakRemainPct)
        put("peakConfident", r.peakConfident)
        put("bidWallPrice", r.bidWallPrice); put("bidWallUsd", r.bidWallUsd)
        put("askWallPrice", r.askWallPrice); put("askWallUsd", r.askWallUsd)
        put("signals", JSONArray().apply {
            r.signals.forEach { s ->
                put(JSONObject().apply {
                    put("type", s.type); put("emoji", s.emoji)
                    put("name", s.name); put("detail", s.detail)
                })
            }
        })
        r.accumulation?.let { acc ->
            put("acc", JSONObject().apply {
                put("type", acc.type)
                put("level", acc.level)
                put("oiChangePct", acc.oiChangePct)
                put("oiBarsPositive", acc.oiBarsPositive)
                put("rangePct", acc.rangePct)
                put("deltaUsd", acc.deltaUsd)
            })
        }
    }

    private fun decScanResult(o: JSONObject): ScanResult {
        val sigArr = o.optJSONArray("signals") ?: JSONArray()
        val signals = ArrayList<Signal>()
        for (i in 0 until sigArr.length()) {
            val s = sigArr.getJSONObject(i)
            signals.add(Signal(
                s.optString("type"), s.optString("emoji"),
                s.optString("name"), s.optString("detail")
            ))
        }
        val accumulation = o.optJSONObject("acc")?.let { a ->
            AccumulationInfo(
                type = a.optString("type", ""),
                level = a.optString("level", ""),
                oiChangePct = a.optDouble("oiChangePct", 0.0),
                oiBarsPositive = a.optInt("oiBarsPositive", 0),
                rangePct = a.optDouble("rangePct", 0.0),
                deltaUsd = a.optDouble("deltaUsd", 0.0)
            )
        }
        return ScanResult(
            symbol = o.getString("symbol"), price = o.getDouble("price"),
            change15m = o.getDouble("change15m"), turnover24h = o.getDouble("turnover24h"),
            turnover1h = o.getDouble("turnover1h"), longRatio = o.getDouble("longRatio"),
            shortRatio = o.getDouble("shortRatio"), volRatio = o.getDouble("volRatio"),
            turnover1m = o.getDouble("turnover1m"), signals = signals,
            lastSignalTime = o.optLong("lastSignalTime", System.currentTimeMillis()),
            openInterest = o.optDouble("openInterest", 0.0),
            openInterestChange = o.optDouble("openInterestChange", 0.0),
            longRatioChange = o.optDouble("longRatioChange", 0.0),
            oiTrend = o.optString("oiTrend", "stable"),
            signalType = o.optString("signalType", ""),
            signalStrength = o.optString("signalStrength", ""),
            signalCandleTime = o.optLong("signalCandleTime", 0L),
            signalPattern = o.optString("signalPattern", ""),
            peakForecast = o.optDouble("peakForecast", 0.0),
            peakLow = o.optDouble("peakLow", 0.0),
            peakHigh = o.optDouble("peakHigh", 0.0),
            peakRemainPct = o.optDouble("peakRemainPct", 0.0),
            peakConfident = o.optBoolean("peakConfident", false),
            bidWallPrice = o.optDouble("bidWallPrice", 0.0),
            bidWallUsd = o.optDouble("bidWallUsd", 0.0),
            askWallPrice = o.optDouble("askWallPrice", 0.0),
            askWallUsd = o.optDouble("askWallUsd", 0.0),
            accumulation = accumulation
        )
    }

    private fun encLiqEvent(e: LiqEvent): JSONObject = JSONObject().apply {
        put("time", e.time); put("usd", e.usd); put("isLong", e.isLong); put("price", e.price)
    }

    private fun decLiqEvent(o: JSONObject): LiqEvent = LiqEvent(
        o.getLong("time"), o.getDouble("usd"),
        o.getBoolean("isLong"), o.optDouble("price", 0.0)
    )

    private fun encObInfo(v: OrderbookInfo): JSONObject = JSONObject().apply {
        put("imbalance", v.imbalance)
        put("bidWallPrice", v.bidWallPrice); put("bidWallUsd", v.bidWallUsd)
        put("askWallPrice", v.askWallPrice); put("askWallUsd", v.askWallUsd)
    }

    private fun decObInfo(o: JSONObject): OrderbookInfo = OrderbookInfo(
        o.getDouble("imbalance"),
        o.optDouble("bidWallPrice", 0.0), o.optDouble("bidWallUsd", 0.0),
        o.optDouble("askWallPrice", 0.0), o.optDouble("askWallUsd", 0.0)
    )

    private fun encSpeed(v: SpeedReading): JSONObject = JSONObject().apply {
        put("speedAt15", v.speedAt15); put("speedAt30", v.speedAt30)
        put("speedAt45", v.speedAt45); put("speedNow", v.speedNow)
        put("accelerating", v.accelerating); put("dying", v.dying)
        put("dyingUp", v.dyingUp); put("dyingDown", v.dyingDown)
        put("points", JSONArray().apply { v.points.forEach { p ->
            put(JSONArray().apply { put(p.first); put(p.second) })
        } })
    }

    private fun decSpeed(o: JSONObject): SpeedReading {
        val pts = ArrayList<Pair<Long, Double>>()
        val arr = o.optJSONArray("points") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val p = arr.getJSONArray(i)
            pts.add(p.getLong(0) to p.getDouble(1))
        }
        return SpeedReading(
            o.getDouble("speedAt15"), o.getDouble("speedAt30"),
            o.getDouble("speedAt45"), o.getDouble("speedNow"),
            o.getBoolean("accelerating"), o.getBoolean("dying"),
            o.getBoolean("dyingUp"), o.getBoolean("dyingDown"), pts
        )
    }
}
