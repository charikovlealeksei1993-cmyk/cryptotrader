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
    private val PACKAGE_NAME = "com.cryptotrader.bybit"

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

        // === Заголовок с версией ===
        val headerRow = LinearLayout(this)
        headerRow.orientation = LinearLayout.HORIZONTAL
        headerRow.gravity = Gravity.CENTER_VERTICAL
        headerRow.setPadding(0, 0, 0, 24)

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
        } catch (_: Exception) {
            "1.0"
        }
        versionView.text = "v$versionName"
        versionView.textSize = 14f
        versionView.setTextColor(Prefs.textDim(this))
        headerRow.addView(versionView)

        root.addView(headerRow)

        // === Источник данных ===
        addSection(
            root,
            "📊 Источник данных",
            "Сканер работает с биржей Bybit (linear futures).\n\n" +
            "Все данные о ценах, объёмах, открытом интересе, " +
            "стакане и ликвидациях поступают напрямую из публичного API Bybit " +
            "в реальном времени."
        )

        // === Что отслеживается ===
        addSection(
            root,
            "🔍 Что отслеживается",
            "• Резкие скачки цены и объёма\n" +
            "• Ликвидации (каскады)\n" +
            "• Соотношение лонгов и шортов\n" +
            "• Открытый интерес (OI)\n" +
            "• Стакан (bid/ask walls)\n" +
            "• Крупные сделки"
        )

        // === Сигналы входа ===
        addSection(
            root,
            "🎯 Сигналы входа",
            "Сигналы 🟢 ВХОД формируются по свече-разворота " +
            "(Shooting Star / Hammer) после затухания каскада ликвидаций " +
            "при перегруженной толпе.\n\n" +
            "Сигнал — это не рекомендация к торговле. Решение принимаете вы."
        )

        // === Расшифровка иконок ===
        addSection(
            root,
            "📖 Расшифровка иконок",
            "🚀 Резкий скачок цены/объёма\n" +
            "🚀🚀 2 сигнала · 🚀🚀🚀 3+ сигналов\n" +
            "💥 Ликвидации выше порога\n" +
            "⚡ Ускорение цены · 🪫 Затухание\n" +
            "🐂 Ликвидированы лонги\n" +
            "🐻 Ликвидированы шорты\n" +
            "🔥 Каскад растёт · 🪫 Затухает\n" +
            "🐋 Крупные сделки (киты)\n" +
            "📈 OI растёт · 📉 OI падает · ➡️ Стоит\n" +
            "⚖️ Стакан: 🟢 покупатели · 🔴 продавцы · ⚪ баланс\n" +
            "🔺 BUY сигнал · 🔻 SELL сигнал\n" +
            "🟢 ВХОД — сигнал по свече-разворота\n" +
            "📌 Не закреплена · 📍 Закреплена\n" +
            "📋 Скопировать карточку"
        )

        // === Контакты ===
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

        // Кнопка Telegram
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

        // === Дисклеймер ===
        addSection(
            root,
            "⚠️ Дисклеймер",
            "Приложение является информационным инструментом для анализа рынка.\n\n" +
            "Мы не даём инвестиционных рекомендаций и не несём ответственности " +
            "за ваши торговые решения. Торговля на бирже связана с риском потери средств."
        )

        // === Данные ===
        addSection(
            root,
            "🔒 Данные",
            "Приложение не собирает персональные данные.\n" +
            "Используется анонимная аналитика (Firebase) для улучшения работы: " +
            "какие экраны открываются, сколько пользователей онлайн."
        )

        // === Кнопка Play Market ===
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

        // === Кнопка Назад ===
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
            val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PACKAGE_NAME"))
            startActivity(marketIntent)
        } catch (_: ActivityNotFoundException) {
            try {
                val webIntent = Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=$PACKAGE_NAME"))
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