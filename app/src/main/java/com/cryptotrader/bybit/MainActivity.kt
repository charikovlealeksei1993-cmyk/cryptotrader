package com.cryptotrader.bybit

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.analytics.FirebaseAnalytics
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {

    companion object {
        @Volatile
        var sharedScanResults: List<ScanResult> = ArrayList()
    }

    @Volatile
    private var isDestroyed = false

    private lateinit var root: LinearLayout
    private lateinit var listFrame: FrameLayout
    private lateinit var scanOverlay: LinearLayout
    private lateinit var scanOverlayText: TextView
    private lateinit var listContainer: LinearLayout
    private lateinit var listScroll: ScrollView
    private lateinit var chartView: ChartView
    private lateinit var chartTitle: TextView
    private lateinit var backButton: Button
    private lateinit var refreshButton: Button
    private lateinit var settingsButton: Button
    private lateinit var portfolioButton: Button
    private lateinit var balanceLabel: TextView
    private lateinit var tfRow: LinearLayout
    private lateinit var tfButtons: MutableList<Button>

    private var firebaseAnalytics: FirebaseAnalytics? = null
    private var scanResults: List<ScanResult>
        get() = sharedScanResults
        set(value) { sharedScanResults = value }
    private var currentSymbol: String = "BTCUSDT"
    private var currentTf: String = "1"
    private var currentTfLabel: String = "1м"
    private var currentTfMinutes: Int = 1
    private var allCandles: List<Candle> = emptyList()
    private var visibleCount = 100
    private var offset = 0


    private val scanner = Handler(Looper.getMainLooper())
    private val scanInterval = 30_000L
    private var isChartMode = false
    private var isScanning = false
    private var firstScanDone = false

    private val executor = Executors.newCachedThreadPool()


    private var tradeSocket: TradeSocket? = null
    private var lastChartUpdate = 0L
    private val chartUpdateInterval = 50L

    private var renderScheduled = false
    private val renderRunnable = Runnable {
        renderScheduled = false
        renderList()
    }
    private fun requestRender(delayMs: Long = 500) {
        if (renderScheduled) return
        renderScheduled = true
        scanner.postDelayed(renderRunnable, delayMs)
    }


    private var liqSocket: LiquidationSocket? = null
    private var currentLiqSymbols: List<String> = emptyList()


    private val tradeDeltaAnalyzer = TradeDeltaAnalyzer()
    private var tradeDeltaSocket: TradeDeltaSocket? = null
    private var currentDeltaSymbols: List<String> = emptyList()

    private var tickerSocket: TickerSocket? = null
    private var currentTickerSymbols: List<String> = emptyList()





    private data class ChartCache(
        val candles: List<Candle>,
        val ls: List<LsPoint>,
        val timestamp: Long
    )
    private val chartCache = HashMap<String, ChartCache>()
    private val chartCacheLock = Any()

    private val MAX_PINNED = 3
    private val liveInterval = 5_000L
    private val obInterval = 5_000L
    private val obHistorySize = 12
    private val SIGNAL_COOLDOWN_MS = 30 * 60 * 1000L
    private val KEEP_OLD_MS = 10 * 60 * 1000L
    private val CHART_CACHE_TTL_MS = 60_000L

    private var chartLoading = false










        private val scanRunnable = object : Runnable {
        override fun run() {
            if (!isChartMode) startScan()
            scanner.postDelayed(this, scanInterval)
        }
    }

    private val chartRefreshRunnable = object : Runnable {
        override fun run() {
            if (isChartMode && !chartLoading) refreshChart()
            scanner.postDelayed(this, 30_000L)
        }
    }

    private val liveRunnable = object : Runnable {
        override fun run() {
            refreshLiveSpeeds()
            refreshLiqStatsCache()
            scanner.postDelayed(this, liveInterval)
        }
    }

    private val orderbookRunnable = object : Runnable {
        override fun run() {
            refreshOrderbook()
            scanner.postDelayed(this, obInterval)
        }
    }

    private val deltaRunnable = object : Runnable {
        override fun run() {
            updateRealDelta()
            scanner.postDelayed(this, 5_000L)
        }
    }

    private val autoCloseRunnable = object : Runnable {
        override fun run() {
            try {
                val closed = PaperTrading.checkAutoClose(this@MainActivity) { sym ->
                    scanResults.firstOrNull { it.symbol == sym }?.price ?: 0.0
                }
                if (closed > 0) {
                    requestRender(0)
                    updateBalanceLabel()
                }
            } catch (_: Exception) {}
            scanner.postDelayed(this, 5_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            firebaseAnalytics = FirebaseAnalytics.getInstance(this)
        } catch (e: Exception) {
            android.util.Log.e("Cryptotrader", "Firebase init failed: ${e.message}")
        }

        try {
            PaperTrading.load(this)
        } catch (_: Exception) {}

        try {
            val serviceIntent = Intent(this, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            android.util.Log.e("Cryptotrader", "Service start failed: ${e.message}")
        }

        synchronized(MarketState.pinnedLock) {
            MarketState.pinnedSymbols.clear()
            MarketState.pinnedSymbols.addAll(Prefs.pinned(this).take(MAX_PINNED))
        }

        NotificationHelper.createNotificationChannel(this)
        NotificationHelper.requestNotificationPermission(this)
        buildUi()
        applyOrientation()
        loadState()

        root.setOnApplyWindowInsetsListener { v, insets ->
            val top: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                top = bars.top
                bottom = bars.bottom
            } else {
                @Suppress("DEPRECATION")
                top = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                bottom = insets.systemWindowInsetBottom
            }
            v.setPadding(0, top, 0, bottom)
            insets
        }

        val sym = intent?.getStringExtra("symbol")
        if (!sym.isNullOrEmpty()) openChart(sym) else {
            if (scanResults.isNotEmpty()) {
                renderList()
                firstScanDone = true
            }
            startScan()
        }
    }

    private fun logEvent(name: String, params: Bundle? = null) {
        try {
            firebaseAnalytics?.logEvent(name, params)
        } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (isChartMode) {
            showList()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        isDestroyed = true
        super.onDestroy()
        saveStateSync()
        stopSocket()
        stopLiqSocket()
        stopTradeDeltaSocket()
        stopTickerSocket()
        scanner.removeCallbacks(scanRunnable)
        scanner.removeCallbacks(chartRefreshRunnable)
        scanner.removeCallbacks(liveRunnable)
        scanner.removeCallbacks(orderbookRunnable)
        scanner.removeCallbacks(renderRunnable)
        scanner.removeCallbacks(deltaRunnable)
        scanner.removeCallbacks(autoCloseRunnable)
        executor.shutdownNow()
    }

    override fun onResume() {
        super.onResume()
        try { PaperTrading.reload(this) } catch (_: Exception) {}
        applyTheme()
        scanner.postDelayed(scanRunnable, scanInterval)
        scanner.postDelayed(chartRefreshRunnable, 30_000L)
        scanner.postDelayed(liveRunnable, 0)
        scanner.postDelayed(orderbookRunnable, 0)
        scanner.postDelayed(deltaRunnable, 5_000L)
        scanner.postDelayed(autoCloseRunnable, 5_000L)
        if (scanResults.isNotEmpty()) {
            val socketPool = scanResults.take(30).map { it.symbol }
            startLiqSocket(socketPool)
            startTradeDeltaSocket(socketPool)
            startTickerSocket(socketPool)
        }
        updateBalanceLabel()
    }

    override fun onPause() {
        super.onPause()
        scanner.removeCallbacks(chartRefreshRunnable)
        scanner.removeCallbacks(deltaRunnable)
        saveStateAsync()
    }

    private fun captureState(): AppState = AppState(
        savedAt = System.currentTimeMillis(),
        scanResults = scanResults,
        liqBuffer = synchronized(MarketState.liqLock) { HashMap(MarketState.liqBuffer) },
        obInfos = synchronized(MarketState.obLock) { HashMap(MarketState.obInfos) },
        liveSpeeds = synchronized(MarketState.liveLock) { HashMap(MarketState.liveSpeeds) },
        longRatioHistory = synchronized(MarketState.longRatioLock) { HashMap(MarketState.longRatioHistory) },
        oiChangeHistory = synchronized(MarketState.oiChangeLock) { HashMap(MarketState.oiChangeHistory) },
        signalHistory = synchronized(MarketState.signalHistoryLock) { HashMap(MarketState.signalHistory) },
        signalMarkers = synchronized(MarketState.signalMarkersLock) {
            MarketState.signalMarkers.mapValues { ArrayList(it.value) }
        },
        chartEvents = synchronized(MarketState.chartEventsLock) {
            MarketState.chartEvents.mapValues { ArrayList(it.value) }
        },
        cascadePeaks = synchronized(MarketState.cascadePeakLock) {
            MarketState.cascadePeaks.mapValues { ArrayList(it.value) }
        },
        cascadeTrackers = synchronized(MarketState.cascadeTrackerLock) {
            HashMap(MarketState.cascadeTrackers)
        }
    )

    private fun saveStateAsync() {
        try {
            val state = captureState()
            executor.execute { Persistence.save(this, state) }
        } catch (_: Exception) {}
    }

    private fun saveStateSync() {
        try {
            Persistence.save(this, captureState())
        } catch (_: Exception) {}
    }

    private fun loadState() {
        val state = Persistence.load(this) ?: return
        val ageMs = System.currentTimeMillis() - state.savedAt
        val isFresh = ageMs < Persistence.MAX_AGE_MS

        // «Живые» данные — только если state моложе 3 часов
        if (isFresh) {
            scanResults = state.scanResults
            synchronized(MarketState.liqLock) {
                MarketState.liqBuffer.clear()
                state.liqBuffer.forEach { (k, v) ->
                    MarketState.liqBuffer[k] = ArrayList(v)
                }
            }
            synchronized(MarketState.obLock) {
                MarketState.obInfos.clear()
                MarketState.obInfos.putAll(state.obInfos)
            }
            synchronized(MarketState.liveLock) {
                MarketState.liveSpeeds.clear()
                MarketState.liveSpeeds.putAll(state.liveSpeeds)
            }
        } else {
            android.util.Log.d("Persistence", "Live data too old (${ageMs / 3_600_000}ч), skipped")
        }

        // «Исторические» данные — восстанавливаются всегда
        synchronized(MarketState.longRatioLock) {
            MarketState.longRatioHistory.clear()
            state.longRatioHistory.forEach { (k, v) ->
                MarketState.longRatioHistory[k] = ArrayList(v)
            }
        }
        synchronized(MarketState.oiChangeLock) {
            MarketState.oiChangeHistory.clear()
            state.oiChangeHistory.forEach { (k, v) ->
                MarketState.oiChangeHistory[k] = ArrayList(v)
            }
        }
        synchronized(MarketState.signalHistoryLock) {
            MarketState.signalHistory.clear()
            MarketState.signalHistory.putAll(state.signalHistory)
        }
        synchronized(MarketState.signalMarkersLock) {
            MarketState.signalMarkers.clear()
            state.signalMarkers.forEach { (k, v) ->
                MarketState.signalMarkers[k] = ArrayList(v)
            }
        }

        synchronized(MarketState.chartEventsLock) {
            MarketState.chartEvents.clear()
            state.chartEvents.forEach { (k, v) ->
                MarketState.chartEvents[k] = ArrayList(v)
            }
        }

        synchronized(MarketState.cascadePeakLock) {
            MarketState.cascadePeaks.clear()
            state.cascadePeaks.forEach { (k, v) ->
                MarketState.cascadePeaks[k] = ArrayList(v)
            }
        }
        synchronized(MarketState.cascadeTrackerLock) {
            MarketState.cascadeTrackers.clear()
            state.cascadeTrackers.forEach { (k, v) ->
                MarketState.cascadeTrackers[k] = v
            }
        }

        if (scanResults.isNotEmpty()) {
            firstScanDone = true
        }
    }
    private fun buildUi() {
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Prefs.bg(this))

        val scanRow = LinearLayout(this)
        scanRow.orientation = LinearLayout.HORIZONTAL
        scanRow.setPadding(16, 8, 16, 4)
        scanRow.gravity = Gravity.CENTER_VERTICAL

        refreshButton = makeButton("⟳ Сканировать") { startScan() }
        scanRow.addView(refreshButton)

        backButton = makeButton("← К списку") { showList() }
        backButton.visibility = View.GONE
        scanRow.addView(backButton)

        balanceLabel = TextView(this)
        balanceLabel.textSize = 13f
        balanceLabel.setTypeface(null, Typeface.BOLD)
        balanceLabel.setTextColor(Prefs.textDim(this))
        balanceLabel.setPadding(8, 0, 8, 0)
        balanceLabel.gravity = Gravity.CENTER_VERTICAL
        scanRow.addView(balanceLabel)

        portfolioButton = makeButton("💰") { openPortfolio() }
        val pbLp = portfolioButton.layoutParams as LinearLayout.LayoutParams
        pbLp.weight = 0.35f
        portfolioButton.layoutParams = pbLp
        scanRow.addView(portfolioButton)

        settingsButton = makeButton("⚙") { openSettings() }
        val sp = settingsButton.layoutParams as LinearLayout.LayoutParams
        sp.weight = 0.35f
        settingsButton.layoutParams = sp
        scanRow.addView(settingsButton)

        val aboutButton = makeButton("ℹ️") { openAbout() }
        val ap = aboutButton.layoutParams as LinearLayout.LayoutParams
        ap.weight = 0.35f
        aboutButton.layoutParams = ap
        scanRow.addView(aboutButton)

        root.addView(scanRow)

        tfRow = LinearLayout(this)
        tfRow.orientation = LinearLayout.HORIZONTAL
        tfRow.setPadding(16, 0, 16, 4)
        tfRow.visibility = View.GONE
        tfButtons = ArrayList()
        tfButtons.add(makeTfButton("1м", "1", 1))
        tfButtons.add(makeTfButton("5м", "5", 5))
        tfButtons.add(makeTfButton("15м", "15", 15))
        tfButtons.add(makeTfButton("1ч", "60", 60))
        tfButtons.forEach { tfRow.addView(it) }
        root.addView(tfRow)

        chartView = ChartView(this)
        val chartParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0)
        chartParams.weight = 3f
        chartView.layoutParams = chartParams
        chartView.visibility = View.GONE
        chartView.onViewportChanged = { off, vis ->
            offset = off
            visibleCount = vis
        }
        root.addView(chartView)

        chartTitle = TextView(this)
        chartTitle.text = "$currentSymbol · $currentTfLabel"
        chartTitle.setPadding(24, 8, 24, 8)
        chartTitle.textSize = 15f
        chartTitle.setTextColor(Prefs.textDim(this))
        chartTitle.gravity = Gravity.CENTER
        chartTitle.visibility = View.GONE
        root.addView(chartTitle)

        listContainer = LinearLayout(this)
        listContainer.orientation = LinearLayout.VERTICAL
        listScroll = ScrollView(this)
        listScroll.setBackgroundColor(Prefs.bg(this))
        listScroll.addView(listContainer)

        scanOverlay = LinearLayout(this)
        scanOverlay.orientation = LinearLayout.VERTICAL
        scanOverlay.gravity = Gravity.CENTER
        scanOverlay.setBackgroundColor(Color.argb(180, 10, 10, 16))
        scanOverlay.visibility = View.GONE

        scanOverlayText = TextView(this)
        scanOverlayText.text = "Сканирую рынок..."
        scanOverlayText.setTextColor(Color.WHITE)
        scanOverlayText.textSize = 20f
        scanOverlayText.gravity = Gravity.CENTER
        scanOverlay.addView(scanOverlayText)

        val subText = TextView(this)
        subText.text = "несколько секунд"
        subText.setTextColor(Color.LTGRAY)
        subText.textSize = 14f
        subText.gravity = Gravity.CENTER
        subText.setPadding(0, 12, 0, 0)
        scanOverlay.addView(subText)

        listFrame = FrameLayout(this)
        listFrame.addView(listScroll, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        listFrame.addView(scanOverlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val listParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0)
        listParams.weight = 3f
        root.addView(listFrame, listParams)

        setContentView(root)
        updateTfButtons()
        updateBalanceLabel()
    }

    private fun updateBalanceLabel() {
        try {
            val bal = PaperTrading.balance()
            val open = PaperTrading.openTrades()
            val openPnl = open.sumOf { t ->
                val p = scanResults.firstOrNull { it.symbol == t.symbol }?.price ?: 0.0
                t.unrealizedPnl(p)
            }
            val sign = if (openPnl >= 0) "+" else ""
            val txt = if (open.isEmpty()) {
                "💰 ${"%.0f".format(bal)}"
            } else {
                "💰 ${"%.0f".format(bal)}  $sign${"%.0f".format(openPnl)}"
            }
            balanceLabel.text = txt
            balanceLabel.setTextColor(
                if (openPnl > 0.01) Color.rgb(120, 200, 140)
                else if (openPnl < -0.01) Color.rgb(220, 120, 120)
                else Prefs.textDim(this)
            )
        } catch (_: Exception) {}
    }
    private fun openPortfolio() {
        logEvent("portfolio_opened")
        startActivity(Intent(this, PortfolioActivity::class.java))
    }

    private fun openSettings() {
        logEvent("settings_opened")
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun openAbout() {
        logEvent("about_opened")
        startActivity(Intent(this, AboutActivity::class.java))
    }

    private fun applyTheme() {
        root.setBackgroundColor(Prefs.bg(this))
        listScroll.setBackgroundColor(Prefs.bg(this))
        renderList()
        chartView.invalidate()
    }

    private fun roundedDrawable(color: Int, radius: Float = 20f): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(color)
        }

    private fun pressedDrawable(normal: Int, pressed: Int, radius: Float = 20f): StateListDrawable =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), roundedDrawable(pressed, radius))
            addState(intArrayOf(), roundedDrawable(normal, radius))
        }

    private fun applyButtonFeedback(b: Button, normalColor: Int, pressedColor: Int) {
        b.background = pressedDrawable(normalColor, pressedColor)
        b.isAllCaps = false
    }

    private fun makeButton(label: String, action: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.textSize = 13f
        b.isAllCaps = false
        b.setTextColor(Prefs.text(this))
        applyButtonFeedback(b, Prefs.buttonOff(this), Prefs.buttonPressed(this))
        b.setOnClickListener { action() }
        val p = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        p.setMargins(3, 0, 3, 0)
        b.layoutParams = p
        return b
    }

    private fun makeTfButton(label: String, tf: String, minutes: Int): Button {
        val b = Button(this)
        b.text = label
        b.textSize = 14f
        b.isAllCaps = false
        b.setTag(tf)
        b.setTextColor(Prefs.textDim(this))
        applyButtonFeedback(b, Prefs.buttonOff(this), Prefs.buttonPressed(this))
        b.setOnClickListener { switchTimeframe(tf, label, minutes) }
        val p = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        p.setMargins(3, 0, 3, 0)
        b.layoutParams = p
        return b
    }

    private fun switchTimeframe(tf: String, label: String, minutes: Int) {
        if (tf == currentTf) return
        logEvent("tf_changed", Bundle().apply { putString("tf", tf) })
        currentTf = tf
        currentTfLabel = label
        currentTfMinutes = minutes
        updateTfButtons()
        chartTitle.text = "$currentSymbol · $currentTfLabel"
        chartTitle.setTextColor(Prefs.textDim(this))

        val cacheKey = "$currentSymbol|$currentTf"
        val cached = synchronized(chartCacheLock) { chartCache[cacheKey] }
        val now = System.currentTimeMillis()

        if (cached != null && cached.candles.isNotEmpty()) {
            applyChartData(cached.candles, cached.ls)
        }

        if (cached != null && (now - cached.timestamp) < CHART_CACHE_TTL_MS) {
            return
        }

        chartLoading = true
        chartTitle.text = "$currentSymbol · $currentTfLabel (загрузка...)"
        chartTitle.setTextColor(Color.rgb(255, 200, 60))

        val loadStart = System.currentTimeMillis()

        executor.execute {
            try {
                val limit = Prefs.historyForTf(this, currentTf)
                val lsPeriod = lsPeriodForTf(currentTf)
                val sym = currentSymbol
                val tfLocal = currentTf

                val candlesFuture = CompletableFuture.supplyAsync(
                    { BybitApi.fetchKlines(sym, tfLocal, limit).reversed() }, executor
                )
                val lsFuture = CompletableFuture.supplyAsync(
                    { try { BybitApi.fetchLsHistory(sym, lsPeriod, 100) } catch (e: Exception) { emptyList<LsPoint>() } }, executor
                )

                val candles = try { candlesFuture.get(25, TimeUnit.SECONDS) } catch (e: Exception) {
                    emptyList<Candle>()
                }
                val ls = try { lsFuture.get(25, TimeUnit.SECONDS) } catch (e: Exception) { emptyList<LsPoint>() }

                if (candles.isNotEmpty()) {
                    synchronized(chartCacheLock) {
                        chartCache[cacheKey] = ChartCache(candles, ls, System.currentTimeMillis())
                    }
                }

                Handler(Looper.getMainLooper()).post {
                    if (isDestroyed) return@post
                    chartLoading = false
                    if (candles.isEmpty()) {
                        chartTitle.text = "$currentSymbol · $currentTfLabel (ошибка)"
                        chartTitle.setTextColor(Color.rgb(255, 100, 100))
                        Toast.makeText(this@MainActivity, "Не удалось загрузить ${currentTfLabel}", Toast.LENGTH_LONG).show()
                        return@post
                    }
                    chartTitle.text = "$currentSymbol · $currentTfLabel"
                    chartTitle.setTextColor(Prefs.textDim(this))
                    applyChartData(candles, ls)
                }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    if (isDestroyed) return@post
                    chartLoading = false
                    chartTitle.text = "$currentSymbol · $currentTfLabel (ошибка)"
                    chartTitle.setTextColor(Color.rgb(255, 100, 100))
                    Toast.makeText(this@MainActivity, "Ошибка: ${e.message?.take(60)}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun recordChartEvents(results: List<ScanResult>) {
        val now = System.currentTimeMillis()
        val tfMs = currentTfMinutes * 60_000L
        val candleStart = (now / tfMs) * tfMs
        val liqThreshold = Prefs.liqThreshold(this)
        val cutoff = now - 24 * 60 * 60_000L

        for (r in results) {
            val ts = synchronized(MarketState.liveLock) { MarketState.liveTradeSpeeds[r.symbol] }
            if (ts != null) {
                val absSpeed = kotlin.math.abs(ts.pctPerMin)
                if (absSpeed > 0.5) {
                    val level = when {
                        absSpeed > 2.0 -> 3
                        absSpeed > 1.0 -> 2
                        else -> 1
                    }
                    addChartEvent(r.symbol, ChartEvent(candleStart, "accel", level))
                }
            }
            val liq = MarketState.liqStatsCache[r.symbol]
            if (liq != null && liq.total15m >= liqThreshold) {
                val ratio = liq.total15m / liqThreshold
                val level = when {
                    ratio >= 5.0 -> 3
                    ratio >= 3.0 -> 2
                    else -> 1
                }
                addChartEvent(r.symbol, ChartEvent(candleStart, "liq", level))
            }
        }

        synchronized(MarketState.chartEventsLock) {
            for (list in MarketState.chartEvents.values) {
                list.removeAll { it.time < cutoff }
            }
        }
    }

    private fun addChartEvent(symbol: String, event: ChartEvent) {
        synchronized(MarketState.chartEventsLock) {
            val list = MarketState.chartEvents.getOrPut(symbol) { ArrayList() }
            if (list.any { it.time == event.time && it.type == event.type }) return
            list.add(event)
            while (list.size > 200) list.removeAt(0)
        }
    }

    private fun applyChartData(candles: List<Candle>, ls: List<LsPoint>) {
        allCandles = candles
        visibleCount = min(100, candles.size)
        offset = max(0, candles.size - visibleCount)
        chartView.resetZoomY()
        chartView.setData(candles, offset, visibleCount, Prefs.chartType(this), Prefs.theme(this))
        chartView.setLsPoints(ls)
        chartView.setSignalMarkers(getSignalMarkers(currentSymbol))
        val chartEvts = synchronized(MarketState.chartEventsLock) {
            MarketState.chartEvents[currentSymbol]?.toList() ?: emptyList()
        }
        chartView.setChartEvents(chartEvts)
        val obInfo = synchronized(MarketState.obLock) { MarketState.obInfos[currentSymbol] }
        chartView.setWalls(
            obInfo?.bidWallPrice ?: 0.0, obInfo?.bidWallUsd ?: 0.0,
            obInfo?.askWallPrice ?: 0.0, obInfo?.askWallUsd ?: 0.0
        )
    }

    private fun updateTfButtons() {
        tfButtons.forEach { b ->
            val tf = b.getTag() as? String
            if (tf == currentTf) {
                b.background = pressedDrawable(Color.rgb(60, 90, 140), Color.rgb(85, 120, 175))
                b.setTextColor(Color.WHITE)
            } else {
                b.background = pressedDrawable(Prefs.buttonOff(this), Prefs.buttonPressed(this))
                b.setTextColor(Prefs.textDim(this))
            }
        }
    }

    private fun applyOrientation() {
        if (isChartMode) {
            listFrame.visibility = View.GONE
            chartView.visibility = View.VISIBLE
            chartTitle.visibility = View.VISIBLE
            tfRow.visibility = View.VISIBLE
            backButton.visibility = View.VISIBLE
            refreshButton.visibility = View.GONE
            portfolioButton.visibility = View.GONE
            balanceLabel.visibility = View.GONE
        } else {
            chartView.visibility = View.GONE
            chartTitle.visibility = View.GONE
            tfRow.visibility = View.GONE
            backButton.visibility = View.GONE
            refreshButton.visibility = View.VISIBLE
            portfolioButton.visibility = View.VISIBLE
            balanceLabel.visibility = View.VISIBLE
            listFrame.visibility = View.VISIBLE
        }
        root.requestLayout()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientation()
        chartView.invalidate()
    }

    private fun togglePin(symbol: String) {
        var showLimitToast = false
        val wasPinned = synchronized(MarketState.pinnedLock) { symbol in MarketState.pinnedSymbols }
        logEvent("pin_toggled", Bundle().apply {
            putString("symbol", symbol)
            putString("action", if (wasPinned) "unpin" else "pin")
        })
        synchronized(MarketState.pinnedLock) {
            if (symbol in MarketState.pinnedSymbols) {
                MarketState.pinnedSymbols.remove(symbol)
                Prefs.setPinned(this, MarketState.pinnedSymbols.toSet())
            } else {
                val active = scanResults.map { it.symbol }.toSet()
                val activeCount = MarketState.pinnedSymbols.count { it in active }
                if (activeCount >= MAX_PINNED) {
                    showLimitToast = true
                } else {
                    MarketState.pinnedSymbols.add(symbol)
                    Prefs.setPinned(this, MarketState.pinnedSymbols.toSet())
                }
            }
        }
        if (showLimitToast) {
            Toast.makeText(this, "Максимум $MAX_PINNED закреплённых монеты", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, if (wasPinned) "Откреплено: $symbol" else "Закреплено: $symbol", Toast.LENGTH_SHORT).show()
            requestRender(0)
        }
    }

    private fun copyToClipboard(r: ScanResult) {
        logEvent("card_copied", Bundle().apply { putString("symbol", r.symbol) })
        val text = buildCardText(r)
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Crypto Trader", text))
            Toast.makeText(this, "Скопировано: ${r.symbol}", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Не удалось скопировать", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isWeakConflict(r: ScanResult): Boolean {
        if (r.signalStrength != "weak") return false
        val liq = getCachedLiqStats(r.symbol)
        if (r.signalType == "short") {
            if (r.change15m > 5.0) return true
            if (liq != null && liq.shortPct > 70.0) return true
        } else if (r.signalType == "long") {
            if (r.change15m < -5.0) return true
            if (liq != null && liq.longPct > 70.0) return true
        }
        return false
    }

    private fun buildCardText(r: ScanResult): String {
        val sb = StringBuilder()
        val liqThreshold = Prefs.liqThreshold(this)

        r.accumulation?.let { acc ->
            if (acc.level.isNotEmpty()) {
                val accEmoji = when (acc.level) { "strong" -> "🔥"; "medium" -> "🟢"; else -> "🟡" }
                val accDir = if (acc.type == "long") "В ЛОНГ" else "В ШОРТ"
                sb.appendLine("$accEmoji НАКОПЛЕНИЕ $accDir")
            }
        }

        val patternSuffix = if (r.signalPattern.isNotEmpty()) " — ${r.signalPattern}" else ""
        when {
            r.signalType == "short" && r.signalStrength == "strong" ->
                sb.appendLine("🟢 ВХОД В ШОРТ$patternSuffix")
            r.signalType == "long" && r.signalStrength == "strong" ->
                sb.appendLine("🟢 ВХОД В ЛОНГ$patternSuffix")
            r.signalType == "short" && r.signalStrength == "weak" && !isWeakConflict(r) ->
                sb.appendLine("🟡 Возможен вход в шорт (объём/позиция не дотянули)$patternSuffix")
            r.signalType == "long" && r.signalStrength == "weak" && !isWeakConflict(r) ->
                sb.appendLine("🟡 Возможен вход в лонг (объём/позиция не дотянули)$patternSuffix")
        }

        val scoreEmoji = when {
            r.signalStrength == "strong" -> "🟢🚀"
            r.signalStrength == "weak" && !isWeakConflict(r) -> "🟡🚀"
            r.accumulation?.level == "strong" -> "🔥"
            r.accumulation?.level == "medium" -> "🟢"
            r.accumulation?.level == "weak" -> "🟡"
            r.score >= 3 -> "🚀🚀🚀"
            r.score == 2 -> "🚀🚀"
            else -> "🚀"
        }
        sb.appendLine("$scoreEmoji ${r.symbol}")
        sb.appendLine("Цена: ${"%.4f".format(r.price)}  ·  15м: ${"%+.2f".format(r.change15m)}%")

        val hourPct = if (r.turnover24h > 0) r.turnover1h / r.turnover24h * 100 else 0.0
        sb.appendLine("💰 24ч: ${UiUtils.formatMoney(r.turnover24h)} · 1ч: ${UiUtils.formatMoney(r.turnover1h)} (${"%.1f".format(hourPct)}%)")

        if (r.signals.isNotEmpty()) {
            r.signals.forEach { sig -> sb.appendLine("${sig.emoji} ${sig.detail}") }
        }

        if (r.deltaPct1m != 0.0 || r.flowStatus.isNotEmpty()) {
            val deltaEmoji = when {
                r.deltaPct1m > 20 -> "🟢"
                r.deltaPct1m < -20 -> "🔴"
                else -> "⚪"
            }
            val deltaSign = if (r.deltaPct1m >= 0) "+" else ""
            val realDeltaStr = when {
                r.realDeltaUsd == 0.0 -> "—"
                r.realDeltaUsd > 0 -> "+${UiUtils.formatMoney(r.realDeltaUsd)}"
                else -> "-${UiUtils.formatMoney(-r.realDeltaUsd)}"
            }
            val flowLabel = when (r.flowStatus) {
                "new_long" -> "New Money ↑"
                "short_squeeze" -> "Short Squeeze"
                "new_short" -> "New Money ↓"
                "long_squeeze" -> "Long Squeeze"
                else -> "Churn"
            }
            sb.appendLine("Δ 1м: $deltaEmoji $deltaSign${"%.0f".format(r.deltaPct1m)}% ($realDeltaStr buy/sell) · $flowLabel")
        }

        val liq = getCachedLiqStats(r.symbol)
        if (liq != null && liq.total15m >= liqThreshold) {
            val who = if (liq.dominantIsLong) "🐂 лонги" else "🐻 шорты"
            val domPct = if (liq.dominantIsLong) liq.longPct else liq.shortPct
            val trendIcon = when (liq.trend) {
                "rising" -> "🔥 растёт"
                "falling" -> "🪫 затухает"
                else -> "➡️ стабильно"
            }
            sb.appendLine("💥 Ликвидации 15м: ${UiUtils.formatMoney(liq.total15m)} · $who ${"%.0f".format(domPct)}% · $trendIcon")
            val peakList = synchronized(MarketState.cascadePeakLock) {
                MarketState.cascadePeaks[r.symbol]?.toList() ?: emptyList()
            }
            if (peakList.isNotEmpty()) {
                val avgPeak = peakList.map { it.sum }.average()
                if (avgPeak > 0) {
                    val percent = (liq.total15m / avgPeak * 100).toInt()
                    val maxPeak = peakList.maxOf { it.sum }
                    val exceeded = liq.total15m > maxPeak
                    val peaksStr = peakList.joinToString(", ") { UiUtils.formatMoney(it.sum) }
                    val suffix = if (exceeded) " (перебит максимум!)" else ""
                    sb.appendLine("   Прошлые пики: $peaksStr · сейчас $percent% от среднего$suffix")
                }
            }
            val zoneAhead = if (!liq.dominantIsLong) r.peakForecast > r.price else r.peakForecast < r.price
            if (r.peakForecast > 0 && zoneAhead) {
                val dirWord = if (!liq.dominantIsLong) "вверх" else "вниз"
                val zone = if (r.peakLow > 0 && r.peakHigh > r.peakLow) {
                    "зона ${"%.4f".format(r.peakLow)} – ${"%.4f".format(r.peakHigh)}"
                } else {
                    "%.4f".format(r.peakForecast)
                }
                val conf = if (r.peakConfident) "" else " ⚠️"
                sb.appendLine("🎯 Зона выноса: $zone (ещё ${"%.1f".format(r.peakRemainPct)}% $dirWord)$conf")
            }
        }

        if (r.openInterest > 0) {
            val oiSign = if (r.openInterestChange >= 0) "+" else ""
            val oiUsd = r.openInterest * r.price
            val trendIcon = when (r.oiTrend) {
                "accelerating" -> " ↗️"
                "slowing" -> " ↘️"
                else -> ""
            }
            sb.appendLine("📊 OI: ${UiUtils.formatMoney(r.openInterest)} (~${UiUtils.formatMoney(oiUsd)}) · $oiSign${"%.2f".format(r.openInterestChange)}%$trendIcon")
        }

        if (r.longRatio > 0 || r.shortRatio > 0) {
            val longPct = r.longRatio * 100
            val shortPct = r.shortRatio * 100
            val who = if (r.longRatio > r.shortRatio) "лонги" else "шорты"
            sb.appendLine("📊 Лонги: ${"%.0f".format(longPct)}%  ·  Шорты: ${"%.0f".format(shortPct)}%  ·  $who")
        } else {
            sb.appendLine("📊 Лонги/Шорты: нет данных")
        }

        val obInfo = synchronized(MarketState.obLock) { MarketState.obInfos[r.symbol] }
        if (obInfo != null) {
            val imb = obInfo.imbalance
            val sign = if (imb >= 0) "+" else ""
            val label = when {
                imb >= 0.30 -> "покупатели"
                imb <= -0.30 -> "продавцы"
                else -> "баланс"
            }
            sb.appendLine("⚖️ Стакан: $sign${"%.2f".format(imb)} ($label)")
            if ((obInfo.bidWallPrice > 0 || obInfo.askWallPrice > 0) && (obInfo.bidWallUsd >= 1000.0 || obInfo.askWallUsd >= 1000.0)) {
                val bidStar = if (obInfo.bidWallUsd > obInfo.askWallUsd) " ★" else ""
                val askStar = if (obInfo.askWallUsd > obInfo.bidWallUsd) " ★" else ""
                sb.appendLine("   Bid wall: ${"%.4f".format(obInfo.bidWallPrice)} (${UiUtils.formatMoney(obInfo.bidWallUsd)})$bidStar")
                sb.appendLine("   Ask wall: ${"%.4f".format(obInfo.askWallPrice)} (${UiUtils.formatMoney(obInfo.askWallUsd)})$askStar")
            }
        } else {
            sb.appendLine("⚖️ Стакан: нет данных")
        }

        val sp = synchronized(MarketState.liveLock) { MarketState.liveSpeeds[r.symbol] }
        if (sp != null) {
            val icon = when {
                sp.accelerating -> "⚡⚡⚡ ускорение"
                sp.dying -> "🪫 затухание"
                else -> "⚡ стабильно"
            }
            sb.appendLine("$icon · ${"%.2f".format(sp.speedNow)}%/мин")
        }
        val ts = synchronized(MarketState.liveLock) { MarketState.liveTradeSpeeds[r.symbol] }
        if (ts != null) {
            val tsEmoji = when {
                ts.pctPerMin > 0.3 -> "🔥↑"
                ts.pctPerMin < -0.3 -> "🔥↓"
                ts.pctPerMin > 0.05 -> "·↑"
                ts.pctPerMin < -0.05 -> "·↓"
                else -> "·"
            }
            val tsSign = if (ts.pctPerMin >= 0) "+" else ""
            sb.appendLine("$tsEmoji $tsSign${"%.2f".format(ts.pctPerMin)}%/мин (${ts.tradeCount} сделок)")
        }

        if (r.signalType.isEmpty()) {
            val hist = synchronized(MarketState.signalHistoryLock) { MarketState.signalHistory[r.symbol] }
            if (hist != null && hist.candleTime > 0L) {
                val ageMs = System.currentTimeMillis() - hist.detectedAt
                val ageMin = ageMs / 60_000L
                val ageLabel = when {
                    ageMin < 1 -> "только что"
                    ageMin < 60 -> "$ageMin мин назад"
                    ageMin < 1440 -> "${ageMin / 60} ч назад"
                    else -> "${ageMin / 1440} дн назад"
                }
                val sigLabel = if (hist.type == "long") "лонг" else "шорт"
                val strLabel = if (hist.strength == "strong") "STRONG" else "WEAK"
                sb.appendLine("📜 Был сигнал $ageLabel ($strLabel $sigLabel)")
            }
        }

        r.accumulation?.let { acc ->
            if (acc.level.isNotEmpty()) {
                val accEmoji = when (acc.level) { "strong" -> "🔥"; "medium" -> "🟢"; else -> "🟡" }
                val accDir = if (acc.type == "long") "лонг" else "шорт"
                sb.appendLine("$accEmoji Накопление $accDir · OI +${"%.2f".format(acc.oiChangePct)}% (${acc.oiBarsPositive}/9) · Диапазон ${"%.2f".format(acc.rangePct)}% · Delta ${UiUtils.formatMoney(acc.deltaUsd)}")
            }
        }

        val openTrade = PaperTrading.openTrades().firstOrNull { it.symbol == r.symbol }
        if (openTrade != null) {
            val pnl = openTrade.unrealizedPnl(r.price)
            val pnlPct = openTrade.unrealizedPnlPct(r.price)
            val dir = if (openTrade.isLong) "🟢 LONG" else "🔴 SHORT"
            val pnlSign = if (pnl >= 0) "+" else ""
            sb.appendLine("📈 Открыта $dir · $${"%.2f".format(openTrade.sizeUsd)} · $pnlSign$${"%.2f".format(pnl)} ($pnlSign${"%.1f".format(pnlPct)}%)")
        } else {
            val sizeUsd = Prefs.paperTradeSize(this)
            sb.appendLine("💡 BUY $${"%.0f".format(sizeUsd)} / SELL $${"%.0f".format(sizeUsd)}")
        }

        return sb.toString().trim()
    }
    private fun sortResults(list: List<ScanResult>): List<ScanResult> {
        val base = list.sortedWith(
            compareByDescending<ScanResult> { it.score }.thenByDescending { abs(it.change15m) }
        )

        val liqThreshold = Prefs.liqThreshold(this)

        val (priority, others) = base.partition {
            it.signalType.isNotEmpty() ||
            NotificationHelper.notificationPriority(it.symbol) > 0 ||
            (getCachedLiqStats(it.symbol)?.total15m ?: 0.0) >= liqThreshold
        }

        val sortedPriority = priority.sortedWith(
            compareByDescending<ScanResult> {
                when (it.signalStrength) {
                    "strong" -> 4
                    "weak" -> 3
                    else -> if (it.signalType.isNotEmpty()) 2
                            else if (NotificationHelper.notificationPriority(it.symbol) > 0) 1
                            else 0
                }
            }
                .thenByDescending { NotificationHelper.notificationPriority(it.symbol) }
                .thenByDescending { getCachedLiqStats(it.symbol)?.total15m ?: 0.0 }
        )
        return sortedPriority + others
    }

    private fun showScanOverlay(on: Boolean, text: String = "Сканирую рынок...") {
        isScanning = on
        scanOverlayText.text = text
        scanOverlay.visibility = if (on) View.VISIBLE else View.GONE
    }

    // ================== Signal markers ==================

        private fun addSignalMarker(symbol: String, time: Long, type: String, pattern: String, sourceTf: String) {
        if (time <= 0L || type.isEmpty()) return
        synchronized(MarketState.signalMarkersLock) {
            val list = MarketState.signalMarkers.getOrPut(symbol) { ArrayList() }
            if (list.any { it.time == time }) return
            list.add(SignalMarker(time, type, pattern, sourceTf))
            if (list.size > 200) {
                while (list.size > 200) list.removeAt(0)
            }
        }
    }
    private fun getSignalMarkers(symbol: String): List<SignalMarker> {
        synchronized(MarketState.signalMarkersLock) {
            return MarketState.signalMarkers[symbol]?.toList() ?: emptyList()
        }
    }

    // ================== HTTP ==================


    private fun lsPeriodForTf(tf: String): String = when (tf) {
        "1" -> "5min"
        "5" -> "5min"
        "15" -> "15min"
        "60" -> "1h"
        else -> "5min"
    }

    // ================== OI / L-S / Trend ==================


    private fun updateLongRatioHistory(symbol: String, longRatio: Double) {
        synchronized(MarketState.longRatioLock) {
            val buf = MarketState.longRatioHistory.getOrPut(symbol) { ArrayList() }
            buf.add(System.currentTimeMillis() to longRatio)
            val cutoff = System.currentTimeMillis() - 20 * 60 * 1000L
            buf.removeAll { it.first < cutoff }
        }
    }

    private fun computeLongRatioChange(symbol: String, current: Double): Double {
        synchronized(MarketState.longRatioLock) {
            val buf = MarketState.longRatioHistory[symbol] ?: return 0.0
            val targetTime = System.currentTimeMillis() - 15 * 60 * 1000L
            val old = buf.minByOrNull { abs(it.first - targetTime) } ?: return 0.0
            if (old.first < System.currentTimeMillis() - 20 * 60 * 1000L) return 0.0
            return (current - old.second) * 100
        }
    }

    private fun updateOiTrend(symbol: String, change: Double) {
        synchronized(MarketState.oiChangeLock) {
            val buf = MarketState.oiChangeHistory.getOrPut(symbol) { ArrayList() }
            buf.add(change)
            while (buf.size > 4) buf.removeAt(0)
        }
    }

    private fun computeOiTrend(symbol: String): String {
        synchronized(MarketState.oiChangeLock) {
            val buf = MarketState.oiChangeHistory[symbol] ?: return "stable"
            if (buf.size < 3) return "stable"
            val last = buf.last()
            val prev = buf.subList(0, buf.size - 1).average()
            if (last > prev + 0.05 && last > 0) return "accelerating"
            if (last < prev - 0.05 && last < 0) return "accelerating"
            if (abs(last) < abs(prev) * 0.5) return "slowing"
            return "stable"
        }
    }

    // ================== Orderbook ==================


    private fun refreshOrderbook() {
        val symbols = scanResults.map { it.symbol }
        if (symbols.isEmpty()) return

        executor.execute {
            for (sym in symbols) {
                val info = BybitApi.fetchOrderbookInfo(sym) ?: continue
                synchronized(MarketState.obLock) {
                    val hist = MarketState.obHistory.getOrPut(sym) { ArrayList() }
                    hist.add(info.imbalance)
                    while (hist.size > obHistorySize) hist.removeAt(0)
                    val avg = hist.average()
                    MarketState.obInfos[sym] = info.copy(imbalance = avg)
                }
            }

            Handler(Looper.getMainLooper()).post {
                    if (isDestroyed) return@post
                NotificationHelper.checkNotifications(this, scanResults)
                requestRender()
                updateBalanceLabel()
                if (isChartMode) {
                    val obInfo = synchronized(MarketState.obLock) { MarketState.obInfos[currentSymbol] }
                    if (obInfo != null) {
                        chartView.setWalls(
                            obInfo.bidWallPrice, obInfo.bidWallUsd,
                            obInfo.askWallPrice, obInfo.askWallUsd
                        )
                    }
                }
            }
        }
    }
        // ================== Notifications ==================






    // ================== Liquidation socket ==================

    private fun startLiqSocket(symbols: List<String>) {
        if (symbols.isEmpty()) return
        if (symbols == currentLiqSymbols && liqSocket != null) return
        stopLiqSocket()
        currentLiqSymbols = symbols
        liqSocket = LiquidationSocket(symbols) { symbol, time, usd, isLong, price ->
            synchronized(MarketState.liqLock) {
                val buf = MarketState.liqBuffer.getOrPut(symbol) { ArrayList() }
                buf.add(LiqEvent(time, usd, isLong, price))
                val cutoff = System.currentTimeMillis() - 15 * 60 * 1000L
                buf.removeAll { it.time < cutoff }
            }
        }.also { it.connect() }
    }

    private fun stopLiqSocket() {
        try { liqSocket?.close() } catch (_: Exception) {}
        liqSocket = null
        currentLiqSymbols = emptyList()
    }

    private fun startTradeDeltaSocket(symbols: List<String>) {
        if (symbols.isEmpty()) return
        if (symbols == currentDeltaSymbols && tradeDeltaSocket != null) return
        stopTradeDeltaSocket()
        currentDeltaSymbols = symbols
        tradeDeltaSocket = TradeDeltaSocket(symbols) { symbol, time, price, size, isBuy ->
            tradeDeltaAnalyzer.addTrade(symbol, time, price * size, isBuy)
            synchronized(MarketState.liveLock) {
                val buf = MarketState.liveBuffer.getOrPut(symbol) { ArrayList() }
                val lastTime = buf.lastOrNull()?.time ?: 0L
                if (time > lastTime) {
                    buf.add(Trade(time, price, size))
                }
                while (buf.size > 2000) buf.removeAt(0)
            }
        }.also { it.connect() }
    }
    private fun stopTradeDeltaSocket() {
        try { tradeDeltaSocket?.close() } catch (_: Exception) {}
        tradeDeltaSocket = null
        currentDeltaSymbols = emptyList()
    }

    private fun updateRealDelta() {
        if (scanResults.isEmpty()) return
        var changed = false
        val newList = ArrayList<ScanResult>(scanResults.size)
        for (r in scanResults) {
            val d = tradeDeltaAnalyzer.computeDelta(r.symbol, 60_000L)
            if (d != r.realDeltaUsd) {
                newList.add(r.copy(realDeltaUsd = d))
                changed = true
            } else {
                newList.add(r)
            }
        }
        if (changed) {
            scanResults = newList
            requestRender(0)
            updateBalanceLabel()
        }
    }

    // ================== TickerSocket ==================

    private fun startTickerSocket(symbols: List<String>) {
        if (symbols.isEmpty()) return
        if (symbols == currentTickerSymbols && tickerSocket != null) return
        stopTickerSocket()
        currentTickerSymbols = symbols
        tickerSocket = TickerSocket(symbols) { symbol, price ->
            onTickerPrice(symbol, price)
        }.also { it.connect() }
    }
    private fun stopTickerSocket() {
        try { tickerSocket?.close() } catch (_: Exception) {}
        tickerSocket = null
        currentTickerSymbols = emptyList()
    }

    private fun onTickerPrice(symbol: String, price: Double) {
        Handler(Looper.getMainLooper()).post {
                    if (isDestroyed) return@post
            val idx = scanResults.indexOfFirst { it.symbol == symbol }
            if (idx < 0) return@post
            val old = scanResults[idx]
            if (abs(old.price - price) < 0.0000001) return@post

            val newChange = if (old.priceAt15mAgo > 0)
                (price - old.priceAt15mAgo) / old.priceAt15mAgo * 100
            else old.change15m

            val liq = getCachedLiqStats(symbol)
            val shortsBurning = liq != null && !liq.dominantIsLong && liq.shortPct >= 60 && liq.total15m >= 10_000
            val longsBurning = liq != null && liq.dominantIsLong && liq.longPct >= 60 && liq.total15m >= 10_000
            val newFlow = when {
                newChange > 0.3 -> when {
                    old.openInterestChange < -0.3 -> "short_squeeze"
                    old.openInterestChange > 0.3 -> "new_long"
                    shortsBurning -> "short_squeeze"
                    else -> "churn"
                }
                newChange < -0.3 -> when {
                    old.openInterestChange < -0.3 -> "long_squeeze"
                    old.openInterestChange > 0.3 -> "new_short"
                    longsBurning -> "long_squeeze"
                    else -> "churn"
                }
                else -> "churn"
            }

            val updated = old.copy(price = price, change15m = newChange, flowStatus = newFlow)
            val newList = scanResults.toMutableList()
            newList[idx] = updated
            scanResults = newList

            if (!isChartMode) requestRender()
            updateBalanceLabel()
        }
    }

    // ================== Liq stats cache ==================

    private fun refreshLiqStatsCache() {
        try {
            val keys = synchronized(MarketState.liqLock) { MarketState.liqBuffer.keys.toList() }
            val newMap = HashMap<String, LiqStats>(keys.size)
            for (sym in keys) {
                val s = computeLiqStats(sym) ?: continue
                newMap[sym] = s
                updateCascadePeak(sym, s.total15m)
            }
            MarketState.liqStatsCache.clear()
            MarketState.liqStatsCache.putAll(newMap)
        } catch (_: Exception) {}
    }

    private fun getCachedLiqStats(symbol: String): LiqStats? = MarketState.liqStatsCache[symbol]
    private fun updateCascadePeak(symbol: String, currentSum: Double) {
        val now = System.currentTimeMillis()
        val liqThreshold = Prefs.liqThreshold(this)
        synchronized(MarketState.cascadeTrackerLock) {
            val tracker = MarketState.cascadeTrackers.getOrPut(symbol) { CascadeTracker() }
            if (currentSum > tracker.maxSum) {
                MarketState.cascadeTrackers[symbol] = tracker.copy(
                    maxSum = currentSum,
                    lastGrowthTime = now
                )
            } else if (tracker.maxSum > 0 && now - tracker.lastGrowthTime > 10 * 60_000L) {
                if (tracker.maxSum >= liqThreshold) {
                    synchronized(MarketState.cascadePeakLock) {
                        val peaks = MarketState.cascadePeaks.getOrPut(symbol) { ArrayList() }
                        peaks.add(CascadePeak(tracker.maxSum, tracker.lastGrowthTime))
                        while (peaks.size > 3) peaks.removeAt(0)
                        val cutoff = now - 24 * 60 * 60_000L
                        peaks.removeAll { it.time < cutoff }
                    }
                }
                MarketState.cascadeTrackers[symbol] = CascadeTracker()
            }
        }
    }


    private fun computeLiqStats(symbol: String): LiqStats? {
        val events: List<LiqEvent>
        synchronized(MarketState.liqLock) { events = MarketState.liqBuffer[symbol]?.toList() ?: return null }
        if (events.isEmpty()) return null

        val now = System.currentTimeMillis()
        val cut15 = now - 15 * 60 * 1000L
        val cut5 = now - 5 * 60 * 1000L
        var total15 = 0.0; var long15 = 0.0; var short15 = 0.0; var sum5 = 0.0
        for (e in events) {
            if (e.time < cut15) continue
            total15 += e.usd
            if (e.isLong) long15 += e.usd else short15 += e.usd
            if (e.time >= cut5) sum5 += e.usd
        }
        if (total15 <= 0.0) return null
        val extrapolated5 = sum5 * 3.0
        val rawTrend = when {
            extrapolated5 > total15 * 1.5 -> "rising"
            extrapolated5 < total15 * 0.5 -> "falling"
            else -> "stable"
        }

        val smoothedTrend = synchronized(MarketState.liqTrendLock) {
            val hist = MarketState.liqTrendHistory.getOrPut(symbol) { ArrayList() }
            hist.add(rawTrend)
            while (hist.size > 3) hist.removeAt(0)
            when {
                hist.count { it == "falling" } >= 2 -> "falling"
                hist.count { it == "rising" } >= 2 -> "rising"
                else -> "stable"
            }
        }
        return LiqStats(total15, long15, short15, smoothedTrend)
    }
        // ================== Peak forecast ==================


    // ================== Reversal detection ==================


    // ================== Scan ==================

    private fun startScan() {
        if (isScanning) return
        logEvent("scan_started")
        if (!firstScanDone) showScanOverlay(true, "Сканирую рынок...")

        executor.execute {
            try {
                val tickers = BybitApi.getTickers()
                val turnoverMap = HashMap<String, Double>()
                tickers.forEach { turnoverMap[it.first] = it.second }

                val pinnedSet = synchronized(MarketState.pinnedLock) { MarketState.pinnedSymbols.toSet() }
                val scanLimit = Prefs.scanLimit(this)

                val topN = tickers
                    .filter { it.first.endsWith("USDT") && it.second > 500_000.0 }
                    .sortedByDescending { it.second }
                    .take(scanLimit)
                    .map { it.first }

                val nowMs = System.currentTimeMillis()
                val cut15 = nowMs - 15 * 60 * 1000L
                val liqThreshold = Prefs.liqThreshold(this)
                val hotSymbols = synchronized(MarketState.liqLock) {
                    MarketState.liqBuffer.keys.filter { sym ->
                        if (sym in topN || sym in pinnedSet) return@filter false
                        val events = MarketState.liqBuffer[sym] ?: return@filter false
                        var total = 0.0
                        for (e in events) if (e.time >= cut15) total += e.usd
                        total >= liqThreshold
                    }
                }
                val hotSet = hotSymbols.toHashSet()

                val symbolsToScan = (topN + pinnedSet + hotSymbols).distinct()

                val total = symbolsToScan.size
                val results = Collections.synchronizedList(ArrayList<ScanResult>())
                val doneCount = AtomicInteger(0)
                val threads = ArrayList<Thread>()
                val queue = ConcurrentLinkedQueue<String>()
                queue.addAll(symbolsToScan)

                val workers = 4
                for (w in 0 until workers) {
                    val t = Thread {
                        while (true) {
                            val sym = queue.poll() ?: break
                            try {
                                val candles = BybitApi.fetchKlines(sym, Prefs.signalTf(this), 90).reversed()
                                val realTurn24 = turnoverMap[sym] ?: 0.0
                                val ratio = BybitApi.fetchLongShortRatio(sym)
                                val oiHist = BybitApi.fetchOiHistory(sym, 9)
                                val oiPair = if (oiHist != null && oiHist.size >= 2) {
                                    val oiLatest = oiHist.last()
                                    val oiPrev = oiHist[oiHist.size - 2]
                                    val oiChg = if (oiPrev > 0) (oiLatest - oiPrev) / oiPrev * 100 else 0.0
                                    synchronized(MarketState.oiLock) {
                                        MarketState.openInterestHistory[sym] = oiLatest
                                        MarketState.openInterestTimestamp[sym] = System.currentTimeMillis()
                                    }
                                    oiLatest to oiChg
                                } else null

                                val longRatio = ratio?.first ?: 0.0
                                val longRatioChange = computeLongRatioChange(sym, longRatio)
                                updateLongRatioHistory(sym, longRatio)

                                val oiChange = oiPair?.second ?: 0.0
                                if (oiPair != null) updateOiTrend(sym, oiChange)
                                val oiTrend = computeOiTrend(sym)

                                val res = scanSymbol(
                                    sym, candles, realTurn24,
                                    longRatio, ratio?.second ?: 0.0,
                                    forceInclude = (sym in pinnedSet) || (sym in hotSet),
                                    openInterest = oiPair?.first ?: 0.0,
                                    openInterestChange = oiChange,
                                    longRatioChange = longRatioChange,
                                    oiTrend = oiTrend,
                                    oiHistory = oiHist,
                                    deltaBuckets = tradeDeltaAnalyzer.getDeltaBuckets(sym, 9)
                                )
                                if (res != null) results.add(res)
                            } catch (_: Exception) {}
                            val done = doneCount.incrementAndGet()
                            if (!firstScanDone) {
                                Handler(Looper.getMainLooper()).post {
                    if (isDestroyed) return@post
                                    scanOverlayText.text = "Сканирую $done/$total..."
                                }
                            }
                        }
                    }
                    threads.add(t)
                    t.start()
                }
                for (t in threads) t.join()

                var sorted = results.sortedWith(
                    compareByDescending<ScanResult> { it.score }.thenByDescending { abs(it.change15m) }
                ).take(scanLimit)

                if (sorted.size < scanLimit && results.size > sorted.size) {
                    val existing = sorted.map { it.symbol }.toSet()
                    val extra = results
                        .filter { it.symbol !in existing }
                        .sortedByDescending { it.turnover1m }
                        .take(scanLimit - sorted.size)
                    sorted = (sorted + extra).take(scanLimit)
                }

                Handler(Looper.getMainLooper()).post {
                    if (isDestroyed) return@post
                    val now = System.currentTimeMillis()
                    val limit = Prefs.scanLimit(this)
                    val newSymbols = sorted.map { it.symbol }.toSet()
                    val combined = ArrayList<ScanResult>(sorted)
                    for (old in scanResults) {
                        if (combined.size >= limit) break
                        if (old.symbol !in newSymbols) {
                            val isPinned = synchronized(MarketState.pinnedLock) { old.symbol in MarketState.pinnedSymbols }
                            if (isPinned) {
                                combined.add(old)
                            } else {
                                val age = now - old.lastSignalTime
                                if (age < KEEP_OLD_MS) {
                                    combined.add(old)
                                }
                            }
                        }
                    }
                    scanResults = combined.take(limit)

                    recordChartEvents(scanResults)

                    val socketPool = topN.take(30)
                    startLiqSocket(socketPool)
                    startTradeDeltaSocket(socketPool)
                    startTickerSocket(socketPool)

                    refreshLiqStatsCache()
                    NotificationHelper.checkNotifications(this, scanResults)
                    renderList()
                    saveStateAsync()
                    updateBalanceLabel()
                    if (!firstScanDone) {
                        showScanOverlay(false)
                        firstScanDone = true
                    }
                }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    if (isDestroyed) return@post
                    if (!firstScanDone) {
                        showScanOverlay(false)
                        firstScanDone = true
                    }
                }
            }
        }
    }

    // ================== Fetch API ==================






    // ================== Speed ==================

    private fun appendToBuffer(symbol: String, trades: List<Trade>) {
        if (trades.isEmpty()) return
        synchronized(MarketState.liveLock) {
            val buf = MarketState.liveBuffer.getOrPut(symbol) { ArrayList() }
            val cutoff = System.currentTimeMillis() - 60 * 60 * 1000L
            buf.removeAll { it.time < cutoff }
            val lastTime = buf.lastOrNull()?.time ?: 0L
            val fresh = trades.filter { it.time > lastTime }
            buf.addAll(fresh)
            while (buf.size > 2000) buf.removeAt(0)
        }
    }

    private fun computeSpeedByTrades(symbol: String): TradeSpeedReading? {
        val trades: List<Trade> = synchronized(MarketState.liveLock) {
            MarketState.liveBuffer[symbol]?.toList() ?: emptyList()
        }
        if (trades.size < 10) return null
        val now = System.currentTimeMillis()
        val cutoff = now - 60_000L
        val recent = ArrayList<Trade>()
        for (t in trades) if (t.time >= cutoff) recent.add(t)
        if (recent.size < 10) return null

        var totalVol = 0.0
        for (t in recent) totalVol += t.price * t.size
        if (totalVol < 500.0) return null

        // Разбиваем 60 сек на 6 окон по 10 сек, считаем VWAP в каждом
        val windowMs = 10_000L
        val bucketCount = 6
        val bucketStart = now - 60_000L
        val prices = DoubleArray(bucketCount) { 0.0 }
        val sizes = DoubleArray(bucketCount) { 0.0 }

        for (t in recent) {
            val idx = ((t.time - bucketStart) / windowMs).toInt().coerceIn(0, bucketCount - 1)
            val vol = t.price * t.size
            prices[idx] += vol
            sizes[idx] += t.size
        }

        // VWAP каждого непустого окна
        val vwaps = ArrayList<Double>(bucketCount)
        for (i in 0 until bucketCount) {
            if (sizes[i] > 0) vwaps.add(prices[i] / sizes[i])
        }
        if (vwaps.size < 2) return null

        // Скорости между соседними окнами (% за окно)
        val speeds = ArrayList<Double>(vwaps.size - 1)
        for (i in 1 until vwaps.size) {
            val prev = vwaps[i - 1]
            if (prev <= 0) continue
            speeds.add((vwaps[i] - prev) / prev * 100.0)
        }
        if (speeds.isEmpty()) return null

        speeds.sort()
        val median = speeds[speeds.size / 2]
        // median — % за окно (10 сек), переводим в %/мин
        val pctPerMin = median * (60.0 / 10.0)

        return TradeSpeedReading(
            pctPerMin = pctPerMin,
            tradeCount = recent.size,
            volumeUsd = totalVol,
            seconds = 60
        )
    }
    private fun computeIntrabarSpeed(symbol: String, tfMinutes: Int): SpeedReading? {
        val trades: List<Trade>
        synchronized(MarketState.liveLock) { trades = MarketState.liveBuffer[symbol]?.toList() ?: return null }
        if (trades.size < 5) return null

        val nowMs = System.currentTimeMillis()
        val periodMs = tfMinutes * 60_000L
        val periodStart = nowMs - (nowMs % periodMs)
        val elapsedSec = ((nowMs - periodStart) / 1000.0).coerceAtLeast(1.0)
        val thisPeriod = trades.filter { it.time >= periodStart }
        if (thisPeriod.size < 3) return null
        val openPrice = thisPeriod.first().price
        if (openPrice <= 0) return null

        fun speedAt(secFromStart: Double): Double {
            val clamped = secFromStart.coerceAtMost(elapsedSec).coerceAtLeast(1.0)
            val targetMs = periodStart + (clamped * 1000).toLong()
            val closest = thisPeriod.minByOrNull { abs(it.time - targetMs) } ?: return 0.0
            val distPct = abs(closest.price - openPrice) / openPrice * 100
            return distPct / (clamped / 60.0)
        }

        val s15 = speedAt(15.0); val s30 = speedAt(30.0); val s45 = speedAt(45.0)
        val lastPrice = thisPeriod.last().price
        val distNow = abs(lastPrice - openPrice) / openPrice * 100
        val sNow = distNow / (elapsedSec / 60.0)
        val accelerating = sNow > s15 * 1.5 && sNow > 0.2
        val maxPrev = maxOf(s15, s30, s45)
        val dying = maxPrev > 0.5 && sNow < maxPrev * 0.3
        val wasUp = lastPrice > openPrice
        val dyingUp = dying && wasUp
        val dyingDown = dying && !wasUp
        val points = thisPeriod.map { it.time to it.price }
        return SpeedReading(s15, s30, s45, sNow, accelerating, dying, dyingUp, dyingDown, points)
    }

    private fun refreshLiveSpeeds() {
        val top = scanResults.take(15).map { it.symbol }.filter { it != currentSymbol }
        if (top.isEmpty()) return

        executor.execute {
            for (sym in top) {
                val trades = BybitApi.fetchRecentTrades(sym)
                if (trades.isNotEmpty()) {
                    appendToBuffer(sym, trades)
                    val sp = computeIntrabarSpeed(sym, currentTfMinutes)
                    if (sp != null) {
                        synchronized(MarketState.liveLock) { MarketState.liveSpeeds[sym] = sp }
                    }
                    val ts = computeSpeedByTrades(sym)
                    if (ts != null) {
                        synchronized(MarketState.liveLock) { MarketState.liveTradeSpeeds[sym] = ts }
                    }
                }
            }
            Handler(Looper.getMainLooper()).post { if (isDestroyed) return@post; requestRender() }
        }
    }

    private fun startSocket(symbol: String) {
        stopSocket()
        tradeSocket = TradeSocket(symbol) { time, price, size, _ ->
            synchronized(MarketState.liveLock) {
                val buf = MarketState.liveBuffer.getOrPut(symbol) { ArrayList() }
                val lastTime = buf.lastOrNull()?.time ?: 0L
                if (time > lastTime) {
                    buf.add(Trade(time, price, size))
                }
                while (buf.size > 2000) buf.removeAt(0)
            }
            val now = System.currentTimeMillis()
            if (now - lastChartUpdate < chartUpdateInterval) return@TradeSocket
            lastChartUpdate = now
            val sp = computeIntrabarSpeed(symbol, currentTfMinutes)
            if (sp != null) {
                synchronized(MarketState.liveLock) { MarketState.liveSpeeds[symbol] = sp }
            }
        }.also { it.connect() }
    }

    private fun stopSocket() {
        try { tradeSocket?.close() } catch (_: Exception) {}
        tradeSocket = null
    }

    // ================== Per-symbol scan ==================

    private fun scanSymbol(
        symbol: String,
        candles: List<Candle>,
        turnover24hFromTicker: Double,
        longRatio: Double,
        shortRatio: Double,
        forceInclude: Boolean = false,
        openInterest: Double = 0.0,
        openInterestChange: Double = 0.0,
        longRatioChange: Double = 0.0,
        oiTrend: String = "stable",
        oiHistory: List<Double>? = null,
        deltaBuckets: List<Double> = emptyList()
    ): ScanResult? {
        if (candles.size < 60) return null
        val signals = ArrayList<Signal>()
        val lastIdx = candles.size - 1
        val curPrice = candles[lastIdx].close
        val tfMin = when (Prefs.signalTf(this)) {
            "1" -> 1
            "5" -> 5
            "15" -> 15
            "60" -> 60
            else -> 1
        }
        val bars15 = max(1, 15 / tfMin)
        val idx15 = max(0, lastIdx - bars15)
        val price15 = candles[idx15].close
        val change15m = if (price15 > 0) (curPrice - price15) / price15 * 100 else 0.0

        val curVol = candles[lastIdx].volume
        val volFrom = max(0, lastIdx - 60)
        var sumVol = 0.0; var cntVol = 0
        for (k in volFrom until lastIdx) { sumVol += candles[k].volume; cntVol++ }
        val avgVol = if (cntVol > 0) sumVol / cntVol else 1.0
        val volRatio = if (avgVol > 0) curVol / avgVol else 0.0

        val last = candles[lastIdx]
        val movePct = if (last.open > 0) (last.close - last.open) / last.open * 100 else 0.0
        val absMove = abs(movePct)
        val turnover1m = last.volume * last.close

        val candleRange = last.high - last.low
        val deltaPct1m = if (candleRange > 0.0) {
            ((2.0 * last.close - last.high - last.low) / candleRange * 100.0).coerceIn(-100.0, 100.0)
        } else 0.0
        val deltaUsd1m = last.volume * last.close * deltaPct1m / 100.0

        val liq = computeLiqStats(symbol)
        val liqThreshold = Prefs.liqThreshold(this)

        val shortsBurning = liq != null &&
            !liq.dominantIsLong &&
            liq.shortPct >= 60.0 &&
            liq.total15m >= 10_000.0

        val longsBurning = liq != null &&
            liq.dominantIsLong &&
            liq.longPct >= 60.0 &&
            liq.total15m >= 10_000.0

        val flowStatus = when {
            change15m > 0.3 -> when {
                openInterestChange < -0.3 -> "short_squeeze"
                openInterestChange > 0.3 -> "new_long"
                shortsBurning -> "short_squeeze"
                else -> "churn"
            }
            change15m < -0.3 -> when {
                openInterestChange < -0.3 -> "long_squeeze"
                openInterestChange > 0.3 -> "new_short"
                longsBurning -> "long_squeeze"
                else -> "churn"
            }
            else -> "churn"
        }

        val volSpike = volRatio >= 2.0
        val sharpMove = absMove >= 0.3
        val bigTurnover = turnover1m >= 20_000.0

        if (volSpike && (sharpMove || bigTurnover)) {
            signals.add(Signal("volume", "🚀", "Резкий скачок",
                "×${"%.1f".format(volRatio)} объём · ${"%+.2f".format(movePct)}% за мин"))
        } else if (bigTurnover && sharpMove) {
            signals.add(Signal("volume", "🚀", "Резкое движение",
                "${"%+.2f".format(movePct)}% · ${UiUtils.formatMoney(turnover1m)} за мин"))
        }

        if (signals.isEmpty() && !forceInclude) return null

        var turn1h = 0.0
        val from1h = max(0, lastIdx - 60)
        for (k in from1h until lastIdx) turn1h += candles[k].volume * candles[k].close

        val avgVol20 = if (candles.size >= 21) {
            candles.subList(candles.size - 21, candles.size - 1)
                .map { it.volume }
                .average()
        } else 1.0
        val newsLikeExplosion = avgVol20 > 0.0 &&
            last.volume / avgVol20 >= 10.0 &&
            abs(change15m) >= 5.0

        val rawSig = if (newsLikeExplosion) {
            SignalResult("", 0L, "")
        } else {
            SignalDetector.detectReversalSignal(candles, liq, liqThreshold)
        }

        val sigResult = if (rawSig.strength == "strong") {
            val lastNotified = synchronized(MarketState.signalCooldown) { MarketState.signalCooldown[symbol] ?: 0L }
            if (System.currentTimeMillis() - lastNotified < SIGNAL_COOLDOWN_MS) {
                rawSig.copy(strength = "weak")
            } else rawSig
        } else rawSig

        if (sigResult.type.isNotEmpty()) {
            synchronized(MarketState.signalHistoryLock) {
                MarketState.signalHistory[symbol] = SymbolSignal(
                    type = sigResult.type,
                    strength = sigResult.strength,
                    candleTime = sigResult.candleTime,
                    detectedAt = System.currentTimeMillis(),
                    price = curPrice
                )
            }
            addSignalMarker(symbol, sigResult.candleTime, sigResult.type, sigResult.pattern, Prefs.signalTf(this))
        }

        val peak = if (sigResult.type.isNotEmpty()) {
    val events = synchronized(MarketState.liqLock) { MarketState.liqBuffer[symbol]?.toList() ?: emptyList() }
    SignalDetector.computePeakForecast(curPrice, sigResult.type == "short", events)
} else PeakForecast.empty()
val accumulation = if (oiHistory != null && oiHistory.size >= 9 && deltaBuckets.size >= 9) {
    SignalDetector.detectAccumulation(candles, oiHistory, deltaBuckets)
} else null
        val obInfo = synchronized(MarketState.obLock) { MarketState.obInfos[symbol] }

        return ScanResult(symbol, curPrice, change15m, turnover24hFromTicker, turn1h,
            longRatio, shortRatio, volRatio, turnover1m, signals,
            openInterest = openInterest, openInterestChange = openInterestChange,
            longRatioChange = longRatioChange, oiTrend = oiTrend,
            signalType = sigResult.type,
            signalStrength = sigResult.strength,
            signalCandleTime = sigResult.candleTime,
            signalPattern = sigResult.pattern,
            peakForecast = peak.mid,
            peakLow = peak.low,
            peakHigh = peak.high,
            peakRemainPct = peak.remainPct,
            peakConfident = peak.confident,
            bidWallPrice = obInfo?.bidWallPrice ?: 0.0,
            bidWallUsd = obInfo?.bidWallUsd ?: 0.0,
            askWallPrice = obInfo?.askWallPrice ?: 0.0,
            askWallUsd = obInfo?.askWallUsd ?: 0.0,
            deltaPct1m = deltaPct1m,
            deltaUsd1m = deltaUsd1m,
            realDeltaUsd = 0.0,
            flowStatus = flowStatus,
            priceAt15mAgo = price15,
            accumulation = accumulation)
    }

    // ================== Render list ==================

    private fun renderList() {
        listContainer.removeAllViews()
        val sorted = sortResults(scanResults)
        val pinnedList = ArrayList<ScanResult>()
        val otherList = ArrayList<ScanResult>()
        synchronized(MarketState.pinnedLock) {
            for (r in sorted) {
                if (r.symbol in MarketState.pinnedSymbols) pinnedList.add(r) else otherList.add(r)
            }
        }
        if (pinnedList.isEmpty() && otherList.isEmpty()) {
            val tv = TextView(this)
            tv.text = "Пока ничего не нашлось.\nНажми ⟳ Сканировать ещё раз или подожди следующего скана."
            tv.setPadding(24, 24, 24, 24)
            tv.setTextColor(Prefs.textDim(this))
            tv.textSize = 15f
            listContainer.addView(tv)
            return
        }
        pinnedList.forEach { listContainer.addView(makeScanRow(it)) }
        otherList.forEach { listContainer.addView(makeScanRow(it)) }
    }

    private fun makeScanRow(r: ScanResult): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.VERTICAL
        val isPinned = synchronized(MarketState.pinnedLock) { r.symbol in MarketState.pinnedSymbols }
        val notifPriority = NotificationHelper.notificationPriority(r.symbol)

        val liq = getCachedLiqStats(r.symbol)
        val liqThreshold = Prefs.liqThreshold(this)
        val liqBg: Int? = when {
            liq == null -> null
            liq.total15m >= liqThreshold -> if (Prefs.theme(this) == "dark") Color.rgb(120, 40, 40) else Color.rgb(255, 200, 200)
            else -> null
        }
        val notifBg: Int? = when (notifPriority) {
            3 -> if (Prefs.theme(this) == "dark") Color.rgb(100, 20, 20) else Color.rgb(255, 190, 190)
            2 -> if (Prefs.theme(this) == "dark") Color.rgb(80, 70, 20) else Color.rgb(255, 240, 180)
            1 -> if (Prefs.theme(this) == "dark") Color.rgb(20, 60, 80) else Color.rgb(200, 230, 255)
            else -> null
        }
        when {
            notifBg != null -> row.setBackgroundColor(notifBg)
            liqBg != null -> row.setBackgroundColor(liqBg)
            r.score >= 3 -> row.setBackgroundColor(Prefs.hotRow(this))
            else -> row.setBackgroundColor(Prefs.row(this))
        }
   row.setPadding(24, 20, 24, 20)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(8, 6, 8, 6)
        row.layoutParams = lp

        r.accumulation?.let { acc ->
            if (acc.level.isNotEmpty()) {
                val accEmoji = when (acc.level) { "strong" -> "🔥"; "medium" -> "🟢"; else -> "🟡" }
                val accDir = if (acc.type == "long") "В ЛОНГ" else "В ШОРТ"
                val accColor = when (acc.level) { "strong" -> Color.rgb(200, 60, 20); "medium" -> Color.rgb(30, 120, 60); else -> Color.rgb(140, 110, 20) }
                row.addView(makeSignalBadge("$accEmoji НАКОПЛЕНИЕ $accDir", accColor))
            }
        }
        val patternSuffix = if (r.signalPattern.isNotEmpty()) " — ${r.signalPattern}" else ""
        if (r.signalType == "short" && r.signalStrength == "strong") {
            row.addView(makeSignalBadge("🟢 ВХОД В ШОРТ$patternSuffix", Color.rgb(30, 120, 60)))
        } else if (r.signalType == "long" && r.signalStrength == "strong") {
            row.addView(makeSignalBadge("🟢 ВХОД В ЛОНГ$patternSuffix", Color.rgb(30, 120, 60)))
        } else if (r.signalType == "short" && r.signalStrength == "weak" && !isWeakConflict(r)) {
            row.addView(makeSignalBadge("🟡 Возможен вход в шорт (объём/позиция)$patternSuffix", Color.rgb(140, 110, 20)))
        } else if (r.signalType == "long" && r.signalStrength == "weak" && !isWeakConflict(r)) {
            row.addView(makeSignalBadge("🟡 Возможен вход в лонг (объём/позиция)$patternSuffix", Color.rgb(140, 110, 20)))
        }

        val notifEmoji = when (notifPriority) { 3 -> "💥 "; 2 -> "⚡ "; 1 -> "📈 "; else -> "" }
        val scoreEmoji = when {
            r.signalStrength == "strong" -> "${notifEmoji}🟢🚀"
            r.signalStrength == "weak" && !isWeakConflict(r) -> "${notifEmoji}🟡🚀"
            liq != null && liq.total15m >= liqThreshold -> "${notifEmoji}🚀💥"
            r.score >= 3 -> "${notifEmoji}🚀🚀🚀"
            r.score == 2 -> "${notifEmoji}🚀🚀"
            else -> "${notifEmoji}🚀"
        }

        val titleRow = LinearLayout(this)
        titleRow.orientation = LinearLayout.HORIZONTAL
        titleRow.gravity = Gravity.CENTER_VERTICAL
        val title = TextView(this)
        title.text = "$scoreEmoji  ${r.symbol}"
        title.setTextColor(Prefs.text(this))
        title.textSize = 21f
        title.setTypeface(null, Typeface.BOLD)
        title.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        titleRow.addView(title)

        val copyBtn = TextView(this)
        copyBtn.text = "📋"
        copyBtn.textSize = 22f
        copyBtn.setPadding(12, 0, 0, 0)
        copyBtn.setOnClickListener { copyToClipboard(r) }
        titleRow.addView(copyBtn)

        val pinBtn = TextView(this)
        pinBtn.text = if (isPinned) "📍" else "📌"
        pinBtn.textSize = 24f
        pinBtn.setPadding(12, 0, 0, 0)
        pinBtn.setOnClickListener { togglePin(r.symbol) }
        titleRow.addView(pinBtn)
        row.addView(titleRow)

        val changeSign = if (r.change15m >= 0) "+" else ""
        val priceLine = TextView(this)
        priceLine.text = "Цена: ${"%.4f".format(r.price)}  ·  15м: $changeSign${"%.2f".format(r.change15m)}%"
        priceLine.setTextColor(Prefs.textDim(this))
        priceLine.textSize = 17f
        row.addView(priceLine)

        val volLine = TextView(this)
        volLine.text = "💰 24ч: ${UiUtils.formatMoney(r.turnover24h)} · 1ч: ${UiUtils.formatMoney(r.turnover1h)}"
        volLine.setTextColor(Prefs.textDim(this))
        volLine.textSize = 17f
        row.addView(volLine)

        if (r.deltaPct1m != 0.0 || r.flowStatus.isNotEmpty()) {
            val deltaLine = TextView(this)
            val deltaEmoji = when {
                r.deltaPct1m > 20 -> "🟢"
                r.deltaPct1m < -20 -> "🔴"
                else -> "⚪"
            }
            val deltaColor = when {
                r.deltaPct1m > 20 -> Color.rgb(120, 200, 140)
                r.deltaPct1m < -20 -> Color.rgb(220, 120, 120)
                else -> Prefs.textDim(this)
            }
            val flowLabel = when (r.flowStatus) {
                "new_long" -> "🟢 New Money ↑"
                "short_squeeze" -> "🔴 Short Squeeze"
                "new_short" -> "🔴 New Money ↓"
                "long_squeeze" -> "🟢 Long Squeeze"
                else -> "⚪ Churn"
            }
            val deltaSign = if (r.deltaPct1m >= 0) "+" else ""
            val realDeltaStr = when {
                r.realDeltaUsd == 0.0 -> "—"
                r.realDeltaUsd > 0 -> "+${UiUtils.formatMoney(r.realDeltaUsd)}"
                else -> "-${UiUtils.formatMoney(-r.realDeltaUsd)}"
            }
            deltaLine.text = "Δ 1м: $deltaEmoji $deltaSign${"%.0f".format(r.deltaPct1m)}% ($realDeltaStr buy/sell)  ·  $flowLabel"
            deltaLine.setTextColor(deltaColor)
            deltaLine.textSize = 16f
            deltaLine.setPadding(0, 2, 0, 0)
            row.addView(deltaLine)
        }

        if (r.openInterest > 0) {
            val oiLine = TextView(this)
            val oiChangeStr = if (r.openInterestChange >= 0)
                "+${"%.2f".format(r.openInterestChange)}%"
            else
                "${"%.2f".format(r.openInterestChange)}%"
            val trendIcon = when (r.oiTrend) {
                "accelerating" -> " ↗️"
                "slowing" -> " ↘️"
                else -> ""
            }
            val oiUsd = r.openInterest * r.price
            oiLine.text = "📊 OI: ${UiUtils.formatMoney(r.openInterest)} (~${UiUtils.formatMoney(oiUsd)}) · $oiChangeStr$trendIcon"
            oiLine.setTextColor(
                when {
                    r.openInterestChange > 1.0 -> Color.rgb(120, 200, 140)
                    r.openInterestChange < -1.0 -> Color.rgb(220, 120, 120)
                    else -> Prefs.textDim(this)
                }
            )
            oiLine.textSize = 18f
            row.addView(oiLine)
        }

        val lsLine = TextView(this)
        if (r.longRatio > 0 || r.shortRatio > 0) {
            val longPct = r.longRatio * 100
            val shortPct = r.shortRatio * 100
            val who = if (r.longRatio > r.shortRatio) "🟢 лонги" else "🔴 шорты"
            lsLine.text = "📊 Лонги ${"%.0f".format(longPct)}% · Шорты ${"%.0f".format(shortPct)}% · $who"
            lsLine.setTextColor(
                if (r.longRatio > r.shortRatio) Color.rgb(120, 200, 140)
                else Color.rgb(220, 120, 120)
            )
        } else {
            lsLine.text = "📊 Лонги/Шорты: нет данных"
            lsLine.setTextColor(Prefs.textDim(this))
        }
        lsLine.textSize = 18f
        row.addView(lsLine)

        if (liq != null && liq.total15m >= liqThreshold) {
            val liqLine = TextView(this)
            val direction = if (liq.dominantIsLong) "🐂 лонги" else "🐻 шорты"
            val domPct = if (liq.dominantIsLong) liq.longPct else liq.shortPct
            val trendIcon = when (liq.trend) {
                "rising" -> "🔥 растёт"
                "falling" -> "🪫 затухает"
                else -> "➡️ стабильно"
            }
            liqLine.text = "💥 Ликвидации: ${UiUtils.formatMoney(liq.total15m)} · $direction ${"%.0f".format(domPct)}% · $trendIcon"
            liqLine.textSize = 18f
            liqLine.setTypeface(null, Typeface.BOLD)
            liqLine.setTextColor(
                when (liq.trend) {
                    "rising" -> Color.rgb(255, 120, 120)
                    "falling" -> Color.rgb(255, 200, 60)
                    else -> Prefs.text(this)
                }
            )
            row.addView(liqLine)
            val peakList = synchronized(MarketState.cascadePeakLock) {
                MarketState.cascadePeaks[r.symbol]?.toList() ?: emptyList()
            }
            if (peakList.isNotEmpty()) {
                val avgPeak = peakList.map { it.sum }.average()
                if (avgPeak > 0) {
                    val percent = (liq.total15m / avgPeak * 100).toInt()
                    val maxPeak = peakList.maxOf { it.sum }
                    val exceeded = liq.total15m > maxPeak
                    val peaksStr = peakList.joinToString(", ") { UiUtils.formatMoney(it.sum) }
                    val peaksLine = TextView(this)
                    if (exceeded) {
                        peaksLine.text = "   Прошлые пики: $peaksStr · сейчас $percent% (перебит максимум!)"
                        peaksLine.setTextColor(Color.rgb(255, 150, 200))
                    } else {
                        peaksLine.text = "   Прошлые пики: $peaksStr · сейчас $percent% от среднего"
                        peaksLine.setTextColor(Prefs.textDim(this))
                    }
                    peaksLine.textSize = 15f
                    peaksLine.setPadding(0, 2, 0, 0)
                    row.addView(peaksLine)
                }
            }

            val zoneAhead = if (!liq.dominantIsLong) r.peakForecast > r.price else r.peakForecast < r.price
            if (r.peakForecast > 0 && zoneAhead) {
                val peakLine = TextView(this)
                val dirWord = if (!liq.dominantIsLong) "вверх" else "вниз"
                val zone = if (r.peakLow > 0 && r.peakHigh > r.peakLow) {
                    "зона ${"%.4f".format(r.peakLow)} – ${"%.4f".format(r.peakHigh)}"
                } else {
                    "%.4f".format(r.peakForecast)
                }
                val conf = if (r.peakConfident) "" else " ⚠️"
                peakLine.text = "🎯 Зона выноса: $zone (ещё ${"%.1f".format(r.peakRemainPct)}% $dirWord)$conf"
                peakLine.textSize = 16f
                peakLine.setTextColor(Color.rgb(120, 200, 255))
                peakLine.setPadding(0, 2, 0, 0)
                row.addView(peakLine)
            }
        }

        val sp = synchronized(MarketState.liveLock) { MarketState.liveSpeeds[r.symbol] }
        if (sp != null) {
            val spLine = TextView(this)
            val icon = when {
                sp.accelerating -> "⚡⚡⚡ ускорение"
                sp.dying -> "🪫 затухание"
                else -> "⚡ стабильно"
            }
            spLine.text = "$icon · ${"%.2f".format(sp.speedNow)}%/мин"
            spLine.setTextColor(
                when {
                    sp.accelerating -> Color.rgb(120, 255, 150)
                    sp.dying -> Color.rgb(255, 150, 150)
                    else -> Prefs.textDim(this)
                }
            )
            spLine.textSize = 17f
            row.addView(spLine)
        }

            val ts = synchronized(MarketState.liveLock) { MarketState.liveTradeSpeeds[r.symbol] }
            if (ts != null) {
                val tsEmoji = when {
                    ts.pctPerMin > 0.3 -> "🔥↑"
                    ts.pctPerMin < -0.3 -> "🔥↓"
                    ts.pctPerMin > 0.05 -> "·↑"
                    ts.pctPerMin < -0.05 -> "·↓"
                    else -> "·"
                }
                val tsSign = if (ts.pctPerMin >= 0) "+" else ""
                val tsLine = TextView(this)
                tsLine.text = "$tsEmoji $tsSign${"%.2f".format(ts.pctPerMin)}%/мин (${ts.tradeCount} сделок)"
                tsLine.setTextColor(
                    when {
                        ts.pctPerMin > 0.3 -> Color.rgb(120, 255, 150)
                        ts.pctPerMin < -0.3 -> Color.rgb(255, 150, 150)
                        else -> Prefs.textDim(this)
                    }
                )
                tsLine.textSize = 17f
                row.addView(tsLine)
            }

        if (r.signals.isNotEmpty()) {
            r.signals.forEach { sig ->
                val sigLine = TextView(this)
                sigLine.text = "${sig.emoji} ${sig.detail}"
                sigLine.setTextColor(Color.rgb(255, 200, 60))
                sigLine.textSize = 16f
                sigLine.setTypeface(null, Typeface.BOLD)
                sigLine.setPadding(0, 2, 0, 0)
                row.addView(sigLine)
            }
        }

        val obInfo = synchronized(MarketState.obLock) { MarketState.obInfos[r.symbol] }
        val obLine = TextView(this)
        if (obInfo != null) {
            val imb = obInfo.imbalance
            val (icon, color, label) = when {
                imb >= 0.30 -> Triple("🟢", Color.rgb(120, 200, 140), "покупатели")
                imb <= -0.30 -> Triple("🔴", Color.rgb(220, 120, 120), "продавцы")
                else -> Triple("⚪", Prefs.textDim(this), "баланс")
            }
            val sign = if (imb >= 0) "+" else ""
            obLine.text = "⚖️ Стакан: $icon $sign${"%.2f".format(imb)} ($label)"
            obLine.setTextColor(color)
            obLine.textSize = 17f
            row.addView(obLine)

            if ((obInfo.bidWallPrice > 0 || obInfo.askWallPrice > 0) && (obInfo.bidWallUsd >= 1000.0 || obInfo.askWallUsd >= 1000.0)) {
                val wallLine = TextView(this)
                val bidStr = if (obInfo.bidWallPrice > 0)
                    "🟢 ${"%.4f".format(obInfo.bidWallPrice)} (${UiUtils.formatMoney(obInfo.bidWallUsd)})"
                else "—"
                val askStr = if (obInfo.askWallPrice > 0)
                    "🔴 ${"%.4f".format(obInfo.askWallPrice)} (${UiUtils.formatMoney(obInfo.askWallUsd)})"
                else "—"
                val bidWins = obInfo.bidWallUsd > obInfo.askWallUsd
                val askWins = obInfo.askWallUsd > obInfo.bidWallUsd
                val bidColor = if (bidWins) Color.rgb(120, 255, 150) else Prefs.textDim(this)
                val askColor = if (askWins) Color.rgb(255, 150, 150) else Prefs.textDim(this)
                val sp = android.text.SpannableStringBuilder()
                sp.append("   Bid: ")
                val bidStart = sp.length
                sp.append(bidStr)
                sp.setSpan(android.text.style.ForegroundColorSpan(bidColor), bidStart, sp.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sp.append("  ·  Ask: ")
                val askStart = sp.length
                sp.append(askStr)
                sp.setSpan(android.text.style.ForegroundColorSpan(askColor), askStart, sp.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                wallLine.text = sp
                wallLine.setTextColor(Prefs.textDim(this))
                wallLine.textSize = 15f
                wallLine.setPadding(0, 2, 0, 0)
                row.addView(wallLine)
            }
        } else {
            obLine.text = "⚖️ Стакан: собираю данные..."
            obLine.setTextColor(Prefs.textDim(this))
            obLine.textSize = 17f
            row.addView(obLine)
        }

        r.accumulation?.let { acc ->
            if (acc.level.isNotEmpty()) {
                val accEmoji = when (acc.level) { "strong" -> "🔥"; "medium" -> "🟢"; else -> "🟡" }
                val accDir = if (acc.type == "long") "лонг" else "шорт"
                val accColor = when (acc.level) { "strong" -> Color.rgb(255, 120, 60); "medium" -> Color.rgb(120, 200, 140); else -> Color.rgb(255, 200, 60) }
                val accLine = TextView(this)
                accLine.text = "$accEmoji Накопление $accDir · OI +${"%.2f".format(acc.oiChangePct)}% (${acc.oiBarsPositive}/9) · Диапазон ${"%.2f".format(acc.rangePct)}% · Delta ${UiUtils.formatMoney(acc.deltaUsd)}"
                accLine.setTextColor(accColor)
                accLine.textSize = 17f
                accLine.setTypeface(null, Typeface.BOLD)
                accLine.setPadding(0, 4, 0, 0)
                row.addView(accLine)
            }
        }

        if (r.signalType.isEmpty()) {
            val hist = synchronized(MarketState.signalHistoryLock) { MarketState.signalHistory[r.symbol] }
            if (hist != null && hist.candleTime > 0L) {
                val ageMs = System.currentTimeMillis() - hist.detectedAt
                val ageMin = ageMs / 60_000L
                val ageLabel = when {
                    ageMin < 1 -> "только что"
                    ageMin < 60 -> "$ageMin мин назад"
                    ageMin < 1440 -> "${ageMin / 60} ч назад"
                    else -> "${ageMin / 1440} дн назад"
                }
                val sigLabel = if (hist.type == "long") "лонг" else "шорт"
                val strLabel = if (hist.strength == "strong") "STRONG" else "WEAK"
                val histLine = TextView(this)
                histLine.text = "📜 Был сигнал $ageLabel ($strLabel $sigLabel)"
                histLine.setTextColor(Color.rgb(150, 150, 170))
                histLine.textSize = 15f
                histLine.setPadding(0, 2, 0, 0)
                row.addView(histLine)
            }
        }

        row.addView(makePaperTradingRow(r))

        row.setOnClickListener { openChart(r.symbol) }
        return row
    }

    private fun makePaperTradingRow(r: ScanResult): View {
        val container = LinearLayout(this)
        container.orientation = LinearLayout.HORIZONTAL
        val clp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        clp.setMargins(0, 8, 0, 0)
        container.layoutParams = clp

        val open = PaperTrading.openTrades().firstOrNull { it.symbol == r.symbol }

        if (open != null) {
            val pnl = open.unrealizedPnl(r.price)
            val pnlPct = open.unrealizedPnlPct(r.price)
            val pnlColor = if (pnl >= 0) Color.rgb(120, 200, 140) else Color.rgb(220, 120, 120)
            val sign = if (pnl >= 0) "+" else ""
            val dir = if (open.isLong) "🟢 LONG" else "🔴 SHORT"

            val info = TextView(this)
            info.text = "$dir · $${"%.2f".format(open.sizeUsd)} · $sign$${"%.2f".format(pnl)} ($sign${"%.1f".format(pnlPct)}%)"
            info.textSize = 13f
            info.setTypeface(null, Typeface.BOLD)
            info.setTextColor(pnlColor)
            info.gravity = Gravity.CENTER_VERTICAL
            info.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            container.addView(info)

            val closeBtn = Button(this)
            closeBtn.text = "❌ Закрыть"
            closeBtn.textSize = 12f
            closeBtn.isAllCaps = false
            closeBtn.setTextColor(Color.WHITE)
            closeBtn.background = pressedDrawable(Color.rgb(140, 50, 50), Color.rgb(180, 70, 70))
            closeBtn.setPadding(20, 8, 20, 8)
            closeBtn.setOnClickListener {
                if (r.price > 0) {
                    val closed = PaperTrading.closeTrade(this, open.id, r.price, "manual")
                    if (closed != null) {
                        Toast.makeText(this, "${r.symbol}: $sign$${"%.2f".format(closed.realizedPnl())}", Toast.LENGTH_SHORT).show()
                        updateBalanceLabel()
                        requestRender(0)
                    }
                }
            }
            container.addView(closeBtn)
        } else {
            val sizeUsd = Prefs.paperTradeSize(this)

            val buyBtn = Button(this)
            buyBtn.text = "🟢 BUY $${"%.0f".format(sizeUsd)}"
            buyBtn.textSize = 12f
            buyBtn.isAllCaps = false
            buyBtn.setTextColor(Color.WHITE)
            buyBtn.background = pressedDrawable(Color.rgb(30, 120, 60), Color.rgb(45, 160, 80))
            buyBtn.setPadding(16, 8, 16, 8)
            val bLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            bLp.setMargins(0, 0, 4, 0)
            buyBtn.layoutParams = bLp
            buyBtn.setOnClickListener {
                val balance = PaperTrading.balance()
                if (balance < sizeUsd) {
                    Toast.makeText(this, "Недостаточно средств: $${"%.2f".format(balance)}", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val ctx = buildContextSnapshot(r)
                val t = PaperTrading.openTrade(this, r.symbol, true, r.price, sizeUsd, ctx)
                if (t != null) {
                    Toast.makeText(this, "LONG ${r.symbol} @ ${"%.4f".format(r.price)}", Toast.LENGTH_SHORT).show()
                    updateBalanceLabel()
                    requestRender(0)
                }
            }
            container.addView(buyBtn)

            val sellBtn = Button(this)
            sellBtn.text = "🔴 SELL $${"%.0f".format(sizeUsd)}"
            sellBtn.textSize = 12f
            sellBtn.isAllCaps = false
            sellBtn.setTextColor(Color.WHITE)
            sellBtn.background = pressedDrawable(Color.rgb(140, 50, 50), Color.rgb(180, 70, 70))
            sellBtn.setPadding(16, 8, 16, 8)
            val sLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            sLp.setMargins(4, 0, 0, 0)
            sellBtn.layoutParams = sLp
            sellBtn.setOnClickListener {
                val balance = PaperTrading.balance()
                if (balance < sizeUsd) {
                    Toast.makeText(this, "Недостаточно средств: $${"%.2f".format(balance)}", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val ctx = buildContextSnapshot(r)
                val t = PaperTrading.openTrade(this, r.symbol, false, r.price, sizeUsd, ctx)
                if (t != null) {
                    Toast.makeText(this, "SHORT ${r.symbol} @ ${"%.4f".format(r.price)}", Toast.LENGTH_SHORT).show()
                    updateBalanceLabel()
                    requestRender(0)
                }
            }
            container.addView(sellBtn)
        }

        return container
    }

    private fun buildContextSnapshot(r: ScanResult): PaperTrading.ContextSnapshot {
        val liq = getCachedLiqStats(r.symbol)
        val liqThreshold = Prefs.liqThreshold(this)
        val sp = synchronized(MarketState.liveLock) { MarketState.liveSpeeds[r.symbol] }
        val obInfo = synchronized(MarketState.obLock) { MarketState.obInfos[r.symbol] }

        val hasCascade = liq != null && liq.total15m >= liqThreshold

        return PaperTrading.ContextSnapshot(
            liqTotal15m = liq?.total15m ?: 0.0,
            liqLongPct = liq?.longPct ?: 0.0,
            liqShortPct = liq?.shortPct ?: 0.0,
            liqTrend = liq?.trend ?: "",
            hasCascade = hasCascade,
            oiValue = r.openInterest,
            oiChange = r.openInterestChange,
            oiTrend = r.oiTrend,
            deltaPct1m = r.deltaPct1m,
            realDeltaUsd = r.realDeltaUsd,
            flowStatus = r.flowStatus,
            speedNow = sp?.speedNow ?: 0.0,
            speedAccelerating = sp?.accelerating ?: false,
            speedDying = sp?.dying ?: false,
            volRatio = r.volRatio,
            turnover1m = r.turnover1m,
            change15m = r.change15m,
            longRatio = r.longRatio,
            shortRatio = r.shortRatio,
            obImbalance = obInfo?.imbalance ?: 0.0,
            signalType = r.signalType,
            signalStrength = r.signalStrength,
            signalPattern = r.signalPattern
        )
    }

    private fun makeSignalBadge(text: String, bgColor: Int): TextView {
        val badge = TextView(this)
        badge.text = text
        badge.setTextColor(Color.WHITE)
        badge.textSize = 18f
        badge.setTypeface(null, Typeface.BOLD)
        badge.setBackgroundColor(bgColor)
        badge.setPadding(16, 10, 16, 10)
        val sLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        sLp.setMargins(0, 0, 0, 8)
        badge.layoutParams = sLp
        return badge
    }


    // ================== Chart mode ==================

    private fun openChart(symbol: String) {
        currentSymbol = symbol
        isChartMode = true
        logEvent("chart_opened", Bundle().apply { putString("symbol", symbol) })
        chartTitle.text = "$currentSymbol · $currentTfLabel"
        chartTitle.setTextColor(Prefs.textDim(this))
        applyOrientation()

        val cacheKey = "$currentSymbol|$currentTf"
        val cached = synchronized(chartCacheLock) { chartCache[cacheKey] }
        val now = System.currentTimeMillis()

        if (cached != null && cached.candles.isNotEmpty()) {
            applyChartData(cached.candles, cached.ls)
        }

        executor.execute {
            if (synchronized(MarketState.obLock) { MarketState.obInfos[symbol] } == null) {
                val info = BybitApi.fetchOrderbookInfo(symbol)
                if (info != null) {
                    synchronized(MarketState.obLock) { MarketState.obInfos[symbol] = info }
                }
            }
        }

        if (cached != null && (now - cached.timestamp) < CHART_CACHE_TTL_MS) {
            startSocket(symbol)
            return
        }

        chartLoading = true
        val loadStart = System.currentTimeMillis()

        executor.execute {
            try {
                val limit = Prefs.historyForTf(this, currentTf)
                val lsPeriod = lsPeriodForTf(currentTf)
                val tfLocal = currentTf

                val candlesFuture = CompletableFuture.supplyAsync(
                    { BybitApi.fetchKlines(symbol, tfLocal, limit).reversed() }, executor
                )
                val lsFuture = CompletableFuture.supplyAsync(
                    { try { BybitApi.fetchLsHistory(symbol, lsPeriod, 100) } catch (e: Exception) { emptyList<LsPoint>() } }, executor
                )

                val candles = try { candlesFuture.get(25, TimeUnit.SECONDS) } catch (e: Exception) {
                    emptyList<Candle>()
                }
                val ls = try { lsFuture.get(25, TimeUnit.SECONDS) } catch (e: Exception) { emptyList<LsPoint>() }

                if (candles.isNotEmpty()) {
                    synchronized(chartCacheLock) {
                        chartCache[cacheKey] = ChartCache(candles, ls, System.currentTimeMillis())
                    }
                }

                Handler(Looper.getMainLooper()).post {
                    if (isDestroyed) return@post
                    chartLoading = false
                    if (candles.isEmpty()) return@post
                    applyChartData(candles, ls)
                }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post { if (isDestroyed) return@post; chartLoading = false }
            }
        }
        startSocket(symbol)
    }

    private fun refreshChart() {
        if (!isChartMode) return
        executor.execute {
            try {
                val limit = Prefs.historyForTf(this, currentTf)
                val lsPeriod = lsPeriodForTf(currentTf)
                val sym = currentSymbol
                val tfLocal = currentTf

                val candlesFuture = CompletableFuture.supplyAsync(
                    { BybitApi.fetchKlines(sym, tfLocal, limit).reversed() }, executor
                )
                val lsFuture = CompletableFuture.supplyAsync(
                    { try { BybitApi.fetchLsHistory(sym, lsPeriod, 100) } catch (e: Exception) { emptyList<LsPoint>() } }, executor
                )
                val candles = try { candlesFuture.get(25, TimeUnit.SECONDS) } catch (e: Exception) {
                    emptyList<Candle>()
                }
                val ls = try { lsFuture.get(25, TimeUnit.SECONDS) } catch (e: Exception) { emptyList<LsPoint>() }

                if (candles.isEmpty()) return@execute

                Handler(Looper.getMainLooper()).post {
                    if (isDestroyed) return@post
                    val wasAtRight = (offset + visibleCount) >= allCandles.size
                    allCandles = candles
                    offset = if (wasAtRight) max(0, candles.size - visibleCount)
                             else offset.coerceIn(0, max(0, candles.size - visibleCount))
                    chartView.setData(candles, offset, visibleCount, Prefs.chartType(this), Prefs.theme(this))
                    chartView.setLsPoints(ls)
                    chartView.setSignalMarkers(getSignalMarkers(currentSymbol))
                    val chartEvts2 = synchronized(MarketState.chartEventsLock) {
                        MarketState.chartEvents[currentSymbol]?.toList() ?: emptyList()
                    }
                    chartView.setChartEvents(chartEvts2)
                    val obInfo = synchronized(MarketState.obLock) { MarketState.obInfos[currentSymbol] }
                    chartView.setWalls(
                        obInfo?.bidWallPrice ?: 0.0, obInfo?.bidWallUsd ?: 0.0,
                        obInfo?.askWallPrice ?: 0.0, obInfo?.askWallUsd ?: 0.0
                    )
                }
            } catch (_: Exception) {}
        }
    }

    private fun showList() {
        if (!isChartMode) return
        stopSocket()
        isChartMode = false
        applyOrientation()
        updateBalanceLabel()
    }
}