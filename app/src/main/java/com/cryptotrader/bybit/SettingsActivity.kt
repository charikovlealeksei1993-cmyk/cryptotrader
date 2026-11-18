package com.cryptotrader.bybit

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {
    private val PICK_SOUND_MAIN = 2001
    private val PICK_SOUND_LIQ = 2002
    private val PICK_SOUND_SPEED = 2003
    private val PICK_SOUND_VOLUME = 2004

    private lateinit var liqToggle: Button
    private lateinit var speedToggle: Button
    private lateinit var autoToggle: Button
    private lateinit var autoWithBtn: Button
    private lateinit var autoAgainstBtn: Button
    private lateinit var autoEnterFadeBtn: Button
    private lateinit var autoEnterStartBtn: Button
    private lateinit var volumeToggle: Button
    private lateinit var liqSoundBtn: Button
    private lateinit var speedSoundBtn: Button
    private lateinit var volumeSoundBtn: Button
    private lateinit var soundBtn: Button

    private lateinit var scanLimitLabel: TextView
    private lateinit var scanLimitButtons: MutableList<Pair<Int, Button>>

    private lateinit var slLabel: TextView
    private lateinit var slButtons: MutableList<Pair<Double, Button>>
    private lateinit var tpLabel: TextView
    private lateinit var tpButtons: MutableList<Pair<Double, Button>>
    private lateinit var timeoutLabel: TextView
    private lateinit var timeoutButtons: MutableList<Pair<Int, Button>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Prefs.bg(this))

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(32, 32, 32, 32)
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

        val title = TextView(this)
        title.text = "⚙ Настройки"
        title.textSize = 24f
        title.setTextColor(Prefs.text(this))
        title.setPadding(0, 0, 0, 24)
        root.addView(title)
                // ===== TF для сигналов =====
        val signalTfTitle = TextView(this)
        signalTfTitle.text = "📡 Таймфрейм сигналов"
        signalTfTitle.textSize = 20f
        signalTfTitle.setTypeface(null, android.graphics.Typeface.BOLD)
        signalTfTitle.setTextColor(Prefs.text(this))
        signalTfTitle.setPadding(0, 0, 0, 8)
        root.addView(signalTfTitle)

        val signalTfHint = TextView(this)
        signalTfHint.text = "На каком TF искать сигналы. 1м — много и быстро, 15м — редко но точнее."
        signalTfHint.textSize = 13f
        signalTfHint.setTextColor(Prefs.textDim(this))
        signalTfHint.setPadding(0, 0, 0, 8)
        root.addView(signalTfHint)

        val signalTfLabel = TextView(this)
        signalTfLabel.textSize = 16f
        signalTfLabel.setTextColor(Prefs.text(this))
        signalTfLabel.setPadding(0, 0, 0, 8)
        root.addView(signalTfLabel)

        val signalTfButtons = ArrayList<Pair<String, Button>>()
        val signalTfRow = LinearLayout(this)
        signalTfRow.orientation = LinearLayout.HORIZONTAL
        val stRowLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        stRowLp.setMargins(0, 0, 0, 16)
        signalTfRow.layoutParams = stRowLp

        val signalTfOptions = listOf(
            "1" to "1м",
            "5" to "5м",
            "15" to "15м",
            "60" to "1ч"
        )

        for ((tf, label) in signalTfOptions) {
            val b = Button(this)
            b.text = label
            b.textSize = 14f
            b.isAllCaps = false
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(3, 0, 3, 0)
            b.layoutParams = lp
            b.setOnClickListener {
                Prefs.setSignalTf(this, tf)
                updateSignalTfButtons(signalTfButtons, signalTfLabel)
            }
            signalTfRow.addView(b)
            signalTfButtons.add(tf to b)
        }
        root.addView(signalTfRow)

        // ===== Сканирование =====
        // ===== Порог агрессивной дельты =====
        val burstTitle = TextView(this)
        burstTitle.text = "📊 Агрессивная дельта"
        burstTitle.textSize = 20f
        burstTitle.setTypeface(null, android.graphics.Typeface.BOLD)
        burstTitle.setTextColor(Prefs.text(this))
        burstTitle.setPadding(0, 0, 0, 8)
        root.addView(burstTitle)

        val burstLabel = TextView(this)
        burstLabel.textSize = 16f
        burstLabel.setTextColor(Prefs.text(this))
        burstLabel.setPadding(0, 0, 0, 8)
        root.addView(burstLabel)

        val burstButtons = ArrayList<Pair<Double, Button>>()
        val burstRow = LinearLayout(this)
        burstRow.orientation = LinearLayout.HORIZONTAL
        val burstRowLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        burstRowLp.setMargins(0, 0, 0, 4)
        burstRow.layoutParams = burstRowLp

        for (v in listOf(10_000.0, 25_000.0, 50_000.0, 100_000.0, 500_000.0)) {
            val b = Button(this)
            val k = (v / 1000).toInt()
            b.text = "${k}K"
            b.textSize = 13f
            b.isAllCaps = false
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(2, 0, 2, 0)
            b.layoutParams = lp
            b.setOnClickListener {
                Prefs.setDeltaBurstThreshold(this, v)
                updateBurstButtons(burstButtons, burstLabel)
            }
            burstRow.addView(b)
            burstButtons.add(v to b)
        }
        root.addView(burstRow)

        val burstHint = TextView(this)
        burstHint.text = "Сделка считается «толчком», если её сумма превышает порог. Счётчик покажет, сколько раз покупатели/продавцы давили за час."
        burstHint.textSize = 13f
        burstHint.setTextColor(Prefs.textDim(this))
        burstHint.setPadding(0, 4, 0, 20)
        root.addView(burstHint)
        val scanTitle = TextView(this)
        scanTitle.text = "🎯 Сканирование"
        scanTitle.textSize = 20f
        scanTitle.setTypeface(null, android.graphics.Typeface.BOLD)
        scanTitle.setTextColor(Prefs.text(this))
        scanTitle.setPadding(0, 0, 0, 12)
        root.addView(scanTitle)

        scanLimitLabel = TextView(this)
        scanLimitLabel.textSize = 16f
        scanLimitLabel.setTextColor(Prefs.text(this))
        scanLimitLabel.setPadding(0, 0, 0, 8)
        root.addView(scanLimitLabel)

        scanLimitButtons = ArrayList()
        val scanRow = LinearLayout(this)
        scanRow.orientation = LinearLayout.HORIZONTAL
        val scanRowLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        scanRowLp.setMargins(0, 0, 0, 4)
        scanRow.layoutParams = scanRowLp

        for (n in listOf(5, 10, 15, 20)) {
            val b = Button(this)
            b.text = n.toString()
            b.textSize = 14f
            b.isAllCaps = false
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(3, 0, 3, 0)
            b.layoutParams = lp
            b.setOnClickListener {
                Prefs.setScanLimit(this, n)
                updateScanLimitButtons()
            }
            scanRow.addView(b)
            scanLimitButtons.add(n to b)
        }
        root.addView(scanRow)

        val scanHint = TextView(this)
        scanHint.text = "Сколько топ-монет по обороту сканировать. Больше — больше сигналов, но дольше скан"
        scanHint.textSize = 13f
        scanHint.setTextColor(Prefs.textDim(this))
        scanHint.setPadding(0, 4, 0, 20)
        root.addView(scanHint)

        // ===== Окно накопления =====
        val accTitle = TextView(this)
        accTitle.text = "🟡 Накопление"
        accTitle.textSize = 20f
        accTitle.setTypeface(null, android.graphics.Typeface.BOLD)
        accTitle.setTextColor(Prefs.text(this))
        accTitle.setPadding(0, 24, 0, 12)
        root.addView(accTitle)

        val accLabel = TextView(this)
        accLabel.textSize = 16f
        accLabel.setTextColor(Prefs.text(this))
        accLabel.setPadding(0, 0, 0, 8)
        root.addView(accLabel)

        val accSeek = SeekBar(this)
        accSeek.max = 23 // (120 - 5) / 5 = 23 шага
        accSeek.progress = ((Prefs.accMinutes(this) - 5) / 5).coerceIn(0, 23)
        accSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = 5 + progress * 5
                accLabel.text = "Окно накопления: $v минут"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                val v = 5 + (sb?.progress ?: 0) * 5
                Prefs.setAccMinutes(this@SettingsActivity, v)
            }
        })
        accLabel.text = "Окно накопления: ${Prefs.accMinutes(this)} минут"
        root.addView(accSeek)

        val accHint = TextView(this)
        accHint.text = "Сколько минут анализировать для поиска накопления. 45 мин — скальпинг. 120 мин — более точный сигнал."
        accHint.textSize = 13f
        accHint.setTextColor(Prefs.textDim(this))
        accHint.setPadding(0, 4, 0, 20)
        root.addView(accHint)

        // ===== Окно макро-накопления (часы) =====
        val accHoursLabel = TextView(this)
        accHoursLabel.textSize = 16f
        accHoursLabel.setTextColor(Prefs.text(this))
        accHoursLabel.setPadding(0, 16, 0, 8)
        accHoursLabel.text = "Макро-накопление: ${Prefs.accHours(this)} ч"
        root.addView(accHoursLabel)

        val accHoursSeek = SeekBar(this)
        accHoursSeek.max = 119 // (120 - 1) = 119 шагов
        accHoursSeek.progress = Prefs.accHours(this) - 1
        accHoursSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = progress + 1
                accHoursLabel.text = "Макро-накопление: $v ч"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                val v = (sb?.progress ?: 0) + 1
                Prefs.setAccHours(this@SettingsActivity, v)
            }
        })
        root.addView(accHoursSeek)

        val accHoursHint = TextView(this)
        accHoursHint.text = "Анализ на M15-барах. 4 часа — стандарт, 24 часа — поиск глобального накопления."
        accHoursHint.textSize = 13f
        accHoursHint.setTextColor(Prefs.textDim(this))
        accHoursHint.setPadding(0, 4, 0, 20)
        root.addView(accHoursHint)

        // ===== Paper Trading: SL / TP / Timeout =====
        val paperTitle = TextView(this)
        paperTitle.text = "📈 Paper Trading"
        paperTitle.textSize = 20f
        paperTitle.setTypeface(null, android.graphics.Typeface.BOLD)
        paperTitle.setTextColor(Prefs.text(this))
        paperTitle.setPadding(0, 0, 0, 12)
        root.addView(paperTitle)

        // SL
        slLabel = TextView(this)
        slLabel.textSize = 16f
        slLabel.setTextColor(Prefs.text(this))
        slLabel.setPadding(0, 0, 0, 8)
        root.addView(slLabel)

        slButtons = ArrayList()
        val slRow = LinearLayout(this)
        slRow.orientation = LinearLayout.HORIZONTAL
        val slRowLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        slRowLp.setMargins(0, 0, 0, 12)
        slRow.layoutParams = slRowLp

        for (v in listOf(0.5, 1.0, 2.0, 3.0, 5.0)) {
            val b = Button(this)
            b.text = "${v}%"
            b.textSize = 13f
            b.isAllCaps = false
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(2, 0, 2, 0)
            b.layoutParams = lp
            b.setOnClickListener {
                Prefs.setPaperSlPercent(this, v)
                updateSlButtons()
            }
            slRow.addView(b)
            slButtons.add(v to b)
        }
        root.addView(slRow)

        // TP
        tpLabel = TextView(this)
        tpLabel.textSize = 16f
        tpLabel.setTextColor(Prefs.text(this))
        tpLabel.setPadding(0, 0, 0, 8)
        root.addView(tpLabel)

        tpButtons = ArrayList()
        val tpRow = LinearLayout(this)
        tpRow.orientation = LinearLayout.HORIZONTAL
        val tpRowLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        tpRowLp.setMargins(0, 0, 0, 12)
        tpRow.layoutParams = tpRowLp

        for (v in listOf(1.0, 2.0, 3.0, 5.0, 10.0)) {
            val b = Button(this)
            b.text = "${v}%"
            b.textSize = 13f
            b.isAllCaps = false
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(2, 0, 2, 0)
            b.layoutParams = lp
            b.setOnClickListener {
                Prefs.setPaperTpPercent(this, v)
                updateTpButtons()
            }
            tpRow.addView(b)
            tpButtons.add(v to b)
        }
        root.addView(tpRow)

        // Timeout
        timeoutLabel = TextView(this)
        timeoutLabel.textSize = 16f
        timeoutLabel.setTextColor(Prefs.text(this))
        timeoutLabel.setPadding(0, 0, 0, 8)
        root.addView(timeoutLabel)

        timeoutButtons = ArrayList()
        val toRow = LinearLayout(this)
        toRow.orientation = LinearLayout.HORIZONTAL
        val toRowLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        toRowLp.setMargins(0, 0, 0, 4)
        toRow.layoutParams = toRowLp

        for (v in listOf(30, 60, 240, 480, 1440)) {
            val b = Button(this)
            b.text = formatTimeout(v)
            b.textSize = 12f
            b.isAllCaps = false
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(2, 0, 2, 0)
            b.layoutParams = lp
            b.setOnClickListener {
                Prefs.setPaperTimeoutMinutes(this, v)
                updateTimeoutButtons()
            }
            toRow.addView(b)
            timeoutButtons.add(v to b)
        }
        root.addView(toRow)

        val paperHint = TextView(this)
        paperHint.text = "SL — авто-стоп, TP — авто-тейк, Timeout — максимум времени жизни сделки. Применяется к новым сделкам."
        paperHint.textSize = 13f
        paperHint.setTextColor(Prefs.textDim(this))
        paperHint.setPadding(0, 4, 0, 20)
        root.addView(paperHint)

        // ===== Тема =====
        val themeLabel = TextView(this)
        themeLabel.text = "Тема"
        themeLabel.textSize = 18f
        themeLabel.setTextColor(Prefs.text(this))
        root.addView(themeLabel)

        val themeRow = LinearLayout(this)
        themeRow.orientation = LinearLayout.HORIZONTAL
        val themeLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        themeLp.setMargins(3, 8, 3, 24)

        val darkBtn = Button(this)
        darkBtn.text = "Тёмная"
        darkBtn.layoutParams = themeLp
        val lightLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        lightLp.setMargins(3, 8, 3, 24)
        val lightBtn = Button(this)
        lightBtn.text = "Светлая"
        lightBtn.layoutParams = lightLp

        darkBtn.setOnClickListener {
            if (Prefs.theme(this) != "dark") {
                Prefs.setTheme(this, "dark")
                recreate()
            }
        }
        lightBtn.setOnClickListener {
            if (Prefs.theme(this) != "light") {
                Prefs.setTheme(this, "light")
                recreate()
            }
        }
        themeRow.addView(darkBtn)
        themeRow.addView(lightBtn)
        root.addView(themeRow)
        updatePair(darkBtn, lightBtn, Prefs.theme(this) == "dark")

        // ===== Тип графика =====
        val chartLabel = TextView(this)
        chartLabel.text = "Тип графика"
        chartLabel.textSize = 18f
        chartLabel.setTextColor(Prefs.text(this))
        root.addView(chartLabel)

        val chartRow = LinearLayout(this)
        chartRow.orientation = LinearLayout.HORIZONTAL
        val candLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        candLp.setMargins(3, 8, 3, 24)
        val candBtn = Button(this)
        candBtn.text = "Свечи"
        candBtn.layoutParams = candLp
        val lineLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        lineLp.setMargins(3, 8, 3, 24)
        val lineBtn = Button(this)
        lineBtn.text = "Линия"
        lineBtn.layoutParams = lineLp

        candBtn.setOnClickListener {
            Prefs.setChartType(this, "candles")
            updatePair(candBtn, lineBtn, true)
        }
        lineBtn.setOnClickListener {
            Prefs.setChartType(this, "line")
            updatePair(candBtn, lineBtn, false)
        }
        chartRow.addView(candBtn)
        chartRow.addView(lineBtn)
        root.addView(chartRow)
        updatePair(candBtn, lineBtn, Prefs.chartType(this) == "candles")

        // ===== История =====
        val histLabel = TextView(this)
        histLabel.text = "Свечей в истории: ${Prefs.history(this)}"
        histLabel.textSize = 18f
        histLabel.setTextColor(Prefs.text(this))
        root.addView(histLabel)

        val histSeek = SeekBar(this)
        histSeek.max = 900
        histSeek.progress = (Prefs.history(this) - 100).coerceIn(0, 900)
        histSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                histLabel.text = "Свечей в истории: ${progress + 100}"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                Prefs.setHistory(this@SettingsActivity, (sb?.progress ?: 100) + 100)
            }
        })
        root.addView(histSeek)

        // ===== Уведомления =====
        val notifTitle = TextView(this)
        notifTitle.text = "📢 Уведомления"
        notifTitle.textSize = 20f
        notifTitle.setTextColor(Prefs.text(this))
        notifTitle.setPadding(0, 32, 0, 12)
        root.addView(notifTitle)

        val step = Prefs.LIQ_THRESHOLD_STEP
        val minV = Prefs.LIQ_THRESHOLD_MIN
        val maxV = Prefs.LIQ_THRESHOLD_MAX
        val steps = ((maxV - minV) / step).toInt()

        val liqThresholdLabel = TextView(this)
        liqThresholdLabel.textSize = 16f
        liqThresholdLabel.setTextColor(Prefs.text(this))

        fun formatThreshold(v: Double): String {
            val k = (v / 1000).toInt()
            return "Порог ликвидаций: \$${k}K"
        }
liqThresholdLabel.text = formatThreshold(Prefs.liqThreshold(this))
        root.addView(liqThresholdLabel)

        val liqThresholdSeek = SeekBar(this)
        liqThresholdSeek.max = steps
        liqThresholdSeek.progress =
            ((Prefs.liqThreshold(this) - minV) / step).toInt().coerceIn(0, steps)
        liqThresholdSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = minV + progress * step
                liqThresholdLabel.text = formatThreshold(v)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                val v = minV + (sb?.progress ?: 0) * step
                Prefs.setLiqThreshold(this@SettingsActivity, v)
            }
        })
        root.addView(liqThresholdSeek)

        val liqThresholdHint = TextView(this)
        liqThresholdHint.text = "Ниже порога уведомления и строка «Ликв 15м» в карточке скрываются"
        liqThresholdHint.textSize = 13f
        liqThresholdHint.setTextColor(Prefs.textDim(this))
        liqThresholdHint.setPadding(0, 4, 0, 12)
        root.addView(liqThresholdHint)

        val lttLabel = TextView(this)
        lttLabel.textSize = 16f
        lttLabel.setTextColor(Prefs.text(this))

        fun formatLtt(v: Double): String {
            val k = (v / 1000).toInt()
            return "Крупные сделки: ≥$${k}K"
        }

        lttLabel.text = formatLtt(Prefs.largeTradeThreshold(this))
        lttLabel.setPadding(0, 16, 0, 8)
        root.addView(lttLabel)

        val lttStepI = Prefs.LARGE_TRADE_STEP.toInt()
        val lttMinI = Prefs.LARGE_TRADE_MIN.toInt()
        val lttMaxI = Prefs.LARGE_TRADE_MAX.toInt()
        val lttSteps = (lttMaxI - lttMinI) / lttStepI

        val lttSeek = SeekBar(this)
        lttSeek.max = lttSteps
        lttSeek.progress = ((Prefs.largeTradeThreshold(this).toInt() - lttMinI) / lttStepI).coerceIn(0, lttSteps)
        lttSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = (lttMinI + progress * lttStepI).toDouble()
                lttLabel.text = formatLtt(v)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                val v = (lttMinI + (sb?.progress ?: 0) * lttStepI).toDouble()
                Prefs.setLargeTradeThreshold(this@SettingsActivity, v)
            }
        })
        root.addView(lttSeek)

        val lttHint = TextView(this)
        lttHint.text = "Показывать в карточке сделки крупнее этого порога"
        lttHint.textSize = 13f
        lttHint.setTextColor(Prefs.textDim(this))
        lttHint.setPadding(0, 4, 0, 12)
        root.addView(lttHint)

        fun notifRow(label: String, type: String): Pair<Button, Button> {
            val l = TextView(this)
            l.text = label
            l.textSize = 16f
            l.setTextColor(Prefs.text(this))
            root.addView(l)

            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL

            val toggleLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            toggleLp.setMargins(3, 8, 3, 16)
            val toggle = Button(this)
            toggle.layoutParams = toggleLp
            toggle.setOnClickListener {
                Prefs.setNotifEnabled(this, type, !Prefs.notifEnabled(this, type))
                updateNotifButtons()
            }

            val sndLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            sndLp.setMargins(3, 8, 3, 16)
            val snd = Button(this)
            snd.layoutParams = sndLp
            snd.setOnClickListener { pickSound(pickCodeFor(type), Prefs.soundUriFor(this, type)) }

            row.addView(toggle)
            row.addView(snd)
            root.addView(row)
            return toggle to snd
        }

        val (lt, ls) = notifRow("Ликвидации", "liquidation")
        liqToggle = lt; liqSoundBtn = ls
        val (st, ss) = notifRow("Скорость (ускорение)", "speed")
        speedToggle = st; speedSoundBtn = ss
        val (vt, vs) = notifRow("Объём и оборот", "volume")
        volumeToggle = vt; volumeSoundBtn = vs

        // ===== Звук по умолчанию =====
        val soundLabel = TextView(this)
        soundLabel.text = "Звук по умолчанию (если не выбран выше)"
        soundLabel.textSize = 14f
        soundLabel.setTextColor(Prefs.textDim(this))
        soundLabel.setPadding(0, 16, 0, 8)
        root.addView(soundLabel)

        soundBtn = Button(this)
        soundBtn.setOnClickListener { pickSound(PICK_SOUND_MAIN, Prefs.soundUri(this)) }
        root.addView(soundBtn)

        val resetBtn = Button(this)
        resetBtn.text = "Сбросить звук по умолчанию"
        val resetLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        resetLp.setMargins(0, 16, 0, 0)
        resetBtn.layoutParams = resetLp
        resetBtn.setOnClickListener {
            Prefs.setSoundUri(this, null)
            updateSoundButtons()
            Toast.makeText(this, "Звук сброшен", Toast.LENGTH_SHORT).show()
        }
        root.addView(resetBtn)

        // ===== Анализ =====
        val btTitle = TextView(this)
        btTitle.text = "🔬 Анализ"
        btTitle.textSize = 20f
        btTitle.setTextColor(Prefs.text(this))
        btTitle.setPadding(0, 32, 0, 12)
        root.addView(btTitle)

        val btBtn = Button(this)
        btBtn.text = "🔬 Backtest сигналов"
        btBtn.textSize = 15f
        btBtn.isAllCaps = false
        btBtn.setTextColor(android.graphics.Color.WHITE)
        btBtn.background = pressedDrawable(
            android.graphics.Color.rgb(60, 90, 140),
            android.graphics.Color.rgb(85, 120, 175)
        )
        val btLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        btLp.setMargins(0, 0, 0, 12)
        btBtn.layoutParams = btLp
        btBtn.setOnClickListener {
            startActivity(Intent(this, BacktestActivity::class.java))
        }
        root.addView(btBtn)

        val analysisBtn = Button(this)
        analysisBtn.text = "📊 Анализ входов"
        analysisBtn.textSize = 15f
        analysisBtn.isAllCaps = false
        analysisBtn.setTextColor(android.graphics.Color.WHITE)
        analysisBtn.background = pressedDrawable(
            android.graphics.Color.rgb(40, 100, 60),
            android.graphics.Color.rgb(55, 130, 80)
        )
        val anLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        anLp.setMargins(0, 0, 0, 16)
        analysisBtn.layoutParams = anLp
        analysisBtn.setOnClickListener {
            startActivity(Intent(this, AnalysisActivity::class.java))
        }
        root.addView(analysisBtn)

        // ===== Авто-торговля =====
        val autoTitle = TextView(this)
        autoTitle.text = "🤖 Авто-торговля"
        autoTitle.textSize = 20f
        autoTitle.setTypeface(null, android.graphics.Typeface.BOLD)
        autoTitle.setTextColor(Prefs.text(this))
        autoTitle.setPadding(0, 24, 0, 12)
        root.addView(autoTitle)

        autoToggle = Button(this)
        autoToggle.textSize = 15f
        autoToggle.isAllCaps = false
        autoToggle.setOnClickListener {
            Prefs.setAutoTradingEnabled(this, !Prefs.autoTradingEnabled(this))
            updateAutoToggle()
        }
        root.addView(autoToggle)

        val autoHint = TextView(this)
        autoHint.text = "Открывает виртуальные сделки автоматически, когда каскад ликвидаций затухает. Направление — по сигналу, если нет — против горящей стороны. Размер/SL/TP — из настроек."
        autoHint.textSize = 13f
        autoHint.setTextColor(Prefs.textDim(this))
        autoHint.setPadding(0, 4, 0, 20)
        root.addView(autoHint)

        val autoDirLabel = TextView(this)
        autoDirLabel.text = "Направление при отсутствии сигнала:"
        autoDirLabel.textSize = 14f
        autoDirLabel.setTextColor(Prefs.textDim(this))
        autoDirLabel.setPadding(0, 8, 0, 6)
        root.addView(autoDirLabel)

        val autoDirRow = LinearLayout(this)
        autoDirRow.orientation = LinearLayout.HORIZONTAL

        autoWithBtn = Button(this)
        autoWithBtn.text = "За горящими"
        autoWithBtn.textSize = 14f
        autoWithBtn.isAllCaps = false
        autoWithBtn.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(3, 0, 3, 0) }
        autoWithBtn.setOnClickListener {
            Prefs.setAutoDirectionWith(this, true)
            updateAutoDirectionButtons()
        }
        autoDirRow.addView(autoWithBtn)

        autoAgainstBtn = Button(this)
        autoAgainstBtn.text = "Против горящих"
        autoAgainstBtn.textSize = 14f
        autoAgainstBtn.isAllCaps = false
        autoAgainstBtn.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(3, 0, 3, 0) }
        autoAgainstBtn.setOnClickListener {
            Prefs.setAutoDirectionWith(this, false)
            updateAutoDirectionButtons()
        }
        autoDirRow.addView(autoAgainstBtn)

        root.addView(autoDirRow)

        val autoDirHint = TextView(this)
        autoDirHint.text = "«За горящими» — лонг при горящих лонгах, шорт при горящих шортах (на отскок). «Против» — наоборот. Сделки помечаются AUTO_WITH / AUTO_AGAINST в статистике."
        autoDirHint.textSize = 12f
        autoDirHint.setTextColor(Prefs.textDim(this))
        autoDirHint.setPadding(0, 4, 0, 20)
        root.addView(autoDirHint)

        val autoEnterLabel = TextView(this)
        autoEnterLabel.text = "Когда входить:"
        autoEnterLabel.textSize = 14f
        autoEnterLabel.setTextColor(Prefs.textDim(this))
        autoEnterLabel.setPadding(0, 8, 0, 6)
        root.addView(autoEnterLabel)

        val autoEnterRow = LinearLayout(this)
        autoEnterRow.orientation = LinearLayout.HORIZONTAL

        autoEnterFadeBtn = Button(this)
        autoEnterFadeBtn.text = "На затухании"
        autoEnterFadeBtn.textSize = 14f
        autoEnterFadeBtn.isAllCaps = false
        autoEnterFadeBtn.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(3, 0, 3, 0) }
        autoEnterFadeBtn.setOnClickListener {
            Prefs.setAutoEnterAtStart(this, false)
            updateAutoEnterButtons()
        }
        autoEnterRow.addView(autoEnterFadeBtn)

        autoEnterStartBtn = Button(this)
        autoEnterStartBtn.text = "В начале каскада"
        autoEnterStartBtn.textSize = 14f
        autoEnterStartBtn.isAllCaps = false
        autoEnterStartBtn.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(3, 0, 3, 0) }
        autoEnterStartBtn.setOnClickListener {
            Prefs.setAutoEnterAtStart(this, true)
            updateAutoEnterButtons()
        }
        autoEnterRow.addView(autoEnterStartBtn)

        root.addView(autoEnterRow)

        val autoEnterHint = TextView(this)
        autoEnterHint.text = "«На затухании» — вход после того, как каскад выдохся (отскок). «В начале каскада» — вход сразу при росте ликвидаций (движение). С направлением «Против горящих» — зарабатываешь на самом каскаде."
        autoEnterHint.textSize = 12f
        autoEnterHint.setTextColor(Prefs.textDim(this))
        autoEnterHint.setPadding(0, 4, 0, 20)
        root.addView(autoEnterHint)

        // ===== Назад =====
        val back = Button(this)
        back.text = "← Назад"
        val backLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        backLp.setMargins(0, 32, 0, 0)
        back.layoutParams = backLp
        back.setOnClickListener { finish() }
        root.addView(back)

        setContentView(scroll)

        listOf(
            darkBtn, lightBtn, candBtn, lineBtn,
            soundBtn, resetBtn, back,
            liqToggle, speedToggle, volumeToggle,
            liqSoundBtn, speedSoundBtn, volumeSoundBtn
        ).forEach { styleButton(it) }

        scanLimitButtons.forEach { (_, b) -> styleButton(b) }
                signalTfButtons.forEach { (_, b) -> styleButton(b) }
        updateSignalTfButtons(signalTfButtons, signalTfLabel)
        slButtons.forEach { (_, b) -> styleButton(b) }
        tpButtons.forEach { (_, b) -> styleButton(b) }
        timeoutButtons.forEach { (_, b) -> styleButton(b) }

        updateScanLimitButtons()
        updateBurstButtons(burstButtons, burstLabel)
        updateSlButtons()
        updateTpButtons()
        updateTimeoutButtons()
        updateNotifButtons()
        updateSoundButtons()
        updateAutoToggle()
        updateAutoDirectionButtons()
        updateAutoEnterButtons()
    }

    private fun formatTimeout(min: Int): String = when (min) {
        30 -> "30м"
        60 -> "1ч"
        240 -> "4ч"
        480 -> "8ч"
        1440 -> "24ч"
        else -> "${min}м"
    }

    private fun updateBurstButtons(
        buttons: List<Pair<Double, Button>>,
        label: TextView
    ) {
        val current = Prefs.deltaBurstThreshold(this)
        label.text = "Порог толчка: ${(current / 1000).toInt()}K"
        buttons.forEach { (v, b) ->
            if (Math.abs(v - current) < 1.0) {
                b.background = pressedDrawable(Color.rgb(60, 90, 140), Color.rgb(85, 120, 175))
                b.setTextColor(Color.WHITE)
            } else {
                b.background = pressedDrawable(Prefs.buttonOff(this), Prefs.buttonPressed(this))
                b.setTextColor(Prefs.textDim(this))
            }
        }
    }

    private fun updateScanLimitButtons() {
        val current = Prefs.scanLimit(this)
        scanLimitLabel.text = "Сканировать монет: $current"
        scanLimitButtons.forEach { (n, b) ->
            if (n == current) {
                b.background = pressedDrawable(Color.rgb(60, 90, 140), Color.rgb(85, 120, 175))
                b.setTextColor(Color.WHITE)
            } else {
                b.background = pressedDrawable(Prefs.buttonOff(this), Prefs.buttonPressed(this))
                b.setTextColor(Prefs.textDim(this))
            }
        }
    }

    private fun updateSlButtons() {
        val current = Prefs.paperSlPercent(this)
        slLabel.text = "SL (стоп-лосс): ${current}%"
        slButtons.forEach { (v, b) ->
            if (Math.abs(v - current) < 0.001) {
                b.background = pressedDrawable(Color.rgb(140, 50, 50), Color.rgb(180, 70, 70))
                b.setTextColor(Color.WHITE)
            } else {
                b.background = pressedDrawable(Prefs.buttonOff(this), Prefs.buttonPressed(this))
                b.setTextColor(Prefs.textDim(this))
            }
        }
    }

    private fun updateTpButtons() {
        val current = Prefs.paperTpPercent(this)
        tpLabel.text = "TP (тейк-профит): ${current}%"
        tpButtons.forEach { (v, b) ->
            if (Math.abs(v - current) < 0.001) {
                b.background = pressedDrawable(Color.rgb(40, 100, 60), Color.rgb(55, 130, 80))
                b.setTextColor(Color.WHITE)
            } else {
                b.background = pressedDrawable(Prefs.buttonOff(this), Prefs.buttonPressed(this))
                b.setTextColor(Prefs.textDim(this))
            }
        }
    }

    private fun updateTimeoutButtons() {
        val current = Prefs.paperTimeoutMinutes(this)
        timeoutLabel.text = "Таймаут сделки: ${formatTimeout(current)}"
        timeoutButtons.forEach { (v, b) ->
            if (v == current) {
                b.background = pressedDrawable(Color.rgb(60, 90, 140), Color.rgb(85, 120, 175))
                b.setTextColor(Color.WHITE)
            } else {
                b.background = pressedDrawable(Prefs.buttonOff(this), Prefs.buttonPressed(this))
                b.setTextColor(Prefs.textDim(this))
            }
        }
    }

    private fun pickCodeFor(type: String): Int = when (type) {
        "liquidation" -> PICK_SOUND_LIQ
        "speed" -> PICK_SOUND_SPEED
        "volume" -> PICK_SOUND_VOLUME
        else -> PICK_SOUND_MAIN
    }
    private fun updateAutoEnterButtons() {
        updatePair(autoEnterFadeBtn, autoEnterStartBtn, !Prefs.autoEnterAtStart(this))
    }

    private fun updateAutoDirectionButtons() {
        updatePair(autoWithBtn, autoAgainstBtn, Prefs.autoDirectionWith(this))
    }

    private fun updateAutoToggle() {
        applyToggleStyle(autoToggle, Prefs.autoTradingEnabled(this))
    }


    private fun updateNotifButtons() {
        applyToggleStyle(liqToggle, Prefs.notifEnabled(this, "liquidation"))
        applyToggleStyle(speedToggle, Prefs.notifEnabled(this, "speed"))
        applyToggleStyle(volumeToggle, Prefs.notifEnabled(this, "volume"))
    }

    private fun updateSoundButtons() {
        liqSoundBtn.text = "🔊 ${Prefs.soundDisplayName(this, Prefs.soundUriFor(this, "liquidation"))}"
        speedSoundBtn.text = "🔊 ${Prefs.soundDisplayName(this, Prefs.soundUriFor(this, "speed"))}"
        volumeSoundBtn.text = "🔊 ${Prefs.soundDisplayName(this, Prefs.soundUriFor(this, "volume"))}"
        soundBtn.text = "🔊 ${Prefs.soundDisplayName(this, Prefs.soundUri(this))}"
    }

    private fun applyToggleStyle(b: Button, on: Boolean) {
        if (on) {
            b.text = "✅ Вкл"
            b.background = pressedDrawable(Color.rgb(40, 100, 60), Color.rgb(55, 130, 80))
            b.setTextColor(Color.WHITE)
        } else {
            b.text = "❌ Выкл"
            b.background = pressedDrawable(Prefs.buttonOff(this), Prefs.buttonPressed(this))
            b.setTextColor(Prefs.textDim(this))
        }
    }

    private fun updatePair(a: Button, b: Button, firstActive: Boolean) {
        applyActive(a, firstActive)
        applyActive(b, !firstActive)
    }

    private fun applyActive(b: Button, active: Boolean) {
        if (active) {
            b.background = pressedDrawable(Color.rgb(60, 90, 140), Color.rgb(85, 120, 175))
            b.setTextColor(Color.WHITE)
        } else {
            b.background = pressedDrawable(Prefs.buttonOff(this), Prefs.buttonPressed(this))
            b.setTextColor(Prefs.textDim(this))
        }
        b.isAllCaps = false
    }

    private fun pickSound(code: Int, current: Uri?) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try {
            startActivityForResult(intent, code)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Файловый менеджер не найден", Toast.LENGTH_SHORT).show()
        }
    }

    private fun styleButton(b: Button) {
        b.background = pressedDrawable(Prefs.buttonOff(this), Prefs.buttonPressed(this))
        b.isAllCaps = false
        b.setTextColor(Prefs.text(this))
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {}

        when (requestCode) {
            PICK_SOUND_MAIN -> Prefs.setSoundUri(this, uri)
            PICK_SOUND_LIQ -> Prefs.setSoundUriFor(this, "liquidation", uri)
            PICK_SOUND_SPEED -> Prefs.setSoundUriFor(this, "speed", uri)
            PICK_SOUND_VOLUME -> Prefs.setSoundUriFor(this, "volume", uri)
        }
        playPreview(uri)
        updateSoundButtons()
    }

    private fun playPreview(uri: Uri) {
        try {
            RingtoneManager.getRingtone(applicationContext, uri)?.play()
        } catch (_: Exception) {}
    }
        private fun updateSignalTfButtons(
        buttons: List<Pair<String, Button>>,
        label: TextView
    ) {
        val current = Prefs.signalTf(this)
        val displayLabel = when (current) {
            "1" -> "1м"
            "5" -> "5м"
            "15" -> "15м"
            "60" -> "1ч"
            else -> "1м"
        }
        label.text = "Сигналы на TF: $displayLabel"

        buttons.forEach { (tf, b) ->
            if (tf == current) {
                b.background = pressedDrawable(Color.rgb(60, 90, 140), Color.rgb(85, 120, 175))
                b.setTextColor(Color.WHITE)
            } else {
                b.background = pressedDrawable(Prefs.buttonOff(this), Prefs.buttonPressed(this))
                b.setTextColor(Prefs.textDim(this))
            }
        }
    }
}