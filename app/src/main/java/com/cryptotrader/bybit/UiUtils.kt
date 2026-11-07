package com.cryptotrader.bybit

object UiUtils {

    fun formatMoney(v: Double): String = when {
        v >= 1_000_000_000 -> "${"%.2f".format(v / 1_000_000_000)}B"
        v >= 1_000_000 -> "${"%.2f".format(v / 1_000_000)}M"
        v >= 1_000 -> "${"%.2f".format(v / 1_000)}K"
        else -> "${"%.0f".format(v)}"
    }
}