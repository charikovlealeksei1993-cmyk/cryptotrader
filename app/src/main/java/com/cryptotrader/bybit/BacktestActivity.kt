package com.cryptotrader.bybit

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

class BacktestActivity : Activity() {

    private lateinit var root: LinearLayout
    private lateinit var symbolContainer: LinearLayout
    private lateinit var tfButtons: MutableList<Pair<String, Button>>
    private lateinit var daysButtons: MutableList<Pair<Int, Button>>
    private lateinit var patternButton: Button
    private lateinit var runButton: Button
    private lateinit var progressText: TextView
    private lateinit var resultContainer: LinearLayout

    private var selectedSymbol: String = "BTCUSDT"
    private var selectedTf: String = "1"
    private var selectedDays: Int = 7
    private var selectedPattern: String? = null  // null = все паттерны

    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    private var isRunning = false
    private var lastReport: BacktestReport? = null

    private val allPatterns = listOf(
        // Одиночные
        "Shooting Star", "Hammer", "Inverted Hammer", "Hanging Man",
        "Dragonfly Doji", "Gravestone Doji",
        // Двойные
        "Bullish Engulfing", "Bearish Engulfing", "Piercing Line", "Dark Cloud Cover",
        "Tweezer Bottom", "Tweezer Top", "Bullish Harami", "Bearish Harami",
        // Тройные
        "Morning Star", "Evening Star", "Three White Soldiers", "Three Black Crows",
        "Three Inside Up", "Three Inside Down", "Three Outside Up", "Three Outside Down"
    )

    private fun safe(tag: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            android.util.Log.e("Backtest", "CRASH in $tag: ${e.message}", e)
            try {
                Toast.makeText(this, "Ошибка [$tag]: ${e.message?.take(100)}", Toast.LENGTH_LONG).show()
            } catch (_: Exception) {}
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        safe("init") {
            selectedTf = Prefs.btTf(this)
            selectedDays = Prefs.btDays(this)
            selectedSymbol = Prefs.btSymbol(this)
        }
        safe("buildUi") { buildUi() }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { executor.shutdownNow() } catch (_: Exception) {}
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Prefs.bg(this))

        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(24, 24, 24, 24)
        root.setBackgroundColor(Prefs.bg(this))
        scroll.addView(root)

        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val top: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                top = bars.top
                bottom = bars.bottom
            } else {
                @Suppress("DEPRECATION") top = insets.systemWindowInsetTop
                @Suppress("DEPRECATION") bottom = insets.systemWindowInsetBottom
            }
            v.setPadding(0, top, 0, bottom)
            insets
        }

        val headerRow = LinearLayout(this)
        headerRow.orientation = LinearLayout.HORIZONTAL
        headerRow.gravity = Gravity.CENTER_VERTICAL
        headerRow.setPadding(0, 0, 0, 16)

        val title = TextView(this)
        title.text = "🔬 Backtest"
        title.textSize = 24f
        title.setTypeface(null, Typeface.BOLD)
        title.setTextColor(Prefs.text(this))
        title.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        headerRow.addView(title)

        val backBtn = Button(this)
        backBtn.text = "✕"
        backBtn.textSize = 18f
        backBtn.isAllCaps = false
        backBtn.setTextColor(Prefs.text(this))
        backBtn.background = pressedDrawable(Prefs.buttonOff(this), Prefs.buttonPressed(this))
        backBtn.setOnClickListener { finish() }
        headerRow.addView(backBtn)

        root.addView(headerRow)

        val hint = TextView(this)
        hint.text = "Прогон истории свечей. 22 паттерна: одиночные / двойные / тройные.\n💥 Псевдо-каскад: 2+ свечи с |move| > 0.4% и объёмом ×2.5.\n🎯 SL 2% / TP 5% / timeout 15 баров."
        hint.textSize = 12f
        hint.setTextColor(Prefs.textDim(this))
        hint.setPadding(0, 0, 0, 16)
        root.addView(hint)

        addLabel("Символ:")
        symbolContainer = LinearLayout(this)
        symbolContainer.orientation = LinearLayout.VERTICAL
        root.addView(symbolContainer)
        safe("symbols") { rebuildSymbolButtons() }

        addLabel("Таймфрейм:")
        val tfRow = LinearLayout(this)
        tfRow.orientation = LinearLayout.HORIZONTAL
        val tfLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        tfLp.setMargins(0, 0, 0, 12)
        tfRow.layoutParams = tfLp
        tfButtons = ArrayList()
        listOf("1" to "1м", "5" to "5м", "15" to "15м").forEach { (tf, label) ->
            val b = Button(this)
            b.text = label
            b.textSize = 14f
            b.isAllCaps = false
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(2, 0, 2, 0)
            b.layoutParams = lp
            b.setOnClickListener {
                selectedTf = tf
                safe("setTf") { Prefs.setBtTf(this, tf) }
                safe("upd") { updateButtonSelection() }
            }
            tfRow.addView(b)
            tfButtons.add(tf to b)
        }
        root.addView(tfRow)

        addLabel("Период:")
        val daysRow = LinearLayout(this)
        daysRow.orientation = LinearLayout.HORIZONTAL
        val daysLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        daysLp.setMargins(0, 0, 0, 12)
        daysRow.layoutParams = daysLp
        daysButtons = ArrayList()
        listOf(1, 3, 7, 14).forEach { d ->
            val b = Button(this)
            b.text = "${d}д"
            b.textSize = 14f
            b.isAllCaps = false
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(2, 0, 2, 0)
            b.layoutParams = lp
            b.setOnClickListener {
                selectedDays = d
                safe("setDays") { Prefs.setBtDays(this, d) }
                safe("upd") { updateButtonSelection() }
            }
            daysRow.addView(b)
            daysButtons.add(d to b)
        }
        root.addView(daysRow)

        addLabel("Паттерн:")
        patternButton = Button(this)
        patternButton.textSize = 14f
        patternButton.isAllCaps = false
        patternButton.setTextColor(Prefs.text(this))
        patternButton.background = pressedDrawable(Prefs.buttonOff(this), Prefs.buttonPressed(this))
        patternButton.setPadding(16, 12, 16, 12)
        val pLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        pLp.setMargins(0, 0, 0, 12)
        patternButton.layoutParams = pLp
        patternButton.setOnClickListener { showPatternPicker() }
        root.addView(patternButton)
        updatePatternButton()

        runButton = Button(this)
        runButton.text = "🚀 Запустить backtest"
        runButton.textSize = 16f
        runButton.isAllCaps = false
        runButton.setTextColor(Color.WHITE)
        runButton.background = pressedDrawable(Color.rgb(60, 90, 140), Color.rgb(85, 120, 175))
        runButton.setPadding(16, 14, 16, 14)
        val rbLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        rbLp.setMargins(0, 8, 0, 12)
        runButton.layoutParams = rbLp
        runButton.setOnClickListener { safe("run") { runBacktest() } }
        root.addView(runButton)

        progressText = TextView(this)
        progressText.textSize = 13f
        progressText.setTextColor(Prefs.textDim(this))
        progressText.setPadding(0, 0, 0, 16)
        progressText.visibility = View.GONE
        root.addView(progressText)

        resultContainer = LinearLayout(this)
        resultContainer.orientation = LinearLayout.VERTICAL
        root.addView(resultContainer)

        setContentView(scroll)
        safe("upd") { updateButtonSelection() }
    }

    private fun addLabel(text: String) {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 14f
        tv.setTypeface(null, Typeface.BOLD)
        tv.setTextColor(Prefs.text(this))
        tv.setPadding(0, 8, 0, 6)
        root.addView(tv)
    }

    private fun showPatternPicker() {
        val items = ArrayList<String>()
        items.add("✓ Все паттерны (22)")
        items.add("── Одиночные ──")
        listOf("Shooting Star", "Hammer", "Inverted Hammer", "Hanging Man",
            "Dragonfly Doji", "Gravestone Doji").forEach { items.add("   $it") }
        items.add("── Двойные ──")
        listOf("Bullish Engulfing", "Bearish Engulfing", "Piercing Line", "Dark Cloud Cover",
            "Tweezer Bottom", "Tweezer Top", "Bullish Harami", "Bearish Harami").forEach { items.add("   $it") }
        items.add("── Тройные ──")
        listOf("Morning Star", "Evening Star", "Three White Soldiers", "Three Black Crows",
            "Three Inside Up", "Three Inside Down", "Three Outside Up", "Three Outside Down").forEach { items.add("   $it") }

        val builder = AlertDialog.Builder(this)
        builder.setTitle("Какой паттерн тестировать?")

        val currentIdx = if (selectedPattern == null) 0 else {
            val idx = items.indexOfFirst { it.trim() == selectedPattern }
            if (idx >= 0) idx else 0
        }

        builder.setSingleChoiceItems(items.toTypedArray(), currentIdx) { dialog, which ->
            val item = items[which]
            selectedPattern = when {
                item.startsWith("✓ Все") -> null
                item.startsWith("──") -> null  // заголовки не выбираем, но пропускаем
                else -> item.trim()
            }
            dialog.dismiss()
            updatePatternButton()
        }
        builder.setNegativeButton("Отмена", null)
        builder.show()
    }

    private fun updatePatternButton() {
        val label = if (selectedPattern.isNullOrEmpty()) "Все паттерны (22)" else selectedPattern
        patternButton.text = "🎯 $label  ▼"
    }

    private fun rebuildSymbolButtons() {
        symbolContainer.removeAllViews()

        val current = try { MainActivity.sharedScanResults } catch (_: Exception) { emptyList() }
        val top = if (current.isNotEmpty()) current.take(12).map { it.symbol } else
            listOf("BTCUSDT", "ETHUSDT", "SOLUSDT", "BNBUSDT", "XRPUSDT", "DOGEUSDT")

        if (selectedSymbol !in top) {
            selectedSymbol = top.firstOrNull() ?: "BTCUSDT"
            try { Prefs.setBtSymbol(this, selectedSymbol) } catch (_: Exception) {}
        }

        var row: LinearLayout? = null
        top.forEachIndexed { idx, sym ->
            if (idx % 3 == 0) {
                row = LinearLayout(this)
                row!!.orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.setMargins(0, 0, 0, 4)
                row!!.layoutParams = lp
                symbolContainer.addView(row)
            }
            val b = Button(this)
            b.text = sym.replace("USDT", "")
            b.textSize = 12f
            b.isAllCaps = false
            b.tag = sym
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(2, 0, 2, 0)
            b.layoutParams = lp
            b.setOnClickListener {
                selectedSymbol = sym
                safe("setSym") { Prefs.setBtSymbol(this, sym) }
                safe("upd") { updateButtonSelection() }
            }
            row!!.addView(b)
        }
    }

    private fun updateButtonSelection() {
        try {
            tfButtons.forEach { (tf, b) ->
                if (tf == selectedTf) {
                    b.background = pressedDrawable(Color.rgb(60, 90, 140), Color.rgb(85, 120, 175))
                    b.setTextColor(Color.WHITE)
                } else {
                    b.background = pressedDrawable(Prefs.buttonOff(this), Prefs.buttonPressed(this))
                    b.setTextColor(Prefs.textDim(this))
                }
            }
            daysButtons.forEach { (d, b) ->
                if (d == selectedDays) {
                    b.background = pressedDrawable(Color.rgb(60, 90, 140), Color.rgb(85, 120, 175))
                    b.setTextColor(Color.WHITE)
                } else {
                    b.background = pressedDrawable(Prefs.buttonOff(this), Prefs.buttonPressed(this))
                    b.setTextColor(Prefs.textDim(this))
                }
            }
            for (i in 0 until symbolContainer.childCount) {
                val r = symbolContainer.getChildAt(i) as? LinearLayout ?: continue
                for (j in 0 until r.childCount) {
                    val b = r.getChildAt(j) as? Button ?: continue
                    val sym = b.tag as? String ?: continue
                    if (sym == selectedSymbol) {
                        b.background = pressedDrawable(Color.rgb(60, 90, 140), Color.rgb(85, 120, 175))
                        b.setTextColor(Color.WHITE)
                    } else {
                        b.background = pressedDrawable(Prefs.buttonOff(this), Prefs.buttonPressed(this))
                        b.setTextColor(Prefs.textDim(this))
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("Backtest", "updateButtonSelection: ${e.message}", e)
        }
    }

    private fun runBacktest() {
        if (isRunning) return
        isRunning = true
        runButton.isEnabled = false
        runButton.text = "⏳ Работаю..."
        progressText.visibility = View.VISIBLE
        progressText.text = "Запускаю..."
        resultContainer.removeAllViews()
        lastReport = null

        val sym = selectedSymbol
        val tf = selectedTf
        val days = selectedDays
        val pattern = selectedPattern

        executor.execute {
            try {
                val engine = BacktestEngine { msg ->
                    handler.post { safe("progress") { progressText.text = msg } }
                }
                val report = engine.run(sym, tf, days, pattern)
                handler.post {
                    isRunning = false
                    runButton.isEnabled = true
                    runButton.text = "🚀 Запустить backtest"
                    progressText.visibility = View.GONE
                    safe("render") { renderReport(report) }
                }
            } catch (e: Throwable) {
                android.util.Log.e("Backtest", "runBacktest: ${e.message}", e)
                handler.post {
                    isRunning = false
                    runButton.isEnabled = true
                    runButton.text = "🚀 Запустить backtest"
                    progressText.visibility = View.GONE
                    Toast.makeText(this, "Ошибка: ${e.message?.take(100)}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    private fun renderReport(report: BacktestReport) {
        lastReport = report
        resultContainer.removeAllViews()

        if (report.errorMessage != null) {
            val tv = TextView(this)
            tv.text = "❌ ${report.errorMessage}"
            tv.textSize = 14f
            tv.setTextColor(Color.rgb(220, 120, 120))
            tv.setPadding(0, 16, 0, 0)
            resultContainer.addView(tv)
            return
        }

        val head = TextView(this)
        val patternLabel = if (selectedPattern.isNullOrEmpty()) "Все паттерны" else selectedPattern
        head.text = "${report.symbol} · ${tfLabel(report.tf)} · ${report.days}д · ${report.candlesCount} свечей · $patternLabel"
        head.textSize = 15f
        head.setTypeface(null, Typeface.BOLD)
        head.setTextColor(Prefs.text(this))
        head.setPadding(0, 8, 0, 8)
        resultContainer.addView(head)

        if (report.signals.isEmpty()) {
            val tv = TextView(this)
            tv.text = "Сигналов не найдено"
            tv.textSize = 14f
            tv.setTextColor(Prefs.textDim(this))
            resultContainer.addView(tv)
            return
        }

        val summary = LinearLayout(this)
        summary.orientation = LinearLayout.VERTICAL
        summary.setBackgroundColor(Prefs.row(this))
        summary.setPadding(14, 10, 14, 10)
        val sumLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        sumLp.setMargins(0, 0, 0, 12)
        summary.layoutParams = sumLp

        addStatLine(summary, "Всего сигналов", "${report.signals.size}", Prefs.text(this))
        addStatLine(summary, "С псевдо-каскадом", "${report.withCascadeCount}", Prefs.text(this))
        addStatLine(summary, "STRONG / WEAK", "${report.strongCount} / ${report.weakCount}", Prefs.text(this))
        addStatLine(summary, "LONG / SHORT", "${report.longCount} / ${report.shortCount}", Prefs.text(this))
        resultContainer.addView(summary)

        // SL/TP блок
        val sltp = report.statsWithSlTp()
        if (sltp.total > 0) {
            resultContainer.addView(sectionHeader("🎯 С симуляцией SL 2% / TP 5% (max 15 баров)"))
            val box = LinearLayout(this)
            box.orientation = LinearLayout.VERTICAL
            box.setBackgroundColor(Prefs.row(this))
            box.setPadding(14, 10, 14, 10)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, 0, 8)
            box.layoutParams = lp

            val winColor = if (sltp.winRate >= 50) Color.rgb(120, 200, 140) else Color.rgb(220, 120, 120)
            val pnlColor = if (sltp.totalPnl100 >= 0) Color.rgb(120, 200, 140) else Color.rgb(220, 120, 120)
            val pnlSign = if (sltp.totalPnl100 >= 0) "+" else ""
            val avgSign = if (sltp.avgPnl >= 0) "+" else ""

            addStatLine(box, "Сигналов", "${sltp.total}", Prefs.text(this))
            addStatLine(box, "Winrate", "${"%.1f".format(sltp.winRate)}%  (W ${sltp.wins} / L ${sltp.losses} / B ${sltp.breakeven})", winColor)
            addStatLine(box, "TP / SL / timeout", "${sltp.tpCount} / ${sltp.slCount} / ${sltp.timeoutCount}", Prefs.textDim(this))
            addStatLine(box, "Средний P&L", "$avgSign${"%.2f".format(kotlin.math.abs(sltp.avgPnl))}%", pnlColor)
            addStatLine(box, "Итог (если $100 на каждый)", "$pnlSign$${"%.2f".format(sltp.totalPnl100)}", pnlColor)
            addStatLine(box, "Profit factor", "%.2f".format(sltp.profitFactor),
                if (sltp.profitFactor >= 1.5) Color.rgb(120, 200, 140) else Prefs.textDim(this))
            addStatLine(box, "Max drawdown", "-${"%.1f".format(sltp.maxDrawdown)}%", Color.rgb(220, 120, 120))
            resultContainer.addView(box)
        }

        // Разбивка по паттернам (15м)
        val patternStats = report.statsByPattern("15m")
        if (patternStats.size > 1) {
            resultContainer.addView(sectionHeader("📊 ПО ПАТТЕРНАМ (15м)"))
            for (ps in patternStats) {
                val box = LinearLayout(this)
                box.orientation = LinearLayout.VERTICAL
                box.setBackgroundColor(Prefs.row(this))
                box.setPadding(14, 8, 14, 8)
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.setMargins(0, 0, 0, 6)
                box.layoutParams = lp

                val hr = LinearLayout(this)
                hr.orientation = LinearLayout.HORIZONTAL
                val l = TextView(this)
                l.text = ps.pattern
                l.textSize = 13f
                l.setTypeface(null, Typeface.BOLD)
                l.setTextColor(Prefs.text(this))
                l.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                hr.addView(l)
                val wr = TextView(this)
                wr.text = "${"%.0f".format(ps.winRate)}%  (${ps.wins}W/${ps.losses}L)"
                wr.textSize = 13f
                wr.setTypeface(null, Typeface.BOLD)
                wr.setTextColor(if (ps.winRate >= 50) Color.rgb(120, 200, 140) else Color.rgb(220, 120, 120))
                hr.addView(wr)
                box.addView(hr)

                val pnlColor = if (ps.totalPnl100 >= 0) Color.rgb(120, 200, 140) else Color.rgb(220, 120, 120)
                val pnlSign = if (ps.totalPnl100 >= 0) "+" else ""
                val info = TextView(this)
                info.text = "Сигналов: ${ps.total} · Итог: $pnlSign$${"%.2f".format(ps.totalPnl100)}"
                info.textSize = 11f
                info.setTextColor(pnlColor)
                info.setPadding(0, 3, 0, 0)
                box.addView(info)

                resultContainer.addView(box)
            }
        }

        // Псевдо-каскад
        val cascadeStats = report.statsForFiltered("15m") { it.hasPseudoCascade }
        if (cascadeStats.total > 0 && report.withCascadeCount != report.signals.size) {
            resultContainer.addView(sectionHeader("💥 Только с псевдо-каскадом (15м)"))
            resultContainer.addView(makeHorizonBlock("Cascade · 15м", cascadeStats))
        }

        // По горизонтам
        resultContainer.addView(sectionHeader("📊 Результат по горизонтам (без SL/TP)"))
        listOf("15m" to "15 минут", "1h" to "1 час", "4h" to "4 часа").forEach { (h, label) ->
            val st = report.statsFor(h)
            if (st.total > 0) {
                resultContainer.addView(makeHorizonBlock(label, st))
            }
        }

        // STRONG / LONG / SHORT
        val strongStats15 = report.statsForFiltered("15m") { it.strength == "strong" }
        if (strongStats15.total > 0 && report.strongCount != report.signals.size) {
            resultContainer.addView(sectionHeader("⭐ Только STRONG (15м)"))
            resultContainer.addView(makeHorizonBlock("STRONG · 15м", strongStats15))
        }

        val longStats15 = report.statsForFiltered("15m") { it.isLong }
        if (longStats15.total > 0) {
            resultContainer.addView(sectionHeader("🟢 Только LONG (15м)"))
            resultContainer.addView(makeHorizonBlock("LONG · 15м", longStats15))
        }

        val shortStats15 = report.statsForFiltered("15m") { !it.isLong }
        if (shortStats15.total > 0) {
            resultContainer.addView(sectionHeader("🔴 Только SHORT (15м)"))
            resultContainer.addView(makeHorizonBlock("SHORT · 15м", shortStats15))
        }

        // Последние сигналы
        resultContainer.addView(sectionHeader("📜 Последние сигналы"))
        val last = report.signals.takeLast(30).reversed()
        val df = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
        last.forEach { sig ->
            resultContainer.addView(makeSignalRow(sig, df))
        }

        val copyBtn = Button(this)
        copyBtn.text = "📋 Скопировать отчёт"
        copyBtn.textSize = 15f
        copyBtn.isAllCaps = false
        copyBtn.setTextColor(Color.WHITE)
        copyBtn.background = pressedDrawable(Color.rgb(40, 100, 60), Color.rgb(55, 130, 80))
        copyBtn.setPadding(16, 12, 16, 12)
        val cbLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        cbLp.setMargins(0, 16, 0, 0)
        copyBtn.layoutParams = cbLp
        copyBtn.setOnClickListener { safe("copy") { copyReport() } }
        resultContainer.addView(copyBtn)
    }

    private fun copyReport() {
        val report = lastReport ?: return
        val sb = StringBuilder()

        sb.appendLine("=== BACKTEST REPORT ===")
        val patternLabel = if (selectedPattern.isNullOrEmpty()) "Все паттерны" else selectedPattern
        sb.appendLine("${report.symbol} · TF ${report.tf} · ${report.days}д · ${report.candlesCount} свечей")
        sb.appendLine("Паттерн: $patternLabel")
        sb.appendLine()

        sb.appendLine("--- СВОДКА ---")
        sb.appendLine("Всего сигналов: ${report.signals.size}")
        sb.appendLine("С псевдо-каскадом: ${report.withCascadeCount}")
        sb.appendLine("STRONG / WEAK: ${report.strongCount} / ${report.weakCount}")
        sb.appendLine("LONG / SHORT: ${report.longCount} / ${report.shortCount}")
        sb.appendLine()

        val sltp = report.statsWithSlTp()
        if (sltp.total > 0) {
            sb.appendLine("--- SL 2% / TP 5% / max 15 баров ---")
            sb.appendLine("Сигналов: ${sltp.total}")
            sb.appendLine("Winrate: ${"%.1f".format(sltp.winRate)}%  (W ${sltp.wins} / L ${sltp.losses} / B ${sltp.breakeven})")
            sb.appendLine("TP / SL / timeout: ${sltp.tpCount} / ${sltp.slCount} / ${sltp.timeoutCount}")
            sb.appendLine("Средний P&L: ${if (sltp.avgPnl >= 0) "+" else ""}${"%.2f".format(sltp.avgPnl)}%")
            sb.appendLine("Итог (если \$100 на каждый): ${if (sltp.totalPnl100 >= 0) "+" else ""}\$${"%.2f".format(sltp.totalPnl100)}")
            sb.appendLine("Profit factor: ${"%.2f".format(sltp.profitFactor)}")
            sb.appendLine("Max drawdown: -${"%.1f".format(sltp.maxDrawdown)}%")
            sb.appendLine()
        }

        val patternStats = report.statsByPattern("15m")
        if (patternStats.size > 1) {
            sb.appendLine("--- ПО ПАТТЕРНАМ (15м) ---")
            for (ps in patternStats) {
                sb.appendLine("${ps.pattern}: ${ps.total} сигн · ${"%.1f".format(ps.winRate)}% (${ps.wins}W/${ps.losses}L) · ${if (ps.totalPnl100 >= 0) "+" else ""}\$${"%.2f".format(ps.totalPnl100)}")
            }
            sb.appendLine()
        }

        val cascadeStats = report.statsForFiltered("15m") { it.hasPseudoCascade }
        if (cascadeStats.total > 0 && report.withCascadeCount != report.signals.size) {
            sb.appendLine("--- С ПСЕВДО-КАСКАДОМ (15м) ---")
            sb.appendLine("Сигналов: ${cascadeStats.total} · winrate ${"%.1f".format(cascadeStats.winRate)}% · avg ${if (cascadeStats.avgPnl >= 0) "+" else ""}${"%.2f".format(cascadeStats.avgPnl)}%")
            sb.appendLine()
        }

        sb.appendLine("--- ПО ГОРИЗОНТАМ ---")
        listOf("15m", "1h", "4h").forEach { h ->
            val st = report.statsFor(h)
            if (st.total > 0) {
                sb.appendLine("[$h] ${st.total} сигн · winrate ${"%.1f".format(st.winRate)}% · avg ${if (st.avgPnl >= 0) "+" else ""}${"%.2f".format(st.avgPnl)}%")
            }
        }

        try {
            val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Backtest", sb.toString().trim()))
            Toast.makeText(this, "Отчёт скопирован", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось скопировать: ${e.message?.take(50)}", Toast.LENGTH_LONG).show()
        }
    }

    private fun sectionHeader(text: String): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 16f
        tv.setTypeface(null, Typeface.BOLD)
        tv.setTextColor(Prefs.text(this))
        tv.setPadding(0, 16, 0, 8)
        return tv
    }

    private fun makeHorizonBlock(label: String, st: BacktestReport.HorizonStats): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setBackgroundColor(Prefs.row(this))
        box.setPadding(14, 10, 14, 10)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, 0, 0, 8)
        box.layoutParams = lp

        val winColor = if (st.winRate >= 50) Color.rgb(120, 200, 140) else Color.rgb(220, 120, 120)
        val pnlColor = if (st.totalPnl100 >= 0) Color.rgb(120, 200, 140) else Color.rgb(220, 120, 120)
        val sign = if (st.totalPnl100 >= 0) "+" else ""

        addStatLine(box, "Сигналов", "${st.total}", Prefs.text(this))
        addStatLine(box, "Winrate", "${"%.1f".format(st.winRate)}%  (W ${st.wins} / L ${st.losses} / B ${st.breakeven})", winColor)
        addStatLine(box, "Средний P&L", "${signOrMinus(st.avgPnl)}${"%.2f".format(abs(st.avgPnl))}%", pnlColor)
        addStatLine(box, "Если \$100 на каждый", "$sign\$${"%.2f".format(st.totalPnl100)}", pnlColor)
        return box
    }

    private fun signOrMinus(v: Double): String = if (v >= 0) "+" else "-"

    private fun makeSignalRow(sig: BacktestSignal, df: SimpleDateFormat): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.VERTICAL
        row.setBackgroundColor(Prefs.row(this))
        row.setPadding(12, 8, 12, 8)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, 0, 0, 4)
        row.layoutParams = lp

        val headerRow = LinearLayout(this)
        headerRow.orientation = LinearLayout.HORIZONTAL

        val dirIcon = if (sig.isLong) "🟢" else "🔴"
        val strengthIcon = if (sig.strength == "strong") "⭐" else "·"
        val cascadeIcon = if (sig.hasPseudoCascade) "💥" else ""
        val timeStr = df.format(Date(sig.time))

        val l = TextView(this)
        l.text = "$dirIcon $strengthIcon $cascadeIcon ${sig.pattern.ifEmpty { sig.type.uppercase() }}  $timeStr"
        l.textSize = 12f
        l.setTypeface(null, Typeface.BOLD)
        l.setTextColor(Prefs.text(this))
        l.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        headerRow.addView(l)

        val pnlText = buildString {
            sig.pnl15m?.let { append("15м ${fmtPnl(it)}  ") }
            sig.pnl1h?.let { append("1ч ${fmtPnl(it)}") }
        }.trim()

        val pnl = TextView(this)
        pnl.text = pnlText
        pnl.textSize = 11f
        pnl.setTextColor(
            when {
                (sig.pnl15m ?: 0.0) > 0.05 -> Color.rgb(120, 200, 140)
                (sig.pnl15m ?: 0.0) < -0.05 -> Color.rgb(220, 120, 120)
                else -> Prefs.textDim(this)
            }
        )
        headerRow.addView(pnl)

        row.addView(headerRow)
        return row
    }

    private fun fmtPnl(v: Double): String {
        val sign = if (v >= 0) "+" else ""
        return "$sign${"%.2f".format(v)}%"
    }

    private fun addStatLine(parent: LinearLayout, label: String, value: String, color: Int) {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.setPadding(0, 3, 0, 3)
        val l = TextView(this)
        l.text = label
        l.textSize = 12f
        l.setTextColor(Prefs.textDim(this))
        l.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        r.addView(l)
        val v = TextView(this)
        v.text = value
        v.textSize = 12f
        v.setTypeface(null, Typeface.BOLD)
        v.setTextColor(color)
        r.addView(v)
        parent.addView(r)
    }

    private fun tfLabel(tf: String): String = when (tf) {
        "1" -> "1м"
        "5" -> "5м"
        "15" -> "15м"
        else -> tf
    }

    private fun pressedDrawable(normal: Int, pressed: Int): StateListDrawable {
        val normalD = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 20f
            setColor(normal)
        }
        val pressedD = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 20f
            setColor(pressed)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressedD)
            addState(intArrayOf(), normalD)
        }
    }
}