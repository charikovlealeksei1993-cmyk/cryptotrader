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

class AnalysisActivity : Activity() {

    private lateinit var root: LinearLayout
    private lateinit var contentContainer: LinearLayout
    private var minTradesForGroup = 2

    private fun safe(tag: String, block: () -> Unit) {
        try { block() } catch (e: Throwable) {
            android.util.Log.e("Analysis", "CRASH $tag: ${e.message}", e)
            try { Toast.makeText(this, "Ошибка: ${e.message?.take(80)}", Toast.LENGTH_LONG).show() } catch (_: Exception) {}
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
            val top: Int; val bottom: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                top = bars.top; bottom = bars.bottom
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
        title.text = "📊 Анализ входов"
        title.textSize = 22f
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

        contentContainer = LinearLayout(this)
        contentContainer.orientation = LinearLayout.VERTICAL
        root.addView(contentContainer)

        setContentView(scroll)
    }

    private fun refresh() {
        contentContainer.removeAllViews()

        val allTrades = PaperTrading.trades()
        val closed = allTrades.filter { !it.isOpen }

        if (closed.isEmpty()) {
            val tv = TextView(this)
            tv.text = "Нет закрытых сделок.\n\nСделай несколько paper-сделок (BUY/SELL в карточках) и закрой их — статистика появится здесь."
            tv.textSize = 14f
            tv.setTextColor(Prefs.textDim(this))
            tv.setPadding(0, 16, 0, 0)
            contentContainer.addView(tv)
            return
        }

        val info = TextView(this)
        info.text = "Закрытых сделок: ${closed.size}\nАнализ показывает связь между условиями входа и результатом.\n⚠️ Минимум $minTradesForGroup сделок в группе для показа."
        info.textSize = 12f
        info.setTextColor(Prefs.textDim(this))
        info.setPadding(0, 0, 0, 16)
        contentContainer.addView(info)

        // ===== ПО ПАТТЕРНАМ (новое, вверху) =====
        val patternStats = PaperTrading.statsByPattern()
        if (patternStats.isNotEmpty() && patternStats.any { it.count >= minTradesForGroup }) {
            addSection("🎯 По паттернам")
            for (ps in patternStats) {
                if (ps.count < minTradesForGroup) continue
                addPatternGroup(ps)
            }
        }

        // ===== Каскад =====
        addSection("💥 Каскад ликвидаций на входе")
        addGroup("С каскадом", { it.ctxHasCascade })
        addGroup("Без каскада", { !it.ctxHasCascade })

        // ===== STRONG / WEAK =====
        addSection("⭐ Сила сигнала")
        addGroup("STRONG", { it.ctxSignalStrength == "strong" })
        addGroup("WEAK", { it.ctxSignalStrength == "weak" })
        addGroup("Без сигнала (вход по своим правилам)", { it.ctxSignalStrength.isEmpty() })

        // ===== Направление =====
        addSection("🟢 / 🔴 Направление")
        addGroup("LONG", { it.isLong })
        addGroup("SHORT", { !it.isLong })

        // ===== Flow =====
        addSection("💰 Flow (направление денег)")
        addGroup("New Money ↑", { it.ctxFlowStatus == "new_long" })
        addGroup("New Money ↓", { it.ctxFlowStatus == "new_short" })
        addGroup("Short Squeeze", { it.ctxFlowStatus == "short_squeeze" })
        addGroup("Long Squeeze", { it.ctxFlowStatus == "long_squeeze" })
        addGroup("Churn", { it.ctxFlowStatus == "churn" || it.ctxFlowStatus.isEmpty() })

        // ===== Тренд ликвидаций =====
        addSection("🔥 Тренд ликвидаций")
        addGroup("🔥 растёт", { it.ctxLiqTrend == "rising" })
        addGroup("➡️ стабильно", { it.ctxLiqTrend == "stable" })
        addGroup("🪫 затухает", { it.ctxLiqTrend == "falling" })

        // ===== OI change =====
        addSection("📊 Изменение OI")
        addGroup("OI < -1%", { it.ctxOiChange < -1.0 })
        addGroup("OI -1..0%", { it.ctxOiChange in -1.0..0.0 })
        addGroup("OI 0..+1%", { it.ctxOiChange in 0.0..1.0 })
        addGroup("OI > +1%", { it.ctxOiChange > 1.0 })

        // ===== Delta =====
        addSection("Δ Candle delta (1м)")
        addGroup("Delta < -50%", { it.ctxDeltaPct1m < -50 })
        addGroup("Delta -50..-20%", { it.ctxDeltaPct1m in -50.0..-20.0 })
        addGroup("Delta -20..+20%", { it.ctxDeltaPct1m in -20.0..20.0 })
        addGroup("Delta +20..+50%", { it.ctxDeltaPct1m in 20.0..50.0 })
        addGroup("Delta > +50%", { it.ctxDeltaPct1m > 50 })

        // ===== Real delta =====
        addSection("💰 Real delta (знак)")
        addGroup("Real delta + (покупатели)", { it.ctxRealDeltaUsd > 0 })
        addGroup("Real delta - (продавцы)", { it.ctxRealDeltaUsd < 0 })

        // ===== Volume =====
        addSection("🚀 VolRatio")
        addGroup("×1.5–2", { it.ctxVolRatio in 1.5..2.0 })
        addGroup("×2–5", { it.ctxVolRatio in 2.0..5.0 })
        addGroup("×5–10", { it.ctxVolRatio in 5.0..10.0 })
        addGroup("×10+", { it.ctxVolRatio > 10.0 })

        // ===== Speed =====
        addSection("⚡ Скорость")
        addGroup("⚡⚡⚡ ускорение", { it.ctxSpeedAccelerating })
        addGroup("🪫 затухание", { it.ctxSpeedDying })
        addGroup("⚡ стабильно", { !it.ctxSpeedAccelerating && !it.ctxSpeedDying })

        // ===== L/S =====
        addSection("📊 Перекос лонгов/шортов")
        addGroup("Лонги > 60%", { it.ctxLongRatio > 0.60 })
        addGroup("Баланс 40-60%", { it.ctxLongRatio in 0.40..0.60 })
        addGroup("Шорты > 60%", { it.ctxLongRatio < 0.40 })

        // ===== Стакан =====
        addSection("⚖️ Стакан")
        addGroup("Покупатели (imb > +0.3)", { it.ctxObImbalance > 0.30 })
        addGroup("Баланс", { it.ctxObImbalance in -0.30..0.30 })
        addGroup("Продавцы (imb < -0.3)", { it.ctxObImbalance < -0.30 })
    }

    private fun addSection(title: String) {
        val tv = TextView(this)
        tv.text = title
        tv.textSize = 16f
        tv.setTypeface(null, Typeface.BOLD)
        tv.setTextColor(Prefs.text(this))
        tv.setPadding(0, 20, 0, 8)
        contentContainer.addView(tv)
    }

    private fun addPatternGroup(ps: PaperTrading.PatternGroup) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setBackgroundColor(Prefs.row(this))
        box.setPadding(14, 10, 14, 10)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, 0, 0, 6)
        box.layoutParams = lp

        val headerRow = LinearLayout(this)
        headerRow.orientation = LinearLayout.HORIZONTAL

        val labelView = TextView(this)
        labelView.text = ps.pattern
        labelView.textSize = 13f
        labelView.setTypeface(null, Typeface.BOLD)
        labelView.setTextColor(Prefs.text(this))
        labelView.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        headerRow.addView(labelView)

        val wrColor = if (ps.winRate >= 50) Color.rgb(120, 200, 140) else Color.rgb(220, 120, 120)
        val wrView = TextView(this)
        wrView.text = "${"%.0f".format(ps.winRate)}%  (${ps.wins}W / ${ps.losses}L)"
        wrView.textSize = 13f
        wrView.setTypeface(null, Typeface.BOLD)
        wrView.setTextColor(wrColor)
        headerRow.addView(wrView)

        box.addView(headerRow)

        val pnlColor = if (ps.totalPnl >= 0) Color.rgb(120, 200, 140) else Color.rgb(220, 120, 120)
        val pnlSign = if (ps.totalPnl >= 0) "+" else ""
        val avgSign = if (ps.avgPnl >= 0) "+" else ""

        val info = TextView(this)
        info.text = "Сделок: ${ps.count} · Итог: $pnlSign$${"%.2f".format(ps.totalPnl)} · Средний: $avgSign$${"%.2f".format(abs(ps.avgPnl))}"
        info.textSize = 11f
        info.setTextColor(pnlColor)
        info.setPadding(0, 3, 0, 0)
        box.addView(info)

        contentContainer.addView(box)
    }

    private fun addGroup(label: String, filter: (PaperTrade) -> Boolean) {
        val stats = PaperTrading.groupStats(label, filter)
        if (stats.count < minTradesForGroup) return

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setBackgroundColor(Prefs.row(this))
        box.setPadding(14, 10, 14, 10)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, 0, 0, 6)
        box.layoutParams = lp

        val headerRow = LinearLayout(this)
        headerRow.orientation = LinearLayout.HORIZONTAL

        val labelView = TextView(this)
        labelView.text = label
        labelView.textSize = 13f
        labelView.setTypeface(null, Typeface.BOLD)
        labelView.setTextColor(Prefs.text(this))
        labelView.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        headerRow.addView(labelView)

        val wrColor = if (stats.winRate >= 50) Color.rgb(120, 200, 140) else Color.rgb(220, 120, 120)
        val wrView = TextView(this)
        wrView.text = "${"%.0f".format(stats.winRate)}%  (${stats.wins}W / ${stats.losses}L)"
        wrView.textSize = 13f
        wrView.setTypeface(null, Typeface.BOLD)
        wrView.setTextColor(wrColor)
        headerRow.addView(wrView)

        box.addView(headerRow)

        val pnlColor = if (stats.totalPnl >= 0) Color.rgb(120, 200, 140) else Color.rgb(220, 120, 120)
        val pnlSign = if (stats.totalPnl >= 0) "+" else ""
        val avgSign = if (stats.avgPnl >= 0) "+" else ""

        val info = TextView(this)
        info.text = "Сделок: ${stats.count} · Итог: $pnlSign$${"%.2f".format(stats.totalPnl)} · Средний: $avgSign$${"%.2f".format(abs(stats.avgPnl))}"
        info.textSize = 11f
        info.setTextColor(pnlColor)
        info.setPadding(0, 3, 0, 0)
        box.addView(info)

        contentContainer.addView(box)
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