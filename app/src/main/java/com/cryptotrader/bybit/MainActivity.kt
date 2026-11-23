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
import java.io.File
import java.io.FileOutputStream
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
    private lateinit var chartTitleRow: LinearLayout
    private lateinit var chartCopyBtn: Button
    private lateinit var chartShareBtn: Button
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
    private var volumeAggregator: VolumeBarAggregator? = null
    private var activeChartType = ""
    private var activeVolumeThreshold = 0.0
    private var lastVolumeRender = 0L
    private var lastChartUpdate = 0L
    private val chartUpdateInterval = 50L

    private var renderScheduled = false
    private val renderRunnable = Runnable {
        renderScheduled = false
        renderList()
    }
    private fun requestRender(delayMs: Long = 1500) {
        if (renderScheduled) return
        renderScheduled = true
        scanner.postDelayed(renderRunnable, delayMs)
    }


    private var liqSocket: LiquidationSocket? = null
    private var currentLiqSymbols: List<String> = emptyList()
    @Volatile private var lastLiqZoneRefresh = 0L


    private val tradeDeltaAnalyzer = TradeDeltaAnalyzer()
    private val reversalScoreLock = Any()
    private val frozenReversalScores = HashMap<String, Pair<Long, SignalResult>>()

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
        if (isChartMode) {
            val type = Prefs.chartType(this)
            val threshold = Prefs.volumeBarUsdt(this)
            if (type != activeChartType || (type == "volume" && threshold != activeVolumeThreshold)) {
                openChart(currentSymbol)
            }
        }
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
        liqClusters = synchronized(MarketState.liqClustersLock) { MarketState.liqClusters.toMap() },
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

        synchronized(MarketState.liqClustersLock) {
            MarketState.liqClusters.clear()
            state.liqClusters.forEach { (k, v) ->
                MarketState.liqClusters[k] = ArrayList(v)
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

        chartTitleRow = LinearLayout(this)
        chartTitleRow.orientation = LinearLayout.HORIZONTAL
        chartTitleRow.gravity = Gravity.CENTER_VERTICAL
        chartTitleRow.setPadding(16, 4, 16, 4)
        chartTitleRow.visibility = View.GONE

        chartTitle = TextView(this)
        chartTitle.text = "$currentSymbol · $currentTfLabel"
        chartTitle.textSize = 15f
        chartTitle.setTextColor(Prefs.textDim(this))
        chartTitle.gravity = Gravity.CENTER
        chartTitle.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        chartTitleRow.addView(chartTitle)

        chartCopyBtn = Button(this)
        chartCopyBtn.text = "📋"
        chartCopyBtn.textSize = 16f
        chartCopyBtn.isAllCaps = false
        chartCopyBtn.setTextColor(Prefs.text(this))
        chartCopyBtn.background = pressedDrawable(Prefs.buttonOff(this), Prefs.buttonPressed(this))
        chartCopyBtn.setPadding(20, 8, 20, 8)
        chartCopyBtn.setOnClickListener { copyChartToClipboard() }
        chartTitleRow.addView(chartCopyBtn)

        chartShareBtn = Button(this)
        chartShareBtn.text = "📤"
        chartShareBtn.textSize = 16f
        chartShareBtn.isAllCaps = false
        chartShareBtn.setTextColor(Prefs.text(this))
        chartShareBtn.background = pressedDrawable(Prefs.buttonOff(this), Prefs.buttonPressed(this))
        chartShareBtn.setPadding(20, 8, 20, 8)
        chartShareBtn.setOnClickListener { shareChart() }
        chartTitleRow.addView(chartShareBtn)

        root.addView(chartTitleRow)

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

    private fun dirForFlow(flow: String): String = when (flow) {
        "new_long" -> "🟢"
        "short_squeeze" -> "🟢"
        "new_short" -> "🔴"
        "long_squeeze" -> "🔴"
        else -> "⚪"
    }

    private fun dirForLiq(liq: LiqStats?): String {
        if (liq == null) return "⚪"
        return if (liq.dominantIsLong) "🔴" else "🟢"
    }

    private fun dirForSpeed(pct: Double): String = when {
        pct > 0.05 -> "🟢"
        pct < -0.05 -> "🔴"
        else -> "⚪"
    }

    private fun dirForAcc(type: String): String = when (type) {
        "long" -> "🟢"
        "short" -> "🔴"
        else -> "⚪"
    }

    private fun dirForMove(movePct: Double): String = when {
        movePct > 0.01 -> "🟢"
        movePct < -0.01 -> "🔴"
        else -> "⚪"
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
            val liq = MarketState.liqStatsCache[r.symbol]
            if (liq != null && liq.total15m >= liqThreshold) {
                addChartEvent(r.symbol, ChartEvent(candleStart, "liq", 1))
            }
        }

        synchronized(MarketState.chartEventsLock) {
            for (list in MarketState.chartEvents.values) {
                list.removeAll { it.time < cutoff || it.type != "liq" }
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
        if (Prefs.chartType(this) == "volume") return
        chartView.setVolumeBars(emptyList())
        allCandles = candles
        visibleCount = min(100, candles.size)
        offset = max(0, candles.size - visibleCount + visibleCount / 5)
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
        chartView.setLiqClusters(computeLiqClusters(currentSymbol))
        val currentOiChange = scanResults.firstOrNull { it.symbol == currentSymbol }?.openInterestChange ?: 0.0
        val estimatedZones = estimateFutureLiqZones(currentSymbol, candles, currentOiChange)
        chartView.setFutureLiqZones(
            if (Prefs.futureLiquidationsEnabled(this)) estimatedZones else emptyList()
        )
        checkFutureZoneAlert(currentSymbol, candles.last().close, estimatedZones)
        checkCascadeAlert(currentSymbol)
                        val acc2 = scanResults.firstOrNull { it.symbol == currentSymbol }?.accumulation
                        chartView.setAccumulationZone(acc2?.priceLow ?: 0.0, acc2?.priceHigh ?: 0.0)
        val acc = scanResults.firstOrNull { it.symbol == currentSymbol }?.accumulation
        chartView.setAccumulationZone(acc?.priceLow ?: 0.0, acc?.priceHigh ?: 0.0)
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

    private fun copyChartToClipboard() {
        try {
            val bmp = chartView.exportBitmap() ?: return
            val file = File(cacheDir, "charts")
            if (!file.exists()) file.mkdirs()
            val png = File(file, "chart_${System.currentTimeMillis()}.png")
            FileOutputStream(png).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", png)
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newUri(contentResolver, "chart", uri))
            Toast.makeText(this, "Скопировано (вставь в чат)", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка: ${e.message?.take(60)}", Toast.LENGTH_LONG).show()
        }
    }

    private fun shareChart() {
        try {
            val bmp = chartView.exportBitmap() ?: return
            val file = File(cacheDir, "charts")
            if (!file.exists()) file.mkdirs()
            val png = File(file, "chart_${System.currentTimeMillis()}.png")
            FileOutputStream(png).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", png)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Поделиться графиком"))
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка: ${e.message?.take(60)}", Toast.LENGTH_LONG).show()
        }
    }

    private fun formatTimeLeft(ms: Long): String {
        if (ms <= 0) return "0м"
        val totalMinutes = ms / 60_000L
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        val warning = if (totalMinutes < 15) " ⚠️" else ""
        return if (hours > 0) "${hours}ч ${minutes}м$warning" else "${minutes}м$warning"
    }

    private fun applyOrientation() {
        if (isChartMode) {
            listFrame.visibility = View.GONE
            chartView.visibility = View.VISIBLE
            chartTitleRow.visibility = View.VISIBLE
            tfRow.visibility = View.VISIBLE
            chartCopyBtn.visibility = View.VISIBLE
            chartShareBtn.visibility = View.VISIBLE
            backButton.visibility = View.VISIBLE
            refreshButton.visibility = View.GONE
            portfolioButton.visibility = View.GONE
            balanceLabel.visibility = View.GONE
        } else {
            chartView.visibility = View.GONE
            chartTitleRow.visibility = View.GONE
            chartCopyBtn.visibility = View.GONE
            chartShareBtn.visibility = View.GONE
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

    private fun cardSignalStatus(r: ScanResult): String {
        val direction = when (r.signalType) {
            "long" -> "LONG"
            "short" -> "SHORT"
            else -> return "⏳ Нет подтверждённого сигнала"
        }
        return if (r.signalStrength == "strong") "✅ Сигнал $direction"
        else "🟡 $direction · слабый сигнал, вход не подтверждён силой"
    }

    private fun cardPositionConflict(r: ScanResult): Boolean {
        val open = PaperTrading.openTrades().firstOrNull { it.symbol == r.symbol } ?: return false
        return (r.signalType == "long" && !open.isLong) ||
            (r.signalType == "short" && open.isLong)
    }

    private fun cardLiquidations(symbol: String): String {
        val liq = getCachedLiqStats(symbol) ?: return ""
        if (liq.total15m <= 0.0) return ""
        val trend = when (liq.trend) {
            "rising" -> "растут"
            "falling" -> "снижаются"
            else -> "стабильно"
        }
        return "Ликвидации 15м: ${UiUtils.formatMoney(liq.total15m)} · " +
            "LONG ${UiUtils.formatMoney(liq.long15m)} / SHORT ${UiUtils.formatMoney(liq.short15m)} · $trend"
    }

    private fun cardLevels(r: ScanResult): String {
        val open = PaperTrading.openTrades().firstOrNull { it.symbol == r.symbol }
        if (open != null) {
            return "Позиция ${if (open.isLong) "LONG" else "SHORT"} · фактические уровни робота\n" +
                "Вход: ${"%.4f".format(open.entryPrice)} · SL: ${"%.4f".format(open.slPrice)} · TP: ${"%.4f".format(open.tpPrice)}"
        }
        if (r.signalEntry <= 0.0 || (r.signalType != "long" && r.signalType != "short"))
            return ""
        val slPct = Prefs.paperSlPercent(this)
        val tpPct = Prefs.paperTpPercent(this)
        if (slPct <= 0.0 || tpPct <= 0.0) return "Проверь настройки SL/TP"
        val long = r.signalType == "long"
        val entry = r.signalEntry
        val stop = entry * (if (long) 1 - slPct / 100.0 else 1 + slPct / 100.0)
        val target = entry * (if (long) 1 + tpPct / 100.0 else 1 - tpPct / 100.0)
        if (target <= 0.0 || stop <= 0.0) return "Проверь настройки SL/TP"
        val moved = kotlin.math.abs(r.price - entry) / entry * 100.0 >= 0.5
        val prefix = if (moved) "⚠️ Цена ушла от исторического входа" else "План по настройкам робота (не ордер)"
        return "$prefix\nВход-ориентир: ${"%.4f".format(entry)} · SL: ${"%.4f".format(stop)} ($slPct%)\n" +
            "TP: ${"%.4f".format(target)} ($tpPct%) · R:R 1:${"%.2f".format(tpPct / slPct)}"
    }

    private fun buildCardText(r: ScanResult): String {
        val lines = ArrayList<String>()
        lines.add(r.symbol)
        val hasSignal = r.signalType == "long" || r.signalType == "short"
        val direction = if (r.signalType == "long") "LONG" else "SHORT"
        lines.add(cardSignalStatus(r))
        if (hasSignal && r.signalStrength != "strong")
            lines.add("Паттерн подтверждён свечой, но условий для сильного сигнала недостаточно")
        if (cardPositionConflict(r))
            lines.add("⚠️ Противоположный сигнал к открытой виртуальной позиции")
        if (hasSignal && r.signalPattern.isNotEmpty()) lines.add(r.signalPattern)
        lines.add("Цена: " + "%.4f".format(r.price) + " · 15м: " + "%+.2f".format(r.change15m) + "%")
        if (hasSignal) lines.add("Свеча подтверждения закрыта")
        if (cardLiquidations(r.symbol).isNotEmpty()) lines.add(cardLiquidations(r.symbol))
        if (hasSignal && cardLevels(r).isNotEmpty()) lines.add(cardLevels(r))
        val open = PaperTrading.openTrades().firstOrNull { it.symbol == r.symbol }
        if (open != null) {
            val pnl = open.unrealizedPnl(r.price)
            lines.add("Виртуальная позиция " + (if (open.isLong) "LONG" else "SHORT") +
                " · PnL " + "%+.2f".format(pnl) + " USD")
        }
        return lines.joinToString("\n")
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
                    chartView.setLiqClusters(computeLiqClusters(currentSymbol))
                        val acc2 = scanResults.firstOrNull { it.symbol == currentSymbol }?.accumulation
                        chartView.setAccumulationZone(acc2?.priceLow ?: 0.0, acc2?.priceHigh ?: 0.0)
        val acc = scanResults.firstOrNull { it.symbol == currentSymbol }?.accumulation
        chartView.setAccumulationZone(acc?.priceLow ?: 0.0, acc?.priceHigh ?: 0.0)
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
                val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
                buf.removeAll { it.time < cutoff }
            }
            // Keep the liquidation overlay current without waiting for a candle refresh.
            // Throttle redraws when the exchange sends bursts of liquidation events.
            val now = System.currentTimeMillis()
            if (isChartMode && symbol == currentSymbol && now - lastLiqZoneRefresh >= 1_000L) {
                lastLiqZoneRefresh = now
                chartView.post {
                    if (!isDestroyed && isChartMode && symbol == currentSymbol) {
                        chartView.setLiqClusters(computeLiqClusters(symbol))
                    }
                }
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
            val usd = price * size
            tradeDeltaAnalyzer.addTrade(symbol, time, usd, isBuy)
            val ltThreshold = Prefs.largeTradeThreshold(this)
            if (usd >= ltThreshold) {
                synchronized(MarketState.largeTradesLock) {
                    val buf = MarketState.largeTrades.getOrPut(symbol) { ArrayList() }
                    buf.add(LargeTrade(time, usd, isBuy, price))
                    val cutoff = System.currentTimeMillis() - 60 * 60_000L
                    buf.removeAll { it.time < cutoff }
                    while (buf.size > 20) buf.removeAt(0)
                }
            }
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
    private val cascadeAlertState = HashMap<String, Boolean>()
    private val cascadeLastCheck = HashMap<String, Long>()

    private fun nearestFutureZone(symbol: String, price: Double): FutureLiqZone? {
        if (price <= 0.0) return null
        return synchronized(futureZoneLock) {
            val zones = futureZoneState[symbol] ?: return@synchronized null
            val strongest = zones.maxOfOrNull { it.weight } ?: return@synchronized null
            if (strongest <= 0.0) return@synchronized null
            zones.filter { it.weight >= strongest * 0.35 }
                .minByOrNull { kotlin.math.abs(it.price / price - 1.0) }
                ?.let { FutureLiqZone(it.price, it.side, it.leverage, it.weight / strongest) }
        }
    }

    private fun checkFutureZoneAlert(symbol: String, price: Double, zones: List<FutureLiqZone>) {
        if (price <= 0.0 || zones.isEmpty()) return
        val near = zones.filter { it.strength >= 0.65 &&
            kotlin.math.abs(it.price / price - 1.0) <= 0.005 }
            .minByOrNull { kotlin.math.abs(it.price - price) } ?: return
        NotificationHelper.sendRiskAlert(this, symbol, "cluster",
            "⚠️ Цена рядом с оценочным кластером ${near.side} ${near.leverage}×")
    }

    private val cascadeStages = HashMap<String, Int>()
    private val cascadeWeakChecks = HashMap<String, Int>()

    private fun checkCascadeAlert(symbol: String) {
        val now = System.currentTimeMillis()
        if (now - (cascadeLastCheck[symbol] ?: 0L) < 60_000L) return
        cascadeLastCheck[symbol] = now
        val events = synchronized(MarketState.liqLock) {
            MarketState.liqBuffer[symbol]?.toList() ?: emptyList()
        }
        val recent = events.filter { it.time >= now - 180_000L && it.usd > 0.0 }
        val previous = events.filter {
            it.time >= now - 360_000L && it.time < now - 180_000L && it.usd > 0.0
        }
        val recentUsd = recent.sumOf { it.usd }
        val previousUsd = previous.sumOf { it.usd }
        val active = recent.size >= 3 && recentUsd >= 50_000.0 &&
            recentUsd > previousUsd * 1.5
        val stage = cascadeStages[symbol] ?: 0
        if (active && stage == 0) {
            cascadeStages[symbol] = 1
            cascadeWeakChecks[symbol] = 0
            NotificationHelper.sendRiskAlert(this, symbol, "cascade",
                "💥 Возможное начало каскада: рост реальных ликвидаций за 3 минуты")
            return
        }
        if (stage == 0) return

        // Compare direction-specific liquidation pressure, not just total volume.
        val longDominant = previous.filter { it.isLong }.sumOf { it.usd } >=
            previous.filter { !it.isLong }.sumOf { it.usd }
        val previousSide = previous.filter { it.isLong == longDominant }.sumOf { it.usd }
        val recentSide = recent.filter { it.isLong == longDominant }.sumOf { it.usd }
        val liqSlowing = previousSide > 0.0 && recentSide <= previousSide * 0.75
        val liqFading = previousSide > 0.0 && recentSide <= previousSide * 0.50

        // A fall in liquidation volume alone is insufficient to declare a reversal.
        // M1 candles are used only if the chart currently has fresh, matching data.
        val priceSlowing: Boolean? = if (currentSymbol == symbol && currentTf == "1") {
            val bars = allCandles.filter { it.time >= now - 420_000L &&
                it.time + 60_000L <= now }.takeLast(6)
            if (bars.size < 6) null else {
                val olderMove = kotlin.math.abs(bars[2].close - bars[0].open)
                val newerMove = kotlin.math.abs(bars[5].close - bars[3].open)
                olderMove > 0.0 && newerMove <= olderMove * 0.75
            }
        } else null
        val flow = tradeDeltaAnalyzer.cascadeFlowWindows(symbol, now)
        val flowSlowing: Boolean? = flow?.let {
            val oldDirectional = if (longDominant) -it.first else it.first
            val newDirectional = if (longDominant) -it.second else it.second
            oldDirectional > 0.0 && newDirectional <= oldDirectional * 0.75
        }
        val confirmations = listOf(priceSlowing, flowSlowing).count { it == true }
        val weak = liqSlowing && confirmations >= 1
        val consecutive = if (weak) (cascadeWeakChecks[symbol] ?: 0) + 1 else 0
        cascadeWeakChecks[symbol] = consecutive

        val nextStage = when {
            liqFading && confirmations == 2 && consecutive >= 3 -> 3
            liqFading && confirmations >= 1 && consecutive >= 2 -> 2
            weak -> 1
            else -> stage
        }
        if (nextStage > stage) {
            cascadeStages[symbol] = nextStage
            val message = when (nextStage) {
                1 -> "📉 Каскад замедляется: ликвидации и рыночный импульс ослабевают"
                2 -> "📉 Каскад, вероятно, затухает: несколько проверок подтвердили ослабление"
                else -> "✅ Каскад, вероятно, завершён: ликвидации, цена и поток сделок ослабли"
            }
            NotificationHelper.sendRiskAlert(this, symbol, "cascade", message)
        }
        // Reset after a quiet period so a new independent burst can be detected.
        if (recentUsd < 1_000.0 && previousUsd < 1_000.0) {
            cascadeStages.remove(symbol)
            cascadeWeakChecks.remove(symbol)
        }
    }

    // Stateful, relative risk estimates: these are NOT observed liquidation orders.
    private data class TrackedFutureZone(
        var price: Double, val side: String, val leverage: Int,
        var weight: Double, var updatedAt: Long
    )
    private val futureZoneLock = Any()
    private val futureZoneState = HashMap<String, MutableList<TrackedFutureZone>>()
    private val futureZoneLastCandle = HashMap<String, Long>()
    private val futureZoneLastPrice = HashMap<String, Double>()

    private fun estimateFutureLiqZones(
        symbol: String, candles: List<Candle>, oiChangePct: Double
    ): List<FutureLiqZone> = synchronized(futureZoneLock) {
        if (candles.size < 8) return@synchronized emptyList()
        val latest = candles.last()
        val price = latest.close
        if (!price.isFinite() || price <= 0.0) return@synchronized emptyList()
        val now = System.currentTimeMillis()
        val zones = futureZoneState.getOrPut(symbol) { ArrayList() }
        val previousPrice = futureZoneLastPrice[symbol] ?: price
        val lastProcessed = futureZoneLastCandle[symbol] ?: 0L
        val newCandle = latest.time > lastProcessed

        // A touched level loses most of its remaining estimated concentration.
        for (zone in zones) {
            val crossed = if (zone.side == "LONG") {
                kotlin.math.min(previousPrice, price) <= zone.price
            } else {
                kotlin.math.max(previousPrice, price) >= zone.price
            }
            if (crossed) zone.weight *= 0.15
            // Ageing continues independently of chart refresh cadence.
            val hours = ((now - zone.updatedAt).coerceAtLeast(0L) / 3_600_000.0)
            zone.weight *= Math.pow(0.92, hours)
            zone.updatedAt = now
        }
        futureZoneLastPrice[symbol] = price

        // Only add newly estimated exposure once per new candle.
        // Positive OI change is a market-wide proxy, not proof of long/short entries.
        if (newCandle && oiChangePct.isFinite() && oiChangePct > 0.0) {
            val recent = candles.takeLast(24)
            val total = recent.sumOf { (it.volume * it.close).coerceAtLeast(0.0) }
            if (total > 0.0) {
                val growth = (oiChangePct / 100.0).coerceIn(0.0, 0.1)
                val bin = price * 0.002
                for (c in recent.takeLast(8)) {
                    val entry = (c.open + c.close + c.high + c.low) / 4.0
                    if (entry <= 0.0) continue
                    val fraction = (c.volume * c.close).coerceAtLeast(0.0) / total
                    val longShare = if (c.close >= c.open) 0.6 else 0.4
                    for (lev in listOf(10, 25, 50, 100)) {
                        for (side in listOf("LONG", "SHORT")) {
                            val level = entry * (1.0 + if (side == "LONG") -1.0 / lev else 1.0 / lev)
                            if ((side == "LONG" && level >= price) ||
                                (side == "SHORT" && level <= price)) continue
                            val sideShare = if (side == "LONG") longShare else 1.0 - longShare
                            val increment = growth * fraction * sideShare / 4.0
                            if (increment <= 0.0) continue
                            val existing = zones.firstOrNull {
                                it.side == side && kotlin.math.abs(it.price - level) <= bin
                            }
                            if (existing != null) {
                                existing.price = (existing.price * existing.weight + level * increment) /
                                    (existing.weight + increment)
                                existing.weight += increment
                            } else {
                                zones.add(TrackedFutureZone(level, side, lev, increment, now))
                            }
                        }
                    }
                }
            }
        }
        if (newCandle) futureZoneLastCandle[symbol] = latest.time

        // Falling OI is consistent with positions closing: reduce estimated exposure.
        if (newCandle && oiChangePct.isFinite() && oiChangePct < 0.0) {
            val shrink = (1.0 + oiChangePct / 100.0).coerceIn(0.7, 1.0)
            zones.forEach { it.weight *= shrink }
        }
        zones.removeAll { it.weight < 0.00000001 ||
            (it.side == "LONG" && it.price >= price) ||
            (it.side == "SHORT" && it.price <= price) }
        // Bound state size and return up to three strongest estimates per side.
        if (zones.size > 200) {
            val keep = zones.sortedByDescending { it.weight }.take(200).toSet()
            zones.retainAll(keep)
        }
        zones.groupBy { it.side }.flatMap { (_, sideZones) ->
            val maxWeight = sideZones.maxOf { it.weight }.coerceAtLeast(1e-12)
            sideZones.sortedByDescending { it.weight }.take(3).map {
                FutureLiqZone(it.price, it.side, it.leverage,
                    (it.weight / maxWeight).coerceIn(0.0, 1.0))
            }
        }
    }

    private fun computeLiqClusters(symbol: String): List<LiqCluster> {
        val events = synchronized(MarketState.liqLock) {
            MarketState.liqBuffer[symbol]?.toList() ?: emptyList()
        }
        if (events.isEmpty()) {
            return synchronized(MarketState.liqClustersLock) { MarketState.liqClusters[symbol] ?: emptyList() }
        }
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60_000L
        val recent = events.filter { it.time >= cutoff && it.price > 0.0 && it.usd > 0.0 }
        if (recent.isEmpty()) {
            synchronized(MarketState.liqClustersLock) { MarketState.liqClusters[symbol] = emptyList() }
            return emptyList()
        }

        val avgPrice = recent.sumOf { it.price } / recent.size
        val binSize = avgPrice * 0.001
        if (binSize <= 0.0) return emptyList()

        val out = ArrayList<LiqCluster>()
        // Never combine liquidated LONGs with liquidated SHORTs, even when their
        // execution prices overlap. Each direction has its own price clusters.
        for (isLong in listOf(true, false)) {
            val bins = HashMap<Long, DoubleArray>()
            for (e in recent) {
                if (e.isLong != isLong) continue
                val idx = (e.price / binSize).toLong()
                val arr = bins.getOrPut(idx) { doubleArrayOf(e.price, e.price, 0.0) }
                if (e.price < arr[0]) arr[0] = e.price
                if (e.price > arr[1]) arr[1] = e.price
                arr[2] += e.usd
            }

            val merged = ArrayList<DoubleArray>()
            for (arr in bins.values.sortedBy { it[0] }) {
                if (merged.isNotEmpty() && arr[0] <= merged.last()[1] * 1.0015) {
                    val m = merged.last()
                    if (arr[1] > m[1]) m[1] = arr[1]
                    m[2] += arr[2]
                } else {
                    merged.add(doubleArrayOf(arr[0], arr[1], arr[2]))
                }
            }
            for (arr in merged) {
                if (arr[2] > 0.0) out.add(LiqCluster(arr[0], arr[1], arr[2], isLong))
            }
        }
        out.sortByDescending { it.usd }
        synchronized(MarketState.liqClustersLock) {
            MarketState.liqClusters[symbol] = out
        }
        return out
    }


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
                                // Crowd ratios are no longer fetched for signal cards.
                                val oiHist = BybitApi.fetchOiHistory(sym, 9)
                                val fundingInfo = BybitApi.fetchFundingInfo(sym)
                                val funding = fundingInfo?.rate

                                var m15Candles: List<Candle>? = null
                                var oi15Hist: List<Double>? = null
                                var delta15Buckets: List<Double> = emptyList()
                                val macroHours = Prefs.accHours(this)
                                if (macroHours > 0) {
                                    try {
                                        val barsNeeded = ((macroHours * 60) / 15).coerceAtLeast(3)
                                        m15Candles = BybitApi.fetchKlines(sym, "15", barsNeeded + 10).reversed()
                                        oi15Hist = BybitApi.fetchOiHistory(sym, barsNeeded, "15min")
                                        val all5Buckets = tradeDeltaAnalyzer.getDeltaBuckets(sym, (macroHours * 60) / 5)
                                        delta15Buckets = all5Buckets.chunked(3).map { it.sum() }
                                    } catch (_: Exception) {}
                                }
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


                                val oiChange = oiPair?.second ?: 0.0
                                if (oiPair != null) updateOiTrend(sym, oiChange)
                                val oiTrend = computeOiTrend(sym)

                                val res = scanSymbol(
                                    sym, candles, realTurn24,
                                    0.0, 0.0,
                                    forceInclude = (sym in pinnedSet) || (sym in hotSet),
                                    openInterest = oiPair?.first ?: 0.0,
                                    openInterestChange = oiChange,
                                    longRatioChange = 0.0,
                                    oiTrend = oiTrend,
                                    oiHistory = oiHist,
                                    deltaBuckets = tradeDeltaAnalyzer.getDeltaBuckets(sym, (Prefs.accMinutes(this) / 5).coerceAtLeast(3)),
                                    m15Candles = m15Candles,
                                    oi15Hist = oi15Hist,
                                    delta15Buckets = delta15Buckets,
                                    funding = fundingInfo?.rate, nextFundingTime = fundingInfo?.nextTime ?: 0L
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

                    val autoLiqTh = Prefs.liqThreshold(this)
                    for (autoR in scanResults) {
                        val autoLiq = MarketState.liqStatsCache[autoR.symbol]
                        tryAutoTrade(autoR, autoLiq, autoLiqTh)
                    }

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
        if (Prefs.chartType(this) == "volume") {
            val threshold = Prefs.volumeBarUsdt(this)
            volumeAggregator = VolumeBarAggregator(threshold)
            activeVolumeThreshold = threshold
        } else volumeAggregator = null
        tradeSocket = TradeSocket(symbol) { time, price, size, isBuy ->
            val aggregator = volumeAggregator
            if (aggregator != null && Prefs.chartType(this) == "volume" && symbol == currentSymbol) {
                aggregator.addTrade(time, price, size, isBuy)
                val now = System.currentTimeMillis()
                if (now - lastVolumeRender >= 250L) {
                    lastVolumeRender = now
                    Handler(Looper.getMainLooper()).post {
                        if (!isDestroyed && isChartMode && Prefs.chartType(this) == "volume" &&
                            symbol == currentSymbol && volumeAggregator === aggregator) {
                            renderVolumeBars(aggregator)
                        }
                    }
                }
            }
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
        volumeAggregator = null
    }

    private fun renderVolumeBars(aggregator: VolumeBarAggregator) {
        val bars = aggregator.snapshot()
        if (bars.isEmpty()) return
        val candles = bars.map {
            Candle(it.startTime, it.open, it.high, it.low, it.close, it.volumeUsdt)
        }
        val wasAtRight = (offset + visibleCount) >= allCandles.size
        allCandles = candles
        visibleCount = visibleCount.coerceIn(1, candles.size)
        if (wasAtRight) offset = max(0, candles.size - visibleCount)
        else offset = offset.coerceIn(0, max(0, candles.size - visibleCount))
        chartView.setVolumeBars(bars)
        chartView.setData(candles, offset, visibleCount, "candles", Prefs.theme(this))
        chartView.setLiqClusters(computeLiqClusters(currentSymbol))
        chartTitle.text = currentSymbol + " · Объём " + formatVolumeBarThreshold(activeVolumeThreshold) +
            " · Δ% / CVD ↓"
        chartTitle.setTextColor(Prefs.textDim(this))
    }

    private fun formatVolumeBarThreshold(value: Double): String =
        when {
            value >= 1_000_000.0 -> "%.1fM".format(java.util.Locale.US, value / 1_000_000.0)
            value >= 1_000.0 -> "%.0fK".format(java.util.Locale.US, value / 1_000.0)
            else -> "%.0f".format(java.util.Locale.US, value)
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
        deltaBuckets: List<Double> = emptyList(),
        m15Candles: List<Candle>? = null,
        oi15Hist: List<Double>? = null,
        delta15Buckets: List<Double> = emptyList(), funding: Double? = null, nextFundingTime: Long = 0L
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
                "×${"%.1f".format(volRatio)} объём · ${"%+.2f".format(movePct)}% за мин", movePct = movePct))
        } else if (bigTurnover && sharpMove) {
            signals.add(Signal("volume", "🚀", "Резкое движение",
                "${"%+.2f".format(movePct)}% · ${UiUtils.formatMoney(turnover1m)} за мин"))
        }

        var turn1h = 0.0
        val from1h = max(0, lastIdx - 60)
        for (k in from1h until lastIdx) turn1h += candles[k].volume * candles[k].close

        // Shared confirmed-trap detector: no legacy candle-pattern, news or crowd filters.
        // Keep the UI and background scanner on the same signal rules.
        // Flow must correspond to the CLOSED confirmation candle, not scan time.
        val confirmationTime = candles[candles.size - 2].time
        val closedFlow = if (Prefs.signalTf(this) == "1")
            tradeDeltaAnalyzer.closedMinuteFlow(symbol, confirmationTime) else null
        // Independent of the chart's selected volume-bar threshold.
        val turnoverAcceleration = if (Prefs.signalTf(this) == "1")
            tradeDeltaAnalyzer.closedMinuteSpeedRatio(symbol, confirmationTime)
        else null
        val trapCandidate = SignalDetector.detectReversalSignal(
            candles, liq, liqThreshold,
            flowDeltaUsd = closedFlow?.first,
            cvdTrendUsd = closedFlow?.second,
            turnoverAcceleration = turnoverAcceleration,
            adaptiveVolumeRatio = if (Prefs.signalTf(this) == "1")
                tradeDeltaAnalyzer.adaptiveMinuteVolumeRatio(symbol, confirmationTime)
            else null,
            timeframeMinutes = tfMin
        )
        // Candle patterns are independent of liquidation and OI availability.
        val sigResult = synchronized(reversalScoreLock) {
            val saved = frozenReversalScores[symbol]
            when {
                trapCandidate.type.isEmpty() -> trapCandidate
                saved != null && saved.first == trapCandidate.candleTime -> saved.second
                else -> {
                    frozenReversalScores[symbol] = trapCandidate.candleTime to trapCandidate
                    trapCandidate
                }
            }
        }
        // Reference setup based on the actual closed pattern and confirmation candles.
        // Not an executable order or a guaranteed fill at the historical close.
        val patternBar = candles[candles.size - 3]
        val confirmBar = candles[candles.size - 2]
        val referenceEntry = if (sigResult.type.isNotEmpty()) confirmBar.close else 0.0
        val referenceStop = when (sigResult.type) {
            "long" -> min(patternBar.low, confirmBar.low)
            "short" -> max(patternBar.high, confirmBar.high)
            else -> 0.0
        }
        val referenceRisk = if (sigResult.type == "long") referenceEntry - referenceStop
                            else referenceStop - referenceEntry
        val validReference = referenceEntry > 0.0 && referenceStop > 0.0 && referenceRisk > 0.0
        val referenceTarget = if (validReference)
            referenceEntry + (if (sigResult.type == "long") 2.0 else -2.0) * referenceRisk
            else 0.0
        if (signals.isEmpty() && !forceInclude && sigResult.type.isEmpty()) return null

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
    SignalDetector.detectAccumulation(candles, oiHistory, deltaBuckets, Prefs.accMinutes(this))
} else null
        val macroAcc = if (m15Candles != null && m15Candles.isNotEmpty() && oi15Hist != null && oi15Hist.size >= 3 && delta15Buckets.size >= 3) {
            SignalDetector.detectMacroAccumulation(m15Candles, oi15Hist, delta15Buckets, Prefs.accHours(this))
        } else null

        val cvd1h = tradeDeltaAnalyzer.getCvd(symbol, 60)
        val burstThresh = Prefs.deltaBurstThreshold(this)
        val burstsPair = tradeDeltaAnalyzer.getBursts(symbol, 60, burstThresh)
        val burstsUp = burstsPair.first
        val burstsDown = burstsPair.second

        return ScanResult(symbol, curPrice, change15m, turnover24hFromTicker, turn1h,
            longRatio, shortRatio, volRatio, turnover1m, signals,
            openInterest = openInterest, openInterestChange = openInterestChange,
            longRatioChange = longRatioChange, oiTrend = oiTrend,
            signalType = sigResult.type,
            signalStrength = sigResult.strength,
            signalCandleTime = sigResult.candleTime,
            signalPattern = sigResult.pattern,
            signalEntry = if (validReference) referenceEntry else 0.0,
            signalStop = if (validReference) referenceStop else 0.0,
            signalTarget = referenceTarget,
            peakForecast = peak.mid,
            peakLow = peak.low,
            peakHigh = peak.high,
            peakRemainPct = peak.remainPct,
            peakConfident = peak.confident,
            bidWallPrice = 0.0,
            bidWallUsd = 0.0,
            askWallPrice = 0.0,
            askWallUsd = 0.0,
            deltaPct1m = deltaPct1m,
            deltaUsd1m = deltaUsd1m,
            realDeltaUsd = 0.0,
            flowStatus = flowStatus,
            priceAt15mAgo = price15,
            accumulation = accumulation,
            macroAccumulation = macroAcc,
            accumulationLow = accumulation?.priceLow ?: 0.0,
            accumulationHigh = accumulation?.priceHigh ?: 0.0,
            fundingRate = funding ?: 0.0, nextFundingTime = nextFundingTime,
            cvd1h = cvd1h, burstsUp = burstsUp, burstsDown = burstsDown)
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

    private fun makeDirRow(
        text: String,
        dir: String,
        color: Int,
        size: Float,
        bold: Boolean = false,
        padTop: Int = 0
    ): LinearLayout {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.HORIZONTAL
        box.setPadding(0, padTop, 0, 0)
        val tv = TextView(this)
        tv.text = text
        tv.setTextColor(color)
        tv.textSize = size
        if (bold) tv.setTypeface(null, Typeface.BOLD)
        tv.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        box.addView(tv)
        val dv = TextView(this)
        dv.text = dir
        dv.textSize = size
        dv.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        box.addView(dv)
        return box
    }

    private fun isEscalating(peaks: List<CascadePeak>): Boolean {
        if (peaks.size < 2) return false
        for (i in 1 until peaks.size) {
            if (peaks[i].sum <= peaks[i - 1].sum * 1.05) return false
        }
        return true
    }

    private fun peaksString(peaks: List<CascadePeak>): String {
        val esc = isEscalating(peaks)
        val sep = if (esc) " → " else ", "
        val base = peaks.joinToString(sep) { UiUtils.formatMoney(it.sum) }
        return if (esc) "$base 🔥" else base
    }

    // Signal-first card: show the decision, not raw market telemetry.
    private fun makeScanRow(r: ScanResult): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.VERTICAL
        row.setBackgroundColor(Prefs.row(this))
        row.setPadding(24, 20, 24, 20)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(8, 6, 8, 6)
        row.layoutParams = lp

        val pinned = synchronized(MarketState.pinnedLock) { r.symbol in MarketState.pinnedSymbols }
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        val symbol = TextView(this)
        symbol.text = r.symbol
        symbol.textSize = 21f
        symbol.setTypeface(null, Typeface.BOLD)
        symbol.setTextColor(Prefs.text(this))
        symbol.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        header.addView(symbol)
        val copy = TextView(this)
        copy.text = "📋"
        copy.textSize = 20f
        copy.setPadding(12, 0, 0, 0)
        copy.setOnClickListener { copyToClipboard(r) }
        header.addView(copy)
        val pin = TextView(this)
        pin.text = if (pinned) "📍" else "📌"
        pin.textSize = 22f
        pin.setPadding(12, 0, 0, 0)
        pin.setOnClickListener { togglePin(r.symbol) }
        header.addView(pin)
        row.addView(header)

        val hasSignal = r.signalType == "long" || r.signalType == "short"
        val strong = r.signalStrength == "strong"
        val direction = if (r.signalType == "long") "LONG" else "SHORT"
        val status = cardSignalStatus(r)
        val statusColor = when {
            !hasSignal -> Prefs.textDim(this)
            strong -> Color.rgb(30, 120, 60)
            else -> Color.rgb(160, 120, 30)
        }
        val statusLine = TextView(this)
        statusLine.text = status
        statusLine.setTextColor(statusColor)
        statusLine.setTypeface(null, Typeface.BOLD)
        statusLine.textSize = 17f
        statusLine.setPadding(0, 8, 0, 0)
        row.addView(statusLine)

        if (hasSignal && !strong) {
            val reason = TextView(this)
            reason.text = "Свеча закрыта, но силы сигнала недостаточно для входа"
            reason.setTextColor(Prefs.textDim(this))
            reason.textSize = 13f
            row.addView(reason)
        }
        if (cardPositionConflict(r)) {
            val warning = TextView(this)
            warning.text = "⚠️ Сигнал против открытой виртуальной позиции"
            warning.setTextColor(Color.rgb(210, 105, 45))
            warning.setTypeface(null, Typeface.BOLD)
            warning.textSize = 14f
            warning.setPadding(0, 8, 0, 0)
            row.addView(warning)
        }


        if (hasSignal && r.signalPattern.isNotEmpty()) {
            val pattern = TextView(this)
            pattern.text = r.signalPattern
            pattern.setTextColor(Prefs.textDim(this))
            pattern.textSize = 15f
            row.addView(pattern)
        }

        val price = TextView(this)
        price.text = "Цена: "+ "%.4f".format(r.price) + "  ·  15м: " + "%+.2f".format(r.change15m) + "%"
        price.setTextColor(Prefs.text(this))
        price.textSize = 16f
        price.setPadding(0, 8, 0, 0)
        row.addView(price)

        if (hasSignal) {
            val confirmation = TextView(this)
            confirmation.text = "✓ Свеча подтверждения закрыта"
            confirmation.setTextColor(Prefs.textDim(this))
            confirmation.textSize = 14f
            row.addView(confirmation)
        }
        val liquidationLine = cardLiquidations(r.symbol)
        if (liquidationLine.isNotEmpty()) {
            val liquidationView = TextView(this)
            liquidationView.text = liquidationLine
            liquidationView.setTextColor(Prefs.textDim(this))
            liquidationView.textSize = 14f
            liquidationView.setPadding(0, 8, 0, 0)
            row.addView(liquidationView)
        }
        if (hasSignal && cardLevels(r).isNotEmpty()) {
            val levels = TextView(this)
            levels.text = cardLevels(r)
            levels.setTextColor(Prefs.textDim(this))
            levels.textSize = 14f
            levels.setPadding(0, 8, 0, 0)
            row.addView(levels)
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
            val autoBadge = if (open.isAuto) " 🤖" else ""

            val info = TextView(this)
            info.text = "$dir$autoBadge · $${"%.2f".format(open.sizeUsd)} · $sign$${"%.2f".format(pnl)} ($sign${"%.1f".format(pnlPct)}%)"
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

    private fun tryAutoTrade(r: ScanResult, liq: LiqStats?, liqThreshold: Double) {
        if (!Prefs.autoTradingEnabled(this)) return
        if (liq == null) return
        if (liq.total15m < liqThreshold) return
        val enterAtStart = Prefs.autoEnterAtStart(this)
        if (enterAtStart) {
            if (liq.trend != "rising") return
        } else {
            if (liq.trend == "rising") return
        }
        if (r.price <= 0) return
        if (PaperTrading.hasOpenFor(r.symbol)) return

        val autoWith = Prefs.autoDirectionWith(this)
        val isLong: Boolean = when (r.signalType) {
            "long" -> true
            "short" -> false
            else -> if (autoWith) liq.dominantIsLong else !liq.dominantIsLong
        }

        val baseCtx = buildContextSnapshot(r)
        val ctx = if (r.signalType.isEmpty()) {
            baseCtx.copy(signalPattern = if (autoWith) "AUTO_WITH" else "AUTO_AGAINST")
        } else baseCtx

        val size = Prefs.paperTradeSize(this)
        PaperTrading.openTrade(
            this, r.symbol, isLong, r.price, size,
            ctx, isAuto = true
        )
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
        activeChartType = Prefs.chartType(this)
        if (activeChartType == "volume") {
            chartLoading = false
            allCandles = emptyList()
            offset = 0
            visibleCount = 100
            chartView.setVolumeBars(emptyList())
            chartView.setData(emptyList(), 0, visibleCount, "candles", Prefs.theme(this))
            chartTitle.text = symbol + " · Объёмные (накопление сделок...)"
            startSocket(symbol)
            return
        }

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
        if (!isChartMode || Prefs.chartType(this) == "volume") return
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
                    if (isDestroyed || Prefs.chartType(this) == "volume") return@post
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
                    chartView.setLiqClusters(computeLiqClusters(currentSymbol))
                        val acc2 = scanResults.firstOrNull { it.symbol == currentSymbol }?.accumulation
                        chartView.setAccumulationZone(acc2?.priceLow ?: 0.0, acc2?.priceHigh ?: 0.0)
        val acc = scanResults.firstOrNull { it.symbol == currentSymbol }?.accumulation
        chartView.setAccumulationZone(acc?.priceLow ?: 0.0, acc?.priceHigh ?: 0.0)
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