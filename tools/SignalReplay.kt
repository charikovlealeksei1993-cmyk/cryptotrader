package com.cryptotrader.bybit

import java.io.File

fun main() {
    val candles = mutableListOf<Candle>()
    val flows = mutableListOf<List<Pair<Double, Double>>>()
    File("replay-input.csv").forEachLine { line ->
        val a = line.split(",")
        candles.add(Candle(a[0].toLong(),a[1].toDouble(),a[2].toDouble(),a[3].toDouble(),a[4].toDouble(),a[5].toDouble()))
        flows.add(a[7].split(";").map { val v=it.split(":"); v[0].toDouble() to v[1].toDouble() })
    }
    val results = File("signal-results.csv")
    results.writeText("time,side,strength,pattern,entry,return5,return15,return60\n")
    val groups = mutableMapOf<String, MutableList<List<Double>>>()
    for (i in 6 until candles.size-60) {
        if ((i-5..i+60).any { candles[it].time-candles[it-1].time != 60000L }) continue
        val current = flows[i]
        val previous = flows[i-1].takeLast((5000-current.size).coerceAtLeast(0))
        val delta = if(previous.isNotEmpty()) current.sumOf { it.second } else null
        val cvd = delta?.minus(previous.sumOf { it.second })
        val prevUsd = previous.sumOf { it.first }
        val speed = if(prevUsd>0) current.sumOf { it.first } / prevUsd else null
        val entry = candles[i+1].open
        // Placeholder forming candle contains no future OHLC data.
        val forming = Candle(candles[i].time+60000,entry,entry,entry,entry,0.0)
        val signal = SignalDetector.detectReversalSignal(candles.subList(i-6,i+1)+forming,null,0.0,delta,cvd,speed,null,1)
        if(signal.type.isEmpty()) continue
        val sign = if(signal.type=="long") 1 else -1
        val returns = listOf(5,15,60).map { sign*(candles[i+it].close/entry-1)*100 }
        results.appendText(listOf(signal.candleTime,signal.type,signal.strength,signal.pattern,entry,*returns.toTypedArray()).joinToString(",")+"\n")
        groups.getOrPut(signal.strength+" "+signal.type){mutableListOf()}.add(returns)
    }
    val report = File("signal-summary.txt")
    report.writeText("BTCUSDT M1; candles="+candles.size+"; next-open entry; gross directional returns; historical trade replay approximates live scan timing; no fees or SL/TP.\n")
    for((group,rows) in groups.toSortedMap()) {
        report.appendText("$group: n=${rows.size}\n")
        for((j,h) in listOf(5,15,60).withIndex()) {
            val values=rows.map{it[j]}
            report.appendText("  ${h}m: positive=${100.0*values.count{it>0}/values.size}% mean=${values.average()}%\n")
        }
    }
    print(report.readText())
}
