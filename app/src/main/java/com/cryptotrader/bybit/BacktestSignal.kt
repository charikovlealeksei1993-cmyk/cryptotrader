package com.cryptotrader.bybit

data class BacktestSignal(
    val time: Long,
    val type: String,
    val strength: String,
    val entryPrice: Double,
    val pattern: String = "",
    val pnl15m: Double? = null,
    val pnl1h: Double? = null,
    val pnl4h: Double? = null,
    val hasPseudoCascade: Boolean = false,
    val pnlWithSlTp: Double? = null,
    val exitReason: String = "",
    val exitBars: Int = 0
) {
    fun pnlFor(horizon: String): Double? = when (horizon) {
        "15m" -> pnl15m
        "1h" -> pnl1h
        "4h" -> pnl4h
        else -> null
    }

    val isLong: Boolean get() = type == "long"
}

data class BacktestReport(
    val symbol: String,
    val tf: String,
    val days: Int,
    val candlesCount: Int,
    val signals: List<BacktestSignal>,
    val errorMessage: String? = null
) {
    data class HorizonStats(
        val horizon: String,
        val total: Int,
        val wins: Int,
        val losses: Int,
        val breakeven: Int,
        val winRate: Double,
        val avgPnl: Double,
        val bestPnl: Double,
        val worstPnl: Double,
        val totalPnl100: Double
    )

    fun statsFor(horizon: String): HorizonStats {
        val pnls = signals.mapNotNull { it.pnlFor(horizon) }
        if (pnls.isEmpty()) {
            return HorizonStats(horizon, 0, 0, 0, 0, 0.0, 0.0, 0.0, 0.0, 0.0)
        }
        var wins = 0; var losses = 0; var be = 0
        var sum = 0.0
        var best = Double.NEGATIVE_INFINITY
        var worst = Double.POSITIVE_INFINITY
        for (p in pnls) {
            sum += p
            if (p > 0.05) wins++
            else if (p < -0.05) losses++
            else be++
            if (p > best) best = p
            if (p < worst) worst = p
        }
        return HorizonStats(
            horizon = horizon,
            total = pnls.size,
            wins = wins,
            losses = losses,
            breakeven = be,
            winRate = if (pnls.isNotEmpty()) wins.toDouble() / pnls.size * 100 else 0.0,
            avgPnl = sum / pnls.size,
            bestPnl = if (best > Double.NEGATIVE_INFINITY) best else 0.0,
            worstPnl = if (worst < Double.POSITIVE_INFINITY) worst else 0.0,
            totalPnl100 = pnls.sum()
        )
    }

    val strongCount: Int get() = signals.count { it.strength == "strong" }
    val weakCount: Int get() = signals.count { it.strength == "weak" }
    val longCount: Int get() = signals.count { it.type == "long" }
    val shortCount: Int get() = signals.count { it.type == "short" }
    val withCascadeCount: Int get() = signals.count { it.hasPseudoCascade }

    fun statsForFiltered(horizon: String, filter: (BacktestSignal) -> Boolean): HorizonStats {
        val pnls = signals.filter(filter).mapNotNull { it.pnlFor(horizon) }
        if (pnls.isEmpty()) {
            return HorizonStats(horizon, 0, 0, 0, 0, 0.0, 0.0, 0.0, 0.0, 0.0)
        }
        var wins = 0; var losses = 0; var be = 0
        var sum = 0.0
        var best = Double.NEGATIVE_INFINITY
        var worst = Double.POSITIVE_INFINITY
        for (p in pnls) {
            sum += p
            if (p > 0.05) wins++
            else if (p < -0.05) losses++
            else be++
            if (p > best) best = p
            if (p < worst) worst = p
        }
        return HorizonStats(
            horizon = horizon,
            total = pnls.size,
            wins = wins,
            losses = losses,
            breakeven = be,
            winRate = if (pnls.isNotEmpty()) wins.toDouble() / pnls.size * 100 else 0.0,
            avgPnl = sum / pnls.size,
            bestPnl = if (best > Double.NEGATIVE_INFINITY) best else 0.0,
            worstPnl = if (worst < Double.POSITIVE_INFINITY) worst else 0.0,
            totalPnl100 = pnls.sum()
        )
    }

    // Разбивка по паттернам (15м)
    data class PatternStats(
        val pattern: String,
        val total: Int,
        val wins: Int,
        val losses: Int,
        val winRate: Double,
        val avgPnl: Double,
        val totalPnl100: Double
    )

    fun statsByPattern(horizon: String): List<PatternStats> {
        val grouped = signals.groupBy { it.pattern.ifEmpty { "(без названия)" } }
        val result = ArrayList<PatternStats>()
        for ((pattern, sigs) in grouped) {
            val pnls = sigs.mapNotNull { it.pnlFor(horizon) }
            if (pnls.isEmpty()) continue
            var wins = 0; var losses = 0
            var sum = 0.0
            for (p in pnls) {
                sum += p
                if (p > 0.05) wins++ else if (p < -0.05) losses++
            }
            result.add(PatternStats(
                pattern = pattern,
                total = pnls.size,
                wins = wins,
                losses = losses,
                winRate = if (pnls.isNotEmpty()) wins.toDouble() / pnls.size * 100 else 0.0,
                avgPnl = sum / pnls.size,
                totalPnl100 = pnls.sum()
            ))
        }
        return result.sortedByDescending { it.winRate }
    }

    data class SlTpStats(
        val total: Int,
        val wins: Int,
        val losses: Int,
        val breakeven: Int,
        val winRate: Double,
        val avgPnl: Double,
        val totalPnl100: Double,
        val avgExitBars: Double,
        val tpCount: Int,
        val slCount: Int,
        val timeoutCount: Int,
        val maxDrawdown: Double,
        val profitFactor: Double
    )

    fun statsWithSlTp(): SlTpStats {
        val filtered = signals.filter { it.pnlWithSlTp != null }
        if (filtered.isEmpty()) {
            return SlTpStats(0, 0, 0, 0, 0.0, 0.0, 0.0, 0.0, 0, 0, 0, 0.0, 0.0)
        }
        var wins = 0; var losses = 0; var be = 0
        var sumWin = 0.0; var sumLoss = 0.0
        var totalBars = 0
        var tp = 0; var sl = 0; var timeout = 0
        var equity = 0.0
        var peak = 0.0
        var maxDD = 0.0
        for (s in filtered) {
            val pnl = s.pnlWithSlTp ?: 0.0
            totalBars += s.exitBars
            if (pnl > 0.05) { wins++; sumWin += pnl }
            else if (pnl < -0.05) { losses++; sumLoss += pnl }
            else be++
            when (s.exitReason) {
                "tp" -> tp++
                "sl" -> sl++
                "timeout" -> timeout++
            }
            equity += pnl
            if (equity > peak) peak = equity
            val dd = peak - equity
            if (dd > maxDD) maxDD = dd
        }
        val totalPnl = filtered.sumOf { it.pnlWithSlTp ?: 0.0 }
        return SlTpStats(
            total = filtered.size,
            wins = wins,
            losses = losses,
            breakeven = be,
            winRate = wins.toDouble() / filtered.size * 100,
            avgPnl = totalPnl / filtered.size,
            totalPnl100 = totalPnl,
            avgExitBars = totalBars.toDouble() / filtered.size,
            tpCount = tp,
            slCount = sl,
            timeoutCount = timeout,
            maxDrawdown = maxDD,
            profitFactor = if (sumLoss != 0.0) kotlin.math.abs(sumWin / sumLoss) else 0.0
        )
    }
}