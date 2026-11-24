package com.cryptotrader.bybit

import java.io.File

fun main() {
    val candles = mutableListOf<Candle>()
    val entryEnd = java.time.Instant.parse("2026-11-09T00:00:00Z").toEpochMilli()
    File("replay-input.csv").forEachLine { line ->
        val a = line.split(",")
        candles.add(Candle(a[0].toLong(),a[1].toDouble(),a[2].toDouble(),a[3].toDouble(),a[4].toDouble(),a[5].toDouble()))
    }
    val bars = candles.chunked(5).filter { chunk ->
        chunk.size == 5 && chunk.first().time % 300000L == 0L &&
            chunk.zipWithNext().all { (a,b) -> b.time-a.time == 60000L }
    }.map { chunk -> Candle(chunk.first().time,chunk.first().open,chunk.maxOf { it.high },chunk.minOf { it.low },chunk.last().close,chunk.sumOf { it.volume }) }
    val minuteIndex = candles.withIndex().associate { it.value.time to it.index }
    val results = File("signal-results.csv")
    results.writeText("time,strength,side,pattern,entry,exitTime,exitPrice,status,returnPct\n")
    val report = File("signal-summary.txt")
    val counts = mutableMapOf<String, Int>()
    val pnl = mutableMapOf<String, Double>()
    for (i in 6 until bars.size-1) {
        if ((i-5..i+1).any { bars[it].time-bars[it-1].time != 300000L }) continue
        if(bars[i+1].time >= entryEnd) break
        val entryIndex = minuteIndex.getValue(bars[i+1].time)
        val entry = bars[i+1].open
        val forming = Candle(bars[i].time+300000L,entry,entry,entry,entry,0.0)
        val signal = SignalDetector.detectReversalSignal(bars.subList(i-6,i+1)+forming,null,0.0,null,null,null,null,5)
        if(signal.strength !in listOf("weak","medium","strong") || signal.type !in listOf("long","short")) continue
        val long = signal.type=="long"
        val tp = entry * if(long) 1.05 else 0.95
        val sl = entry * if(long) 0.98 else 1.02
        var status = "open"
        var exitPrice = candles.last().close
        var exitTime = candles.last().time
        for(j in entryIndex until candles.size) {
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
        results.appendText(listOf(bars[i+1].time,signal.strength,signal.type,signal.pattern,entry,exitTime,exitPrice,status,ret).joinToString(",")+"\n")
    }
    report.writeText("BTCUSDT M5 all signal strengths; entries Oct 10-Nov 8 UTC; exit-only Nov 9-22 UTC; independent overlapping trades; TP 5%, SL 2%; no fees; next-open entry; open positions marked at last close, not realized; ambiguous candles conservatively SL; M5 flow inputs disabled as in app; M1 exit checks; approximate live signal timing.\n")
    for((key,n) in counts.toSortedMap()) report.appendText("$key: count=$n, sumReturnPct=${pnl[key]}\n")
    print(report.readText())
}
