package com.cryptotrader.bybit

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class AboutActivity : Activity() {

    private val TELEGRAM_URL = "https://t.me/lealeksei1993"

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
                @Suppress("DEPRECATION") top = insets.systemWindowInsetTop
                @Suppress("DEPRECATION") bottom = insets.systemWindowInsetBottom
            }
            v.setPadding(0, top, 0, bottom)
            insets
        }

        // ===== Заголовок =====
        val headerRow = LinearLayout(this)
        headerRow.orientation = LinearLayout.HORIZONTAL
        headerRow.gravity = Gravity.CENTER_VERTICAL
        headerRow.setPadding(0, 0, 0, 8)

        val title = TextView(this)
        title.text = "ℹ️ О сканере"
        title.textSize = 26f
        title.setTextColor(Prefs.text(this))
        title.setTypeface(null, Typeface.BOLD)
        title.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        headerRow.addView(title)

        val versionView = TextView(this)
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
        } catch (_: Exception) { "1.0" }
        versionView.text = "v$versionName"
        versionView.textSize = 14f
        versionView.setTextColor(Prefs.textDim(this))
        headerRow.addView(versionView)

        root.addView(headerRow)

        // Идентификатор выпуска позволяет отличить установленную сборку от старой.
        addSection(
            root,
            "📱 Версия приложения",
            "Версия: $versionName\n" +
            "Номер сборки: 3\n" +
            "Дата выпуска: 22.11.2026\n" +
            "Идентификатор: BG-20261122-02"
        )


        val subtitle = TextView(this)
        subtitle.text = "Сканер рынка Bybit с поиском точек входа и виртуальным портфелем"
        subtitle.textSize = 14f
        subtitle.setTextColor(Prefs.textDim(this))
        subtitle.setPadding(0, 0, 0, 16)
        root.addView(subtitle)

        // ===== Источник данных =====
        addSection(
            root,
            "📊 Источник данных",
            "Биржа Bybit (linear futures).\n\n" +
            "Цены, объёмы, открытый интерес, стакан, ликвидации и сделки " +
            "поступают напрямую из публичного API Bybit в реальном времени " +
            "через HTTP и WebSocket."
        )

        // ===== Два типа сигналов =====
        val signalsTitle = TextView(this)
        signalsTitle.text = "🎯 Сигналы"
        signalsTitle.textSize = 18f
        signalsTitle.setTextColor(Prefs.text(this))
        signalsTitle.setTypeface(null, Typeface.BOLD)
        signalsTitle.setPadding(0, 16, 0, 8)
        root.addView(signalsTitle)

        // 1. Сигнал на каскаде ликвидаций
        addSignalBlock(
            root,
            "🟢 ВХОД (на каскаде ликвидаций)",
            "Свеча-разворот (один из 22 паттернов) после затухания каскада " +
            "ликвидаций при перегруженной толпе.\n\n" +
            "• 🟢 ВХОД — сильный сигнал (объём + позиция в диапазоне)\n" +
            "• 🟡 Возможен вход — слабый (объём/позиция не дотянули)\n\n" +
            "Работает на любом TF из настроек (M1/M5/M15/M1H)."
        )

        // 2. Сигнал накопления
        addSignalBlock(
            root,
            "🟡 НАКОПЛЕНИЕ (без ликвидаций)",
            "Кто-то тихо набирает позицию, не двигая цену. " +
            "Проверка каждые 5 минут по последним 45 минутам:\n\n" +
            "• OI растёт ≥ +1% (слабое) / +2% (среднее) / +3.5% (сильное)\n" +
            "• Цена стоит в узком диапазоне (< 1.2%)\n" +
            "• Дельта (buy/sell) перекошена в одну сторону\n\n" +
            "Уровни: 🟡 слабое · 🟢 среднее · 🔥 сильное\n" +
            "Направление: В ЛОНГ / В ШОРТ\n\n" +
            "Работает на M5 (склеивается из M1). Требует signalTf = M1."
        )

        // ===== Расшифровка карточки =====
        addSection(
            root,
            "📖 Что в карточке",
            "Карточка монеты — это снимок текущей ситуации. Сверху вниз:"
        )

        addSubsection(root, "Цена и оборот", listOf(
            "🚀 — резкий скачок цены/объёма",
            "🚀🚀 / 🚀🚀🚀 — 2 или 3+ сигнала сразу",
            "💰 — оборот за 24ч и 1ч"
        ))

        addSubsection(root, "Накопление", listOf(
            "🟡 НАКОПЛЕНИЕ В ЛОНГ/ШОРТ — тихий набор позиции",
            "Детали: OI +X% (Y/9 баров), диапазон, дельта"
        ))

        addSubsection(root, "Дельта и поток денег", listOf(
            "Δ 1м — реальное соотношение buy/sell за минуту",
            "🟢 New Money ↑ — новые деньги входят в лонг",
            "🔴 New Money ↓ — новые деньги входят в шорт",
            "🔴 Short Squeeze — шорты закрываются, цена вверх",
            "🟢 Long Squeeze — лонги закрываются, цена вниз",
            "⚪ Churn — просто шум, без направления"
        ))

        addSubsection(root, "Ликвидации и зона выноса", listOf(
            "💥 Ликвидации 15м — сумма за 15 минут",
            "🐂 лонги / 🐻 шорты — кого выносит",
            "🔥 растёт · 🪫 затухает · ➡️ стабильно — тренд",
            "🎯 Зона выноса — кластер ликвидаций как магнит для цены",
            "   (показывается, только если цена ещё не дошла до зоны)"
        ))

        addSubsection(root, "Позиции толпы", listOf(
            "📊 OI — открытый интерес и его изменение",
            "   ↗️ ускоряется · ↘️ замедляется",
            "📊 Лонги/Шорты — процент лонгов и шортов",
            "⚖️ Стакан — перевес bid/ask",
            "   🟢 покупатели · 🔴 продавцы · ⚪ баланс",
            "Bid/Ask wall — крупнейшие заявки (только если > $1000)"
        ))

        addSubsection(root, "Скорость", listOf(
            "⚡ свечная — скорость от открытия бара",
            "   ⚡⚡⚡ ускорение · 🪫 затухание · ⚡ стабильно",
            "·↑ по сделкам — медиана тиков за 60 сек",
            "   🔥↑ быстро вверх · 🔥↓ быстро вниз",
            "   ·↑ слабо вверх · ·↓ слабо вниз · · стоит",
            "   (X сделок) — сколько учтено в медиане"
        ))

        addSubsection(root, "История и бумажная торговля", listOf(
            "📜 — был сигнал N времени назад (STRONG/WEAK)",
            "📈 — открытая виртуальная сделка (LONG/SHORT, P&L)",
            "💡 — подсказка BUY/SELL, если сделки нет"
        ))

        // ===== Paper Trading =====
        addSection(
            root,
            "🎮 Paper Trading (виртуальный портфель)",
            "Открывайте сделки кнопками BUY/SELL прямо в карточке. " +
            "Стартовый баланс $10,000.\n\n" +
            "Авто-закрытие:\n" +
            "• SL (стоп-лосс) — 2%\n" +
            "• TP (тейк-профит) — 5%\n" +
            "• Timeout — 4 часа\n\n" +
            "Всё настраивается в Настройках. Сделки сохраняются в истории " +
            "и доступны в разделе «Портфель» (иконка 💰)."
        )

        // ===== Анализ =====
        addSection(
            root,
            "🔬 Анализ и Backtest",
            "🔬 Backtest — прогон истории свечей по 22 паттернам. " +
            "Показывает winrate, средний P&L, распределение по TP/SL/timeout.\n\n" +
            "📊 Анализ входов — статистика закрытых paper-сделок по контексту: " +
            "с каскадом / без каскада, по силе сигнала, по flow, по OI, по стакану. " +
            "Помогает понять, какие условия дают профит, а какие — нет.\n\n" +
            "Оба раздела — в Настройках."
        )

        // ===== Настройки =====
        addSection(
            root,
            "⚙ Что можно настроить",
            "• TF сигналов (M1 / M5 / M15 / M1H)\n" +
            "• Сколько монет сканировать (5 / 10 / 15 / 20)\n" +
            "• Порог ликвидаций для уведомлений ($10K–$50K)\n" +
            "• SL / TP / Timeout для paper-сделок\n" +
            "• Тип графика (свечи / линия)\n" +
            "• История свечей на графике\n" +
            "• Тёмная / светлая тема\n" +
            "• Звуки уведомлений (отдельные для каждого типа)\n" +
            "• Вкл/выкл уведомлений по типам (ликвидации, скорость, объём)"
        )

        // ===== Управление =====
        addSection(
            root,
            "🎛 Управление карточкой",
            "• Тап по карточке — открыть график\n" +
            "• 📋 — скопировать всю карточку в буфер (для отправки)\n" +
            "• 📌 — закрепить монету (до 3 штук, будут всегда сверху)\n" +
            "• 📍 — монета закреплена\n\n" +
            "На графике:\n" +
            "• Свайп — прокрутка\n" +
            "• Двумя пальцами — масштаб\n" +
            "• Двойной тап — сброс масштаба\n" +
            "• Рамки с ⬆⬇ — сигналы входа (история)"
        )

        // ===== Контакты =====
        val contactsTitle = TextView(this)
        contactsTitle.text = "💬 Контакты"
        contactsTitle.textSize = 18f
        contactsTitle.setTextColor(Prefs.text(this))
        contactsTitle.setTypeface(null, Typeface.BOLD)
        contactsTitle.setPadding(0, 16, 0, 8)
        root.addView(contactsTitle)

        val contactsBody = TextView(this)
        contactsBody.text = "Telegram: @lealeksei1993\n\nПишите по вопросам, багам, идеям."
        contactsBody.textSize = 15f
        contactsBody.setTextColor(Prefs.textDim(this))
        contactsBody.setLineSpacing(4f, 1f)
        contactsBody.setPadding(0, 0, 0, 12)
        root.addView(contactsBody)

        val telegramBtn = Button(this)
        telegramBtn.text = "💬 Написать в Telegram"
        telegramBtn.textSize = 16f
        telegramBtn.isAllCaps = false
        telegramBtn.setTextColor(Color.WHITE)
        telegramBtn.background = pressedDrawable(Color.rgb(41, 150, 210), Color.rgb(30, 120, 170))
        val tgLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        tgLp.setMargins(0, 0, 0, 16)
        telegramBtn.layoutParams = tgLp
        telegramBtn.setOnClickListener { openTelegram() }
        root.addView(telegramBtn)

        // ===== Дисклеймер =====
        addSection(
            root,
            "⚠️ Дисклеймер",
            "Приложение — информационный инструмент для анализа рынка.\n\n" +
            "Мы не даём инвестиционных рекомендаций и не несём ответственности " +
            "за ваши торговые решения. Торговля на бирже связана с риском " +
            "потери средств. Все сделки в приложении — виртуальные (paper)."
        )

        // ===== Данные =====
        addSection(
            root,
            "🔒 Данные",
            "Приложение не собирает персональные данные.\n\n" +
            "Используется анонимная аналитика (Firebase) для улучшения работы: " +
            "какие экраны открываются, сколько пользователей активно. " +
            "Никакой личной информации, никаких привязок к аккаунту."
        )

        // ===== Play Market =====
        val playBtn = Button(this)
        playBtn.text = "⭐ Оценить в Play Market"
        playBtn.textSize = 16f
        playBtn.isAllCaps = false
        playBtn.setTextColor(Color.WHITE)
        playBtn.background = pressedDrawable(Color.rgb(40, 140, 80), Color.rgb(30, 110, 65))
        val pLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        pLp.setMargins(0, 24, 0, 16)
        playBtn.layoutParams = pLp
        playBtn.setOnClickListener { openPlayMarket() }
        root.addView(playBtn)

        // ===== Назад =====
        val back = Button(this)
        back.text = "← Назад"
        back.textSize = 16f
        back.isAllCaps = false
        back.setTextColor(Prefs.text(this))
        back.background = pressedDrawable(Prefs.buttonOff(this), Prefs.buttonPressed(this))
        val backLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        backLp.setMargins(0, 16, 0, 0)
        back.layoutParams = backLp
        back.setOnClickListener { finish() }
        root.addView(back)

        setContentView(scroll)
    }

    private fun addSection(parent: LinearLayout, title: String, body: String) {
        val titleView = TextView(this)
        titleView.text = title
        titleView.textSize = 18f
        titleView.setTextColor(Prefs.text(this))
        titleView.setTypeface(null, Typeface.BOLD)
        titleView.setPadding(0, 16, 0, 8)
        parent.addView(titleView)

        val bodyView = TextView(this)
        bodyView.text = body
        bodyView.textSize = 15f
        bodyView.setTextColor(Prefs.textDim(this))
        bodyView.setLineSpacing(4f, 1f)
        bodyView.setPadding(0, 0, 0, 8)
        parent.addView(bodyView)
    }

    private fun addSignalBlock(parent: LinearLayout, title: String, body: String) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setBackgroundColor(Prefs.row(this))
        box.setPadding(16, 12, 16, 12)
        val boxLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        boxLp.setMargins(0, 0, 0, 10)
        box.layoutParams = boxLp

        val titleView = TextView(this)
        titleView.text = title
        titleView.textSize = 16f
        titleView.setTextColor(Prefs.text(this))
        titleView.setTypeface(null, Typeface.BOLD)
        titleView.setPadding(0, 0, 0, 6)
        box.addView(titleView)

        val bodyView = TextView(this)
        bodyView.text = body
        bodyView.textSize = 14f
        bodyView.setTextColor(Prefs.textDim(this))
        bodyView.setLineSpacing(3f, 1f)
        box.addView(bodyView)

        parent.addView(box)
    }

    private fun addSubsection(parent: LinearLayout, title: String, lines: List<String>) {
        val titleView = TextView(this)
        titleView.text = title
        titleView.textSize = 15f
        titleView.setTextColor(Prefs.text(this))
        titleView.setTypeface(null, Typeface.BOLD)
        titleView.setPadding(0, 10, 0, 4)
        parent.addView(titleView)

        val body = StringBuilder()
        lines.forEach { body.appendLine(it) }

        val bodyView = TextView(this)
        bodyView.text = body.toString().trim()
        bodyView.textSize = 14f
        bodyView.setTextColor(Prefs.textDim(this))
        bodyView.setLineSpacing(3f, 1f)
        bodyView.setPadding(0, 0, 0, 4)
        parent.addView(bodyView)
    }

    private fun openTelegram() {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(TELEGRAM_URL))
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Telegram не найден", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Не удалось открыть ссылку", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openPlayMarket() {
        try {
            val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
            startActivity(marketIntent)
        } catch (_: ActivityNotFoundException) {
            try {
                val webIntent = Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=$packageName"))
                startActivity(webIntent)
            } catch (_: Exception) {
                Toast.makeText(this, "Play Market не найден", Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {
            Toast.makeText(this, "Не удалось открыть Play Market", Toast.LENGTH_SHORT).show()
        }
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
