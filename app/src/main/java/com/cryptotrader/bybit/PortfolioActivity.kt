package com.cryptotrader.bybit

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

class PortfolioActivity : Activity() {

    private lateinit var root: LinearLayout
    private lateinit var balanceLabel: TextView
    private lateinit var contentContainer: LinearLayout

    private fun safe(tag: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            android.util.Log.e("Portfolio", "CRASH in $tag: ${e.message}", e)
            try {
                Toast.makeText(this, "Ошибка: ${e.message?.take(80)}", Toast.LENGTH_LONG).show()
            } catch (_: Exception) {}
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        safe("load") { PaperTrading.load(this) }
        safe("buildUi") { buildUi() }
    }

    override fun onResume() {
        super.onResume()
        safe("reload") { PaperTrading.reload(this) }
        safe("refresh") { refresh() }
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
                @Suppress("DEPRECATION")
                top = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                bottom = insets.systemWindowInsetBottom
            }
            v.setPadding(0, top, 0, bottom)
            insets
        }

        val headerRow = LinearLayout(this)
        headerRow.orientation = LinearLayout.HORIZONTAL
        headerRow.gravity = Gravity.CENTER_VERTICAL
        headerRow.setPadding(0, 0, 0, 16)

        val title = TextView(this)
        title.text = "💰 Портфель"
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

        balanceLabel = TextView(this)
        balanceLabel.textSize = 22f
        balanceLabel.setTypeface(null, Typeface.BOLD)
        balanceLabel.setPadding(0, 0, 0, 16)
        root.addView(balanceLabel)

        val btnRow = LinearLayout(this)
        btnRow.orientation = LinearLayout.HORIZONTAL
        val btnRowLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        btnRowLp.setMargins(0, 0, 0, 12)
        btnRow.layoutParams = btnRowLp

        val closeAllBtn = Button(this)
        closeAllBtn.text = "❌ Закрыть все"
        closeAllBtn.textSize = 14f
        closeAllBtn.isAllCaps = false
        closeAllBtn.setTextColor(Color.WHITE)
        closeAllBtn.background = pressedDrawable(Color.rgb(140, 50, 50), Color.rgb(180, 70, 70))
        val caLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        caLp.setMargins(0, 0, 4, 0)
        closeAllBtn.layoutParams = caLp
        closeAllBtn.setOnClickListener { safe("closeAll") { closeAll() } }
        btnRow.addView(closeAllBtn)

        val resetBtn = Button(this)
        resetBtn.text = "🔄 Сброс"
        resetBtn.textSize = 14f
        resetBtn.isAllCaps = false
        resetBtn.setTextColor(Color.WHITE)
        resetBtn.background = pressedDrawable(Color.rgb(60, 60, 80), Color.rgb(90, 90, 110))
        val rsLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        rsLp.setMargins(4, 0, 0, 0)
        resetBtn.layoutParams = rsLp
        resetBtn.setOnClickListener { safe("resetAll") { resetAll() } }
        btnRow.addView(resetBtn)

        root.addView(btnRow)

        val copyBtn = Button(this)
        copyBtn.text = "📋 Скопировать все сделки"
        copyBtn.textSize = 14f
        copyBtn.isAllCaps = false
        copyBtn.setTextColor(Color.WHITE)
        copyBtn.background = pressedDrawable(Color.rgb(40, 100, 60), Color.rgb(55, 130, 80))
        val cLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        cLp.setMargins(0, 0, 0, 16)
        copyBtn.layoutParams = cLp
        copyBtn.setOnClickListener { safe("copy") { copyTradesToClipboard() } }
        root.addView(copyBtn)

        contentContainer = LinearLayout(this)
        contentContainer.orientation = LinearLayout.VERTICAL
        root.addView(contentContainer)

        setContentView(scroll)
    }

    private fun refresh() {
        val balance = PaperTrading.balance()
        val open = PaperTrading.openTrades()
        val stats = PaperTrading.computeStats()

        val totalUnrealized = open.sumOf { t ->
            val price = currentPriceOf(t.symbol)
            t.unrealizedPnl(price)
        }

        val pnlColor = if (totalUnrealized >= 0) Color.rgb(120, 200, 140) else Color.rgb(220, 120, 120)
        val pnlSign = if (totalUnrealized >= 0) "+" else ""

        balanceLabel.text = "Баланс: $${"%.2f".format(balance)}  ·  Открыто: ${open.size}" +
                (if (open.isNotEmpty()) "  ·  P&L: $pnlSign$${"%.2f".format(totalUnrealized)}" else "")
        balanceLabel.setTextColor(if (totalUnrealized < -0.01) pnlColor else Prefs.text(this))

        contentContainer.removeAllViews()

        if (stats.totalTrades > 0) {
            contentContainer.addView(makeSectionHeader("📊 Статистика"))
            contentContainer.addView(makeStatsBlock(stats))
        }

        if (open.isNotEmpty()) {
            contentContainer.addView(makeSectionHeader("🟢 Открытые (${open.size})"))
            open.sortedByDescending { it.entryTime }.forEach { t ->
                contentContainer.addView(makeOpenRow(t))
            }
        } else {
            val empty = TextView(this)
            empty.text = "Открытых позиций нет"
            empty.textSize = 14f
            empty.setTextColor(Prefs.textDim(this))
            empty.setPadding(8, 12, 8, 16)
            contentContainer.addView(empty)
        }

        val closed = PaperTrading.closedTrades().take(50)
        if (closed.isNotEmpty()) {
            contentContainer.addView(makeSectionHeader("📜 Закрытые (${PaperTrading.closedTrades().size})"))
            closed.forEach { t ->
                contentContainer.addView(makeClosedRow(t))
            }
        }
    }

    private fun makeSectionHeader(text: String): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 18f
        tv.setTypeface(null, Typeface.BOLD)
        tv.setTextColor(Prefs.text(this))
        tv.setPadding(0, 16, 0, 8)
        return tv
    }

    private fun makeStatsBlock(stats: PaperTrading.Stats): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setBackgroundColor(Prefs.row(this))
        box.setPadding(16, 12, 16, 12)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(0, 0, 0, 12)
        box.layoutParams = lp

        val pnlColor = if (stats.totalPnl >= 0) Color.rgb(120, 200, 140) else Color.rgb(220, 120, 120)
        val pnlSign = if (stats.totalPnl >= 0) "+" else ""

        addStatLine(box, "Всего сделок", "${stats.totalTrades}  (W ${stats.wins} / L ${stats.losses} / B ${stats.breakeven})", Prefs.text(this))
        addStatLine(box, "Winrate", "${"%.1f".format(stats.winRate)}%",
            if (stats.winRate >= 50) Color.rgb(120, 200, 140) else Color.rgb(220, 120, 120))
        addStatLine(box, "Общий P&L", "$pnlSign$${"%.2f".format(stats.totalPnl)}", pnlColor)
        addStatLine(box, "Средний выигрыш", "+$${"%.2f".format(stats.avgWin)}", Color.rgb(120, 200, 140))
        addStatLine(box, "Средний проигрыш", "$${"%.2f".format(stats.avgLoss)}", Color.rgb(220, 120, 120))
        addStatLine(box, "Profit factor", "%.2f".format(stats.profitFactor),
            if (stats.profitFactor >= 1.5) Color.rgb(120, 200, 140) else Prefs.textDim(this))
        addStatLine(box, "Лучшая/худшая", "+$${"%.2f".format(stats.bestTrade)} / $${"%.2f".format(stats.worstTrade)}", Prefs.textDim(this))
        addStatLine(box, "Средняя длительность", formatDuration(stats.avgDurationSec), Prefs.textDim(this))
        addStatLine(box, "Лонги", "W ${stats.longWins} / L ${stats.longLosses}",
            if (stats.longWins >= stats.longLosses) Color.rgb(120, 200, 140) else Color.rgb(220, 120, 120))
        addStatLine(box, "Шорты", "W ${stats.shortWins} / L ${stats.shortLosses}",
            if (stats.shortWins >= stats.shortLosses) Color.rgb(120, 200, 140) else Color.rgb(220, 120, 120))

        val exits = PaperTrading.exitReasonStats()
        addStatLine(box, "── Выходы ──", "", Prefs.textDim(this))
        addStatLine(box, "✅ TP", "${exits.tp}", Color.rgb(120, 200, 140))
        addStatLine(box, "❌ SL", "${exits.sl}", Color.rgb(220, 120, 120))
        addStatLine(box, "⏱ timeout", "${exits.timeout}", Color.rgb(255, 200, 60))
        addStatLine(box, "✋ вручную", "${exits.manual}", Prefs.textDim(this))

        return box
    }

    private fun addStatLine(parent: LinearLayout, label: String, value: String, valueColor: Int) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(0, 3, 0, 3)

        val l = TextView(this)
        l.text = label
        l.textSize = 13f
        l.setTextColor(Prefs.textDim(this))
        l.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        row.addView(l)

        val v = TextView(this)
        v.text = value
        v.textSize = 13f
        v.setTypeface(null, Typeface.BOLD)
        v.setTextColor(valueColor)
        row.addView(v)

        parent.addView(row)
    }

    private fun makeOpenRow(t: PaperTrade): View {
        val price = currentPriceOf(t.symbol)
        val pnl = t.unrealizedPnl(price)
        val pnlPct = t.unrealizedPnlPct(price)
        val isProfit = pnl >= 0
        val pnlColor = if (isProfit) Color.rgb(120, 200, 140) else Color.rgb(220, 120, 120)
        val pnlSign = if (isProfit) "+" else ""

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setBackgroundColor(Prefs.row(this))
        box.setPadding(14, 10, 14, 10)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(0, 0, 0, 8)
        box.layoutParams = lp

        val headerRow = LinearLayout(this)
        headerRow.orientation = LinearLayout.HORIZONTAL
        headerRow.gravity = Gravity.CENTER_VERTICAL

        val cascadeIcon = if (t.ctxHasCascade) "💥 " else ""
        val dirText = if (t.isLong) "🟢 LONG" else "🔴 SHORT"
        val dirView = TextView(this)
        dirView.text = "$cascadeIcon$dirText  ${t.symbol}"
        dirView.textSize = 16f
        dirView.setTypeface(null, Typeface.BOLD)
        dirView.setTextColor(Prefs.text(this))
        dirView.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        headerRow.addView(dirView)

        val closeBtn = Button(this)
        closeBtn.text = "Закрыть"
        closeBtn.textSize = 12f
        closeBtn.isAllCaps = false
        closeBtn.setTextColor(Color.WHITE)
        closeBtn.background = pressedDrawable(Color.rgb(140, 50, 50), Color.rgb(180, 70, 70))
        closeBtn.setOnClickListener {
            safe("closeTrade") {
                if (price > 0) {
                    val closed = PaperTrading.closeTrade(this, t.id, price, "manual")
                    if (closed != null) {
                        Toast.makeText(this, "Закрыто: ${t.symbol}", Toast.LENGTH_SHORT).show()
                        refresh()
                    }
                } else {
                    Toast.makeText(this, "Нет текущей цены", Toast.LENGTH_SHORT).show()
                }
            }
        }
        headerRow.addView(closeBtn)

        box.addView(headerRow)

        val info = TextView(this)
        info.text = "Вход: ${"%.4f".format(t.entryPrice)} · Сейчас: ${"%.4f".format(price)} · Размер: $${"%.2f".format(t.sizeUsd)}"
        info.textSize = 13f
        info.setTextColor(Prefs.textDim(this))
        info.setPadding(0, 4, 0, 0)
        box.addView(info)

        if (t.slPrice > 0 && t.tpPrice > 0) {
            val sltpLine = TextView(this)
            sltpLine.text = "SL: ${"%.4f".format(t.slPrice)} · TP: ${"%.4f".format(t.tpPrice)}"
            sltpLine.textSize = 12f
            sltpLine.setTextColor(Color.rgb(150, 150, 170))
            sltpLine.setPadding(0, 2, 0, 0)
            box.addView(sltpLine)
        }

        val pnlLine = TextView(this)
        pnlLine.text = "P&L: $pnlSign$${"%.2f".format(pnl)} ($pnlSign${"%.2f".format(pnlPct)}%)"
        pnlLine.textSize = 15f
        pnlLine.setTypeface(null, Typeface.BOLD)
        pnlLine.setTextColor(pnlColor)
        pnlLine.setPadding(0, 4, 0, 0)
        box.addView(pnlLine)

        val dur = t.durationSec()
        val durLine = TextView(this)
        durLine.text = "Держится: ${formatDuration(dur)}"
        durLine.textSize = 12f
        durLine.setTextColor(Prefs.textDim(this))
        box.addView(durLine)

        return box
    }

    private fun makeClosedRow(t: PaperTrade): View {
        val pnl = t.realizedPnl()
        val pnlPct = t.realizedPnlPct()
        val isProfit = pnl >= 0
        val pnlColor = if (isProfit) Color.rgb(120, 200, 140) else Color.rgb(220, 120, 120)
        val pnlSign = if (isProfit) "+" else ""

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setBackgroundColor(Prefs.row(this))
        box.setPadding(14, 8, 14, 8)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(0, 0, 0, 6)
        box.layoutParams = lp

        val headerRow = LinearLayout(this)
        headerRow.orientation = LinearLayout.HORIZONTAL

        val dirText = if (t.isLong) "🟢" else "🔴"
        val cascadeIcon = if (t.ctxHasCascade) "💥" else ""
        val dirView = TextView(this)
        dirView.text = "$dirText $cascadeIcon ${t.symbol}"
        dirView.textSize = 14f
        dirView.setTypeface(null, Typeface.BOLD)
        dirView.setTextColor(Prefs.text(this))
        dirView.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        headerRow.addView(dirView)

        val pnlView = TextView(this)
        pnlView.text = "$pnlSign$${"%.2f".format(pnl)} ($pnlSign${"%.2f".format(pnlPct)}%)"
        pnlView.textSize = 14f
        pnlView.setTypeface(null, Typeface.BOLD)
        pnlView.setTextColor(pnlColor)
        headerRow.addView(pnlView)

        box.addView(headerRow)

        val reasonLabel = when (t.closeReason) {
            "sl" -> "  ❌ SL"
            "tp" -> "  ✅ TP"
            "timeout" -> "  ⏱ timeout"
            "close_all" -> "  ✋ закрыто всё"
            else -> ""
        }
          val info = TextView(this)
        info.text = "${"%.4f".format(t.entryPrice)} → ${"%.4f".format(t.exitPrice)} · $${"%.2f".format(t.sizeUsd)} · ${formatDuration(t.durationSec())}$reasonLabel"
        info.textSize = 12f
        info.setTextColor(Prefs.textDim(this))
        info.setPadding(0, 3, 0, 0)
        box.addView(info)

        return box
    }

    private fun copyTradesToClipboard() {
        val balance = PaperTrading.balance()
        val all = PaperTrading.trades()
        val open = all.filter { it.isOpen }
        val closed = all.filter { !it.isOpen }
        val stats = PaperTrading.computeStats()
        val exits = PaperTrading.exitReasonStats()

        val sb = StringBuilder()
        sb.appendLine("=== PAPER TRADING ===")
        val df = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault())
        sb.appendLine("Сформировано: ${df.format(java.util.Date())}")
        sb.appendLine()

        sb.appendLine("--- БАЛАНС ---")
        sb.appendLine("Текущий: $${"%.2f".format(balance)}")
        sb.appendLine("Открытых позиций: ${open.size}")
        sb.appendLine("Закрытых сделок: ${closed.size}")
        sb.appendLine()

        if (stats.totalTrades > 0) {
            sb.appendLine("--- СТАТИСТИКА ---")
            sb.appendLine("Всего: ${stats.totalTrades} (W ${stats.wins} / L ${stats.losses} / B ${stats.breakeven})")
            sb.appendLine("Winrate: ${"%.1f".format(stats.winRate)}%")
            val pnlSign = if (stats.totalPnl >= 0) "+" else ""
            sb.appendLine("Total P&L: $pnlSign$${"%.2f".format(stats.totalPnl)}")
            sb.appendLine("Средний выигрыш: +$${"%.2f".format(stats.avgWin)}")
            sb.appendLine("Средний проигрыш: $${"%.2f".format(stats.avgLoss)}")
            sb.appendLine("Profit factor: ${"%.2f".format(stats.profitFactor)}")
            sb.appendLine("Лучшая: +$${"%.2f".format(stats.bestTrade)} · Худшая: $${"%.2f".format(stats.worstTrade)}")
            sb.appendLine("Средняя длительность: ${formatDuration(stats.avgDurationSec)}")
            sb.appendLine("Лонги: W ${stats.longWins} / L ${stats.longLosses}")
            sb.appendLine("Шорты: W ${stats.shortWins} / L ${stats.shortLosses}")
            sb.appendLine()
            sb.appendLine("Выходы:")
            sb.appendLine("  ✅ TP: ${exits.tp}")
            sb.appendLine("  ❌ SL: ${exits.sl}")
            sb.appendLine("  ⏱ timeout: ${exits.timeout}")
            sb.appendLine("  ✋ вручную: ${exits.manual}")
            sb.appendLine()
        }

        if (open.isNotEmpty()) {
            sb.appendLine("--- ОТКРЫТЫЕ ---")
            for (t in open.sortedByDescending { it.entryTime }) {
                val price = currentPriceOf(t.symbol)
                val pnl = t.unrealizedPnl(price)
                val pnlPct = t.unrealizedPnlPct(price)
                val sign = if (pnl >= 0) "+" else ""
                val dir = if (t.isLong) "🟢 LONG" else "🔴 SHORT"
                val cascade = if (t.ctxHasCascade) " 🔥" else ""
                val sltp = if (t.slPrice > 0 && t.tpPrice > 0)
                    " SL ${"%.4f".format(t.slPrice)} / TP ${"%.4f".format(t.tpPrice)}" else ""
                sb.appendLine("$dir ${t.symbol}$cascade · $${"%.2f".format(t.sizeUsd)} · $sign$${"%.2f".format(pnl)} ($sign${"%.2f".format(pnlPct)}%) · ${formatDuration(t.durationSec())}$sltp")
            }
            sb.appendLine()
        }

        if (closed.isNotEmpty()) {
            sb.appendLine("--- ЗАКРЫТЫЕ (последние 30) ---")
            for (t in closed.take(30)) {
                val pnl = t.realizedPnl()
                val pnlPct = t.realizedPnlPct()
                val sign = if (pnl >= 0) "+" else ""
                val dir = if (t.isLong) "🟢" else "🔴"
                val cascade = if (t.ctxHasCascade) "🔥" else ""
                val sig = when (t.ctxSignalStrength) {
                    "strong" -> "⭐"
                    "weak" -> "·"
                    else -> ""
                }
                val reason = when (t.closeReason) {
                    "sl" -> " ❌SL"
                    "tp" -> " ✅TP"
                    "timeout" -> " ⏱"
                    else -> ""
                }
                val timeStr = df.format(java.util.Date(t.exitTime))
                sb.appendLine("$dir $cascade $sig ${t.symbol} · $${"%.2f".format(t.sizeUsd)} · $sign$${"%.2f".format(pnl)} ($sign${"%.2f".format(pnlPct)}%) · $timeStr$reason")
            }
            sb.appendLine()
        }

        sb.appendLine("--- АНАЛИЗ ВХОДОВ ---")
        val withCascade = PaperTrading.groupStats("С каскадом") { it.ctxHasCascade }
        val withoutCascade = PaperTrading.groupStats("Без каскада") { !it.ctxHasCascade }
        if (withCascade.count > 0) {
            sb.appendLine("💥 С каскадом: ${"%.1f".format(withCascade.winRate)}% (${withCascade.wins}W/${withCascade.losses}L) · $${"%.2f".format(withCascade.totalPnl)}")
        }
        if (withoutCascade.count > 0) {
            sb.appendLine("⚪ Без каскада: ${"%.1f".format(withoutCascade.winRate)}% (${withoutCascade.wins}W/${withoutCascade.losses}L) · $${"%.2f".format(withoutCascade.totalPnl)}")
        }

        try {
            val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("PaperTrading", sb.toString().trim()))
            Toast.makeText(this, "Скопировано", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка: ${e.message?.take(50)}", Toast.LENGTH_LONG).show()
        }
    }

    private fun closeAll() {
        val open = PaperTrading.openTrades()
        if (open.isEmpty()) {
            Toast.makeText(this, "Нет открытых позиций", Toast.LENGTH_SHORT).show()
            return
        }
        val closed = PaperTrading.closeAll(this, { sym -> currentPriceOf(sym) }, "close_all")
        Toast.makeText(this, "Закрыто: $closed", Toast.LENGTH_SHORT).show()
        refresh()
    }

    private fun resetAll() {
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Сбросить портфель?")
            .setMessage("Все сделки будут удалены, баланс вернётся к \$10,000")
            .setPositiveButton("Сбросить") { _, _ ->
                PaperTrading.reset(this, 10_000.0)
                Toast.makeText(this, "Портфель сброшен", Toast.LENGTH_SHORT).show()
                refresh()
            }
            .setNegativeButton("Отмена", null)
            .create()
        dialog.show()
    }

    private fun currentPriceOf(symbol: String): Double {
        return try {
            val results = MainActivity.sharedScanResults
            results.firstOrNull { it.symbol == symbol }?.price ?: 0.0
        } catch (_: Exception) { 0.0 }
    }

    private fun formatDuration(sec: Long): String {
        if (sec < 60) return "${sec}с"
        if (sec < 3600) return "${sec / 60}м ${sec % 60}с"
        val h = sec / 3600
        val m = (sec % 3600) / 60
        return "${h}ч ${m}м"
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