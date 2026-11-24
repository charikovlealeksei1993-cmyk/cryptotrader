package com.cryptotrader.bybit

import java.io.File

fun main() {
    val candles = mutableListOf<Candle>()
    val flows = mutableListOf<Triple<Double?,Double?,Double?>>()
    var prior = emptyList<Pair<Double,Double>>()
    val entryEnd = java.time.Instant.parse("2026-11-09T00:00:00Z").toEpochMilli()
    File("replay-input.csv").forEachLine { line ->
        val a = line.split(",")
        candles.add(Candle(a[0].toLong(),a[1].toDouble(),a[2].toDouble(),a[3].toDouble(),a[4].toDouble(),a[5].toDouble()))
        val current = if(candles.last().time < entryEnd) a[7].split(";").map { val v=it.split(":"); v[0].toDouble() to v[1].toDouble() } else emptyList()
        val previous = prior.takeLast((5000-current.size).coerceAtLeast(0))
        val delta = if(previous.isNotEmpty()) current.sumOf { it.second } else null
        val cvd = delta?.minus(previous.sumOf { it.second })
        val prevUsd = previous.sumOf { it.first }
        flows.add(Triple(delta,cvd,if(prevUsd>0) current.sumOf { it.first } / prevUsd else null))
        prior = current
    }
    val results = File("signal-results.csv")
    results.writeText("time,strength,side,pattern,entry,exitTime,exitPrice,status,returnPct\n")
    val report = File("signal-summary.txt")
    val counts = mutableMapOf<String, Int>()
    val pnl = mutableMapOf<String, Double>()
    for (i in 6 until candles.size-1) {
        if ((i-5..i+1).any { candles[it].time-candles[it-1].time != 60000L }) continue
        if(candles[i+1].time >= entryEnd) break
        val (delta,cvd,speed) = flows[i]
        val entry = candles[i+1].open
        val forming = Candle(candles[i].time+60000,entry,entry,entry,entry,0.0)
        val signal = SignalDetector.detectReversalSignal(candles.subList(i-6,i+1)+forming,null,0.0,delta,cvd,speed,null,1)
        if(signal.strength !in listOf("weak","medium","strong") || signal.type !in listOf("long","short")) continue
        val long = signal.type=="long"
        val tp = entry * if(long) 1.05 else 0.95
        val sl = entry * if(long) 0.98 else 1.02
        var status = "open"
        var exitPrice = candles.last().close
        var exitTime = candles.last().time
        for(j in i+1 until candles.size) {
            val c = candles[j]
            if(c.time-candles[j-1].time != 60000L) { status="gap"; exitPrice=candles[j-1].close; exitTime=candles[j-1].time; break }
            val hitSl = if(long) c.low<=sl else c.high>=sl
            val hitTp = if(long) c.high>=tp else c.low<=tp
            if(hitSl || hitTp) {
                status = if(hitSl && hitTp) "ambiguous" else if(hitSl) "SL" else "TP"
                exitPrice = if(hitSl) sl else tp
                exitTime = c.time
                break
            }
        }
        val ret = (if(long) 1 else -1)*(exitPrice/entry-1)*100
        val key = signal.strength+" "+signal.type+" "+status
        counts[key]=(counts[key]?:0)+1
        pnl[key]=(pnl[key]?:0.0)+ret
        results.appendText(listOf(candles[i+1].time,signal.strength,signal.type,signal.pattern,entry,exitTime,exitPrice,status,ret).joinToString(",")+"\n")
    }
    report.writeText("BTCUSDT M1 all signal strengths; entries Oct 10-Nov 8 UTC; exit-only Nov 9-22 UTC; independent overlapping trades; TP 5%, SL 2%; no fees; next-open entry; open positions marked at last close, not realized; ambiguous candles conservatively SL; approximate live signal timing.\n")
    for((key,n) in counts.toSortedMap()) report.appendText("$key: count=$n, sumReturnPct=${pnl[key]}\n")
    print(report.readText())
}
