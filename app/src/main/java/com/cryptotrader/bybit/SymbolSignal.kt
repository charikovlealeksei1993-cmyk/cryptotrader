package com.cryptotrader.bybit

data class SymbolSignal(
    val type: String,
    val strength: String,
    val candleTime: Long,
    val detectedAt: Long,
    val price: Double
)